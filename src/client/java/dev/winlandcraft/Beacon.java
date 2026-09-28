package dev.winlandcraft;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Misspawned-window beacon: a blue outline fading out over ten seconds. Marked at spawn
 * time, drawn in the world pass. Note: the old no-depth-test variant has no 26.x equivalent
 * yet, so beacons hide behind blocks; see the smoke test notes. */
final class Beacon {
    static final long DURATION_NANOS = 10_000_000_000L;

    static void mark(WorldPanel panel) {
        panel.markBeacon();
        var player = Minecraft.getInstance().player;
        if (player != null) player.sendOverlayMessage(Component.translatable("message.winlandcraft.spawn_blocked"));
    }

    static void draw(WorldPass pass, WorldPanel panel, float alpha) {
        var right = new Vector3f(1, 0, 0).rotate(panel.orientation);
        var up = new Vector3f(0, 1, 0).rotate(panel.orientation);
        float half = panel.worldWidth() / 2;
        float top = panel.worldHeight() / 2 + panel.titlebarHeight() * panel.worldHeight() / panel.pixelHeight();
        float bottom = -panel.worldHeight() / 2;
        Vec3 center = panel.position;
        float a = alpha * 0.95f;
        int color = ((int) (a * 255) << 24) | 0x51CFDF;
        pass.collector().order(90).submitCustomGeometry(new PoseStack(), RenderTypes.linesTranslucentNoDepthWrite(), (pose, vertices) -> {
            float[][] local = {{-half, top}, {half, top}, {half, bottom}, {-half, bottom}};
            for (int i = 0; i < 4; i++) {
                float[] from = local[i], to = local[(i + 1) % 4];
                vertices.addVertex(pose, (float) (center.x + right.x * from[0] + up.x * from[1]),
                        (float) (center.y + right.y * from[0] + up.y * from[1]),
                        (float) (center.z + right.z * from[0] + up.z * from[1])).setColor(color);
                vertices.addVertex(pose, (float) (center.x + right.x * to[0] + up.x * to[1]),
                        (float) (center.y + right.y * to[0] + up.y * to[1]),
                        (float) (center.z + right.z * to[0] + up.z * to[1])).setColor(color);
            }
        });
    }

    /** Draws in world coordinates with an identity pose. */
    static void line(SubmitNodeCollector collector, PoseStack pose, Vec3 from, Vec3 to, int color) {
        collector.order(90).submitCustomGeometry(new PoseStack(), RenderTypes.linesTranslucentNoDepthWrite(), (p, vertices) -> {
            vertices.addVertex(p, (float) from.x, (float) from.y, (float) from.z).setColor(color);
            vertices.addVertex(p, (float) to.x, (float) to.y, (float) to.z).setColor(color);
        });
    }
}
