package com.senyalert.repository;

import com.senyalert.model.AlertMode;
import com.senyalert.model.DistressEvent;
import com.senyalert.model.Incident;
import com.senyalert.model.IncidentEvidence;
import com.senyalert.model.IncidentStatus;
import com.senyalert.model.MediaReadyEvent;
import com.senyalert.model.OperatorIncidentUpdate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface IncidentRepository extends AutoCloseable {
    CompletableFuture<Void> initialize();

    CompletableFuture<Incident> create(DistressEvent event, AlertMode alertMode);

    CompletableFuture<Optional<Incident>> attachMedia(MediaReadyEvent event);

    CompletableFuture<List<Incident>> listRecent();

    CompletableFuture<Optional<IncidentEvidence>> findEvidence(long incidentId);

    /** Updates only operator workflow metadata; detector and evidence fields remain immutable. */
    CompletableFuture<Optional<Incident>> updateOperatorRecord(long incidentId, OperatorIncidentUpdate update);

    /** Deletes only the SQLite incident row and its stored BLOBs; never touches source media files. */
    CompletableFuture<Boolean> deleteRecord(long incidentId);

    @Override
    void close();
}
