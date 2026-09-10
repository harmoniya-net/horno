package net.harmoniya.horno;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.grack.nanojson.JsonParserException;

import net.harmoniya.horno.detector.DetectorLoader;
import net.harmoniya.horno.detector.IFileDetector;
import net.harmoniya.horno.install.Installer;
import net.harmoniya.horno.patch.JarModLauncher;
import net.harmoniya.horno.util.ModuleUtil;

/**
 * The three ways in.
 *
 * <ul>
 *   <li>{@code java -jar horno.jar install --document … --root …} — the
 *       standalone installer, which no launcher is involved in.</li>
 *   <li>as a {@code mainClass}, with {@code horno.mainClass} set — the pre-1.13
 *       eras, where installing means rewriting the client jar.</li>
 *   <li>as a {@code mainClass}, without it — the processor era, where the
 *       loader's own installer has to be run first.</li>
 * </ul>
 */
public class Main {
    static final String USAGE = ""
        + "horno - installs Forge and the kinda-Forges\n"
        + "\n"
        + "USAGE\n"
        + "  java -jar horno.jar install --document <url|path> --root <dir>\n"
        + "        Install a published version document into <dir>. Fetches what the\n"
        + "        document lists and runs the loader's install; never touches assets.\n"
        + "        Every era, including the ones where horno never launches anything.\n"
        + "\n"
        + "  java -Dhorno.* ... -cp ... net.harmoniya.horno.Main <game arguments>\n"
        + "        How a launcher runs horno. The version document names the\n"
        + "        properties; nothing has to be passed by hand.\n"
        + "\n"
        + "PROPERTIES\n"
        + "  horno.librariesDir   the libraries folder (detected when absent)\n"
        + "  horno.minecraft      the vanilla client jar\n"
        + "  horno.installer      where the loader's installer belongs\n"
        + "  horno.installerUrl   where to fetch it, with horno.installerSha1\n"
        + "  horno.mainClass      pre-1.13 only: the class to hand off to\n"
        + "  horno.patched        pre-1.13 only: where the patched client jar goes\n"
        + "  horno.jarmod         pre-1.13 only: archives to overlay, path-separated,\n"
        + "                       with horno.jarmodUrl and horno.jarmodSha1\n"
        + "\n"
        + "FLAGS\n"
        + "  -Dhorno.offline           never open a connection\n"
        + "  -Dhorno.installOnly       do the install half and stop\n"
        + "  -Dhorno.forceProcessors   re-run the processors\n"
        + "  -Dhorno.skipVerify        do not hash what is already on disk\n"
        + "\n"
        + "  horno.skipHashCheck is a deprecated spelling of forceProcessors. It never\n"
        + "  skipped a hash check.\n"
        + "\n"
        + "https://github.com/harmoniya-net/horno\n";

