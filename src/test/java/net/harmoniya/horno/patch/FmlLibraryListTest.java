package net.harmoniya.horno.patch;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FmlLibraryListTest {
    /** Bytes after the constant pool. Nothing in a class refers to a constant by offset, so they must survive untouched. */
    private static final byte[] TRAILER = { 0x00, 0x21, 0x00, 0x05, 0x00, 0x06, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 };

    @Test
    void replacesTheNamesAndTheHashes() {
        byte[] original = classFile(
            "cpw/mods/fml/relauncher/CoreFMLLibraries",
            "argo-2.25.jar", "asm-all-4.0.jar",
            "bb672829fde76cb163004752b86b0484bd0a7f4b", "98308890597acb64047f7e896638e0d98753ae82"
        );

        byte[] patched = FmlLibraryList.rewrite(
            original,
            Arrays.asList("argo-2.25.jar", "asm-debug-all-4.0.jar"),
            Arrays.asList("bb672829fde76cb163004752b86b0484bd0a7f4b", "2340f4db0d1a57ba3a430597c42875c827a4cb69")
        );

        List<String> strings = FmlLibraryListTest.strings(patched);
        assertTrue(strings.contains("asm-debug-all-4.0.jar"), strings.toString());
        assertTrue(strings.contains("2340f4db0d1a57ba3a430597c42875c827a4cb69"), strings.toString());
        assertTrue(!strings.contains("asm-all-4.0.jar"), strings.toString());
        // The class name, and everything that is neither a filename nor a hash.
        assertTrue(strings.contains("cpw/mods/fml/relauncher/CoreFMLLibraries"), strings.toString());
    }

    @Test
    void leavesEverythingAfterThePoolAlone() {
        byte[] original = classFile("x", "argo-2.25.jar", "bb672829fde76cb163004752b86b0484bd0a7f4b");

        byte[] patched = FmlLibraryList.rewrite(
            original,
            Collections.singletonList("argo-3.2.jar"),
            Collections.singletonList("58912ea2858d168c50781f956fa5b59f0f7c6b51")
        );

        assertArrayEquals(TRAILER, Arrays.copyOfRange(patched, patched.length - TRAILER.length, patched.length));
    }

    /**
     * Half a patched list is worse than none: FML would then ask for one file
     * horno never placed, and report it as a failure to reach a dead host.
     */
    @Test
    void refusesAListOfADifferentLength() {
        byte[] original = classFile("x", "argo-2.25.jar", "bb672829fde76cb163004752b86b0484bd0a7f4b");

        assertNull(FmlLibraryList.rewrite(
            original,
            Arrays.asList("argo-2.25.jar", "guava-12.0.1.jar"),
            Arrays.asList("bb672829fde76cb163004752b86b0484bd0a7f4b", "b8e78b9af7bf45900e14c6f958486b6ca682195f")
        ));
    }

    /**
     * A Long constant takes two pool slots. Counting it as one shifts every
     * entry after it and turns the class into garbage that still parses.
     */
    @Test
    void survivesAConstantThatTakesTwoSlots() {
        byte[] original = classFileWithLong("argo-2.25.jar", "bb672829fde76cb163004752b86b0484bd0a7f4b");

        byte[] patched = FmlLibraryList.rewrite(
            original,
            Collections.singletonList("argo-3.2.jar"),
            Collections.singletonList("58912ea2858d168c50781f956fa5b59f0f7c6b51")
        );

        assertEquals(Arrays.asList("argo-3.2.jar", "58912ea2858d168c50781f956fa5b59f0f7c6b51"), strings(patched));
    }

    // ── a class file with nothing in it but a constant pool ─────────────────

    private static byte[] classFile(String... utf8) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        header(out, utf8.length + 1);
        for (String text : utf8) {
            utf8(out, text);
        }
        out.write(TRAILER, 0, TRAILER.length);
        return out.toByteArray();
    }

    private static byte[] classFileWithLong(String... utf8) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // The Long occupies slots 1 and 2, so the count is entries + 2 + 1.
        header(out, utf8.length + 3);
        out.write(5);
        for (int i = 0; i < 8; i++) {
            out.write(0);
        }
        for (String text : utf8) {
            utf8(out, text);
        }
        out.write(TRAILER, 0, TRAILER.length);
        return out.toByteArray();
    }

    private static void header(ByteArrayOutputStream out, int poolCount) {
        int[] magic = { 0xca, 0xfe, 0xba, 0xbe, 0x00, 0x00, 0x00, 0x34 };
        for (int b : magic) {
            out.write(b);
        }
        out.write((poolCount >>> 8) & 0xff);
        out.write(poolCount & 0xff);
    }

    private static void utf8(ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.write(1);
        out.write((bytes.length >>> 8) & 0xff);
        out.write(bytes.length & 0xff);
        out.write(bytes, 0, bytes.length);
    }

    /** Read the pool back, the same way the generator does. */
    private static List<String> strings(byte[] file) {
        List<String> found = new java.util.ArrayList<>();
        int at = 8;
        int count = ((file[at] & 0xff) << 8) | (file[at + 1] & 0xff);
        at += 2;
        for (int i = 1; i < count; i++) {
            int tag = file[at++] & 0xff;
            if (tag == 1) {
                int length = ((file[at] & 0xff) << 8) | (file[at + 1] & 0xff);
                at += 2;
                found.add(new String(file, at, length, StandardCharsets.UTF_8));
                at += length;
            } else if (tag == 5 || tag == 6) {
                at += 8;
                i++;
            } else {
                throw new IllegalStateException("unexpected tag " + tag);
            }
        }
        return found;
    }
}
