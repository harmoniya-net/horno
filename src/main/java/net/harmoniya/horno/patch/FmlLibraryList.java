package net.harmoniya.horno.patch;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A patch to Forge's own list of the libraries FML fetches for itself.
 *
 * <p>FML 4.x and 5.x carry that list in
 * {@code cpw/mods/fml/relauncher/CoreFMLLibraries}: two parallel
 * {@code String[]}s, filenames and sha1s, and a root URL at
 * {@code files.minecraftforge.net/fmllibs} which has been gone for years. Horno
 * puts the files where FML looks, so the dead host is never reached — but one
 * of the files was only ever published there, and no copy of it exists on maven
 * under any name. The document answers that one with a different artifact
 * carrying the same classes, and FML has to be told, because it validates the
 * sha1 of what it finds.
 *
 * <p>So this rewrites the list to say what the document says. It is a patch to
 * somebody else's code and is treated as one: it changes only string constants,
 * never the shape of the class; it refuses outright when the list it finds is
 * not the length the document describes, rather than patching half of it; and
 * what it writes is exactly what horno then puts on disk, so the two cannot
 * disagree.
 *
 * <p>What it deliberately does not touch is the root URL. If horno ever fails
 * to place a file, FML reaching a dead host is a recognisable symptom; a
 * rewritten URL would turn it into a puzzling one.
 */
final class FmlLibraryList {
    private FmlLibraryList() {
    }

    static final String ENTRY = "cpw/mods/fml/relauncher/CoreFMLLibraries.class";

    /** The filenames the class asks for, in order. */
    static List<String> names(byte[] classFile) {
        List<String> names = new ArrayList<>();
        for (Entry entry : readPool(classFile)) {
            if (isFileName(entry.text)) {
                names.add(entry.text);
            }
        }
        return names;
    }

    /** The sha1s the class expects, in the same order as {@link #names}. */
    static List<String> hashes(byte[] classFile) {
        List<String> hashes = new ArrayList<>();
        for (Entry entry : readPool(classFile)) {
            if (isSha1(entry.text)) {
                hashes.add(entry.text);
            }
        }
        return hashes;
    }

    /**
     * The class file, with its filenames and hashes replaced by the given ones.
     *
     * @return the rewritten class, or {@code null} when the class does not
     *         describe the list the document does — in which case the caller
     *         should leave Forge's own bytes alone and say so.
     */
    static byte[] rewrite(byte[] classFile, List<String> names, List<String> hashes) {
        List<Entry> pool = readPool(classFile);

        List<Entry> foundNames = new ArrayList<>();
        List<Entry> foundHashes = new ArrayList<>();
        for (Entry entry : pool) {
            if (isFileName(entry.text)) {
                foundNames.add(entry);
            } else if (isSha1(entry.text)) {
                foundHashes.add(entry);
            }
        }

        if (foundNames.size() != names.size() || foundHashes.size() != hashes.size()) {
            return null;
        }
        for (int i = 0; i < foundNames.size(); i++) {
            foundNames.get(i).text = names.get(i);
        }
        for (int i = 0; i < foundHashes.size(); i++) {
            foundHashes.get(i).text = hashes.get(i);
        }
        return write(classFile, pool);
    }

    private static boolean isFileName(String text) {
        return text.endsWith(".jar") || text.endsWith(".zip");
    }

    private static boolean isSha1(String text) {
        if (text.length() != 40) {
            return false;
        }
        for (int i = 0; i < 40; i++) {
            char c = text.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }

    /** One UTF8 constant, and where it sits in the file. */
    private static final class Entry {
        final int at;
        final int length;
        String text;

        Entry(int at, int length, String text) {
            this.at = at;
            this.length = length;
            this.text = text;
        }
    }

    /**
     * Every UTF8 constant, in pool order.
     *
     * <p>Long and Double take two pool slots each. Missing that shifts every
     * index after them and produces a class file that verifies as garbage.
     */
    private static List<Entry> readPool(byte[] file) {
        List<Entry> pool = new ArrayList<>();
        int at = 8;
        int count = u2(file, at);
        at += 2;
        for (int i = 1; i < count; i++) {
            int tag = file[at++] & 0xff;
            switch (tag) {
                case 1: {
                    int length = u2(file, at);
                    at += 2;
                    pool.add(new Entry(at - 3, length, new String(file, at, length, StandardCharsets.UTF_8)));
                    at += length;
                    break;
                }
                case 5:
                case 6:
                    at += 8;
                    i++;
                    break;
                case 3:
                case 4:
                case 9:
                case 10:
                case 11:
                case 12:
                case 17:
                case 18:
                    at += 4;
                    break;
                case 15:
                    at += 3;
                    break;
                case 7:
                case 8:
                case 16:
                case 19:
                case 20:
                    at += 2;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown constant pool tag " + tag);
            }
        }
        return pool;
    }

    /**
     * The file back out with the given constants.
     *
     * <p>Everything outside the pool is copied byte for byte — the pool is the
     * only part that changes length, and nothing in a class file refers to a
     * constant by offset.
     */
    private static byte[] write(byte[] file, List<Entry> pool) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(file.length + 64);
        int copied = 0;
        for (Entry entry : pool) {
            out.write(file, copied, entry.at - copied);
            byte[] text = entry.text.getBytes(StandardCharsets.UTF_8);
            out.write(1);
            out.write((text.length >>> 8) & 0xff);
            out.write(text.length & 0xff);
            out.write(text, 0, text.length);
            copied = entry.at + 3 + entry.length;
        }
        out.write(file, copied, file.length - copied);
        return out.toByteArray();
    }

    private static int u2(byte[] file, int at) {
        return ((file[at] & 0xff) << 8) | (file[at + 1] & 0xff);
    }
}
