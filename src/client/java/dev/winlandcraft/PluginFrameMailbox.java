package dev.winlandcraft;
import dev.winlandcraft.api.v2.*;
import java.nio.ByteBuffer;
import java.util.Objects;
/** CPU-only bounded ownership boundary between capture workers and rendering. */
final class PluginFrameMailbox {
    record Frame(byte[] pixels,int width,int height,PixelFormat format) { }
    record Update(Frame frame,boolean clear) { }
    private Frame pending;private boolean cleared,closed;
    synchronized boolean submit(ByteBuffer input,int width,int height,int stride,PixelFormat format){
        if(closed)return false;Objects.requireNonNull(input);Objects.requireNonNull(format);
        if(width<1||height<1||width>4096||height>4096||stride<(long)width*4)throw new IllegalArgumentException("Invalid frame size/stride (maximum 4096 x 4096)");
        long needed=(long)(height-1)*stride+width*4L;
        if(needed>input.remaining())throw new IllegalArgumentException("Frame buffer is too short");
        byte[] pixels=new byte[width*height*4];var view=input.duplicate();int base=view.position();
        for(int y=0;y<height;y++)view.get(base+y*stride,pixels,y*width*4,width*4);
        pending=new Frame(pixels,width,height,format);return true;
    }
    synchronized void clear(){pending=null;cleared=true;}
    synchronized Update take(){var result=new Update(pending,cleared);pending=null;cleared=false;return result;}
    synchronized void close(){closed=true;clear();}
}
