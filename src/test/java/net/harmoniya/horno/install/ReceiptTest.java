package net.harmoniya.horno.install;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceiptTest {
    private static final String SHA = "ab306b654c44d659ce69da5d4f87590b66dc91e8";

    @Test
    void nothingIsInstalledUntilItSaysSo(@TempDir Path dir) throws Exception {
        Receipt receipt = new Receipt(dir.resolve("forge-installer.jar"), SHA);
        assertFalse(receipt.present());
        receipt.issue();
        assertTrue(receipt.present());
        assertTrue(Files.isRegularFile(dir.resolve("forge-installer.jar.installed")));
    }

    /** The failure this exists for: a chain that started again and did not finish. */
    @Test
    void aRunThatStartsWithdrawsWhatTheLastOneIssued(@TempDir Path dir) throws Exception {
        Receipt receipt = new Receipt(dir.resolve("forge-installer.jar"), SHA);
        receipt.issue();
        receipt.withdraw();
        assertFalse(receipt.present());
        // And withdrawing what was never issued is not an error.
        receipt.withdraw();
    }

    @Test
    void aReceiptCountsOnlyForTheInstallerItNames(@TempDir Path dir) throws Exception {
        Path installer = dir.resolve("forge-installer.jar");
        new Receipt(installer, SHA).issue();
        assertFalse(new Receipt(installer, "0000000000000000000000000000000000000000").present());
        assertTrue(new Receipt(installer, SHA.toUpperCase()).present());
    }

    @Test
    void anythingElseInTheFileIsNotAReceipt(@TempDir Path dir) throws Exception {
        Path installer = dir.resolve("forge-installer.jar");
        Files.write(dir.resolve("forge-installer.jar.installed"), "".getBytes(StandardCharsets.UTF_8));
        assertFalse(new Receipt(installer, SHA).present());
    }
}
