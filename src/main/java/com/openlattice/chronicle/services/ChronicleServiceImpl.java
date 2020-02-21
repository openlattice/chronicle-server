/*
 * Copyright (C) 2018. OpenLattice, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * You can contact the owner of the copyright at support@openlattice.com
 */

package com.openlattice.chronicle.services;

import com.dataloom.streams.StreamUtil;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.*;
import com.google.common.eventbus.EventBus;
import com.openlattice.ApiUtil;
import com.openlattice.chronicle.ChronicleServerUtil;
import com.openlattice.chronicle.configuration.ChronicleConfiguration;
import com.openlattice.chronicle.constants.ParticipationStatus;
import com.openlattice.chronicle.sources.AndroidDevice;
import com.openlattice.chronicle.sources.Datasource;
import com.openlattice.client.ApiClient;
import com.openlattice.data.*;
import com.openlattice.data.requests.NeighborEntityDetails;
import com.openlattice.directory.PrincipalApi;
import com.openlattice.edm.EdmApi;
import com.openlattice.edm.EntitySet;
import com.openlattice.entitysets.EntitySetsApi;
import com.openlattice.search.SearchApi;
import com.openlattice.search.requests.EntityNeighborsFilter;
import com.openlattice.search.requests.SearchTerm;
import com.openlattice.shuttle.MissionControl;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.openlattice.chronicle.ChronicleServerUtil.PARTICIPANTS_PREFIX;
import static com.openlattice.edm.EdmConstants.ID_FQN;

public class ChronicleServiceImpl implements ChronicleService {
    protected static final Logger logger = LoggerFactory.getLogger( ChronicleServiceImpl.class );

    // studyId -> participantId -> deviceID -> device EKID
    private final Map<UUID, Map<String, Map<String, UUID>>> studyDevices  = new HashMap<>();

    // studyId -> participantId -> participant EKID
    private final Map<UUID, Map<String, UUID>> studyParticipants = new HashMap<>();

    // studyId -> study EKID
    private final Map<UUID, UUID> studies = new HashMap<>();

    private final String username;
    private final String password;

    private final EventBus eventBus;
    private final String   STUDY_ENTITY_SET_NAME       = "chronicle_study";
    private final String   DEVICES_ENTITY_SET_NAME     = "chronicle_device";
    private final String   DATA_ENTITY_SET_NAME        = "chronicle_app_data";
    private final String   PREPROCESSED_DATA_ENTITY_SET_NAME = "chronicle_preprocessed_app_data";
    private final String   RECORDED_BY_ENTITY_SET_NAME = "chronicle_recorded_by";
    private final String   USED_BY_ENTITY_SET_NAME     = "chronicle_used_by";
    private final String   PARTICIPATED_IN_AESN        = "chronicle_participated_in";
    private final String   SEARCH_PREFIX               = "entity";

    private final Set<UUID>         dataKey;
    private final FullQualifiedName STRING_ID_FQN   = new FullQualifiedName( "general.stringid" );
    private final FullQualifiedName PERSON_ID_FQN   = new FullQualifiedName( "nc.SubjectIdentification" );
    private final FullQualifiedName DATE_LOGGED_FQN = new FullQualifiedName( "ol.datelogged" );
    private final FullQualifiedName STATUS_FQN      = new FullQualifiedName( "ol.status" );
    private final FullQualifiedName VERSION_FQN     = new FullQualifiedName( "ol.version" );
    private final FullQualifiedName MODEL_FQN       = new FullQualifiedName( "vehicle.model" );
    private final UUID              studyEntitySetId;
    private final UUID              deviceEntitySetId;
    private final UUID              dataEntitySetId;
    private final UUID              preProcessedEntitySetId;
    private final UUID              recordedByEntitySetId;
    private final UUID              usedByEntitySetId;
    private final UUID              participatedInEntitySetId;
    private final UUID              stringIdPropertyTypeId;
    private final UUID              participantIdPropertyTypeId;
    private final UUID              dateLoggedPropertyTypeId;
    private final UUID              versionPropertyTypeId;
    private final UUID              modelPropertyTypeId;

