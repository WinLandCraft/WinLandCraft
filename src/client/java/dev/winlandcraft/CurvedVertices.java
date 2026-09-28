package dev.winlandcraft;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Warps panel pixels after their local text/icon transforms but before world rendering. */
final class CurvedVertices implements VertexConsumer {
    private final VertexConsumer target;
    private final WorldPanel panel;
    private final Matrix4f inverse, world, submit;
    private final Vec3 camera;

    CurvedVertices(VertexConsumer target, WorldPanel panel, Matrix4f inverse, Matrix4f world, Vec3 camera, Matrix4f submit) {
        this.target = target;
        this.panel = panel;
        this.inverse = inverse;
        this.world = world;
        this.camera = camera;
        this.submit = submit;
    }

    /** Bends submitting-pose pixels onto the cylinder, returned in lambda-pose-local coordinates. */
    private Vector3f bend(float x, float y, float z) {
        var placed = submit.transformPosition(new Vector3f(x, y, z));
        var pixel = inverse.transformPosition(new Vector3f(placed));
        var point = panel.curve.panelPoint(panel, (pixel.x / panel.pixelWidth() - .5f) * panel.worldWidth(),
                (.5f - pixel.y / panel.pixelHeight()) * panel.worldHeight(), pixel.z * panel.worldWidth() / panel.pixelWidth()).subtract(camera);
        var bent = world.transformPosition(new Vector3f((float) point.x, (float) point.y, (float) point.z));
        return new Matrix4f(submit).invert().transformPosition(bent);
    }

    @Override public VertexConsumer addVertex(float x, float y, float z) {
        var bent = bend(x, y, z);
        target.addVertex(bent.x, bent.y, bent.z);
        return this;
    }

    @Override public VertexConsumer addVertex(PoseStack.Pose pose, float x, float y, float z) {
        var bent = bend(x, y, z);
        target.addVertex(bent.x, bent.y, bent.z);
        return this;
    }

    @Override public VertexConsumer addVertex(Vector3fc vertex) {
        return addVertex(vertex.x(), vertex.y(), vertex.z());
    }

    @Override public VertexConsumer addVertex(PoseStack.Pose pose, Vector3fc vertex) {
        return addVertex(pose, vertex.x(), vertex.y(), vertex.z());
    }

    @Override public VertexConsumer setColor(int r, int g, int b, int a) { target.setColor(r, g, b, a); return this; }
    @Override public VertexConsumer setColor(int color) { target.setColor(color); return this; }
    @Override public VertexConsumer setUv(float u, float v) { target.setUv(u, v); return this; }
    @Override public VertexConsumer setUv1(int u, int v) { target.setUv1(u, v); return this; }
    @Override public VertexConsumer setUv2(int u, int v) { target.setUv2(u, v); return this; }
    @Override public VertexConsumer setUv3(float u, float v) { target.setUv3(u, v); return this; }
    @Override public VertexConsumer setNormal(float x, float y, float z) { target.setNormal(x, y, z); return this; }
    @Override public VertexConsumer setLineWidth(float width) { target.setLineWidth(width); return this; }
}
