package dev.winlandcraft;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/** Page-declared icons first, conventional favicon second. Runs off the render thread. */
final class FaviconLoader {
    private static final int MAX_BYTES = 1024 * 1024;
    private static final Pattern LINK = Pattern.compile("<link\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTRIBUTE = Pattern.compile("([\\w-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    static List<URI> candidates(String page, String html) {
        var result = new LinkedHashSet<URI>();
        try {
            URI base = URI.create(page);
            if (!web(base)) return List.of();
            var links = LINK.matcher(html);
            while (links.find() && result.size() < 6) {
                String rel = "", href = "";
                var attributes = ATTRIBUTE.matcher(links.group());
                while (attributes.find()) {
                    String value = attributes.group(2) != null ? attributes.group(2)
                            : attributes.group(3) != null ? attributes.group(3) : attributes.group(4);
                    if (attributes.group(1).equalsIgnoreCase("rel")) rel = value.toLowerCase(Locale.ROOT);
                    if (attributes.group(1).equalsIgnoreCase("href")) href = value.replace("&amp;", "&");
                }
                if (!href.isBlank() && List.of(rel.split("\\s+")).stream()
                        .anyMatch(token -> token.equals("icon") || token.equals("apple-touch-icon"))) {
                    try {
                        URI icon = base.resolve(href);
                        if (web(icon)) result.add(icon);
                    } catch (IllegalArgumentException ignored) { }
                }
            }
            result.add(base.resolve("/favicon.ico"));
        } catch (IllegalArgumentException ignored) { }
        return List.copyOf(result);
    }

    private static boolean web(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme());
    }

    static byte[] load(String page, String html) {
        for (URI uri : candidates(page, html)) {
            try {
                // ofByteArray keeps the request timeout active until the body is received.
                // A limiting subscriber prevents an icon endpoint from allocating an unbounded body.
                var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();
                var response = HTTP.send(request, info -> new LimitedBody());
                if (response.statusCode() != 200) continue;
                BufferedImage image = decode(response.body());
                if (image == null) continue;
                var square = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
                var graphics = square.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    double scale = 32.0 / Math.max(image.getWidth(), image.getHeight());
                    int width = Math.max(1, (int) (image.getWidth() * scale));
                    int height = Math.max(1, (int) (image.getHeight() * scale));
                    graphics.drawImage(image, (32 - width) / 2, (32 - height) / 2, width, height, null);
                } finally { graphics.dispose(); }
                var png = new ByteArrayOutputStream();
                ImageIO.write(square, "png", png);
                return png.toByteArray();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return null;
            } catch (Exception ignored) { /* Missing, unsupported, or inaccessible icon: try the next one. */ }
        }
        return null;
    }

    static BufferedImage decode(byte[] bytes) throws java.io.IOException {
        if (bytes.length < 6 || bytes.length > MAX_BYTES) return null;
        var data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (data.getShort(0) != 0 || data.getShort(2) != 1) return raster(bytes);
        int count = Short.toUnsignedInt(data.getShort(4));
        if (count > 256 || bytes.length < 6 + count * 16) return null;
        var entries = new ArrayList<int[]>();
        for (int i = 0; i < count; i++) {
            int entry = 6 + i * 16;
            int length = data.getInt(entry + 8), offset = data.getInt(entry + 12);
            int size = Byte.toUnsignedInt(bytes[entry]);
            if (length > 0 && offset >= 6 + count * 16 && (long) offset + length <= bytes.length)
                entries.add(new int[]{size == 0 ? 256 : size, offset, length});
        }
        entries.sort((a, b) -> Integer.compare(b[0], a[0]));
        for (int[] entry : entries) {
            byte[] frame = java.util.Arrays.copyOfRange(bytes, entry[1], entry[1] + entry[2]);
            BufferedImage image = raster(frame);
            if (image == null) image = bitmap(frame);
            if (image != null) return image;
        }
        return null;
    }

    private static BufferedImage raster(byte[] bytes) throws java.io.IOException {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            var reader = readers.next();
            try {
                reader.setInput(input);
                if (reader.getWidth(0) > 1024 || reader.getHeight(0) > 1024) return null;
                return reader.read(0);
            } finally { reader.dispose(); }
        }
    }

    /** Windows ICO frames commonly contain a 24/32-bit DIB followed by an AND mask. */
    private static BufferedImage bitmap(byte[] bytes) {
        if (bytes.length < 40) return null;
        var data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int header = data.getInt(0), width = data.getInt(4), height = data.getInt(8) / 2;
        int bits = Short.toUnsignedInt(data.getShort(14));
        if (header < 40 || width < 1 || width > 256 || height < 1 || height > 256
                || (bits != 32 && bits != 24) || data.getInt(16) != 0) return null;
        int stride = ((width * bits + 31) / 32) * 4, maskStride = ((width + 31) / 32) * 4;
        if ((long) header + stride * height > bytes.length) return null;
        boolean alpha = false;
        if (bits == 32) for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            alpha |= bytes[header + y * stride + x * 4 + 3] != 0;
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int row = height - 1 - y, offset = header + row * stride + x * (bits / 8);
            int a = alpha ? Byte.toUnsignedInt(bytes[offset + 3]) : 255;
            int mask = header + stride * height + row * maskStride + x / 8;
            if (!alpha && mask < bytes.length && (bytes[mask] & (128 >> (x % 8))) != 0) a = 0;
            image.setRGB(x, y, (a << 24) | (Byte.toUnsignedInt(bytes[offset + 2]) << 16)
                    | (Byte.toUnsignedInt(bytes[offset + 1]) << 8) | Byte.toUnsignedInt(bytes[offset]));
        }
        return image;
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final java.util.concurrent.CompletableFuture<byte[]> body = new java.util.concurrent.CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private java.util.concurrent.Flow.Subscription subscription;
        public java.util.concurrent.CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(java.util.concurrent.Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if ((long) bytes.size() + buffer.remaining() > MAX_BYTES) {
                    subscription.cancel(); body.completeExceptionally(new java.io.IOException("Icon too large")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { body.completeExceptionally(error); }
        public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
