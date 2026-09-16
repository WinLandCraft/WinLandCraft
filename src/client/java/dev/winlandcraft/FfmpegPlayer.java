package dev.winlandcraft;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVCodecHWConfig;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVBufferRef;
import org.bytedeco.ffmpeg.avutil.AVChannelLayout;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swresample;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.ffmpeg.swresample.SwrContext;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;

/** Local file player on the bundled FFmpeg: demux, hardware-preferred decode, A/V sync,
 *  seeking, and Java Sound audio. Plays files straight off disk; no server, no browser.
 *  Only the newest decoded frame is kept; the render thread uploads it to GL. */
final class FfmpegPlayer implements AutoCloseable {
    /** Newest decoded top-down RGBA frame. The worker never reuses a published buffer. */
    record Frame(byte[] rgba, int width, int height, long sequence) {}

    final Path file;
    volatile Frame current;
    volatile boolean ready, closed, eof;
    volatile String error = "";
    volatile boolean paused;
    volatile double volume = 1, durationSec;
    volatile boolean muted;
    volatile String hw = "SW";
    volatile int videoWidth, videoHeight;
    volatile boolean hasAudio;

    private final Object stateLock = new Object();
    private volatile boolean seekPending;
    /** Last worker parking spot, for stuck-playback diagnostics. */
    private volatile String workerState = "starting";
    private double seekTargetSec;
    /** Set by the worker on publish, cleared by the render thread after upload. While set,
     *  the worker drops incoming video instead of decoding it. */
    private volatile boolean fresh;
    private long wallStartNanos = System.nanoTime();
    private double audioFedSec, videoFedSec, fedBaseSec;

    private final ArrayDeque<byte[]> framePool = new ArrayDeque<>();
    private StreamAudioMonitor monitor;
    private Thread worker;
    private long frameSequence, decodedFrames, droppedFrames, clampedPts, audioPackets;
    private long nextHealth;

    FfmpegPlayer(Path file) {
        this.file = file.toAbsolutePath().normalize();
        Ffmpeg.require();
        worker = Thread.ofVirtual().name("WinLandCraft media player").start(this::run);
    }

    /** Last presented video timestamp. The render thread consumes the newest frame, so this
     *  only moves forward in normal playback and freezes the moment decoding stops. */
    volatile double lastVideoPtsSec;

    double timeSec() { return Math.max(0, lastVideoPtsSec); }

    void play() {
        synchronized (stateLock) {
            if (closed) return;
            if (eof) { seekLocked(0); }
            if (paused) {
                paused = false;
                audioFedSec = videoFedSec = fedBaseSec = lastVideoPtsSec;
                wallStartNanos = System.nanoTime();
                stateLock.notifyAll();
            }
        }
    }

    void pause() {
        synchronized (stateLock) {
            if (closed || paused || eof) return;
            paused = true;
            stateLock.notifyAll();
        }
    }

    void toggle() {
        synchronized (stateLock) {
            if (paused || eof) play();
            else pause();
        }
    }

    void seekFraction(double fraction) {
        if (durationSec > 0) seekTo(Math.clamp(fraction, 0, 1) * durationSec);
    }

    void seekBy(double deltaSec) {
        seekTo(timeSec() + deltaSec);
    }

    void seekTo(double seconds) {
        synchronized (stateLock) {
            if (closed) return;
            seekLocked(seconds);
        }
    }

    private void seekLocked(double seconds) {
        seekTargetSec = Math.max(0, durationSec > 0 ? Math.min(durationSec, seconds) : seconds);
        seekPending = true;
        eof = false;
        stateLock.notifyAll();
    }

    void setVolume(double volume) {
        this.volume = Math.clamp(volume, 0, 1);
        if (this.volume > 0) muted = false;
    }

    void toggleMute() { muted = !muted; }

    String debugState() {
        return "worker=" + workerState + " ready=" + ready + " paused=" + paused + " eof=" + eof
                + " closed=" + closed + " error=" + error + " time=" + timeSec() + "/" + durationSec
                + " decoded=" + decodedFrames + " dropped=" + droppedFrames + " audio=" + audioPackets;
    }

    /** Called by the render thread after uploading a new frame so the worker decodes the next one. */
    void markConsumed() { fresh = false; }

    /** Returns a fully-copied frame buffer for reuse. Only these enter the pool. */
    void releaseFrame(byte[] rgba) {
        if (rgba == null) return;
        synchronized (framePool) {
            for (var candidate : framePool) if (candidate == rgba) return;
            if (framePool.size() < 3) framePool.offer(rgba);
        }
    }

