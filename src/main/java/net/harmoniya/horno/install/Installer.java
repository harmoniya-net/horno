package net.harmoniya.horno.install;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.grack.nanojson.JsonParserException;

import net.harmoniya.horno.Downloader;
import net.harmoniya.horno.Flags;
import net.harmoniya.horno.Version;
import net.harmoniya.horno.util.ModuleUtil;

/**
 * An installer jar, opened as data.
 *
 * <p>Horno used to run the loader's installer by loading its classes and calling
 * into them — {@code DownloadUtils.downloadLibrary}, {@code PostProcessors},
 * a private {@code outputs} field. Five reflective pins into somebody else's
 * private API, and the two families had already drifted apart under three of
 * them. The install profile is a published format; the processors are ordinary
 * jars with a {@code Main-Class}. So horno reads the format and runs the jars,
 * and the installer's own code is never loaded at all.
 *
 * <p>That is also what makes the shaded Gson inside a NeoForge installer
 * permanently harmless: the jar is a zip here, never a classpath entry.
 *
 * <p>What is emphatically <em>not</em> reimplemented is the work: jar splitting,
 * renaming and binary patching stay in Forge's and NeoForge's own tools, which
 * this drives. Horno owns the driver, not the transformation.
 */
public final class Installer implements Closeable {
    /** Horno installs clients. The profile's server half is read and ignored. */
    private static final String SIDE = "client";

    private final Path jarPath;
    private final ZipFile jar;
    private final Profile profile;
    private final Version version;

    private Installer(Path jarPath, ZipFile jar, Profile profile, Version version) {
        this.jarPath = jarPath;
        this.jar = jar;
        this.profile = profile;
        this.version = version;
    }

    public static Installer open(Path installerJar) throws IOException, JsonParserException {
        ZipFile jar = new ZipFile(installerJar.toFile());
        try {
            Profile profile = Profile.read(jar, SIDE);
            // `json` is a resource path, so it is absolute inside the jar.
            String name = profile.json.startsWith("/") ? profile.json.substring(1) : profile.json;
            Version version = Version.parse(Profile.entry(jar, name));
            return new Installer(installerJar, jar, profile, version);
        } catch (IOException | JsonParserException | RuntimeException e) {
            jar.close();
            throw e;
        }
    }

    public String mainClass() {
        return this.version.mainClass;
    }

    /**
     * The JVM line the loader expects, which horno applies after the fact.
     *
     * <p>A rule-tagged argument here is refused rather than dropped: a missing
     * `--add-opens` surfaces much later and somewhere else. None of the
     * installers published so far carries one — their own reader binds this
     * array to `String[]`, so a build that did would already be unreadable by
     * the installer that ships it.
     */
    public List<String> jvmArgs() {
        if (this.version.hasConditionalJvmArgs) {
            throw new IllegalStateException("The installer's version JSON has a conditional jvm argument, which horno cannot apply");
        }
        return this.version.jvmArgs;
    }

    public List<String> producedLibraries() {
        return this.version.produced();
    }

    /**
     * Put the tools on disk, then run every client-side processor that is not
     * already satisfied.
     *
     * <p>Libraries come first and all at once, so that a missing one fails
     * before any processor has written anything — which is also all
     * {@code horno.offline} needs to be true, since every fetch below is ours.
     */
    public void install(Path libraryDir, Path minecraftJar) throws IOException {
        for (Profile.Library library : this.profile.libraries) {
            obtain(library, libraryDir);
        }

        Path temp = Files.createTempDirectory("horno-install");
        try {
            Map<String, String> data = data(libraryDir, minecraftJar, temp);
            boolean cacheable = !Flags.forceProcessors() && derivedFilesPresent(libraryDir);


            for (Profile.Processor processor : this.profile.processors) {
                if (!processor.runsOn(SIDE)) {
                    continue;
                }
                Map<String, String> outputs = resolveOutputs(processor, data, libraryDir);
                if (satisfied(processor, outputs, cacheable)) {
                    System.out.println("[horno] " + name(processor) + ": up to date");
                    continue;
                }
                run(processor, data, libraryDir);
                verify(processor, outputs);
            }
        } finally {
            deleteTree(temp);
        }
    }

    // ── data ────────────────────────────────────────────────────────────────

