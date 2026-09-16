package dev.winlandcraft;

import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

final class FileMediaChecks {
    static void run() {
        imageViewer();
        videoPlayer();
        System.out.println("File media panels: titles, selection, replacement, deferred close and reopen passed (without native CEF).");
    }
    private static void videoPlayer() {
        var panel = new VideoPlayerPanel();
        try {
            check(panel.windowTitle().equals("Video Player"), "empty player title");
            panel.position = new Vec3(0, 0, -3);
            panel.orientation = new Quaternionf();
            Path first = Path.of("movie.mp4"), second = Path.of("clip.mkv");
            panel.dropFile(first);
            check(panel.windowTitle().equals("movie.mp4 - Video Player"), "selected video title");
            panel.dropFile(second);
            check(panel.windowTitle().equals("clip.mkv - Video Player"), "replacement video title");
            // Missing files fail asynchronously in the player worker, never on the caller.
            long deadline = System.currentTimeMillis() + 10_000;
            while (panel.playerError().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50);
            check(panel.playerError().contains("not found"), "unreadable file reports an error: " + panel.playerError());
            var failure = new AtomicReference<Throwable>();
            Thread network = new Thread(() -> { try { panel.close(); } catch (Throwable error) { failure.set(error); } }, "video-close-check");
            network.start();
            network.join();
            if (failure.get() != null) throw new AssertionError("off-thread video close", failure.get());
            RenderSystem.replayQueue();
            check(!panel.isOpen(), "video resources cleared");
            check(panel.windowTitle().equals("Video Player"), "video title reset on close");
        } catch (Exception error) { throw new AssertionError(error); }
        finally { panel.close(); }
    }
    private static void imageViewer() {
        var panel = new ImageViewerPanel();
        try {
            check(panel.windowTitle().equals("Image Viewer") && !panel.browserEnabled(), "lazy image endpoint");
            panel.position = new Vec3(0, 0, -3);
            panel.orientation = new Quaternionf();
            Path file = Path.of("picture.png");
            panel.dropFile(file);
            check(panel.windowTitle().equals(file.getFileName() + " - Image Viewer"), "selected image title");
            panel.dropFile(file);
            var failure = new AtomicReference<Throwable>();
            Thread network = new Thread(() -> { try { panel.close(); } catch (Throwable error) { failure.set(error); } }, "media-close-check");
            network.start();
            network.join();
            if (failure.get() != null) throw new AssertionError("off-thread image close", failure.get());
            RenderSystem.replayQueue();
            check(!panel.isOpen() && !panel.browserEnabled(), "image resources cleared");
            check(panel.windowTitle().equals("Image Viewer"), "image title reset on close");
        } catch (Exception error) { throw new AssertionError(error); }
        finally { panel.close(); }
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
