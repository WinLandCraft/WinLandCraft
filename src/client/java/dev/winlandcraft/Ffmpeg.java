package dev.winlandcraft;

import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Loader;

/** Loads the bundled FFmpeg natives once and maps fatal API errors to exceptions. */
final class Ffmpeg {
    private static volatile boolean loaded;
    private static String version = "unknown";

    static synchronized void require() {
        if (loaded) return;
        try {
            Loader.load(org.bytedeco.ffmpeg.global.avutil.class);
            Loader.load(org.bytedeco.ffmpeg.global.swscale.class);
            Loader.load(org.bytedeco.ffmpeg.global.avcodec.class);
            version = avutil.av_version_info().getString();
            loaded = true;
            WinLandCraftClient.LOGGER.info("Loaded FFmpeg {} for stream codecs on {} {}",
                    version, System.getProperty("os.name"), System.getProperty("os.arch"));
        } catch (RuntimeException | LinkageError failure) {
            throw new IllegalStateException("FFmpeg natives are unavailable", failure);
        }
    }

    static String version() { return version; }

    static void check(int result, String operation) {
        if (result < 0) throw new IllegalStateException(operation + " failed: " + error(result));
    }

    static String error(int result) {
        try (var buffer = new BytePointer(1024)) {
            avutil.av_strerror(result, buffer, 1024);
            return buffer.getString();
        }
    }

    private Ffmpeg() {}
}
