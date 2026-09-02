package dev.winlandcraft;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;

import java.util.HashMap;
import java.util.IdentityHashMap;

/** Top-left pixel coordinates shared by native panels and CEF textures. */
public final class PanelCanvas implements AutoCloseable {
    private static final int BUFFER_SIZE=256*1024;
    private static ByteBufferBuilder worldVertices;
    private static MultiBufferSource.BufferSource worldBuffers;
    private static final IdentityHashMap<RenderType,HashMap<Integer,RenderType>> offsetTypes=new IdentityHashMap<>();
    private static boolean worldBatchOpen;
    private final PoseStack pose;
    private final org.joml.Matrix4f worldMatrix;
    private final boolean orderedWorldBatch;
    private MultiBufferSource buffers;
    private int layerOffset;
    private boolean curved,front=true,closed;
    /** Flat app-only render target, also used to capture the native sidebar and titlebar. */
    PanelCanvas(PoseStack pose, MultiBufferSource buffers) {
        this.pose=pose;this.buffers=buffers;orderedWorldBatch=false;
        worldMatrix=new org.joml.Matrix4f(pose.last().pose());pose.pushPose();
    }
    public PanelCanvas(WorldRenderContext context,WorldPanel panel) {
        this(context,panel,false);
    }
    PanelCanvas(WorldRenderContext context,WorldPanel panel,boolean surface) {
        this(context,panel.position,panel.orientation,panel.worldWidth()/panel.pixelWidth(),panel.worldHeight()/panel.pixelHeight(),panel.pixelWidth(),panel.pixelHeight());
        if(panel.curve!=null && panel.curve.amount>=.0001f) {
            curved=true;
            // Undo the panel transform to obtain its logical pixels, then bend all geometry,
            // including Font's glyph vertices, onto the same cylindrical surface.
            var inverse=new org.joml.Matrix4f(pose.last().pose()).invert();
            var original=buffers;
            buffers=type -> new CurvedVertices(original.getBuffer(type),panel,inverse,worldMatrix,context.camera().getPosition());
        }
        if(surface&&!panel.frontFacing(context.camera().getPosition())) {
            front=false;
            backside(panel);
        }
    }
    public PanelCanvas(WorldRenderContext context, Vec3 position, Quaternionf rotation,
                       float scaleX, float scaleY, int width, int height) {
        pose = context.matrixStack();
        worldMatrix=new org.joml.Matrix4f(pose.last().pose());
        var immediate=beginWorldBatch();
        buffers = type -> immediate.getBuffer(offsetType(type,layerOffset));
        orderedWorldBatch=true;
        Vec3 relative = position.subtract(context.camera().getPosition());
        pose.pushPose();
        pose.translate(relative.x, relative.y, relative.z);
        pose.mulPose(rotation);
        pose.scale(scaleX, -scaleY, scaleX);
        pose.translate(-width / 2f, -height / 2f, 0);
    }
    private static MultiBufferSource.BufferSource beginWorldBatch() {
        RenderSystem.assertOnRenderThread();
        if(worldBatchOpen)throw new IllegalStateException("Panel canvases cannot be nested");
        if(worldVertices==null) {
            worldVertices=new ByteBufferBuilder(BUFFER_SIZE);
            worldBuffers=MultiBufferSource.immediate(worldVertices);
        }
        worldBatchOpen=true;
        return worldBuffers;
    }
    private static RenderType offsetType(RenderType original,int offset) {
        if(offset==0)return original;
        return offsetTypes.computeIfAbsent(original,ignored->new HashMap<>()).computeIfAbsent(offset,units->
                new RenderType("panel_depth_"+units,original.format(),original.mode(),original.bufferSize(),
                        original.affectsCrumbling(),original.sortOnUpload(),()->{
                    original.setupRenderState();
                    GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
                    GL11.glPolygonOffset(0,-units);
                },()->{
                    GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
                    GL11.glPolygonOffset(0,0);
                    original.clearRenderState();
                }){});
    }
    public boolean frontFacing(){return front;}
    private void backside(WorldPanel panel) {
        int title=panel.titlebarHeight();
        rect(-3,-title-3,panel.pixelWidth()+6,panel.pixelHeight()+title+6,0,0xFF536579);
        rect(0,-title,panel.pixelWidth(),panel.pixelHeight()+title,.1f,0xFF111923);
    }
    /** World layers stay coplanar; polygon offset separates them in depth-buffer units at any distance. */
    private float depth(float layer){
        if(!orderedWorldBatch)return layer;
        layerOffset=Math.clamp(Math.round(layer*256),0,1024);
        return 0;
    }
    public void rect(float x, float y, float width, float height, float z, int color) {
        float depth=depth(z);
        var vertices = buffers.getBuffer(RenderType.debugQuads());
        var matrix = pose.last().pose();
        int segments=curved?Math.max(1,(int)Math.ceil(width/12)):1;
        for(int i=0;i<segments;i++) {
            float left=x+width*i/segments,right=x+width*(i+1)/segments;
            vertices.addVertex(matrix, left, y, depth).setColor(color);
            vertices.addVertex(matrix, left, y + height, depth).setColor(color);
            vertices.addVertex(matrix, right, y + height, depth).setColor(color);
            vertices.addVertex(matrix, right, y, depth).setColor(color);
        }
    }
    public void text(String text, int x, int y, int color) {
        text(text, x, y, color, 1);
    }
    public void text(String text, int x, int y, int color, float scale) {
        pose.pushPose();
        pose.translate(x, y, depth(.6f));
        pose.scale(scale, scale, 1);
        Minecraft.getInstance().font.drawInBatch(Component.literal(text), 0, 0, color, false,
                pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
        pose.popPose();
    }
    public void texture(ResourceLocation texture, int width, int height) {
        texture(texture, 0, 0, width, height, 0.2f);
    }
    /** Cyan compass mark for the Browser app, independent of any website's branding. */
    public void browserIcon(int x, int y, int size) {
        browserIcon(x,y,size,false);
    }
    public void browserIcon(int x, int y, int size, boolean purple) {
        float unit = size / 24f;
        rect(x, y, size, size, 0.4f, purple?0xFF8450BD:0xFF16778D);
        rect(x + 3 * unit, y + 3 * unit, 18 * unit, 18 * unit, 0.42f, purple?0xFF302047:0xFF102E45);
        for (int i = 0; i < 12; i++) {
            float width = (i < 6 ? i + 1 : 12 - i) * unit;
            rect(x + (12 * unit) - width / 2, y + (6 + i) * unit, width, unit, 0.44f,
                    i < 6 ? (purple?0xFFD4ACFF:0xFF72ECF1) : 0xFFFFFFFF);
        }
    }
    public void texture(ResourceLocation texture, int x, int y, int width, int height, float z) {
        texture(texture,x,y,width,height,z,-1,0,0,1,1);
    }
    void texture(ResourceLocation texture, float x, float y, float width, float height, float z, int color,
                 float u0, float v0, float u1, float v1) {
        float depth=depth(z);
        var vertices = buffers.getBuffer(RenderType.text(texture));
        var matrix = pose.last().pose();
        int segments=curved?Math.max(1,(int)Math.ceil(width/12f)):1;
        for(int i=0;i<segments;i++) {
            float from=i/(float)segments,to=(i+1)/(float)segments,left=x+width*from,right=x+width*to;
            float su=u0+(u1-u0)*from,eu=u0+(u1-u0)*to;
            vertices.addVertex(matrix, left, y, depth).setColor(color).setUv(su, v0).setLight(LightTexture.FULL_BRIGHT);
            vertices.addVertex(matrix, left, y + height, depth).setColor(color).setUv(su, v1).setLight(LightTexture.FULL_BRIGHT);
            vertices.addVertex(matrix, right, y + height, depth).setColor(color).setUv(eu, v1).setLight(LightTexture.FULL_BRIGHT);
            vertices.addVertex(matrix, right, y, depth).setColor(color).setUv(eu, v0).setLight(LightTexture.FULL_BRIGHT);
        }
    }
    @Override public void close() {
        if(closed)return;
        closed=true;
        try {
            if(orderedWorldBatch)worldBuffers.endBatch();
        } finally {
            pose.popPose();
            if(orderedWorldBatch)worldBatchOpen=false;
        }
    }
    static void shutdown() {
        RenderSystem.assertOnRenderThread();
        if(worldBatchOpen)throw new IllegalStateException("Cannot close panel renderer during a draw");
        if(worldVertices!=null)worldVertices.close();
        worldVertices=null;worldBuffers=null;offsetTypes.clear();
    }
}
