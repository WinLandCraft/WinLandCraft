package dev.winlandcraft.mixin;

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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Points the compatibility layer at the ABI-matched, checksum-published JCEF release. */
@Mixin(value = MCEFDownloader.class, remap = false)
public abstract class McefRuntimeMixin {
    private static final String RELEASES = "https://github.com/Keksuccino/jcef-rinku/releases/download/java-cef-";

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

    @Inject(method = "downloadJavaCefBuild", at = @At("RETURN"))
    private void winlandcraft$verifyRuntime(CallbackInfo callback) throws IOException {
        Path directory = Path.of(System.getProperty("mcef.libraries.path"));
        String name = platform.getNormalizedName();
        String expected = Files.readString(directory.resolve(name + ".tar.gz.sha256"))
                .strip().split("\\s+", 2)[0];
        String actual = sha256(directory.resolve(name + ".tar.gz"));
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII))) {
            throw new IOException("Downloaded JCEF archive failed SHA-256 verification");
        }
    }

    private String releaseAsset(String suffix) {
        return RELEASES + javaCefCommitHash + '/' + platform.getNormalizedName() + suffix;
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