    private void run() {
        AVFormatContext format = null;
        try {
            if (!Files.isRegularFile(file)) throw new IllegalStateException("File not found: " + file.getFileName());
            format = new AVFormatContext(null);
            try {
                Ffmpeg.check(avformat.avformat_open_input(format, file.toString(), null, null), "open " + file.getFileName());
            } catch (IllegalStateException failure) {
                try { avformat.avformat_close_input(format); } catch (RuntimeException ignored) {}
                throw failure;
            }
            try {
                Ffmpeg.check(avformat.avformat_find_stream_info(format, (AVDictionary) null), "probe " + file.getFileName());
                playStream(format);
            } finally {
                avformat.avformat_close_input(format);
            }
        } catch (IllegalStateException failure) {
            if (!closed) {
                error = failure.getMessage();
                WinLandCraftClient.LOGGER.warn("Media player failed for {}: {}", file.getFileName(), failure.getMessage());
            }
        } finally {
            synchronized (stateLock) { closed = true; stateLock.notifyAll(); }
        }
    }

    private void playStream(AVFormatContext format) {
        long nanos = format.duration();
        durationSec = nanos != avutil.AV_NOPTS_VALUE() ? Math.max(0, nanos / (double) avutil.AV_TIME_BASE) : 0;
        int videoIndex = avformat.av_find_best_stream(format, avutil.AVMEDIA_TYPE_VIDEO, -1, -1, (PointerPointer) null, 0);
        if (videoIndex < 0) throw new IllegalStateException("No video stream in " + file.getFileName());
        int audioIndex = avformat.av_find_best_stream(format, avutil.AVMEDIA_TYPE_AUDIO, -1, videoIndex, (PointerPointer) null, 0);
        var session = new Session(format, videoIndex, audioIndex);
        try {
            ready = true;
            hw = session.hwName;
            videoWidth = session.width;
            videoHeight = session.height;
            hasAudio = session.hasAudio();
            WinLandCraftClient.LOGGER.info("Playing {}: {} {}x{}, {}s, audio={}, hw={}",
                    file.getFileName(), session.videoName, session.width, session.height,
                    String.format(java.util.Locale.ROOT, "%.1f", durationSec), session.hasAudio(), session.hwName);
            session.loop();
        } finally {
            session.close();
        }
    }

    private final class Session implements AutoCloseable {
        private final AVFormatContext format;
        private final int videoIndex, audioIndex;
        private final AVStream videoStream, audioStream;
        private final double videoUnit;
        private final String videoName;
        private String hwName = "SW";
        private final int width, height;
        private final AVCodecContext videoCtx, audioCtx;
        private final AVCodecContext.Get_format_AVCodecContext_IntPointer hwSelector;
        private final AVBufferRef hwDevice;
        private final int hwPixFmt;
        private SwsContext scaler;
        private String scalerKey = "";
        private final AVFrame decoded, rgba, audioFrame;
        private BytePointer rgbaBuffer = new BytePointer(0);
        private SwrContext swr;
        private final AVChannelLayout swrInLayout = new AVChannelLayout(), swrOutLayout = new AVChannelLayout();
        private BytePointer swrLeft = new BytePointer(0), swrRight = new BytePointer(0);
        private final PointerPointer swrOut = new PointerPointer(2);
        private final AVPacket packet = avcodec.av_packet_alloc();

