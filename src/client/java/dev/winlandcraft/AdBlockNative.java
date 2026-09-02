package dev.winlandcraft;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

final class AdBlockNative {
    private static volatile boolean ready;
    private static boolean loaded;

    private AdBlockNative() {}

    static synchronized int initialize(AdBlockAssets.Bundle assets) throws IOException {
        if (!loaded) loadLibrary();
        int ruleCount = initialize0(assets.main(), assets.unbreak(), assets.resources());
        if (ruleCount < 0) throw new IOException("Native adblock engine rejected its filter data");
        ready = true;
        return ruleCount;
    }

    static boolean isReady() {
        return ready;
    }

    static String check(String url, String source, String kind, String method) {
        return ready ? check0(url, source, kind, method) : null;
    }

    static String cosmetics(String url) {
        return ready ? cosmetics0(url) : null;
    }

    static String hiddenSelectors(String url, String query) {
        return ready ? hiddenSelectors0(url, query) : "[]";
    }

    private static void loadLibrary() throws IOException {
        String platform = platform();
        String filename = platform.startsWith("windows_") ? "winlandcraft_adblock.dll"
                : platform.startsWith("macos_") ? "libwinlandcraft_adblock.dylib" : "libwinlandcraft_adblock.so";
        String resource = "/META-INF/natives/" + platform + '/' + filename;
        Path directory = FabricLoader.getInstance().getConfigDir().resolve("winlandcraft/native/adblock");
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, filename + '-', ".extract");
        try (InputStream input = AdBlockNative.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("This build does not contain " + resource);
            Files.copy(input, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            String digest = VerifiedDownload.digest(temporary);
            Path library = directory.resolve(digest + '-' + filename);
            if (!Files.isRegularFile(library)) VerifiedDownload.move(temporary, library);
            System.load(library.toAbsolutePath().toString());
            loaded = true;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String platform() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String osName = os.contains("win") ? "windows" : os.contains("mac") ? "macos"
                : os.contains("linux") ? "linux" : null;
        String archName = architecture.equals("amd64") || architecture.equals("x86_64") ? "amd64"
                : architecture.equals("aarch64") || architecture.equals("arm64") ? "arm64" : null;
        if (osName == null || archName == null)
            throw new IOException("Unsupported adblock native platform: " + os + ' ' + architecture);
        return osName + '_' + archName;
    }

    static native int initialize0(byte[] main, byte[] unbreak, byte[] resources);
    static native String check0(String url, String source, String kind, String method);
    static native String cosmetics0(String url);
    static native String hiddenSelectors0(String url, String query);
}
