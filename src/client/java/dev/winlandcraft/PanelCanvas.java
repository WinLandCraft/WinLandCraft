package dev.winlandcraft;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/** Top-left pixel coordinates shared by native panels and CEF textures. */
public final class PanelCanvas implements AutoCloseable {
    /** Packed full-bright lightmap value for emissive UI quads. */
    private static final int FULL_BRIGHT = 0xF000F0;
    private final PoseStack pose;
    private final SubmitNodeCollector collector;
    private final WorldPass pass;
    private final WorldPanel panel;
    private final org.joml.Matrix4f worldMatrix;
    private final Matrix4f inverse;
    private final Vec3 cameraPos;
    private final boolean worldSpace;
    private int baseOrder;
    private boolean curved, front = true, closed;

    public PanelCanvas(WorldPass pass, WorldPanel panel) {
        this(pass, panel, panel.position, panel.orientation,
                panel.worldWidth() / panel.pixelWidth(), panel.worldHeight() / panel.pixelHeight(),
                panel.pixelWidth(), panel.pixelHeight());
        if (panel.curve != null && panel.curve.amount >= .0001f) {
            curved = true;
            // Undo the panel transform to obtain its logical pixels, then bend all geometry,
            // including icons and rects, onto the same cylindrical surface.
            inverse = new Matrix4f(pose.last().pose()).invert();
        } else {
            inverse = null;
        }
        if (!panel.frontFacing(pass.camera().pos)) {
            front = false;
            backside(panel);
        }
    }

    public PanelCanvas(WorldPass pass, Vec3 position, Quaternionf rotation,
                       float scaleX, float scaleY, int width, int height) {
        this.pass = pass;
        this.panel = null;
        this.collector = pass.collector();
        this.cameraPos = pass.camera().pos;
        this.pose = pass.pose();
        this.worldMatrix = new Matrix4f(pose.last().pose());
        this.inverse = null;
        Vec3 relative = position.subtract(cameraPos);
        pose.pushPose();
        pose.translate(relative.x, relative.y, relative.z);
        pose.mulPose(rotation);
        pose.scale(scaleX, -scaleY, scaleX);
        pose.translate(-width / 2f, -height / 2f, 0);
        worldSpace = true;
    }

    private PanelCanvas(WorldPass pass, WorldPanel panel, Vec3 position, Quaternionf rotation,
                        float scaleX, float scaleY, int width, int height) {
        this.pass = pass;
        this.panel = panel;
        this.collector = pass.collector();
        this.cameraPos = pass.camera().pos;
        this.pose = pass.pose();
        this.worldMatrix = new Matrix4f(pose.last().pose());
        this.inverse = null;
        Vec3 relative = position.subtract(cameraPos);
        pose.pushPose();
        pose.translate(relative.x, relative.y, relative.z);
        pose.mulPose(rotation);
        pose.scale(scaleX, -scaleY, scaleX);
        pose.translate(-width / 2f, -height / 2f, 0);
        worldSpace = true;
    }

    public boolean frontFacing() {
        return front;
    }

    private void backside(WorldPanel panel) {
        int title = panel.titlebarHeight();
        rect(-3, -title - 3, panel.pixelWidth() + 6, panel.pixelHeight() + title + 6, 0, 0xFF536579);
        rect(0, -title, panel.pixelWidth(), panel.pixelHeight() + title, .1f, 0xFF111923);
    }

    /** Window frame shared by browser, plugin, and other content panels. A border, not a
     *  full quad behind the content (which would also z-fight). */
    public void frame(int width, int height, int titlebar) {
        rect(-3, -titlebar - 3, width + 6, 3, 0, 0xFF536579);
        rect(-3, height, width + 6, 3, 0, 0xFF536579);
        rect(-3, -titlebar, 3, height + titlebar, 0, 0xFF536579);
        rect(width, -titlebar, 3, height + titlebar, 0, 0xFF536579);
    }

    /** Preserve the 3D projection while reserving late order for local control chrome. */
    void foreground() {
        baseOrder += 64;
    }

