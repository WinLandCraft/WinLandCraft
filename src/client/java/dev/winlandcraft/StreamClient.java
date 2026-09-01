package dev.winlandcraft;

import java.util.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Owner-side capture and timestamped H.264/VP9/Opus relay. Chromium handles media codecs. */
final class StreamClient {
    private final AppWindows apps;
    private final StreamCapture capture=new StreamCapture();
    private final Map<UUID,RemoteStreamPanel> remote=new HashMap<>();
    private MediaBridge bridge;
    private MediaBridge.Endpoint encoder;
    private StreamProtocol.State published;
    private long lastState,nextFrame,sequence;
    private long sentUnits,sentParts,sentBytes,sentVideo,sentAudio,nextSenderHealth;
    StreamClient(AppWindows apps){this.apps=apps;}
    void register() {
        ClientPlayNetworking.registerGlobalReceiver(StreamProtocol.State.TYPE,(p,c)->receive(p));
        ClientPlayNetworking.registerGlobalReceiver(StreamProtocol.Frame.TYPE,(p,c)->{
            var panel=remote.get(p.owner());
            if(panel==null||!panel.isOpen()||panel.decoder==null||!panel.session.equals(p.session()))return;
            byte[] bytes=panel.assembly.accept(p,System.currentTimeMillis());
            if(bytes!=null) {
                if(panel.lastSequence>=0&&p.sequence()!=panel.lastSequence+1){panel.sequenceGaps++;panel.decoder.incoming.clear();}
                panel.lastSequence=p.sequence();panel.receive(bytes);
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(StreamProtocol.Stop.TYPE,(p,c)->{
            if(published!=null&&published.owner().equals(p.owner())&&published.session().equals(p.session())) {
                published=null;fail("The server ended this browser stream.");
            }
            var panel=remote.get(p.owner());
            if(panel!=null&&panel.session.equals(p.session()))remove(panel);
        });
    }
    private void receive(StreamProtocol.State state) {
        var client=Minecraft.getInstance();
        if(!state.valid()||client.level==null||client.player==null||state.owner().equals(client.player.getUUID())
                ||!client.level.dimension().location().equals(state.dimension()))return;
        var panel=remote.get(state.owner());
        if(panel!=null&&(!panel.isOpen()||!panel.session.equals(state.session())||panel.level!=client.level)){remove(panel);panel=null;}
        if(panel==null) {
            if(remote.size()>=StreamProtocol.MAX_STREAMS)return;
            try {
                panel=new RemoteStreamPanel(state);panel.start(media().create(false,"receiver owner="+shortId(state.owner())));remote.put(state.owner(),panel);apps.windows.add(panel);
                WinLandCraftClient.LOGGER.info("Started receiving browser stream from {} (session {})",shortId(state.owner()),shortId(state.session()));
            }catch(Exception|LinkageError failure){if(panel!=null)panel.close();WinLandCraftClient.LOGGER.error("Could not start stream playback",failure);return;}
        }
        panel.apply(state);
    }
    private MediaBridge media()throws java.io.IOException {if(bridge==null)bridge=new MediaBridge();return bridge;}
    void tick(Minecraft client) {
        long now=System.currentTimeMillis();
        for(var panel:List.copyOf(remote.values()))if(!panel.isOpen()||panel.level!=client.level||now-panel.lastState>10_000)remove(panel);
        var panel=apps.streamBrowser;
        if(client.player==null||client.level==null||!panel.isOpen()||panel.broadcastSession==null||!ClientPlayNetworking.canSend(StreamProtocol.State.TYPE)) {stopPublishing();return;}
        if(published!=null&&!published.session().equals(panel.broadcastSession))stopPublishing();
        if(encoder==null)try {
            encoder=media().create(true,"sender");panel.encoder=encoder;panel.streamStatus="Starting video + Opus...";
            sentUnits=sentParts=sentBytes=sentVideo=sentAudio=0;sequence=nextFrame=nextSenderHealth=0;
        }catch(Exception|LinkageError failure){WinLandCraftClient.LOGGER.error("Could not start stream codecs",failure);fail("Could not start browser streaming. See latest.log.");return;}
        encoder.quality=StreamQuality.current();encoder.tick();
        panel.streamStatus=encoder.error.isEmpty()?(encoder.ready?"Live: "+encoder.videoCodec+" ("+encoder.videoAcceleration+") + Opus":"Starting video + Opus..."):encoder.error;
        if(!encoder.error.isEmpty()) {
            String reason=encoder.error;WinLandCraftClient.LOGGER.warn("Stream codec error: {}",reason);
            client.player.displayClientMessage(Component.literal("Stream codec error: "+reason),false);
            stopPublishing();panel.streamStatus=reason;panel.broadcastSession=null;return;
        }
        if(published!=null)for(int i=0;i<128;i++) {
            byte[] packet=encoder.encoded.poll();if(packet==null)break;
            if((packet[4]&255)==StreamMedia.VIDEO)sentVideo++;else sentAudio++;
            var parts=StreamProtocol.split(published.owner(),published.session(),sequence++,packet);
            for(var part:parts){ClientPlayNetworking.send(part);sentParts++;sentBytes+=part.bytes().length;}
            sentUnits++;
        }
        if(published!=null&&now>=nextSenderHealth) {
            nextSenderHealth=now+10_000;
            WinLandCraftClient.LOGGER.info("Stream sender network health: session={}, media={} (video={}, audio={}), parts={}, payloadBytes={}, sequence={}",
                    shortId(published.session()),sentUnits,sentVideo,sentAudio,sentParts,sentBytes,sequence);
        }
        if(now-lastState<100)return;
        var state=snapshot(panel,client.player.getUUID(),panel.broadcastSession);
        if(!state.valid()) {fail("Shared window exceeds the relay test limits.");return;}
        if(state.equals(published)&&now-lastState<1000)return;
        ClientPlayNetworking.send(state);published=state;lastState=now;
    }
    static StreamProtocol.State snapshot(BrowserPanel panel,UUID owner,UUID session) {
        Vec3 center=panel.position;var rotation=new Quaternionf(panel.orientation);
        float amount=0;int facing=1;
        if(panel.curve!=null) {
            var curve=panel.curve;var layout=curve.layout.get(panel);
            center=curve.point(layout.x(),layout.y(),0);rotation=new Quaternionf(curve.rotation);amount=curve.amount;facing=curve.facing;
        }
        float title=panel.titlebarHeight()*panel.worldHeight()/panel.pixelHeight();
        var up=new Vector3f(0,title/2,0).rotate(rotation);center=center.add(up.x,up.y,up.z);
        return new StreamProtocol.State(owner,session,panel.level==null?net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"):panel.level.dimension().location(),
                center.x,center.y,center.z,rotation.x,rotation.y,rotation.z,rotation.w,panel.worldWidth(),panel.worldHeight()+title,
                panel.pixelWidth(),panel.pixelHeight()+panel.titlebarHeight(),amount,facing);
    }
    void renderCapture() {
        var client=Minecraft.getInstance();var panel=apps.streamBrowser;
        if(published==null||encoder==null||!encoder.wantsVideo()||!panel.isOpen()||!published.session().equals(panel.broadcastSession)||client.isPaused()||System.nanoTime()<nextFrame)return;
        nextFrame=System.nanoTime()+1_000_000_000L/encoder.quality.fps();
        try {
            encoder.video(capture.capture(panel,encoder.quality));
        } catch(RuntimeException|LinkageError error) {
            WinLandCraftClient.LOGGER.error("Stream capture failed",error);fail("Stream capture failed; see latest.log.");
        }
    }
    private void fail(String message) {
        apps.streamBrowser.close();stopPublishing();
        var player=Minecraft.getInstance().player;if(player!=null)player.displayClientMessage(Component.literal(message),false);
    }
    private void stopPublishing() {
        if(published==null&&encoder==null)return;
        if(published!=null&&ClientPlayNetworking.canSend(StreamProtocol.Stop.TYPE))ClientPlayNetworking.send(new StreamProtocol.Stop(published.owner(),published.session()));
        published=null;lastState=nextFrame=sequence=0;capture.close();
        apps.streamBrowser.encoder=null;
        if(encoder!=null){encoder.close();encoder=null;}
    }
    private void remove(RemoteStreamPanel panel){
        WinLandCraftClient.LOGGER.info("Stopped receiving browser stream from {}: {}",shortId(panel.owner),panel.health());
        remote.remove(panel.owner);apps.windows.remove(panel);panel.close();
    }
    static String shortId(UUID id){return id.toString().substring(0,8);}
    void clear(){stopPublishing();for(var panel:List.copyOf(remote.values()))remove(panel);if(bridge!=null){bridge.close();bridge=null;}}
    void shutdown(){clear();}
}