    public static void main(String[] args) throws Throwable {
        Flags.warnOnContradictions();

        if (args.length > 0 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            System.out.print(USAGE);
            return;
        }

        // Nothing named, nothing to install, nothing to launch. A launcher always
        // passes the game its arguments and names a mode by property, so this is
        // only ever reached by a person, and only by mistake.
        if (args.length == 0 && System.getProperty("horno.mainClass") == null && System.getProperty("horno.installer") == null) {
            System.err.print(USAGE);
            System.exit(1);
        }

        // Only ever the first argument, and only from a command line: a launcher
        // hands the game its own arguments, which all begin with `--`.
        if (args.length > 0 && "install".equals(args[0])) {
            try {
                Standalone.run(Arrays.copyOfRange(args, 1, args.length));
            } catch (IOException | IllegalArgumentException | JsonParserException e) {
                // Run by hand, so the failures that are the operator's — a wrong
                // path, a missing file under `horno.offline` — are a sentence, not
                // a stack trace. Anything else still gets one.
                System.err.println("horno: " + e.getMessage());
                System.exit(1);
            }
            return;
        }

        // Before 1.13 there is no install profile to run and no --fml.* to read:
        // the whole job is rewriting the client jar. The launcher says so by
        // naming the class to hand off to.
        if (System.getProperty("horno.mainClass") != null) {
            JarModLauncher.launch(args);
            return;
        }

        // --fml.neoForgeVersion 20.2.20-beta --fml.fmlVersion 1.0.2 --fml.mcVersion 1.20.2 --fml.neoFormVersion 20231019.002635 --launchTarget forgeclient

        List<String> argsList = Stream.of(args).collect(Collectors.toList());
        // NOTE: this is only true for NeoForge versions past 20.2.x
        // early versions of NeoForge (for 1.20.1) are not supposed to be covered here
        boolean isNeoForge = argsList.contains("--fml.neoForgeVersion");

        String mcVersion = argsList.get(argsList.indexOf("--fml.mcVersion") + 1);
        String forgeGroup = argsList.contains("--fml.forgeGroup") ? argsList.get(argsList.indexOf("--fml.forgeGroup") + 1) : "net.neoforged";
        String forgeArtifact = isNeoForge ? "neoforge" : "forge";
        String forgeVersionKey = isNeoForge ? "--fml.neoForgeVersion" : "--fml.forgeVersion";
        String forgeVersion = argsList.get(argsList.indexOf(forgeVersionKey) + 1);
        String forgeFullVersion = isNeoForge ? forgeVersion : mcVersion + "-" + forgeVersion;

        IFileDetector detector = DetectorLoader.loadDetector();
        Path installerJar = installer(detector, forgeGroup, forgeArtifact, forgeFullVersion);

        // Vanilla is the launcher's, and the document declares the client jar as
        // a library so that it lands somewhere horno can address. If it is not
        // there, the launcher has not finished and there is nothing horno can
        // usefully do about it.
        Path minecraftJar = detector.getMinecraftJar(mcVersion);
        if (minecraftJar == null || !Files.isRegularFile(minecraftJar)) {
            throw new RuntimeException("Unable to detect the Minecraft jar!");
        }

        try (Installer installer = Installer.open(installerJar)) {
            try {
                Bootstrap.bootstrap(installer.jvmArgs().toArray(new String[0]), minecraftJar.getFileName().toString(), installerJar.getFileName().toString(), detector.getLibraryDir().toAbsolutePath().toString());
            } catch (Throwable t) {
                // Avoid this bunch of hacks that nuke the whole wrapper.
                t.printStackTrace();
            }

            installer.install(detector.getLibraryDir(), minecraftJar);

            if (Flags.installOnly()) {
                System.out.println("[horno] installed; horno.installOnly set, not launching");
                return;
            }

            ModuleUtil.setupClassPath(detector.getLibraryDir(), installer.producedLibraries());
            Class<?> mainClass = ModuleUtil.setupBootstrapLauncher(Class.forName(installer.mainClass()));
            mainClass.getMethod("main", String[].class).invoke(null, new Object[] {args});
        }
    }

    /**
     * The installer jar, fetched if it is not already there.
     *
     * <p>It used to be declared a library, which put fetching it on the
     * launcher — at the cost of also putting it on {@code -cp}, where it is at
     * best inert and at worst fatal: an automatic module named after the loader
     * it installs, and a fat jar whose shaded Gson shadows the game's. It is an
     * input to horno, so horno fetches it.
     */
    static Path installer(IFileDetector detector, String group, String artifact, String fullVersion) throws IOException {
        Path installerJar = detector.getInstallerJar(group, artifact, fullVersion);
        if (installerJar == null) {
            throw new RuntimeException("Unable to work out where the installer belongs - name it with `-Dhorno.installer=`");
        }
        return Downloader.ensure(installerJar, System.getProperty("horno.installerUrl"), System.getProperty("horno.installerSha1"), "the " + artifact + " installer");
    }
}
