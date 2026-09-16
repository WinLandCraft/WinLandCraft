package dev.winlandcraft;

import java.util.ArrayDeque;

/** Bounded media queue shared by the FFmpeg sender and receiver. Audio is continuity-sensitive:
 *  when the latency bound is reached, stale video is discarded until a replacement keyframe while
 *  audio is preserved. Never accumulates unbounded delay. */
final class StreamQueue {
    private static final int MAX_PACKETS = 128, MAX_BYTES = 2_000_000;
    private final ArrayDeque<byte[]> packets = new ArrayDeque<>();
    private int bytes;
    private boolean needKey = true;
    private long offered, accepted, invalid, rejectedForKey, dropped, resets;

    synchronized boolean offer(byte[] packet) {
        var media = StreamMedia.header(packet);
        if (media == null) { invalid++; return false; }
        return offer(packet, media);
    }

    synchronized boolean offer(byte[] packet, StreamMedia.Header media) {
        offered++;
        if (bytes + packet.length > MAX_BYTES || packets.size() >= MAX_PACKETS) makeRoom(packet.length);
        if (media.kind() == StreamMedia.VIDEO && needKey) {
            if (!media.key()) { rejectedForKey++; return false; }
            needKey = false;
        }
        packets.add(packet);
        bytes += packet.length;
        accepted++;
        notifyAll();
        return true;
    }

    private void makeRoom(int incomingBytes) {
        int removedVideo = 0;
        for (var iterator = packets.iterator(); iterator.hasNext();) {
            byte[] packet = iterator.next();
            var media = StreamMedia.header(packet);
            if (media != null && media.kind() == StreamMedia.VIDEO) { iterator.remove(); bytes -= packet.length; removedVideo++; }
        }
        if (removedVideo > 0) { dropped += removedVideo; needKey = true; resets++; }
        while (!packets.isEmpty() && (bytes + incomingBytes > MAX_BYTES || packets.size() >= MAX_PACKETS)) {
            byte[] packet = packets.remove();
            bytes -= packet.length;
            dropped++;
        }
    }

    synchronized byte[] poll() {
        var packet = packets.poll();
        if (packet != null) bytes -= packet.length;
        return packet;
    }

    synchronized byte[] poll(long waitMillis) {
        long deadline = System.nanoTime() + waitMillis * 1_000_000L;
        while (packets.isEmpty() && waitMillis > 0) {
            try { wait(waitMillis); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return null; }
            waitMillis = Math.max(0, (deadline - System.nanoTime()) / 1_000_000L);
        }
        return poll();
    }

    synchronized void clear() { dropped += packets.size(); packets.clear(); bytes = 0; needKey = true; resets++; notifyAll(); }
    synchronized Stats stats() { return new Stats(offered, accepted, invalid, rejectedForKey, dropped, resets, packets.size(), bytes, needKey); }
    record Stats(long offered, long accepted, long invalid, long rejectedForKey, long dropped, long resets, int queued, int bytes, boolean needKey) {}
}
