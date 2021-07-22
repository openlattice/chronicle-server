package com.openlattice.chronicle.services.entitysets;

import com.dataloom.streams.StreamUtil;
import com.google.common.collect.*;
import com.openlattice.apps.App;
import com.openlattice.apps.AppApi;
import com.openlattice.apps.UserAppConfig;
import com.openlattice.authorization.securable.AbstractSecurableObject;
import com.openlattice.chronicle.constants.*;
import com.openlattice.chronicle.data.ChronicleCoreAppConfig;
import com.openlattice.chronicle.data.ChronicleDataCollectionAppConfig;
import com.openlattice.chronicle.data.ChronicleSurveysAppConfig;
import com.openlattice.chronicle.services.ApiCacheManager;
import com.openlattice.chronicle.services.edm.EdmCacheManager;
import com.openlattice.chronicle.util.ChronicleServerUtil;
import com.openlattice.client.ApiClient;
import com.openlattice.collections.CollectionTemplateType;
import com.openlattice.collections.CollectionsApi;
import com.openlattice.collections.EntitySetCollection;
import com.openlattice.collections.EntityTypeCollection;
import com.openlattice.data.DataApi;
import com.openlattice.data.requests.EntitySetSelection;
import com.openlattice.data.requests.FileType;
import com.openlattice.entitysets.EntitySetsApi;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static com.openlattice.chronicle.constants.AppComponent.CHRONICLE;
import static com.openlattice.chronicle.constants.AppComponent.CHRONICLE_DATA_COLLECTION;
import static com.openlattice.chronicle.constants.AppComponent.CHRONICLE_SURVEYS;
import static com.openlattice.chronicle.constants.CollectionTemplateTypeName.*;
import static com.openlattice.chronicle.constants.EdmConstants.LEGACY_DATASET_COLLECTION_TEMPLATE_MAP;
import static com.openlattice.chronicle.constants.EdmConstants.STRING_ID_FQN;
import static com.openlattice.chronicle.util.ChronicleServerUtil.getFirstUUIDOrNull;

/**
 * @author alfoncenzioka &lt;alfonce@openlattice.com&gt;
 */
public class EntitySetIdsService implements EntitySetIdsManager {
    protected static final Logger logger = LoggerFactory.getLogger( EntitySetIdsService.class );

    private final long ENTITY_SETS_REFRESH_INTERVAL     = 15 * 60 * 1000; // 15 minutes
    private final long PARTICIPANTS_ES_REFRESH_INTERVAL = 60 * 1000; // i minute

    // appName -> orgId -> templateName -> entitySetID
    private final Map<AppComponent, Map<UUID, Map<CollectionTemplateTypeName, UUID>>> entitySetIdsByOrgId = Maps
            .newHashMap();

    private final Map<String, UUID>                     legacyParticipantsEntitySetIds = Maps
            .newHashMap();
    private final Map<CollectionTemplateTypeName, UUID> legacyEntitySetIds             = Maps
            .newHashMap();

    // appComponent -> orgId -> settings
    private final Map<AppComponent, Map<UUID, Map<String, Object>>> appSettings = Maps.newHashMapWithExpectedSize(AppComponent.values().length );

    private final Map<AppComponent, Map<String, Object>> defaultAppSettings = Maps.newHashMapWithExpectedSize( AppComponent.values().length );

    private final ApiCacheManager apiCacheManager;
    private final EdmCacheManager edmCacheManager;

    public EntitySetIdsService( ApiCacheManager apiCacheManager, EdmCacheManager edmCacheManager )
            throws ExecutionException {
        this.apiCacheManager = apiCacheManager;
        this.edmCacheManager = edmCacheManager;

        ApiClient prodApiClient = apiCacheManager.prodApiClientCache.get( ApiClient.class );
        EntitySetsApi entitySetsApi = prodApiClient.getEntitySetsApi();

        // get legacy entity set ids
        Set<String> entitySetNames = LEGACY_DATASET_COLLECTION_TEMPLATE_MAP.keySet();
        Map<String, UUID> entitySetIds = entitySetsApi.getEntitySetIds( entitySetNames );

        Map<CollectionTemplateTypeName, UUID> legacyEntitySetIds = Maps
                .newHashMapWithExpectedSize( entitySetIds.size() );
        entitySetIds.forEach( ( esName, esId ) -> {
            legacyEntitySetIds.put( LEGACY_DATASET_COLLECTION_TEMPLATE_MAP.get( esName ), esId );
        } );

        this.legacyEntitySetIds.putAll( legacyEntitySetIds );

        refreshOrgsEntitySets();
        refreshLegacyParticipantEntitySetIds();
    }