        Session(AVFormatContext format, int videoIndex, int audioIndex) {
            this.format = format;
            this.videoIndex = videoIndex;
            this.audioIndex = audioIndex;
            videoStream = format.streams(videoIndex);
            var params = videoStream.codecpar();
            var videoCodec = avcodec.avcodec_find_decoder(params.codec_id());
            if (videoCodec == null || videoCodec.isNull()) throw new IllegalStateException("Unsupported video codec in " + file.getFileName());
            videoName = videoCodec.name().getString();
            var tb = videoStream.time_base();
            videoUnit = tb.num() / (double) tb.den();
            AVCodecContext vctx = avcodec.avcodec_alloc_context3(videoCodec);
            if (vctx == null || vctx.isNull()) throw new IllegalStateException("Could not allocate video decoder");
            AVBufferRef device = null;
            int pixFmt = 0;
            AVCodecContext.Get_format_AVCodecContext_IntPointer selector = null;
            try {
                Ffmpeg.check(avcodec.avcodec_parameters_to_context(vctx, params), "read video parameters");
                width = vctx.width();
                height = vctx.height();
                if (width < 2 || height < 2 || width > 8192 || height > 8192)
                    throw new IllegalStateException("Unsupported video dimensions " + width + "x" + height);
                // Prefer hardware decode; fall back to software below on any failure.
                var attempt = tryHardware(vctx, videoCodec);
                if (attempt != null) {
                    device = attempt.device();
                    pixFmt = attempt.pixFmt();
                    selector = attempt.selector();
                    vctx.hw_device_ctx(device);
                    vctx.get_format(selector);
                    hwName = attempt.name();
                }
                Ffmpeg.check(avcodec.avcodec_open2(vctx, videoCodec, (AVDictionary) null), "open video decoder");
            } catch (RuntimeException failure) {
                if (device != null && !device.isNull()) avutil.av_buffer_unref(device);
                avcodec.avcodec_free_context(vctx);
                throw failure instanceof IllegalStateException illegal ? illegal : new IllegalStateException(failure.getMessage(), failure);
            }
            videoCtx = vctx;
            hwDevice = device;
            hwPixFmt = pixFmt;
            hwSelector = selector;
            AVCodecContext actx = null;
            AVStream astream = null;
            if (audioIndex >= 0) {
                try {
                    astream = format.streams(audioIndex);
                    var aparams = astream.codecpar();
                    var acodec = avcodec.avcodec_find_decoder(aparams.codec_id());
                    if (acodec != null && !acodec.isNull()) {
                        actx = avcodec.avcodec_alloc_context3(acodec);
                        if (actx != null && !actx.isNull()) {
                            Ffmpeg.check(avcodec.avcodec_parameters_to_context(actx, aparams), "read audio parameters");
                            Ffmpeg.check(avcodec.avcodec_open2(actx, acodec, (AVDictionary) null), "open audio decoder");
                            openResampler(actx, astream);
                        } else actx = null;
                    }
                } catch (RuntimeException failure) {
                    WinLandCraftClient.LOGGER.warn("Media audio unavailable for {}: {}", file.getFileName(), failure.getMessage());
                    if (actx != null && !actx.isNull()) avcodec.avcodec_free_context(actx);
                    actx = null;
                    astream = null;
                }
            }
            audioCtx = actx;
            audioStream = actx != null ? astream : null;
            if (audioCtx != null && monitor == null) monitor = new StreamAudioMonitor(24);
            decoded = avutil.av_frame_alloc();
            rgba = avutil.av_frame_alloc();
            audioFrame = avutil.av_frame_alloc();
        }

        boolean hasAudio() { return audioCtx != null; }

        private record HwAttempt(AVBufferRef device, int pixFmt, AVCodecContext.Get_format_AVCodecContext_IntPointer selector, String name) {}

        private HwAttempt tryHardware(AVCodecContext context, AVCodec codec) {
            // Escape hatch for broken drivers (and headless bisecting): software decode.
            if (Boolean.getBoolean("winlandcraft.player.nohw")) return null;
            String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            int[] order = os.contains("win")
                    ? new int[]{avutil.AV_HWDEVICE_TYPE_D3D11VA, avutil.AV_HWDEVICE_TYPE_CUDA}
                    : os.contains("mac")
                    ? new int[]{avutil.AV_HWDEVICE_TYPE_VIDEOTOOLBOX}
                    : new int[]{avutil.AV_HWDEVICE_TYPE_VAAPI, avutil.AV_HWDEVICE_TYPE_CUDA};
            for (int type : order) {
                int pixFmt = hwPixFmt(codec, type);
                if (pixFmt == 0) continue;
                var ref = new AVBufferRef(null);
                String typeName = avutil.av_hwdevice_get_type_name(type).getString();
                if (avutil.av_hwdevice_ctx_create(ref, type, (String) null, null, 0) != 0) continue;
                var selector = new AVCodecContext.Get_format_AVCodecContext_IntPointer() {
                    @Override public int call(AVCodecContext ctx, IntPointer formats) {
                        for (int i = 0; ; i++) {
                            int format = formats.get(i);
                            if (format == avutil.AV_PIX_FMT_NONE) break;
                            if (format == pixFmt) return format;
                        }
                        return avutil.AV_PIX_FMT_NONE;
                    }
                };
                WinLandCraftClient.LOGGER.info("Media player trying hardware decode: {}", typeName);
                return new HwAttempt(ref, pixFmt, selector, typeName);
            }
            return null;
        }

