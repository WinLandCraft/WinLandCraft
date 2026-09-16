package dev.winlandcraft;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVChannelLayout;
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

/** FFmpeg sender: GPU-preferred H.264 video plus Opus audio into bounded StreamMedia packets.
 *  Capture still arrives as top-down RGBA; only the newest pending frame is kept. All FFmpeg
 *  calls for one session run on its worker threads; the render thread only hands over pixels. */
final class StreamEncoder implements AutoCloseable {
    static final int AUDIO_FRAME_SAMPLES = 960;
    private static final long STARTUP_TIMEOUT_MILLIS = 15_000;
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    private record VideoCandidate(String encoder, String label, boolean software) {}
    private static final VideoCandidate[] CANDIDATES = {
            new VideoCandidate("h264_nvenc", "NVENC", false),
            new VideoCandidate("h264_amf", "AMF", false),
            new VideoCandidate("h264_qsv", "QSV", false),
            new VideoCandidate("h264_videotoolbox", "VideoToolbox", false),
            new VideoCandidate("libx264", "x264", true),
    };

    final int id = NEXT_ID.incrementAndGet();
    /** 0 auto, 1 prefer GPU, 2 force CPU. Snapshotted at creation; changes rotate the session. */
    final int codecMode;
    final StreamQueue encoded = new StreamQueue();
    volatile StreamQuality quality = StreamQuality.current();
    volatile boolean ready, closed;
    volatile String error = "", videoCodec = "n/a", videoAcceleration = "n/a";
    private final long startedNanos = System.nanoTime();
    private final long startedMillis = System.currentTimeMillis();
    private long nextHealth;
    private String lastHealthState = "";

    private final Object videoLock = new Object();
    private RawVideo pendingVideo;
    private record RawVideo(StreamCapture.Pixels pixels, long timeUs) {}

    private final Object audioLock = new Object();
    private final AudioFifo audioFifo = new AudioFifo();
    private long nextAudioTimeUs = -1;

    private final Set<String> blockedVideo = new HashSet<>();
    private String videoFailure = "";
    private VideoSession video;
    private AudioSession audio;
    private Thread videoThread, audioThread;

    private long rawVideoFrames, rawVideoReplaced, rawAudioPackets, rawAudioDropped;
    private long workerVideoIn, workerVideoOut, workerVideoDrop, workerAudioIn, workerAudioOut, workerAudioDrop, workerBytes, workerResets;

    StreamEncoder() {
        this.codecMode = ModSettings.streamCodecMode;
        Ffmpeg.require();
        videoThread = Thread.ofVirtual().name("WinLandCraft stream video encode #" + id).start(this::runVideo);
        audioThread = Thread.ofVirtual().name("WinLandCraft stream audio encode #" + id).start(this::runAudio);
    }

    long elapsedTimeUs() { return Math.max(0, (System.nanoTime() - startedNanos) / 1_000); }

    /** True while the mailbox is empty. Deliberately ignores {@link #ready}: the first frame
     *  is what lets the worker open the codec and become ready. Gating on ready deadlocks. */
    boolean wantsVideo() { synchronized (videoLock) { return !closed && error.isEmpty() && pendingVideo == null; } }

    /** Takes ownership of the pixels in all cases. */
    void video(StreamCapture.Pixels pixels) {
        synchronized (videoLock) {
            if (closed || !error.isEmpty()) { pixels.close(); return; }
            rawVideoFrames++;
            var previous = pendingVideo;
            pendingVideo = new RawVideo(pixels, pixels.timeUs());
            if (previous != null) { previous.pixels().close(); rawVideoReplaced++; }
            videoLock.notifyAll();
        }
    }

