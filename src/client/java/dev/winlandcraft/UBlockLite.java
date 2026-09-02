package dev.winlandcraft;

import com.cinemamod.mcef.MCEF;
import net.fabricmc.loader.api.FabricLoader;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.handler.CefResourceRequestHandlerAdapter;
import org.cef.misc.BoolRef;
import org.cef.network.CefRequest;

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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Loads the official uBlock Origin Lite rules and enforces their network block/allow actions in CEF. */
final class UBlockLite {
    private static final String VERSION = "2026.901.1442";
    private static final String SHA256 = "71a8c65573489e0702f6398d3d744fcbe2bbc48336a75781c90bec50acb00b7e";
    private static final URI DOWNLOAD = URI.create("https://github.com/uBlockOrigin/uBOL-home/releases/download/"
            + VERSION + "/uBOLite_" + VERSION + ".chromium.zip");
    private static final long MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024;
    private static final List<String> RULESETS = List.of(
            "ublock-filters", "easylist", "easyprivacy", "pgl", "ublock-badware", "urlhaus-full");
    private static final CefResourceRequestHandler CANCEL = new CefResourceRequestHandlerAdapter() {
        @Override public boolean onBeforeResourceLoad(CefBrowser browser, CefFrame frame, CefRequest request) {
            return true;
        }
    };
    private static final LongAdder BLOCKED = new LongAdder();
    private static final AtomicLong NEXT_HEALTH_LOG = new AtomicLong();
    private static volatile UBlockRules rules;

    private UBlockLite() {}

    static void install() {
        CompletableFuture.runAsync(UBlockLite::loadRules);
        MCEF.scheduleForInit(success -> {
            if (!success) return;
            MCEF.getClient().getHandle().addRequestHandler(new CefRequestHandlerAdapter() {
                @Override public CefResourceRequestHandler getResourceRequestHandler(
                        CefBrowser browser, CefFrame frame, CefRequest request, boolean navigation,
                        boolean download, String initiator, BoolRef disableDefaultHandling) {
                    UBlockRules current = rules;
                    if (current == null || request == null) return null;
                    String top = browser == null ? "" : browser.getURL();
                    String source = initiator == null || initiator.isBlank()
                            ? frame == null ? top : frame.getURL() : initiator;
                    if (!current.blocks(request.getURL(), source, top,
                            request.getResourceType(), request.getMethod())) return null;
                    BLOCKED.increment();
                    logHealth(current);
                    return CANCEL;
                }
            });
            WinLandCraftClient.LOGGER.info("uBlock Origin Lite request filtering attached to Chromium");
        });
    }

    private static void loadRules() {
        try {
            Path archive = cachedArchive();
            try (var zip = new ZipFile(archive.toFile())) {
                var streams = new ArrayList<InputStream>();
                for (String ruleset : RULESETS) {
                    add(zip, streams, "rulesets/main/" + ruleset + ".json", true);
                    add(zip, streams, "rulesets/regex/" + ruleset + ".json", false);
                }
                rules = UBlockRules.load(streams);
            }
            WinLandCraftClient.LOGGER.info("uBlock Origin Lite {} loaded: {} indexed block/allow rules",
                    VERSION, rules.size());
        } catch (Exception failure) {
            WinLandCraftClient.LOGGER.error("uBlock Origin Lite rules could not be loaded; browsing will continue without request filtering", failure);
        }
    }

    private static void add(ZipFile zip, List<InputStream> streams, String name, boolean required) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            if (required) throw new IOException("Missing uBlock ruleset: " + name);
            return;
        }
        if (entry.getSize() < 0 || entry.getSize() > 16L * 1024 * 1024)
            throw new IOException("Invalid uBlock ruleset size: " + name);
        streams.add(zip.getInputStream(entry));
    }

    private static Path cachedArchive() throws IOException, InterruptedException {
        Path directory = FabricLoader.getInstance().getConfigDir().resolve("winlandcraft/ublock");
        Files.createDirectories(directory);
        Path archive = directory.resolve("uBOLite_" + VERSION + ".chromium.zip");
        if (Files.isRegularFile(archive) && SHA256.equals(digest(archive))) return archive;

        Path temporary = Files.createTempFile(directory, "ublock-", ".download");
        try {
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
            var request = HttpRequest.newBuilder(DOWNLOAD).timeout(Duration.ofMinutes(2)).GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                if (response.statusCode() != 200)
                    throw new IOException("uBlock download returned HTTP " + response.statusCode());
                try (var output = Files.newOutputStream(temporary)) {
                    byte[] buffer = new byte[64 * 1024];
                    long total = 0;
                    for (int read; (read = input.read(buffer)) >= 0;) {
                        total += read;
                        if (total > MAX_DOWNLOAD_BYTES)
                            throw new IOException("uBlock download exceeded size limit");
                        output.write(buffer, 0, read);
                    }
                }
            }
            if (!SHA256.equals(digest(temporary))) throw new IOException("uBlock download failed SHA-256 verification");
            try {
                Files.move(temporary, archive, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, archive, StandardCopyOption.REPLACE_EXISTING);
            }
            return archive;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String digest(Path file) throws IOException {
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

    private static void logHealth(UBlockRules current) {
        long now = System.currentTimeMillis(), next = NEXT_HEALTH_LOG.get();
        if (now >= next && NEXT_HEALTH_LOG.compareAndSet(next, now + 30_000))
            WinLandCraftClient.LOGGER.info("uBlock Origin Lite health: blocked={}, rules={}", BLOCKED.sum(), current.size());
    }
}
