package dev.winlandcraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AdBlockNativeChecks {
    private AdBlockNativeChecks() {}

    public static void main(String[] arguments) throws IOException {
        if (arguments.length != 1 && arguments.length != 4)
            throw new AssertionError("Expected native library path, optionally followed by main, unbreak, and resources assets");
        check(AdBlock.bypasses("http://127.0.0.1:49152/token/index"), "local media page bypass");
        check(AdBlock.bypasses("http://127.0.0.1:49152/token/worker.js"), "local media script bypass");
        check(!AdBlock.bypasses("https://127.0.0.1:49152/token/worker.js"), "HTTPS is not the private media server");
        check(!AdBlock.bypasses("http://127.0.0.1.example/token/worker.js"), "lookalike host is not bypassed");
        System.load(arguments[0]);
        if (arguments.length == 4) {
            verifyBraveBundle(arguments);
            return;
        }
        byte[] filters = bytes("||ads.example^\nexample.com##.advert\n##.generic-ad");
        int count = AdBlockNative.initialize0(filters, bytes("@@||ads.example/allowed^"), bytes("[]"));
        equal(4, count, "filter count");
        equal("B", AdBlockNative.check0("https://ads.example/banner.js", "https://example.com",
                "script", "GET"), "network block");
        equal(null, AdBlockNative.check0("https://ads.example/allowed", "https://example.com",
                "script", "GET"), "network exception");

        JsonObject cosmetics = JsonParser.parseString(
                AdBlockNative.cosmetics0("https://example.com/page")).getAsJsonObject();
        check(cosmetics.getAsJsonArray("hide_selectors").asList().stream()
                .anyMatch(value -> ".advert".equals(value.getAsString())), "site cosmetic selector");
        String generic = AdBlockNative.hiddenSelectors0("https://example.com/page", "[[\"generic-ad\"],[]]");
        check(JsonParser.parseString(generic).getAsJsonArray().asList().stream()
                .anyMatch(value -> ".generic-ad".equals(value.getAsString())), "dynamic generic selector");
        System.out.println("Native adblock JNI/network/cosmetic checks passed.");
    }

    private static void verifyBraveBundle(String[] arguments) throws IOException {
        int count = AdBlockNative.initialize0(read(arguments[1]), read(arguments[2]), read(arguments[3]));
        check(count > 100_000, "full Brave filter count");
        JsonObject youtube = JsonParser.parseString(
                AdBlockNative.cosmetics0("https://www.youtube.com/watch?v=dQw4w9WgXcQ")).getAsJsonObject();
        check(youtube.get("injected_script").getAsString().length() > 1_000, "YouTube scriptlet injection");
        System.out.println("Pinned Brave bundle loaded: " + count + " filter lines, "
                + youtube.get("injected_script").getAsString().length() + " bytes of YouTube scriptlets.");
    }

    private static byte[] read(String path) throws IOException {
        return Files.readAllBytes(Path.of(path));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void equal(Object expected, Object actual, String label) {
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
