package net.harmoniya.horno.patch;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import net.harmoniya.horno.Flags;

/**
 * The pre-1.13 launch, once {@link JarMod} has built the jar.
 *
 * <p>{@code horno.mainClass} — the class to hand off to. Its presence is what
 * selects this mode.
 *
 * <p>Arguments reach the real main class untouched, exactly as in the processor
 * era: horno is a shim, not a launcher.
 */
public class JarModLauncher {
    public static void launch(String[] args) throws Throwable {
        String mainClass = System.getProperty("horno.mainClass");
        Path client = JarMod.requirePath("horno.minecraft");
        Path patched = JarMod.install();

        if (Flags.installOnly()) {
            System.out.println("[horno] patched " + patched + "; horno.installOnly set, not launching");
            return;
        }

        ClassLoader loader = newClassLoader(client, patched);
        Thread.currentThread().setContextClassLoader(loader);
        Class.forName(mainClass, false, loader)
            .getMethod("main", String[].class)
            .invoke(null, new Object[] { args });
    }

    /**
     * The patched jar has to be reached instead of the vanilla one, not as well
     * as it — appending to the running classpath cannot shadow what is already
     * there, and a launcher that honours {@code inheritsFrom} always puts the
     * inherited client jar on {@code -cp}. The Mojang format has no way to ask
     * it not to, so the classpath is rebuilt here rather than negotiated.
     *
     * <p>Order is what makes that correct, not the exclusion. The patched jar
     * goes first and is a superset of the vanilla one — client entries, overlay
     * entries on top, {@code META-INF} gone — so nothing the game asks for can
     * resolve past it. Dropping the vanilla jar is only the case that can be
     * recognised: a launcher is free to place a second copy of it under a name
     * of its own, typically {@code versions/<id>/<id>.jar}, and that copy is
     * indistinguishable from any other classpath entry.
     *
     * <p>Horno's own jar goes too, and for a different reason. FML scans every
     * source on this loader for mods with the ASM it shipped with — ASM 4.0 in
     * 1.4.7 — which cannot read anything newer than Java 7 bytecode and throws
     * {@code IllegalArgumentException} on horno's classes, reported as
     * {@code probably a corrupt zip}. Horno has already done its whole job by
     * this point; the game has no use for it. It is an input to the launch, not
     * a library in it, which is the same rule that keeps the installer off the
     * classpath in the processor era.
     *
     * <p>A {@link URLClassLoader} is also what LaunchWrapper expects — its
     * {@code Launch} casts its own loader to one to read the sources for
     * {@code LaunchClassLoader}, which is a cast that fails outright against
     * the Java 9+ application loader.
     */
    static ClassLoader newClassLoader(Path client, Path patched) throws Exception {
        Path vanilla = client.toAbsolutePath().normalize();
        Path self = ownJar();
        List<URL> urls = new ArrayList<>();
        urls.add(patched.toUri().toURL());
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            Path path = Paths.get(entry).toAbsolutePath().normalize();
            if (!path.equals(vanilla) && !path.equals(self)) {
                urls.add(path.toUri().toURL());
            }
        }
        // Platform loader on 9+, null (bootstrap) on 8 — either way the
        // application loader, and with it the vanilla jar, stays out of reach.
        return URLClassLoader.newInstance(urls.toArray(new URL[0]), platformClassLoader());
    }

    /** Where horno itself is, or null when it cannot tell — a directory, a test. */
    private static Path ownJar() {
        try {
            java.security.CodeSource source = JarModLauncher.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            return Paths.get(source.getLocation().toURI()).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static ClassLoader platformClassLoader() {
        try {
            return (ClassLoader) ClassLoader.class.getMethod("getPlatformClassLoader").invoke(null);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