    private transient LoadingCache<Class<?>, ApiClient> apiClientCache = null;

    public ChronicleServiceImpl(
            EventBus eventBus,
            ChronicleConfiguration chronicleConfiguration ) throws ExecutionException {
        this.eventBus = eventBus;
        this.username = chronicleConfiguration.getUser();
        this.password = chronicleConfiguration.getPassword();

        apiClientCache = CacheBuilder
                .newBuilder()
                .expireAfterWrite( 10, TimeUnit.HOURS )
                .build( new CacheLoader<Class<?>, ApiClient>() {
                    @Override
                    public ApiClient load( Class<?> key ) throws Exception {
                        String jwtToken = MissionControl.getIdToken( username, password );
                        return new ApiClient( () -> jwtToken );
                    }
                } );

        ApiClient apiClient = apiClientCache.get( ApiClient.class );

        EdmApi edmApi = apiClient.getEdmApi();
        EntitySetsApi entitySetsApi = apiClient.getEntitySetsApi();

        studyEntitySetId = entitySetsApi.getEntitySetId( STUDY_ENTITY_SET_NAME );
        deviceEntitySetId = entitySetsApi.getEntitySetId( DEVICES_ENTITY_SET_NAME );
        dataEntitySetId = entitySetsApi.getEntitySetId( DATA_ENTITY_SET_NAME );
        preProcessedEntitySetId = entitySetsApi.getEntitySetId(PREPROCESSED_DATA_ENTITY_SET_NAME);
        recordedByEntitySetId = entitySetsApi.getEntitySetId( RECORDED_BY_ENTITY_SET_NAME );
        usedByEntitySetId = entitySetsApi.getEntitySetId( USED_BY_ENTITY_SET_NAME );
        participatedInEntitySetId = entitySetsApi.getEntitySetId( PARTICIPATED_IN_AESN );

        stringIdPropertyTypeId = edmApi.getPropertyTypeId( STRING_ID_FQN.getNamespace(), STRING_ID_FQN.getName() );
        participantIdPropertyTypeId = edmApi.getPropertyTypeId( PERSON_ID_FQN.getNamespace(), PERSON_ID_FQN.getName() );
        dateLoggedPropertyTypeId = edmApi
                .getPropertyTypeId( DATE_LOGGED_FQN.getNamespace(), DATE_LOGGED_FQN.getName() );
        versionPropertyTypeId = edmApi.getPropertyTypeId( VERSION_FQN.getNamespace(), VERSION_FQN.getName() );
        modelPropertyTypeId = edmApi.getPropertyTypeId( MODEL_FQN.getNamespace(), MODEL_FQN.getName() );

        dataKey = edmApi.getEntityType( entitySetsApi.getEntitySet( dataEntitySetId ).getEntityTypeId() ).getKey();

        refreshStudyInformation();

    }

    private UUID getEntityKeyIdAndUpdate(
            UUID entitySetId,
            UUID keyPropertyTypeId,
            Map<UUID, Set<Object>> data,
            DataIntegrationApi dataIntegrationApi,
            DataApi dataApi ) {

        UUID entityKeyId = dataIntegrationApi.getEntityKeyIds( ImmutableSet.of( new EntityKey(
                entitySetId,
                ApiUtil.generateDefaultEntityId( ImmutableList.of( keyPropertyTypeId ), data )
        ) ) ).iterator().next();

        dataApi.updateEntitiesInEntitySet( entitySetId,
                ImmutableMap.of( entityKeyId, data ),
                UpdateType.Merge );

        return entityKeyId;

    }

