package net.harmoniya.horno.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The record that an install ran to its end.
 *
 * <p>Half of an installer's processors declare no outputs, the binary patcher
 * among them, so nothing they leave behind says whether they finished. The
 * files they work on being present was taken for that, and it is not the same
 * thing: a chain that failed at its fourth step has left the first three
 * steps' files on disk, and the next launch found them and called the install
 * done. Forge 1.14.3 did exactly this — failed its first launch, passed its
 * second, and had never run its last processor.
 *
 * <p>So finishing is written down. The receipt sits beside the installer and
 * holds the installer's sha1: it is written after the last processor returns,
 * removed before the first one starts, and counts only for the installer it
 * names. An install made before receipts existed has none, and pays for that
 * once, by running its undeclared processors again.
 */
final class Receipt {
    private final Path file;
    private final String installer;

    Receipt(Path installerJar, String installerSha1) {
        this.file = installerJar.resolveSibling(installerJar.getFileName() + ".installed");
        this.installer = installerSha1;
    }

    /** Whether this installer's processors have all run here before. */
    boolean present() {
        try {
            return Files.isRegularFile(this.file)
                && this.installer.equalsIgnoreCase(new String(Files.readAllBytes(this.file), StandardCharsets.UTF_8).trim());
        } catch (IOException unreadable) {
            return false;
        }
    }

    /** Before the first processor: whatever happens next, the install is not finished. */
    void withdraw() throws IOException {
        Files.deleteIfExists(this.file);
    }

    /** After the last processor. */
    void issue() throws IOException {
        Files.write(this.file, (this.installer + "\n").getBytes(StandardCharsets.UTF_8));
    }
}
