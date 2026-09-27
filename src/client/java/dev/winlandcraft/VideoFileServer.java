package dev.winlandcraft;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Serves only a user-selected image; URLs never contain filesystem paths. */
final class VideoFileServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{var t=new Thread(r,"WLC image file");t.setDaemon(true);return t;});
    private final String token=UUID.randomUUID().toString()+UUID.randomUUID(),origin;
    private volatile Selection selected=new Selection(null,UUID.randomUUID().toString());
    private record Selection(Path path,String id){}
    VideoFileServer() throws IOException {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),8);
        origin="http://127.0.0.1:"+server.getAddress().getPort();
        server.createContext("/",this::handle);server.setExecutor(workers);server.start();
    }
    String url(){return origin+"/"+token+"/"+selected.id()+"/index.html";}
    void select(Path path){selected=new Selection(path==null?null:path.toAbsolutePath().normalize(),UUID.randomUUID().toString());}
    private static String extension(Path path){String n=path.getFileName().toString().toLowerCase(Locale.ROOT);return n.substring(n.lastIndexOf('.')+1);}
    private void handle(HttpExchange exchange) throws IOException {
        try(exchange){
            String host=exchange.getRequestHeaders().getFirst("Host"),requestOrigin=exchange.getRequestHeaders().getFirst("Origin");
            if(!exchange.getRemoteAddress().getAddress().isLoopbackAddress()||!origin.substring(7).equals(host)
                    ||requestOrigin!=null&&!requestOrigin.equals(origin)&&!requestOrigin.equals("null")){exchange.sendResponseHeaders(403,-1);return;}
            String method=exchange.getRequestMethod();boolean head=method.equals("HEAD");
            if(!head&&!method.equals("GET")){exchange.getResponseHeaders().set("Allow","GET, HEAD");exchange.sendResponseHeaders(405,-1);return;}
            var selection=selected;String base="/"+token+"/"+selection.id()+"/",path=exchange.getRequestURI().getPath();
            exchange.getResponseHeaders().set("Cache-Control","no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
            exchange.getResponseHeaders().set("Referrer-Policy","no-referrer");
            if(path.equals(base+"index.html")){
                byte[] html=ImageViewerPage.html(selection.path()!=null).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");
                exchange.getResponseHeaders().set("Content-Security-Policy","default-src 'none'; media-src 'self'; img-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'");
                exchange.getResponseHeaders().set("Content-Length",Integer.toString(html.length));
                exchange.sendResponseHeaders(200,head?-1:html.length);if(!head)exchange.getResponseBody().write(html);return;
            }
            if(!path.equals(base+"media")||selection.path()==null){exchange.sendResponseHeaders(404,-1);return;}
            // Disk access belongs to these bounded HTTP workers, never the render thread.
            try(var file=java.nio.channels.FileChannel.open(selection.path(),StandardOpenOption.READ)){
                if(!Files.isRegularFile(selection.path())){exchange.sendResponseHeaders(404,-1);return;}
                long size=file.size();String range=exchange.getRequestHeaders().getFirst("Range");long[] bounds;
                try{bounds=range(range,size);}catch(IllegalArgumentException invalid){exchange.getResponseHeaders().set("Content-Range","bytes */"+size);exchange.sendResponseHeaders(416,-1);return;}
                long length=bounds[1]-bounds[0]+1;
                exchange.getResponseHeaders().set("Content-Type",ImageViewerPage.mime(selection.path()));exchange.getResponseHeaders().set("Accept-Ranges","bytes");
                exchange.getResponseHeaders().set("Content-Length",Long.toString(length));
                if(range!=null)exchange.getResponseHeaders().set("Content-Range","bytes "+bounds[0]+"-"+bounds[1]+"/"+size);
                exchange.sendResponseHeaders(range==null?200:206,head||length==0?-1:length);
                if(!head&&length>0){file.position(bounds[0]);var buffer=java.nio.ByteBuffer.allocate(64*1024);while(length>0){buffer.clear();buffer.limit((int)Math.min(buffer.capacity(),length));int read=file.read(buffer);if(read<0)break;exchange.getResponseBody().write(buffer.array(),0,read);length-=read;}}
            }catch(NoSuchFileException|AccessDeniedException failure){exchange.sendResponseHeaders(404,-1);}
        }catch(IOException failure){/* A seek/close cancels an in-flight response. Chromium reports other read failures. */}
    }
    static long[] range(String value,long size){
        if(value==null)return new long[]{0,size-1};
        if(size==0||!value.matches("bytes=[0-9]*-[0-9]*"))throw new IllegalArgumentException("Invalid range");
        String[] parts=value.substring(6).split("-",-1);
        try{
            long start,end;
            if(parts[0].isEmpty()){long suffix=Long.parseLong(parts[1]);if(suffix<=0)throw new IllegalArgumentException();start=Math.max(0,size-suffix);end=size-1;}
            else{start=Long.parseLong(parts[0]);end=parts[1].isEmpty()?size-1:Math.min(size-1,Long.parseLong(parts[1]));}
            if(start>=size||end<start)throw new IllegalArgumentException();return new long[]{start,end};
        }catch(NumberFormatException error){throw new IllegalArgumentException("Invalid range",error);}
    }
    public void close(){server.stop(0);workers.shutdownNow();}
}
