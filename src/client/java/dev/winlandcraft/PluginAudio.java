package dev.winlandcraft;
import dev.winlandcraft.api.v2.AudioOutput;
import java.nio.*;

final class PluginAudio implements AudioOutput {
    private final WorldPanel panel;private StreamAudioMonitor monitor;private boolean local=true,closed;
    private long nextNs;private MediaBridge.Endpoint clock;private long timeUs;
    PluginAudio(WorldPanel panel){this.panel=panel;}
    static byte[] pcm(float[] stereo){
        if(stereo==null||stereo.length<2||stereo.length>9600||(stereo.length&1)!=0)throw new IllegalArgumentException("Expected 1..4800 stereo frames at 48 kHz");
        byte[] result=new byte[stereo.length*4];var out=ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        for(int channel=0;channel<2;channel++)for(int i=channel;i<stereo.length;i+=2){float value=stereo[i];out.putFloat(Float.isFinite(value)?Math.clamp(value,-1,1):0);}
        return result;
    }
    public synchronized boolean submit(float[] stereo){
        if(closed)return false;
        long now=System.nanoTime();if(nextNs>now+200_000_000L)return false;
        byte[] bytes=pcm(stereo);int frames=stereo.length/2;nextNs=Math.max(now,nextNs)+frames*1_000_000_000L/48000;
        var packet=StreamAudio.Packet.owned(bytes);boolean a=false,b=false;
        try {
            if(local){if(monitor==null)monitor=new StreamAudioMonitor();monitor.offer(packet);}else packet.release();a=true;
            var encoder=panel.encoder;
            if(encoder!=null){long current=encoder.elapsedTimeUs();if(clock!=encoder||Math.abs(timeUs-current)>200_000){clock=encoder;timeUs=current;}encoder.audio(packet,frames,timeUs);timeUs+=frames*1_000_000L/48000;}else packet.release();b=true;
        }finally{if(!a)packet.release();if(!b)packet.release();}
        return true;
    }
    public synchronized void localPlayback(boolean enabled){if(closed)return;local=enabled;if(!local&&monitor!=null){monitor.close();monitor=null;}}
    public synchronized void clear(){if(monitor!=null){monitor.close();monitor=null;}nextNs=0;clock=null;timeUs=0;}
    synchronized void close(){closed=true;clear();}
}
