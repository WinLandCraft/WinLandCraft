package dev.winlandcraft.api.v2;
/** Valid only during render(). Coordinates are logical pixels; colors are ARGB. */
public interface Canvas {
    int width();
    int height();
    int textWidth(String text);
    void rectangle(float x,float y,float width,float height,int argb);
    void text(String text,int x,int y,int argb,float scale);
    /** A texture packaged at assets/<namespace>/textures/...png. */
    void image(String resource,int x,int y,int width,int height,int argb);
}
