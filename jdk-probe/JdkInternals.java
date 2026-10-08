import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.spi.URLStreamHandlerProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.harmoniya.horno.util.ModuleUtil;

/**
 * Does this JDK still keep its internals where horno reaches for them?
 *
 * <p>Three things in {@code ModuleUtil} go through fields and methods no JDK
 * promises to keep. Two of them fail soft — horno prints a line and carries
 * on, because only one Forge build each depends on them — so a JDK that moves
 * a field would show up as that build crashing, months later, with nothing
 * pointing here. This asks the same questions those builds do, of the jar that
 * is published, and exits non-zero on the first wrong answer.
 *
 * <p>Run by {@code run.sh}, which sets up the class path this expects.
 */
public class JdkInternals {
    /** Says which protocols the JVM asked a provider about. */
    public static class Provider extends URLStreamHandlerProvider {
        static final Set<String> ASKED = ConcurrentHashMap.newKeySet();

        @Override
        public URLStreamHandler createURLStreamHandler(String protocol) {
            ASKED.add(protocol);
            if (!"hornoprobe".equals(protocol)) {
                return null;
            }
            return new URLStreamHandler() {
                @Override
                protected URLConnection openConnection(URL url) {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }

    public static void main(String[] args) throws Throwable {
        System.out.println("java " + Runtime.version());
        urlHandlers();
        classPath(args[0], args[1]);
        opens();
        System.out.println("ok");
    }

    /** Forge 1.20.2: no provider may be loaded to answer for http or https. */
    private static void urlHandlers() throws Throwable {
        ModuleUtil.claimUrlHandlers();
        new URL("http://horno.invalid/");
        new URL("https://horno.invalid/");
        new URL("hornoprobe://horno.invalid/");
        check(Provider.ASKED.contains("hornoprobe"), "the probe's own URL handler provider was never asked, so this checks nothing");
        check(Collections.disjoint(Provider.ASKED, Set.of("http", "https")),
            "claimUrlHandlers: a provider was still asked about " + Provider.ASKED + " — Forge for Minecraft 1.20.2 will fail its first launch");
    }

    /** Forge 26.1: a produced library must be found ahead of what -cp held. */
    private static void classPath(String libraryDir, String produced) throws Throwable {
        check("old".equals(which()), "the class path is not what run.sh sets up: " + which());
        ModuleUtil.setupClassPath(Paths.get(libraryDir), Collections.singletonList(produced));
        check("new".equals(which()),
            "setupClassPath: the produced library is not first on the class path (found \"" + which() + "\") — Forge for Minecraft 26.1 will crash on start");
    }

    /** Every modular loader: --add-opens applied after the JVM has started. */
    private static void opens() throws Throwable {
        ModuleUtil.addOpens(Collections.singletonList("java.base/java.lang.invoke=ALL-UNNAMED"));
        Field field = Class.forName("java.lang.invoke.MethodHandles$Lookup").getDeclaredField("IMPL_LOOKUP");
        try {
            field.setAccessible(true);
        } catch (RuntimeException e) {
            throw new AssertionError("addOpens: java.lang.invoke is still closed — no modular Forge or NeoForge will start", e);
        }
    }

    private static String which() throws Throwable {
        try (InputStream in = ClassLoader.getSystemResourceAsStream("horno-probe/which")) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
