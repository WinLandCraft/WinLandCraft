package dev.winlandcraft;

import com.cinemamod.mcef.MCEF;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefAudioHandlerAdapter;
import org.cef.misc.*;
import org.lwjgl.system.MemoryUtil;
import java.nio.*;
import java.util.concurrent.ConcurrentHashMap;

/** Browser-scoped loopback only. Native pointers are copied before returning from CEF's callback. */
final class StreamAudio {
    private static final ConcurrentHashMap<CefBrowser,Source> sources=new ConcurrentHashMap<>();
    private static final class Source {
        final StreamBrowserPanel panel;
        volatile int rate,channels;
        Source(StreamBrowserPanel panel){this.panel=panel;}
    }
    static void attach(CefBrowser browser,StreamBrowserPanel panel){sources.put(browser,new Source(panel));}
    static void detach(CefBrowser browser){sources.remove(browser);}
    static void install() {
        MCEF.scheduleForInit(success->{if(success)MCEF.getClient().addAudioHandler(new CefAudioHandlerAdapter(){
            @Override public boolean getAudioParameters(CefBrowser browser,CefAudioParameters params){
                var source=sources.get(browser);if(source==null)return false;
                // MCEF's native adapter passes parameters here, but nullptr in
                // OnAudioStreamStarted. Retain the negotiated rate before capture starts.
                if(params==null||params.sampleRate<8000||params.sampleRate>192000){
                    WinLandCraftClient.LOGGER.warn("Stream audio parameters unavailable; keeping native playback");
                    return false;
                }
                source.rate=params.sampleRate;
                return true;
            }
            @Override public void onAudioStreamStarted(CefBrowser browser,CefAudioParameters params,int channels) {
                var source=sources.get(browser);if(source!=null){
                    if(params!=null)source.rate=params.sampleRate;
                    source.channels=channels;
                    WinLandCraftClient.LOGGER.info("Stream audio capture started: {} Hz, {} channels",source.rate,channels);
                }
            }
            @Override public void onAudioStreamPacket(CefBrowser browser,DataPointer data,int frames,long pts) {
                var source=sources.get(browser);
                if(source==null||source.panel.audioTab!=browser)return;
                var encoder=source.panel.encoder;
                int rate=source.rate,channels=source.channels;
                if(frames<=0||frames>8192||channels<1||channels>32||rate<8000||rate>192000||data==null||data.getAddress()==0)return;
                try {
                    long leftAddress=MemoryUtil.memGetAddress(data.getAddress());
                    long rightAddress=channels==1?leftAddress:MemoryUtil.memGetAddress(data.getAddress()+org.lwjgl.system.Pointer.POINTER_SIZE);
                    if(leftAddress==0||rightAddress==0)return;
                    var left=MemoryUtil.memFloatBuffer(leftAddress,frames);var right=MemoryUtil.memFloatBuffer(rightAddress,frames);
                    int outputFrames=Math.max(1,(int)Math.round(frames*48000.0/rate));
                    var bytes=ByteBuffer.allocate(outputFrames*8).order(ByteOrder.LITTLE_ENDIAN);
                    for(var input:new FloatBuffer[]{left,right})for(int i=0;i<outputFrames;i++) {
                        double pos=i*rate/48000.0;int a=Math.min(frames-1,(int)pos),b=Math.min(frames-1,a+1);
                        float value=input.get(a)+(input.get(b)-input.get(a))*(float)(pos-a);
                        bytes.putFloat(Float.isFinite(value)?Math.clamp(value,-1,1):0);
                    }
                    source.panel.monitor(bytes.array());
                    if(encoder!=null)encoder.audio(bytes.array(),outputFrames,Math.max(0,(pts-encoder.startedAt)*1000));
                }catch(RuntimeException error){source.panel.streamStatus="Audio capture error; see log";WinLandCraftClient.LOGGER.debug("Stream audio capture failed",error);}
            }
            @Override public void onAudioStreamError(CefBrowser browser,String message) {
                var source=sources.get(browser);if(source!=null)source.panel.streamStatus="Audio unavailable: "+message;
            }
        });});
    }
}