    /** World layers stay coplanar; submit order separates them instead of polygon offset. */
    private int orderFor(float layer) {
        return baseOrder + Math.clamp(Math.round(layer * 64), 0, 128);
    }

    private VertexConsumer curved(VertexConsumer target) {
        return curved ? new CurvedVertices(target, panel, inverse, worldMatrix, cameraPos) : target;
    }

    public void rect(float x, float y, float width, float height, float z, int color) {
        int order = orderFor(z);
        collector.order(order).submitCustomGeometry(pose, RenderTypes.debugQuads(), (p, vc) -> {
            var vertices = curved(vc);
            int segments = curved ? Math.max(1, (int) Math.ceil(width / 12)) : 1;
            for (int i = 0; i < segments; i++) {
                float left = x + width * i / segments, right = x + width * (i + 1) / segments;
                vertices.addVertex(p, left, y, 0).setColor(color);
                vertices.addVertex(p, left, y + height, 0).setColor(color);
                vertices.addVertex(p, right, y + height, 0).setColor(color);
                vertices.addVertex(p, right, y, 0).setColor(color);
            }
        });
    }

    public void text(String text, int x, int y, int color) {
        text(text, x, y, color, 1);
    }

    public void text(String text, int x, int y, int color, float scale) {
        text(text, x, y, color, scale, .6f);
    }

    void text(String text, int x, int y, int color, float scale, float layer) {
        // Curved panels submit glyphs unbent: the submit-text path takes whole strings and
        // cannot warp individual vertices. Flat panels are unaffected.
        pose.pushPose();
        pose.translate(x, y, 0);
        pose.scale(scale, scale, 1);
        collector.order(orderFor(layer)).submitText(pose, 0, 0,
                Component.literal(text).getVisualOrderText(), false, Font.DisplayMode.NORMAL,
                FULL_BRIGHT, color, Integer.MIN_VALUE, 0);
        pose.popPose();
    }

    public void texture(Identifier texture, int width, int height) {
        texture(texture, 0, 0, width, height, 0.2f);
    }

    /** Cyan compass mark for the Browser app, independent of any website's branding. */
    public void browserIcon(int x, int y, int size) {
        float unit = size / 24f;
        rect(x, y, size, size, 0.4f, 0xFF16778D);
        rect(x + 3 * unit, y + 3 * unit, 18 * unit, 18 * unit, 0.42f, 0xFF102E45);
        for (int i = 0; i < 12; i++) {
            float width = (i < 6 ? i + 1 : 12 - i) * unit;
            rect(x + (12 * unit) - width / 2, y + (6 + i) * unit, width, unit, 0.44f,
                    i < 6 ? 0xFF72ECF1 : 0xFFFFFFFF);
        }
    }

    public void texture(Identifier texture, int x, int y, int width, int height, float z) {
        texture(texture, x, y, width, height, z, -1, 0, 0, 1, 1);
    }

    void texture(Identifier texture, float x, float y, float width, float height, float z, int color,
                 float u0, float v0, float u1, float v1) {
        int order = orderFor(z);
        collector.order(order).submitCustomGeometry(pose, RenderTypes.text(texture), (p, vc) -> {
            var vertices = curved(vc);
            int segments = curved ? Math.max(1, (int) Math.ceil(width / 12f)) : 1;
            for (int i = 0; i < segments; i++) {
                float from = i / (float) segments, to = (i + 1) / (float) segments;
                float left = x + width * from, right = x + width * to;
                float su = u0 + (u1 - u0) * from, eu = u0 + (u1 - u0) * to;
                vertices.addVertex(p, left, y, 0).setColor(color).setUv(su, v0).setLight(FULL_BRIGHT);
                vertices.addVertex(p, left, y + height, 0).setColor(color).setUv(su, v1).setLight(FULL_BRIGHT);
                vertices.addVertex(p, right, y + height, 0).setColor(color).setUv(eu, v1).setLight(FULL_BRIGHT);
                vertices.addVertex(p, right, y, 0).setColor(color).setUv(eu, v0).setLight(FULL_BRIGHT);
            }
        });
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        pose.popPose();
    }
}
