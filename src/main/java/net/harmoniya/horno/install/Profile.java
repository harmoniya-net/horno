package net.harmoniya.horno.install;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

/**
 * {@code install_profile.json}, read out of the installer jar as a zip.
 *
 * <p>The jar is never put on a classpath. Nothing inside it is loaded as code,
 * which is what makes the shaded Gson it carries — the one that shadowed the
 * game's and killed NeoForge 26.x — permanently harmless.
 *
 * <p>Only spec 1 is read, because only spec 1 has processors. The eras below it
 * (1.6.1-1.12.2 with an inline {@code versionInfo}, and the jar mods before
 * that) never reach this code: their documents name no installer at all.
 */
public final class Profile {
    /** One entry of the profile's {@code libraries} — a tool the processors run on. */
    public static final class Library {
        public final String name;
        public final String path;
        public final String url;
        public final String sha1;

        Library(String name, String path, String url, String sha1) {
            this.name = name;
            this.path = path;
            this.url = url;
            this.sha1 = sha1;
        }
    }

    /** One processor: a jar to run, a classpath to run it on, and arguments. */
    public static final class Processor {
        public final List<String> sides;
        public final String jar;
        public final List<String> classpath;
        public final List<String> args;
        public final Map<String, String> outputs;

        Processor(List<String> sides, String jar, List<String> classpath, List<String> args, Map<String, String> outputs) {
            this.sides = sides;
            this.jar = jar;
            this.classpath = classpath;
            this.args = args;
            this.outputs = outputs;
        }

        /** Absent `sides` means every side, which is how the profile spells "both". */
        public boolean runsOn(String side) {
            return this.sides == null || this.sides.contains(side);
        }
    }

    public final String minecraft;
    public final String version;
    /** Where the installer's own version JSON lives inside the jar. */
    public final String json;
    /** Raw data entries, still in profile spelling: `[coord]`, `/resource` or `'literal'`. */
    public final Map<String, String> data;
    public final List<Processor> processors;
    public final List<Library> libraries;

    private Profile(String minecraft, String version, String json, Map<String, String> data, List<Processor> processors, List<Library> libraries) {
        this.minecraft = minecraft;
        this.version = version;
        this.json = json;
        this.data = Collections.unmodifiableMap(data);
        this.processors = Collections.unmodifiableList(processors);
        this.libraries = Collections.unmodifiableList(libraries);
    }

    /** Read the profile and the installer's version JSON out of one installer jar. */
    public static Profile read(ZipFile installer, String side) throws IOException, JsonParserException {
        JsonObject root = JsonParser.object().from(entry(installer, "install_profile.json"));

        int spec = root.getInt("spec", 0);
        if (spec != 1) {
            throw new IllegalArgumentException("Unsupported install profile spec " + spec + "; horno reads spec 1");
        }

        Map<String, String> data = new LinkedHashMap<>();
        JsonObject rawData = root.getObject("data");
        if (rawData == null) {
            throw new IllegalArgumentException("The install profile has no `data`");
        }
        for (Map.Entry<String, Object> entry : rawData.entrySet()) {
            JsonObject sides = rawData.getObject(entry.getKey());
            if (sides == null) {
                throw new IllegalArgumentException("`data." + entry.getKey() + "` is not an object");
            }
            String value = sides.getString(side);
            if (value != null) {
                data.put(entry.getKey(), value);
            }
        }

        List<Processor> processors = new ArrayList<>();
        JsonArray rawProcessors = root.getArray("processors");
        if (rawProcessors == null) {
            throw new IllegalArgumentException("The install profile has no `processors`");
        }
        for (int i = 0; i < rawProcessors.size(); i++) {
            JsonObject p = rawProcessors.getObject(i);
            if (p == null) {
                throw new IllegalArgumentException("`processors[" + i + "]` is not an object");
            }
            String jar = p.getString("jar");
            if (jar == null) {
                throw new IllegalArgumentException("`processors[" + i + "]` names no jar");
            }
            processors.add(new Processor(
                strings(p.getArray("sides"), null),
                jar,
                strings(p.getArray("classpath"), Collections.<String>emptyList()),
                strings(p.getArray("args"), Collections.<String>emptyList()),
                map(p.getObject("outputs"))
            ));
        }

        List<Library> libraries = new ArrayList<>();
        JsonArray rawLibraries = root.getArray("libraries");
        if (rawLibraries == null) {
            throw new IllegalArgumentException("The install profile has no `libraries`");
        }
        for (int i = 0; i < rawLibraries.size(); i++) {
            JsonObject library = rawLibraries.getObject(i);
            JsonObject downloads = library == null ? null : library.getObject("downloads");
            JsonObject artifact = downloads == null ? null : downloads.getObject("artifact");
            if (artifact == null) {
                throw new IllegalArgumentException("`libraries[" + i + "]` declares no artifact to download");
            }
            libraries.add(new Library(library.getString("name"), artifact.getString("path"), artifact.getString("url"), artifact.getString("sha1")));
        }

        String minecraft = root.getString("minecraft");
        String json = root.getString("json");
        if (minecraft == null || json == null) {
            throw new IllegalArgumentException("The install profile names no `minecraft` or no `json`");
        }
        return new Profile(minecraft, root.getString("version"), json, data, processors, libraries);
    }

    static String entry(ZipFile zip, String name) throws IOException {
        java.util.zip.ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            throw new IOException(zip.getName() + " has no " + name + ", so it is not an installer horno can read");
        }
        try (InputStream in = zip.getInputStream(entry)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                bytes.write(buffer, 0, read);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> strings(JsonArray array, List<String> absent) {
        if (array == null) {
            return absent;
        }
        List<String> values = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            String value = array.getString(i);
            if (value == null) {
                throw new IllegalArgumentException("Expected a string, got " + array.get(i));
            }
            values.add(value);
        }
        return values;
    }

    private static Map<String, String> map(JsonObject object) {
        Map<String, String> values = new LinkedHashMap<>();
        if (object != null) {
            for (Map.Entry<String, Object> entry : object.entrySet()) {
                values.put(entry.getKey(), object.getString(entry.getKey()));
            }
        }
        return values;
    }
}