    private UUID reserveDeviceEntityKeyId(
            String deviceId,
            Optional<Datasource> datasource,
            DataIntegrationApi dataIntegrationApi,
            DataApi dataApi ) {

        Map<UUID, Set<Object>> deviceData = new HashMap<>();

        deviceData.put( stringIdPropertyTypeId, Sets.newHashSet( deviceId ) );

        if ( datasource.isPresent() && AndroidDevice.class.isAssignableFrom( datasource.get().getClass() ) ) {
            AndroidDevice device = (AndroidDevice) datasource.get();
            deviceData.put( modelPropertyTypeId, Sets.newHashSet( device.getModel() ) );
            deviceData.put( versionPropertyTypeId, Sets.newHashSet( device.getOsVersion() ) );
        }

        return getEntityKeyIdAndUpdate(
                deviceEntitySetId,
                stringIdPropertyTypeId,
                deviceData,
                dataIntegrationApi,
                dataApi
        );

    }

    private UUID getStudyEntityKeyId( UUID studyId ) {
        logger.info("Retrieving studyEntityKeyId, studyId = {}", studyId);
        if (studies.containsKey(studyId)) {
            return studies.get(studyId);
        }

        logger.error("Failed to retrieve studyEntityKeyId, studyId = {}", studyId);
        return null;
    }

    private UUID getParticipantEntitySetId( UUID studyId ) {
        EntitySetsApi entitySetsApi;
        try {
            ApiClient apiClient = apiClientCache.get( ApiClient.class );
            entitySetsApi = apiClient.getEntitySetsApi();
        } catch ( ExecutionException e ) {
            logger.error( "Unable to load apis." );
            return null;
        }

        return entitySetsApi.getEntitySetId(
                ChronicleServerUtil.getParticipantEntitySetName( studyId )
        );

    }

    private UUID getParticipantEntityKeyId (String participantId, UUID studyId) {
        if (studyParticipants.containsKey(studyId)) {
            Map<String, UUID> participantIdToEKMap = studyParticipants.get(studyId);

            if (participantIdToEKMap.containsKey(participantId)) {
                return participantIdToEKMap.get(participantId);
            } else {
                logger.error("Unable to get participantEntityKeyId. participant {} not associated with studId {} ", participantId, studyId);
            }
        } else {
            logger.error("Unable to get participantEntityKeyId of participantId {}. StudyId {}  not found ", participantId, studyId);
        }

        return null;
    }

    //  TODO: add in throws exception!
    @Override
    public Integer logData(
            UUID studyId,
            String participantId,
            String deviceId,
            List<SetMultimap<UUID, Object>> data ) {

        DataApi dataApi;
        try {
            ApiClient apiClient = apiClientCache.get( ApiClient.class );
            dataApi = apiClient.getDataApi();
        } catch ( ExecutionException e ) {
            logger.error( "Unable to load apis." );
            return 0;
        }

        ListMultimap<UUID, Map<UUID, Set<Object>>> entities = ArrayListMultimap.create();
        ListMultimap<UUID, DataAssociation> associations = ArrayListMultimap.create();

        UUID participantEntitySetId = getParticipantEntitySetId( studyId );
        UUID deviceEntityKeyId = getDeviceEntityKeyId(studyId, participantId, deviceId);

        UUID participantEntityKeyId = getParticipantEntityKeyId( participantId, studyId );
        if (participantEntityKeyId == null) {
            logger.error("Unable to retrieve participantEntityKeyId, studyId = {}, participantId = {}", studyId, participantId);
            return 0;
        }

        ParticipationStatus status = getParticipationStatus( studyId, participantId );
        if ( ParticipationStatus.NOT_ENROLLED.equals( status ) ) {
            logger.warn( "participantId = {} is not enrolled, ignoring data upload", participantId );
            return 0;
        }

        OffsetDateTime timestamp = OffsetDateTime.now();

        for ( int i = 0; i < data.size(); i++ ) {
            entities.put( dataEntitySetId, Multimaps.asMap( data.get( i ) ) );

            Map<UUID, Set<Object>> recordedByEntity = ImmutableMap
                    .of( dateLoggedPropertyTypeId, Sets.newHashSet( timestamp ) );

            associations.put( recordedByEntitySetId, new DataAssociation(
                    dataEntitySetId,
                    java.util.Optional.of( i ),
                    java.util.Optional.empty(),
                    deviceEntitySetId,
                    java.util.Optional.empty(),
                    java.util.Optional.of( deviceEntityKeyId ),
                    recordedByEntity
            ) );

            associations.put( recordedByEntitySetId, new DataAssociation(
                    dataEntitySetId,
                    java.util.Optional.of( i ),
                    java.util.Optional.empty(),
                    participantEntitySetId,
                    java.util.Optional.empty(),
                    java.util.Optional.of( participantEntityKeyId ),
                    recordedByEntity
            ) );
        }

        dataApi.createEntityAndAssociationData( new DataGraph( entities, associations ) );

        //  TODO:s Make sure to return any errors??? Currently void method.
        return data.size();
    }