        private int hwPixFmt(AVCodec codec, int type) {
            for (int i = 0; ; i++) {
                AVCodecHWConfig config = avcodec.avcodec_get_hw_config(codec, i);
                if (config == null || config.isNull()) return 0;
                if (config.device_type() == type
                        && (config.methods() & avcodec.AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX) != 0)
                    return config.pix_fmt();
            }
        }

        private void openResampler(AVCodecContext actx, AVStream astream) {
            var inParams = astream.codecpar();
            AVChannelLayout in = inParams.ch_layout();
            if (in == null || in.isNull()) {
                in = new AVChannelLayout();
                avutil.av_channel_layout_default(in, 2);
            }
            Ffmpeg.check(avutil.av_channel_layout_copy(swrInLayout, in), "copy audio layout");
            avutil.av_channel_layout_default(swrOutLayout, 2);
            swr = swresample.swr_alloc();
            Ffmpeg.check(swresample.swr_alloc_set_opts2(swr, swrOutLayout, avutil.AV_SAMPLE_FMT_FLTP,
                    StreamAudioResampler.OUTPUT_RATE, swrInLayout, actx.sample_fmt(), actx.sample_rate(), 0, null),
                    "configure audio resampler");
            Ffmpeg.check(swresample.swr_init(swr), "start audio resampler");
        }

