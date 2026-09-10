package net.harmoniya.horno.patch;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FmlLibrariesTest {
    @Test
    void keepsAFileThatStillExistsUnderItsOwnNameAndHash() {
        List<FmlLibraries.Library> resolved = FmlLibraries.resolve(
            Collections.singletonList("argo-2.25.jar"),
            Collections.singletonList("bb672829fde76cb163004752b86b0484bd0a7f4b")
        );

        assertEquals("argo-2.25.jar", resolved.get(0).name);
        assertEquals("bb672829fde76cb163004752b86b0484bd0a7f4b", resolved.get(0).sha1);
        assertTrue(resolved.get(0).url.endsWith("/net/sourceforge/argo/argo/2.25/argo-2.25.jar"), resolved.get(0).url);
    }

    /**
     * The one file that no longer exists. Both the name and the hash change,
     * which is why this cannot be a table of URLs: FML has to be told to ask
     * for the substitute, not handed it under the old name.
     */
    @Test
    void answersTheMissingFileWithADifferentArtifact() {
        List<FmlLibraries.Library> resolved = FmlLibraries.resolve(
            Collections.singletonList("asm-all-4.0.jar"),
            Collections.singletonList("98308890597acb64047f7e896638e0d98753ae82")
        );

        assertEquals("asm-debug-all-4.0.jar", resolved.get(0).name);
        assertEquals("2340f4db0d1a57ba3a430597c42875c827a4cb69", resolved.get(0).sha1);
    }

    /** Every set any of the 568 published builds asks for. */
    @Test
    void resolvesEverySetTheEraAsksFor() {
        assertEquals(3, FmlLibraries.resolve(
            Arrays.asList("argo-2.25.jar", "guava-12.0.1.jar", "asm-all-4.0.jar"),
            Arrays.asList("a", "b", "c")).size());
        assertEquals(4, FmlLibraries.resolve(
            Arrays.asList("argo-2.25.jar", "guava-12.0.1.jar", "asm-all-4.0.jar", "bcprov-jdk15on-147.jar"),
            Arrays.asList("a", "b", "c", "d")).size());
        assertEquals(5, FmlLibraries.resolve(
            Arrays.asList("argo-small-3.2.jar", "guava-14.0-rc3.jar", "asm-all-4.1.jar", "bcprov-jdk15on-148.jar", "scala-library.jar"),
            Arrays.asList("a", "b", "c", "d", "e")).size());
    }

    /**
     * A name from outside the era — a coremod may register a library set of its
     * own. Guessing a maven coordinate from a filename is how a launcher ends up
     * serving the wrong jar under the right name.
     */
    @Test
    void refusesANameItHasNoSourceFor() {
        assertThrows(IllegalArgumentException.class, () -> FmlLibraries.resolve(
            Collections.singletonList("something-1.0.jar"),
            Collections.singletonList("a")));
    }

    @Test
    void refusesAListAndHashesOfDifferentLengths() {
        assertThrows(IllegalArgumentException.class, () -> FmlLibraries.resolve(
            Arrays.asList("argo-2.25.jar", "guava-12.0.1.jar"),
            Collections.singletonList("a")));
    }
}
