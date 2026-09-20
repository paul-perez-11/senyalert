package com.senyalert;

import com.senyalert.repository.SqliteIncidentRepository;
import com.senyalert.service.AppExecutors;
import java.nio.file.Path;

/** @deprecated Persistence is provided by repository.SqliteIncidentRepository. */
@Deprecated
public final class DatabaseManager {
    private DatabaseManager() {
    }

    public static SqliteIncidentRepository createRepository(Path databasePath, AppExecutors executors) {
        return new SqliteIncidentRepository(databasePath, executors);
    }
}
