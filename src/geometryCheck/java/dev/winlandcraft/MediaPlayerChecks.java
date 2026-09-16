package dev.winlandcraft;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** End-to-end media player checks against real files. Video uses the ffmpeg binary bundled
 *  beside the JavaCPP natives; if it cannot run here, only the container-error paths run. */
final class MediaPlayerChecks {
    static void run() throws Exception {
        // Bisecting aid: WLC_PLAYER_NOHW=1 forces software decode in the test JVM.
        if (System.getenv("WLC_PLAYER_NOHW") != null) System.setProperty("winlandcraft.player.nohw", "true");
        audioOnlyFile();
        missingFile();
        Path ffmpeg = extractFfmpeg();
        if (ffmpeg == null) {
            System.out.println("Media player: error paths passed; video decode skipped (no ffmpeg binary).");
            return;
        }
        Path movie = Files.createTempFile("wlc-player-", ".mp4");
        try {
            int rc = new ProcessBuilder(ffmpeg.toString(), "-y", "-v", "error",
                    "-f", "lavfi", "-i", "testsrc=size=320x240:rate=10:duration=8",
                    "-f", "lavfi", "-i", "sine=frequency=440:duration=8",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", movie.toString())
                    .redirectErrorStream(true).start().waitFor();
            check(rc == 0, "ffmpeg generated test media");
            var player = new FfmpegPlayer(movie);
            String backend = "SW";
            try {
                waitFor(() -> player.ready || !player.error.isEmpty(), 15_000, "player opens test media");
                check(player.error.isEmpty(), "no player error: " + player.error);
                check(player.videoWidth == 320 && player.videoHeight == 240, "decoded dimensions");
                check(Math.abs(player.durationSec - 8) < .6, "duration ~8s: " + player.durationSec);
                check(player.hasAudio, "audio stream detected");
                check(!player.hw.isEmpty(), "decoder backend reported: " + player.hw);
                backend = player.hw;
                waitFor(() -> player.current != null, 10_000, "first frame decoded");
                var frame = player.current;
                check(frame.width() == 320 && frame.height() == 240, "frame dimensions");
                check(!player.paused, "autoplay");
                // Consume like the panel upload does so the worker keeps producing.
                player.markConsumed();
                double t0 = player.timeSec();
                for (int i = 0; i < 30 && player.timeSec() <= t0; i++) { player.markConsumed(); Thread.sleep(100); }
                check(player.timeSec() > t0, "clock advances while playing");
                player.pause();
                check(player.paused, "pause holds");
                Thread.sleep(300);
                double frozen = player.timeSec();
                Thread.sleep(200);
                check(player.timeSec() == frozen, "clock frozen while paused; error=" + player.error);
                player.seekTo(1);
                dumpOnTimeout(player, () -> Math.abs(player.timeSec() - 1) < .6, 5_000, "seek lands near 1s");
                player.play();
                check(!player.paused, "resume clears pause");
                // Re-anchor mid-file so later toggles can't trip over natural EOF.
                player.seekTo(2);
                dumpOnTimeout(player, () -> Math.abs(player.timeSec() - 2) < .6, 5_000, "re-anchor near 2s");
                player.seekFraction(.5);
                player.setVolume(.5);
                check(player.volume == .5 && !player.muted, "volume step");
                player.toggleMute();
                check(player.muted, "mute toggle");
                player.toggleMute();
                player.toggle();
                check(player.paused, "toggle pauses");
                player.toggle();
                check(!player.paused, "toggle resumes");
            } finally {
                player.close();
            }
            System.out.println("Media player: demux, decode (" + backend + "), sync, seek, volume and error paths passed.");
        } finally {
            Files.deleteIfExists(movie);
        }
    }

    private static void audioOnlyFile() throws Exception {
        Path wav = Files.createTempFile("wlc-audio-", ".wav");
        try {
            int rate = 48000, frames = rate / 2;
            var header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            header.put("RIFF".getBytes()).putInt(36 + frames * 4).put("WAVEfmt ".getBytes()).putInt(16)
                    .putShort((short) 1).putShort((short) 2).putInt(rate).putInt(rate * 4)
                    .putShort((short) 4).putShort((short) 16).put("data".getBytes()).putInt(frames * 4);
            var samples = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < frames * 2; i++) samples.putShort((short) (Math.sin(i * .05) * 10000));
            Files.write(wav, header.array());
            try (var out = Files.newOutputStream(wav, java.nio.file.StandardOpenOption.APPEND)) { out.write(samples.array()); }
            var player = new FfmpegPlayer(wav);
            try {
                waitFor(() -> !player.error.isEmpty(), 10_000, "audio-only file rejected");
                check(player.error.contains("No video stream"), "audio-only error: " + player.error);
            } finally {
                player.close();
            }
        } finally {
            Files.deleteIfExists(wav);
        }
    }