        void loop() {
            wallStartNanos = System.nanoTime();
            while (true) {
                double seekTo = -1;
                workerState = "loop-top paused=" + paused + " seek=" + seekPending;
                synchronized (stateLock) {
                    while (paused && !seekPending && !closed && error.isEmpty()) {
                        try { stateLock.wait(50); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                    }
                    if (closed || !error.isEmpty()) return;
                    if (seekPending) { seekPending = false; seekTo = seekTargetSec; }
                }
                if (seekTo >= 0) { workerState = "seeking"; doSeek(seekTo); continue; }
                workerState = "reading";
                if (!readFrame()) return;
                workerState = "pacing";
                pace();
            }
        }

        /** Holds demux near realtime so playback (and the test) observes wall-clock
         *  progress instead of racing to EOF. Audio fed time leads; video follows.
         *  Base-relative: after a seek both bases restart at the target, so a far
         *  seek never parks the worker hours out (which locked video and audio). */
        private void pace() {
            while (!seekPending && !paused && !closed && error.isEmpty()) {
                double fed = hasAudio() ? Math.max(audioFedSec, videoFedSec) : videoFedSec;
                double ahead = (fed - fedBaseSec) - (System.nanoTime() - wallStartNanos) / 1_000_000_000.0;
                if (ahead <= 0.75) return;
                try { Thread.sleep(10); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            }
        }

        private void doSeek(double seconds) {
            var tb = videoStream.time_base();
            long target = (long) (seconds * tb.den() / (double) tb.num());
            int rc = avformat.av_seek_frame(format, videoIndex, target, avformat.AVSEEK_FLAG_BACKWARD);
            if (rc < 0) {
                WinLandCraftClient.LOGGER.warn("Media seek failed: {}", Ffmpeg.error(rc));
                return;
            }
            avcodec.avcodec_flush_buffers(videoCtx);
            if (audioCtx != null) {
                avcodec.avcodec_flush_buffers(audioCtx);
                resetAudio();
            }
            synchronized (stateLock) {
                lastVideoPtsSec = seconds;
                fresh = false;
                audioFedSec = videoFedSec = fedBaseSec = seconds;
                wallStartNanos = System.nanoTime();
                eof = false;
            }
        }

        private void resetAudio() {
            if (monitor != null) { monitor.close(); monitor = new StreamAudioMonitor(24); }
            if (swr != null && !swr.isNull()) swresample.swr_free(swr);
            swr = null;
            if (audioCtx != null) {
                try { if (audioStream != null) openResampler(audioCtx, audioStream); }
                catch (RuntimeException failure) { WinLandCraftClient.LOGGER.warn("Media audio reset failed: {}", failure.getMessage()); }
            }
        }

        /** Returns false when playback is done. */
        private boolean readFrame() {
            int rc = avformat.av_read_frame(format, packet);
            try {
                if (rc == avutil.AVERROR_EOF()) { drain(); return false; }
                if (rc < 0) {
                    WinLandCraftClient.LOGGER.warn("Media read failed: {}", Ffmpeg.error(rc));
                    return false;
                }
                if (packet.stream_index() == videoIndex) decodeVideo();
                else if (packet.stream_index() == audioIndex && audioCtx != null) decodeAudio();
                return true;
            } finally {
                avcodec.av_packet_unref(packet);
            }
        }

        private void drain() {
            Ffmpeg.check(avcodec.avcodec_send_packet(videoCtx, (AVPacket) null), "flush video");
            while (avcodec.avcodec_receive_frame(videoCtx, decoded) == 0) {
                try { present(decoded, ptsSec(decoded, videoStream)); }
                finally { avutil.av_frame_unref(decoded); }
            }
            eof = true;
            paused = true;
            WinLandCraftClient.LOGGER.info("Finished playing {}", file.getFileName());
        }

        private void decodeVideo() {
            // The panel shows the newest frame; when it hasn't consumed the last one yet,
            // drop instead of decoding. Pacing is handled separately in pace().
            if (fresh) { droppedFrames++; return; }
            int send = avcodec.avcodec_send_packet(videoCtx, packet);
            if (send < 0 && send != avutil.AVERROR_EAGAIN() && send != avutil.AVERROR_INVALIDDATA()) {
                WinLandCraftClient.LOGGER.warn("Media video packet rejected: {}", Ffmpeg.error(send));
                return;
            }
            while (avcodec.avcodec_receive_frame(videoCtx, decoded) == 0) {
                try {
                    present(decoded, ptsSec(decoded, videoStream));
                    if (fresh) break;
                } finally {
                    avutil.av_frame_unref(decoded);
                }
            }
        }

        private double ptsSec(AVFrame frame, AVStream stream) {
            long pts = frame.pts();
            if (pts == avutil.AV_NOPTS_VALUE()) return timeSec();
            var tb = stream.time_base();
            return sanePts(pts * tb.num() / (double) tb.den());
        }

        /** Broken container timestamps would park the worker hours out and starve audio
         *  on the same thread; play through instead of sleeping to them. */
        private double sanePts(double pts) {
            if (!Double.isFinite(pts) || pts < 0 || (durationSec > 0 && pts > durationSec + 60)) {
                clampedPts++;
                return timeSec();
            }
            return pts;
        }

        private void present(AVFrame frame, double pts) {
            AVFrame rgb = frame;
            AVFrame moved = null;
            try {
                if (hwPixFmt != 0 && frame.format() == hwPixFmt) {
                    moved = avutil.av_frame_alloc();
                    if (avutil.av_hwframe_transfer_data(moved, frame, 0) != 0 || moved.format() == hwPixFmt)
                        throw new IllegalStateException("hardware frame download failed");
                    rgb = moved;
                }
                int w = rgb.width(), h = rgb.height();
                if (w < 2 || h < 2 || w > 8192 || h > 8192) return;
                String key = rgb.format() + ":" + w + "x" + h;
                if (scaler == null || !key.equals(scalerKey)) {
                    if (scaler != null && !scaler.isNull()) swscale.sws_freeContext(scaler);
                    scaler = swscale.sws_getContext(w, h, rgb.format(), w, h,
                            avutil.AV_PIX_FMT_RGBA, swscale.SWS_BILINEAR, null, null, (org.bytedeco.javacpp.DoublePointer) null);
                    if (scaler == null || scaler.isNull()) { scaler = null; throw new IllegalStateException("pixel converter failed"); }
                    scalerKey = key;
                }
                rgba.width(w);
                rgba.height(h);
                rgba.format(avutil.AV_PIX_FMT_RGBA);
                int size = w * h * 4;
                if (rgbaBuffer.capacity() < size) {
                    rgbaBuffer.deallocate();
                    rgbaBuffer = new BytePointer(size);
                }
                rgba.data(0, rgbaBuffer);
                rgba.linesize(0, w * 4);
                if (swscale.sws_scale(scaler, rgb.data(), rgb.linesize(), 0, h, rgba.data(), rgba.linesize()) <= 0) return;
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
                current = new Frame(bytes, w, h, ++frameSequence);
                decodedFrames++;
                lastVideoPtsSec = pts;
                videoFedSec = Math.max(videoFedSec, pts);
                fresh = true;
            } finally {
                if (moved != null && !moved.isNull()) avutil.av_frame_free(moved);
            }
        }

        private void decodeAudio() {
            int send = avcodec.avcodec_send_packet(audioCtx, packet);
            if (send < 0 && send != avutil.AVERROR_EAGAIN() && send != avutil.AVERROR_INVALIDDATA()) return;
            while (avcodec.avcodec_receive_frame(audioCtx, audioFrame) == 0) {
                try {
                    convertAudio(audioFrame);
                } finally {
                    avutil.av_frame_unref(audioFrame);
                }
            }
        }

        private void convertAudio(AVFrame decoded) {
            if (muted || monitor == null || swr == null || swr.isNull()) return;
            int inSamples = decoded.nb_samples();
            if (inSamples <= 0 || inSamples > 16384) return;
            if (decoded.sample_rate() != audioCtx.sample_rate()) return;
            int capacity = inSamples * StreamAudioResampler.OUTPUT_RATE / Math.max(1, audioCtx.sample_rate()) + 256;
            long need = (long) capacity * 4;
            if (swrLeft.capacity() < need) {
                swrLeft.deallocate();
                swrRight.deallocate();
                swrLeft = new BytePointer(need);
                swrRight = new BytePointer(need);
            }
            swrOut.put(0, swrLeft);
            swrOut.put(1, swrRight);
            int outSamples = swresample.swr_convert(swr, swrOut, capacity, decoded.data(), inSamples);
            if (outSamples <= 0) return;
            double gain = muted ? 0 : volume;
            byte[] planar = new byte[outSamples * 8];
            var floats = ByteBuffer.wrap(planar).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            var left = new float[outSamples];
            var right = new float[outSamples];
            new FloatPointer(swrLeft).get(left, 0, outSamples);
            new FloatPointer(swrRight).get(right, 0, outSamples);
            for (int i = 0; i < outSamples; i++) {
                floats.put(i, (float) (left[i] * gain));
                floats.put(outSamples + i, (float) (right[i] * gain));
            }
            monitor.offer(StreamAudio.Packet.owned(planar));
            audioPackets++;
            audioFedSec += outSamples / (double) StreamAudioResampler.OUTPUT_RATE;
        }

        @Override public void close() {
            if (scaler != null && !scaler.isNull()) swscale.sws_freeContext(scaler);
            if (swr != null && !swr.isNull()) swresample.swr_free(swr);
            if (!swrInLayout.isNull()) avutil.av_channel_layout_uninit(swrInLayout);
            if (!swrOutLayout.isNull()) avutil.av_channel_layout_uninit(swrOutLayout);
            if (swrLeft.capacity() > 0) swrLeft.deallocate();
            if (swrRight.capacity() > 0) swrRight.deallocate();
            if (decoded != null && !decoded.isNull()) avutil.av_frame_free(decoded);
            if (rgba != null && !rgba.isNull()) avutil.av_frame_free(rgba);
            if (audioFrame != null && !audioFrame.isNull()) avutil.av_frame_free(audioFrame);
            if (!rgbaBuffer.isNull()) rgbaBuffer.deallocate();
            if (packet != null && !packet.isNull()) avcodec.av_packet_free(packet);
            if (videoCtx != null && !videoCtx.isNull()) avcodec.avcodec_free_context(videoCtx);
            if (audioCtx != null && !audioCtx.isNull()) avcodec.avcodec_free_context(audioCtx);
            // The codec context consumes the hardware device reference on free; unreferencing
            // hwDevice here as well double-frees because the hw_device_ctx setter copies the
            // struct without bumping its reference count (proven by heap corruption, not theory).
        }
    }

    void tick() {
        if (closed) return;
        long now = System.currentTimeMillis();
        if (now >= nextHealth) {
            nextHealth = now + 30_000;
            WinLandCraftClient.LOGGER.info("Media player {}: ready={}, hw={}, video={}x{}, decoded={}, dropped={}+{}clamped, audioPackets={}, time={}s, error='{}'",
                    file.getFileName(), ready, hw, videoWidth, videoHeight, decodedFrames, droppedFrames, clampedPts,
                    audioPackets, String.format(java.util.Locale.ROOT, "%.1f/%.1f", timeSec(), durationSec), error);
        }
    }

    @Override public void close() {
        Thread workerThread;
        synchronized (stateLock) {
            if (closed) return;
            closed = true;
            stateLock.notifyAll();
            workerThread = worker;
        }
        if (workerThread != null) {
            workerThread.interrupt();
            try { workerThread.join(5_000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        if (monitor != null) { monitor.close(); monitor = null; }
        synchronized (framePool) { framePool.clear(); }
        WinLandCraftClient.LOGGER.info("Closed media player for {}", file.getFileName());
    }
}