    @Override
    public UUID registerDatasource(
            UUID studyId,
            String participantId,
            String datasourceId,
            Optional<Datasource> datasource ) {

        //  previous logic already verified the participant and that the device is not already connected.
        //  add the device and associate to the participant and to the study
        //  this will be two associations device --> person, device --> study
        //  aka write a line to these association tables, createAssociationData() does not exist in lattice-js yet.
        //  DataApi.createEntityAndAssociationData() see example above for template

        DataApi dataApi;
        DataIntegrationApi dataIntegrationApi;
        try {
            ApiClient apiClient = apiClientCache.get( ApiClient.class );
            dataApi = apiClient.getDataApi();
            dataIntegrationApi = apiClient.getDataIntegrationApi();
        } catch ( ExecutionException e ) {
            logger.error( "Unable to load apis." );
            return null;
        }

        UUID deviceEntityKeyId = reserveDeviceEntityKeyId( datasourceId, datasource, dataIntegrationApi, dataApi );
        if (deviceEntityKeyId == null) {
            logger.error("Unable to reserve deviceEntityKeyId, dataSourceId = {}, studyId = {}, participantId = {}", datasourceId, studyId, participantId);
            return null;
        }
        EntityDataKey deviceEDK = new EntityDataKey( deviceEntitySetId, deviceEntityKeyId );
        studyDevices.computeIfAbsent( studyId, key -> new HashMap<>() )
                .put( participantId, Map.of(datasourceId, deviceEntityKeyId) );

        UUID participantEntitySetId = getParticipantEntitySetId( studyId );
        UUID participantEntityKeyId = getParticipantEntityKeyId( participantId, studyId );
        if (participantEntityKeyId == null) {
            logger.error("Unable to retrieve participantEntityKeyId, studyId = {}, participantId = {}", studyId, participantId);
            return null;
        }
        EntityDataKey participantEDK = new EntityDataKey( participantEntitySetId, participantEntityKeyId );

        UUID studyEntityKeyId = getStudyEntityKeyId( studyId );
        if (studyEntityKeyId == null) {
            logger.error("Unable to retrieve studyEntityKeyId, studyId = {}", studyId);
            return null;
        }
        EntityDataKey studyEDK = new EntityDataKey( studyEntitySetId, studyEntityKeyId );

        ListMultimap<UUID, DataEdge> associations = ArrayListMultimap.create();

        Map<UUID, Set<Object>> usedByEntity = ImmutableMap
                .of( stringIdPropertyTypeId, Sets.newHashSet( UUID.randomUUID() ) );

        associations.put( usedByEntitySetId, new DataEdge( deviceEDK, participantEDK, usedByEntity ) );
        associations.put( usedByEntitySetId, new DataEdge( deviceEDK, studyEDK, usedByEntity ) );

        dataApi.createAssociations( associations );

        return deviceEntityKeyId;
    }

