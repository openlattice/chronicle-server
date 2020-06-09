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

import com.google.common.base.Optional;
import com.google.common.collect.SetMultimap;
import com.openlattice.chronicle.data.ChronicleAppsUsageDetails;
import com.openlattice.chronicle.data.ChronicleQuestionnaire;
import com.openlattice.chronicle.data.DeleteType;
import com.openlattice.chronicle.data.ParticipationStatus;
import com.openlattice.chronicle.sources.Datasource;
import org.apache.olingo.commons.api.edm.FullQualifiedName;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public interface ChronicleService {
    //  TODO: add in throws exception!
    Integer logData(
            UUID studyId,
            String participantId,
            String datasourceId,
            List<SetMultimap<UUID, Object>> data );

    UUID registerDatasource( UUID studyId, String participantId, String datasourceId, Optional<Datasource> datasource );

    UUID getDeviceEntityKeyId( UUID studyId, String participantId, String datasourceId );

    boolean isKnownDatasource( UUID studyId, String participantId, String datasourceId );

    boolean isKnownParticipant( UUID studyId, String participantId );

    Void deleteParticipantAndAllNeighbors( UUID studyId, String participantId, DeleteType deleteType, String token );

    Void deleteStudyAndAllNeighbors( UUID studyId, DeleteType deleteType, String token );

    Map<String, UUID> getPropertyTypeIds( Set<String> propertyTypeFqns );

    Iterable<Map<String, Set<Object>>> getAllParticipantData(
            UUID studyId,
            UUID participantEntityId,
            String token );

    Iterable<Map<String, Set<Object>>> getAllPreprocessedParticipantData(
            UUID studyId,
            UUID participantEntityId,
            String token );

    Iterable<Map<String, Set<Object>>> getAllParticipantAppsUsageData(
            UUID studyId,
            UUID participantEntityId,
            String token );

    Map<FullQualifiedName, Set<Object>> getParticipantEntity( UUID studyId, UUID participantEntityId );

    ParticipationStatus getParticipationStatus( UUID studyId, String participantId );

    List<ChronicleAppsUsageDetails> getParticipantAppsUsageData(
            UUID studyId,
            String participantId,
            String date );

    Integer updateAppsUsageAssociationData(
            UUID studyId,
            String participantId,
            Map<UUID, Map<FullQualifiedName, Set<Object>>> associationDetails );

    boolean isNotificationsEnabled( UUID studyId );

    ChronicleQuestionnaire getQuestionnaire( UUID studyId, UUID questionnaireEKID );

    Map<UUID, Map<FullQualifiedName, Set<Object>>> getStudyQuestionnaires( UUID studyId );
    
    void submitQuestionnaire( UUID studyId, String participantId, Map<UUID, Map<FullQualifiedName, Set<Object>>> questionnaireResponses );
}
