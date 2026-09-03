package dev.winlandcraft;

import java.nio.file.Path;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.systems.RenderSystem;

final class ImageViewerPanel extends BrowserPanel {
    private VideoFileServer server;private Path file;private String error="";
    ImageViewerPanel(){super("about:blank");setAppName("Image Viewer");}
    @Override public String windowTitle(){return file==null?"Image Viewer":file.getFileName()+" - Image Viewer";}
    @Override protected boolean showBrowserMenu(){return false;}
    @Override public boolean acceptsFileDrop(){return true;}
    @Override public void dropFile(Path path){file=path;ensureServer();if(server!=null){server.select(path);if(managedBrowser()!=null)managedBrowser().loadURL(server.url());}}
    private void ensureServer(){if(server!=null||!error.isEmpty())return;try{server=new VideoFileServer(true);server.select(file);}catch(IOException failure){error="Could not start Image Viewer. Close and reopen to retry.";WinLandCraftClient.LOGGER.error("Image Viewer endpoint failed",failure);}}
    @Override protected String initialUrl(){return server==null?"about:blank":server.url();}
    @Override protected boolean browserEnabled(){return server!=null;}
    @Override public void tick(Minecraft client){if(isOpen())ensureServer();super.tick(client);}
    @Override void drawSurface(PanelCanvas canvas){if(!error.isEmpty()){canvas.rect(0,0,pixelWidth(),pixelHeight(),0,0xFF10151C);canvas.text(error,20,30,0xFFFFAAAA);}else super.drawSurface(canvas);}
    @Override public void close(){
        if(!RenderSystem.isOnRenderThread()){RenderSystem.recordRenderCall(this::close);return;}
        super.close();if(server!=null){server.close();server=null;}file=null;error="";
    }
}
