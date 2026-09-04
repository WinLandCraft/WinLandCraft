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
        final BrowserPanel panel;
        volatile int browserId;
        private StreamAudioMonitor monitor;
        private boolean closed;
        synchronized void monitor(Packet packet){if(closed){packet.release();return;}if(monitor==null)monitor=new StreamAudioMonitor();monitor.offer(packet);}
        synchronized void close(){closed=true;if(monitor!=null){monitor.close();monitor=null;}}
        volatile int rate,channels;
        final ArrayBlockingQueue<byte[]> freeBuffers=new ArrayBlockingQueue<>(32);
        final StreamAudioResampler resampler=new StreamAudioResampler();
        MediaBridge.Endpoint clockEndpoint;
        long timeUs,clockFrames,packets,frames,invalid,nextLog,nextWarning;
        Source(CefBrowser browser,BrowserPanel panel){this.browser=browser;this.panel=panel;browserId=id(browser);}
        byte[] acquire(int size){byte[] bytes;while((bytes=freeBuffers.poll())!=null)if(bytes.length==size)return bytes;return new byte[size];}
        void recycle(byte[] bytes){freeBuffers.offer(bytes);}
    }
    /** One planar PCM block shared by the local monitor and encoder without copying. */
    static final class Packet {
        private final Source source;
        private final byte[] data;
        private final AtomicInteger owners=new AtomicInteger(2);
        private Packet(Source source,byte[] data){this.source=source;this.data=data;}
        static Packet owned(byte[] pcm){return new Packet(null,pcm);}
        byte[] data(){return data;}
        void release(){if(owners.decrementAndGet()==0&&source!=null)source.recycle(data);}
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
    static void attach(CefBrowser browser,BrowserPanel panel){
        var source=new Source(browser,panel);sourcesByBrowser.put(browser,source);
        if(source.browserId>=0)sourcesById.put(source.browserId,source);
        WinLandCraftClient.LOGGER.info("Attached stream audio capture to browser tab {} ({} tabs tracked)",
                source.browserId<0?"pending":Integer.toString(source.browserId),sourcesByBrowser.size());
    }
    static void detach(CefBrowser browser){
        Source source=sourcesByBrowser.remove(browser);int browserId=id(browser);
        if(source==null&&browserId>=0)source=sourcesById.get(browserId);
        if(source!=null){
            source.close();sourcesByBrowser.remove(source.browser);if(source.browserId>=0)sourcesById.remove(source.browserId,source);
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
                    source.channels=channels;source.clockEndpoint=null;source.clockFrames=0;source.resampler.reset();
                    source.packets=source.frames=source.invalid=source.nextLog=source.nextWarning=0;
                    WinLandCraftClient.LOGGER.info("Stream audio capture started: {} Hz, {} channels",source.rate,channels);
                }
            }
            @Override public void onAudioStreamPacket(CefBrowser browser,DataPointer data,int frames,long pts) {
                var source=source(browser);
                if(source==null)return;
                var encoder=selected(source,browser)?source.panel.encoder:null;
                int rate=source.rate,channels=source.channels;
                if(frames<=0||frames>8192||channels<1||channels>32||rate<8000||rate>192000||data==null||data.getAddress()==0){
                    source.invalid++;return;
                }
                try {
                    long leftAddress=MemoryUtil.memGetAddress(data.getAddress());
                    long rightAddress=channels==1?leftAddress:MemoryUtil.memGetAddress(data.getAddress()+org.lwjgl.system.Pointer.POINTER_SIZE);
                    if(leftAddress==0||rightAddress==0)return;
                    var left=MemoryUtil.memFloatBuffer(leftAddress,frames);var right=MemoryUtil.memFloatBuffer(rightAddress,frames);
                    var output=source.resampler.process(left,right,frames,rate,source::acquire);
                    if(output==null)return;
                    int outputFrames=output.frames();byte[] pcm=output.pcm();
                    var packet=new Packet(source,pcm);boolean monitorOwned=false,encoderOwned=false;
                    try {
                        source.monitor(packet);monitorOwned=true;
                        if(encoder!=null){
                            long current=encoder.elapsedTimeUs();
                            if(source.clockEndpoint!=encoder){source.clockEndpoint=encoder;source.timeUs=current;source.clockFrames=0;}
                            long packetTime=source.timeUs+source.clockFrames*1_000_000/StreamAudioResampler.OUTPUT_RATE;
                            source.clockFrames+=outputFrames;
                            encoder.audio(packet,outputFrames,packetTime);encoderOwned=true;
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
                    source.channels=0;source.clockEndpoint=null;source.clockFrames=0;source.resampler.reset();
                }
            }
            @Override public void onAudioStreamError(CefBrowser browser,String message) {
                var source=source(browser);if(source!=null){source.panel.streamStatus="Audio unavailable: "+message;WinLandCraftClient.LOGGER.warn("CEF stream audio error: {}",message);}
            }
        });});
    }
}