    private static void missingFile() {
        var player = new FfmpegPlayer(Path.of("missing-video.mp4"));
        try {
            waitForUnchecked(() -> !player.error.isEmpty(), 10_000);
            check(player.error.contains("not found"), "missing file error: " + player.error);
        } finally {
            player.close();
        }
    }

    private static Path extractFfmpeg() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm64") || arch.startsWith("arm");
        String platform;
        if (os.contains("win")) {
            if (arm) return null;
            platform = "windows-x86_64";
        } else if (os.contains("mac")) platform = arm ? "macosx-arm64" : "macosx-x86_64";
        else platform = arm ? "linux-arm64" : "linux-x86_64";
        String prefix = "org/bytedeco/ffmpeg/" + platform + "-gpl/";
        String binary = prefix + (os.contains("win") ? "ffmpeg.exe" : "ffmpeg");
        String reason = "missing from classpath";
        try {
            var url = MediaPlayerChecks.class.getClassLoader().getResource(binary);
            if (url == null) {
                WinLandCraftClient.LOGGER.warn("Media player test binary {} {}", binary, reason);
                return null;
            }
            // The executable needs its sibling native libraries, so extract the whole directory.
            String spec = url.toString();
            Path dir = Files.createTempDirectory("wlc-ffmpeg-");
            try (var zip = new java.util.zip.ZipFile(new java.io.File(new java.net.URI(spec.substring("jar:".length(), spec.indexOf("!/")))))) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (!entry.getName().startsWith(prefix) || entry.isDirectory()) continue;
                    Path target = dir.resolve(entry.getName().substring(prefix.length()));
                    Files.createDirectories(target.getParent());
                    try (var input = zip.getInputStream(entry)) { Files.write(target, input.readAllBytes()); }
                }
            }
            Path file = dir.resolve(os.contains("win") ? "ffmpeg.exe" : "ffmpeg");
            if (!os.contains("win") && !file.toFile().setExecutable(true)) reason = "not executable";
            else {
                int rc = new ProcessBuilder(file.toString(), "-version").redirectErrorStream(true).start().waitFor();
                if (rc != 0) reason = "ffmpeg -version failed";
                else {
                    file.toFile().deleteOnExit();
                    return file;
                }
            }
        } catch (Exception failed) {
            reason = failed.toString();
        }
        WinLandCraftClient.LOGGER.warn("Media player video decode skipped: {} ({})", binary, reason);
        return null;
    }

    private interface Condition { boolean ready(); }

    private static void dumpOnTimeout(FfmpegPlayer player, Condition condition, long timeoutMillis, String message) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.ready() && System.currentTimeMillis() < deadline) {
            // Consume like the panel upload does, or video stays parked on one frame.
            player.markConsumed();
            Thread.sleep(50);
        }
        if (condition.ready()) return;
        System.out.println("TIMEOUT: " + message + " :: " + player.debugState());
        check(false, message);
    }

    private static void waitFor(Condition condition, long timeoutMillis, String message) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.ready() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        check(condition.ready(), message);
    }

    private static void waitForUnchecked(Condition condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.ready() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
        }
    }

    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