    @Override
    public UUID getDeviceEntityKeyId( UUID studyId, String participantId, String datasourceId ) {
        logger.info("Getting device entity key id, studyId = {}, participantId = {}, datasourceId = {}", studyId, participantId, datasourceId);

        if (isKnownDatasource(studyId, participantId, datasourceId)) {
            return studyDevices.get(studyId).get(participantId).get(datasourceId);
        }
        return null;
    }

    @Override
    public boolean isKnownDatasource( UUID studyId, String participantId, String datasourceId ) {

        logger.info( "Checking isKnownDatasource, studyId = {}, participantId = {}", studyId, participantId );

        Map<String, Map<String, UUID>> participantDevices = Preconditions
                    .checkNotNull(studyDevices.get(studyId), "Study must exist");

        return participantDevices.containsKey(participantId)
                    && participantDevices.get(participantId).containsKey(datasourceId);

    }

    @Override
    public boolean isKnownParticipant( UUID studyId, String participantId ) {
        return studyParticipants.getOrDefault(studyId, new HashMap<>()).containsKey(participantId);
    }

    @Override
    public Map<String, UUID> getPropertyTypeIds( Set<String> propertyTypeFqns ) {
        EdmApi edmApi;
        try {
            ApiClient apiClient = apiClientCache.get( ApiClient.class );
            edmApi = apiClient.getEdmApi();
        } catch ( ExecutionException e ) {
            logger.error( "Unable to load EdmApi" );
            return ImmutableMap.of();
        }

        return propertyTypeFqns.stream().map( fqn -> new FullQualifiedName( fqn ) ).map( fqn -> Pair
                .of( fqn.getFullQualifiedNameAsString(),
                        edmApi.getPropertyTypeId( fqn.getNamespace(), fqn.getName() ) ) )
                .collect( Collectors.toMap( pair -> pair.getLeft(), pair -> pair.getRight() ) );
    }

