package eu.ha3.mc.quick.update;

import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import net.minecraft.util.Util;

public class UpdateChecker {
    private static final Logger LOGGER = LogManager.getLogger("UpdateChecker");
    private static final Gson GSON = new Gson();

    private final TargettedVersion currentVersion;

    private final String server;
    private final Reporter reporter;

    private final UpdaterConfig config;

    private boolean started;

    private CompletableFuture<Optional<Versions>> cachedResult = CompletableFuture.completedFuture(Optional.empty());

    public UpdateChecker(UpdaterConfig config, String modId, String server, Reporter reporter) {
        this.config = config;
        this.currentVersion = new TargettedVersion(modId);
        this.server = server;
        this.reporter = reporter;
    }

    public Optional<Versions> getLast() {
        return cachedResult.getNow(Optional.empty());
    }

    public Optional<Versions> getNewer() {
        return getLast().filter(versions -> versions.latest().version().compareTo(currentVersion.version()) > 0);
    }

    public void attempt() {
        if (started || !config.enabled) {
            return;
        }
        started = true;

        checkNow().thenAccept(o -> o.ifPresent(versions -> {
            TargettedVersion latest = versions.latest();

            LOGGER.info("Server responded version: {}, we are {}", latest, currentVersion);

            if (latest.version().compareTo(currentVersion.version()) > 0) {
                if (config.shouldReport(latest)) {
                    reporter.report(latest, currentVersion);
                }
            }
        }));
    }

    public CompletableFuture<Optional<Versions>> checkNow() {
        return cachedResult = CompletableFuture.supplyAsync(() -> {
            try {
                URL url = new URL(server + "?t=" + System.currentTimeMillis());

                try (Reader reader = new InputStreamReader(url.openStream())) {
                    return Optional.of(new Versions(GSON.fromJson(reader, JsonObject.class)));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, Util.getIoWorkerExecutor()).exceptionally(e -> {
            LOGGER.error("Error occured whilst checking for updates", e);
            return Optional.empty();
        });
    }
}