    @Scheduled( fixedRate = ENTITY_SETS_REFRESH_INTERVAL )
    public void refreshOrgsEntitySets() throws ExecutionException {
        logger.info( "Refreshing entity set ids map" );

        ApiClient apiClient = apiCacheManager.prodApiClientCache.get( ApiClient.class );
        AppApi appApi = apiClient.getAppApi();
        CollectionsApi collectionsApi = apiClient.getCollectionsApi();

        // create a app -> appId mapping
        Map<AppComponent, UUID> appNameIdMap = Maps.newHashMapWithExpectedSize( AppComponent.values().length );
        for ( AppComponent component : AppComponent.values() ) {
            App app = appApi.getAppByName( component.toString() );
            appNameIdMap.put( component, app.getId() );
            defaultAppSettings.put( component, app.getDefaultSettings() );
        }

        Map<AppComponent, Map<UUID, Map<CollectionTemplateTypeName, UUID>>> entitySets = new HashMap<>();

        // get configs for each app
        appNameIdMap.forEach( ( appComponent, appId ) -> {
            List<UserAppConfig> configs = appApi.getAvailableAppConfigs( appId );

            if ( configs.isEmpty() ) {
                return;
            }

            // get EntityTypeCollection associated with app
            EntitySetCollection entitySetCollection = collectionsApi
                    .getEntitySetCollection( configs.get( 0 ).getEntitySetCollectionId() );
            EntityTypeCollection entityTypeCollection = collectionsApi
                    .getEntityTypeCollection( entitySetCollection.getEntityTypeCollectionId() );

            // create mapping from templateTypeName -> templateTypeId
            Map<String, UUID> templateTypeNameIdMap = entityTypeCollection
                    .getTemplate()
                    .stream()
                    .collect( Collectors.toMap( CollectionTemplateType::getName, AbstractSecurableObject::getId ) );

            // for each config map orgId -> templateTypeName -> ESID
            Map<UUID, Map<CollectionTemplateTypeName, UUID>> orgEntitySetMap = new HashMap<>();

            // orgId -> settings
            Map<UUID, Map<String, Object>> settings = Maps.newHashMap();

            configs.forEach( userAppConfig -> {
                Map<UUID, UUID> templateTypeIdESIDMap = collectionsApi
                        .getEntitySetCollection( userAppConfig.getEntitySetCollectionId() ).getTemplate();

                // iterate over templateTypeName enums and create mapping templateTypeName -> entitySetId
                Map<CollectionTemplateTypeName, UUID> templateTypeNameESIDMap = Maps
                        .newHashMapWithExpectedSize( templateTypeIdESIDMap.size() );

                for ( CollectionTemplateTypeName templateTypeName : CollectionTemplateTypeName.values() ) {
                    UUID templateTypeId = templateTypeNameIdMap.get( templateTypeName.toString() );

                    if ( templateTypeId != null ) {
                        templateTypeNameESIDMap.put( templateTypeName, templateTypeIdESIDMap.get( templateTypeId ) );
                    }
                }
                orgEntitySetMap.put( userAppConfig.getOrganizationId(), templateTypeNameESIDMap );

                settings.put( userAppConfig.getOrganizationId(), userAppConfig.getSettings() );
            } );

            appSettings.put( appComponent, settings );
            entitySets.put( appComponent, orgEntitySetMap );
        } );

        entitySetIdsByOrgId.putAll( entitySets );
    }