    private RawVideo takeVideo(long waitMillis) {
        synchronized (videoLock) {
            if (pendingVideo == null && !closed && error.isEmpty()) {
                try { videoLock.wait(waitMillis); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            var raw = pendingVideo;
            pendingVideo = null;
            return raw;
        }
    }

    /** Consumes one shared audio-packet owner in all cases. */
    void audio(StreamAudio.Packet packet, int frames, long timeUs) {
        synchronized (audioLock) {
            if (closed || !quality.audio()) { packet.release(); return; }
            long current = elapsedTimeUs();
            if (timeUs < current - 1_000_000 || timeUs > current + 1_000_000) timeUs = current;
            rawAudioPackets++;
            if (!audioFifo.append(packet.data(), frames, timeUs)) rawAudioDropped++;
            packet.release();
            audioLock.notifyAll();
        }
    }

    private void runVideo() {
        while (!closed && error.isEmpty()) {
            var raw = takeVideo(1_000);
            if (raw == null) continue;
            try (var pixels = raw.pixels()) {
                workerVideoIn++;
                try {
                    encodeVideo(pixels.rgba(), pixels.width(), pixels.height(), raw.timeUs());
                } catch (IllegalStateException fatal) {
                    workerVideoDrop++;
                    onVideoFailure(fatal.getMessage());
                }
            }
        }
        closeVideo();
    }

    private void onVideoFailure(String message) {
        videoFailure = message;
        workerResets++;
        closeVideo();
        if (codecMode != 0 || blockedVideo.size() >= CANDIDATES.length) {
            error = message + ". Choose another encoder in the pill.";
            WinLandCraftClient.LOGGER.warn("Stream video encoder failed: {}", message);
        } else {
            WinLandCraftClient.LOGGER.warn("Stream video encoder failed, probing next candidate: {}", message);
        }
    }

    private void encodeVideo(byte[] rgba, int width, int height, long timeUs) {
        var q = quality;
        String signature = width + ":" + height + ":" + (q.kbps() * 1000) + ":" + q.fps() + ":" + codecMode;
        if (video == null || !video.signature.equals(signature)) {
            closeVideo();
            video = openVideo(width, height, q.kbps() * 1000, q.fps(), signature);
            ready = true;
        }
        video.encode(rgba, width, height, timeUs);
    }

    private VideoSession openVideo(int width, int height, int bitrate, int fps, String signature) {
        var probes = new StringBuilder();
        for (var candidate : CANDIDATES) {
            if (codecMode == 1 && candidate.software()) continue;
            if (codecMode == 2 && !candidate.software()) continue;
            if (blockedVideo.contains(candidate.encoder())) { probes.append(candidate.encoder()).append("=blocked,"); continue; }
            AVCodec codec = avcodec.avcodec_find_encoder_by_name(candidate.encoder());
            if (codec == null || codec.isNull()) { probes.append(candidate.encoder()).append("=missing,"); continue; }
            try {
                var session = new VideoSession(codec, candidate, width, height, bitrate, fps, signature);
                videoCodec = "H.264";
                videoAcceleration = candidate.label();
                videoFailure = "";
                // A working open clears past transient failures so the next quality
                // change retries the preferred candidate instead of skipping it.
                blockedVideo.clear();
                WinLandCraftClient.LOGGER.info("Stream video encoder #{}: {} {}x{} @ {} kbps, {} fps (probes {})",
                        id, candidate.label(), width, height, bitrate / 1000, fps, probes);
                return session;
            } catch (IllegalStateException failed) {
                blockedVideo.add(candidate.encoder());
                probes.append(candidate.encoder()).append('=').append(failed.getMessage()).append(',');
            }
        }
        String detail = probes.length() == 0 ? "no candidates" : probes.toString();
        throw new IllegalStateException("No supported H.264 video encoder is available (" + detail + ")");
    }

    private void closeVideo() {
        if (video != null) { video.close(); video = null; }
    }

    private void runAudio() {
        var pcm = new float[AUDIO_FRAME_SAMPLES];
        while (!closed && error.isEmpty()) {
            long frameTime;
            synchronized (audioLock) {
                while (audioFifo.available() < AUDIO_FRAME_SAMPLES && !closed && error.isEmpty()) {
                    try { audioLock.wait(1_000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                }
                if (closed || !error.isEmpty()) return;
                frameTime = audioFifo.take(pcm);
                workerAudioIn++;
            }
            try {
                encodeAudio(pcm, frameTime);
            } catch (IllegalStateException fatal) {
                workerAudioDrop++;
                WinLandCraftClient.LOGGER.warn("Stream audio encoder failed, reopening: {}", fatal.getMessage());
                closeAudio();
            }
        }
        closeAudio();
    }

    private void encodeAudio(float[] pcm, long timeUs) {
        var q = quality;
        if (!q.audio()) return;
        String signature = (q.audioKbps() * 1000) + ":" + codecMode;
        if (audio == null || !audio.signature.equals(signature)) {
            closeAudio();
            audio = openAudio(q.audioKbps() * 1000, signature);
        }
        audio.encode(pcm, timeUs);
    }

    private AudioSession openAudio(int bitrate, String signature) {
        AVCodec codec = avcodec.avcodec_find_encoder_by_name("libopus");
        if (codec == null || codec.isNull()) codec = avcodec.avcodec_find_encoder_by_name("opus");
        if (codec == null || codec.isNull()) throw new IllegalStateException("Opus audio encoding is unavailable");
        // Probe the sample format by opening: encoders reject unsupported formats with EINVAL.
        int[] order = {avutil.AV_SAMPLE_FMT_FLTP, avutil.AV_SAMPLE_FMT_S16, avutil.AV_SAMPLE_FMT_FLT};
        IllegalStateException last = null;
        for (int format : order) {
            try {
                return new AudioSession(codec, bitrate, signature, format);
            } catch (IllegalStateException failed) {
                last = failed;
            }
        }
        throw new IllegalStateException("Opus audio encoding is unavailable", last);
    }

    private void closeAudio() {
        if (audio != null) { audio.close(); audio = null; }
        nextAudioTimeUs = -1;
    }

    void tick() {
        if (closed) return;
        long now = System.currentTimeMillis();
        if (!ready && now - startedMillis > STARTUP_TIMEOUT_MILLIS && error.isEmpty())
            error = "Stream encoder failed to start";
        String state = ready + "/" + videoCodec + "/" + videoAcceleration + "/" + error;
        if (!state.equals(lastHealthState) || now >= nextHealth) { logHealth("health"); lastHealthState = state; nextHealth = now + 10_000; }
    }

    void logHealth(String event) {
        var queue = encoded.stats();
        WinLandCraftClient.LOGGER.info("Stream encoder {} #{} (FFmpeg {}): ready={}, codec={}, acceleration={}, error='{}', video={}/{} drop={}, audio={}/{} drop={}, bytes={}, resets={}, raw video={} replaced={}, raw audio={} drop={}, outgoing={} queued/{} accepted/{} dropped/{} key-wait/{} reset",
                event, id, Ffmpeg.version(), ready, videoCodec, videoAcceleration, error,
                workerVideoIn, workerVideoOut, workerVideoDrop, workerAudioIn, workerAudioOut, workerAudioDrop,
                workerBytes, workerResets, rawVideoFrames, rawVideoReplaced, rawAudioPackets, rawAudioDropped,
                queue.queued(), queue.accepted(), queue.dropped(), queue.rejectedForKey(), queue.resets());
    }

    @Override public void close() {
        RawVideo pending;
        synchronized (videoLock) {
            if (closed) return;
            closed = true;
            pending = pendingVideo;
            pendingVideo = null;
            videoLock.notifyAll();
        }
        synchronized (audioLock) { audioLock.notifyAll(); }
        if (pending != null) pending.pixels().close();
        if (videoThread != null) videoThread.interrupt();
        if (audioThread != null) audioThread.interrupt();
        try {
            if (videoThread != null) videoThread.join(5_000);
            if (audioThread != null) audioThread.join(5_000);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        closeVideo();
        closeAudio();
        encoded.clear();
        logHealth("closing");
    }

    private final class VideoSession implements AutoCloseable {
        final String signature;
        private final AVCodecContext context;
        private final AVFrame source, target;
        private final SwsContext scaler;
        private final BytePointer rgbaBuffer;
        private final PointerPointer srcPointers = new PointerPointer(1);
        private final IntPointer srcStride = new IntPointer(1);
        private final int width, height;
        private boolean needKey = true;
        private long keyAtUs = Long.MIN_VALUE;

        VideoSession(AVCodec codec, VideoCandidate candidate, int width, int height, int bitrate, int fps, String signature) {
            this.signature = signature;
            this.width = width;
            this.height = height;
            context = avcodec.avcodec_alloc_context3(codec);
            if (context == null || context.isNull()) throw new IllegalStateException("could not allocate " + candidate.encoder());
            try {
                context.width(width);
                context.height(height);
                context.time_base().num(1);
                context.time_base().den(1_000_000);
                context.framerate().num(Math.max(1, fps));
                context.framerate().den(1);
                context.gop_size(Math.max(1, fps));
                context.max_b_frames(0);
                context.pix_fmt(avutil.AV_PIX_FMT_YUV420P);
                context.bit_rate(bitrate);
                var options = new AVDictionary();
                try {
                    switch (candidate.encoder()) {
                        case "h264_nvenc" -> { avutil.av_dict_set(options, "preset", "p1", 0); avutil.av_dict_set(options, "tune", "ll", 0); }
                        case "h264_amf" -> avutil.av_dict_set(options, "quality", "speed", 0);
                        case "h264_qsv" -> avutil.av_dict_set(options, "preset", "veryfast", 0);
                        case "libx264" -> { avutil.av_dict_set(options, "preset", "superfast", 0); avutil.av_dict_set(options, "tune", "zerolatency", 0); }
                        default -> {}
                    }
                    Ffmpeg.check(avcodec.avcodec_open2(context, codec, options), "open " + candidate.encoder());
                } finally {
                    avutil.av_dict_free(options);
                }
                target = avutil.av_frame_alloc();
                target.width(width);
                target.height(height);
                target.format(avutil.AV_PIX_FMT_YUV420P);
                Ffmpeg.check(avutil.av_frame_get_buffer(target, 0), "allocate " + candidate.encoder() + " frame");
                source = avutil.av_frame_alloc();
                source.width(width);
                source.height(height);
                source.format(avutil.AV_PIX_FMT_RGBA);
                scaler = swscale.sws_getContext(width, height, avutil.AV_PIX_FMT_RGBA, width, height,
                        avutil.AV_PIX_FMT_YUV420P, swscale.SWS_BILINEAR, null, null, (org.bytedeco.javacpp.DoublePointer) null);
                if (scaler == null || scaler.isNull()) throw new IllegalStateException("could not create pixel converter");
                rgbaBuffer = new BytePointer((long) width * height * 4);
            } catch (RuntimeException failure) {
                close();
                throw failure instanceof IllegalStateException illegal ? illegal : new IllegalStateException(failure.getMessage(), failure);
            }
        }

        void encode(byte[] rgba, int width, int height, long timeUs) {
            rgbaBuffer.put(rgba, 0, width * height * 4);
            source.data(0, rgbaBuffer);
            source.linesize(0, width * 4);
            // NB: never wrap a buffer with new PointerPointer(pointer): the view inherits the
            // buffer's byte capacity and crashes swscale. Use an explicit one-slot array instead.
            srcPointers.put(0, rgbaBuffer);
            srcStride.put(0, width * 4);
            if (swscale.sws_scale(scaler, srcPointers, srcStride,
                    0, height, target.data(), target.linesize()) <= 0)
                throw new IllegalStateException("pixel conversion produced no output");
            boolean key = needKey || keyAtUs == Long.MIN_VALUE || timeUs - keyAtUs >= 1_000_000;
            target.pts(timeUs);
            target.pict_type(key ? avutil.AV_PICTURE_TYPE_I : avutil.AV_PICTURE_TYPE_NONE);
            int send = avcodec.avcodec_send_frame(context, target);
            if (send == avutil.AVERROR_EAGAIN()) {
                drain(false);
                Ffmpeg.check(avcodec.avcodec_send_frame(context, target), "send video frame");
            } else Ffmpeg.check(send, "send video frame");
            drain(key);
            if (key) { keyAtUs = timeUs; needKey = false; }
        }

        private void drain(boolean key) {
            var packet = avcodec.av_packet_alloc();
            try {
                while (avcodec.avcodec_receive_packet(context, packet) == 0) {
                    try {
                        boolean packetKey = (packet.flags() & avcodec.AV_PKT_FLAG_KEY) != 0;
                        int size = (int) packet.size();
                        if (size <= 0 || size > StreamProtocol.MAX_FRAME_BYTES) { workerVideoDrop++; continue; }
                        byte[] bytes = new byte[size];
                        packet.data().get(bytes, 0, size);
                        var media = new StreamMedia(StreamMedia.VIDEO, packetKey,
                                StreamMedia.H264, target.pts(), width, height, bytes).pack();
                        workerBytes += media.length;
                        if (encoded.offer(media)) workerVideoOut++;
                        else { workerVideoDrop++; needKey = true; }
                    } finally {
                        avcodec.av_packet_unref(packet);
                    }
                }
            } finally {
                avcodec.av_packet_free(packet);
            }
        }

        @Override public void close() {
            if (scaler != null && !scaler.isNull()) swscale.sws_freeContext(scaler);
            if (source != null && !source.isNull()) avutil.av_frame_free(source);
            if (target != null && !target.isNull()) avutil.av_frame_free(target);
            if (rgbaBuffer != null && !rgbaBuffer.isNull()) rgbaBuffer.deallocate();
            if (context != null && !context.isNull()) avcodec.avcodec_free_context(context);
        }
    }

    private final class AudioSession implements AutoCloseable {
        final String signature;
        private final AVCodecContext context;
        private final AVFrame frame;
        private final AVChannelLayout layout = new AVChannelLayout();
        private final boolean planarFloat, packedFloat;

        AudioSession(AVCodec codec, int bitrate, String signature, int format) {
            this.signature = signature;
            context = avcodec.avcodec_alloc_context3(codec);
            if (context == null || context.isNull()) throw new IllegalStateException("could not allocate Opus encoder");
            try {
                avutil.av_channel_layout_default(layout, 2);
                context.ch_layout(layout);
                context.sample_rate(StreamAudioResampler.OUTPUT_RATE);
                context.bit_rate(bitrate);
                context.time_base().num(1);
                context.time_base().den(StreamAudioResampler.OUTPUT_RATE);
                planarFloat = format == avutil.AV_SAMPLE_FMT_FLTP;
                packedFloat = format == avutil.AV_SAMPLE_FMT_FLT;
                context.sample_fmt(format);
                Ffmpeg.check(avcodec.avcodec_open2(context, codec, (AVDictionary) null), "open Opus encoder");
                frame = avutil.av_frame_alloc();
                frame.nb_samples(AUDIO_FRAME_SAMPLES);
                frame.format(format);
                frame.sample_rate(StreamAudioResampler.OUTPUT_RATE);
                avutil.av_channel_layout_default(frame.ch_layout(), 2);
                Ffmpeg.check(avutil.av_frame_get_buffer(frame, 0), "allocate Opus frame");
            } catch (RuntimeException failure) {
                close();
                throw failure instanceof IllegalStateException illegal ? illegal : new IllegalStateException(failure.getMessage(), failure);
            }
        }

        void encode(float[] pcm, long timeUs) {
            if (planarFloat) {
                new FloatPointer(frame.data(0)).put(pcm, 0, AUDIO_FRAME_SAMPLES);
                new FloatPointer(frame.data(1)).put(pcm, AUDIO_FRAME_SAMPLES, AUDIO_FRAME_SAMPLES);
            } else if (packedFloat) {
                var interleaved = new float[AUDIO_FRAME_SAMPLES * 2];
                for (int i = 0; i < AUDIO_FRAME_SAMPLES; i++) {
                    interleaved[i * 2] = pcm[i];
                    interleaved[i * 2 + 1] = pcm[AUDIO_FRAME_SAMPLES + i];
                }
                new FloatPointer(frame.data(0)).put(interleaved, 0, interleaved.length);
            } else {
                var interleaved = new short[AUDIO_FRAME_SAMPLES * 2];
                for (int i = 0; i < AUDIO_FRAME_SAMPLES; i++) {
                    interleaved[i * 2] = (short) Math.clamp(pcm[i] * 32767, -32768, 32767);
                    interleaved[i * 2 + 1] = (short) Math.clamp(pcm[AUDIO_FRAME_SAMPLES + i] * 32767, -32768, 32767);
                }
                new org.bytedeco.javacpp.ShortPointer(frame.data(0)).put(interleaved, 0, interleaved.length);
            }
            frame.pts(timeUs * StreamAudioResampler.OUTPUT_RATE / 1_000_000L);
            Ffmpeg.check(avcodec.avcodec_send_frame(context, frame), "send audio frame");
            var packet = avcodec.av_packet_alloc();
            try {
                while (avcodec.avcodec_receive_packet(context, packet) == 0) {
                    try {
                        int size = (int) packet.size();
                        if (size <= 0 || size > StreamProtocol.MAX_FRAME_BYTES) { workerAudioDrop++; continue; }
                        byte[] bytes = new byte[size];
                        packet.data().get(bytes, 0, size);
                        var media = new StreamMedia(StreamMedia.AUDIO, true, timeUs, 0, 0, bytes).pack();
                        workerBytes += media.length;
                        if (encoded.offer(media)) workerAudioOut++;
                        else workerAudioDrop++;
                    } finally {
                        avcodec.av_packet_unref(packet);
                    }
                }
            } finally {
                avcodec.av_packet_free(packet);
            }
        }

        @Override public void close() {
            if (frame != null && !frame.isNull()) avutil.av_frame_free(frame);
            if (context != null && !context.isNull()) avcodec.avcodec_free_context(context);
            if (!layout.isNull()) avutil.av_channel_layout_uninit(layout);
        }
    }

    /** Accumulates planar 48 kHz float PCM into fixed 20 ms takes with a continuous clock. */
    private static final class AudioFifo {
        private float[] left = new float[0], right = new float[0];
        private int start, length;
        private long baseTimeUs;

        synchronized boolean append(byte[] planar, int frames, long timeUs) {
            if (frames <= 0 || planar.length < frames * 8) return false;
            if (length == 0) baseTimeUs = timeUs;
            ensure(length + frames);
            var floats = java.nio.ByteBuffer.wrap(planar).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            for (int i = 0; i < frames; i++) {
                left[start + length + i] = floats.get(i);
                right[start + length + i] = floats.get(frames + i);
            }
            length += frames;
            return true;
        }

        synchronized int available() { return length; }

        /** Copies one take into {@code pcm} as [left..., right...] and returns its start time. */
        synchronized long take(float[] pcm) {
            long time = baseTimeUs;
            System.arraycopy(left, start, pcm, 0, AUDIO_FRAME_SAMPLES);
            System.arraycopy(right, start, pcm, AUDIO_FRAME_SAMPLES, AUDIO_FRAME_SAMPLES);
            start += AUDIO_FRAME_SAMPLES;
            length -= AUDIO_FRAME_SAMPLES;
            baseTimeUs += AUDIO_FRAME_SAMPLES * 1_000_000L / StreamAudioResampler.OUTPUT_RATE;
            if (length == 0) start = 0;
            return time;
        }

        private void ensure(int needed) {
            if (start + needed > left.length) {
                int capacity = Math.max(needed, Math.max(4096, left.length * 2));
                var l = new float[capacity];
                var r = new float[capacity];
                System.arraycopy(left, start, l, 0, length);
                System.arraycopy(right, start, r, 0, length);
                left = l;
                right = r;
                start = 0;
            }
        }
    }
}
