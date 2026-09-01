package dev.winlandcraft;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import javax.imageio.ImageIO;

public final class FaviconChecks {
    public static void main(String[] args) throws Exception {
        var icons = FaviconLoader.candidates("https://example.com/folder/page", """
                <link rel="stylesheet" href="wrong.css">
                <link REL='shortcut icon' href='../logo.png?a=1&amp;b=2'>
                <link rel=icon href=//cdn.example.com/icon.png>
                <link rel=icon href=javascript:bad>
                """);
        check(icons.equals(List.of(URI.create("https://example.com/logo.png?a=1&b=2"),
                URI.create("https://cdn.example.com/icon.png"), URI.create("https://example.com/favicon.ico"))), "icon URLs");
        check(FaviconLoader.candidates("about:blank", "").isEmpty(), "non-web page");
        check(FaviconLoader.decode(new byte[10]) == null, "invalid image");
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFF12AB34);
        var png = new ByteArrayOutputStream(); ImageIO.write(image, "png", png);
        check(FaviconLoader.decode(png.toByteArray()).getRGB(0, 0) == 0xFF12AB34, "PNG colors");
        check(FaviconLoader.decode(ico(png.toByteArray(), 2)).getRGB(0, 0) == 0xFF12AB34, "PNG ICO frame");
        for (int bits : new int[]{24, 32}) {
            var dib = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
            dib.putInt(0, 40).putInt(4, 1).putInt(8, 2).putShort(12, (short) 1).putShort(14, (short) bits);
            dib.put(40, (byte) 0x34).put(41, (byte) 0xAB).put(42, (byte) 0x12);
            check(FaviconLoader.decode(ico(dib.array(), 1)).getRGB(0, 0) == 0xFF12AB34, "opaque DIB " + bits);
            dib.put(44, (byte) 0x80);
            check(FaviconLoader.decode(ico(dib.array(), 1)).getRGB(0, 0) >>> 24 == 0, "AND transparency " + bits);
            if (bits == 32) {
                dib.put(43, (byte) 127);
                check(FaviconLoader.decode(ico(dib.array(), 1)).getRGB(0, 0) == 0x7F12AB34, "DIB alpha");
            }
        }
        System.out.println("Favicon parsing and PNG/ICO decoding: 10 checks passed.");
    }
    private static byte[] ico(byte[] frame, int size) {
        var ico = ByteBuffer.allocate(22 + frame.length).order(ByteOrder.LITTLE_ENDIAN);
        ico.putShort(2, (short) 1).putShort(4, (short) 1).put(6, (byte) size).put(7, (byte) size);
        ico.putInt(14, frame.length).putInt(18, 22).position(22); ico.put(frame);
        return ico.array();
    }
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
