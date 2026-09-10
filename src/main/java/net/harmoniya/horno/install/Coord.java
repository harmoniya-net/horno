package net.harmoniya.horno.install;

/**
 * A maven coordinate, {@code group:artifact:version[:classifier][@extension]}.
 *
 * <p>Everything an install profile points at is written this way — the tools the
 * processors run, their classpaths, and the files they read and write. The only
 * thing horno wants from one is where it lives under {@code libraries/}.
 */
public final class Coord {
    public final String group;
    public final String artifact;
    public final String version;
    public final String classifier;
    public final String extension;

    private Coord(String group, String artifact, String version, String classifier, String extension) {
        this.group = group;
        this.artifact = artifact;
        this.version = version;
        this.classifier = classifier;
        this.extension = extension;
    }

    public static Coord parse(String coord) {
        String rest = coord;
        String extension = "jar";
        int at = rest.indexOf('@');
        if (at >= 0) {
            extension = rest.substring(at + 1);
            rest = rest.substring(0, at);
        }
        String[] parts = rest.split(":");
        if (parts.length < 3 || parts.length > 4) {
            throw new IllegalArgumentException("Not a maven coordinate: " + coord);
        }
        return new Coord(parts[0], parts[1], parts[2], parts.length == 4 ? parts[3] : null, extension);
    }

    /** The path under a libraries directory, always with {@code /} separators. */
    public String path() {
        StringBuilder path = new StringBuilder();
        path.append(this.group.replace('.', '/')).append('/');
        path.append(this.artifact).append('/');
        path.append(this.version).append('/');
        path.append(this.artifact).append('-').append(this.version);
        if (this.classifier != null && !this.classifier.isEmpty()) {
            path.append('-').append(this.classifier);
        }
        return path.append('.').append(this.extension).toString();
    }

    @Override
    public String toString() {
        return this.group + ':' + this.artifact + ':' + this.version
            + (this.classifier == null ? "" : ':' + this.classifier)
            + ("jar".equals(this.extension) ? "" : '@' + this.extension);
    }
}
