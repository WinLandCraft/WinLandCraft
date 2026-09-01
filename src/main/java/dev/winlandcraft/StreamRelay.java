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
    static final class Session {
        StreamProtocol.State state;
        final StreamProtocol.Assembly assembly=new StreamProtocol.Assembly();
        long lastState,budgetAt,nextReport,receivedUnits,acceptedUnits,acceptedBytes,videoUnits,audioUnits,invalidUnits,budgetDrops;
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
    public static void register() {
        PayloadTypeRegistry.playC2S().register(StreamProtocol.State.TYPE,StreamProtocol.State.CODEC);
        PayloadTypeRegistry.playS2C().register(StreamProtocol.State.TYPE,StreamProtocol.State.CODEC);
        PayloadTypeRegistry.playC2S().register(StreamProtocol.Frame.TYPE,StreamProtocol.Frame.CODEC);
        PayloadTypeRegistry.playS2C().register(StreamProtocol.Frame.TYPE,StreamProtocol.Frame.CODEC);
        PayloadTypeRegistry.playC2S().register(StreamProtocol.Stop.TYPE,StreamProtocol.Stop.CODEC);
        PayloadTypeRegistry.playS2C().register(StreamProtocol.Stop.TYPE,StreamProtocol.Stop.CODEC);
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
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{stop(server,handler.player.getUUID(),"owner disconnected");viewers.remove(handler.player.getUUID());});
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
                    LOGGER.info("Stream relay health for {} session {}: received={}, accepted={} (video={}, audio={}), bytes={}, invalid={}, budgetDrops={}, parts={}, completed={}, replayed={}, abandoned={}",
                            shortId(entry.getKey()),shortId(s.state.session()),s.receivedUnits,s.acceptedUnits,s.videoUnits,s.audioUnits,s.acceptedBytes,
                            s.invalidUnits,s.budgetDrops,parts.parts(),parts.completed(),parts.replayed(),parts.abandoned());
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
        var parts=removed.assembly.stats();
        LOGGER.info("Stopped stream relay for {} session {} ({}): accepted={} media / {} bytes, invalid={}, budgetDrops={}, incompleteOrExpired={}",
                shortId(owner),shortId(removed.state.session()),reason,removed.acceptedUnits,removed.acceptedBytes,removed.invalidUnits,removed.budgetDrops,parts.abandoned());
        var packet=new StreamProtocol.Stop(owner,removed.state.session());
        for(var player:server.getPlayerList().getPlayers())if(ServerPlayNetworking.canSend(player,StreamProtocol.Stop.TYPE))ServerPlayNetworking.send(player,packet);
    }
    private static String shortId(UUID id){return id.toString().substring(0,8);}
}
