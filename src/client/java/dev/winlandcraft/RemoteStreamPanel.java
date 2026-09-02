package dev.winlandcraft;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** Read-only local WebCodecs playback surface. It never loads the owner's website. */
final class RemoteStreamPanel extends WorldPanel {
    final java.util.UUID owner,session;
    final StreamProtocol.Assembly assembly=new StreamProtocol.Assembly();
    private final ResourceLocation texture;
    MediaBridge.Endpoint decoder;
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
    void start(MediaBridge.Endpoint endpoint) {
        decoder=endpoint;
        Minecraft.getInstance().getTextureManager().register(texture,new AbstractTexture(){
            @Override public int getId(){return decoder==null||decoder.browser==null?0:decoder.browser.getRenderer().getTextureID();}
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
    @Override public void render(net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext context) {
        try(var surface=surface(context)) {
            if(surface==null||!surface.frontFacing())return;
            var canvas=surface.canvas();
            boolean image=decoder!=null&&decoder.browser!=null&&decoder.renderedVideo&&decoder.browser.getRenderer().getTextureID()>0;
            if(image)canvas.texture(texture,pixelWidth(),pixelHeight());
            else {
                canvas.rect(0,0,pixelWidth(),pixelHeight(),.1f,0xFF21182D);
                String phase=decoder==null?"starting":decoder.phase;
                canvas.text("Connecting to shared browser... ("+phase+")",20,40,-1,2);
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
        if(decoder!=null){Minecraft.getInstance().getTextureManager().release(texture);decoder.close();decoder=null;}
    }
}
