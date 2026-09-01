package dev.winlandcraft;

import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** The server only validates ownership and forwards bounded bytes; it never decodes a frame. */
public final class StreamRelay {
    private static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger("winlandcraft/stream-relay");
    private static final Map<UUID,Session> streams=new HashMap<>();
    private static final Map<UUID,net.minecraft.resources.ResourceLocation> viewers=new HashMap<>();
    static final class ControlBudget {
        double tokens=240;
        long updated,lastSeen;
        ControlBudget(long now){updated=lastSeen=now;}
        boolean allow(long now){
            tokens=Math.min(240,tokens+Math.max(0,now-updated)*.12);updated=lastSeen=now;
            if(tokens<1)return false;
            tokens--;return true;
        }
    }
    static final class Session {
        StreamProtocol.State state;
        final StreamProtocol.Assembly assembly=new StreamProtocol.Assembly();
        final Map<UUID,ControlBudget> controllers=new HashMap<>();
        long lastState,budgetAt,nextReport,receivedUnits,acceptedUnits,acceptedBytes,videoUnits,audioUnits,invalidUnits,budgetDrops;
        long acceptedControls,invalidControls,controlDrops;
        double byteBudget=4_000_000,packetBudget=300;
        Session(StreamProtocol.State state,long now){this.state=state;lastState=budgetAt=now;}
        boolean allow(int bytes,long now) {
            double elapsed=Math.max(0,now-budgetAt)/1000.0;budgetAt=now;
            byteBudget=Math.min(4_000_000,byteBudget+elapsed*2_000_000);
            packetBudget=Math.min(300,packetBudget+elapsed*200);
            if(byteBudget<bytes||packetBudget<1)return false;
            byteBudget-=bytes;packetBudget--;return true;
        }
    }
    static boolean owns(UUID sender,StreamProtocol.State state,net.minecraft.resources.ResourceLocation dimension) {
        return sender.equals(state.owner())&&dimension.equals(state.dimension())&&state.valid();
    }
    static boolean mayControl(UUID sender,StreamProtocol.Control control,StreamProtocol.State state,
                              net.minecraft.resources.ResourceLocation dimension) {
        return !sender.equals(state.owner())&&sender.equals(control.controller())&&dimension.equals(state.dimension())
                &&(state.remoteControl()||control.event()==StreamProtocol.Control.CANCEL)&&control.valid(state);
    }
    public static void register() {
        PayloadTypeRegistry.playC2S().register(StreamProtocol.State.TYPE,StreamProtocol.State.CODEC);
        PayloadTypeRegistry.playS2C().register(StreamProtocol.State.TYPE,StreamProtocol.State.CODEC);
        PayloadTypeRegistry.playC2S().register(StreamProtocol.Frame.TYPE,StreamProtocol.Frame.CODEC);
        PayloadTypeRegistry.playS2C().register(StreamProtocol.Frame.TYPE,StreamProtocol.Frame.CODEC);
        PayloadTypeRegistry.playC2S().register(StreamProtocol.Stop.TYPE,StreamProtocol.Stop.CODEC);
        PayloadTypeRegistry.playS2C().register(StreamProtocol.Stop.TYPE,StreamProtocol.Stop.CODEC);
        PayloadTypeRegistry.playC2S().register(StreamProtocol.Control.TYPE,StreamProtocol.Control.CODEC);
        PayloadTypeRegistry.playS2C().register(StreamProtocol.Control.TYPE,StreamProtocol.Control.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(StreamProtocol.State.TYPE,(p,c)->{
            var player=c.player();long now=System.currentTimeMillis();
            if(!player.isAlive()||!owns(player.getUUID(),p,player.serverLevel().dimension().location()))return;
            var old=streams.get(p.owner());
            if(old!=null&&now-old.lastState<40)return;
            if(old==null&&streams.size()>=StreamProtocol.MAX_STREAMS) {
                ServerPlayNetworking.send(player,new StreamProtocol.Stop(p.owner(),p.session()));
                player.displayClientMessage(net.minecraft.network.chat.Component.literal("The relay test supports up to 16 simultaneous streams."),false);
                return;
            }
            if(old!=null&&!old.state.session().equals(p.session()))stop(c.server(),p.owner(),"session replaced");
            boolean created=!streams.containsKey(p.owner());
            var session=streams.computeIfAbsent(p.owner(),ignored->new Session(p,now));
            if(session.state.remoteControl()&&!p.remoteControl())cancelControllers(c.server(),session);
            session.state=p;session.lastState=now;
            if(created)LOGGER.info("Started stream relay for {} ({}, session {}, dimension {})",player.getGameProfile().getName(),shortId(p.owner()),shortId(p.session()),p.dimension());
            for(var viewer:c.server().getPlayerList().getPlayers())if(watches(viewer,p))ServerPlayNetworking.send(viewer,p);
        });
        ServerPlayNetworking.registerGlobalReceiver(StreamProtocol.Frame.TYPE,(p,c)->{
            var s=streams.get(c.player().getUUID());long now=System.currentTimeMillis();
            if(s==null)return;
            if(!p.owner().equals(c.player().getUUID())||!p.session().equals(s.state.session())||!p.valid()
                    ||!c.player().serverLevel().dimension().location().equals(s.state.dimension())){s.invalidUnits++;return;}
            byte[] bytes=s.assembly.accept(p,now);
            if(bytes==null)return;
            s.receivedUnits++;
            var media=StreamMedia.header(bytes);if(media==null){s.invalidUnits++;return;}
            if(!s.allow(bytes.length,now)){s.budgetDrops++;return;}
            s.acceptedUnits++;s.acceptedBytes+=bytes.length;if(media.kind()==StreamMedia.VIDEO)s.videoUnits++;else s.audioUnits++;
            var parts=StreamProtocol.split(p.owner(),p.session(),p.sequence(),bytes);
            for(var viewer:c.server().getPlayerList().getPlayers())if(watches(viewer,s.state))for(var part:parts)ServerPlayNetworking.send(viewer,part);
        });
        ServerPlayNetworking.registerGlobalReceiver(StreamProtocol.Stop.TYPE,(p,c)->{
            var s=streams.get(c.player().getUUID());
            if(s!=null&&p.owner().equals(c.player().getUUID())&&p.session().equals(s.state.session()))stop(c.server(),p.owner(),"sender requested");
        });
        ServerPlayNetworking.registerGlobalReceiver(StreamProtocol.Control.TYPE,(p,c)->{
            var player=c.player();var s=streams.get(p.owner());long now=System.currentTimeMillis();
            if(s==null)return;
            if(!player.isAlive()||player.isSpectator()||!mayControl(player.getUUID(),p,s.state,player.serverLevel().dimension().location())){
                s.invalidControls++;return;
            }
            if(p.event()==StreamProtocol.Control.CANCEL){cancelController(c.server(),s,player.getUUID(),true);return;}
            var budget=s.controllers.computeIfAbsent(player.getUUID(),ignored->new ControlBudget(now));
            if(!budget.allow(now)){s.controlDrops++;return;}
            if(p.event()==StreamProtocol.Control.KEEPALIVE){s.acceptedControls++;return;}
            var owner=c.server().getPlayerList().getPlayer(p.owner());
            if(owner==null||!ServerPlayNetworking.canSend(owner,StreamProtocol.Control.TYPE)){s.controlDrops++;return;}
            ServerPlayNetworking.send(owner,p);s.acceptedControls++;
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{
            stop(server,handler.player.getUUID(),"owner disconnected");viewers.remove(handler.player.getUUID());
            for(var session:streams.values())cancelController(server,session,handler.player.getUUID(),false);
        });
        ServerTickEvents.END_SERVER_TICK.register(server->{
            long now=System.currentTimeMillis();
            for(var entry:List.copyOf(streams.entrySet())) {
                var player=server.getPlayerList().getPlayer(entry.getKey());var s=entry.getValue();
                if(player==null)stop(server,entry.getKey(),"owner missing");
                else if(!player.isAlive())stop(server,entry.getKey(),"owner not alive");
                else if(!player.serverLevel().dimension().location().equals(s.state.dimension()))stop(server,entry.getKey(),"owner changed dimension");
                else if(now-s.lastState>10_000)stop(server,entry.getKey(),"state heartbeat timed out");
                else if(now>=s.nextReport){
                    s.nextReport=now+10_000;var parts=s.assembly.stats();
                    LOGGER.info("Stream relay health for {} session {}: received={}, accepted={} (video={}, audio={}), bytes={}, invalid={}, budgetDrops={}, controls={} accepted/{} invalid/{} dropped, parts={}, completed={}, replayed={}, abandoned={}",
                            shortId(entry.getKey()),shortId(s.state.session()),s.receivedUnits,s.acceptedUnits,s.videoUnits,s.audioUnits,s.acceptedBytes,
                            s.invalidUnits,s.budgetDrops,s.acceptedControls,s.invalidControls,s.controlDrops,parts.parts(),parts.completed(),parts.replayed(),parts.abandoned());
                }
                if(streams.containsKey(entry.getKey()))for(var controller:List.copyOf(s.controllers.entrySet())) {
                    var viewer=server.getPlayerList().getPlayer(controller.getKey());
                    if(now-controller.getValue().lastSeen>3_000||viewer==null||!viewer.isAlive()||viewer.isSpectator()
                            ||!viewer.serverLevel().dimension().location().equals(s.state.dimension()))
                        cancelController(server,s,controller.getKey(),false);
                }
            }
            for(var player:server.getPlayerList().getPlayers()) {
                var dim=player.serverLevel().dimension().location();
                if(dim.equals(viewers.put(player.getUUID(),dim)))continue;
                for(var s:streams.values())if(watches(player,s.state)) {
                    ServerPlayNetworking.send(player,s.state);
                    // New viewers wait for the next periodic keyframe instead of receiving an undecodable delta frame.
                }
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{streams.clear();viewers.clear();});
    }
    private static boolean watches(ServerPlayer player,StreamProtocol.State state) {
        return !player.getUUID().equals(state.owner())&&player.serverLevel().dimension().location().equals(state.dimension())
                &&ServerPlayNetworking.canSend(player,StreamProtocol.State.TYPE)&&ServerPlayNetworking.canSend(player,StreamProtocol.Frame.TYPE);
    }
    private static void stop(MinecraftServer server,UUID owner,String reason) {
        var removed=streams.remove(owner);if(removed==null)return;
        cancelControllers(server,removed);
        var parts=removed.assembly.stats();
        LOGGER.info("Stopped stream relay for {} session {} ({}): accepted={} media / {} bytes, invalid={}, budgetDrops={}, incompleteOrExpired={}",
                shortId(owner),shortId(removed.state.session()),reason,removed.acceptedUnits,removed.acceptedBytes,removed.invalidUnits,removed.budgetDrops,parts.abandoned());
        var packet=new StreamProtocol.Stop(owner,removed.state.session());
        for(var player:server.getPlayerList().getPlayers())if(ServerPlayNetworking.canSend(player,StreamProtocol.Stop.TYPE))ServerPlayNetworking.send(player,packet);
    }
    private static void cancelController(MinecraftServer server,Session session,UUID controller,boolean forwardIfUnknown) {
        if(session.controllers.remove(controller)==null&&!forwardIfUnknown)return;
        var owner=server.getPlayerList().getPlayer(session.state.owner());
        if(owner!=null&&ServerPlayNetworking.canSend(owner,StreamProtocol.Control.TYPE))
            ServerPlayNetworking.send(owner,StreamProtocol.Control.cancel(session.state.owner(),session.state.session(),controller));
    }
    private static void cancelControllers(MinecraftServer server,Session session) {
        for(var controller:List.copyOf(session.controllers.keySet()))cancelController(server,session,controller,false);
    }
    private static String shortId(UUID id){return id.toString().substring(0,8);}
}
