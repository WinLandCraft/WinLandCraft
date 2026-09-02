package dev.winlandcraft;

import net.fabricmc.loader.api.FabricLoader;
import org.cef.network.CefRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Network-only fallback for platforms without the native Brave-compatible engine. */
final class UBlockLite {
    private static final String VERSION = "2026.901.1442";
    private static final String SHA256 = "71a8c65573489e0702f6398d3d744fcbe2bbc48336a75781c90bec50acb00b7e";
    private static final URI DOWNLOAD = URI.create("https://github.com/uBlockOrigin/uBOL-home/releases/download/"
            + VERSION + "/uBOLite_" + VERSION + ".chromium.zip");
    private static final List<String> RULESETS = List.of(
            "ublock-filters", "easylist", "easyprivacy", "pgl", "ublock-badware", "urlhaus-full");
    private static final AtomicBoolean LOADING = new AtomicBoolean();
    private static final LongAdder BLOCKED = new LongAdder();
    private static final AtomicLong NEXT_HEALTH_LOG = new AtomicLong();
    private static volatile UBlockRules rules;

    private UBlockLite() {}

    static void enable() {
        if (LOADING.compareAndSet(false, true)) CompletableFuture.runAsync(UBlockLite::loadRules);
    }

    static boolean blocks(String url, String source, String top, CefRequest.ResourceType type, String method) {
        UBlockRules current = rules;
        if (current == null || !current.blocks(url, source, top, type, method)) return false;
        BLOCKED.increment();
        logHealth(current);
        return true;
    }

    private static void loadRules() {
        try {
            Path directory = FabricLoader.getInstance().getConfigDir().resolve("winlandcraft/ublock");
            Path archive = VerifiedDownload.fetch(directory, "uBOLite_" + VERSION + ".chromium.zip",
                    DOWNLOAD, SHA256, 64L * 1024 * 1024);
            try (var zip = new ZipFile(archive.toFile())) {
                var streams = new ArrayList<InputStream>();
                for (String ruleset : RULESETS) {
                    add(zip, streams, "rulesets/main/" + ruleset + ".json", true);
                    add(zip, streams, "rulesets/regex/" + ruleset + ".json", false);
                }
                rules = UBlockRules.load(streams);
            }
            WinLandCraftClient.LOGGER.warn("Native adblock unavailable; uBlock Origin Lite {} fallback loaded with {} network rules",
                    VERSION, rules.size());
        } catch (Exception failure) {
            WinLandCraftClient.LOGGER.error("Fallback adblock rules could not be loaded; browsing will continue unfiltered", failure);
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

    private static void logHealth(UBlockRules current) {
        long now = System.currentTimeMillis(), next = NEXT_HEALTH_LOG.get();
        if (now >= next && NEXT_HEALTH_LOG.compareAndSet(next, now + 30_000))
            WinLandCraftClient.LOGGER.info("uBlock Origin Lite fallback health: blocked={}, rules={}",
                    BLOCKED.sum(), current.size());
    }
}
