package net.harmoniya.horno.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileTest {
    private static final String PROFILE = "{"
        + "\"spec\":1,\"profile\":\"forge\",\"version\":\"1.20.1-forge-47.4.10\","
        + "\"minecraft\":\"1.20.1\",\"json\":\"/version.json\","
        + "\"data\":{"
        + "  \"BINPATCH\":{\"client\":\"/data/client.lzma\",\"server\":\"/data/server.lzma\"},"
        + "  \"PATCHED\":{\"client\":\"[net.minecraftforge:forge:1.20.1-47.4.10:client]\",\"server\":\"[net.minecraftforge:forge:1.20.1-47.4.10:server]\"},"
        + "  \"PATCHED_SHA\":{\"client\":\"'4d8a9a63'\",\"server\":\"'72cef317'\"}"
        + "},"
        + "\"processors\":["
        + "  {\"sides\":[\"server\"],\"jar\":\"a:tools:1\",\"classpath\":[],\"args\":[\"--task\",\"EXTRACT_FILES\"]},"
        + "  {\"jar\":\"a:jarsplitter:1\",\"classpath\":[\"a:asm:9\"],\"args\":[\"--input\",\"{MINECRAFT_JAR}\"],\"outputs\":{\"{PATCHED}\":\"{PATCHED_SHA}\"}}"
        + "],"
        + "\"libraries\":[{\"name\":\"a:tools:1\",\"downloads\":{\"artifact\":{\"path\":\"a/tools/1/tools-1.jar\",\"url\":\"https://example.test/tools-1.jar\",\"sha1\":\"abc\"}}}]"
        + "}";

    @Test
    void readsTheClientHalfOfEachDataEntry(@TempDir Path dir) throws Exception {
        try (ZipFile jar = installer(dir, PROFILE)) {
            Profile profile = Profile.read(jar, "client");

            assertEquals("1.20.1", profile.minecraft);
            assertEquals("/version.json", profile.json);
            assertEquals("/data/client.lzma", profile.data.get("BINPATCH"));
            assertEquals("'4d8a9a63'", profile.data.get("PATCHED_SHA"));
        }
    }

    @Test
    void readsProcessorsWithTheirSidesAndOutputs(@TempDir Path dir) throws Exception {
        try (ZipFile jar = installer(dir, PROFILE)) {
            Profile profile = Profile.read(jar, "client");

            assertEquals(2, profile.processors.size());
            assertFalse(profile.processors.get(0).runsOn("client"));
            assertTrue(profile.processors.get(0).runsOn("server"));
            // No `sides` at all means every side, which is how a profile spells "both".
            assertNull(profile.processors.get(1).sides);
            assertTrue(profile.processors.get(1).runsOn("client"));
            assertEquals("{PATCHED_SHA}", profile.processors.get(1).outputs.get("{PATCHED}"));
        }
    }

    @Test
    void readsTheToolsToDownload(@TempDir Path dir) throws Exception {
        try (ZipFile jar = installer(dir, PROFILE)) {
            Profile profile = Profile.read(jar, "client");

            assertEquals(1, profile.libraries.size());
            assertEquals("a/tools/1/tools-1.jar", profile.libraries.get(0).path);
            assertEquals("abc", profile.libraries.get(0).sha1);
        }
    }

    /**
     * Forge 1.13.2-1.16.5. The same language as spec 1, minus `sides` — these
     * were refused once, on the belief that only spec 1 had processors, and
     * every Forge build in that range failed to install.
     */
    @Test
    void readsSpecZeroTheSameWay(@TempDir Path dir) throws Exception {
        String json = PROFILE.replace("\"spec\":1", "\"spec\":0");
        try (ZipFile jar = installer(dir, json)) {
            assertFalse(Profile.read(jar, "client").processors.isEmpty());
        }
    }

    @Test
    void refusesAProfileSpecItDoesNotRead(@TempDir Path dir) throws Exception {
        String json = PROFILE.replace("\"spec\":1", "\"spec\":2");
        try (ZipFile jar = installer(dir, json)) {
            assertThrows(IllegalArgumentException.class, () -> Profile.read(jar, "client"));
        }
    }

    @Test
    void refusesAProfileWithNoProcessors(@TempDir Path dir) throws Exception {
        String json = PROFILE.replace("\"processors\":", "\"absent\":");
        try (ZipFile jar = installer(dir, json)) {
            assertThrows(IllegalArgumentException.class, () -> Profile.read(jar, "client"));
        }
    }

    @Test
    void refusesALibraryWithNothingToDownload(@TempDir Path dir) throws Exception {
        String json = PROFILE.replace("{\"name\":\"a:tools:1\",\"downloads\":{\"artifact\":{\"path\":\"a/tools/1/tools-1.jar\",\"url\":\"https://example.test/tools-1.jar\",\"sha1\":\"abc\"}}}",
            "{\"name\":\"a:tools:1\"}");
        try (ZipFile jar = installer(dir, json)) {
            assertThrows(IllegalArgumentException.class, () -> Profile.read(jar, "client"));
        }
    }

    @Test
    void saysSoWhenTheJarIsNotAnInstaller(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("empty.jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            out.putNextEntry(new ZipEntry("nothing.txt"));
            out.closeEntry();
        }
        try (ZipFile jar = new ZipFile(path.toFile())) {
            assertThrows(IOException.class, () -> Profile.read(jar, "client"));
        }
    }

    private static ZipFile installer(Path dir, String profile) throws IOException {
        Path path = dir.resolve("installer.jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            write(out, "install_profile.json", profile);
        }
        return new ZipFile(path.toFile());
    }

    private static void write(ZipOutputStream out, String name, String body) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(body.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
}
