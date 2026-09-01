package dev.winlandcraft;

import javax.sound.sampled.*;
import java.nio.*;
import java.util.concurrent.ArrayBlockingQueue;

/** CEF capture replaces native playback, so keep the creator's selected tab audible locally. */
final class StreamAudioMonitor implements AutoCloseable {
    private final ArrayBlockingQueue<byte[]> queue=new ArrayBlockingQueue<>(8);
    private volatile boolean closed;
    private final Thread worker;
    StreamAudioMonitor(){worker=Thread.ofVirtual().name("WinLandCraft local browser audio").start(this::run);}
    void offer(byte[] planar){if(closed)return;if(!queue.offer(planar)){queue.poll();queue.offer(planar);}}
    private void run() {
        SourceDataLine line=null;
        try {
            var format=new AudioFormat(48000,16,2,true,false);
            line=AudioSystem.getSourceDataLine(format);line.open(format,4800*4);line.start();
            while(!closed) {
                byte[] data=queue.take();int frames=data.length/8;
                var floats=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
                byte[] pcm=new byte[frames*4];var output=ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
                for(int i=0;i<frames;i++){output.putShort((short)(floats.get(i)*32767));output.putShort((short)(floats.get(frames+i)*32767));}
                int offset=0;while(!closed&&offset<pcm.length)offset+=line.write(pcm,offset,pcm.length-offset);
            }
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        catch(Exception unavailable){WinLandCraftClient.LOGGER.warn("Local stream audio output unavailable",unavailable);}
        finally {closed=true;queue.clear();if(line!=null){line.stop();line.flush();line.close();}}
    }
    @Override public void close(){closed=true;queue.clear();worker.interrupt();}
}
