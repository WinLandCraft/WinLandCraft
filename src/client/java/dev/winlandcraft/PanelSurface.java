package dev.winlandcraft;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CompiledShaderProgram;
import net.minecraft.client.renderer.FogParameters;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.TriState;
import org.joml.Matrix4f;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Composites a complete panel before world-space minification. */
final class PanelSurface implements AutoCloseable {
    private static final AtomicInteger IDS=new AtomicInteger();
    private static float maximumAnisotropy=Float.NaN;
    static final int MARGIN=3;
    private final WorldRenderContext context;
    private final WorldPanel panel;
    private final Target target;
    private final boolean front;
    private PanelCanvas canvas;
    private RenderState state;
    private IrisOffscreenRender.Scope irisScope;
    private boolean modelViewPushed,closed;
    private boolean foreground;
    private float opacity=1;

    PanelSurface(WorldRenderContext context,WorldPanel panel,Target target,boolean front) {
        this.context=context;this.panel=panel;this.target=target;
        this.front=front;
        if(!front) {
            canvas=new PanelCanvas(context,panel,true);
            return;
        }
        openComposite();
    }

    boolean frontFacing(){return front;}
    PanelCanvas canvas(){return canvas;}
    void foreground(float opacity){foreground=true;this.opacity=Math.clamp(opacity,0,1);}
    static ResourceLocation location(){return ResourceLocation.fromNamespaceAndPath("winlandcraft","panel_surface_"+IDS.incrementAndGet());}

