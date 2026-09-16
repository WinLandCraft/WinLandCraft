package dev.winlandcraft;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;

/** Read-only local FFmpeg playback surface. It never loads the owner's website. */
final class RemoteStreamPanel extends WorldPanel {
    final java.util.UUID owner,session;
    final StreamProtocol.Assembly assembly=new StreamProtocol.Assembly();
    private final ResourceLocation texture;
    StreamDecoder decoder;
    private int pixelsWide=1280,pixelsHigh=752,controlTop;
    private boolean remoteControl,controlSent;
    private int lastHoverX=Integer.MIN_VALUE,lastHoverY=Integer.MIN_VALUE;
    private final java.util.Set<Integer> pressedButtons=new java.util.HashSet<>();
    private boolean keyboardCaptured,hoverDirty;
    private long lastControlSend;
    private long nextHoverSend;
    long lastState;
    long lastSequence=-1;
    long sequenceGaps,mediaPackets,videoPackets,audioPackets,mediaBytes,lastVideoFrame,nextHealth;
    // Render-thread GL upload state for the newest decoded frame.
    private int textureId,uploadedWidth,uploadedHeight;
    private long uploadedSequence=-1;
    private ByteBuffer staging;
    RemoteStreamPanel(StreamProtocol.State state) {
        super(3.2f,1.88f);owner=state.owner();session=state.session();remoteControl=state.remoteControl();controlTop=state.titlebarPixels();
        texture=ResourceLocation.fromNamespaceAndPath("winlandcraft","stream/"+owner+"/"+session);
    }
    @Override public boolean canInteract(){return remoteControl;}
    @Override protected boolean projectsLight(){return true;}
    @Override public boolean canResize(){return false;}
    @Override public boolean canMove(){return false;}
    @Override public boolean canGroup(){return false;}
    @Override public boolean acceptsKeyboard(){return remoteControl;}
    @Override public int pixelWidth(){return pixelsWide;}
    @Override public int pixelHeight(){return pixelsHigh;}
    @Override public void resize(float width,float height){scaleTo(width,height);}
    void apply(StreamProtocol.State state) {
        level=Minecraft.getInstance().level;
        if(remoteControl&&!state.remoteControl())cancelControl();
        remoteControl=state.remoteControl();controlTop=state.titlebarPixels();place(state);lastState=System.currentTimeMillis();
    }
    void place(StreamProtocol.State state) {
        curve=null;
        position=new Vec3(state.x(),state.y(),state.z());orientation=new Quaternionf(state.qx(),state.qy(),state.qz(),state.qw()).normalize();
        scaleTo(state.width(),state.height());pixelsWide=state.pixelsWide();pixelsHigh=state.pixelsHigh();
        if(state.curve()>0){var bend=GroupCurve.get(this);bend.facing=state.facing();bend.apply(state.curve());}
    }
    void start(StreamDecoder endpoint) {
        decoder=endpoint;uploadedSequence=-1;
        Minecraft.getInstance().getTextureManager().register(texture,new AbstractTexture(){
            @Override public int getId(){return textureId;}
            @Override public void releaseId(){}
            @Override public void close(){}
        });
    }
    void receive(byte[] packet) {
        if(decoder==null)return;
        var media=decoder.receive(packet);
        if(media!=null){
            mediaPackets++;mediaBytes+=packet.length;
            if(media.kind()==StreamMedia.VIDEO){videoPackets++;lastVideoFrame=System.currentTimeMillis();}else audioPackets++;
        }
    }
    private void send(java.util.function.Function<java.util.UUID,StreamProtocol.Control> event) {
        var client=Minecraft.getInstance();
        if(!remoteControl||client.player==null||!ClientPlayNetworking.canSend(StreamProtocol.Control.TYPE))return;
        ClientPlayNetworking.send(event.apply(client.player.getUUID()));controlSent=true;lastControlSend=System.currentTimeMillis();
    }
    private void cancelControl() {
        var client=Minecraft.getInstance();
        if(controlSent&&client.player!=null&&ClientPlayNetworking.canSend(StreamProtocol.Control.TYPE))
            ClientPlayNetworking.send(StreamProtocol.Control.cancel(owner,session,client.player.getUUID()));
        controlSent=keyboardCaptured=hoverDirty=false;pressedButtons.clear();lastHoverX=lastHoverY=Integer.MIN_VALUE;lastControlSend=nextHoverSend=0;
    }
    int controlY(int y){return y-controlTop;}
    @Override public void hover(int x,int y){
        if(x==lastHoverX&&y==lastHoverY)return;
        lastHoverX=x;lastHoverY=y;hoverDirty=true;flushHover();
    }
    private void flushHover(){
        long now=System.nanoTime();
        if(!hoverDirty||now<nextHoverSend)return;
        hoverDirty=false;nextHoverSend=now+16_666_667L;
        send(controller->StreamProtocol.Control.pointer(owner,session,controller,StreamProtocol.Control.MOVE,lastHoverX,controlY(lastHoverY),0));
    }
    @Override public void mouseDown(int x,int y,int button){lastHoverX=x;lastHoverY=y;hoverDirty=false;pressedButtons.add(button);send(controller->StreamProtocol.Control.pointer(owner,session,controller,StreamProtocol.Control.MOUSE_DOWN,x,controlY(y),button));}
    @Override public void mouseUp(int x,int y,int button){lastHoverX=x;lastHoverY=y;hoverDirty=false;send(controller->StreamProtocol.Control.pointer(owner,session,controller,StreamProtocol.Control.MOUSE_UP,x,controlY(y),button));pressedButtons.remove(button);}
    @Override public void scroll(int x,int y,double amount){send(controller->StreamProtocol.Control.scroll(owner,session,controller,x,controlY(y),amount));}
    @Override public void key(int key,int scan,int action,int modifiers){send(controller->StreamProtocol.Control.key(owner,session,controller,key,scan,action,modifiers));}
    @Override public void character(char character,int modifiers){send(controller->StreamProtocol.Control.character(owner,session,controller,character,modifiers));}
    @Override public void keyboardStarted(){keyboardCaptured=true;}
    @Override public void keyboardStopped(){keyboardCaptured=false;cancelControl();}
    @Override public void tick(Minecraft client) {
        super.tick(client);
        if(isOpen()&&decoder!=null){
            decoder.tick();long now=System.currentTimeMillis();
            flushHover();
            if(remoteControl&&(keyboardCaptured||!pressedButtons.isEmpty())&&now-lastControlSend>=1_000)
                send(controller->StreamProtocol.Control.keepalive(owner,session,controller));
            if(now>=nextHealth){nextHealth=now+10_000;WinLandCraftClient.LOGGER.info("Stream receiver network health for {}: {}",StreamClient.shortId(owner),health());}
        }
    }
    String health(){
        var parts=assembly.stats();var queue=decoder==null?null:decoder.incoming.stats();
        return "media="+mediaPackets+" (video="+videoPackets+", audio="+audioPackets+"), bytes="+mediaBytes+", sequenceGaps="+sequenceGaps+
                ", parts="+parts.parts()+", completed="+parts.completed()+", invalid="+parts.invalid()+", replayed="+parts.replayed()+", abandoned="+parts.abandoned()+
                (queue==null?"":", decoderQueue="+queue.queued()+", accepted="+queue.accepted()+", dropped="+queue.dropped()+", keyWait="+queue.rejectedForKey()+", resets="+queue.resets());
    }
    /** Uploads the newest decoded frame. Render thread only. */
    private boolean upload() {
        var frame=decoder==null?null:decoder.current;
        if(frame==null||frame.sequence()==uploadedSequence)return uploadedSequence>=0;
        int size=frame.rgba().length;
        if(staging==null||staging.capacity()!=size){if(staging!=null)MemoryUtil.memFree(staging);staging=MemoryUtil.memAlloc(size);}
        staging.clear();staging.put(frame.rgba()).flip();
        int binding=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),pbo=GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int alignment=GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        try{
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,0);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT,1);
            if(textureId==0){
                textureId=GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D,textureId);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
            }else GL11.glBindTexture(GL11.GL_TEXTURE_2D,textureId);
            if(uploadedWidth!=frame.width()||uploadedHeight!=frame.height())
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,frame.width(),frame.height(),0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,frame.width(),frame.height(),GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,staging);
            uploadedWidth=frame.width();uploadedHeight=frame.height();uploadedSequence=frame.sequence();
            lastVideoFrame=System.currentTimeMillis();
            return true;
        }finally{
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,binding);GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,pbo);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT,alignment);
        }
    }
    @Override public void render(net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext context) {
        try(var surface=surface(context)) {
            if(surface==null||!surface.frontFacing())return;
            var canvas=surface.canvas();
            boolean image=decoder!=null&&upload()&&textureId>0;
            if(image)canvas.texture(texture,pixelWidth(),pixelHeight());
            else {
                canvas.rect(0,0,pixelWidth(),pixelHeight(),.1f,0xFF21182D);
                String phase=decoder==null?"starting":decoder.phase;
                canvas.text("Connecting to shared app... ("+phase+")",20,40,-1,2);
            }
            if(decoder!=null&&!decoder.error.isEmpty())canvas.text(Minecraft.getInstance().font.plainSubstrByWidth(decoder.error,500),12,40,0xFFFF8888,1.5f);
            else if(image&&System.currentTimeMillis()-lastVideoFrame>5000) {
                canvas.rect(0,0,Math.min(430,pixelWidth()),24,.4f,0xEE392641);
                canvas.text("Stream paused / waiting for host",8,8,0xFFFFCC88);
            }
            if(decoder!=null&&image&&!"running".equals(decoder.audioState)&&audioPackets>0){
                canvas.rect(0,pixelHeight()-26,Math.min(560,pixelWidth()),26,.4f,0xEE392641);
                canvas.text("Audio state: "+decoder.audioState+" (see latest.log)",8,pixelHeight()-18,0xFFFFCC88);
            }
        }
    }
    @Override public void close() {
        cancelControl();
        super.close();
        if(decoder!=null){decoder.close();decoder=null;}
        Minecraft.getInstance().getTextureManager().release(texture);
        int doomed=textureId;textureId=0;uploadedWidth=uploadedHeight=0;uploadedSequence=-1;
        if(staging!=null){MemoryUtil.memFree(staging);staging=null;}
        if(doomed>0) {
            if(RenderSystem.isOnRenderThread())GL11.glDeleteTextures(doomed);
            else RenderSystem.recordRenderCall(()->GL11.glDeleteTextures(doomed));
        }
    }
}
