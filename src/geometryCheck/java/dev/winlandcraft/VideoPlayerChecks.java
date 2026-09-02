package dev.winlandcraft;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.util.*;

public final class VideoPlayerChecks {
    public static void main(String[] args)throws Exception{
        Path file=Files.createTempFile("wlc-video-", ".mp4");
        byte[] data=new byte[1024];for(int i=0;i<data.length;i++)data[i]=(byte)i;Files.write(file,data);
        try(var server=new VideoFileServer();var client=HttpClient.newHttpClient()){
            server.select(file);String page=server.url(),media=page.replace("index.html","media");
            check(get(client,page,null).statusCode()==200,"player page");
            var full=get(client,media,null);check(full.statusCode()==200&&Arrays.equals(full.body(),data),"complete media");
            var part=get(client,media,"bytes=100-199");check(part.statusCode()==206&&Arrays.equals(part.body(),Arrays.copyOfRange(data,100,200)),"seek range");
            check(part.headers().firstValue("Content-Range").orElse("").equals("bytes 100-199/1024"),"range header");
            check(get(client,media,"bytes=-20").body().length==20,"suffix");
            check(get(client,media,"bytes=1000-").body().length==24,"open end");
            for(String range:List.of("bytes=1024-","bytes=10-2","bytes=0-1,4-5","bytes=-0","bytes=99999999999999999999999-"))check(get(client,media,range).statusCode()==416,"invalid range");
            var head=client.send(HttpRequest.newBuilder(URI.create(media)).method("HEAD",HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofByteArray());
            check(head.statusCode()==200&&head.body().length==0&&head.headers().firstValue("Content-Length").orElse("").equals("1024"),"HEAD");
            var foreign=client.send(HttpRequest.newBuilder(URI.create(media)).header("Origin","https://example.com").build(),HttpResponse.BodyHandlers.ofByteArray());check(foreign.statusCode()==403,"foreign origin");
            check(get(client,media.replace("/media","/other"),null).statusCode()==404,"no arbitrary routes");
            server.select(file.resolveSibling("missing-video.mp4"));check(get(client,media,null).statusCode()==404,"old selection invalidated");
            check(get(client,server.url().replace("index.html","media"),null).statusCode()==404,"missing file");
            Files.write(file,new byte[0]);server.select(file);check(get(client,server.url().replace("index.html","media"),null).body().length==0,"empty file");
            check(VideoFileServer.supports(Path.of("movie.MP4"))&&!VideoFileServer.supports(Path.of("notes.txt")),"associations");
            var apps=new AppWindows();check(apps.fileTargets(file).stream().anyMatch(t->t.id().equals("winlandcraft:video_player")),"file manager handler");
            check(apps.appEntries().stream().anyMatch(e->e.panel() instanceof VideoPlayerPanel),"launcher app");
        }finally{Files.deleteIfExists(file);}
        System.out.println("Video Player: HTTP media, seeking/ranges, HEAD, selection isolation, failures and app associations passed.");
    }
    private static HttpResponse<byte[]> get(HttpClient client,String url,String range)throws Exception{var request=HttpRequest.newBuilder(URI.create(url));if(range!=null)request.header("Range",range);return client.send(request.build(),HttpResponse.BodyHandlers.ofByteArray());}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
