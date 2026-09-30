package dev.winlandcraft;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Top-left pixel coordinates shared by native panels and CEF textures.
 * Two backends: world submission for in-world panels, and direct extractor
 * drawing for the 2D desktop hybrid. Call order is draw order in both.
 */
public final class PanelCanvas implements AutoCloseable {
    /** 1x1 opaque white: solid rects reuse the world text pipeline, which 26.3
     *  still draws in the level pass (debugQuads no longer has a world consumer). */
    private static final Identifier WHITE = Identifier.fromNamespaceAndPath("winlandcraft", "textures/gui/white.png");

    /** Packed full-bright lightmap value for emissive UI quads. */
    private static final int FULL_BRIGHT = 0xF000F0;
    private final PoseStack pose;
    private final SubmitNodeCollector collector;
    private final GuiGraphicsExtractor graphics;
    private final WorldPass pass;
    private final WorldPanel panel;
    private final Matrix4f worldMatrix;
    private Matrix4f inverse;
    private final Vec3 cameraPos;
    private final boolean worldSpace;
    private int baseOrder;
    private float opacity = 1;
    private boolean curved, front = true, closed, pushed;
    /** Rear shell winding: the world text pipeline culls back faces, so the
     *  shell (only ever viewed from behind) must be wound to face backward. */
    private boolean mirror;

    public PanelCanvas(WorldPass pass, WorldPanel panel) {
        this(pass, panel, panel.position, panel.orientation,
                panel.worldWidth() / panel.pixelWidth(), panel.worldHeight() / panel.pixelHeight(),
                panel.pixelWidth(), panel.pixelHeight());
        if (panel.curve != null && panel.curve.amount >= .0001f) {
            curved = true;
            // Undo the panel transform to obtain its logical pixels, then bend all geometry,
            // including icons and rects, onto the same cylindrical surface.
            inverse = new Matrix4f(pose.last().pose()).invert();
        }
        if (!panel.frontFacing(pass.camera().pos)) {
            front = false;
            backside(panel);
        }
    }

    public PanelCanvas(WorldPass pass, Vec3 position, Quaternionf rotation,
                       float scaleX, float scaleY, int width, int height) {
        this(pass, null, position, rotation, scaleX, scaleY, width, height);
    }

    private PanelCanvas(WorldPass pass, WorldPanel panel, Vec3 position, Quaternionf rotation,
                        float scaleX, float scaleY, int width, int height) {
        this.pass = pass;
        this.panel = panel;
        this.collector = pass.collector();
        this.graphics = null;
        this.cameraPos = pass.camera().pos;
        this.pose = pass.pose();
        this.worldMatrix = new Matrix4f(pose.last().pose());
        this.inverse = null;
        Vec3 relative = position.subtract(cameraPos);
        pose.pushPose();
        pushed = true;
        pose.translate(relative.x, relative.y, relative.z);
        pose.mulPose(new Matrix4f().rotate(rotation));
        pose.scale(scaleX, -scaleY, scaleX);
        pose.translate(-width / 2f, -height / 2f, 0);
        worldSpace = true;
    }

    /** GUI backend for the 2D desktop hybrid. */
    public PanelCanvas(GuiGraphicsExtractor graphics) {
        this.pass = null;
        this.panel = null;
        this.collector = null;
        this.graphics = graphics;
        this.cameraPos = Vec3.ZERO;
        this.pose = null;
        this.worldMatrix = null;
        this.inverse = null;
        worldSpace = false;
    }

    public boolean frontFacing() {
        return front;
    }

    private void backside(WorldPanel panel) {
        int title = panel.titlebarHeight();
        mirror = true;
        rect(-3, -title - 3, panel.pixelWidth() + 6, panel.pixelHeight() + title + 6, 0, 0xFF536579);
        rect(0, -title, panel.pixelWidth(), panel.pixelHeight() + title, .1f, 0xFF111923);
        mirror = false;
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
        zLift += FOREGROUND_LIFT;
    }

