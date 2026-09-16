package dev.winlandcraft;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.javacpp.ShortPointer;

/** FFmpeg receiver: decodes H.264/VP9 video plus Opus audio from bounded StreamMedia packets.
 *  Video publishes the newest decoded frame for render-thread GL upload; audio plays through a
 *  bounded Java Sound monitor. One worker thread owns all FFmpeg state. */
final class StreamDecoder implements AutoCloseable {
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    /** Newest decoded top-down RGBA frame. The decoder never reuses a published buffer. */
    record Frame(byte[] rgba, int width, int height, long sequence) {}

    final int id = NEXT_ID.incrementAndGet();
    final StreamQueue incoming = new StreamQueue();
    volatile Frame current;
    volatile boolean ready;
    volatile boolean closed;
    volatile String error = "", phase = "created", audioState = "n/a", videoCodec = "n/a", videoAcceleration = "SW";
    private final long startedNanos = System.nanoTime();
    private long nextHealth;
    private String lastHealthState = "";

    private final StreamAudioMonitor monitor = new StreamAudioMonitor();
    private final ArrayDeque<byte[]> framePool = new ArrayDeque<>();
    private VideoSession video;
    private AudioSession audio;
    private boolean needKey = true;
    private long lastVideoTimeUs, frameSequence;
    private Thread worker;
    private long workerVideoIn, workerVideoOut, workerVideoDrop, workerAudioIn, workerAudioOut, workerAudioDrop, workerBytes, workerResets, workerRendered;

    StreamDecoder() {
        Ffmpeg.require();
        worker = Thread.ofVirtual().name("WinLandCraft stream decode #" + id).start(this::run);
    }

    /** Validates an envelope and queues it for the worker. Returns its header, or null. */
    synchronized StreamMedia.Header receive(byte[] packet) {
        var media = StreamMedia.header(packet);
        if (media == null || closed) return null;
        incoming.offer(packet, media);
        return media;
    }

    private void run() {
        phase = "decoding";
        ready = true;
        while (!closed && error.isEmpty()) {
            byte[] packet = incoming.poll(1_000);
            if (packet == null) continue;
            var media = StreamMedia.read(packet);
            if (media == null) continue;
            workerBytes += packet.length;
            try {
                if (media.kind() == StreamMedia.VIDEO) decodeVideo(media);
                else decodeAudio(media);
            } catch (IllegalStateException fatal) {
                WinLandCraftClient.LOGGER.warn("Stream decode failed, resetting codec: {}", fatal.getMessage());
                workerResets++;
                closeVideo();
                closeAudio();
                needKey = true;
            }
        }
        closeVideo();
        closeAudio();
    }

    private void decodeVideo(StreamMedia media) {
        workerVideoIn++;
        if (media.width() < 2 || media.height() < 2) { workerVideoDrop++; return; }
        if (media.timeUs() - lastVideoTimeUs > 2_000_000 && lastVideoTimeUs != 0) {
            needKey = true;
            workerVideoDrop++;
            WinLandCraftClient.LOGGER.info("Stream video timestamp gap exceeded two seconds; waiting for keyframe");
        }
        lastVideoTimeUs = media.timeUs();
        if (needKey && !media.key()) { workerVideoDrop++; return; }
        String signature = media.codec() + ":" + media.width() + ":" + media.height();
        if (video == null || !video.signature.equals(signature) || needKey && !media.key()) {
            if (!media.key()) { workerVideoDrop++; return; }
            closeVideo();
            video = openVideo(media.codec(), media.width(), media.height(), signature);
            needKey = false;
        }
        if (video.decode(media.data(), media.timeUs(), ++frameSequence)) {
            needKey = false;
            workerVideoOut++;
            workerRendered++;
        } else workerVideoDrop++;
    }