    @Deprecated
    @Scheduled( fixedRate = PARTICIPANTS_ES_REFRESH_INTERVAL )
    public void refreshLegacyParticipantEntitySetIds() {
        logger.info( "refreshing participant entityset ids for legacy studies" );

        try {
            ApiClient apiClient = apiCacheManager.prodApiClientCache.get( ApiClient.class );
            DataApi dataApi = apiClient.getDataApi();
            EntitySetsApi entitySetsApi = apiClient.getEntitySetsApi();

            ChronicleCoreAppConfig coreAppConfig = getLegacyChronicleAppConfig();

            Iterable<SetMultimap<FullQualifiedName, Object>> studies = dataApi.loadSelectedEntitySetData(
                    coreAppConfig.getStudiesEntitySetId(),
                    new EntitySetSelection(
                            Optional.of( ImmutableSet.of( edmCacheManager.getPropertyTypeId( STRING_ID_FQN ) ) )
                    ),
                    FileType.json
            );

            Set<String> entitySetNames = StreamUtil.stream( studies )
                    .map( study -> getFirstUUIDOrNull( Multimaps.asMap( study ), STRING_ID_FQN ) )
                    .filter( Objects::nonNull )
                    .map( ChronicleServerUtil::getParticipantEntitySetName )
                    .collect( Collectors.toSet() );

            Map<String, UUID> entitySetIds = entitySetsApi.getEntitySetIds( entitySetNames );

            legacyParticipantsEntitySetIds.putAll( entitySetIds );

            logger.info( "loaded {} legacy participant entity set ids", entitySetIds.size() );

        } catch ( Exception e ) {
            logger.error( "error refreshing legacy participant entityset ids" );
        }
    }

    @Override
    public Map<AppComponent, Map<UUID, Map<CollectionTemplateTypeName, UUID>>> getEntitySetIdsByOrgId() {
        return entitySetIdsByOrgId;
    }

    // app settings
    @Override
    public Map<String, Object> getOrgAppSettings( AppComponent appComponent, UUID organizationId ) {
        Map<String, Object> settings = appSettings.getOrDefault( appComponent, ImmutableMap.of() )
                .getOrDefault( organizationId, ImmutableMap.of() );

        if (settings.isEmpty()) {
            return defaultAppSettings.get( appComponent );
        }

        return settings;
    }

    @Override
    public Map<String, Object> getOrgAppSettings( String appName, UUID organizationId ) {
        AppComponent component = AppComponent.fromString( appName );
        return getOrgAppSettings( component, organizationId );
    }

    public ChronicleCoreAppConfig getChronicleAppConfig( UUID organizationId ) {

        if ( organizationId == null ) {
            return getLegacyChronicleAppConfig();
        }

        Map<CollectionTemplateTypeName, UUID> templateEntitySetIdMap = getEntitySetIdsByOrgId()
                .getOrDefault( CHRONICLE, ImmutableMap.of() )
                .getOrDefault( organizationId, ImmutableMap.of() );

        if ( templateEntitySetIdMap.isEmpty() ) {
            logger.error( "organization {} does not have chronicle core app installed ", organizationId );
            return null;
        }

        return new ChronicleCoreAppConfig(
                templateEntitySetIdMap.get( HAS ),
                Optional.of( templateEntitySetIdMap.get( PARTICIPANTS ) ),
                templateEntitySetIdMap.get( PARTICIPATED_IN ),
                templateEntitySetIdMap.get( METADATA ),
                templateEntitySetIdMap.get( PART_OF ),
                templateEntitySetIdMap.get( NOTIFICATION ),
                templateEntitySetIdMap.get( STUDIES )
        );
    }

    public ChronicleCoreAppConfig getChronicleAppConfig( UUID organizationId, String participantESName ) {

        if ( organizationId == null ) {
            return getLegacyChronicleAppConfig( participantESName );
        }

        return getChronicleAppConfig( organizationId );
    }

    public ChronicleDataCollectionAppConfig getChronicleDataCollectionAppConfig( UUID organizationId ) {

        if ( organizationId == null ) {
            return getLegacyChronicleDataCollectionAppConfig();
        }
        Map<CollectionTemplateTypeName, UUID> templateEntitySetIdMap = getEntitySetIdsByOrgId()
                .getOrDefault( CHRONICLE_DATA_COLLECTION, ImmutableMap.of() )
                .getOrDefault( organizationId, ImmutableMap.of() );

        if ( templateEntitySetIdMap.isEmpty() ) {
            logger.error( "organization {} does not have chronicle data collection app installed ", organizationId );
            return null;
        }

        return new ChronicleDataCollectionAppConfig(
                templateEntitySetIdMap.get( APP_DICTIONARY ),
                templateEntitySetIdMap.get( RECORDED_BY ),
                templateEntitySetIdMap.get( DEVICE ),
                templateEntitySetIdMap.get( USED_BY ),
                templateEntitySetIdMap.get( USER_APPS ),
                templateEntitySetIdMap.get( PREPROCESSED_DATA ),
                templateEntitySetIdMap.get( APPDATA )
        );
    }

