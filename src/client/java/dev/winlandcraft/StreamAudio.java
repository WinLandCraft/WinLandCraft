package dev.winlandcraft;

import com.cinemamod.mcef.MCEF;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefAudioHandlerAdapter;
import org.cef.misc.*;
import org.lwjgl.system.MemoryUtil;
import java.nio.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Browser-scoped loopback only. Native pointers are copied before returning from CEF's callback. */
final class StreamAudio {
    private static final Map<CefBrowser,Source> sourcesByBrowser=Collections.synchronizedMap(new IdentityHashMap<>());
    private static final ConcurrentHashMap<Integer,Source> sourcesById=new ConcurrentHashMap<>();
    private static final class Source {
        final CefBrowser browser;
        final StreamBrowserPanel panel;
        volatile int browserId;
        volatile int rate,channels;
        final ArrayBlockingQueue<byte[]> freeBuffers=new ArrayBlockingQueue<>(32);
        MediaBridge.Endpoint clockEndpoint;
        long lastPts=Long.MIN_VALUE,timeUs,packets,frames,invalid,nextLog,nextWarning;
        Source(CefBrowser browser,StreamBrowserPanel panel){this.browser=browser;this.panel=panel;browserId=id(browser);}
        byte[] acquire(int size){byte[] bytes;while((bytes=freeBuffers.poll())!=null)if(bytes.length==size)return bytes;return new byte[size];}
        void recycle(byte[] bytes){freeBuffers.offer(bytes);}
    }
    /** One planar PCM block shared by the local monitor and encoder without copying. */
    static final class Packet {
        private final Source source;
        private final byte[] data;
        private final AtomicInteger owners=new AtomicInteger(2);
        private Packet(Source source,byte[] data){this.source=source;this.data=data;}
        byte[] data(){return data;}
        void release(){if(owners.decrementAndGet()==0)source.recycle(data);}
    }
    private static int id(CefBrowser browser){return browser==null?-1:browser.getIdentifier();}
    private static Source source(CefBrowser browser){
        int browserId=id(browser);Source source=browserId<0?null:sourcesById.get(browserId);
        if(source==null)synchronized(sourcesByBrowser){
            source=sourcesByBrowser.get(browser);
            // Some JCEF callbacks use a different Java wrapper. Resolve the original
            // browser once its native ID becomes available, then keep the hot path O(1).
            if(source==null&&browserId>=0)for(var entry:sourcesByBrowser.entrySet())
                if(id(entry.getKey())==browserId){source=entry.getValue();break;}
        }
        if(source!=null&&browserId>=0&&source.browserId!=browserId){
            int previous=source.browserId;source.browserId=browserId;sourcesById.put(browserId,source);
            if(previous>=0&&previous!=browserId)sourcesById.remove(previous,source);
            WinLandCraftClient.LOGGER.info("Stream audio browser registration became ready: tab {}",browserId);
        }
        return source;
    }
    private static boolean selected(Source source,CefBrowser callback) {
        var selected=source.panel.audioTab;if(selected==null)return false;
        if(selected==callback)return true;
        int selectedId=id(selected),callbackId=id(callback);return selectedId>=0&&selectedId==callbackId;
    }
    static void attach(CefBrowser browser,StreamBrowserPanel panel){
        var source=new Source(browser,panel);sourcesByBrowser.put(browser,source);
        if(source.browserId>=0)sourcesById.put(source.browserId,source);
        WinLandCraftClient.LOGGER.info("Attached stream audio capture to browser tab {} ({} tabs tracked)",
                source.browserId<0?"pending":Integer.toString(source.browserId),sourcesByBrowser.size());
    }
    static void detach(CefBrowser browser){
        Source source=sourcesByBrowser.remove(browser);int browserId=id(browser);
        if(source==null&&browserId>=0)source=sourcesById.get(browserId);
        if(source!=null){
            sourcesByBrowser.remove(source.browser);if(source.browserId>=0)sourcesById.remove(source.browserId,source);
            WinLandCraftClient.LOGGER.info("Detached stream audio capture from browser tab {} ({} tabs tracked)",
                    source.browserId<0?"pending":Integer.toString(source.browserId),sourcesByBrowser.size());
        }
    }
    static void install() {
        MCEF.scheduleForInit(success->{if(success)MCEF.getClient().addAudioHandler(new CefAudioHandlerAdapter(){
            @Override public boolean getAudioParameters(CefBrowser browser,CefAudioParameters params){
                var source=source(browser);if(source==null)return false;
                // MCEF's native adapter passes parameters here, but nullptr in
                // OnAudioStreamStarted. Retain the negotiated rate before capture starts.
                if(params==null||params.sampleRate<8000||params.sampleRate>192000){
                    WinLandCraftClient.LOGGER.warn("Stream audio parameters unavailable; keeping native playback");
                    return false;
                }
                source.rate=params.sampleRate;
                WinLandCraftClient.LOGGER.info("Stream audio negotiated: {} Hz, {} frames/buffer, layout {}",params.sampleRate,params.framesPerBuffer,params.channelLayout);
                return true;
            }
            @Override public void onAudioStreamStarted(CefBrowser browser,CefAudioParameters params,int channels) {
                var source=source(browser);if(source!=null){
                    if(params!=null)source.rate=params.sampleRate;
                    source.channels=channels;source.clockEndpoint=null;source.lastPts=Long.MIN_VALUE;
                    source.packets=source.frames=source.invalid=source.nextLog=source.nextWarning=0;
                    WinLandCraftClient.LOGGER.info("Stream audio capture started: {} Hz, {} channels",source.rate,channels);
                }
            }
            @Override public void onAudioStreamPacket(CefBrowser browser,DataPointer data,int frames,long pts) {
                var source=source(browser);
                if(source==null||!selected(source,browser))return;
                var encoder=source.panel.encoder;
                int rate=source.rate,channels=source.channels;
                if(frames<=0||frames>8192||channels<1||channels>32||rate<8000||rate>192000||data==null||data.getAddress()==0){
                    source.invalid++;return;
                }
                try {
                    long leftAddress=MemoryUtil.memGetAddress(data.getAddress());
                    long rightAddress=channels==1?leftAddress:MemoryUtil.memGetAddress(data.getAddress()+org.lwjgl.system.Pointer.POINTER_SIZE);
                    if(leftAddress==0||rightAddress==0)return;
                    var left=MemoryUtil.memFloatBuffer(leftAddress,frames);var right=MemoryUtil.memFloatBuffer(rightAddress,frames);
                    int outputFrames=Math.max(1,(int)Math.round(frames*48000.0/rate));
                    byte[] pcm=source.acquire(outputFrames*8);
                    var bytes=ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
                    double step=rate/48000.0;
                    for(int channel=0;channel<2;channel++) {
                        FloatBuffer input=channel==0?left:right;double pos=0;
                        for(int i=0;i<outputFrames;i++,pos+=step) {
                            int a=Math.min(frames-1,(int)pos),b=Math.min(frames-1,a+1);float first=input.get(a);
                            float value=first+(input.get(b)-first)*(float)(pos-a);
                            bytes.putFloat(Float.isFinite(value)?Math.clamp(value,-1,1):0);
                        }
                    }
                    var packet=new Packet(source,pcm);boolean monitorOwned=false,encoderOwned=false;
                    try {
                        source.panel.monitor(packet);monitorOwned=true;
                        if(encoder!=null){
                            long current=encoder.elapsedTimeUs();
                            if(source.clockEndpoint!=encoder){source.clockEndpoint=encoder;source.lastPts=pts;source.timeUs=current;}
                            else {
                                long delta=pts-source.lastPts;
                                if(delta>=0&&delta<=1_000)source.timeUs+=delta*1000;else source.timeUs=current;
                                source.lastPts=pts;
                                if(source.timeUs<current-1_000_000||source.timeUs>current+1_000_000){
                                    source.timeUs=current;
                                    long now=System.currentTimeMillis();
                                    if(now>=source.nextWarning){source.nextWarning=now+10_000;WinLandCraftClient.LOGGER.warn("Stream audio PTS drifted from the video clock; timeline was resynchronized (pts={})",pts);}
                                }
                            }
                            encoder.audio(packet,outputFrames,source.timeUs);encoderOwned=true;
                        } else {packet.release();encoderOwned=true;}
                    } finally {
                        if(!monitorOwned)packet.release();
                        if(!encoderOwned)packet.release();
                    }
                    source.packets++;source.frames+=outputFrames;
                    long now=System.currentTimeMillis();
                    if(now>=source.nextLog){
                        source.nextLog=now+10_000;
                        WinLandCraftClient.LOGGER.info("Stream audio capture health: packets={}, outputFrames={}, invalid={}, rate={} Hz, channels={}, lastPts={}, encoder={}",
                                source.packets,source.frames,source.invalid,rate,channels,pts,encoder==null?"not ready":"#"+encoder.id);
                    }
                }catch(RuntimeException error){
                    source.panel.streamStatus="Audio capture error; see log";long now=System.currentTimeMillis();
                    if(now>=source.nextWarning){source.nextWarning=now+10_000;WinLandCraftClient.LOGGER.warn("Stream audio capture failed (further failures are rate-limited)",error);}
                }
            }
            @Override public void onAudioStreamStopped(CefBrowser browser) {
                var source=source(browser);if(source!=null){
                    WinLandCraftClient.LOGGER.info("Stream audio capture stopped after {} packets / {} output frames",source.packets,source.frames);
                    source.channels=0;source.clockEndpoint=null;source.lastPts=Long.MIN_VALUE;
                }
            }
            @Override public void onAudioStreamError(CefBrowser browser,String message) {
                var source=source(browser);if(source!=null){source.panel.streamStatus="Audio unavailable: "+message;WinLandCraftClient.LOGGER.warn("CEF stream audio error: {}",message);}
            }
        });});
    }
}
