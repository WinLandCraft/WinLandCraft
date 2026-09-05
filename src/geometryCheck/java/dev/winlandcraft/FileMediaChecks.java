package dev.winlandcraft;

import com.mojang.blaze3d.systems.RenderSystem;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

final class FileMediaChecks {
    static void run() {
        try(var client=HttpClient.newHttpClient()) {
            for(var panel:List.of(new VideoPlayerPanel(),new ImageViewerPanel())) {
                boolean image=panel instanceof ImageViewerPanel;
                String name=image?"Image Viewer":"Video Player",element=image?"image":"video";
                Path file=Path.of(image?"picture.png":"movie.mp4");
                try {
                    check(panel.windowTitle().equals(name)&&!panel.browserEnabled(),"lazy media endpoint");
                    panel.position=new Vec3(0,0,-3);panel.orientation=new Quaternionf();
                    panel.dropFile(file);
                    String first=panel.initialUrl();
                    check(panel.windowTitle().equals(file.getFileName()+" - "+name),"selected file title");
                    check(get(client,first).body().contains(element+".src='media'"),"correct media page");
                    panel.dropFile(file);
                    String selected=panel.initialUrl();
                    check(!selected.equals(first)&&get(client,first).statusCode()==404,"selection invalidates old URL");
                    var failure=new AtomicReference<Throwable>();
                    Thread network=new Thread(()->{try{panel.close();}catch(Throwable error){failure.set(error);}},"media-close-check");
                    network.start();network.join();
                    if(failure.get()!=null)throw new AssertionError("off-thread media close",failure.get());
                    check(panel.isOpen()&&panel.initialUrl().equals(selected)&&get(client,selected).statusCode()==200,"endpoint survives until render-thread close");
                    RenderSystem.replayQueue();
                    check(!panel.isOpen()&&!panel.browserEnabled()&&panel.initialUrl().equals("about:blank"),"media resources cleared");
                    check(panel.windowTitle().equals(name),"title reset on close");
                    panel.close();
                    panel.position=new Vec3(0,0,-3);panel.orientation=new Quaternionf();panel.dropFile(file);
                    check(!panel.initialUrl().equals(selected)&&get(client,panel.initialUrl()).statusCode()==200,"reopen creates a fresh endpoint");
                } finally {panel.close();}
            }
        } catch(Exception error){throw new AssertionError(error);}
        System.out.println("File media panels: titles, page selection, stale URLs, deferred close and reopen passed (without native CEF).");
    }
    private static HttpResponse<String> get(HttpClient client,String url)throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
