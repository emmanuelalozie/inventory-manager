package com.example.inventix.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Where Inventix keeps its data, and the two file operations that must happen before the database is opened:
 * restoring a backup that was requested on the previous run, and copying a database from the old location
 * (./data, relative to the start folder) the first time the new location is used.
 */
public final class DataLocation {

    public static final String DATABASE_NAME = "inventix";
    public static final String DATABASE_FILE = DATABASE_NAME + ".mv.db";
    /** Holds the path of the backup zip to restore on the next start. */
    public static final String RESTORE_MARKER = "restore-pending.txt";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private DataLocation() {
    }

    /**
     * The per-user folder for Inventix data: %LOCALAPPDATA%\Inventix on Windows, ~/Library/Application Support/Inventix
     * on macOS, $XDG_DATA_HOME/inventix (default ~/.local/share/inventix) elsewhere.
     */
    public static Path defaultHome(String osName, Map<String, String> env, String userHome) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String local = env.get("LOCALAPPDATA");
            Path base = local != null && !local.isBlank() ? Path.of(local) : Path.of(userHome, "AppData", "Local");
            return base.resolve("Inventix");
        }
        if (os.contains("mac")) {
            return Path.of(userHome, "Library", "Application Support", "Inventix");
        }
        String xdg = env.get("XDG_DATA_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(userHome, ".local", "share");
        return base.resolve("inventix");
    }

    /**
     * If a restore was requested, puts the backup's database in place. The current database file is kept as
     * inventix-before-restore-{timestamp}.mv.db. On any problem the current database is left as it was.
     * The marker is removed either way, so a broken backup can't block every start.
     */
    public static void applyPendingRestore(Path dataDir, Consumer<String> info, Consumer<String> error) {
        Path marker = dataDir.resolve(RESTORE_MARKER);
        if (!Files.exists(marker)) {
            return;
        }
        Path temp = dataDir.resolve(DATABASE_FILE + ".restoring");
        try {
            Path backup = Path.of(Files.readString(marker, StandardCharsets.UTF_8).strip());
            if (!extractDatabase(backup, temp)) {
                error.accept("Restore skipped: " + backup + " has no " + DATABASE_FILE + " inside");
                return;
            }
            Path current = dataDir.resolve(DATABASE_FILE);
            if (Files.exists(current)) {
                Path kept = dataDir.resolve(DATABASE_NAME + "-before-restore-" + LocalDateTime.now().format(STAMP) + ".mv.db");
                Files.move(current, kept);
                info.accept("Restore: the previous database was kept as " + kept);
            }
            Files.move(temp, current, StandardCopyOption.ATOMIC_MOVE);
            info.accept("Restored the database from " + backup);
        } catch (IOException | RuntimeException e) {
            error.accept("Restore failed, the current database is unchanged: " + e);
        } finally {
            try {
                Files.deleteIfExists(temp);
                Files.deleteIfExists(marker);
            } catch (IOException e) {
                error.accept("Could not clean up after the restore: " + e);
            }
        }
    }

    /**
     * Copies {legacyDir}/inventix.mv.db to dataDir when dataDir has no database yet, so data created before the
     * per-user folder existed isn't lost. The original file is left where it was.
     *
     * @return true if a database was copied
     */
    public static boolean copyLegacyDatabase(Path legacyDir, Path dataDir, Consumer<String> info) throws IOException {
        Path target = dataDir.resolve(DATABASE_FILE);
        Path legacy = legacyDir.resolve(DATABASE_FILE);
        if (Files.exists(target) || !Files.isRegularFile(legacy)
                || legacy.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
            return false;
        }
        Files.createDirectories(dataDir);
        Files.copy(legacy, target);
        info.accept("Copied the existing database from " + legacy.toAbsolutePath() + " to " + target
                + ". The old file was left in place; delete it once you have checked the data.");
        return true;
    }

    /**
     * Writes the database file from a backup zip (the entry ending in .mv.db) to target.
     * The entry name is never used as a path, so a crafted zip can't write elsewhere.
     */
    public static boolean extractDatabase(Path backupZip, Path target) throws IOException {
        try (InputStream in = Files.newInputStream(backupZip); ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (!entry.isDirectory() && entry.getName().endsWith(".mv.db")) {
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                    return true;
                }
            }
        }
        return false;
    }

    /** True if the zip holds an H2 database file. */
    public static boolean containsDatabase(Path backupZip) {
        try (InputStream in = Files.newInputStream(backupZip); ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (!entry.isDirectory() && entry.getName().endsWith(".mv.db")) {
                    return true;
                }
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }
}
