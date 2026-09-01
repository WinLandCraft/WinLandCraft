package dev.winlandcraft;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.*;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/** Renders just the browser surface into a small FBO; never captures the desktop or game screen. */
final class StreamCapture implements AutoCloseable {
    record Pixels(byte[] rgba,int width,int height) {}
    private TextureTarget target;
    Pixels capture(BrowserPanel panel) {
        return capture(panel,StreamQuality.current());
    }
    Pixels capture(BrowserPanel panel,StreamQuality quality) {
        RenderSystem.assertOnRenderThread();
        int logicalWidth=panel.pixelWidth(),logicalHeight=panel.pixelHeight()+panel.titlebarHeight();
        int maxHeight=quality.height(),maxWidth=maxHeight*16/9;
        double factor=Math.min(1,Math.min(maxWidth/(double)logicalWidth,maxHeight/(double)logicalHeight));
        int w=Math.max(2,(int)(logicalWidth*factor)&~1),h=Math.max(2,(int)(logicalHeight*factor)&~1);
        int draw=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),read=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int[] viewport=new int[4];GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);
        int[] scissor=new int[4];GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX,scissor);
        boolean scissored=GL11.glIsEnabled(GL11.GL_SCISSOR_TEST),depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST),cull=GL11.glIsEnabled(GL11.GL_CULL_FACE),blend=GL11.glIsEnabled(GL11.GL_BLEND);
        int depthFunc=GL11.glGetInteger(GL11.GL_DEPTH_FUNC);boolean depthMask=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int pack=GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT),row=GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH),skipRows=GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS),skipPixels=GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        var projection=new Matrix4f(RenderSystem.getProjectionMatrix());var projectionType=RenderSystem.getProjectionType();
        var fog=RenderSystem.getShaderFog();var shader=RenderSystem.getShader();var color=RenderSystem.getShaderColor().clone();
        int blendSrc=GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_SRC_RGB),blendDst=GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_DST_RGB);
        int blendSrcAlpha=GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_SRC_ALPHA),blendDstAlpha=GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_DST_ALPHA);
        int packBuffer=GL11.glGetInteger(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        var modelView=RenderSystem.getModelViewStack();modelView.pushMatrix();modelView.identity();
        try {
            if(target==null||target.width!=w||target.height!=h){close();target=new TextureTarget(w,h,true);}
            RenderSystem.disableScissor();target.setClearColor(.09f,.12f,.17f,1);target.clear();target.bindWrite(true);
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0,logicalWidth,logicalHeight,0,-100,100),ProjectionType.ORTHOGRAPHIC);
            RenderSystem.setShaderFog(FogParameters.NO_FOG);RenderSystem.setShaderColor(1,1,1,1);
            RenderSystem.enableDepthTest();RenderSystem.depthMask(true);RenderSystem.depthFunc(GL11.GL_LEQUAL);
            var pose=new PoseStack();pose.translate(0,panel.titlebarHeight(),0);
            try(var vertices=new ByteBufferBuilder(256*1024)) {
                var buffers=MultiBufferSource.immediate(vertices);
                // Vanilla render types bind Minecraft's main target during setup. Override the
                // destination AFTER that setup, including for font glyphs and browser textures.
                var types=new java.util.IdentityHashMap<RenderType,RenderType>();
                MultiBufferSource redirected=original->buffers.getBuffer(types.computeIfAbsent(original,type->
                        new RenderType("stream_capture",type.format(),type.mode(),type.bufferSize(),type.affectsCrumbling(),type.sortOnUpload(),
                                ()->{type.setupRenderState();target.bindWrite(false);},type::clearRenderState){}));
                try(var canvas=new PanelCanvas(pose,redirected)){panel.drawSurface(canvas);}
                buffers.endBatch();
            }
            // Binding for drawing does not necessarily change the read framebuffer.
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,target.frameBufferId);
            RenderSystem.pixelStore(GL11.GL_PACK_ALIGNMENT,1);RenderSystem.pixelStore(GL11.GL_PACK_ROW_LENGTH,0);
            RenderSystem.pixelStore(GL11.GL_PACK_SKIP_ROWS,0);RenderSystem.pixelStore(GL11.GL_PACK_SKIP_PIXELS,0);
            RenderSystem.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER,0);
            var pixels=MemoryUtil.memAlloc(w*h*4);
            try {
                RenderSystem.readPixels(0,0,w,h,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
                byte[] bytes=new byte[w*h*4];pixels.get(bytes);return new Pixels(bytes,w,h);
            } finally {MemoryUtil.memFree(pixels);}
        } finally {
            modelView.popMatrix();RenderSystem.setProjectionMatrix(projection,projectionType);RenderSystem.setShaderFog(fog);
            RenderSystem.setShader(shader);RenderSystem.setShaderColor(color[0],color[1],color[2],color[3]);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,draw);GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,read);
            RenderSystem.viewport(viewport[0],viewport[1],viewport[2],viewport[3]);
            if(scissored)RenderSystem.enableScissor(scissor[0],scissor[1],scissor[2],scissor[3]);else RenderSystem.disableScissor();
            if(depth)RenderSystem.enableDepthTest();else RenderSystem.disableDepthTest();
            if(cull)RenderSystem.enableCull();else RenderSystem.disableCull();
            if(blend)RenderSystem.enableBlend();else RenderSystem.disableBlend();
            RenderSystem.blendFuncSeparate(blendSrc,blendDst,blendSrcAlpha,blendDstAlpha);
            RenderSystem.depthMask(depthMask);RenderSystem.depthFunc(depthFunc);
            RenderSystem.pixelStore(GL11.GL_PACK_ALIGNMENT,pack);RenderSystem.pixelStore(GL11.GL_PACK_ROW_LENGTH,row);
            RenderSystem.pixelStore(GL11.GL_PACK_SKIP_ROWS,skipRows);RenderSystem.pixelStore(GL11.GL_PACK_SKIP_PIXELS,skipPixels);
            RenderSystem.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER,packBuffer);
        }
    }
    @Override public void close(){if(target!=null){target.destroyBuffers();target=null;}}
}
