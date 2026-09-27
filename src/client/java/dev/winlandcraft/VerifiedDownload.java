package dev.winlandcraft;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

final class VerifiedDownload {
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private VerifiedDownload() {}

    static Path fetch(Path directory, String filename, URI source, String sha256, long maximumBytes)
            throws IOException, InterruptedException {
        Files.createDirectories(directory);
        Path destination = directory.resolve(filename);
        if (Files.isRegularFile(destination) && sha256.equals(digest(destination))) return destination;

        Path temporary = Files.createTempFile(directory, filename + '-', ".download");
        try {
            var request = HttpRequest.newBuilder(source).timeout(Duration.ofMinutes(2)).GET().build();
            HttpResponse<InputStream> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                if (response.statusCode() != 200)
                    throw new IOException("Download returned HTTP " + response.statusCode() + " for " + source);
                long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (declared > maximumBytes) throw new IOException("Download exceeds size limit: " + source);
                try (var output = Files.newOutputStream(temporary)) {
                    byte[] buffer = new byte[64 * 1024];
                    long total = 0;
                    for (int read; (read = input.read(buffer)) >= 0;) {
                        total += read;
                        if (total > maximumBytes) throw new IOException("Download exceeds size limit: " + source);
                        output.write(buffer, 0, read);
                    }
                }
            }
            if (!sha256.equals(digest(temporary)))
                throw new IOException("Download failed SHA-256 verification: " + source);
            move(temporary, destination);
            return destination;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static String digest(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = input.read(buffer)) >= 0;) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static void move(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