    /** Global fade multiplier applied to every draw. */
    void opacity(float opacity) {
        this.opacity = Math.clamp(opacity, 0, 1);
    }

    private int fade(int color) {
        if (opacity >= 1) return color;
        int alpha = Math.round((color >>> 24) * opacity);
        return (alpha << 24) | (color & 0xFFFFFF);
    }

    /** Submit order separates world layers; depthFor adds the physical separation
     *  that replaces the old polygon offset (submit order alone leaves ties). */
    private int orderFor(float layer) {
        return baseOrder + Math.clamp(Math.round(layer * 64), 0, 128);
    }

    /** Panel pixels of physical separation per layer unit. Stacked UI layers need
     *  real depth distance: submit order alone leaves depth ties whose winner
     *  flips every frame (depth-sort instability and OIT mixing), which reads as
     *  flickering. Four pixels per unit separates the finest chrome (0.02 layer
     *  steps) at interaction range while the whole stack stays sub-centimeter. */
    private static final float LAYER_DEPTH = 4.0f;
    /** Foreground chrome floats above every content layer. */
    private static final float FOREGROUND_LIFT = 4.0f;
    private float zLift;

    private float depthFor(float layer) {
        return layer * LAYER_DEPTH + zLift;
    }

    private VertexConsumer curved(VertexConsumer target, Matrix4f submit) {
        return curved ? new CurvedVertices(target, panel, inverse, worldMatrix, cameraPos, submit) : target;
    }