    public ChronicleSurveysAppConfig getChronicleSurveysAppConfig( UUID organizationId ) {

        if ( organizationId == null ) {
            return getLegacyChronicleSurveysAppConfig();
        }

        Map<CollectionTemplateTypeName, UUID> templateEntitySetIdMap = getEntitySetIdsByOrgId()
                .getOrDefault( CHRONICLE_SURVEYS, ImmutableMap.of() )
                .getOrDefault( organizationId, ImmutableMap.of() );

        if ( templateEntitySetIdMap.isEmpty() ) {
            logger.error( "organization {} does not have chronicle surveys app installed ", organizationId );
            return null;
        }

        return new ChronicleSurveysAppConfig(
                templateEntitySetIdMap.get( SURVEY ),
                templateEntitySetIdMap.get( TIME_RANGE ),
                templateEntitySetIdMap.get( SUBMISSION ),
                templateEntitySetIdMap.get( REGISTERED_FOR ),
                templateEntitySetIdMap.get( RESPONDS_WITH ),
                templateEntitySetIdMap.get( ADDRESSES ),
                templateEntitySetIdMap.get( ANSWER ),
                templateEntitySetIdMap.get( QUESTION )
        );
    }

    @Deprecated
    public ChronicleCoreAppConfig getLegacyChronicleAppConfig( String participantESName ) {

        UUID participantESID = legacyParticipantsEntitySetIds.getOrDefault( participantESName, null );
        Optional<UUID> optional = participantESID == null ? Optional.empty() : Optional.of( participantESID );

        return new ChronicleCoreAppConfig(
                legacyEntitySetIds.get( HAS ),
                optional,
                legacyEntitySetIds.get( PARTICIPATED_IN ),
                legacyEntitySetIds.get( METADATA ),
                legacyEntitySetIds.get( PART_OF ),
                legacyEntitySetIds.get( NOTIFICATION ),
                legacyEntitySetIds.get( STUDIES )
        );
    }

    @Deprecated
    public ChronicleCoreAppConfig getLegacyChronicleAppConfig() {

        return new ChronicleCoreAppConfig(
                legacyEntitySetIds.get( HAS ),
                Optional.empty(),
                legacyEntitySetIds.get( PARTICIPATED_IN ),
                legacyEntitySetIds.get( METADATA ),
                legacyEntitySetIds.get( PART_OF ),
                legacyEntitySetIds.get( NOTIFICATION ),
                legacyEntitySetIds.get( STUDIES )
        );
    }

    @Deprecated
    public ChronicleDataCollectionAppConfig getLegacyChronicleDataCollectionAppConfig() {
        return new ChronicleDataCollectionAppConfig(
                legacyEntitySetIds.get( APP_DICTIONARY ),
                legacyEntitySetIds.get( RECORDED_BY ),
                legacyEntitySetIds.get( DEVICE ),
                legacyEntitySetIds.get( USED_BY ),
                legacyEntitySetIds.get( USER_APPS ),
                legacyEntitySetIds.get( PREPROCESSED_DATA ),
                legacyEntitySetIds.get( APPDATA )
        );
    }

    @Deprecated
    public ChronicleSurveysAppConfig getLegacyChronicleSurveysAppConfig() {
        return new ChronicleSurveysAppConfig(
                legacyEntitySetIds.get( SURVEY ),
                legacyEntitySetIds.get( TIME_RANGE ),
                legacyEntitySetIds.get( SUBMISSION ),
                legacyEntitySetIds.get( REGISTERED_FOR ),
                legacyEntitySetIds.get( RESPONDS_WITH ),
                legacyEntitySetIds.get( ADDRESSES ),
                legacyEntitySetIds.get( ANSWER ),
                legacyEntitySetIds.get( QUESTION )
        );
    }
}
