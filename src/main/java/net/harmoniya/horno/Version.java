package net.harmoniya.horno;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

/**
 * A Mojang version JSON, as far as horno reads one.
 *
 * <p>Horno meets two of these and they are the same format: the document a
 * launcher hands it, published by the metadata generator, and the one a loader's
 * installer carries inside itself for the build it installs. One format, one
 * reader — two would be two things to keep in step, and the second would drift.
 *
 * <p>Read by hand out of a tree rather than bound to fields. Databinding is
 * flexible in the wrong direction — a version with no {@code libraries} would
 * quietly become a version with zero of them. Asking for a key and throwing when
 * it is absent is stricter, which is the rule the rest of this stack holds
 * itself to. What is <em>not</em> required is anything horno never reads:
 * demanding a field to prove a point is validation theatre, and it turns a
 * working build into a failing one for no gain.
 */
public final class Version {
    /**
     * One library entry.
     *
     * <p>{@code url} is null or empty when nobody downloads this one: a
     * processor produces it. Those are the entries that have to be appended to
     * the running classpath, because they did not exist when the JVM started.
     */
    public static final class Library {
        public final String name;
        public final String path;
        public final String url;
        public final String sha1;
        public final long size;

        Library(String name, String path, String url, String sha1, long size) {
            this.name = name;
            this.path = path;
            this.url = url;
            this.sha1 = sha1;
            this.size = size;
        }

        public boolean isDownloadable() {
            return this.path != null && this.url != null && !this.url.isEmpty();
        }

        public boolean isProduced() {
            return this.path != null && (this.url == null || this.url.isEmpty());
        }
    }

    public final String id;
    /** The Minecraft version this one is a patch on top of, if it says. */
    public final String inheritsFrom;
    public final String mainClass;
    public final List<Library> libraries;
    public final List<String> jvmArgs;
    /**
     * Whether any JVM argument was rule-tagged rather than a plain string.
     *
     * <p>Only the caller knows whether that matters. Reading `-Dhorno.*` out of a
     * document does not care, because horno's own properties are never
     * conditional; replaying an installer's whole JVM line does, because a
     * dropped `--add-opens` fails much later and somewhere else.
     */
    public final boolean hasConditionalJvmArgs;

    private Version(String id, String inheritsFrom, String mainClass, List<Library> libraries, List<String> jvmArgs, boolean hasConditionalJvmArgs) {
        this.id = id;
        this.inheritsFrom = inheritsFrom;
        this.mainClass = mainClass;
        this.libraries = Collections.unmodifiableList(libraries);
        this.jvmArgs = Collections.unmodifiableList(jvmArgs);
        this.hasConditionalJvmArgs = hasConditionalJvmArgs;
    }

    public static Version parse(String json) throws JsonParserException {
        JsonObject root = JsonParser.object().from(json);

        String mainClass = root.getString("mainClass");
        if (mainClass == null) {
            throw new IllegalArgumentException("The version JSON names no `mainClass`");
        }

        JsonArray entries = root.getArray("libraries");
        if (entries == null) {
            throw new IllegalArgumentException("The version JSON has no `libraries`, so it describes no installation");
        }
        List<Library> libraries = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            JsonObject entry = entries.getObject(i);
            if (entry == null) {
                throw new IllegalArgumentException("`libraries[" + i + "]` is not an object");
            }
            String name = entry.getString("name");
            if (name == null) {
                throw new IllegalArgumentException("`libraries[" + i + "]` has no `name`");
            }
            JsonObject downloads = entry.getObject("downloads");
            JsonObject artifact = downloads == null ? null : downloads.getObject("artifact");
            if (artifact == null) {
                libraries.add(new Library(name, null, null, null, 0));
                continue;
            }
            libraries.add(new Library(name, artifact.getString("path"), artifact.getString("url"), artifact.getString("sha1"), artifact.getLong("size", 0)));
        }

        // A pre-1.13 version JSON has no `arguments` at all — it carries
        // `minecraftArguments`, which horno never reads. Absent means none, not
        // malformed.
        List<String> jvmArgs = new ArrayList<>();
        boolean conditional = false;
        JsonObject arguments = root.getObject("arguments");
        JsonArray jvm = arguments == null ? null : arguments.getArray("jvm");
        if (jvm != null) {
            for (int i = 0; i < jvm.size(); i++) {
                if (jvm.isString(i)) {
                    jvmArgs.add(jvm.getString(i));
                } else {
                    conditional = true;
                }
            }
        }

        return new Version(root.getString("id"), root.getString("inheritsFrom"), mainClass, libraries, jvmArgs, conditional);
    }

    /** The libraries somebody has to fetch. */
    public List<Library> downloadable() {
        List<Library> downloadable = new ArrayList<>();
        for (Library library : this.libraries) {
            if (library.isDownloadable()) {
                downloadable.add(library);
            }
        }
        return downloadable;
    }

    /** The paths of the libraries a processor writes rather than anyone fetching. */
    public List<String> produced() {
        List<String> produced = new ArrayList<>();
        for (Library library : this.libraries) {
            if (library.isProduced()) {
                produced.add(library.path);
            }
        }
        return produced;
    }

    /**
     * The value of {@code -Dhorno.<name>=} as the version JSON spells it, or
     * {@code null}. Placeholders such as {@code ${library_directory}} are still
     * in it — resolving those is the caller's, because only the caller knows
     * where the installation is.
     */
    public String property(String name) {
        String prefix = "-Dhorno." + name + "=";
        for (String arg : this.jvmArgs) {
            if (arg.startsWith(prefix)) {
                return arg.substring(prefix.length());
            }
        }
        return null;
    }
}
