package dev.winlandcraft;

import org.bytedeco.ffmpeg.avutil.AVDictionary;
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
            // The buffer is only NUL-terminated; getString reads its whole capacity,
            // which would append uninitialized native memory (and log tofu) to the message.
            String text = buffer.getString();
            int nul = text.indexOf(0);
            return nul < 0 ? text : text.substring(0, nul);
        }
    }

    /** Extracts natives and warms up the driver on a background thread so the first
     *  stream starts fast. Best effort: failures stay silent until real use. */
    static void prewarm() {
        try {
            require();
            for (String name : new String[]{"h264_nvenc", "libx264"}) {
                var codec = org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name(name);
                if (codec == null || codec.isNull()) continue;
                var context = org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3(codec);
                if (context == null || context.isNull()) continue;
                try {
                    context.width(64);
                    context.height(64);
                    context.time_base().num(1);
                    context.time_base().den(30);
                    context.framerate().num(30);
                    context.framerate().den(1);
                    context.pix_fmt(avutil.AV_PIX_FMT_YUV420P);
                    context.bit_rate(100_000);
                    if (org.bytedeco.ffmpeg.global.avcodec.avcodec_open2(context, codec, (AVDictionary) null) == 0)
                        WinLandCraftClient.LOGGER.info("Stream encoder prewarmed: {}", name);
                } catch (RuntimeException ignored) {
                } finally {
                    org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context(context);
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    private Ffmpeg() {}
}
