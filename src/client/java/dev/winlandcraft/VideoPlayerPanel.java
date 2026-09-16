package dev.winlandcraft;

import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.system.MemoryUtil;

/** Native video player on FfmpegPlayer: file-direct playback with its own controls.
 *  No browser view, no file server. Streams capture the composed panel like native apps. */
final class VideoPlayerPanel extends NativePanel {
    static final int WIDTH = 1280, VIDEO_HEIGHT = 720, BAR_HEIGHT = 32, HEIGHT = VIDEO_HEIGHT + BAR_HEIGHT;
    private static final int PLAY_X = 8, PLAY_SIZE = 24, TRACK_X = 52;

    private int viewWidth() { return pixelWidth(); }
    private int videoHeight() { return pixelHeight() - BAR_HEIGHT; }
    private int barTop() { return pixelHeight() - BAR_HEIGHT; }
    private int trackEnd() { return pixelWidth() - 308; }
    private int timeX() { return pixelWidth() - 300; }
    private int volDownX() { return pixelWidth() - 180; }
    private int volUpX() { return pixelWidth() - 144; }
    private int muteX() { return pixelWidth() - 108; }

    private Path file;
    private FfmpegPlayer player;
    private boolean seeking;
    private final ResourceLocation texture = ResourceLocation.fromNamespaceAndPath("winlandcraft", "video/" + Integer.toHexString(System.identityHashCode(this)));
    private boolean textureRegistered;
    private int textureId, uploadedWidth, uploadedHeight;
    private long uploadedSequence = -1;
    private ByteBuffer staging;

    VideoPlayerPanel() { super(4.5f, 2.64f, WIDTH, HEIGHT); }
    @Override public String windowTitle() { return file == null ? "Video Player" : file.getFileName() + " - Video Player"; }
    @Override public boolean acceptsFileDrop() { return true; }
    @Override public void dropFile(Path path) {
        closePlayer();
        file = path;
        player = new FfmpegPlayer(path);
        uploadedSequence = -1;
    }

    private void closePlayer() {
        if (player != null) { player.close(); player = null; }
    }

    /** Current player error for regression checks; empty while loading or playing. */
    String playerError() { return player == null ? "" : player.error; }

    private boolean inBar(int y) { return y >= barTop() && y < pixelHeight(); }

    @Override public void mouseDown(int x, int y, int button) {
        if (button != 0 || player == null) return;
        if (inBar(y)) {
            if (x >= PLAY_X && x < PLAY_X + PLAY_SIZE) player.toggle();
            else if (x >= TRACK_X && x < trackEnd()) { seeking = true; seekTo(x); }
            else if (x >= volDownX() && x < volDownX() + 32) player.setVolume(player.volume - .1);
            else if (x >= volUpX() && x < volUpX() + 32) player.setVolume(player.volume + .1);
            else if (x >= muteX() && x < muteX() + 48) player.toggleMute();
        } else if (y >= 0) player.toggle();
    }

    @Override public void hover(int x, int y) {
        if (seeking && player != null && inBar(y)) seekTo(x);
    }

    @Override public void mouseUp(int x, int y, int button) {
        seeking = false;
    }

    private void seekTo(int x) {
        player.seekFraction((x - TRACK_X) / (double) (trackEnd() - TRACK_X));
    }

    @Override public void tick(Minecraft client) {
        super.tick(client);
        if (player != null) player.tick();
    }

