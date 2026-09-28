package dev.winlandcraft;

import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** The server only validates ownership and forwards bounded bytes; it never decodes a frame. */
public final class StreamRelay {
    private static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger("winlandcraft/stream-relay");
    private static final Map<UUID,Session> streams=new HashMap<>();
    private static final Map<UUID,net.minecraft.resources.Identifier> viewers=new HashMap<>();
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
        boolean demand,demandKnown;
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
    static boolean owns(UUID sender,StreamProtocol.State state,net.minecraft.resources.Identifier dimension) {
        return sender.equals(state.owner())&&dimension.equals(state.dimension())&&state.valid();
    }
    static boolean mayControl(UUID sender,StreamProtocol.Control control,StreamProtocol.State state,
                              net.minecraft.resources.Identifier dimension) {
        return !sender.equals(state.owner())&&sender.equals(control.controller())&&dimension.equals(state.dimension())
                &&(state.remoteControl()||control.event()==StreamProtocol.Control.CANCEL)&&control.valid(state);
    }
    public static void register() {
    /** Called on the server thread by the payload dispatch mixin. */
    public static void receiveState(ServerPlayer player, StreamProtocol.State p) {
        var server = player.level().getServer();
        long now = System.currentTimeMillis();
        if (!player.isAlive() || !owns(player.getUUID(), p, player.level().dimension().location())) return;
        var old = streams.get(p.owner());
        if (old != null && now - old.lastState < 40) return;
        if (old == null && streams.size() >= StreamProtocol.MAX_STREAMS) {
            StreamNet.sendToPlayer(player, new StreamProtocol.Stop(p.owner(), p.session()));
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("The relay test supports up to 16 simultaneous streams."), false);
            return;
        }
        if (old != null && !old.state.session().equals(p.session())) stop(server, p.owner(), "session replaced");
        boolean created = !streams.containsKey(p.owner());
        var session = streams.computeIfAbsent(p.owner(), ignored -> new Session(p, now));
        if (session.state.remoteControl() && !p.remoteControl()) cancelControllers(server, session);
        session.state = p;
        session.lastState = now;
        if (created) LOGGER.info("Started stream relay for {} ({}, session {}, dimension {})", player.getGameProfile().name(), shortId(p.owner()), shortId(p.session()), p.dimension());
        for (var viewer : server.getPlayerList().getPlayers()) if (watches(viewer, p)) StreamNet.sendToPlayer(viewer, p);
        updateDemand(server, session);
    }

    /** Called on the server thread by the payload dispatch mixin. */
    public static void receiveFrame(ServerPlayer player, StreamProtocol.Frame p) {
        var server = player.level().getServer();
        var s = streams.get(player.getUUID());
        long now = System.currentTimeMillis();
        if (s == null) return;
        if (!p.owner().equals(player.getUUID()) || !p.session().equals(s.state.session()) || !p.valid()
                || !player.level().dimension().location().equals(s.state.dimension())) { s.invalidUnits++; return; }
        byte[] bytes = s.assembly.accept(p, now);
        if (bytes == null) return;
        s.receivedUnits++;
        var media = StreamMedia.header(bytes);
        if (media == null) { s.invalidUnits++; return; }
        if (!s.allow(bytes.length, now)) { s.budgetDrops++; return; }
        s.acceptedUnits++;
        s.acceptedBytes += bytes.length;
        if (media.kind() == StreamMedia.VIDEO) s.videoUnits++; else s.audioUnits++;
        var parts = StreamProtocol.split(p.owner(), p.session(), p.sequence(), bytes);
        for (var viewer : server.getPlayerList().getPlayers()) if (watches(viewer, s.state)) for (var part : parts) StreamNet.sendToPlayer(viewer, part);
    }

    /** Called on the server thread by the payload dispatch mixin. */
    public static void receiveStop(ServerPlayer player, StreamProtocol.Stop p) {
        var s = streams.get(player.getUUID());
        if (s != null && p.owner().equals(player.getUUID()) && p.session().equals(s.state.session()))
            stop(player.level().getServer(), p.owner(), "sender requested");
    }

    /** Called on the server thread by the payload dispatch mixin. */
    public static void receiveControl(ServerPlayer player, StreamProtocol.Control p) {
        var server = player.level().getServer();
        var s = streams.get(p.owner());
        long now = System.currentTimeMillis();
        if (s == null) return;
        if (!player.isAlive() || player.isSpectator() || !mayControl(player.getUUID(), p, s.state, player.level().dimension().location())) {
            s.invalidControls++;
            return;
        }
        if (p.event() == StreamProtocol.Control.CANCEL) { cancelController(server, s, player.getUUID(), true); return; }
        var budget = s.controllers.computeIfAbsent(player.getUUID(), ignored -> new ControlBudget(now));
        if (!budget.allow(now)) { s.controlDrops++; return; }
        if (p.event() == StreamProtocol.Control.KEEPALIVE) { s.acceptedControls++; return; }
        var owner = server.getPlayerList().getPlayer(p.owner());
        if (owner == null || !StreamNet.canSendToPlayer(owner)) { s.controlDrops++; return; }
        StreamNet.sendToPlayer(owner, p);
        s.acceptedControls++;
    }

