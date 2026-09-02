package dev.winlandcraft;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/** Selected Pixelarticons packed into one texture to keep panel rendering batched. */
enum PixelIcon {
    CLOSE, PLUS, MINUS, ARROW_LEFT, ARROW_RIGHT, ARROW_UP, REFRESH, CHECK,
    CHECKBOX, SAVE, COPY, UNDO, REDO, APPS, UNLINK, FOLDER,
    FILE_TEXT, CHART, TRASH, OPEN, EXTERNAL_LINK, SEARCH, EXPAND, SCALE;

    private static final int CELL = 24, COLUMNS = 8, WIDTH = COLUMNS * CELL, HEIGHT = 3 * CELL;
    private static final ResourceLocation ATLAS = ResourceLocation.fromNamespaceAndPath(
            "winlandcraft", "textures/gui/pixelarticons.png");

    void draw(PanelCanvas canvas, float x, float y, float size, float z, int color) {
        int column = ordinal() % COLUMNS, row = ordinal() / COLUMNS;
        canvas.texture(ATLAS, x, y, size, size, z, color,
                column / (float) COLUMNS, row / 3f, (column + 1) / (float) COLUMNS, (row + 1) / 3f);
    }

    void draw(GuiGraphics graphics, int x, int y, int size) {
        int column = ordinal() % COLUMNS, row = ordinal() / COLUMNS;
        graphics.blit(RenderType::guiTextured, ATLAS, x, y, column * CELL, row * CELL,
                size, size, CELL, CELL, WIDTH, HEIGHT);
    }
}
