package dev.winlandcraft;

/** Native UI viewport: resize changes available pixels; scaleTo preserves them. */
public abstract class NativePanel extends WorldPanel {
    private int layoutWidth,layoutHeight;
    protected NativePanel(float width,float height,int pixelsWide,int pixelsHigh){super(width,height);layoutWidth=pixelsWide;layoutHeight=pixelsHigh;}
    @Override public final int pixelWidth(){return layoutWidth;}
    @Override public final int pixelHeight(){return layoutHeight;}
    @Override public void resize(float width,float height) {
        float densityX=layoutWidth/worldWidth(),densityY=layoutHeight/worldHeight();
        super.resize(width,height);
        layoutWidth=Math.max(1,Math.round(worldWidth()*densityX));
        layoutHeight=Math.max(1,Math.round(worldHeight()*densityY));
        layoutChanged();
    }
    protected void layoutChanged() { }
}
