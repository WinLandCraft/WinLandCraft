package dev.winlandcraft;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/** Selected Pixelarticons packed into one texture to keep panel rendering batched. */
enum PixelIcon {
    CLOSE, PLUS, MINUS, ARROW_LEFT, ARROW_RIGHT, ARROW_UP, REFRESH, CHECK,
    CHECKBOX, SAVE, COPY, UNDO, REDO, APPS, UNLINK, FOLDER,
    FILE_TEXT, CHART, TRASH, OPEN, EXTERNAL_LINK, SEARCH, EXPAND, SCALE;

    private static final int CELL = 24, COLUMNS = 8, WIDTH = COLUMNS * CELL, HEIGHT = 3 * CELL;
    private static final Identifier ATLAS = Identifier.fromNamespaceAndPath(
            "winlandcraft", "textures/gui/pixelarticons.png");

    void draw(PanelCanvas canvas, float x, float y, float size, float z, int color) {
        int column = ordinal() % COLUMNS, row = ordinal() / COLUMNS;
        canvas.texture(ATLAS, x, y, size, size, z, color,
                column / (float) COLUMNS, row / 3f, (column + 1) / (float) COLUMNS, (row + 1) / 3f);
    }

    void draw(GuiGraphicsExtractor graphics, int x, int y, int size) {
        int column = ordinal() % COLUMNS, row = ordinal() / COLUMNS;
        graphics.blit(ATLAS, x, y, size, size, column / (float) COLUMNS, row / 3f,
                (column + 1) / (float) COLUMNS, (row + 1) / 3f);
    }
}
