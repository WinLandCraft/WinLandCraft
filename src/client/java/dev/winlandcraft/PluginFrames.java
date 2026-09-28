package dev.winlandcraft;
import dev.winlandcraft.api.v2.*;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import com.mojang.blaze3d.systems.RenderSystem;

final class PluginFrames implements FrameSurface {
    private final PluginFrameMailbox mailbox=new PluginFrameMailbox();
    private final Identifier location=Identifier.fromNamespaceAndPath("winlandcraft","plugin_frame/"+java.util.UUID.randomUUID());
    private final CpuUploadTexture video=new CpuUploadTexture();
    private boolean registered;
    private ByteBuffer staging;private int width,height;private boolean visible,flipped,closed,detachGpu,inCallback;
    private GpuSource gpu;
    public synchronized boolean submit(ByteBuffer pixels,int w,int h,int stride,PixelFormat format){return !closed&&gpu==null&&mailbox.submit(pixels,w,h,stride,format);}
    public synchronized void clear(){mailbox.clear();detachGpu=true;}
    public boolean supportsGpu(){RenderSystem.assertOnRenderThread();var caps=GL.getCapabilities();return caps.OpenGL43||caps.GL_ARB_copy_image;}
    public synchronized void gpuSource(GpuSource source){
        RenderSystem.assertOnRenderThread();
        if(closed||inCallback)throw new IllegalStateException("GPU surface closed or inside a producer callback");
        if(source!=null&&!supportsGpu())throw new UnsupportedOperationException("OpenGL copy-image unavailable");
        if(gpu==source)return;
        disposeGpu();gpu=source;detachGpu=false;mailbox.clear();mailbox.take();visible=false;
    }
    private void disposeGpu(){
        GpuSource previous=gpu;gpu=null;
        if(previous!=null){inCallback=true;try{previous.close();}catch(RuntimeException|LinkageError|AssertionError failure){WinLandCraftClient.LOGGER.error("Plugin GPU cleanup failed",failure);}finally{inCallback=false;}}
    }
    synchronized void draw(PanelCanvas canvas,int availableWidth,int availableHeight){
        RenderSystem.assertOnRenderThread();var update=mailbox.take();if(update.clear())visible=false;
        if(detachGpu){detachGpu=false;disposeGpu();}
        if(update.frame()!=null)upload(update.frame());
        if(gpu!=null){
            inCallback=true;
            try{PluginGpuTransfer.copy(gpu,this::copy);}
            finally{inCallback=false;}
        }
        if(!visible)return;
        float scale=Math.min(availableWidth/(float)width,availableHeight/(float)height);
        int w=Math.max(1,Math.round(width*scale)),h=Math.max(1,Math.round(height*scale));
        canvas.texture(location,(availableWidth-w)/2f,(availableHeight-h)/2f,w,h,.21f,-1,0,flipped?1:0,1,flipped?0:1);
    }
    private void copy(GpuFrame frame){
        try{
            if(!GL11.glIsTexture(frame.textureId()))throw new IllegalArgumentException("GPU source is not a live texture");
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,frame.textureId());
            int filter=GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER);
            if((filter!=GL11.GL_LINEAR&&filter!=GL11.GL_NEAREST)||GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D,GL12.GL_TEXTURE_BASE_LEVEL)!=0)
                throw new IllegalArgumentException("GPU source needs base level 0 and non-mipmapped minification");
            if(GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_WIDTH)!=frame.width()
                    ||GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_HEIGHT)!=frame.height()
                    ||GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_INTERNAL_FORMAT)!=GL11.GL_RGBA8)
                throw new IllegalArgumentException("GPU source must match its dimensions and use RGBA8");
            if(!registered){Minecraft.getInstance().getTextureManager().register(location,video);registered=true;}
            if(!video.ensure(frame.width(),frame.height()))throw new IllegalStateException("Could not allocate plugin frame texture");
            int dst=video.glId();
            if(dst==0)throw new IllegalStateException("Plugin GPU copy needs the OpenGL backend");
            ARBCopyImage.glCopyImageSubData(frame.textureId(),GL11.GL_TEXTURE_2D,0,0,0,0,dst,GL11.GL_TEXTURE_2D,0,0,0,0,frame.width(),frame.height(),1);
            width=frame.width();height=frame.height();flipped=!frame.topLeftOrigin();visible=true;
        }finally{GL11.glBindTexture(GL11.GL_TEXTURE_2D,0);}
    }
    private void upload(PluginFrameMailbox.Frame frame){
        int size=frame.pixels().length;
        if(staging==null||staging.capacity()!=size){if(staging!=null)MemoryUtil.memFree(staging);staging=MemoryUtil.memAlloc(size);}
        staging.clear();staging.put(frame.pixels()).flip();
        if(frame.format()==PixelFormat.BGRA8)swizzleBGRA(staging,size);
        if(!registered){Minecraft.getInstance().getTextureManager().register(location,video);registered=true;}
        if(!video.upload(staging,frame.width(),frame.height()))return;
        width=frame.width();height=frame.height();flipped=false;visible=true;
    }
    /** In-place BGRA to RGBA swizzle; RenderPearl has no BGRA format. */
    private static void swizzleBGRA(ByteBuffer staging,int size) {
        for(int i=0;i<size;i+=4){byte b=staging.get(i);staging.put(i,staging.get(i+2));staging.put(i+2,b);}
    }
    synchronized void close(){RenderSystem.assertOnRenderThread();closed=true;mailbox.close();disposeGpu();if(registered){Minecraft.getInstance().getTextureManager().release(location);registered=false;}video.close();if(staging!=null){MemoryUtil.memFree(staging);staging=null;}visible=false;width=height=0;}
}