    @Scheduled( fixedRate = 60000 )
    public void refreshStudyInformation() {
        EntitySetsApi entitySetsApi;
        SearchApi searchApi;
        PrincipalApi principalApi;
        try {
            ApiClient apiClient = apiClientCache.get( ApiClient.class );
            entitySetsApi = apiClient.getEntitySetsApi();
            searchApi = apiClient.getSearchApi();
            principalApi = apiClient.getPrincipalApi();
        } catch ( ExecutionException e ) {
            logger.error( "Unable to load apis." );
            return;
        }

        try {
            logger.info( "attempting to sync user..." );
            principalApi.syncCallingUser();
        } catch ( Exception e ) {
            logger.error( "failed to sync user" );
        }

        logger.info( "Refreshing study info..." );

        Map<UUID, Map<String, UUID>> studyParticipants = new HashMap<>();
        Map<UUID, Map<String, Map<String, UUID>>> studyDevices = new HashMap<>();
        Map<UUID, UUID> studies = new HashMap<>();

        List<Map<FullQualifiedName, Set<Object>>> studySearchResult = searchApi
                .executeEntitySetDataQuery( studyEntitySetId, new SearchTerm( "*", 0, SearchApi.MAX_SEARCH_RESULTS ) )
                .getHits();

        if (studySearchResult.isEmpty()) {
            logger.info("No studies found.");
            return;
        }

        Set<UUID> studyEntityKeyIds = studySearchResult.stream()
                .map( study -> UUID.fromString( study.get( ID_FQN ).iterator().next().toString() ) )
                .collect( Collectors.toSet() );

        Set<UUID> participantEntitySetIds = StreamUtil.stream( entitySetsApi.getEntitySets() )
                .filter( entitySet -> entitySet.getName().startsWith( PARTICIPANTS_PREFIX ) )
                .map( EntitySet::getId )
                .collect( Collectors.toSet() );

        Map<UUID, List<NeighborEntityDetails>> studyNeighbors = searchApi.executeFilteredEntityNeighborSearch(
                studyEntitySetId,
                new EntityNeighborsFilter(
                        studyEntityKeyIds,
                        java.util.Optional.of( participantEntitySetIds ),
                        java.util.Optional.of( ImmutableSet.of() ),
                        java.util.Optional.of( ImmutableSet.of( participatedInEntitySetId ) )
                )
        );

        SetMultimap<UUID, UUID> participantEntityKeysByEntitySetId = Multimaps
                .synchronizedSetMultimap( HashMultimap.create() );

        studyNeighbors
                .values()
                .stream()
                .flatMap( Collection::stream )
                .parallel()
                .filter( neighbor -> neighbor.getNeighborEntitySet().isPresent() && neighbor.getNeighborId()
                        .isPresent() )
                .filter( neighbor -> neighbor.getNeighborEntitySet().get().getName().startsWith( PARTICIPANTS_PREFIX ) )
                .forEach( neighbor -> {
                    UUID participantEntitySetId = neighbor.getNeighborEntitySet().get().getId();
                    UUID participantEntityKeyId = neighbor.getNeighborId().get();
                    participantEntityKeysByEntitySetId.put( participantEntitySetId, participantEntityKeyId );
                } );

        Map<UUID, List<NeighborEntityDetails>> participantNeighbors = Maps.newConcurrentMap();

        participantEntityKeysByEntitySetId.asMap().entrySet().stream().parallel().forEach( entry -> participantNeighbors
                .putAll( searchApi
                        .executeFilteredEntityNeighborSearch( entry.getKey(),
                                new EntityNeighborsFilter( Sets.newHashSet( entry.getValue() ),
                                        java.util.Optional.of( ImmutableSet.of( deviceEntitySetId ) ),
                                        java.util.Optional.of( ImmutableSet.of() ),
                                        java.util.Optional.empty() ) ) ) );

        // populate study information

        studySearchResult.forEach(studyObj -> {
            Map<String, Map<String, UUID>> participantsToDevices = new HashMap<>();
            UUID studyId, studyEntityKeyId;

            try {
                studyId = UUID.fromString( studyObj.get( STRING_ID_FQN ).iterator().next().toString() );
                studyEntityKeyId = UUID.fromString( studyObj.get( ID_FQN ).iterator().next().toString() );
            } catch ( IllegalArgumentException error) {
                logger.error("invalid studyId : {}", studyObj.get( STRING_ID_FQN ).iterator().next().toString());
                return; // skip the current study object
            }

            if (studies.containsKey(studyId)) {
                logger.error("encountered duplicate studyId = {}", studyId);
            } else {
                studies.put(studyId, studyEntityKeyId);
                studyParticipants.put(studyId, new HashMap<>());
            }

            if ( studyNeighbors.containsKey( studyEntityKeyId ) ) {
                studyNeighbors.get( studyEntityKeyId ).stream()
                        .filter( neighbor -> neighbor.getNeighborEntitySet().isPresent() && neighbor
                                .getNeighborEntitySet()
                                .get()
                                .getName()
                                .startsWith( PARTICIPANTS_PREFIX )
                        )
                        .forEach( participantNeighbor -> {

                            Set<Object> participantIds = participantNeighbor.getNeighborDetails().get()
                                    .get( PERSON_ID_FQN );
                            if ( participantIds.size() > 0 ) {

                                String participantId = participantIds.iterator().next().toString();
                                UUID participantEntityKeyId = participantNeighbor.getNeighborId().get();

                                if (studyParticipants.containsKey(studyId) && studyParticipants.get(studyId).containsKey(participantId)) {
                                    logger.error("Encountered duplicate participantId = {} in studyId = {}", participantId, studyId);
                                } else{
                                    studyParticipants.get(studyId).put(participantId, participantEntityKeyId);
                                    if ( participantNeighbors.containsKey( participantEntityKeyId ) ) {
                                        Map<String, UUID> devices = new HashMap<>();
                                        participantNeighbors
                                                .get(participantEntityKeyId)
                                                .stream()
                                                .filter(neighbor -> neighbor.getNeighborEntitySet().isPresent() && neighbor
                                                    .getNeighborEntitySet()
                                                    .get()
                                                    .getName()
                                                    .equals(DEVICES_ENTITY_SET_NAME)
                                                ).forEach (neighbor -> {
                                                    if (neighbor.getNeighborDetails().isPresent()) {
                                                        String deviceId = neighbor.getNeighborDetails().get().get(STRING_ID_FQN).iterator().next().toString();
                                                        UUID deviceEntityKeyId = UUID.fromString(neighbor.getNeighborDetails().get().get(ID_FQN).iterator().next().toString());
                                                        devices.put(deviceId, deviceEntityKeyId);
                                                    }

                                                 });


                                        participantsToDevices.put( participantId, devices );
                                    }
                                }
                            }
                        } );
            }

            studyDevices.put( studyId, participantsToDevices );
        } );

        this.studies.clear();
        this.studies.putAll( studies );
        logger.info( "Updated studyInformation. Size = {}", this.studies.size() );

        this.studyParticipants.clear();
        this.studyParticipants.putAll( studyParticipants );
        logger.info( "Updated studyParticipants. Size = {}", this.studyParticipants.values().stream().flatMap(map -> map.values().stream()).count());

        this.studyDevices.clear();
        this.studyDevices.putAll(studyDevices);
    }

