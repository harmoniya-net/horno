package net.harmoniya.horno.patch;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Builds the client jar that Forge needed an installer to produce before 1.13.
 *
 * Two shapes, one operation. Up to 1.5.1 Forge shipped a "universal" zip of
 * loose class files meant to be copied into minecraft.jar, overwriting what was
 * already there. 1.5.2 shipped an installer whose profile asks only that
 * META-INF be dropped, and puts Forge's own classes on the classpath instead.
 * Both come out of here the same way: overlay entries win, client entries fill
 * in the rest, META-INF is dropped.
 *
 * Dropping META-INF is not tidiness. The vanilla client jar is signed — 1.5.2
 * carries MOJANG_C.SF/.DSA, 1.2.5 carries CODESIGN.SF/.RSA — and a signed jar
 * whose contents no longer match its signature fails to load with a
 * SecurityException. That is why the era's install instructions have always
 * been "delete the META-INF folder", and why Forge's own 1.5.2 profile sets
 * `stripMeta`.
 */
public class ClientPatcher {
    /**
     * The zip epoch (1980-01-01), so repeated runs write the same bytes and a
     * launcher can treat the patched jar as a fixed artifact rather than
     * something that changes under it.
     */
    private static final long FIXED_TIME = 315532800000L;

    public static Path patch(Path client, List<Path> overlays, Path output) throws IOException {
        return patch(client, overlays, output, Collections.<String, byte[]>emptyMap());
    }

    /**
     * @param client The vanilla client jar.
     * @param overlays Archives whose entries replace the client's, in order — empty for the 1.5.2 strip-only case.
     * @param output Where the patched jar goes. Rebuilt only when it is missing or older than an input.
     * @param supplied Entries whose content the caller provides instead of copying — one patched class, in
     *                 practice. They are written wherever the entry would otherwise have come from, and they
     *                 count as inputs: an output carrying different bytes for one of them is out of date, no
     *                 matter what the timestamps say.
     * @return {@code output}.
     */
    public static Path patch(Path client, List<Path> overlays, Path output, Map<String, byte[]> supplied) throws IOException {
        if (isUpToDate(client, overlays, output, supplied)) {
            return output;
        }

        // Sorted, so the entry order is a function of the inputs alone.
        Map<String, ZipFile> owners = new TreeMap<>();
        List<ZipFile> open = new ArrayList<>();
        try {
            ZipFile clientZip = new ZipFile(client.toFile());
            open.add(clientZip);
            collect(clientZip, owners);
            for (Path overlay : overlays) {
                ZipFile overlayZip = new ZipFile(overlay.toFile());
                open.add(overlayZip);
                collect(overlayZip, owners);
            }

            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // Written aside and moved into place, so a failed or interrupted
            // build never leaves a half jar that looks up to date.
            Path temp = output.resolveSibling(output.getFileName() + ".tmp");
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temp))) {
                byte[] buffer = new byte[8192];
                for (Map.Entry<String, ZipFile> owned : owners.entrySet()) {
                    ZipFile source = owned.getValue();
                    ZipEntry entry = new ZipEntry(owned.getKey());
                    entry.setTime(FIXED_TIME);
                    out.putNextEntry(entry);
                    byte[] replacement = supplied.get(owned.getKey());
                    if (replacement != null) {
                        out.write(replacement, 0, replacement.length);
                    } else {
                        try (InputStream in = source.getInputStream(source.getEntry(owned.getKey()))) {
                            copy(in, out, buffer);
                        }
                    }
                    out.closeEntry();
                }
            }
            Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            for (ZipFile zip : open) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                    // Nothing useful to do while unwinding.
                }
            }
        }
        return output;
    }

    private static void collect(ZipFile zip, Map<String, ZipFile> into) {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) {
                continue;
            }
            String name = entry.getName();
            if (name.equals("META-INF") || name.startsWith("META-INF/")) {
                continue;
            }
            into.put(name, zip);
        }
    }

    private static boolean isUpToDate(Path client, List<Path> overlays, Path output, Map<String, byte[]> supplied) throws IOException {
        if (!Files.isRegularFile(output)) {
            return false;
        }
        long built = Files.getLastModifiedTime(output).toMillis();
        if (Files.getLastModifiedTime(client).toMillis() > built) {
            return false;
        }
        for (Path overlay : overlays) {
            if (Files.getLastModifiedTime(overlay).toMillis() > built) {
                return false;
            }
        }
        // A supplied entry has no timestamp to compare — it is computed, not
        // read. So compare the bytes: a jar patched against a different list is
        // out of date however new it is.
        if (!supplied.isEmpty()) {
            try (ZipFile built_ = new ZipFile(output.toFile())) {
                for (Map.Entry<String, byte[]> entry : supplied.entrySet()) {
                    ZipEntry existing = built_.getEntry(entry.getKey());
                    if (existing == null) {
                        return false;
                    }
                    try (InputStream in = built_.getInputStream(existing)) {
                        if (!sameBytes(in, entry.getValue())) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    private static boolean sameBytes(InputStream in, byte[] expected) throws IOException {
        byte[] buffer = new byte[8192];
        int at = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (at + read > expected.length) {
                return false;
            }
            for (int i = 0; i < read; i++) {
                if (buffer[i] != expected[at + i]) {
                    return false;
                }
            }
            at += read;
        }
        return at == expected.length;
    }

    private static void copy(InputStream in, OutputStream out, byte[] buffer) throws IOException {
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }
}
