package com.example.inventix.config;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Runs before the database is opened. When inventix.data.managed=true (set in the app's application.properties,
 * not in the tests'), it decides the data folders, applies a pending restore and copies a database from the old
 * ./data location the first time.
 * <p>
 * inventix.home defaults to a per-user folder (see {@link DataLocation#defaultHome}); inventix.data.dir defaults
 * to {home}/data. Both can be set as properties or as INVENTIX_HOME / INVENTIX_DATA_DIR.
 */
public class DataLocationPostProcessor implements EnvironmentPostProcessor {

    private final Log log;

    public DataLocationPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(DataLocationPostProcessor.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty("inventix.data.managed", Boolean.class, false)) {
            return;
        }
        String homeSetting = environment.getProperty("inventix.home");
        Path home = homeSetting != null && !homeSetting.isBlank()
                ? Path.of(homeSetting)
                : DataLocation.defaultHome(System.getProperty("os.name"), System.getenv(), System.getProperty("user.home"));
        String dataSetting = environment.getProperty("inventix.data.dir");
        Path dataDir = (dataSetting != null && !dataSetting.isBlank() ? Path.of(dataSetting) : home.resolve("data"))
                .toAbsolutePath().normalize();

        // Fallbacks only: anything set explicitly still wins.
        environment.getPropertySources().addLast(new MapPropertySource("inventixDataLocation", Map.of(
                "inventix.home", home.toAbsolutePath().normalize().toString(),
                "inventix.data.dir", dataDir.toString())));

        try {
            Files.createDirectories(dataDir);
            DataLocation.applyPendingRestore(dataDir, log::info, log::error);
            DataLocation.copyLegacyDatabase(Path.of("data"), dataDir, log::warn);
        } catch (IOException e) {
            log.error("Could not prepare the data folder " + dataDir + ": " + e);
        }
        log.info("Database: " + dataDir.resolve(DataLocation.DATABASE_FILE));
    }
}