    private void openComposite() {
        RenderSystem.assertOnRenderThread();
        int width=panel.pixelWidth()+MARGIN*2;
        int height=panel.pixelHeight()+panel.titlebarHeight()+MARGIN*2;
        irisScope=IrisOffscreenRender.enter();
        try {
            state=new RenderState();
            target.ensure(width,height);
            var modelView=RenderSystem.getModelViewStack();
            modelView.pushMatrix();modelView.identity();modelViewPushed=true;
            RenderSystem.disableScissor();
            RenderSystem.depthMask(true);
            target.framebuffer.setClearColor(.06f,.09f,.12f,0);
            target.framebuffer.bindWrite(true);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glColorMask(true,true,true,true);
            target.framebuffer.clear();
            target.framebuffer.bindWrite(true);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glColorMask(true,true,true,true);
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0,width,height,0,-100,100),ProjectionType.ORTHOGRAPHIC);
            RenderSystem.setShaderFog(FogParameters.NO_FOG);
            RenderSystem.setShaderColor(1,1,1,1);
            RenderSystem.disableCull();
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            var pose=new PoseStack();
            pose.translate(MARGIN,panel.titlebarHeight()+MARGIN,0);
            canvas=new PanelCanvas(pose,target.redirected);
        } catch (RuntimeException | Error failure) {
            try {restoreState();} finally {restoreIris();}
            throw failure;
        }
    }

    @Override public void close() {
        if(closed)return;
        closed=true;
        if(!front) {canvas.close();return;}
        try {
            canvas.close();
            target.buffers.endBatch();
            target.generateMipmaps(panel);
        } finally {
            try {restoreState();} finally {restoreIris();}
        }
        try(var world=new PanelCanvas(context,panel)) {
            if(foreground)world.foreground();
            int title=panel.titlebarHeight();
            world.texture(target.location,-MARGIN,-title-MARGIN,panel.pixelWidth()+MARGIN*2,
                    panel.pixelHeight()+title+MARGIN*2,.2f,0xFFFFFF|(Math.round(opacity*255)<<24),0,1,1,0);
        }
    }

    private void restoreState() {
        if(modelViewPushed) {RenderSystem.getModelViewStack().popMatrix();modelViewPushed=false;}
        if(state!=null) {state.restore();state=null;}
    }

    private void restoreIris() {
        if(irisScope==null)return;
        irisScope.close();irisScope=null;
    }

    static final class Target implements AutoCloseable {
        private final ResourceLocation location;
        private final ByteBufferBuilder vertices=new ByteBufferBuilder(256*1024);
        private final MultiBufferSource.BufferSource buffers=MultiBufferSource.immediate(vertices);
        private final IdentityHashMap<RenderType,RenderType> redirectedTypes=new IdentityHashMap<>();
        private TextureTarget framebuffer;
        private final MultiBufferSource redirected=original->buffers.getBuffer(redirectedTypes.computeIfAbsent(original,type->
                new RenderType("panel_composite",type.format(),type.mode(),type.bufferSize(),type.affectsCrumbling(),type.sortOnUpload(),
                        ()->{type.setupRenderState();framebuffer.bindWrite(true);GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);GL11.glColorMask(true,true,true,true);},
                        type::clearRenderState){}));
        private final AbstractTexture texture=new AbstractTexture() {
            @Override public int getId(){return framebuffer==null?0:framebuffer.getColorTextureId();}
            // RenderType normally overwrites filtering before every draw. This texture owns a
            // mip chain, so retain the sampler configured when its GL allocation was created.
            @Override public void setFilter(TriState blur,boolean mipmap){}
            @Override public void setFilter(boolean blur,boolean mipmap){}
            @Override public void setClamp(boolean clamp){}
            @Override public void releaseId(){}
            @Override public void close(){}
        };
        private boolean registered,closed;
        private boolean samplerDirty;
        private boolean samplerSmoothing;
        private ScreenColorSampler lightSampler;

        Target(ResourceLocation location){this.location=location;}

        void ensure(int width,int height) {
            if(framebuffer==null) {
                framebuffer=new TextureTarget(width,height,true);
                samplerDirty=true;
            } else if(framebuffer.width!=width||framebuffer.height!=height) {
                framebuffer.resize(width,height);
                samplerDirty=true;
            }
            if(!registered) {
                Minecraft.getInstance().getTextureManager().register(location,texture);
                registered=true;
            }
        }

        void generateMipmaps(WorldPanel panel) {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,0);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,0);
            RenderSystem.bindTexture(framebuffer.getColorTextureId());
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            if(samplerDirty||samplerSmoothing!=ModSettings.panelSmoothing)configureSampler();
            if(panel.projectsLight()&&ScreenLighting.samplingEnabled()) {
                if(lightSampler==null)lightSampler=new ScreenColorSampler();
                lightSampler.capture(framebuffer.getColorTextureId(),framebuffer.width,framebuffer.height,panel::screenLightColors);
            }
        }

        private void configureSampler() {
            RenderSystem.bindTexture(framebuffer.getColorTextureId());
            boolean smoothing=ModSettings.panelSmoothing;
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,smoothing?GL11.GL_LINEAR_MIPMAP_LINEAR:GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
            if(GL.getCapabilities().GL_EXT_texture_filter_anisotropic) {
                if(Float.isNaN(maximumAnisotropy))
                    maximumAnisotropy=Math.min(16,GL11.glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT));
                GL11.glTexParameterf(GL11.GL_TEXTURE_2D,EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT,smoothing?maximumAnisotropy:1);
            }
            samplerDirty=false;
            samplerSmoothing=smoothing;
        }

        @Override public void close() {
            if(closed)return;
            RenderSystem.assertOnRenderThread();
            closed=true;
            buffers.endBatch();
            if(registered)Minecraft.getInstance().getTextureManager().release(location);
            if(framebuffer!=null)framebuffer.destroyBuffers();
            if(lightSampler!=null)lightSampler.close();
            vertices.close();
            framebuffer=null;lightSampler=null;registered=false;redirectedTypes.clear();
        }
    }

    private static final class RenderState {
        private final int drawFramebuffer,readFramebuffer,readBuffer,depthFunction;
        private final int[] viewport=new int[4],scissor=new int[4],drawBuffers;
        private final boolean scissored,depth,cull,blend,depthMask;
        private final boolean[] colorMask=new boolean[4];
        private final int blendSource,blendDestination,blendSourceAlpha,blendDestinationAlpha;
        private final Matrix4f projection=new Matrix4f(RenderSystem.getProjectionMatrix());
        private final ProjectionType projectionType=RenderSystem.getProjectionType();
        private final FogParameters fog=RenderSystem.getShaderFog();
        private final CompiledShaderProgram shader=RenderSystem.getShader();
        private final float[] shaderColor=RenderSystem.getShaderColor().clone();

        RenderState() {
            try(var stack=MemoryStack.stackPush()) {
                drawFramebuffer=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
                readFramebuffer=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
                readBuffer=GL11.glGetInteger(GL11.GL_READ_BUFFER);
                var values=stack.mallocInt(4);GL11.glGetIntegerv(GL11.GL_VIEWPORT,values);
                for(int i=0;i<4;i++)viewport[i]=values.get(i);
                GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX,values);
                for(int i=0;i<4;i++)scissor[i]=values.get(i);
                int count=GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS);drawBuffers=new int[count];
                for(int i=0;i<count;i++)drawBuffers[i]=GL11.glGetInteger(GL20.GL_DRAW_BUFFER0+i);
                var mask=stack.malloc(4);GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK,mask);
                for(int i=0;i<4;i++)colorMask[i]=mask.get(i)!=0;
            }
            scissored=GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            cull=GL11.glIsEnabled(GL11.GL_CULL_FACE);
            blend=GL11.glIsEnabled(GL11.GL_BLEND);
            depthMask=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            depthFunction=GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
            blendSource=GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
            blendDestination=GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
            blendSourceAlpha=GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
            blendDestinationAlpha=GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        }

        void restore() {
            RenderSystem.setProjectionMatrix(projection,projectionType);
            RenderSystem.setShaderFog(fog);RenderSystem.setShader(shader);
            RenderSystem.setShaderColor(shaderColor[0],shaderColor[1],shaderColor[2],shaderColor[3]);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,drawFramebuffer);
            try(var stack=MemoryStack.stackPush()) {
                var values=stack.mallocInt(drawBuffers.length);values.put(drawBuffers).flip();GL20.glDrawBuffers(values);
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,readFramebuffer);GL11.glReadBuffer(readBuffer);
            GL11.glColorMask(colorMask[0],colorMask[1],colorMask[2],colorMask[3]);
            RenderSystem.viewport(viewport[0],viewport[1],viewport[2],viewport[3]);
            if(scissored)RenderSystem.enableScissor(scissor[0],scissor[1],scissor[2],scissor[3]);else RenderSystem.disableScissor();
            if(depth)RenderSystem.enableDepthTest();else RenderSystem.disableDepthTest();
            if(cull)RenderSystem.enableCull();else RenderSystem.disableCull();
            if(blend)RenderSystem.enableBlend();else RenderSystem.disableBlend();
            RenderSystem.blendFuncSeparate(blendSource,blendDestination,blendSourceAlpha,blendDestinationAlpha);
            RenderSystem.depthMask(depthMask);RenderSystem.depthFunc(depthFunction);
        }
    }
}
