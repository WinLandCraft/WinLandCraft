package dev.winlandcraft.mixin;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFDownloader;
import com.cinemamod.mcef.MCEFPlatform;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;

/** Installs the ABI-matched native runtime from the client-only companion mod. */
@Mixin(value = MCEFDownloader.class, remap = false)
public abstract class McefRuntimeMixin {
    private static final String RELEASES = "https://github.com/Keksuccino/jcef-rinku/releases/download/java-cef-";
    private static final String BUNDLES = "/META-INF/winlandcraft/cef/";
    private static final String CODEC_MANIFEST = "WINLANDCRAFT-CODEC-BUILD.properties";
    private static final String INSTALL_MARKER = ".winlandcraft-runtime.sha256";

    @Shadow @Final private String javaCefCommitHash;
    @Shadow @Final private MCEFPlatform platform;

    @Inject(method = "getJavaCefDownloadUrl", at = @At("HEAD"), cancellable = true)
    private void winlandcraft$runtimeUrl(CallbackInfoReturnable<String> result) {
        result.setReturnValue(releaseAsset(".tar.gz"));
    }

    @Inject(method = "getJavaCefChecksumDownloadUrl", at = @At("HEAD"), cancellable = true)
    private void winlandcraft$checksumUrl(CallbackInfoReturnable<String> result) {
        result.setReturnValue(releaseAsset(".tar.gz.sha256"));
    }

    @Inject(method = "downloadJavaCefChecksum", at = @At("HEAD"), cancellable = true)
    private void winlandcraft$embeddedChecksum(CallbackInfoReturnable<Boolean> result) throws IOException {
        String name = platform.getNormalizedName();
        byte[] bundled = resourceBytes(name + ".tar.gz.sha256");
        if (bundled == null) return;
        String expected = checksum(bundled);
        Path directory = libraries();
        Path marker = directory.resolve(name).resolve(INSTALL_MARKER);
        boolean current = markerMatches(marker, expected);
        writeAtomically(directory.resolve(name + ".tar.gz.sha256"), bundled);
        result.setReturnValue(current);
    }

    @Inject(method = "downloadJavaCefBuild", at = @At("HEAD"), cancellable = true)
    private void winlandcraft$embeddedRuntime(CallbackInfo callback) throws IOException {
        String name = platform.getNormalizedName();
        try (InputStream input = resource(name + ".tar.gz")) {
            if (input == null) return;
            Path archive = libraries().resolve(name + ".tar.gz");
            Path temporary = Files.createTempFile(libraries(), name + '-', ".tar.gz.part");
            try {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
                verifyArchive(temporary, name);
                moveAtomically(temporary, archive);
            } finally {
                Files.deleteIfExists(temporary);
            }
            MCEF.getLogger().info("Installed WinLandCraft Chromium companion runtime archive for {}", name);
            callback.cancel();
        }
    }

    @Inject(method = "downloadJavaCefBuild", at = @At("RETURN"))
    private void winlandcraft$verifyRuntime(CallbackInfo callback) throws IOException {
        String name = platform.getNormalizedName();
        Path directory = libraries();
        String expected = Files.readString(directory.resolve(name + ".tar.gz.sha256"))
                .strip().split("\\s+", 2)[0];
        String actual = sha256(directory.resolve(name + ".tar.gz"));
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII))) {
            throw new IOException("Downloaded JCEF archive failed SHA-256 verification");
        }
    }

    @Inject(method = "extractJavaCefBuild", at = @At("RETURN"))
    private void winlandcraft$verifyExtractedRuntime(boolean deleteAfter, CallbackInfo callback) throws IOException {
        String name = platform.getNormalizedName();
        try (InputStream bundled = resource(name + ".tar.gz")) {
            if (bundled == null) return;
        }
        Path directory = libraries();
        Path runtime = directory.resolve(name);
        Path manifestFile = runtime.resolve(CODEC_MANIFEST);
        Properties manifest = new Properties();
        try (InputStream input = Files.newInputStream(manifestFile)) {
            manifest.load(input);
        }
        String library = name.startsWith("windows_") ? "libcef.dll"
                : name.startsWith("macos_") ? "Chromium Embedded Framework.framework/Chromium Embedded Framework"
                : "libcef.so";
        String expectedLibrary = manifest.getProperty("libcef.sha256", "").strip();
        String actualLibrary = sha256(runtime.resolve(library));
        if (!MessageDigest.isEqual(expectedLibrary.getBytes(StandardCharsets.US_ASCII),
                actualLibrary.getBytes(StandardCharsets.US_ASCII))) {
            throw new IOException("Extracted codec-enabled libcef failed SHA-256 verification");
        }
        String expectedArchive = checksum(resourceBytes(name + ".tar.gz.sha256"));
        writeAtomically(runtime.resolve(INSTALL_MARKER), (expectedArchive + '\n').getBytes(StandardCharsets.US_ASCII));
        MCEF.getLogger().info("Verified WinLandCraft Chromium companion runtime for {}", name);
    }

    private String releaseAsset(String suffix) {
        return RELEASES + javaCefCommitHash + '/' + platform.getNormalizedName() + suffix;
    }

    private static Path libraries() throws IOException {
        Path directory = Path.of(System.getProperty("mcef.libraries.path"));
        Files.createDirectories(directory);
        return directory;
    }

    private static InputStream resource(String name) {
        return McefRuntimeMixin.class.getResourceAsStream(BUNDLES + name);
    }

    private static byte[] resourceBytes(String name) throws IOException {
        try (InputStream input = resource(name)) {
            return input == null ? null : input.readAllBytes();
        }
    }

    private static boolean markerMatches(Path marker, String expected) {
        if (!Files.isRegularFile(marker)) return false;
        try {
            return expected.equals(checksum(Files.readAllBytes(marker)));
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String checksum(byte[] bytes) throws IOException {
        if (bytes == null) throw new IOException("WinLandCraft Chromium runtime checksum is missing");
        String value = new String(bytes, StandardCharsets.US_ASCII).strip().split("\\s+", 2)[0];
        if (!value.matches("[0-9a-fA-F]{64}")) throw new IOException("Embedded codec runtime checksum is invalid");
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    private static void verifyArchive(Path archive, String name) throws IOException {
        String expected = checksum(resourceBytes(name + ".tar.gz.sha256"));
        String actual = sha256(archive);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII))) {
            throw new IOException("WinLandCraft Chromium archive failed SHA-256 verification");
        }
    }

    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".part");
        try {
            Files.write(temporary, bytes);
            moveAtomically(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(Path file) throws IOException {
        final MessageDigest digest;
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
}