    /**
     * The profile's data map, resolved.
     *
     * <p>Three spellings: {@code [coord]} is a file under {@code libraries/},
     * {@code /data/x} is a resource to unpack out of the installer, and anything
     * else is a literal — where {@code 'de86…'} is quoted precisely so a sha1
     * cannot be mistaken for something to substitute.
     */
    private Map<String, String> data(Path libraryDir, Path minecraftJar, Path temp) throws IOException {
        Map<String, String> data = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : this.profile.data.entrySet()) {
            String value = entry.getValue();
            if (isCoord(value)) {
                data.put(entry.getKey(), local(value, libraryDir));
            } else if (value.startsWith("/")) {
                data.put(entry.getKey(), extract(value.substring(1), temp).toString());
            } else {
                data.put(entry.getKey(), Tokens.replace(java.util.Collections.<String, String>emptyMap(), value));
            }
        }

        Path root = libraryDir.toAbsolutePath().getParent();
        data.put("SIDE", SIDE);
        data.put("MINECRAFT_JAR", minecraftJar.toAbsolutePath().toString());
        data.put("MINECRAFT_VERSION", this.profile.minecraft);
        data.put("ROOT", root == null ? libraryDir.toAbsolutePath().toString() : root.toString());
        data.put("INSTALLER", this.jarPath.toAbsolutePath().toString());
        data.put("LIBRARY_DIR", libraryDir.toAbsolutePath().toString());
        return data;
    }

    private static boolean isCoord(String value) {
        return value.length() > 2 && value.charAt(0) == '[' && value.charAt(value.length() - 1) == ']';
    }

    private static String local(String coord, Path libraryDir) {
        Coord parsed = Coord.parse(coord.substring(1, coord.length() - 1));
        return libraryDir.toAbsolutePath().resolve(parsed.path()).toString();
    }

    private Path extract(String name, Path into) throws IOException {
        ZipEntry entry = this.jar.getEntry(name);
        if (entry == null) {
            throw new IOException("The install profile wants " + name + ", which is not in " + this.jarPath.getFileName());
        }
        Path target = into.resolve(name.replace('/', '_'));
        try (InputStream in = this.jar.getInputStream(entry); OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        return target;
    }

    // ── libraries ───────────────────────────────────────────────────────────

    /**
     * One of the processors' tools, on disk.
     *
     * <p>The order matters and is the installer's own: what is already correct
     * is left alone, then the installer's embedded {@code maven/} tree, then the
     * network. The middle step is why {@code horno.offline} is not simply "the
     * file is on disk" — the legacy installers carry every tool they need, and
     * unpacking one is local.
     */
    private void obtain(Profile.Library library, Path libraryDir) throws IOException {
        if (library.path == null) {
            throw new IOException("A library in the install profile declares no path");
        }
        Path target = libraryDir.resolve(library.path);
        if (Files.isRegularFile(target)
            && (Flags.skipVerify() || library.sha1 == null || library.sha1.equalsIgnoreCase(Downloader.sha1(target)))) {
            return;
        }

        ZipEntry embedded = this.jar.getEntry("maven/" + library.path);
        if (embedded != null) {
            System.out.println("[horno] unpacking " + library.name + " from the installer");
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path part = target.resolveSibling(target.getFileName() + ".part");
            try (InputStream in = this.jar.getInputStream(embedded); OutputStream out = Files.newOutputStream(part)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            if (!Flags.skipVerify() && library.sha1 != null && !library.sha1.equalsIgnoreCase(Downloader.sha1(part))) {
                String got = Downloader.sha1(part);
                Files.deleteIfExists(part);
                throw new IOException(library.name + " inside " + this.jarPath.getFileName() + " hashed to " + got + ", not " + library.sha1);
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        Downloader.ensure(target, library.url, library.sha1, library.name);
    }

    // ── processors ──────────────────────────────────────────────────────────

    private static String name(Profile.Processor processor) {
        return Coord.parse(processor.jar).artifact;
    }

    private Map<String, String> resolveOutputs(Profile.Processor processor, Map<String, String> data, Path libraryDir) {
        Map<String, String> outputs = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : processor.outputs.entrySet()) {
            String file = isCoord(entry.getKey()) ? local(entry.getKey(), libraryDir) : Tokens.replace(data, entry.getKey());
            String sha1 = entry.getValue() == null ? null : Tokens.replace(data, entry.getValue());
            outputs.put(file, sha1);
        }
        return outputs;
    }

    /**
     * Whether a processor can be skipped.
     *
     * <p>A processor that declares {@code outputs} says so itself: the files are
     * there and they hash to what the profile expects. Half of them declare
     * none — including the binary patcher, the expensive one — and for those the
     * only evidence available is that every file the profile addresses by
     * coordinate already exists. It is coarse, and deliberately all-or-nothing:
     * one missing derived file re-runs the whole chain rather than guessing
     * which half of it is stale.
     */
    private boolean satisfied(Profile.Processor processor, Map<String, String> outputs, boolean cacheable) throws IOException {
        if (Flags.forceProcessors()) {
            return false;
        }
        if (outputs.isEmpty()) {
            return cacheable;
        }
        for (Map.Entry<String, String> output : outputs.entrySet()) {
            Path file = java.nio.file.Paths.get(output.getKey());
            if (!Files.isRegularFile(file)) {
                return false;
            }
            if (output.getValue() != null && !output.getValue().equalsIgnoreCase(Downloader.sha1(file))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the files the undeclared processors work on are all already there.
     *
     * <p>Only the ones those processors actually name. Scanning every coordinate
     * in the data map instead looks equivalent and is not: {@code MC_UNPACKED} is
     * written by a server-side processor and never exists in a client install, so
     * a whole-map check is false forever and nothing is ever cached.
     *
     * <p>All-or-nothing on purpose. These processors say nothing about what they
     * produce, so one missing file is no evidence about which of them is stale,
     * and re-running the chain is the only answer that cannot be wrong.
     */
    private boolean derivedFilesPresent(Path libraryDir) {
        for (Profile.Processor processor : this.profile.processors) {
            if (!processor.runsOn(SIDE) || !processor.outputs.isEmpty()) {
                continue;
            }
            for (String arg : processor.args) {
                for (String key : tokensIn(arg)) {
                    String value = this.profile.data.get(key);
                    if (value != null && isCoord(value) && !Files.isRegularFile(java.nio.file.Paths.get(local(value, libraryDir)))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** The `{KEY}` names in one profile string, ignoring escapes and quoting. */
    private static List<String> tokensIn(String value) {
        List<String> keys = new ArrayList<>();
        int at = 0;
        while (true) {
            int open = value.indexOf('{', at);
            if (open < 0) {
                return keys;
            }
            int close = value.indexOf('}', open);
            if (close < 0) {
                return keys;
            }
            keys.add(value.substring(open + 1, close));
            at = close + 1;
        }
    }

    private void run(Profile.Processor processor, Map<String, String> data, Path libraryDir) throws IOException {
        Path tool = libraryDir.resolve(Coord.parse(processor.jar).path());
        String main = mainClassOf(tool);

        List<URL> urls = new ArrayList<>();
        for (String entry : processor.classpath) {
            urls.add(libraryDir.resolve(Coord.parse(entry).path()).toUri().toURL());
        }
        urls.add(tool.toUri().toURL());

        String[] args = new String[processor.args.size()];
        for (int i = 0; i < args.length; i++) {
            String arg = processor.args.get(i);
            args[i] = isCoord(arg) ? local(arg, libraryDir) : Tokens.replace(data, arg);
        }

        System.out.println("[horno] " + name(processor) + ": running");
        // Not closed, and not by oversight: a tool is free to leave a thread or a
        // lazily-loaded class behind it, and the installer this replaces does not
        // close its loaders either. A handful of open jars for the session is the
        // cheaper mistake.
        ClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), ModuleUtil.getPlatformClassLoader());
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            Method entry = loader.loadClass(main).getMethod("main", String[].class);
            entry.invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IOException(name(processor) + " failed: " + cause, cause);
        } catch (ReflectiveOperationException e) {
            throw new IOException("Could not run " + name(processor) + " (" + main + ")", e);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private void verify(Profile.Processor processor, Map<String, String> outputs) throws IOException {
        for (Map.Entry<String, String> output : outputs.entrySet()) {
            Path file = java.nio.file.Paths.get(output.getKey());
            if (!Files.isRegularFile(file)) {
                throw new IOException(name(processor) + " was supposed to write " + file + ", and did not");
            }
            if (output.getValue() == null || Flags.skipVerify()) {
                continue;
            }
            String got = Downloader.sha1(file);
            if (!output.getValue().equalsIgnoreCase(got)) {
                throw new IOException(name(processor) + " wrote " + file + " hashing to " + got + ", not " + output.getValue());
            }
        }
    }

    private static String mainClassOf(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            String main = manifest == null ? null : manifest.getMainAttributes().getValue("Main-Class");
            if (main == null) {
                throw new IOException(jar.getFileName() + " is named as a processor but has no Main-Class");
            }
            return main;
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
            List<Path> all = new ArrayList<>();
            paths.forEach(all::add);
            java.util.Collections.reverse(all);
            for (Path path : all) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Override
    public void close() throws IOException {
        this.jar.close();
    }
}
