package net.harmoniya.horno.patch;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * The second file FML 5 fetches for itself: its deobfuscation data.
 *
 * <p>Forge for Minecraft 1.5 and 1.5.1 wants one more file in
 * {@code <game dir>/lib} than {@code CoreFMLLibraries} lists —
 * {@code deobfuscation_data_<mc>.zip}, the mapping from Mojang's obfuscated
 * names to the ones mods were compiled against. No class spells the name: it
 * is built from {@code fmlbuild.mcversion}, and the sha1 it must have is
 * {@code fmlbuild.deobfuscation.hash}, both in the {@code fmlversion.properties}
 * the build ships. 1.4.7 and older carry neither key; 1.5.2 has a real
 * installer and takes nothing from {@code lib/} at all.
 *
 * <p>Unlike the libraries, these have no home left. They are generated data
 * rather than a published artifact, the host that served them is gone, and
 * nothing else carries the same bytes. The copies here came out of the
 * Internet Archive, hash to what the builds ask for, and are served beside the
 * documents — the one place horno fetches from a mirror of its own, because it
 * is the one case with no alternative.
 *
 * <p>Three different files were published as {@code deobfuscation_data_1.5.zip}
 * and only the last survives. Ten early 1.5 builds want one of the other two
 * and cannot be installed by anybody; those are refused by name rather than
 * handed data from a different build, which FML would accept and mods would
 * silently break on.
 */
final class FmlDeobfuscation {
    private FmlDeobfuscation() {
    }

    /** The file inside a Forge build that says what it wants. */
    static final String ENTRY = "fmlversion.properties";

    private static final String MIRROR = "https://harmoniya-net.github.io/metadata/fmllibs";

    /** sha1 to file name, for the two that still exist. 68 and 26 builds respectively. */
    private static final Map<String, String> SURVIVING = new HashMap<>();

    static {
        SURVIVING.put("22e221a0d89516c1f721d6cab056a7e37471d0a6", "deobfuscation_data_1.5.1.zip");
        SURVIVING.put("5f7c142d53776f16304c0bbe10542014abad6af8", "deobfuscation_data_1.5.zip");
    }

    /** What this build wants, or {@code null} when it predates the mechanism. */
    static FmlLibraries.Library resolve(Properties version) {
        String hash = version.getProperty("fmlbuild.deobfuscation.hash");
        if (hash == null || hash.trim().isEmpty()) {
            return null;
        }
        hash = hash.trim().toLowerCase(Locale.ROOT);
        String minecraft = version.getProperty("fmlbuild.mcversion");
        if (minecraft == null || minecraft.trim().isEmpty()) {
            throw new IllegalArgumentException("This build names deobfuscation data " + hash + " and no Minecraft version to name the file after");
        }
        String name = "deobfuscation_data_" + minecraft.trim() + ".zip";
        if (!name.equals(SURVIVING.get(hash))) {
            throw new IllegalArgumentException(
                "This build wants " + name + " with sha1 " + hash + ". That is one of the files Forge published under this name and"
                    + " later replaced, and no copy of it survives — the build cannot be installed, here or anywhere. A later build"
                    + " for the same Minecraft version can.");
        }
        return new FmlLibraries.Library(name, hash, MIRROR + "/" + name);
    }
}
