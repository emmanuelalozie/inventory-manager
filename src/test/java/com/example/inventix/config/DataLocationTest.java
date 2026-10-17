package com.example.inventix.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class DataLocationTest {

    @TempDir
    Path tmp;

    @Test
    void defaultHome_perOperatingSystem() {
        assertThat(DataLocation.defaultHome("Windows 11", Map.of("LOCALAPPDATA", "C:\\\\Users\\\\me\\\\AppData\\\\Local"), "C:\\\\Users\\\\me"))
                .isEqualTo(Path.of("C:\\\\Users\\\\me\\\\AppData\\\\Local", "Inventix"));
        assertThat(DataLocation.defaultHome("Mac OS X", Map.of(), "/Users/me"))
                .isEqualTo(Path.of("/Users/me/Library/Application Support/Inventix"));
        assertThat(DataLocation.defaultHome("Linux", Map.of(), "/home/me")).isEqualTo(Path.of("/home/me/.local/share/inventix"));
        assertThat(DataLocation.defaultHome("Linux", Map.of("XDG_DATA_HOME", "/data"), "/home/me")).isEqualTo(Path.of("/data/inventix"));
    }

    @Test
    void copyLegacyDatabase_copiesOnlyWhenTheNewFolderHasNoDatabase() throws IOException {
        Path legacy = Files.createDirectories(tmp.resolve("repo/data"));
        Files.writeString(legacy.resolve(DataLocation.DATABASE_FILE), "old data");
        Path dataDir = tmp.resolve("home/data");
        List<String> log = new ArrayList<>();

        assertThat(DataLocation.copyLegacyDatabase(legacy, dataDir, log::add)).isTrue();
        assertThat(Files.readString(dataDir.resolve(DataLocation.DATABASE_FILE))).isEqualTo("old data");
        assertThat(legacy.resolve(DataLocation.DATABASE_FILE)).exists(); // left in place
        assertThat(log).singleElement().asString().contains("old file was left in place");

        Files.writeString(dataDir.resolve(DataLocation.DATABASE_FILE), "newer data");
        assertThat(DataLocation.copyLegacyDatabase(legacy, dataDir, log::add)).isFalse();
        assertThat(Files.readString(dataDir.resolve(DataLocation.DATABASE_FILE))).isEqualTo("newer data");
        assertThat(DataLocation.copyLegacyDatabase(tmp.resolve("nowhere"), tmp.resolve("other"), log::add)).isFalse();
    }

    @Test
    void applyPendingRestore_withABrokenBackup_keepsTheCurrentDatabase_andClearsTheMarker() throws IOException {
        Path dataDir = Files.createDirectories(tmp.resolve("data"));
        Files.writeString(dataDir.resolve(DataLocation.DATABASE_FILE), "current");
        Path zip = tmp.resolve("empty.zip");
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("readme.txt"));
            z.write("no database".getBytes(StandardCharsets.UTF_8));
        }
        Files.writeString(dataDir.resolve(DataLocation.RESTORE_MARKER), zip.toString());
        List<String> errors = new ArrayList<>();

        DataLocation.applyPendingRestore(dataDir, m -> { }, errors::add);

        assertThat(errors).singleElement().asString().contains("Restore skipped");
        assertThat(Files.readString(dataDir.resolve(DataLocation.DATABASE_FILE))).isEqualTo("current");
        assertThat(dataDir.resolve(DataLocation.RESTORE_MARKER)).doesNotExist();
    }

    @Test
    void extractDatabase_ignoresTheEntryPath() throws IOException {
        Path zip = tmp.resolve("crafted.zip");
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("../../evil/inventix.mv.db"));
            z.write("db".getBytes(StandardCharsets.UTF_8));
        }
        Path target = tmp.resolve("out/inventix.mv.db");
        Files.createDirectories(target.getParent());

        assertThat(DataLocation.extractDatabase(zip, target)).isTrue();
        assertThat(Files.readString(target)).isEqualTo("db");
        assertThat(tmp.resolve("../evil")).doesNotExist();
    }
}
