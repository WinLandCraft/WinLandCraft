package dev.winlandcraft;

import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

final class FileMediaChecks {
    static void run() {
        imageViewer();
        System.out.println("File media panels: titles, selection, deferred close and reopen passed (without native CEF).");
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
