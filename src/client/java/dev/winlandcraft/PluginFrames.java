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
    private AbstractTexture texture;private ByteBuffer staging;private int width,height;private boolean visible;
    public boolean submit(ByteBuffer pixels,int w,int h,int stride,PixelFormat format){return mailbox.submit(pixels,w,h,stride,format);}
    public void clear(){mailbox.clear();}
    void draw(PanelCanvas canvas,int availableWidth,int availableHeight){
        RenderSystem.assertOnRenderThread();var update=mailbox.take();if(update.clear())visible=false;
        if(update.frame()!=null)upload(update.frame());
        if(!visible)return;
        float scale=Math.min(availableWidth/(float)width,availableHeight/(float)height);
        int w=Math.max(1,Math.round(width*scale)),h=Math.max(1,Math.round(height*scale));
        canvas.texture(location,(availableWidth-w)/2,(availableHeight-h)/2,w,h,.21f);
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
            width=frame.width();height=frame.height();visible=true;
        }finally{
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,binding);GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,pbo);
            for(int i=0;i<fields.length;i++)GL11.glPixelStorei(fields[i],old[i]);
        }
    }
    void close(){RenderSystem.assertOnRenderThread();mailbox.close();if(texture!=null){Minecraft.getInstance().getTextureManager().release(location);texture=null;}if(staging!=null){MemoryUtil.memFree(staging);staging=null;}visible=false;width=height=0;}
}