    private VideoSession openVideo(int codec, int width, int height, String signature) {
        String name = switch (codec) {
            case StreamMedia.H264 -> "h264";
            case StreamMedia.VP9 -> "vp9";
            default -> throw new IllegalStateException("Unsupported video codec id " + codec);
        };
        AVCodec found = avcodec.avcodec_find_decoder_by_name(name);
        if (found == null || found.isNull()) throw new IllegalStateException("No " + name + " video decoder is available");
        videoCodec = codec == StreamMedia.H264 ? "H.264" : "VP9";
        WinLandCraftClient.LOGGER.info("Stream decoder #{}: {} {}x{}", id, videoCodec, width, height);
        return new VideoSession(found, width, height, signature);
    }

    private void closeVideo() {
        if (video != null) { video.close(); video = null; }
    }

    private void decodeAudio(StreamMedia media) {
        workerAudioIn++;
        if (audio == null) audio = openAudio();
        if (audio.decode(media.data(), media.timeUs())) {
            workerAudioOut++;
            audioState = "running";
        } else workerAudioDrop++;
    }

    private AudioSession openAudio() {
        AVCodec codec = avcodec.avcodec_find_decoder_by_name("opus");
        if (codec == null || codec.isNull()) codec = avcodec.avcodec_find_decoder_by_name("libopus");
        if (codec == null || codec.isNull()) throw new IllegalStateException("Opus audio decoding is unavailable");
        return new AudioSession(codec);
    }

    private void closeAudio() {
        if (audio != null) { audio.close(); audio = null; }
    }

    /** Returns a fully-copied frame buffer for reuse. Only these enter the pool. */
    void releaseFrame(byte[] rgba) {
        if (rgba == null) return;
        synchronized (framePool) {
            for (var candidate : framePool) if (candidate == rgba) return;
            if (framePool.size() < 3) framePool.offer(rgba);
        }
    }

    void tick() {
        if (closed) return;
        long now = System.currentTimeMillis();
        String state = ready + "/" + phase + "/" + audioState + "/" + error;
        if (!state.equals(lastHealthState) || now >= nextHealth) { logHealth("health"); lastHealthState = state; nextHealth = now + 10_000; }
    }

