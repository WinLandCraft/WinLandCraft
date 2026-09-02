package dev.winlandcraft;

import javax.sound.sampled.*;
import java.nio.*;
import java.util.concurrent.ArrayBlockingQueue;

/** CEF capture replaces native playback, so keep the each captured browser tab audible locally. */
final class StreamAudioMonitor implements AutoCloseable {
    private final ArrayBlockingQueue<StreamAudio.Packet> queue=new ArrayBlockingQueue<>(8);
    private volatile boolean closed;
    private final Thread worker;
    StreamAudioMonitor(){worker=Thread.ofVirtual().name("WinLandCraft local browser audio").start(this::run);}
    /** Consumes one shared audio-packet owner in all cases. */
    synchronized void offer(StreamAudio.Packet packet){
        if(closed){packet.release();return;}
        if(!queue.offer(packet)){var dropped=queue.poll();if(dropped!=null)dropped.release();if(!queue.offer(packet))packet.release();}
    }
    private void run() {
        SourceDataLine line=null;
        byte[] pcm=new byte[0];
        try {
            var format=new AudioFormat(48000,16,2,true,false);
            line=AudioSystem.getSourceDataLine(format);line.open(format,4800*4);line.start();
            while(!closed) {
                var packet=queue.take();
                try {
                    byte[] data=packet.data();int frames=data.length/8;
                    var floats=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
                    int pcmBytes=frames*4;if(pcm.length<pcmBytes)pcm=new byte[pcmBytes];
                    var output=ByteBuffer.wrap(pcm,0,pcmBytes).order(ByteOrder.LITTLE_ENDIAN);
                    for(int i=0;i<frames;i++){output.putShort((short)(floats.get(i)*32767));output.putShort((short)(floats.get(frames+i)*32767));}
                    int offset=0;while(!closed&&offset<pcmBytes)offset+=line.write(pcm,offset,pcmBytes-offset);
                } finally {packet.release();}
            }
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        catch(Exception unavailable){WinLandCraftClient.LOGGER.warn("Local stream audio output unavailable",unavailable);}
        finally {closed=true;releaseQueued();if(line!=null){line.stop();line.flush();line.close();}}
    }
    private void releaseQueued(){StreamAudio.Packet packet;while((packet=queue.poll())!=null)packet.release();}
    @Override public synchronized void close(){closed=true;releaseQueued();worker.interrupt();}
}