    /** Uploads the newest decoded frame. Render thread only. */
    private boolean upload() {
        var frame = player == null ? null : player.current;
        if (frame == null || frame.sequence() == uploadedSequence) return uploadedSequence >= 0;
        int size = frame.rgba().length;
        if (staging == null || staging.capacity() != size) { if (staging != null) MemoryUtil.memFree(staging); staging = MemoryUtil.memAlloc(size); }
        staging.clear();
        staging.put(frame.rgba()).flip();
        int binding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), pbo = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int[] fields = {GL11.GL_UNPACK_ALIGNMENT, GL11.GL_UNPACK_ROW_LENGTH, GL11.GL_UNPACK_SKIP_ROWS, GL11.GL_UNPACK_SKIP_PIXELS};
        int[] old = new int[fields.length];
        for (int i = 0; i < fields.length; i++) old[i] = GL11.glGetInteger(fields[i]);
        try {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            for (int i = 0; i < fields.length; i++) GL11.glPixelStorei(fields[i], i == 0 ? 1 : 0);
            if (textureId == 0) {
                textureId = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            } else GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            if (uploadedWidth != frame.width() || uploadedHeight != frame.height())
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, frame.width(), frame.height(), 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, frame.width(), frame.height(), GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, staging);
            uploadedWidth = frame.width();
            uploadedHeight = frame.height();
            uploadedSequence = frame.sequence();
            player.markConsumed();
            player.releaseFrame(frame.rgba());
            return true;
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, binding);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pbo);
            for (int i = 0; i < fields.length; i++) GL11.glPixelStorei(fields[i], old[i]);
        }
    }

    @Override void drawSurface(PanelCanvas canvas) {
        int width = pixelWidth(), videoH = videoHeight();
        canvas.rect(0, 0, width, pixelHeight(), 0, 0xFF10151C);
        if (player == null) {
            canvas.text("Drop a video here, or open one from File Manager.", 20, 40, -1, 2);
            canvas.text("MP4, WebM, OGV, MOV, MKV and anything else FFmpeg reads.", 20, 76, 0xFF9BAABD, 1.5f);
            return;
        }
        if (!player.error.isEmpty()) {
            canvas.text("Cannot play this video.", 20, 40, 0xFFFF8888, 2);
            canvas.text(Minecraft.getInstance().font.plainSubstrByWidth(player.error, 600), 20, 76, 0xFFFFAAAA, 1.5f);
            return;
        }
        boolean image = upload();
        if (image) {
            if (!textureRegistered) {
                textureRegistered = true;
                Minecraft.getInstance().getTextureManager().register(texture, new AbstractTexture() {
                    @Override public int getId() { return textureId; }
                    @Override public void releaseId() { }
                    @Override public void close() { }
                });
            }
            float scale = Math.min(width / (float) uploadedWidth, videoH / (float) uploadedHeight);
            int w = Math.max(1, Math.round(uploadedWidth * scale)), h = Math.max(1, Math.round(uploadedHeight * scale));
            canvas.texture(texture, (width - w) / 2f, (videoH - h) / 2f, w, h, .1f, -1, 0, 0, 1, 1);
        } else {
            canvas.text("Loading...", 20, 40, -1, 2);
        }
        drawBar(canvas);
    }

    private void drawBar(PanelCanvas canvas) {
        int top = barTop(), width = pixelWidth();
        canvas.rect(0, top, width, BAR_HEIGHT, .2f, 0xFF1B2530);
        int mid = top + BAR_HEIGHT / 2;
        // Play/pause: triangle built from bars, or two pause bars.
        if (player.paused) {
            for (int i = 0; i < 12; i++)
                canvas.rect(PLAY_X + i, mid - 8 + i / 2, 2, 16 - i, .3f, -1);
        } else {
            canvas.rect(PLAY_X + 4, mid - 8, 6, 16, .3f, -1);
            canvas.rect(PLAY_X + 14, mid - 8, 6, 16, .3f, -1);
        }
        // Progress track.
        int end = trackEnd();
        canvas.rect(TRACK_X, mid - 3, end - TRACK_X, 6, .3f, 0xFF3A4552);
        double fraction = player.durationSec > 0 ? player.timeSec() / player.durationSec : 0;
        int knob = TRACK_X + (int) Math.round(Math.clamp(fraction, 0, 1) * (end - TRACK_X));
        canvas.rect(TRACK_X, mid - 3, Math.max(0, knob - TRACK_X), 6, .35f, 0xFF8C4BC1);
        canvas.rect(knob - 3, mid - 7, 6, 14, .4f, -1);
        canvas.text(formatTime(player.timeSec()) + " / " + formatTime(player.durationSec), timeX(), mid - 7, -1);
        canvas.text("-", volDownX() + 12, mid - 7, -1, 1.5f);
        canvas.text("+", volUpX() + 11, mid - 7, -1, 1.5f);
        canvas.text(player.muted ? "MUTED" : (int) Math.round(player.volume * 100) + "", muteX(), mid - 7, player.muted ? 0xFFFF8888 : -1);
        if (player.eof) canvas.text("Ended — press play to replay", TRACK_X, top - 24, 0xFFFFCC88, 1.2f);
    }

    private static String formatTime(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0) seconds = 0;
        long total = (long) seconds;
        return String.format("%02d:%02d", total / 60, total % 60);
    }

    @Override public void close() {
        super.close();
        closePlayer();
        file = null;
        if (textureRegistered) { Minecraft.getInstance().getTextureManager().release(texture); textureRegistered = false; }
        int doomed = textureId;
        textureId = 0;
        uploadedWidth = uploadedHeight = 0;
        uploadedSequence = -1;
        if (staging != null) { MemoryUtil.memFree(staging); staging = null; }
        if (doomed > 0) {
            if (RenderSystem.isOnRenderThread()) GL11.glDeleteTextures(doomed);
            else RenderSystem.recordRenderCall(() -> GL11.glDeleteTextures(doomed));
        }
    }
}
