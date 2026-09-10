package net.harmoniya.horno;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one place horno can quietly get a hash wrong, against a real socket
 * rather than a mock — the request is the only part of this that touches the
 * world, so it is the part worth actually running.
 */
class DownloaderTest {
    private static final byte[] BODY = "horno".getBytes(StandardCharsets.UTF_8);
    /** sha1("horno") */
    private static final String SHA1 = "ed071b59b0d04ad300c3976dfe461df91cdb8dc8";

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (this.server != null) {
            this.server.stop(0);
        }
        System.clearProperty("horno.offline");
        System.clearProperty("horno.skipVerify");
    }

    @Test
    void hashesAFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("body");
        Files.write(file, BODY);

        assertEquals(SHA1, Downloader.sha1(file));
    }

    @Test
    void fetchesAndVerifies(@TempDir Path dir) throws Exception {
        String url = serve(BODY);
        Path target = dir.resolve("deep/down/body.jar");

        Downloader.ensure(target, url, SHA1, "the body");

        assertArrayEquals(BODY, Files.readAllBytes(target));
    }

    @Test
    void refusesWhatDoesNotMatchAndLeavesNothingBehind(@TempDir Path dir) throws Exception {
        String url = serve(BODY);
        Path target = dir.resolve("body.jar");

        IOException error = assertThrows(IOException.class, () -> Downloader.ensure(target, url, "0000000000000000000000000000000000000000", "the body"));

        assertTrue(error.getMessage().contains(SHA1), error.getMessage());
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(dir.resolve("body.jar.part")));
    }

    @Test
    void leavesAFileThatIsAlreadyRight(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("body.jar");
        Files.write(target, BODY);

        // No URL at all: a correct file needs no source, which is what makes a
        // second launch free.
        Downloader.ensure(target, null, SHA1, "the body");

        assertArrayEquals(BODY, Files.readAllBytes(target));
    }

    @Test
    void replacesAFileThatIsWrong(@TempDir Path dir) throws Exception {
        String url = serve(BODY);
        Path target = dir.resolve("body.jar");
        Files.write(target, "stale".getBytes(StandardCharsets.UTF_8));

        Downloader.ensure(target, url, SHA1, "the body");

        assertArrayEquals(BODY, Files.readAllBytes(target));
    }

    @Test
    void offlineNamesTheFileAndTheHashItWanted(@TempDir Path dir) {
        System.setProperty("horno.offline", "true");
        Path target = dir.resolve("body.jar");

        IOException error = assertThrows(IOException.class, () -> Downloader.ensure(target, "https://example.invalid/body.jar", SHA1, "the body"));

        assertTrue(error.getMessage().contains(target.toString()), error.getMessage());
        assertTrue(error.getMessage().contains(SHA1), error.getMessage());
    }

    @Test
    void offlineRefusesToReplaceAFileThatIsWrong(@TempDir Path dir) throws Exception {
        System.setProperty("horno.offline", "true");
        Path target = dir.resolve("body.jar");
        Files.write(target, "stale".getBytes(StandardCharsets.UTF_8));

        assertThrows(IOException.class, () -> Downloader.ensure(target, "https://example.invalid/body.jar", SHA1, "the body"));
    }

    @Test
    void skipVerifyTakesWhateverIsThere(@TempDir Path dir) throws Exception {
        System.setProperty("horno.skipVerify", "true");
        Path target = dir.resolve("body.jar");
        Files.write(target, "stale".getBytes(StandardCharsets.UTF_8));

        Downloader.ensure(target, "https://example.invalid/body.jar", SHA1, "the body");

        assertArrayEquals("stale".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target));
    }

    @Test
    void aStatusThatIsNotSuccessIsAnError(@TempDir Path dir) throws Exception {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/gone", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        this.server.start();
        String url = "http://127.0.0.1:" + this.server.getAddress().getPort() + "/gone";

        IOException error = assertThrows(IOException.class, () -> Downloader.ensure(dir.resolve("body.jar"), url, SHA1, "the body"));

        assertTrue(error.getMessage().contains("404"), error.getMessage());
    }

    private String serve(byte[] body) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/body.jar", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        this.server.start();
        return "http://127.0.0.1:" + this.server.getAddress().getPort() + "/body.jar";
    }
}
