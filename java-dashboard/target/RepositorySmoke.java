import com.senyalert.model.AlertMode;
import com.senyalert.model.DistressEvent;
import com.senyalert.model.MediaReadyEvent;
import com.senyalert.repository.SqliteIncidentRepository;
import com.senyalert.service.AppExecutors;
import java.nio.file.Path;
import java.util.UUID;

public final class RepositorySmoke {
    public static void main(String[] args) {
        AppExecutors executors = new AppExecutors();
        try {
            SqliteIncidentRepository repository = new SqliteIncidentRepository(
                    Path.of("target", "sqlite-repository-smoke.db"), executors);
            repository.initialize().join();

            String readyToken = "smoke-ready-" + UUID.randomUUID();
            var created = repository.create(new DistressEvent(
                    readyToken, "CAM-SMOKE", 0.91, System.currentTimeMillis() / 1000L,
                    "Smoke zone", "STANDARD", 2, false, "CURRENT", 2, 1,
                    "person-1", "[10,20,30,40]", "", "AQID", "image/jpeg"), AlertMode.AUDIBLE).join();
            var media = repository.attachMedia(new MediaReadyEvent(
                    readyToken, "", "BAUG", "video/mp4", 8.0, "READY")).join().orElseThrow();
            var evidence = repository.findEvidence(created.id()).join().orElseThrow();

            String failedToken = "smoke-failed-" + UUID.randomUUID();
            repository.create(new DistressEvent(
                    failedToken, "CAM-SMOKE", 0.91, System.currentTimeMillis() / 1000L,
                    "Smoke zone", "STANDARD", 0, true, "STALE", 1, 1,
                    "person-2", "", "", "", "image/jpeg"), AlertMode.QUIET).join();
            var failed = repository.attachMedia(new MediaReadyEvent(
                    failedToken, "", "", "video/mp4", 0.0, "FAILED")).join().orElseThrow();

            if (!media.mediaReady() || evidence.snapshotBytes().length != 3 || evidence.videoBytes().length != 3
                    || failed.mediaReady() || !"FAILED".equals(failed.mediaStatus())) {
                throw new AssertionError("Repository media persistence invariants failed");
            }
            System.out.println("repository-smoke=PASS ready=" + media.mediaReady() + " failed=" + failed.mediaReady());
        } finally {
            executors.close();
        }
    }
}
