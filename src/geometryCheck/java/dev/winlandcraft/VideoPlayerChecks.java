package dev.winlandcraft;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.util.*;

public final class VideoPlayerChecks {
    public static void main(String[] args)throws Exception{
        check(VideoFileServer.supports(Path.of("movie.MP4"))&&!VideoFileServer.supports(Path.of("notes.txt")),"associations");
        var apps=new AppWindows();
        check(apps.fileTargets(Path.of("movie.mp4")).stream().anyMatch(t->t.id().equals("winlandcraft:video_player")),"file manager handler");
        check(apps.appEntries().stream().anyMatch(e->e.panel() instanceof VideoPlayerPanel),"launcher app");
        MediaPlayerChecks.run();
        Path picture=Files.createTempFile("wlc-image-", ".png");
        byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aP4sAAAAASUVORK5CYII=");Files.write(picture,png);
        try(var server=new VideoFileServer();var client=HttpClient.newHttpClient()){
            String empty=new String(get(client,server.url(),null).body(),java.nio.charset.StandardCharsets.UTF_8);check(empty.contains("Drop an image"),"empty image viewer");
            server.select(picture);String url=server.url();var page=get(client,url,null);
            check(new String(page.body(),java.nio.charset.StandardCharsets.UTF_8).contains("image.src='media'"),"image page instead of video page");
            check(page.headers().firstValue("Content-Security-Policy").orElse("").contains("img-src 'self'"),"local image allowed by CSP");
            var response=get(client,url.replace("index.html","media"),null);check(response.headers().firstValue("Content-Type").orElse("").equals("image/png")&&Arrays.equals(response.body(),png),"PNG bytes and MIME");
            for(String ext:List.of("PNG","jpeg","gif","webp","svg","bmp","ico","avif","apng"))check(ImageViewerPage.supports(Path.of("image."+ext)),"image association");
            check(!ImageViewerPage.supports(Path.of("file.txt")),"non-image excluded");
            var imageApps=new AppWindows();check(imageApps.fileTargets(Path.of("drawing.svg")).getFirst().id().equals("winlandcraft:image_viewer"),"SVG opens viewer before text editor");
            check(apps.appEntries().stream().anyMatch(e->e.panel() instanceof ImageViewerPanel),"image app registered");
            server.select(picture.resolveSibling("missing.png"));check(get(client,server.url().replace("index.html","media"),null).statusCode()==404,"missing image");
        }finally{Files.deleteIfExists(picture);}
        System.out.println("Image Viewer: page, MIME, image bytes, missing files and associations passed.");
        System.out.println("Video Player: native panel, app associations passed (see Media player above).");
    }
    private static HttpResponse<byte[]> get(HttpClient client,String url,String range)throws Exception{var request=HttpRequest.newBuilder(URI.create(url));if(range!=null)request.header("Range",range);return client.send(request.build(),HttpResponse.BodyHandlers.ofByteArray());}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