    private Iterable<Map<String, Set<Object>>> getParticipantDataHelper(
            UUID studyId,
            UUID participantEntityKeyId,
            String entitySetName,
            String token ) {
        try {

            ApiClient apiClient = new ApiClient( () -> token );
            EntitySetsApi entitySetsApi = apiClient.getEntitySetsApi();
            SearchApi searchApi = apiClient.getSearchApi();

            String participantEntitySetName = ChronicleServerUtil.getParticipantEntitySetName( studyId );
            UUID entitySetId = entitySetsApi.getEntitySetId( participantEntitySetName );
            if ( entitySetId == null ) {
                logger.error( "Unable to load participant EntitySet id." );
                return null;
            }

            List<NeighborEntityDetails> participantNeighbors = searchApi
                    .executeEntityNeighborSearch( entitySetId, participantEntityKeyId );

            return participantNeighbors
                    .stream()
                    .filter( neighbor -> neighbor.getNeighborDetails().isPresent()
                            && neighbor.getNeighborEntitySet().isPresent()
                            && entitySetName.equals( neighbor.getNeighborEntitySet().get().getName() )
                    )
                    .map( neighbor -> {
                        neighbor.getNeighborDetails().get().remove( ID_FQN );
                        Map<String, Set<Object>> neighborDetails = Maps.newHashMap();
                        neighbor.getNeighborDetails().get()
                                .forEach( ( key, value ) -> neighborDetails.put( key.toString(), value ) );
                        return neighborDetails;
                    } )
                    .collect( Collectors.toSet() );
        } catch ( ExecutionException e ) {
            logger.error( "Unable to load participant data.", e );
            return null;
        }
    }

    public Iterable<Map<String, Set<Object>>> getAllPreprocessedParticipantData(
            UUID studyId,
            UUID participatedInEntityKeyId,
            String token ) {
        return getParticipantDataHelper( studyId, participatedInEntityKeyId, PREPROCESSED_DATA_ENTITY_SET_NAME, token );
    }

    @Override
    public Iterable<Map<String, Set<Object>>> getAllParticipantData(
            UUID studyId,
            UUID participantEntityKeyId,
            String token ) {
        return getParticipantDataHelper( studyId, participantEntityKeyId, DATA_ENTITY_SET_NAME, token );
    }

