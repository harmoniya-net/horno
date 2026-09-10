package net.harmoniya.horno.patch;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.harmoniya.horno.Downloader;

/**
 * The pre-1.13 install: rewrite one jar.
 *
 * <p>Where the processor era has to run the loader's own installer to produce
 * its client jar, this era only has to strip the signature (1.5.2) or overlay a
 * universal zip and strip the signature (1.5.1 and older).
 *
 * <p>Kept apart from {@link JarModLauncher} because installing and launching
 * are two things, and {@code horno.installOnly} — and the standalone installer,
 * which never launches anything — need only the first.
 *
 * <ul>
 *   <li>{@code horno.minecraft} — the vanilla client jar to patch. The
 *       launcher's, and already on disk.</li>
 *   <li>{@code horno.patched} — where the patched jar goes.</li>
 *   <li>{@code horno.jarmod} — {@code File.pathSeparator}-separated archives to
 *       overlay, in order. Absent or empty means strip only.</li>
 *   <li>{@code horno.jarmodUrl}, {@code horno.jarmodSha1} — the same list
 *       again, in the same order. The overlay is an input to horno rather than
 *       a runtime library, so horno fetches it.</li>
 * </ul>
 */
public final class JarMod {
    private JarMod() {
    }

    /** Build the patched client jar, fetching whatever this era needs first. */
    public static Path install() throws IOException {
        Path gameDir = targetDirectory();
        Path client = requirePath("horno.minecraft");
        Path output = requirePath("horno.patched");
        List<Path> overlays = overlays();

        // FML's own library list, out of the archive that carries it. Nothing in
        // the document says any of this: the names and hashes are Forge's, and
        // where the files live now is a closed question about a closed era.
        byte[] list = fmlLibraryList(overlays);
        Map<String, byte[]> supplied = Collections.emptyMap();
        if (list != null) {
            List<FmlLibraries.Library> libraries = FmlLibraries.resolve(FmlLibraryList.names(list), FmlLibraryList.hashes(list));
            place(libraries, gameDir.resolve("lib"));
            supplied = Collections.singletonMap(FmlLibraryList.ENTRY, rewritten(list, libraries));
        }

        return ClientPatcher.patch(client, overlays, output, supplied);
    }

    /** The class carrying FML's library list, or null when this build ships no FML. */
    private static byte[] fmlLibraryList(List<Path> overlays) throws IOException {
        for (Path overlay : overlays) {
            try (ZipFile zip = new ZipFile(overlay.toFile())) {
                ZipEntry entry = zip.getEntry(FmlLibraryList.ENTRY);
                if (entry != null) {
                    return readAll(zip, entry);
                }
            }
        }
        return null;
    }

    /**
     * Put the files where FML looks, before it looks.
     *
     * <p>Once they are there and hash correctly, FML finds them and never opens
     * a connection — which is the whole point, because the host it would open
     * one to has been gone for years.
     */
    private static void place(List<FmlLibraries.Library> libraries, Path lib) throws IOException {
        for (FmlLibraries.Library library : libraries) {
            Downloader.ensure(lib.resolve(library.name), library.url, library.sha1, library.name);
        }
    }

    /**
     * FML's list, rewritten to name what horno just placed.
     *
     * <p>Only needed because one of the files is answered by a different
     * artifact, and FML validates the sha1 of what it finds. Rewriting the whole
     * list rather than the one entry keeps the two in step by construction: what
     * the class asks for is exactly what was written to disk.
     */
    private static byte[] rewritten(byte[] list, List<FmlLibraries.Library> libraries) throws IOException {
        List<String> names = new ArrayList<>(libraries.size());
        List<String> hashes = new ArrayList<>(libraries.size());
        for (FmlLibraries.Library library : libraries) {
            names.add(library.name);
            hashes.add(library.sha1);
        }
        byte[] patched = FmlLibraryList.rewrite(list, names, hashes);
        if (patched == null) {
            throw new IOException("FML's library list is not the shape horno read it as; refusing to patch half of it");
        }
        System.out.println("[horno] " + names.size() + " FML libraries in place");
        return patched;
    }

    private static byte[] readAll(ZipFile zip, ZipEntry entry) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.InputStream in = zip.getInputStream(entry)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                bytes.write(buffer, 0, read);
            }
        }
        return bytes.toByteArray();
    }

    /**
     * Tell this era where the game directory is, and report it.
     *
     * <p>Before 1.6 there is no {@code --gameDir}. Minecraft works the directory
     * out for itself, and the only thing that overrides it is
     * {@code minecraft.applet.TargetDirectory}, the property the old applet
     * wrapper used. Nothing sets it, so the game and FML both fall back to the
     * OS-standard {@code .minecraft} — saves, options, mods and FML's own
     * {@code lib/} land in the user's home rather than in the installation the
     * launcher just built. It looks like it works, right up until two instances
     * share one world folder.
     *
     * <p>The working directory is the answer, because that is the one thing
     * every launcher already sets per instance, and a document cannot say it:
     * {@code ${game_directory}} is substituted in game arguments, not reliably
     * in JVM ones. Set by hand it wins, as always.
     */
    public static Path targetDirectory() {
        String declared = System.getProperty("minecraft.applet.TargetDirectory");
        if (declared == null) {
            declared = System.getProperty("user.dir");
            System.setProperty("minecraft.applet.TargetDirectory", declared);
        }
        return Paths.get(declared).toAbsolutePath();
    }

    /**
     * The overlays, on disk.
     *
     * <p>{@code horno.jarmod} is separated by {@code File.pathSeparator}, which
     * is what every launcher property that carries paths uses. The URL and sha1
     * lists beside it are separated by a space instead: on Unix the path
     * separator is {@code :}, which is in every URL.
     */
    private static List<Path> overlays() throws IOException {
        List<Path> paths = splitPaths(System.getProperty("horno.jarmod"));
        List<String> urls = splitOn(System.getProperty("horno.jarmodUrl"), " ");
        List<String> hashes = splitOn(System.getProperty("horno.jarmodSha1"), " ");
        for (int i = 0; i < paths.size(); i++) {
            Downloader.ensure(
                paths.get(i),
                i < urls.size() ? urls.get(i) : null,
                i < hashes.size() ? hashes.get(i) : null,
                "the jarmod overlay " + paths.get(i).getFileName()
            );
        }
        return paths;
    }

    public static Path requirePath(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("Missing required JVM argument -D" + property + "=");
        }
        return Paths.get(value).toAbsolutePath();
    }

    private static List<Path> splitPaths(String value) {
        List<Path> paths = new ArrayList<>();
        for (String entry : splitOn(value, File.pathSeparator)) {
            paths.add(Paths.get(entry).toAbsolutePath());
        }
        return paths;
    }

    static List<String> splitOn(String value, String separator) {
        if (value == null) {
            return Collections.emptyList();
        }
        List<String> parts = new ArrayList<>();
        for (String entry : value.split(java.util.regex.Pattern.quote(separator))) {
            if (!entry.isEmpty()) {
                parts.add(entry);
            }
        }
        return parts;
    }
}
