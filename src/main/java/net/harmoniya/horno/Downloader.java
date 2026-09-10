package net.harmoniya.horno;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Horno's own downloader: GET, sha1, write.
 *
 * <p>Everything else horno fetches goes through the installer's
 * {@code DownloadUtils}, which is better at this than we would be. The
 * installer itself cannot: loading {@code DownloadUtils} needs the installer
 * already. So there is exactly one downloader of our own, and it is
 * deliberately dumb — one request, one check, throw on failure. No retries, no
 * resume, no parallelism, no progress.
 *
 * <p>Dumb is the point. This is the only place in horno that can quietly get a
 * hash wrong, so it should be too simple to hide a bug in.
 */
public final class Downloader {
    private Downloader() {
    }

    private static final int TIMEOUT = 30_000;

    /**
     * Put the file named by {@code url} at {@code target}, unless it is already
     * there and correct.
     *
     * @param target where the file belongs
     * @param url    where to get it, or {@code null} if it can only be already present
     * @param sha1   the expected hash, or {@code null} if the caller has none
     * @param what   how to name this file in an error
     */
    public static Path ensure(Path target, String url, String sha1, String what) throws IOException {
        if (Files.isRegularFile(target)) {
            if (Flags.skipVerify() || sha1 == null || sha1.equalsIgnoreCase(sha1(target))) {
                return target;
            }
            if (Flags.offline()) {
                throw new IOException(what + " at " + target + " does not match sha1 " + sha1 + ", and horno.offline forbids replacing it");
            }
            System.out.println("[horno] " + what + " does not match sha1 " + sha1 + ", fetching it again");
        } else if (Flags.offline()) {
            throw new IOException(what + " is missing at " + target + " (expected sha1 " + sha1 + "), and horno.offline forbids fetching it");
        }

        if (url == null || url.isEmpty()) {
            throw new IOException(what + " is missing at " + target + " and no URL was given for it");
        }

        System.out.println("[horno] fetching " + what + " from " + url);
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // Written aside and moved into place, so an interrupted fetch never
        // leaves a truncated file that a later run would find and trust.
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        try (InputStream in = open(url); OutputStream out = Files.newOutputStream(temp)) {
            copy(in, out);
        }
        if (!Flags.skipVerify() && sha1 != null) {
            String got = sha1(temp);
            if (!sha1.equalsIgnoreCase(got)) {
                Files.deleteIfExists(temp);
                throw new IOException(what + " from " + url + " hashed to " + got + ", not " + sha1);
            }
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /** The bytes at {@code url}. Used for documents, which are read, not stored. */
    public static byte[] get(String url) throws IOException {
        if (Flags.offline()) {
            throw new IOException("horno.offline forbids fetching " + url);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = open(url)) {
            copy(in, bytes);
        }
        return bytes.toByteArray();
    }

    private static InputStream open(String url) throws IOException {
        URLConnection connection = new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT);
        connection.setReadTimeout(TIMEOUT);
        // Maven Central and the GitHub release CDN both answer a default
        // User-Agent; naming ourselves is what makes a horno request legible in
        // someone else's access log.
        connection.setRequestProperty("User-Agent", "horno");
        if (connection instanceof HttpURLConnection) {
            int status = ((HttpURLConnection) connection).getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("GET " + url + " answered " + status);
            }
        }
        return connection.getInputStream();
    }

    public static String sha1(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            // Every JRE ships SHA-1; a JRE that does not cannot run Minecraft.
            throw new IllegalStateException(e);
        }
        byte[] buffer = new byte[8192];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder(40);
        for (byte b : digest.digest()) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }
}
