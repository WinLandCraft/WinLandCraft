package dev.winlandcraft.api.v2;

/** Borrowed GL texture. topLeftOrigin=true means texture v=0 is the top row.
 * Straight alpha, RGBA8, dimensions 1..4096. No ownership of the texture transfers to the host. */
public record GpuFrame(int textureId,int width,int height,boolean topLeftOrigin) {
    public GpuFrame {
        if(textureId<=0||width<1||height<1||width>4096||height>4096)
            throw new IllegalArgumentException("Invalid GPU texture or dimensions (maximum 4096 x 4096)");
    }
}
