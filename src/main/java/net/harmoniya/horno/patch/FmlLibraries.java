package net.harmoniya.horno.patch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the libraries FML fetches for itself actually live now.
 *
 * <p>FML 4.x and 5.x download a handful of jars into {@code <game dir>/lib} on
 * first run, from {@code files.minecraftforge.net/fmllibs} — a host that has
 * been gone for years, which is why nothing in that era starts. Which files a
 * build wants is recorded in the {@code CoreFMLLibraries} class it ships, along
 * with the sha1 of each; the only thing missing is somewhere to get them.
 *
 * <p>This is that missing piece, and it is a table rather than something the
 * document says, because it cannot change. The era is closed: 568 published
 * builds, no more coming, and nine distinct files between them — checked, every
 * build, no exceptions. Eight are on Maven Central or Forge's own maven, where
 * a published artifact cannot be altered or withdrawn. A document is for what
 * varies; this varies never.
 *
 * <p>Keeping it here is also what lets horno install one of these builds from a
 * document that says nothing about any of it. The alternative was three
 * properties that a person installing Forge 1.4 by hand would have to find
 * somewhere.
 */
final class FmlLibraries {
    private FmlLibraries() {
    }

    /** One file, as FML will look for it once horno is done. */
    static final class Library {
        final String name;
        final String sha1;
        final String url;

        Library(String name, String sha1, String url) {
            this.name = name;
            this.sha1 = sha1;
            this.url = url;
        }
    }

    private static final String CENTRAL = "https://repo1.maven.org/maven2";
    private static final String FORGE = "https://maven.minecraftforge.net";

    /** Files that still exist under their own name, at their real home. */
    private static final Map<String, String> SOURCES = new HashMap<>();

    /**
     * The one file that does not exist anywhere any more, and what is asked for
     * instead.
     *
     * <p>{@code asm-all-4.0.jar} was published only by Forge. Central's
     * {@code org.ow2.asm:asm-all:4.0} is a different build of the same release —
     * identical 156 entries, two bytes apart, differing only in the manifest's
     * build stamps — and a search of Central by sha1 finds nothing at all. Since
     * FML validates the sha1 of what it finds, the published one cannot stand in
     * under the old name.
     *
     * <p>{@code asm-debug-all:4.0} answers it because it is the same code: 155
     * classes, compared both ways, zero difference, and debug information is all
     * that it adds. FML's own list is rewritten to ask for it, so nothing here
     * serves one artifact under another artifact's name.
     */
    private static final Map<String, Library> PATCHES = new HashMap<>();

    static {
        SOURCES.put("argo-2.25.jar", CENTRAL + "/net/sourceforge/argo/argo/2.25/argo-2.25.jar");
        SOURCES.put("argo-small-3.2.jar", FORGE + "/net/sourceforge/argo/argo/3.2-small/argo-3.2-small.jar");
        SOURCES.put("asm-all-4.1.jar", CENTRAL + "/org/ow2/asm/asm-all/4.1/asm-all-4.1.jar");
        SOURCES.put("bcprov-jdk15on-147.jar", CENTRAL + "/org/bouncycastle/bcprov-jdk15on/1.47/bcprov-jdk15on-1.47.jar");
        SOURCES.put("bcprov-jdk15on-148.jar", CENTRAL + "/org/bouncycastle/bcprov-jdk15on/1.48/bcprov-jdk15on-1.48.jar");
        SOURCES.put("guava-12.0.1.jar", CENTRAL + "/com/google/guava/guava/12.0.1/guava-12.0.1.jar");
        SOURCES.put("guava-14.0-rc3.jar", CENTRAL + "/com/google/guava/guava/14.0-rc3/guava-14.0-rc3.jar");
        SOURCES.put("scala-library.jar", FORGE + "/org/scala-lang/scala-library/2.10.0-custom/scala-library-2.10.0-custom.jar");

        PATCHES.put("asm-all-4.0.jar", new Library(
            "asm-debug-all-4.0.jar",
            "2340f4db0d1a57ba3a430597c42875c827a4cb69",
            CENTRAL + "/org/ow2/asm/asm-debug-all/4.0/asm-debug-all-4.0.jar"
        ));
    }

    /**
     * FML's own list, resolved to files that can still be obtained.
     *
     * <p>An unknown name throws rather than being skipped. Guessing a maven
     * coordinate from a filename is how a launcher ends up serving the wrong jar
     * under the right name, and a silently short list fails later as a network
     * error against a dead host rather than as itself.
     */
    static List<Library> resolve(List<String> names, List<String> hashes) {
        if (names.size() != hashes.size()) {
            throw new IllegalArgumentException("FML lists " + names.size() + " libraries and " + hashes.size() + " hashes");
        }
        List<Library> resolved = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            Library patched = PATCHES.get(name);
            if (patched != null) {
                resolved.add(patched);
            } else if (SOURCES.containsKey(name)) {
                resolved.add(new Library(name, hashes.get(i), SOURCES.get(name)));
            } else {
                throw new IllegalArgumentException("This build wants the FML library " + name + ", which horno has no source for");
            }
        }
        return Collections.unmodifiableList(resolved);
    }
}
