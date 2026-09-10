package net.harmoniya.horno;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionTest {
    private static final String PROCESSOR = "{"
        + "\"id\":\"1.20.1-forge-47.4.10\","
        + "\"inheritsFrom\":\"1.20.1\","
        + "\"mainClass\":\"net.harmoniya.horno.Main\","
        + "\"arguments\":{\"jvm\":["
        + "  \"-Dhorno.librariesDir=${library_directory}\","
        + "  \"-Dhorno.installer=${library_directory}/net/minecraftforge/forge/1.20.1-47.4.10/forge-1.20.1-47.4.10-installer.jar\","
        + "  {\"rules\":[{\"action\":\"allow\",\"os\":{\"name\":\"osx\"}}],\"value\":\"-XstartOnFirstThread\"}"
        + "]},"
        + "\"libraries\":["
        + "  {\"name\":\"a:b:1\",\"downloads\":{\"artifact\":{\"path\":\"a/b/1/b-1.jar\",\"url\":\"https://example.invalid/b-1.jar\",\"sha1\":\"abc\",\"size\":12}}},"
        + "  {\"name\":\"produced:by:processors\"}"
        + "]}";

    @Test
    void readsTheLibraryList() throws Exception {
        Version version = Version.parse(PROCESSOR);

        assertEquals("1.20.1-forge-47.4.10", version.id);
        assertEquals("1.20.1", version.inheritsFrom);
        assertEquals(2, version.libraries.size());
        assertEquals("a/b/1/b-1.jar", version.libraries.get(0).path);
        assertEquals("abc", version.libraries.get(0).sha1);
        assertEquals(12, version.libraries.get(0).size);
    }

    @Test
    void aLibraryWithNoDownloadIsOneTheProcessorsProduce() throws Exception {
        Version version = Version.parse(PROCESSOR);

        assertNull(version.libraries.get(1).path);
        assertNull(version.libraries.get(1).url);
        assertFalse(version.libraries.get(1).isDownloadable());
    }

    @Test
    void findsAPropertyAndLeavesItsPlaceholdersAlone() throws Exception {
        Version version = Version.parse(PROCESSOR);

        assertTrue(version.property("installer").startsWith("${library_directory}/"));
        assertNull(version.property("jarmod"));
    }

    @Test
    void keepsOnlyThePlainStringsInJvmArgs() throws Exception {
        Version version = Version.parse(PROCESSOR);

        assertEquals(2, version.jvmArgs.size());
    }

    /**
     * The whole reason for reading a tree by hand. Bound to fields, a document
     * with no `libraries` is a document with zero of them, and horno would
     * cheerfully install nothing.
     */
    @Test
    void refusesAVersionWithNoLibraries() {
        String json = "{\"inheritsFrom\":\"1.20.1\",\"mainClass\":\"x\"}";

        assertThrows(IllegalArgumentException.class, () -> Version.parse(json));
    }

    /**
     * `inheritsFrom` is read and reported, not demanded. Nothing horno does
     * depends on it, and requiring a field to prove a point turns a working
     * build into a failing one for no gain.
     */
    @Test
    void takesAVersionThatInheritsFromNothing() throws Exception {
        Version version = Version.parse("{\"mainClass\":\"x\",\"libraries\":[]}");

        assertNull(version.inheritsFrom);
    }

    @Test
    void refusesAVersionWithNoMainClass() {
        assertThrows(IllegalArgumentException.class, () -> Version.parse("{\"libraries\":[]}"));
    }

    /**
     * The installer's own version JSON, read by the same parser: what nobody
     * downloads is what a processor writes.
     */
    @Test
    void separatesWhatIsFetchedFromWhatIsProduced() throws Exception {
        Version version = Version.parse("{\"mainClass\":\"x\",\"libraries\":["
            + "  {\"name\":\"a:b:1\",\"downloads\":{\"artifact\":{\"path\":\"a/b/1/b-1.jar\",\"url\":\"https://example.invalid/b-1.jar\"}}},"
            + "  {\"name\":\"c:d:1\",\"downloads\":{\"artifact\":{\"path\":\"c/d/1/d-1.jar\",\"url\":\"\"}}}"
            + "]}");

        assertEquals(1, version.downloadable().size());
        assertEquals("a/b/1/b-1.jar", version.downloadable().get(0).path);
        assertEquals(java.util.Collections.singletonList("c/d/1/d-1.jar"), version.produced());
    }

    /**
     * Reading `-Dhorno.*` does not care about a rule-tagged argument, and
     * replaying an installer's whole JVM line does. So the parser records that
     * one was there and lets the caller decide.
     */
    @Test
    void recordsThatAConditionalArgumentWasThere() throws Exception {
        assertTrue(Version.parse(PROCESSOR).hasConditionalJvmArgs);
        assertFalse(Version.parse("{\"mainClass\":\"x\",\"libraries\":[],\"arguments\":{\"jvm\":[\"-Xmx2G\"]}}").hasConditionalJvmArgs);
    }

    @Test
    void aPre113VersionHasNoJvmArguments() throws Exception {
        String json = "{\"inheritsFrom\":\"1.7.10\",\"mainClass\":\"net.minecraft.launchwrapper.Launch\","
            + "\"minecraftArguments\":\"--username ${auth_player_name}\",\"libraries\":[]}";

        assertTrue(Version.parse(json).jvmArgs.isEmpty());
    }
}
