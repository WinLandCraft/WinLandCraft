package dev.winlandcraft;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** One opt-in broadcast browser per player, separate from their private browser. */
final class StreamBrowserPanel extends BrowserPanel {
    java.util.UUID broadcastSession;
    volatile MediaBridge.Endpoint encoder;
    volatile com.cinemamod.mcef.MCEFBrowser audioTab;
    volatile String streamStatus="Starting codecs...";
    private StreamAudioMonitor monitor;
    private final java.util.Set<java.util.UUID> remoteControllers=new java.util.HashSet<>();
    /** Consumes one shared audio-packet owner in all cases. */
    synchronized void monitor(StreamAudio.Packet packet){
        if(audioTab==null){packet.release();return;}
        if(monitor==null)monitor=new StreamAudioMonitor();monitor.offer(packet);
    }
    StreamBrowserPanel(){setAppName("Browser(streamable)");}
    @Override public boolean canGroup(){return false;}
    @Override protected int listTop(){return 364;}
    @Override protected float minimumHeight(){return 1.2f;}
    @Override protected void browserCreated(com.cinemamod.mcef.MCEFBrowser browser){StreamAudio.attach(browser,this);}
    @Override protected void browserClosed(com.cinemamod.mcef.MCEFBrowser browser){StreamAudio.detach(browser);if(audioTab==browser)audioTab=null;}
    @Override protected void tabSelected(com.cinemamod.mcef.MCEFBrowser browser){audioTab=browser;}
    @Override protected void drawSidebarExtras(PanelCanvas c) {
        c.text("Stream quality",16,176,0xFFD4ACFF,1.5f);
        String[] labels={"FPS: "+ModSettings.streamFps,"Video: "+ModSettings.streamKbps+" kbps","Size: "+ModSettings.streamHeight+"p","Audio: "+(ModSettings.streamAudio?"ON":"OFF"),"Audio: "+ModSettings.streamAudioKbps+" kbps"};
        for(int i=0;i<labels.length;i++) {
            int y=196+i*24;c.rect(12,y,236,22,.3f,0xFF30263F);c.text(labels[i],18,y+7,-1,1.2f);
            c.rect(196,y,24,22,.4f,0xFF624389);c.rect(224,y,24,22,.4f,0xFF624389);
            c.text("-",204,y+7,-1);c.text("+",232,y+7,-1);
        }
        int y=316;c.rect(12,y,236,22,.3f,0xFF30263F);c.text("Allow remote control",18,y+7,-1,1.2f);
        c.rect(220,y+2,20,18,.4f,ModSettings.streamRemoteControl?0xFF4C8E71:0xFF624389);
        if(ModSettings.streamRemoteControl)c.text("✓",225,y+6,-1);
        c.text(Minecraft.getInstance().font.plainSubstrByWidth(streamStatus,230),16,348,0xFFD4ACFF);
    }
    @Override protected boolean sidebarExtraClick(int x,int y,int button) {
        if(y<176||y>=364)return false;
        if(button!=0)return true;
        if(y>=316&&y<340){
            if(x>=196&&x<248){
                ModSettings.streamRemoteControl=!ModSettings.streamRemoteControl;
                if(!ModSettings.streamRemoteControl)clearRemoteControls();
                if(!ModSettings.save())streamStatus="Could not save quality settings";
            }
            return true;
        }
        if(x<196||x>=248||y<196||y>=316)return true;
        int row=(y-196)/24,step=x<222?-1:1;
        switch(row) {
            case 0->ModSettings.streamFps=StreamQuality.step(ModSettings.streamFps,StreamQuality.FPS,step);
            case 1->ModSettings.streamKbps=StreamQuality.step(ModSettings.streamKbps,StreamQuality.BITRATES,step);
            case 2->ModSettings.streamHeight=StreamQuality.step(ModSettings.streamHeight,StreamQuality.HEIGHTS,step);
            case 3->ModSettings.streamAudio=!ModSettings.streamAudio;
            case 4->ModSettings.streamAudioKbps=StreamQuality.step(ModSettings.streamAudioKbps,StreamQuality.AUDIO,step);
        }
        if(!ModSettings.save())streamStatus="Could not save quality settings";
        return true;
    }
    void remoteControl(StreamProtocol.Control input) {
        var controller=input.controller();
        if(input.event()==StreamProtocol.Control.CANCEL){
            releaseInputSource(controller);
            remoteControllers.remove(controller);
            return;
        }
        if(!ModSettings.streamRemoteControl)return;
        if(input.event()==StreamProtocol.Control.MOUSE_DOWN||input.event()==StreamProtocol.Control.KEY)remoteControllers.add(controller);
        switch(input.event()) {
            case StreamProtocol.Control.MOVE -> super.hover(controller,input.x(),input.y());
            case StreamProtocol.Control.MOUSE_DOWN -> super.mouseDown(controller,input.x(),input.y(),input.value());
            case StreamProtocol.Control.MOUSE_UP -> super.mouseUp(controller,input.x(),input.y(),input.value());
            case StreamProtocol.Control.SCROLL -> super.scroll(input.x(),input.y(),input.amount());
            case StreamProtocol.Control.KEY -> super.key(controller,input.value(),input.scan(),input.action(),input.modifiers());
            case StreamProtocol.Control.CHARACTER -> super.character((char)input.value(),input.modifiers());
        }
    }
    private void clearRemoteControls(){
        if(remoteControllers.isEmpty())return;
        for(var controller:java.util.Set.copyOf(remoteControllers))releaseInputSource(controller);
        remoteControllers.clear();
    }
    @Override public void open(Minecraft client) {
        if(!ClientPlayNetworking.canSend(StreamProtocol.State.TYPE)||!ClientPlayNetworking.canSend(StreamProtocol.Frame.TYPE)) {
            if(client.player!=null)client.player.displayClientMessage(Component.literal("Streaming requires WinLandCraft on the server."),false);
            return;
        }
        if(!isOpen()||broadcastSession==null)broadcastSession=java.util.UUID.randomUUID();
        super.open(client);
        if(client.player!=null)client.player.displayClientMessage(Component.literal("Sharing this browser with video and audio. Quality controls are above the tabs."),false);
    }
    @Override public void close(){
        clearRemoteControls();
        synchronized(this){audioTab=null;if(monitor!=null){monitor.close();monitor=null;}}
        super.close();broadcastSession=null;encoder=null;
    }
}
