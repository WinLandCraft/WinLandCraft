package dev.winlandcraft;

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
    private int pixelsWide=1280,pixelsHigh=752;
    long lastState;
    long lastSequence=-1;
    long sequenceGaps,mediaPackets,videoPackets,audioPackets,mediaBytes,lastVideoFrame,nextHealth;
    RemoteStreamPanel(StreamProtocol.State state) {
        super(3.2f,1.88f);owner=state.owner();session=state.session();
        texture=ResourceLocation.fromNamespaceAndPath("winlandcraft","stream/"+owner+"/"+session);
    }
    @Override public boolean canInteract(){return false;}
    @Override public boolean canResize(){return false;}
    @Override public boolean canGroup(){return false;}
    @Override public int pixelWidth(){return pixelsWide;}
    @Override public int pixelHeight(){return pixelsHigh;}
    @Override public void resize(float width,float height){scaleTo(width,height);}
    void apply(StreamProtocol.State state) {
        level=Minecraft.getInstance().level;
        place(state);lastState=System.currentTimeMillis();
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
    @Override public void tick(Minecraft client) {
        super.tick(client);
        if(isOpen()&&decoder!=null){
            decoder.tick();long now=System.currentTimeMillis();
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
        try(var canvas=canvas(context)) {
            if(canvas==null)return;
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
        super.close();
        if(decoder!=null){Minecraft.getInstance().getTextureManager().release(texture);decoder.close();decoder=null;}
    }
}
