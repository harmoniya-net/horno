package net.harmoniya.horno.detector;

import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;

public interface IFileDetector {
    /**
     * @return The name of the detector.
     */
    String name();

    /**
     * If there are two or more detectors are enabled, an exception will be thrown. Removing anything from the map is in vain.
     * @param others Other detectors.
     * @return True represents enabled.
     */
    boolean enabled(HashMap<String, IFileDetector> others);

    /**
     * @return The ".minecraft/libraries" folder for normal. It can also be defined by JVM argument "-Dhorno.librariesDir=&lt;libraries-path&gt;".
     */
    default Path getLibraryDir() {
        String libraryDir = System.getProperty("horno.librariesDir");
        if (libraryDir != null) {
            return Paths.get(libraryDir).toAbsolutePath();
        }
        try {
            URL launcherLocation = null;
            String[] classNames = {
                "cpw/mods/modlauncher/Launcher.class",
                "net/neoforged/fml/loading/FMLLoader.class"
            };

            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            for (String classResource : classNames) {
                URL url = cl.getResource(classResource);
                if (url != null) {
                    String path = url.toString();
                    if (path.startsWith("jar:") && path.contains("!")) {
                        path = path.substring(4, path.indexOf('!'));
                        try {
                            launcherLocation = new URL(path);
                            break;
                        } catch (MalformedURLException e) {
                              // ignore and try next
                        }
                    }
                }
            }

            if (launcherLocation == null) {
                    throw new UnsupportedOperationException("Could not detect the libraries folder - it can be manually specified with `-Dhorno.librariesDir=` (Java runtime argument)");
            }
            Path launcher = Paths.get(launcherLocation.toURI());

            while (!launcher.getFileName().toString().equals("libraries")) {
                launcher = launcher.getParent();

                if (launcher == null || launcher.getFileName() == null) {
                    throw new UnsupportedOperationException("Could not detect the libraries folder - it can be manually specified with `-Dhorno.librariesDir=` (Java runtime argument)");
                }
            }

            return launcher.toAbsolutePath();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Where the loader's installer belongs.
     *
     * <p>It is a path, not a file: the installer is no longer declared as a
     * library, so nothing has put it there. Horno fetches it itself from
     * {@code -Dhorno.installerUrl} and checks it against
     * {@code -Dhorno.installerSha1}. The destination stays the maven path under
     * {@code libraries/}, which is where it has always been.
     *
     * @param forgeGroup Forge package group (e.g. net.neoforged).
     * @param forgeArtifact Forge package artifact (e.g. forge).
     * @param forgeFullVersion Forge full version (e.g. 1.14.4-28.2.0).
     * @return The installer jar path. It can also be defined by JVM argument "-Dhorno.installer=&lt;installer-path&gt;".
     */
    default Path getInstallerJar(String forgeGroup, String forgeArtifact, String forgeFullVersion) {
        String installer = System.getProperty("horno.installer");
        if (installer != null) {
            return Paths.get(installer).toAbsolutePath();
        }
        return null;
    }

    /**
     * @param mcVersion Minecraft version (e.g. 1.14.4).
     * @return The minecraft client jar path. It can also be defined by JVM argument "-Dhorno.minecraft=&lt;minecraft-path&gt;".
     */
    default Path getMinecraftJar(String mcVersion) {
        String minecraft = System.getProperty("horno.minecraft");
        if (minecraft != null) {
            return Paths.get(minecraft).toAbsolutePath();
        }
        return null;
    }
}