    /** Called on the server thread by the join/leave mixin. */
    public static void onDisconnect(ServerPlayer player) {
        var server = player.level().getServer();
        stop(server, player.getUUID(), "owner disconnected");
        viewers.remove(player.getUUID());
        for (var session : streams.values()) cancelController(server, session, player.getUUID(), false);
    }
        ServerTickEvents.END_SERVER_TICK.register(server->{
            long now=System.currentTimeMillis();
            for(var entry:List.copyOf(streams.entrySet())) {
                var player=server.getPlayerList().getPlayer(entry.getKey());var s=entry.getValue();
                if(player==null)stop(server,entry.getKey(),"owner missing");
                else if(!player.isAlive())stop(server,entry.getKey(),"owner not alive");
                else if(!player.level().dimension().location().equals(s.state.dimension()))stop(server,entry.getKey(),"owner changed dimension");
                else if(now-s.lastState>10_000)stop(server,entry.getKey(),"state heartbeat timed out");
                else if(now>=s.nextReport){
                    s.nextReport=now+10_000;var parts=s.assembly.stats();
                    LOGGER.info("Stream relay health for {} session {}: received={}, accepted={} (video={}, audio={}), bytes={}, invalid={}, budgetDrops={}, controls={} accepted/{} invalid/{} dropped, parts={}, completed={}, replayed={}, abandoned={}",
                            shortId(entry.getKey()),shortId(s.state.session()),s.receivedUnits,s.acceptedUnits,s.videoUnits,s.audioUnits,s.acceptedBytes,
                            s.invalidUnits,s.budgetDrops,s.acceptedControls,s.invalidControls,s.controlDrops,parts.parts(),parts.completed(),parts.replayed(),parts.abandoned());
                }
                if(!streams.containsKey(entry.getKey()))continue;
                updateDemand(server,s);
                for(var controller:List.copyOf(s.controllers.entrySet())) {
                    var viewer=server.getPlayerList().getPlayer(controller.getKey());
                    if(now-controller.getValue().lastSeen>3_000||viewer==null||!viewer.isAlive()||viewer.isSpectator()
                            ||!viewer.level().dimension().location().equals(s.state.dimension()))
                        cancelController(server,s,controller.getKey(),false);
                }
            }
            for(var player:server.getPlayerList().getPlayers()) {
                var dim=player.level().dimension().location();
                if(dim.equals(viewers.put(player.getUUID(),dim)))continue;
                for(var s:streams.values())if(watches(player,s.state)) {
                    StreamNet.sendToPlayer(player,s.state);
                    // New viewers wait for the next periodic keyframe instead of receiving an undecodable delta frame.
                }
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{streams.clear();viewers.clear();});
    }
    private static boolean watches(ServerPlayer player,StreamProtocol.State state) {
        return !player.getUUID().equals(state.owner())&&player.level().dimension().location().equals(state.dimension())
                &&StreamNet.canSendToPlayer(player);
    }
    private static void updateDemand(MinecraftServer server,Session session) {
        boolean demand=false;
        for(var player:server.getPlayerList().getPlayers())if(watches(player,session.state)){demand=true;break;}
        if(session.demandKnown&&session.demand==demand)return;
        session.demand=demand;session.demandKnown=true;
        var owner=server.getPlayerList().getPlayer(session.state.owner());
        if(owner!=null&&StreamNet.canSendToPlayer(owner))
            StreamNet.sendToPlayer(owner,new StreamProtocol.Demand(session.state.owner(),session.state.session(),demand));
        LOGGER.info("Stream relay for {} session {} is now {}",shortId(session.state.owner()),shortId(session.state.session()),demand?"active (viewer present)":"idle (no viewers)");
    }
    private static void stop(MinecraftServer server,UUID owner,String reason) {
        var removed=streams.remove(owner);if(removed==null)return;
        cancelControllers(server,removed);
        var parts=removed.assembly.stats();
        LOGGER.info("Stopped stream relay for {} session {} ({}): accepted={} media / {} bytes, invalid={}, budgetDrops={}, incompleteOrExpired={}",
                shortId(owner),shortId(removed.state.session()),reason,removed.acceptedUnits,removed.acceptedBytes,removed.invalidUnits,removed.budgetDrops,parts.abandoned());
        var packet=new StreamProtocol.Stop(owner,removed.state.session());
        for(var player:server.getPlayerList().getPlayers())if(StreamNet.canSendToPlayer(player))StreamNet.sendToPlayer(player,packet);
    }
    private static void cancelController(MinecraftServer server,Session session,UUID controller,boolean forwardIfUnknown) {
        if(session.controllers.remove(controller)==null&&!forwardIfUnknown)return;
        var owner=server.getPlayerList().getPlayer(session.state.owner());
        if(owner!=null&&StreamNet.canSendToPlayer(owner))
            StreamNet.sendToPlayer(owner,StreamProtocol.Control.cancel(session.state.owner(),session.state.session(),controller));
    }
    private static void cancelControllers(MinecraftServer server,Session session) {
        for(var controller:List.copyOf(session.controllers.keySet()))cancelController(server,session,controller,false);
    }
    private static String shortId(UUID id){return id.toString().substring(0,8);}
}
