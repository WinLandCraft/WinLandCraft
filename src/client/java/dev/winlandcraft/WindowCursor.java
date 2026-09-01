package dev.winlandcraft;

import net.minecraft.client.gui.GuiGraphics;

/** Small crisp cursor sprites, drawn at GUI scale with a dark outline. */
public final class WindowCursor {
    private static final String[] ARROW = {
        "#           ", "##          ", "#W#         ", "#WW#        ",
        "#WWW#       ", "#WWWW#      ", "#WWWWW#     ", "#WWWWWW#    ",
        "#WWWWWWW#   ", "#WWWW#####  ", "#WW#WW#     ", "#W# #WW#    ",
        "##  #WW#    ", "#    #WW#   ", "     #WW#   ", "      ##    "
    };
    public static void render(GuiGraphics graphics, int kind) {
        int cx = graphics.guiWidth() / 2, cy = graphics.guiHeight() / 2;
        if (kind == 1) {
            for (int y = 0; y < ARROW.length; y++) for (int x = 0; x < ARROW[y].length(); x++) {
                char pixel = ARROW[y].charAt(x);
                if (pixel != ' ') graphics.fill(cx + x, cy + y, cx + x + 1, cy + y + 1, pixel == '#' ? 0xFF101820 : 0xFFFFFFFF);
            }
        } else {
            // Diagonal double arrow, with its center (the grab point) at the crosshair.
            for (int outline = 1; outline >= 0; outline--) {
                int color = outline == 1 ? 0xFF101820 : 0xFF71E6EE;
                for (int i = -6; i <= 6; i++) dot(graphics, cx, cy, i, kind == 2 ? i : -i, outline, color);
                for (int sign : new int[]{-1, 1}) for (int i = 0; i < 5; i++) {
                    int x = sign * 6, y = sign * 6;
                    dot(graphics, cx, cy, x - sign * i, kind == 2 ? y : -y, outline, color);
                    dot(graphics, cx, cy, x, kind == 2 ? y - sign * i : -y + sign * i, outline, color);
                }
            }
        }
    }
    private static void dot(GuiGraphics g, int x, int y, int dx, int dy, int radius, int color) {
        g.fill(x + dx - radius, y + dy - radius, x + dx + radius + 1, y + dy + radius + 1, color);
    }
}
