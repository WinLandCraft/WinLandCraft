package dev.winlandcraft;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.*;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.concurrent.ArrayBlockingQueue;

/** Renders just the app surface into a small FBO; never captures the desktop or game screen. */
final class StreamCapture implements AutoCloseable {
    /** A pooled top-down RGBA frame. Ownership passes to the codec bridge. */
    static final class Pixels implements AutoCloseable {
        private StreamCapture owner;
        private final byte[] rgba;
        private final int width,height;
        private Pixels(StreamCapture owner,byte[] rgba,int width,int height){this.owner=owner;this.rgba=rgba;this.width=width;this.height=height;}
        byte[] rgba(){return rgba;}
        int width(){return width;}
        int height(){return height;}
        @Override public void close(){var recycler=owner;owner=null;if(recycler!=null)recycler.recycle(rgba);}
    }
    private TextureTarget target,topDownTarget;
    private ByteBuffer readback;
    private ByteBufferBuilder vertices;
    private MultiBufferSource.BufferSource buffers;
    private final IdentityHashMap<RenderType,RenderType> redirectedTypes=new IdentityHashMap<>();
    private final MultiBufferSource redirected=original->buffers.getBuffer(redirectedTypes.computeIfAbsent(original,type->
            new RenderType("stream_capture",type.format(),type.mode(),type.bufferSize(),type.affectsCrumbling(),type.sortOnUpload(),
                    ()->{type.setupRenderState();target.bindWrite(true);GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);GL11.glColorMask(true,true,true,true);},type::clearRenderState){}));
    private final ArrayBlockingQueue<byte[]> freeFrames=new ArrayBlockingQueue<>(4);
    private long captures,nextDiagnostic;
    private boolean inspectedFailure,loggedOpenGl;
    Pixels capture(WorldPanel panel) {
        return capture(panel,StreamQuality.current());
    }
    Pixels capture(WorldPanel panel,StreamQuality quality) {
        RenderSystem.assertOnRenderThread();
        int logicalWidth=panel.pixelWidth(),logicalHeight=panel.pixelHeight()+panel.titlebarHeight();
        int maxHeight=quality.height(),maxWidth=maxHeight*16/9;
        double factor=Math.min(1,Math.min(maxWidth/(double)logicalWidth,maxHeight/(double)logicalHeight));
        int w=Math.max(2,(int)(logicalWidth*factor)&~1),h=Math.max(2,(int)(logicalHeight*factor)&~1);
        try(var stack=MemoryStack.stackPush()) {
            int draw=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),read=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            var viewport=stack.mallocInt(4);GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);
            var scissor=stack.mallocInt(4);GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX,scissor);
            boolean scissored=GL11.glIsEnabled(GL11.GL_SCISSOR_TEST),depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST),cull=GL11.glIsEnabled(GL11.GL_CULL_FACE),blend=GL11.glIsEnabled(GL11.GL_BLEND);
            int depthFunc=GL11.glGetInteger(GL11.GL_DEPTH_FUNC);boolean depthMask=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            int pack=GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT),row=GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH),skipRows=GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS),skipPixels=GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
            int readBuffer=GL11.glGetInteger(GL11.GL_READ_BUFFER);
            int maxDrawBuffers=GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS);var drawBuffers=stack.mallocInt(maxDrawBuffers);
            for(int i=0;i<maxDrawBuffers;i++)drawBuffers.put(i,GL11.glGetInteger(GL20.GL_DRAW_BUFFER0+i));
            var colorMask=stack.malloc(4);GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK,colorMask);
            var projection=new Matrix4f(RenderSystem.getProjectionMatrix());var projectionType=RenderSystem.getProjectionType();
            var fog=RenderSystem.getShaderFog();var shader=RenderSystem.getShader();var shaderColor=RenderSystem.getShaderColor();
            float red=shaderColor[0],green=shaderColor[1],blue=shaderColor[2],alpha=shaderColor[3];
            int blendSrc=GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_SRC_RGB),blendDst=GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_DST_RGB);
            int blendSrcAlpha=GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_SRC_ALPHA),blendDstAlpha=GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_DST_ALPHA);
            int packBuffer=GL11.glGetInteger(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            var modelView=RenderSystem.getModelViewStack();modelView.pushMatrix();modelView.identity();
            try {
                if(target==null||target.width!=w||target.height!=h){
                    if(target!=null)target.destroyBuffers();if(topDownTarget!=null)topDownTarget.destroyBuffers();
                    target=new TextureTarget(w,h,true);topDownTarget=new TextureTarget(w,h,false);inspectedFailure=false;
                }
                if(vertices==null){vertices=new ByteBufferBuilder(256*1024);buffers=MultiBufferSource.immediate(vertices);}
                RenderSystem.disableScissor();target.setClearColor(.09f,.12f,.17f,1);target.bindWrite(true);
                // glClear(GL_DEPTH_BUFFER_BIT) obeys GL_DEPTH_WRITEMASK. World rendering can
                // leave it disabled at this hook, which made the fresh depth attachment stay
                // at zero and reject every panel fragment on Mesa. Force writes before clear;
                // the caller's mask is restored in the finally block below.
                RenderSystem.depthMask(true);
                GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);GL11.glColorMask(true,true,true,true);target.clear();target.bindWrite(true);
                GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);GL11.glColorMask(true,true,true,true);
                // Preserve the same top-left projection and winding used by normal panel rendering.
                // Changing this projection can make individual RenderTypes cull their entire output.
                RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0,logicalWidth,logicalHeight,0,-100,100),ProjectionType.ORTHOGRAPHIC);
                RenderSystem.setShaderFog(FogParameters.NO_FOG);RenderSystem.setShaderColor(1,1,1,1);
                RenderSystem.disableCull();
                RenderSystem.enableDepthTest();RenderSystem.depthMask(true);RenderSystem.depthFunc(GL11.GL_LEQUAL);
                var pose=new PoseStack();pose.translate(0,panel.titlebarHeight(),0);
                // Vanilla render types bind Minecraft's main target during setup. Override the
                // destination AFTER that setup, including for font glyphs and browser textures.
                try {
                    try(var canvas=new PanelCanvas(pose,redirected)){panel.drawSurface(canvas);}
                } finally {buffers.endBatch();}
                // Flip vertically with a framebuffer blit. This retains normal UI winding while
                // producing top-down bytes for WebCodecs without a full-frame CPU allocation/copy.
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,target.frameBufferId);
                int framebufferStatus=GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER);
                if(framebufferStatus!=GL30.GL_FRAMEBUFFER_COMPLETE)
                    throw new IllegalStateException("Stream capture framebuffer is incomplete: 0x"+Integer.toHexString(framebufferStatus));
                GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,topDownTarget.frameBufferId);GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
                framebufferStatus=GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);
                if(framebufferStatus!=GL30.GL_FRAMEBUFFER_COMPLETE)
                    throw new IllegalStateException("Stream capture flip framebuffer is incomplete: 0x"+Integer.toHexString(framebufferStatus));
                RenderSystem.disableScissor();
                GL30.glBlitFramebuffer(0,0,w,h,0,h,w,0,GL11.GL_COLOR_BUFFER_BIT,GL11.GL_NEAREST);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,topDownTarget.frameBufferId);GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
                RenderSystem.pixelStore(GL11.GL_PACK_ALIGNMENT,1);RenderSystem.pixelStore(GL11.GL_PACK_ROW_LENGTH,0);
                RenderSystem.pixelStore(GL11.GL_PACK_SKIP_ROWS,0);RenderSystem.pixelStore(GL11.GL_PACK_SKIP_PIXELS,0);
                RenderSystem.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER,0);
                int size=w*h*4;
                if(readback==null||readback.capacity()<size){if(readback!=null)MemoryUtil.memFree(readback);readback=MemoryUtil.memAlloc(size);}
                readback.clear();readback.limit(size);RenderSystem.readPixels(0,0,w,h,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,readback);
                byte[] bytes=acquire(size);readback.get(bytes);
                var stats=statistics(bytes);String failureDetail=null;
                // A single failure-only read of the primary target distinguishes panel
                // rendering failures from flip-target failures without taxing healthy streams.
                if(stats.solid()&&!inspectedFailure){inspectedFailure=true;failureDetail=inspectPrimaryFailure(stack,w,h,size,depthMask);}
                diagnose(stats,w,h,"off-screen panel, GPU-flipped",failureDetail);return new Pixels(this,bytes,w,h);
            } finally {
                modelView.popMatrix();RenderSystem.setProjectionMatrix(projection,projectionType);RenderSystem.setShaderFog(fog);
                RenderSystem.setShader(shader);RenderSystem.setShaderColor(red,green,blue,alpha);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,draw);
                GL20.glDrawBuffers(drawBuffers);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,read);GL11.glReadBuffer(readBuffer);
                GL11.glColorMask(colorMask.get(0)!=0,colorMask.get(1)!=0,colorMask.get(2)!=0,colorMask.get(3)!=0);
                RenderSystem.viewport(viewport.get(0),viewport.get(1),viewport.get(2),viewport.get(3));
                if(scissored)RenderSystem.enableScissor(scissor.get(0),scissor.get(1),scissor.get(2),scissor.get(3));else RenderSystem.disableScissor();
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
    }
    private byte[] acquire(int size){byte[] bytes;while((bytes=freeFrames.poll())!=null)if(bytes.length==size)return bytes;return new byte[size];}
    private void recycle(byte[] bytes){freeFrames.offer(bytes);}
    private String inspectPrimaryFailure(MemoryStack stack,int width,int height,int size,boolean inheritedDepthMask) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,target.frameBufferId);GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        readback.clear();readback.limit(size);RenderSystem.readPixels(0,0,width,height,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,readback);
        byte[] primary=acquire(size);readback.get(primary);var primaryStats=statistics(primary);recycle(primary);
        var depthSample=stack.mallocFloat(1);
        GL11.glReadPixels(width/2,height/2,1,1,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,depthSample);
        return "primarySolid="+primaryStats.solid()+", primaryRgb="+primaryStats.redMin+".."+primaryStats.redMax+','+
                primaryStats.greenMin+".."+primaryStats.greenMax+','+primaryStats.blueMin+".."+primaryStats.blueMax+
                ", depthCenter="+depthSample.get(0)+", inheritedDepthWrite="+inheritedDepthMask;
    }
    private void diagnose(Statistics stats,int width,int height,String source,String failureDetail) {
        long now=System.currentTimeMillis();captures++;
        if(!loggedOpenGl) {
            loggedOpenGl=true;
            WinLandCraftClient.LOGGER.info("Stream capture OpenGL on {} {}: vendor='{}', renderer='{}', version='{}'",
                    System.getProperty("os.name"),System.getProperty("os.arch"),GL11.glGetString(GL11.GL_VENDOR),
                    GL11.glGetString(GL11.GL_RENDERER),GL11.glGetString(GL11.GL_VERSION));
        }
        if(captures!=1&&now<nextDiagnostic)return;
        nextDiagnostic=now+10_000;
        String summary="source="+source+", frame="+width+'x'+height+", sampled="+stats.samples+", rgb-range="+
                stats.redMin+".."+stats.redMax+','+stats.greenMin+".."+stats.greenMax+','+stats.blueMin+".."+stats.blueMax+
                ", mean="+stats.redMean+','+stats.greenMean+','+stats.blueMean+", hash="+Long.toUnsignedString(stats.hash,16);
        if(stats.solid())WinLandCraftClient.LOGGER.warn("Stream capture is solid-colored; the receiver will see a blank frame ({}{})",
                summary,failureDetail==null?"":"; "+failureDetail);
        else WinLandCraftClient.LOGGER.info("Stream capture health: {}",summary);
    }
    private static Statistics statistics(byte[] bytes) {
        int pixels=bytes.length/4,step=Math.max(1,pixels/2048),samples=0,redMin=255,redMax=0,greenMin=255,greenMax=0,blueMin=255,blueMax=0;
        long red=0,green=0,blue=0,hash=0xcbf29ce484222325L;
        for(int pixel=0;pixel<pixels;pixel+=step) {
            int offset=pixel*4,r=bytes[offset]&255,g=bytes[offset+1]&255,b=bytes[offset+2]&255;
            redMin=Math.min(redMin,r);redMax=Math.max(redMax,r);greenMin=Math.min(greenMin,g);greenMax=Math.max(greenMax,g);blueMin=Math.min(blueMin,b);blueMax=Math.max(blueMax,b);
            red+=r;green+=g;blue+=b;samples++;
            hash^=(long)r<<16|(long)g<<8|b;hash*=0x100000001b3L;
        }
        return new Statistics(samples,redMin,redMax,greenMin,greenMax,blueMin,blueMax,red/samples,green/samples,blue/samples,hash);
    }
    private record Statistics(int samples,int redMin,int redMax,int greenMin,int greenMax,int blueMin,int blueMax,
                              long redMean,long greenMean,long blueMean,long hash){
        boolean solid(){return redMax-redMin<=2&&greenMax-greenMin<=2&&blueMax-blueMin<=2;}
    }
    @Override public void close(){
        if(target!=null){target.destroyBuffers();target=null;}
        if(topDownTarget!=null){topDownTarget.destroyBuffers();topDownTarget=null;}
        if(readback!=null){MemoryUtil.memFree(readback);readback=null;}
        if(vertices!=null){vertices.close();vertices=null;buffers=null;redirectedTypes.clear();}
        freeFrames.clear();captures=nextDiagnostic=0;inspectedFailure=false;
    }
}
