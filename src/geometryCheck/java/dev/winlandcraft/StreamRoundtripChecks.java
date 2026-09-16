package dev.winlandcraft;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

/** Headless drift check: static marker frames through the real FFmpeg sender and receiver.
 *  A stationary input must not come out sliding; the marker row offset must stay ~constant. */
final class StreamRoundtripChecks {
    private static final int WIDTH = 1280, HEIGHT = 720, MARKER_Y = 100;

    static void run() throws Exception {
        var pixelsCtor = (Constructor<?>) StreamCapture.Pixels.class.getDeclaredConstructors()[0];
        pixelsCtor.setAccessible(true);
        byte[] input = new byte[WIDTH * HEIGHT * 4];
        for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
            int o = (y * WIDTH + x) * 4;
            boolean marker = y >= MARKER_Y && y < MARKER_Y + 4;
            input[o] = (byte) (marker ? 255 : x * 64 / WIDTH);
            input[o + 1] = (byte) (marker ? 0 : y * 64 / HEIGHT);
            input[o + 2] = (byte) (marker ? 0 : 64);
            input[o + 3] = (byte) 255;
        }
        var encoder = new StreamEncoder();
        var decoder = new StreamDecoder();
        try {
            long timeUs = 1_000_000L;
            var received = new ArrayList<Long>();
            // Pace the producer in realtime so workers keep up; warm up, then measure.
            for (int frame = 0; frame < 400 && received.size() < 90; frame++) {
                var pixels = (StreamCapture.Pixels) pixelsCtor.newInstance(null, input.clone(), WIDTH, HEIGHT, timeUs);
                encoder.video(pixels);
                timeUs += 33_333L;
                drain(encoder, decoder, 300);
                var current = decoder.current;
                if (frame >= 30 && current != null && received.size() < 90
                        && (received.isEmpty() || current.sequence() != (received.get(received.size() - 1) >> 32))) {
                    int marker = markerRow(current.rgba(), current.width(), current.height());
                    received.add(current.sequence() << 32 | (marker & 0xffffffffL));
                }
                Thread.sleep(30);
            }
            check(received.size() >= 60, "roundtrip produced " + received.size() + " measured frames");
            int first = (int) (received.get(0) & 0xffffffffL);
            int min = first, max = first;
            for (long packed : received) {
                int marker = (int) (packed & 0xffffffffL);
                min = Math.min(min, marker);
                max = Math.max(max, marker);
            }
            System.out.println("Stream roundtrip: " + received.size() + " frames, marker y=" + first
                    + " drift=[" + min + ".." + max + "] (input y=" + MARKER_Y + ")");
            check(Math.abs(first - MARKER_Y) <= 4, "marker starts near input row " + MARKER_Y + " but was " + first);
            check(max - min <= 4, "decoded content slides vertically: marker drifted " + (max - min) + " rows");
        } finally {
            encoder.close();
            decoder.close();
        }
    }

    private static void drain(StreamEncoder encoder, StreamDecoder decoder, int budget) {
        for (int i = 0; i < budget; i++) {
            byte[] packet = encoder.encoded.poll();
            if (packet == null) break;
            decoder.receive(packet);
        }
    }

    /** First mostly-red row, or -1. Compression bleeds the marker slightly; threshold generously. */
    private static int markerRow(byte[] rgba, int width, int height) {
        for (int y = 0; y < height; y++) {
            int red = 0;
            for (int x = 0; x < width; x += 7) {
                int o = (y * width + x) * 4;
                int r = rgba[o] & 255, g = rgba[o + 1] & 255, b = rgba[o + 2] & 255;
                if (r > 180 && g < 110 && b < 110) red++;
            }
            if (red > width / 14) return y;
        }
        return -1;
    }

    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