    void logHealth(String event) {
        var queue = incoming.stats();
        WinLandCraftClient.LOGGER.info("Stream decoder {} #{} (FFmpeg {}): ready={}, phase={}, codec={}, audio={}, error='{}', video={}/{} drop={}, audio={}/{} drop={}, rendered={}, bytes={}, resets={}, incoming={} queued/{} accepted/{} dropped/{} key-wait/{} reset",
                event, id, Ffmpeg.version(), ready, phase, videoCodec, audioState, error,
                workerVideoIn, workerVideoOut, workerVideoDrop, workerAudioIn, workerAudioOut, workerAudioDrop,
                workerRendered, workerBytes, workerResets,
                queue.queued(), queue.accepted(), queue.dropped(), queue.rejectedForKey(), queue.resets());
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        logHealth("closing");
        incoming.clear();
        if (worker != null) worker.interrupt();
        try { if (worker != null) worker.join(5_000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        closeVideo();
        closeAudio();
        monitor.close();
        synchronized (framePool) { framePool.clear(); }
    }

    private final class VideoSession implements AutoCloseable {
        final String signature;
        private final AVCodecContext context;
        private final AVFrame decoded, rgba;
        // Built lazily from the first decoded frame: a decoder context reports
        // AV_PIX_FMT_NONE until frames arrive, and building swscale on that
        // produces a converter that crashes native code on first use.
        private SwsContext scaler;
        private String scalerKey = "";
        private BytePointer packetBuffer, rgbaBuffer;
        private final int width, height;

        VideoSession(AVCodec codec, int width, int height, String signature) {
            this.signature = signature;
            this.width = width;
            this.height = height;
            context = avcodec.avcodec_alloc_context3(codec);
            if (context == null || context.isNull()) throw new IllegalStateException("could not allocate video decoder");
            packetBuffer = new BytePointer(0);
            rgbaBuffer = new BytePointer(0);
            AVFrame frames = null, target = null;
            try {
                Ffmpeg.check(avcodec.avcodec_open2(context, codec, (AVDictionary) null), "open video decoder");
                frames = avutil.av_frame_alloc();
                target = avutil.av_frame_alloc();
            } catch (RuntimeException failure) {
                if (frames != null && !frames.isNull()) avutil.av_frame_free(frames);
                if (target != null && !target.isNull()) avutil.av_frame_free(target);
                avcodec.avcodec_free_context(context);
                throw failure instanceof IllegalStateException illegal ? illegal : new IllegalStateException(failure.getMessage(), failure);
            }
            this.decoded = frames;
            this.rgba = target;
        }

        /** Returns true when a frame was rendered. */
        boolean decode(byte[] data, long timeUs, long sequence) {
            if (data.length > packetBuffer.capacity()) {
                packetBuffer.deallocate();
                packetBuffer = new BytePointer(data.length);
            }
            packetBuffer.put(data, 0, data.length);
            var packet = avcodec.av_packet_alloc();
            try {
                packet.data(packetBuffer);
                packet.size(data.length);
                int send = avcodec.avcodec_send_packet(context, packet);
                if (send < 0 && send != avutil.AVERROR_EAGAIN() && send != avutil.AVERROR_INVALIDDATA())
                    throw new IllegalStateException("send video packet: " + Ffmpeg.error(send));
                boolean rendered = false;
                while (avcodec.avcodec_receive_frame(context, decoded) == 0) {
                    try {
                        render(decoded, sequence);
                        rendered = true;
                    } finally {
                        avutil.av_frame_unref(decoded);
                    }
                }
                return rendered;
            } finally {
                avcodec.av_packet_unref(packet);
                avcodec.av_packet_free(packet);
            }
        }

        private void render(AVFrame frame, long sequence) {
            int w = frame.width(), h = frame.height();
            if (w != width || h != height) throw new IllegalStateException("Decoder dimensions changed mid-stream");
            String key = frame.format() + ":" + w + "x" + h;
            if (scaler == null || !key.equals(scalerKey)) {
                if (scaler != null && !scaler.isNull()) swscale.sws_freeContext(scaler);
                scaler = swscale.sws_getContext(w, h, frame.format(), w, h,
                        avutil.AV_PIX_FMT_RGBA, swscale.SWS_BILINEAR, null, null, (org.bytedeco.javacpp.DoublePointer) null);
                if (scaler == null || scaler.isNull()) { scaler = null; throw new IllegalStateException("could not create pixel converter"); }
                scalerKey = key;
            }
            int size = w * h * 4;
            if (rgbaBuffer.capacity() < size) {
                rgbaBuffer.deallocate();
                rgbaBuffer = new BytePointer(size);
            }
            rgba.width(w);
            rgba.height(h);
            rgba.format(avutil.AV_PIX_FMT_RGBA);
            rgba.data(0, rgbaBuffer);
            rgba.linesize(0, w * 4);
            swscale.sws_scale(scaler, frame.data(), frame.linesize(), 0, h, rgba.data(), rgba.linesize());
            byte[] bytes;
            synchronized (framePool) {
                bytes = null;
                for (var candidate : framePool) if (candidate.length == size) { bytes = candidate; break; }
                if (bytes != null) framePool.remove(bytes);
            }
            if (bytes == null) bytes = new byte[size];
            rgbaBuffer.get(bytes, 0, size);
            // The panel may still be copying the published buffer; only panel-released
            // buffers return to the pool (see releaseFrame), never the retired one.
            current = new Frame(bytes, w, h, sequence);
        }

        @Override public void close() {
            if (scaler != null && !scaler.isNull()) swscale.sws_freeContext(scaler);
            if (decoded != null && !decoded.isNull()) avutil.av_frame_free(decoded);
            if (rgba != null && !rgba.isNull()) avutil.av_frame_free(rgba);
            if (packetBuffer != null && !packetBuffer.isNull()) packetBuffer.deallocate();
            if (rgbaBuffer != null && !rgbaBuffer.isNull()) rgbaBuffer.deallocate();
            if (context != null && !context.isNull()) avcodec.avcodec_free_context(context);
        }
    }

    private final class AudioSession implements AutoCloseable {
        private final AVCodecContext context;
        private final AVFrame frame;

        AudioSession(AVCodec codec) {
            context = avcodec.avcodec_alloc_context3(codec);
            if (context == null || context.isNull()) throw new IllegalStateException("could not allocate Opus decoder");
            frame = avutil.av_frame_alloc();
            try {
                Ffmpeg.check(avcodec.avcodec_open2(context, codec, (AVDictionary) null), "open Opus decoder");
            } catch (RuntimeException failure) {
                avutil.av_frame_free(frame);
                avcodec.avcodec_free_context(context);
                throw failure instanceof IllegalStateException illegal ? illegal : new IllegalStateException(failure.getMessage(), failure);
            }
        }

        /** Returns true when audio played. */
        boolean decode(byte[] data, long timeUs) {
            var buffer = new BytePointer(data.length);
            buffer.put(data, 0, data.length);
            var packet = avcodec.av_packet_alloc();
            try {
                packet.data(buffer);
                packet.size(data.length);
                int send = avcodec.avcodec_send_packet(context, packet);
                if (send < 0 && send != avutil.AVERROR_EAGAIN() && send != avutil.AVERROR_INVALIDDATA())
                    throw new IllegalStateException("send audio packet: " + Ffmpeg.error(send));
                boolean played = false;
                while (avcodec.avcodec_receive_frame(context, frame) == 0) {
                    try {
                        play(frame);
                        played = true;
                    } finally {
                        avutil.av_frame_unref(frame);
                    }
                }
                return played;
            } finally {
                avcodec.av_packet_unref(packet);
                avcodec.av_packet_free(packet);
                buffer.deallocate();
            }
        }

        private void play(AVFrame decoded) {
            int frames = decoded.nb_samples();
            if (frames <= 0 || frames > 5760 || decoded.sample_rate() != StreamAudioResampler.OUTPUT_RATE) return;
            byte[] planar = new byte[frames * 8];
            var floats = java.nio.ByteBuffer.wrap(planar).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            int format = decoded.format();
            if (format == avutil.AV_SAMPLE_FMT_FLTP) {
                var left = new float[frames];
                var right = new float[frames];
                new FloatPointer(decoded.data(0)).get(left, 0, frames);
                new FloatPointer(decoded.data(1)).get(right, 0, frames);
                for (int i = 0; i < frames; i++) { floats.put(i, left[i]); floats.put(frames + i, right[i]); }
            } else if (format == avutil.AV_SAMPLE_FMT_FLT) {
                var interleaved = new float[frames * 2];
                new FloatPointer(decoded.data(0)).get(interleaved, 0, interleaved.length);
                for (int i = 0; i < frames; i++) {
                    floats.put(i, interleaved[i * 2]);
                    floats.put(frames + i, interleaved[i * 2 + 1]);
                }
            } else if (format == avutil.AV_SAMPLE_FMT_S16) {
                var interleaved = new short[frames * 2];
                new ShortPointer(decoded.data(0)).get(interleaved, 0, interleaved.length);
                for (int i = 0; i < frames; i++) {
                    floats.put(i, interleaved[i * 2] / 32768f);
                    floats.put(frames + i, interleaved[i * 2 + 1] / 32768f);
                }
            } else return;
            monitor.offer(StreamAudio.Packet.owned(planar));
        }

        @Override public void close() {
            if (frame != null && !frame.isNull()) avutil.av_frame_free(frame);
            if (context != null && !context.isNull()) avcodec.avcodec_free_context(context);
        }
    }
}