    public void rect(float x, float y, float width, float height, float z, int color) {
        final int drawColor = fade(color);
        if (graphics != null) {
            graphics.fill((int) x, (int) y, (int) Math.ceil(x + width), (int) Math.ceil(y + height), color);
            return;
        }
        int order = orderFor(z);
        final float depth = depthFor(z);
        final boolean mirrored = mirror;
        collector.order(order).submitCustomGeometry(pose, RenderTypes.text(WHITE), (p, vc) -> {
            var vertices = curved(vc, p.pose());
            int segments = curved ? Math.max(1, (int) Math.ceil(width / 12)) : 1;
            for (int i = 0; i < segments; i++) {
                float left = x + width * i / segments, right = x + width * (i + 1) / segments;
                // Rear shells use reversed winding to face backward (backface
                // culling); the same four corners keep their attributes.
                if (!mirrored) {
                    vertices.addVertex(p, left, y, depth).setColor(drawColor).setUv(0, 0).setLight(FULL_BRIGHT);
                    vertices.addVertex(p, left, y + height, depth).setColor(drawColor).setUv(0, 1).setLight(FULL_BRIGHT);
                    vertices.addVertex(p, right, y + height, depth).setColor(drawColor).setUv(1, 1).setLight(FULL_BRIGHT);
                    vertices.addVertex(p, right, y, depth).setColor(drawColor).setUv(1, 0).setLight(FULL_BRIGHT);
                } else {
                    vertices.addVertex(p, left, y, depth).setColor(drawColor).setUv(0, 0).setLight(FULL_BRIGHT);
                    vertices.addVertex(p, right, y, depth).setColor(drawColor).setUv(1, 0).setLight(FULL_BRIGHT);
                    vertices.addVertex(p, right, y + height, depth).setColor(drawColor).setUv(1, 1).setLight(FULL_BRIGHT);
                    vertices.addVertex(p, left, y + height, depth).setColor(drawColor).setUv(0, 1).setLight(FULL_BRIGHT);
                }
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
        final int drawColor = fade(color);
        if (graphics != null) {
            var poseStack = graphics.pose();
            poseStack.pushMatrix();
            poseStack.translate(x, y);
            poseStack.scale(scale, scale);
            graphics.text(Minecraft.getInstance().font, text, 0, 0, drawColor);
            poseStack.popMatrix();
            return;
        }
        if (curved) {
            curvedText(text, x, y, drawColor, scale, layer);
            return;
        }
        pose.pushPose();
        pose.translate(x, y, depthFor(layer));
        pose.scale(scale, scale, 1);
        collector.order(orderFor(layer)).submitText(pose, 0, 0,
                Component.literal(text).getVisualOrderText(), false, Font.DisplayMode.NORMAL,
                FULL_BRIGHT, drawColor, 0, 0);
        pose.popPose();
    }

    /** Glyph runs bent onto the cylinder. submitText takes whole strings and cannot
     *  warp vertices, so curved panels bake each run and feed it through the same bend
     *  wrapper as rects and icons. Runs are prepared at the text origin; the translated
     *  pose supplies the panel offset, and the identity matrix keeps run coordinates
     *  un-baked so the wrapper (not the glyph emitter) owns the only transform. */
    private void curvedText(String text, int x, int y, int color, float scale, float layer) {
        if (text.isEmpty()) return;
        var prepared = Minecraft.getInstance().font.prepareText(
                Component.literal(text).getVisualOrderText(), 0, 0, color, false, false, 0);
        var runs = new java.util.ArrayList<TextRenderable>();
        prepared.visit(new Font.GlyphVisitor() {
            @Override public void acceptRenderable(TextRenderable renderable) { runs.add(renderable); }
        });
        if (runs.isEmpty()) return;
        pose.pushPose();
        pose.translate(x, y, depthFor(layer));
        pose.scale(scale, scale, 1);
        int order = orderFor(layer);
        var unposed = new Matrix4f();
        for (var run : runs)
            collector.order(order).submitCustomGeometry(pose, run.renderType(Font.DisplayMode.NORMAL),
                    (p, vc) -> run.render(unposed, curved(vc, p.pose()), FULL_BRIGHT, false));
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
        final int drawColor = fade(color);
        if (graphics != null) {
            graphics.blit(texture, (int) x, (int) y, (int) width, (int) height, u0, v0, u1, v1);
            return;
        }
        int order = orderFor(z);
        final float depth = depthFor(z);
        final boolean mirrored = mirror;
        collector.order(order).submitCustomGeometry(pose, RenderTypes.text(texture), (p, vc) -> {
            var vertices = curved(vc, p.pose());
            int segments = curved ? Math.max(1, (int) Math.ceil(width / 12f)) : 1;
            for (int i = 0; i < segments; i++) {
                float from = i / (float) segments, to = (i + 1) / (float) segments;
                float left = x + width * from, right = x + width * to;
                float su = u0 + (u1 - u0) * from, eu = u0 + (u1 - u0) * to;
                if (!mirrored) {
                    vertices.addVertex(p, left, y, depth).setColor(drawColor).setUv(su, v0).setLight(FULL_BRIGHT);
                    vertices.addVertex(p, left, y + height, depth).setColor(drawColor).setUv(su, v1).setLight(FULL_BRIGHT);
                    vertices.addVertex(p, right, y + height, depth).setColor(drawColor).setUv(eu, v1).setLight(FULL_BRIGHT);
                    vertices.addVertex(p, right, y, depth).setColor(drawColor).setUv(eu, v0).setLight(FULL_BRIGHT);
                } else {
                    vertices.addVertex(p, left, y, depth).setColor(drawColor).setUv(su, v0).setLight(FULL_BRIGHT);
                    vertices.addVertex(p, right, y, depth).setColor(drawColor).setUv(eu, v0).setLight(FULL_BRIGHT);
                    vertices.addVertex(p, right, y + height, depth).setColor(drawColor).setUv(eu, v1).setLight(FULL_BRIGHT);
                    vertices.addVertex(p, left, y + height, depth).setColor(drawColor).setUv(su, v1).setLight(FULL_BRIGHT);
                }
            }
        });
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (pushed) {
            pose.popPose();
            pushed = false;
        }
    }
}
