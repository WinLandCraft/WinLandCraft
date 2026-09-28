package dev.winlandcraft;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;

/**
 * Render-thread hop for GPU teardown. In game it delegates to the client's
 * task queue; headless (the geometry checks run without a Minecraft instance)
 * it defers into a drainable queue like the old render-call queue did.
 */
public final class RenderCallQueue {
    private static final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();

    private RenderCallQueue() {
    }

public static void submit(Runnable task) {
        var client = Minecraft.getInstance();
        if (client != null) {
            client.submit(task);
            return;
        }
        if (RenderSystem.isOnRenderThread()) task.run();
        else queue.add(task);
    }

    /** Runs deferred tasks. Only the headless checks call this; in game the client drains its own queue. */
public static void replay() {
        Runnable task;
        while ((task = queue.poll()) != null) task.run();
    }
}
