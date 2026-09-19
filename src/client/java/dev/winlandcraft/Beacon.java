package dev.winlandcraft;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import java.util.OptionalDouble;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Misspawned-window beacon: a blue outline drawn without depth testing so it stays visible
 * through blocks, fading out over ten seconds. Marked at spawn time, drawn in the world pass. */
final class Beacon {
    static final long DURATION_NANOS = 10_000_000_000L;
    private static final RenderType OUTLINE = RenderType.create("winlandcraft_beacon",
            DefaultVertexFormat.POSITION_COLOR_NORMAL, com.mojang.blaze3d.vertex.VertexFormat.Mode.LINES, 256,
            false, false, RenderType.CompositeState.builder()
                    .setShaderState(RenderType.RENDERTYPE_LINES_SHADER)
                    .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.empty()))
                    .setLayeringState(RenderType.NO_LAYERING)
                    .setTransparencyState(RenderType.TRANSLUCENT_TRANSPARENCY)
                    .setOutputState(RenderType.MAIN_TARGET)
                    .setWriteMaskState(RenderType.COLOR_WRITE)
                    .setCullState(RenderType.NO_CULL)
                    .setDepthTestState(RenderType.NO_DEPTH_TEST)
                    .createCompositeState(false));
    static void mark(WorldPanel panel) {
        panel.markBeacon();
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.translatable("message.winlandcraft.spawn_blocked"), true);
    }
    static void draw(WorldRenderContext context, WorldPanel panel, float alpha) {
        var right = new Vector3f(1, 0, 0).rotate(panel.orientation);
        var up = new Vector3f(0, 1, 0).rotate(panel.orientation);
        var normal = new Vector3f(0, 0, 1).rotate(panel.orientation);
        float half = panel.worldWidth() / 2;
        float top = panel.worldHeight() / 2 + panel.titlebarHeight() * panel.worldHeight() / panel.pixelHeight();
        float bottom = -panel.worldHeight() / 2;
        Vec3 center = panel.position;
        Vec3 eye = context.camera().getPosition();
        Vec3[] corners = new Vec3[4];
        double[][] local = {{-half, top}, {half, top}, {half, bottom}, {-half, bottom}};
        for (int i = 0; i < 4; i++)
            corners[i] = center.add(right.x * local[i][0] + up.x * local[i][1],
                    right.y * local[i][0] + up.y * local[i][1],
                    right.z * local[i][0] + up.z * local[i][1]).subtract(eye);
        var consumer = context.consumers().getBuffer(OUTLINE);
        Matrix4f pose = context.matrixStack().last().pose();
        float a = alpha * 0.95f;
        for (int i = 0; i < 4; i++) {
            vertex(consumer, pose, corners[i], normal, a);
            vertex(consumer, pose, corners[(i + 1) % 4], normal, a);
        }
    }
    private static void vertex(com.mojang.blaze3d.vertex.VertexConsumer consumer, Matrix4f pose,
            Vec3 point, Vector3f normal, float alpha) {
        consumer.addVertex(pose, (float) point.x, (float) point.y, (float) point.z)
                .setColor(0x51, 0xCF, 0xDF, (int) (alpha * 255)).setNormal(normal.x, normal.y, normal.z);
    }
}
