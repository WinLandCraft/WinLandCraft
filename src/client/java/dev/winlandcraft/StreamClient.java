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
    private static final int MAX_MEDIA_PER_RENDER=32;
    private static final long RENDER_DRAIN_FALLBACK_NANOS=100_000_000L;
    private final AppWindows apps;
    private WorldPanel source;
    private final StreamCapture capture=new StreamCapture();
    private final Map<UUID,RemoteStreamPanel> remote=new HashMap<>();
    private MediaBridge bridge;
    private MediaBridge.Endpoint encoder;
    private StreamProtocol.State published;
    private boolean demand;
    private int codecMode;
    private String reportedCodecError="";
    private long lastState,nextFrame,lastRenderDrain,sequence;
    private long sentUnits,sentParts,sentBytes,sentVideo,sentAudio,nextSenderHealth;
    StreamClient(AppWindows apps){this.apps=apps;apps.streams=this;}
    boolean start(WorldPanel panel) {
        var client=Minecraft.getInstance();
        if(client.player==null)return false;
        // Multiplayer app streaming is temporarily disabled while the media
        // pipeline is reimplemented without the custom Chromium runtime.
        client.player.displayClientMessage(Component.literal("Multiplayer streaming is temporarily disabled in this build."),false);
        return false;
    }
    void stop(WorldPanel panel) {
        if(source!=panel)return;
        stopPublishing();panel.broadcastSession=null;panel.encoder=null;
        panel.clearRemoteControls();
        source=null;
    }
    void register() {
        ClientPlayNetworking.registerGlobalReceiver(StreamProtocol.State.TYPE,(p,c)->receive(p));
        ClientPlayNetworking.registerGlobalReceiver(StreamProtocol.Demand.TYPE,(p,c)->{
            if(p.valid(published))demand=p.active();
        });
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
        ClientPlayNetworking.registerGlobalReceiver(StreamProtocol.Control.TYPE,(p,c)->{
            var client=Minecraft.getInstance();var panel=source;
            if(panel==null||!panel.remoteControlAllowed()||client.player==null||!p.owner().equals(client.player.getUUID())||published==null
                    ||!published.session().equals(p.session())||!p.valid(published))return;
            if(p.event()==StreamProtocol.Control.CANCEL||ModSettings.streamRemoteControl&&published.remoteControl())panel.remoteControl(p);
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
        var panel=source;
        if(panel==null||client.player==null||client.level==null||!panel.isOpen()||panel.broadcastSession==null||!ClientPlayNetworking.canSend(StreamProtocol.State.TYPE)) {if(panel!=null)stop(panel);else stopPublishing();return;}
        // A fresh session makes existing viewers recreate their decoder after a codec switch.
        if(codecMode!=ModSettings.streamCodecMode){codecMode=ModSettings.streamCodecMode;panel.broadcastSession=UUID.randomUUID();reportedCodecError="";}
        if(published!=null&&!published.session().equals(panel.broadcastSession))stopPublishing();
        if(!publishState(panel,client,now))return;
        if(!demand) {
            stopEncoder();panel.streamStatus="Waiting for viewers...";return;
        }
        if(encoder==null)try {
            encoder=media().create(true,"sender");panel.encoder=encoder;panel.streamStatus="Starting video + Opus...";
            sentUnits=sentParts=sentBytes=sentVideo=sentAudio=0;nextFrame=nextSenderHealth=0;
        }catch(Exception|LinkageError failure){WinLandCraftClient.LOGGER.error("Could not start stream codecs",failure);fail("Could not start browser streaming. See latest.log.");return;}
        encoder.quality=StreamQuality.current();encoder.tick();
        panel.streamStatus=encoder.error.isEmpty()?(encoder.ready?"Live: "+encoder.videoCodec+" ("+encoder.videoAcceleration+") + Opus":"Starting video + Opus..."):encoder.error;
        if(!encoder.error.isEmpty()) {
            String reason=encoder.error;
            if(!reason.equals(reportedCodecError)){reportedCodecError=reason;WinLandCraftClient.LOGGER.warn("Stream codec error: {}",reason);
                client.player.displayClientMessage(Component.literal("Stream codec error: "+reason+". Choose another codec in the pill."),false);}
            return;
        }
        if(System.nanoTime()-lastRenderDrain>=RENDER_DRAIN_FALLBACK_NANOS)drainEncoded();
        if(published!=null&&now>=nextSenderHealth) {
            nextSenderHealth=now+10_000;
            WinLandCraftClient.LOGGER.info("Stream sender network health: session={}, media={} (video={}, audio={}), parts={}, payloadBytes={}, sequence={}",
                    shortId(published.session()),sentUnits,sentVideo,sentAudio,sentParts,sentBytes,sequence);
        }
    }
    private void drainEncoded() {
        if(published==null||encoder==null)return;
        for(int i=0;i<MAX_MEDIA_PER_RENDER;i++) {
            byte[] packet=encoder.encoded.poll();if(packet==null)break;
            if((packet[4]&255)==StreamMedia.VIDEO)sentVideo++;else sentAudio++;
            var parts=StreamProtocol.split(published.owner(),published.session(),sequence++,packet);
            for(var part:parts){ClientPlayNetworking.send(part);sentParts++;sentBytes+=part.bytes().length;}
            sentUnits++;
        }
    }
    private boolean publishState(WorldPanel panel,Minecraft client,long now) {
        if(now-lastState<100)return true;
        var state=snapshot(panel,client.player.getUUID(),panel.broadcastSession);
        if(!state.valid()) {fail("Shared window exceeds the relay test limits.");return false;}
        if(state.equals(published)&&now-lastState<1000)return true;
        ClientPlayNetworking.send(state);published=state;lastState=now;
        return true;
    }
    static StreamProtocol.State snapshot(WorldPanel panel,UUID owner,UUID session) {
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
                panel.pixelWidth(),panel.pixelHeight()+panel.titlebarHeight(),panel.titlebarHeight(),amount,facing,panel.remoteControlAllowed()&&ModSettings.streamRemoteControl);
    }
    void renderCapture() {
        var client=Minecraft.getInstance();var panel=source;
        long now=System.nanoTime();
        if(encoder!=null){drainEncoded();lastRenderDrain=now;}
        if(panel==null||published==null||encoder==null||!encoder.wantsVideo()||!panel.isOpen()||!published.session().equals(panel.broadcastSession)||client.isPaused()||now<nextFrame)return;
        nextFrame=advanceFrameDeadline(nextFrame,now,encoder.quality.fps());
        try {
            var pixels=capture.capture(panel,encoder.quality,encoder.elapsedTimeUs());
            if(pixels!=null)encoder.video(pixels);
        } catch(RuntimeException|LinkageError error) {
            WinLandCraftClient.LOGGER.error("Stream capture failed",error);fail("Stream capture failed; see latest.log.");
        }
    }
    static long advanceFrameDeadline(long deadline,long now,int fps) {
        long interval=Math.max(1,1_000_000_000L/Math.max(1,fps));
        if(deadline<=0||deadline>now+interval)return now+interval;
        if(now<deadline)return deadline;
        return deadline+(Math.floorDiv(now-deadline,interval)+1)*interval;
    }
    private void fail(String message) {
        if(source!=null)stop(source);else stopPublishing();
        var player=Minecraft.getInstance().player;if(player!=null)player.displayClientMessage(Component.literal(message),false);
    }
    private void stopPublishing() {
        if(published==null&&encoder==null){demand=false;return;}
        if(published!=null&&ClientPlayNetworking.canSend(StreamProtocol.Stop.TYPE))ClientPlayNetworking.send(new StreamProtocol.Stop(published.owner(),published.session()));
        published=null;demand=false;lastState=sequence=0;stopEncoder();
    }
    private void stopEncoder() {
        nextFrame=lastRenderDrain=0;capture.close();
        if(source!=null)source.encoder=null;
        if(encoder!=null){encoder.close();encoder=null;}
    }
    private void remove(RemoteStreamPanel panel){
        WinLandCraftClient.LOGGER.info("Stopped receiving browser stream from {}: {}",shortId(panel.owner),panel.health());
        remote.remove(panel.owner);apps.windows.remove(panel);panel.close();
    }
    static String shortId(UUID id){return id.toString().substring(0,8);}
    void clear(){if(source!=null)stop(source);else stopPublishing();for(var panel:List.copyOf(remote.values()))remove(panel);if(bridge!=null){bridge.close();bridge=null;}}
    void shutdown(){clear();}
}
