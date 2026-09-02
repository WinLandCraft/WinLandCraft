package dev.winlandcraft;
import dev.winlandcraft.api.v2.*;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import com.mojang.blaze3d.systems.RenderSystem;

final class PluginFrames implements FrameSurface {
    private final PluginFrameMailbox mailbox=new PluginFrameMailbox();
    private final ResourceLocation location=ResourceLocation.fromNamespaceAndPath("winlandcraft","plugin_frame/"+java.util.UUID.randomUUID());
    private AbstractTexture texture;private ByteBuffer staging;private int width,height;private boolean visible,flipped,closed,detachGpu,inCallback;
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
        int binding=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),pbo=GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
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
            if(texture==null){texture=new AbstractTexture(){};Minecraft.getInstance().getTextureManager().register(location,texture);}
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture.getId());
            if(width!=frame.width()||height!=frame.height()){
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,0);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,frame.width(),frame.height(),0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
            }
            ARBCopyImage.glCopyImageSubData(frame.textureId(),GL11.GL_TEXTURE_2D,0,0,0,0,texture.getId(),GL11.GL_TEXTURE_2D,0,0,0,0,frame.width(),frame.height(),1);
            width=frame.width();height=frame.height();flipped=!frame.topLeftOrigin();visible=true;
        }finally{GL11.glBindTexture(GL11.GL_TEXTURE_2D,binding);GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,pbo);}
    }
    private void upload(PluginFrameMailbox.Frame frame){
        if(texture==null){texture=new AbstractTexture(){};Minecraft.getInstance().getTextureManager().register(location,texture);}
        int size=frame.pixels().length;
        if(staging==null||staging.capacity()!=size){if(staging!=null)MemoryUtil.memFree(staging);staging=MemoryUtil.memAlloc(size);}
        staging.clear();staging.put(frame.pixels()).flip();
        int binding=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),pbo=GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int[] fields={GL11.GL_UNPACK_ALIGNMENT,GL11.GL_UNPACK_ROW_LENGTH,GL11.GL_UNPACK_SKIP_ROWS,GL11.GL_UNPACK_SKIP_PIXELS};int[] old=new int[fields.length];
        for(int i=0;i<fields.length;i++)old[i]=GL11.glGetInteger(fields[i]);
        try {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,0);
            for(int i=0;i<fields.length;i++)GL11.glPixelStorei(fields[i],i==0?1:0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture.getId());
            if(width!=frame.width()||height!=frame.height()){
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,frame.width(),frame.height(),0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_LINEAR);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
            }
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,frame.width(),frame.height(),frame.format()==PixelFormat.RGBA8?GL11.GL_RGBA:GL12.GL_BGRA,GL11.GL_UNSIGNED_BYTE,staging);
            width=frame.width();height=frame.height();flipped=false;visible=true;
        }finally{
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,binding);GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,pbo);
            for(int i=0;i<fields.length;i++)GL11.glPixelStorei(fields[i],old[i]);
        }
    }
    synchronized void close(){RenderSystem.assertOnRenderThread();closed=true;mailbox.close();disposeGpu();if(texture!=null){Minecraft.getInstance().getTextureManager().release(location);texture=null;}if(staging!=null){MemoryUtil.memFree(staging);staging=null;}visible=false;width=height=0;}
}
