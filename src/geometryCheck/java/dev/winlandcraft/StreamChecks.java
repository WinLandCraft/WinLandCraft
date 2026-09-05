package dev.winlandcraft;

import java.nio.ByteBuffer;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

public final class StreamChecks {
    private static final UUID OWNER=UUID.randomUUID(),SESSION=UUID.randomUUID();
    public static void main(String[] args) throws Exception {
        RemoteAppInputChecks.run();packets();media();bridgeOrigins();timing();audioResampling();placement();MediaBridgeChecks.run();
        System.out.println("Streaming: bounded media/control codecs, viewer demand, ownership/dimension/permission validation, rate-safe remote input, replay/partial frame rejection, H.264/VP9/Opus envelopes, ordered batched bridge, audio-preserving queue limits, stable frame cadence and continuous resampling, immutable replicas, scaled and curved replica geometry passed.");
    }
    private static StreamProtocol.State state(UUID owner) {
        return state(owner,false);
    }
    private static StreamProtocol.State state(UUID owner,boolean remoteControl) {
        return new StreamProtocol.State(owner,SESSION,ResourceLocation.withDefaultNamespace("overworld"),1,2,3,0,0,0,1,3.2f,1.88f,1280,752,32,0,1,remoteControl);
    }
    private static void packets() {
        var s=state(OWNER);
        check(StreamRelay.owns(OWNER,s,s.dimension()),"owner accepted");
        check(!StreamRelay.owns(UUID.randomUUID(),s,s.dimension()),"other player cannot move stream");
        check(!StreamRelay.owns(OWNER,s,ResourceLocation.withDefaultNamespace("the_nether")),"dimension spoof rejected");
        var invalid=new StreamProtocol.State(OWNER,SESSION,s.dimension(),Double.NaN,0,0,0,0,0,1,3,2,1280,752,32,0,1,false);
        check(!invalid.valid(),"NaN rejected");
        var buffer=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),net.minecraft.core.RegistryAccess.EMPTY);
        try {
            StreamProtocol.State.CODEC.encode(buffer,s);check(s.equals(StreamProtocol.State.CODEC.decode(buffer)),"state codec round trip");
            var stop=new StreamProtocol.Stop(OWNER,SESSION);StreamProtocol.Stop.CODEC.encode(buffer,stop);
            check(stop.equals(StreamProtocol.Stop.CODEC.decode(buffer)),"stop codec round trip");
            var demand=new StreamProtocol.Demand(OWNER,SESSION,true);StreamProtocol.Demand.CODEC.encode(buffer,demand);
            check(demand.equals(StreamProtocol.Demand.CODEC.decode(buffer))&&demand.valid(s),"viewer demand codec round trip");
            check(!new StreamProtocol.Demand(UUID.randomUUID(),SESSION,true).valid(s),"viewer demand owner spoof rejected");
            var controller=UUID.randomUUID();var controlled=state(OWNER,true);
            var control=StreamProtocol.Control.pointer(OWNER,SESSION,controller,StreamProtocol.Control.MOUSE_DOWN,640,300,0);
            StreamProtocol.Control.CODEC.encode(buffer,control);
            check(control.equals(StreamProtocol.Control.CODEC.decode(buffer)),"control codec round trip");
            check(StreamRelay.mayControl(controller,control,controlled,controlled.dimension()),"enabled same-dimension controller accepted");
            check(!StreamRelay.mayControl(controller,control,s,s.dimension()),"disabled remote control rejected");
            check(!StreamRelay.mayControl(UUID.randomUUID(),control,controlled,controlled.dimension()),"controller spoof rejected");
            check(!StreamRelay.mayControl(controller,control,controlled,ResourceLocation.withDefaultNamespace("the_nether")),"remote dimension spoof rejected");
            check(StreamRelay.mayControl(controller,StreamProtocol.Control.cancel(OWNER,SESSION,controller),s,s.dimension()),"release accepted after permission is disabled");
            check(StreamProtocol.Control.keepalive(OWNER,SESSION,controller).valid(controlled),"controller keepalive is bounded");
            check(!StreamProtocol.Control.key(OWNER,SESSION,controller,67,46,1,2).valid(controlled),"remote control modifier rejected");
            check(!StreamProtocol.Control.key(OWNER,SESSION,controller,341,29,1,0).valid(controlled),"remote system modifier key rejected");
            var budget=new StreamRelay.ControlBudget(1_000);
            for(int i=0;i<240;i++)check(budget.allow(1_000),"initial control burst bounded");
            check(!budget.allow(1_000)&&budget.allow(2_000),"control budget refills over time");
        } finally {buffer.release();}
        byte[] bytes=new byte[StreamProtocol.MAX_FRAME_BYTES];new Random(1).nextBytes(bytes);
        var parts=StreamProtocol.split(OWNER,SESSION,1,bytes);check(parts.size()==StreamProtocol.MAX_PARTS,"bounded chunk count");
        var assembly=new StreamProtocol.Assembly();byte[] complete=null;
        for(var part:parts) {
            var b=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),net.minecraft.core.RegistryAccess.EMPTY);
            try {
                StreamProtocol.Frame.CODEC.encode(b,part);check(b.readableBytes()<32767,"C2S packet below Minecraft limit");
                complete=assembly.accept(StreamProtocol.Frame.CODEC.decode(b),1000);
            } finally {b.release();}
        }
        check(Arrays.equals(bytes,complete),"frame reassembly");
        for(var part:parts)check(assembly.accept(part,1100)==null,"replay rejected");
        parts=StreamProtocol.split(OWNER,SESSION,2,bytes);
        check(assembly.accept(parts.get(0),1200)==null,"incomplete frame waits");
        check(assembly.accept(parts.get(2),1200)==null,"out of order rejected");
        check(assembly.accept(parts.get(3),1200)==null,"partial frame not displayed");
        parts=StreamProtocol.split(OWNER,SESSION,3,bytes);assembly.accept(parts.get(0),1300);
        for(int i=1;i<parts.size();i++)check(assembly.accept(parts.get(i),5000)==null,"expired frame rejected");
        check(!new StreamProtocol.Frame(OWNER,SESSION,4,0,StreamProtocol.MAX_PARTS+1,new byte[24000]).valid(),"too many parts rejected");
        var tiny=StreamProtocol.split(OWNER,SESSION,4,new byte[]{1,2});check(Arrays.equals(new byte[]{1,2},assembly.accept(tiny.getFirst(),6000)),"assembly recovers");
    }
    private static void media() {
        var key=new StreamMedia(StreamMedia.VIDEO,true,123456,1280,720,new byte[]{1,2,3});
        var parsed=StreamMedia.read(key.pack());
        check(parsed!=null&&parsed.key()&&parsed.codec()==StreamMedia.VP9&&parsed.timeUs()==123456&&parsed.width()==1280&&Arrays.equals(parsed.data(),key.data()),"video envelope");
        var h264=new StreamMedia(StreamMedia.VIDEO,true,StreamMedia.H264,123456,1280,720,new byte[]{1,2,3});
        check(StreamMedia.read(h264.pack()).codec()==StreamMedia.H264,"H.264 codec marker");
        check(StreamMedia.read(new StreamMedia(StreamMedia.VIDEO,true,2,123456,1280,720,new byte[]{1}).pack())==null,"unknown codec rejected");
        var audio=new StreamMedia(StreamMedia.AUDIO,true,123457,0,0,new byte[]{4,5});
        check(StreamMedia.read(audio.pack()).kind()==StreamMedia.AUDIO,"audio envelope");
        check(StreamMedia.read(new byte[]{0,1,2})==null,"invalid envelope");
        check(StreamMedia.read(new StreamMedia(StreamMedia.VIDEO,true,0,4096,720,new byte[]{1}).pack())==null,"oversized video rejected");
        var queue=new MediaBridge.Queue();
        check(!queue.offer(new StreamMedia(StreamMedia.VIDEO,false,123450,1280,720,new byte[]{1}).pack()),"late viewer waits for keyframe");
        check(queue.offer(key.pack()),"keyframe starts playback");
        check(queue.offer(audio.pack()),"audio follows video clock");
        check(StreamMedia.read(queue.poll()).kind()==StreamMedia.VIDEO,"ordered video");
        check(StreamMedia.read(queue.poll()).kind()==StreamMedia.AUDIO,"ordered audio");
        queue.clear();check(queue.offer(audio.pack()),"audio continues while video waits for a keyframe");
        check(StreamMedia.read(queue.poll()).kind()==StreamMedia.AUDIO,"audio is not coupled to video recovery");
        var batched=new MediaBridge.Queue();check(batched.offer(key.pack()),"batch starts with keyframe");
        for(int i=0;i<80;i++)check(batched.offer(audio.pack()),"audio accepted into batch");
        check(batchCount(batched.pollBatch())==64,"bridge batch is capped at 64 packets");
        check(batchCount(batched.pollBatch())==17&&batched.stats().queued()==0,"bridge batch drains remaining packets");
        var overflow=new MediaBridge.Queue();
        byte[] large=new byte[700_000];
        check(overflow.offer(new StreamMedia(StreamMedia.VIDEO,true,1,1280,720,large).pack()),"overflow queue starts with keyframe");
        check(overflow.offer(audio.pack()),"overflow queue contains continuity-sensitive audio");
        check(overflow.offer(new StreamMedia(StreamMedia.VIDEO,false,2,1280,720,large).pack()),"first video delta fits");
        check(!overflow.offer(new StreamMedia(StreamMedia.VIDEO,false,3,1280,720,large).pack()),"overflow delta waits for replacement keyframe");
        check(StreamMedia.read(overflow.poll()).kind()==StreamMedia.AUDIO&&overflow.poll()==null,"video overflow preserves queued audio");
        check(overflow.offer(new StreamMedia(StreamMedia.VIDEO,true,4,1280,720,new byte[]{1}).pack()),"queue recovers on replacement keyframe");
    }
    private static int batchCount(MediaBridge.Queue.Batch batch) {
        int bytes=0;for(var packet:batch.packets()){check(StreamMedia.header(packet)!=null,"valid batched media packet");bytes+=Integer.BYTES+packet.length;}
        check(bytes==batch.bytes(),"complete batch length");return batch.packets().length;
    }
    private static void bridgeOrigins() {
        String origin="http://127.0.0.1:49152";
        check(MediaBridge.acceptsOrigin(origin,null,"GET","config"),"missing GET origin accepted");
        check(MediaBridge.acceptsOrigin(origin,origin,"POST","status"),"matching status origin accepted");
        check(MediaBridge.acceptsOrigin(origin,"null","POST","status"),"CEF opaque status origin accepted");
        check(MediaBridge.acceptsOrigin(origin,"null","POST","packet"),"CEF opaque media origin accepted");
        check(MediaBridge.acceptsOrigin(origin,"null","POST","packets"),"CEF opaque batched media origin accepted");
        check(!MediaBridge.acceptsOrigin(origin,"null","POST","unknown"),"opaque unknown POST rejected");
        check(!MediaBridge.acceptsOrigin(origin,"null","GET","config"),"opaque GET origin rejected");
        check(!MediaBridge.acceptsOrigin(origin,"https://example.com","POST","status"),"foreign origin rejected");
    }
    private static void timing() {
        long now=1_000_000_000L,period=1_000_000_000L/30;
        long first=StreamClient.advanceFrameDeadline(0,now,30);
        check(first==now+period,"first frame deadline starts one period ahead");
        check(StreamClient.advanceFrameDeadline(first,first-1,30)==first,"early render keeps its deadline");
        long delayed=StreamClient.advanceFrameDeadline(first,first+period*4+period/2,30);
        check(delayed>first+period*4+period/2&&delayed<=first+period*5+period/2,"late render advances to the next deadline");
        check((delayed-first)%period==0,"late render preserves cadence phase");
    }
    private static void audioResampling() {
        int samples=44_101;float[] left=new float[samples],right=new float[samples];
        for(int i=0;i<samples;i++){left[i]=(float)Math.sin(i*.013);right[i]=(float)Math.cos(i*.009);}
        float[][] whole=resample(left,right,new int[]{samples},44_100),split=resample(left,right,new int[]{1,17,1024,333,4096,8192,12000,18438},44_100);
        check(whole[0].length==48_001&&split[0].length==whole[0].length,"44.1 kHz resampling has exact cumulative length");
        for(int channel=0;channel<2;channel++)for(int i=0;i<whole[channel].length;i++)
            if(Math.abs(whole[channel][i]-split[channel][i])>1.0e-6f)throw new AssertionError("resampling changed at packet boundary");
        float[][] nativeRate=resample(new float[480],new float[480],new int[]{1,31,128,320},48_000);
        check(nativeRate[0].length==480,"48 kHz input remains one-to-one");
    }
    private static float[][] resample(float[] left,float[] right,int[] chunks,int rate) {
        var resampler=new StreamAudioResampler();List<ArrayList<Float>> channels=List.of(new ArrayList<>(),new ArrayList<>());int offset=0;
        for(int size:chunks) {
            check(size>0&&offset+size<=left.length,"valid resampler fixture chunk");
            var l=java.nio.FloatBuffer.wrap(left,offset,size).slice();var r=java.nio.FloatBuffer.wrap(right,offset,size).slice();
            var output=resampler.process(l,r,size,rate,byte[]::new);offset+=size;
            if(output==null)continue;
            var samples=ByteBuffer.wrap(output.pcm()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            for(int channel=0;channel<2;channel++)for(int frame=0;frame<output.frames();frame++)channels.get(channel).add(samples.get(channel*output.frames()+frame));
        }
        check(offset==left.length,"resampler fixture consumes all input");
        var result=new float[2][];
        for(int channel=0;channel<2;channel++){result[channel]=new float[channels.get(channel).size()];for(int i=0;i<result[channel].length;i++)result[channel][i]=channels.get(channel).get(i);}
        return result;
    }
    private static void placement() {
        boolean remoteControl=ModSettings.streamRemoteControl;
        ModSettings.streamRemoteControl=true;
        try {
            for(WorldPanel nativePanel:new WorldPanel[]{new FileManagerPanel(),new NotepadPanel(),new TaskManagerPanel(null),new LaserCalibrationPanel()}) {
                nativePanel.position=new Vec3(0,80,0);nativePanel.orientation=new Quaternionf();
                nativePanel.broadcastSession=SESSION;
                var state=StreamClient.snapshot(nativePanel,OWNER,SESSION);
                check(state.valid()&&state.remoteControl(),"native streams advertise owner-granted remote control");
                check(!nativePanel.canGroup(),"published native apps cannot join private groups");
                check(state.pixelsWide()==nativePanel.pixelWidth()&&state.pixelsHigh()==nativePanel.pixelHeight(),"native capture keeps app resolution");
                nativePanel.broadcastSession=null;
                check(nativePanel.canGroup(),"stopping publication restores grouping");
            }
        } finally {ModSettings.streamRemoteControl=remoteControl;}

        for(float curvature:new float[]{0,.6f,1}) {
            var host=new BrowserPanel();host.broadcastSession=SESSION;host.position=new Vec3(20000000,80,20000000);host.orientation=new Quaternionf().rotateXYZ(.2f,.7f,.1f);
            host.scaleTo(4,2.25f);GroupCurve.get(host).apply(curvature);
            var s=StreamClient.snapshot(host,OWNER,SESSION);var viewer=new RemoteStreamPanel(s);viewer.place(s);
            check(!viewer.canInteract()&&!viewer.canResize()&&!viewer.canMove()&&!viewer.canGroup(),"read-only viewer cannot manipulate stream");
            var controlled=state(OWNER,true);var controllerView=new RemoteStreamPanel(controlled);
            check(controllerView.canInteract()&&controllerView.acceptsKeyboard(),"enabled viewer can control browser content");
            check(!controllerView.canResize()&&!controllerView.canMove()&&!controllerView.canGroup(),"controller cannot manipulate replica placement");
            check(controllerView.controlY(32)==0&&controllerView.controlY(0)==-32,"capture titlebar is excluded from browser input coordinates");
            check(!host.canGroup(),"shared/private grouping disabled");
            for(float x:new float[]{-host.worldWidth()/2,0,host.worldWidth()/2})for(float y:new float[]{-host.worldHeight()/2,0,WindowGroups.top(host)}) {
                Vec3 original=host.curve.panelPoint(host,x,y,0);
                float shifted=y-host.titlebarHeight()*host.worldHeight()/host.pixelHeight()/2;
                Vec3 replica=viewer.curve==null?WindowGroups.world(viewer.position,viewer.orientation,x,shifted):viewer.curve.panelPoint(viewer,x,shifted,0);
                if(original.distanceTo(replica)>.00001)throw new AssertionError("stream geometry mismatch: "+original.distanceTo(replica));
            }
            check(viewer.pixelWidth()==1280&&viewer.pixelHeight()==720,"capture contains content and sidebar; owner controls stay local");
        }
    }
    private static void check(boolean valid,String message){if(!valid)throw new AssertionError(message);}
}
