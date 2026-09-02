package dev.winlandcraft;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

/** Asynchronous six-zone sampling from a panel's existing mip chain. */
final class ScreenColorSampler implements AutoCloseable {
    private static final long INTERVAL=100_000_000L;
    private static final int COLUMNS=3,ROWS=2;
    private static final float BLACK_LEVEL=.002f,FULL_OUTPUT=.03f,SATURATION=1.5f;
    private final Slot[] slots={new Slot(),new Slot()};
    private int cursor;
    private long nextCapture;

    void capture(int texture,int sourceWidth,int sourceHeight,Consumer<float[]> result) {
        RenderSystem.assertOnRenderThread();
        poll(result);
        long now=System.nanoTime();
        if(now<nextCapture)return;
        Slot slot=freeSlot();
        if(slot==null)return;
        nextCapture=now+INTERVAL;
        int width=sourceWidth,height=sourceHeight,level=0;
        while(width>COLUMNS*2||height>ROWS*2) {
            width=Math.max(1,width/2);height=Math.max(1,height/2);level++;
        }
        int previous=GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        slot.id=slot.id==0?GL15.glGenBuffers():slot.id;
        slot.width=width;slot.height=height;slot.bytes=width*height*4;
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,slot.id);
        GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER,slot.bytes,GL15.GL_STREAM_READ);
        int previousTexture=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D,level,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,0L);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D,previousTexture);
        slot.fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,previous);
        cursor=(cursor+1)%slots.length;
    }

    private Slot freeSlot() {
        for(int offset=0;offset<slots.length;offset++) {
            Slot slot=slots[(cursor+offset)%slots.length];
            if(slot.fence==0)return slot;
        }
        return null;
    }

    private void poll(Consumer<float[]> result) {
        for(Slot slot:slots) {
            if(slot.fence==0)continue;
            int state=GL32.glClientWaitSync(slot.fence,0,0);
            if(state==GL32.GL_WAIT_FAILED) {
                GL32.glDeleteSync(slot.fence);slot.fence=0;
                continue;
            }
            if(state!=GL32.GL_ALREADY_SIGNALED&&state!=GL32.GL_CONDITION_SATISFIED)continue;
            int previous=GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,slot.id);
            ByteBuffer pixels=GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER,0,slot.bytes,GL30.GL_MAP_READ_BIT);
            if(pixels!=null) {
                result.accept(zones(pixels,slot.width,slot.height));
                GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,previous);
            GL32.glDeleteSync(slot.fence);slot.fence=0;
        }
    }

    static float[] zones(ByteBuffer pixels,int width,int height) {
        float[] colors=new float[COLUMNS*ROWS*3];
        for(int row=0;row<ROWS;row++)for(int column=0;column<COLUMNS;column++) {
            int x0=column*width/COLUMNS,x1=Math.max(x0+1,(column+1)*width/COLUMNS);
            int y0=row*height/ROWS,y1=Math.max(y0+1,(row+1)*height/ROWS);
            float red=0,green=0,blue=0;int count=0;
            for(int y=y0;y<Math.min(y1,height);y++)for(int x=x0;x<Math.min(x1,width);x++) {
                int offset=(y*width+x)*4;
                red+=linear(pixels.get(offset)&255);green+=linear(pixels.get(offset+1)&255);blue+=linear(pixels.get(offset+2)&255);count++;
            }
            int target=(row*COLUMNS+column)*3;
            calibrate(colors,target,red/count,green/count,blue/count);
        }
        return colors;
    }

    static void calibrate(float[] output,int offset,float red,float green,float blue) {
        float luminance=red*.2126f+green*.7152f+blue*.0722f;
        float gate=smoothstep(BLACK_LEVEL,FULL_OUTPUT,luminance);
        float saturatedRed=Math.max(0,luminance+(red-luminance)*SATURATION);
        float saturatedGreen=Math.max(0,luminance+(green-luminance)*SATURATION);
        float saturatedBlue=Math.max(0,luminance+(blue-luminance)*SATURATION);
        float saturatedLuminance=saturatedRed*.2126f+saturatedGreen*.7152f+saturatedBlue*.0722f;
        float scale=saturatedLuminance==0?0:luminance/saturatedLuminance*gate;
        output[offset]=saturatedRed*scale;
        output[offset+1]=saturatedGreen*scale;
        output[offset+2]=saturatedBlue*scale;
    }

    private static float smoothstep(float low,float high,float value) {
        float unit=Math.clamp((value-low)/(high-low),0,1);
        return unit*unit*(3-2*unit);
    }

    private static float linear(int value) {
        float encoded=value/255f;
        return encoded<=.04045f?encoded/12.92f:(float)Math.pow((encoded+.055f)/1.055f,2.4);
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        for(Slot slot:slots) {
            if(slot.fence!=0){GL32.glDeleteSync(slot.fence);slot.fence=0;}
            if(slot.id!=0){GL15.glDeleteBuffers(slot.id);slot.id=0;}
        }
    }

    private static final class Slot {
        int id,width,height,bytes;
        long fence;
    }
}