    @Override
    @Nonnull
    public Map<FullQualifiedName, Set<Object>> getParticipantEntity( UUID studyId, UUID participantEntityKeyId ) {

        try {
            ApiClient apiClient = apiClientCache.get( ApiClient.class );
            DataApi dataApi = apiClient.getDataApi();
            EntitySetsApi entitySetsApi = apiClient.getEntitySetsApi();

            String entitySetName = ChronicleServerUtil.getParticipantEntitySetName( studyId );
            UUID entitySetId = entitySetsApi.getEntitySetId( entitySetName );
            if ( entitySetId == null ) {
                logger.error( "Unable to load participant EntitySet id." );
                return ImmutableMap.of();
            }

            Map<FullQualifiedName, Set<Object>> entity = dataApi.getEntity( entitySetId, participantEntityKeyId );
            if ( entity == null ) {
                logger.error( "Unable to get participant entity." );
                return ImmutableMap.of();
            }
            return entity;
        } catch ( ExecutionException e ) {
            logger.error( "Unable to get participant entity.", e );
            return ImmutableMap.of();
        }
    }

    @Override
    public ParticipationStatus getParticipationStatus( UUID studyId, String participantId ) {

        EntitySetsApi entitySetsApi;
        SearchApi searchApi;

        try {
            ApiClient apiClient = apiClientCache.get( ApiClient.class );
            entitySetsApi = apiClient.getEntitySetsApi();
            searchApi = apiClient.getSearchApi();
        } catch ( ExecutionException e ) {
            logger.error( "Unable to load apis." );
            return ParticipationStatus.UNKNOWN;
        }

        String participantsEntitySetName = ChronicleServerUtil.getParticipantEntitySetName( studyId );
        UUID participantsEntitySetId = entitySetsApi.getEntitySetId( participantsEntitySetName );
        if ( participantsEntitySetId == null ) {
            logger.error( "unable to get the participants EntitySet id for studyId = {}", studyId );
            return ParticipationStatus.UNKNOWN;
        }

        UUID participantEntityKeyId = getParticipantEntityKeyId( participantId, studyId );
        if ( participantEntityKeyId == null ) {
            logger.error( "unable to load participant entity key id for participantId = {}", participantId );
            return ParticipationStatus.UNKNOWN;
        }

        Map<UUID, List<NeighborEntityDetails>> neighborResults = searchApi.executeFilteredEntityNeighborSearch(
                participantsEntitySetId,
                new EntityNeighborsFilter(
                        ImmutableSet.of( participantEntityKeyId ),
                        java.util.Optional.of( ImmutableSet.of() ),
                        java.util.Optional.of( ImmutableSet.of( studyEntitySetId ) ),
                        java.util.Optional.of( ImmutableSet.of( participatedInEntitySetId ) )
                )
        );

        Set<Map<FullQualifiedName, Set<Object>>> target = neighborResults
                .getOrDefault(participantEntityKeyId, ImmutableList.of())
                .stream()
                .filter( neighborResult -> {
                    if ( neighborResult.getNeighborDetails().isPresent() ) {
                        try {
                            Set<Object> data = neighborResult.getNeighborDetails().get().get( STRING_ID_FQN );
                            UUID expectedStudyId = UUID.fromString( data.iterator().next().toString() );
                            return studyId.equals( expectedStudyId );
                        } catch ( Exception e ) {
                            logger.error( "caught exception while filtering by study id", e );
                        }
                    }
                    return false;
                } )
                .map( NeighborEntityDetails::getAssociationDetails )
                .collect( Collectors.toSet() );

        if ( target.size() == 1 ) {
            try {
                Map<FullQualifiedName, Set<Object>> data = target.iterator().next();
                if ( data.containsKey( STATUS_FQN ) ) {
                    Set<Object> statusValue = data.get( STATUS_FQN );
                    if ( statusValue != null && statusValue.size() == 1 ) {
                        String status = statusValue.iterator().next().toString();
                        return ParticipationStatus.valueOf( status );
                    }
                }
            } catch ( Exception e ) {
                logger.error( "unable to determine participation status", e );
            }
        } else {
            logger.error( "only one edge is expected between the participant and the study, found {}", target.size() );
        }

        return ParticipationStatus.UNKNOWN;
    }
}
