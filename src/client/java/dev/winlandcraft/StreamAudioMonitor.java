package dev.winlandcraft;

import javax.sound.sampled.*;
import java.nio.*;
import java.util.concurrent.ArrayBlockingQueue;

/** CEF capture replaces native playback, so keep the each captured browser tab audible locally.
 *  Reopens the output line instead of dying when the OS switches or loses audio devices. */
final class StreamAudioMonitor implements AutoCloseable {
    // Deep enough to ride out Bluetooth handoffs and decoder jitter (~0.5-2 s).
    private final ArrayBlockingQueue<StreamAudio.Packet> queue;
    private volatile boolean closed;
    private volatile SourceDataLine line;
    private final Thread worker;
    private long nextWarning;
    StreamAudioMonitor(){this(8);}
    StreamAudioMonitor(int capacity){queue=new ArrayBlockingQueue<>(capacity);worker=Thread.ofVirtual().name("WinLandCraft local browser audio").start(this::run);}
    /** Consumes one shared audio-packet owner in all cases. */
    synchronized void offer(StreamAudio.Packet packet){
        if(closed){packet.release();return;}
        if(!queue.offer(packet)){var dropped=queue.poll();if(dropped!=null)dropped.release();if(!queue.offer(packet))packet.release();}
    }
    private void run() {
        byte[] pcm=new byte[0];
        try {
            while(!closed) {
                var active=line;
                if(active==null) {
                    try {
                        var format=new AudioFormat(48000,16,2,true,false);
                        active=AudioSystem.getSourceDataLine(format);active.open(format,4800*4);active.start();
                        line=active;
                    } catch(Exception unavailable) {
                        warn("Local stream audio output unavailable, retrying");
                        if(!sleep(1000))break;
                        continue;
                    }
                }
                StreamAudio.Packet packet;
                try {
                    packet=queue.poll(500,java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();break;}
                if(packet==null)continue;
                try {
                    byte[] data=packet.data();int frames=data.length/8;
                    var floats=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
                    int pcmBytes=frames*4;if(pcm.length<pcmBytes)pcm=new byte[pcmBytes];
                    var output=ByteBuffer.wrap(pcm,0,pcmBytes).order(ByteOrder.LITTLE_ENDIAN);
                    for(int i=0;i<frames;i++){output.putShort((short)(floats.get(i)*32767));output.putShort((short)(floats.get(frames+i)*32767));}
                    int offset=0;while(!closed&&offset<pcmBytes)offset+=active.write(pcm,offset,pcmBytes-offset);
                } catch(RuntimeException lost) {
                    warn("Local stream audio device lost, reopening");
                    closeLine();
                } finally {packet.release();}
            }
        } finally {closed=true;releaseQueued();closeLine();}
    }
    private void closeLine() {
        var active=line;line=null;
        if(active!=null){try{active.stop();active.flush();active.close();}catch(Exception ignored){}}
    }
    private void warn(String message) {
        long now=System.currentTimeMillis();
        if(now>=nextWarning){nextWarning=now+10_000;WinLandCraftClient.LOGGER.warn(message);}
    }
    private boolean sleep(long millis) {
        try {Thread.sleep(millis);return !closed;}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();return false;}
    }
    private void releaseQueued(){StreamAudio.Packet packet;while((packet=queue.poll())!=null)packet.release();}
    /** Buffered packet count so producers can pace themselves to consumption. */
    int queued(){return queue.size();}
    /** Never blocks: closing the line unblocks a stuck write, and the daemon worker exits itself. */
    @Override public synchronized void close(){closed=true;releaseQueued();closeLine();worker.interrupt();}
}
