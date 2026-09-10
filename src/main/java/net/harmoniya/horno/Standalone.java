package net.harmoniya.horno;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import net.harmoniya.horno.install.Installer;
import net.harmoniya.horno.patch.JarMod;

/**
 * {@code java -jar horno.jar install --document <url|path> --root <dir>}.
 *
 * <p>The same install that runs inside a launcher, with nobody to launch
 * afterwards. The only difference between the two is who has already put the
 * libraries on disk: in a launcher, the launcher has; here, horno does.
 *
 * <p>It installs the loader's half and stops there. No asset index, no four
 * thousand asset objects, no version manifest: every launcher already does that
 * and does it better, in parallel and with retries, and reaching in there would
 * mean writing a bad launcher instead of a good installer. What horno fetches
 * is what the document lists — which does include the vanilla client jar,
 * because the document declares it and nothing else will put it where the
 * document says it goes.
 */
final class Standalone {
    private Standalone() {
    }

    static void run(String[] args) throws Throwable {
        String source = null;
        String root = null;
        for (int i = 0; i < args.length; i++) {
            if ("--document".equals(args[i]) && i + 1 < args.length) {
                source = args[++i];
            } else if ("--root".equals(args[i]) && i + 1 < args.length) {
                root = args[++i];
            } else {
                throw new IllegalArgumentException("Unrecognised argument `" + args[i] + "`\n\n" + Main.USAGE);
            }
        }
        if (source == null || root == null) {
            throw new IllegalArgumentException("install needs both --document and --root\n\n" + Main.USAGE);
        }

        Path gameDir = Paths.get(root).toAbsolutePath();
        Path libraryDir = gameDir.resolve("libraries");
        Version version = Version.parse(read(source));
        System.out.println("[horno] installing " + version.id
            + (version.inheritsFrom == null ? "" : " for Minecraft " + version.inheritsFrom)
            + " into " + gameDir);

        // The document's own `-Dhorno.*` arguments are how it names the
        // installer, the overlay and the client jar. Inside a launcher the JVM
        // has already applied them; here horno applies them to itself, so that
        // both halves read the same properties from the same place.
        apply(version, libraryDir);

        int libraries = fetchLibraries(version, libraryDir);
        System.out.println("[horno] " + libraries + " libraries in place");

        // Which era this is, read off the document rather than off a version
        // number. The properties are the era: naming a class to hand off to means
        // the client jar has to be rewritten, naming an installer means there are
        // processors to run, and naming neither means the libraries above were
        // the whole of it.
        if (System.getProperty("horno.mainClass") != null) {
            System.out.println("[horno] patched " + JarMod.install());
        } else if (System.getProperty("horno.installer") != null) {
            runProcessors(libraryDir);
        } else {
            // 1.6.1-1.12.2. Forge is a LaunchWrapper tweaker and a list of jars,
            // and horno is not in that launch at all — which is exactly why the
            // install has to work here anyway. A tool that can only finish the
            // job where it also starts the game is not a tool.
            System.out.println("[horno] " + version.id + " has nothing further to build");
        }

        System.out.println("[horno] installed " + version.id + " into " + gameDir);
        System.out.println("[horno] vanilla is not horno's half: " + gameDir + " still needs Minecraft"
            + (version.inheritsFrom == null ? "" : " " + version.inheritsFrom) + " and its assets from a launcher");
    }

    private static void runProcessors(Path libraryDir) throws Throwable {
        Path installerJar = Downloader.ensure(
            Paths.get(System.getProperty("horno.installer")),
            System.getProperty("horno.installerUrl"),
            System.getProperty("horno.installerSha1"),
            "the installer"
        );
        Path minecraftJar = JarMod.requirePath("horno.minecraft");
        if (!Files.isRegularFile(minecraftJar)) {
            throw new IOException("The Minecraft client jar is missing at " + minecraftJar
                + ". Horno installs the loader's half; vanilla is the launcher's.");
        }
        try (Installer installer = Installer.open(installerJar)) {
            installer.install(libraryDir, minecraftJar);
        }
    }

    /**
     * Set every {@code horno.*} property the document names, resolving
     * {@code ${library_directory}} against this installation.
     *
     * <p>A property already set on the command line wins: horno is being run by
     * hand here, and the person running it is more current than the file.
     */
    private static void apply(Version version, Path libraryDir) {
        for (String arg : version.jvmArgs) {
            if (!arg.startsWith("-Dhorno.")) {
                continue;
            }
            String[] property = arg.substring(2).split("=", 2);
            if (property.length == 2 && System.getProperty(property[0]) == null) {
                System.setProperty(property[0], property[1].replace("${library_directory}", libraryDir.toString()));
            }
        }
    }

    private static int fetchLibraries(Version version, Path libraryDir) throws IOException {
        // Whatever is left out is one a processor produces, and horno is about to
        // run the processor that does it.
        List<Version.Library> wanted = version.downloadable();
        for (Version.Library library : wanted) {
            Downloader.ensure(libraryDir.resolve(library.path), library.url, library.sha1, library.name);
        }
        return wanted.size();
    }

    private static String read(String source) throws IOException {
        if (source.startsWith("http://") || source.startsWith("https://")) {
            return new String(Downloader.get(source), StandardCharsets.UTF_8);
        }
        byte[] bytes = Files.readAllBytes(Paths.get(source));
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
