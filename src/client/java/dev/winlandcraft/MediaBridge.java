package dev.winlandcraft;

import com.cinemamod.mcef.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Private loopback binary bridge to Chromium's WebCodecs. No external service or executable. */
final class MediaBridge implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService http=Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<String,Endpoint> endpoints=new ConcurrentHashMap<>();
    private final String origin;
    MediaBridge() throws IOException {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),16);
        origin="http://127.0.0.1:"+server.getAddress().getPort();
        server.createContext("/",this::handle);server.setExecutor(http);server.start();
    }
    static final class Queue {
        private final ArrayDeque<byte[]> packets=new ArrayDeque<>();
        private int bytes;
        private boolean needKey=true;
        synchronized boolean offer(byte[] packet) {
            var media=StreamMedia.read(packet);if(media==null)return false;
            if(bytes+packet.length>2_000_000||packets.size()>=128){packets.clear();bytes=0;needKey=true;}
            if(needKey) {
                if(media.kind()!=StreamMedia.VIDEO||!media.key())return false;
                needKey=false;
            }
            packets.add(packet);bytes+=packet.length;return true;
        }
        synchronized byte[] poll(){var packet=packets.poll();if(packet!=null)bytes-=packet.length;return packet;}
        synchronized void clear(){packets.clear();bytes=0;needKey=true;}
    }
    record RawVideo(StreamCapture.Pixels pixels,long timeUs){}
    record RawAudio(byte[] bytes,int frames,long timeUs){}
    final class Endpoint implements AutoCloseable {
        final String token=UUID.randomUUID().toString()+UUID.randomUUID();
        final boolean encode;
        final long startedAt=System.currentTimeMillis();
        final AtomicReference<RawVideo> video=new AtomicReference<>();
        final ArrayBlockingQueue<RawAudio> rawAudio=new ArrayBlockingQueue<>(10);
        final Queue encoded=new Queue(),incoming=new Queue();
        volatile StreamQuality quality=StreamQuality.current();
        volatile boolean ready,closed;
        volatile String error="";
        volatile long lastSeen=System.currentTimeMillis(),forceKey;
        MCEFBrowser browser;
        private int width=2,height=2;
        private long nextGesture;
        Endpoint(boolean encode){this.encode=encode;}
        void open() {
            endpoints.put(token,this);
            try {
                browser=MCEF.createBrowser(origin+"/"+token+"/index",false,2,2);
                browser.setCursorChangeListener(cursor->{});browser.setFocus(false);
            }catch(RuntimeException|LinkageError failure){endpoints.remove(token);throw failure;}
        }
        boolean wantsVideo(){return ready&&!closed&&error.isEmpty()&&video.get()==null;}
        void video(StreamCapture.Pixels pixels){video.set(new RawVideo(pixels,Math.max(0,(System.currentTimeMillis()-startedAt)*1000)));}
        void audio(byte[] bytes,int frames,long timeUs) {
            if(!ready||closed||!quality.audio())return;
            long current=Math.max(0,(System.currentTimeMillis()-startedAt)*1000);
            if(Math.abs(timeUs-current)>5_000_000)timeUs=current;
            var packet=new RawAudio(bytes,frames,timeUs);
            if(!rawAudio.offer(packet)){rawAudio.poll();rawAudio.offer(packet);}
        }
        void receive(byte[] packet) {
            var media=StreamMedia.read(packet);if(media==null||closed)return;
            if(media.kind()==StreamMedia.VIDEO&&(media.width()!=width||media.height()!=height)) {
                width=media.width();height=media.height();browser.resize(width,height);
            }
            incoming.offer(packet);
        }
        void tick() {
            if(closed)return;
            if(System.currentTimeMillis()-lastSeen>15_000&&error.isEmpty())error="Codec worker stopped responding";
            // Native CEF input activates Web Audio for the local playback surface. Never sent to a website.
            if(!encode&&ready&&System.currentTimeMillis()>=nextGesture) {
                nextGesture=System.currentTimeMillis()+1000;
                browser.sendMouseMove(1,1);browser.sendMousePress(1,1,0);browser.sendMouseRelease(1,1,0);
            }
        }
        @Override public void close() {
            if(closed)return;closed=true;endpoints.remove(token);video.set(null);rawAudio.clear();encoded.clear();incoming.clear();
            if(browser!=null){browser.close();browser=null;}
        }
    }
    Endpoint create(boolean encode){var endpoint=new Endpoint(encode);endpoint.open();return endpoint;}
    private void handle(HttpExchange exchange) {
        try(exchange) {
            if(!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {reply(exchange,403,null);return;}
            String host=exchange.getRequestHeaders().getFirst("Host"),from=exchange.getRequestHeaders().getFirst("Origin");
            if(!origin.substring(7).equals(host)||from!=null&&!origin.equals(from)){reply(exchange,403,null);return;}
            String[] path=exchange.getRequestURI().getPath().split("/");
            var endpoint=path.length==3?endpoints.get(path[1]):null;
            if(endpoint==null||endpoint.closed){reply(exchange,404,null);return;}
            exchange.getResponseHeaders().set("Cache-Control","no-store");
            exchange.getResponseHeaders().set("Referrer-Policy","no-referrer");
            exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
            String route=path[2];
            if(exchange.getRequestMethod().equals("GET"))switch(route) {
                case "index" -> {
                    exchange.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");
                    exchange.getResponseHeaders().set("Content-Security-Policy","default-src 'none'; script-src 'self'; connect-src 'self'; style-src 'unsafe-inline'; media-src blob:");
                    reply(exchange,200,("<!doctype html><meta charset='utf-8'><style>html,body{margin:0;background:#181d29;overflow:hidden}canvas{width:100vw;height:100vh}</style><canvas id='view'></canvas><script src='worker.js'></script>").getBytes(StandardCharsets.UTF_8));
                }
                case "worker.js" -> {
                    exchange.getResponseHeaders().set("Content-Type","text/javascript; charset=utf-8");
                    try(var input=MediaBridge.class.getResourceAsStream("/assets/winlandcraft/stream-worker.js")) {
                        if(input==null)throw new IOException("Missing codec worker");reply(exchange,200,input.readAllBytes());
                    }
                }
                case "config" -> {
                    var q=endpoint.quality;
                    String config="{\"encode\":"+endpoint.encode+",\"fps\":"+q.fps()+",\"bitrate\":"+(q.kbps()*1000)+",\"audio\":"+q.audio()+",\"audioBitrate\":"+(q.audioKbps()*1000)+",\"forceKey\":"+endpoint.forceKey+"}";
                    exchange.getResponseHeaders().set("Content-Type","application/json");reply(exchange,200,config.getBytes(StandardCharsets.UTF_8));
                }
                case "video" -> {
                    var raw=endpoint.video.getAndSet(null);
                    if(!endpoint.encode||raw==null){reply(exchange,204,null);return;}
                    var pixels=raw.pixels();int stride=pixels.width()*4;byte[] flipped=new byte[pixels.rgba().length];
                    for(int y=0;y<pixels.height();y++)System.arraycopy(pixels.rgba(),(pixels.height()-1-y)*stride,flipped,y*stride,stride);
                    exchange.getResponseHeaders().set("X-Width",Integer.toString(pixels.width()));exchange.getResponseHeaders().set("X-Height",Integer.toString(pixels.height()));
                    exchange.getResponseHeaders().set("X-Time",Long.toString(raw.timeUs()));reply(exchange,200,flipped);
                }
                case "audio" -> {
                    var raw=endpoint.rawAudio.poll();
                    if(!endpoint.encode||!endpoint.quality.audio()||raw==null){reply(exchange,204,null);return;}
                    exchange.getResponseHeaders().set("X-Frames",Integer.toString(raw.frames()));exchange.getResponseHeaders().set("X-Time",Long.toString(raw.timeUs()));reply(exchange,200,raw.bytes());
                }
                case "next" -> {var packet=endpoint.incoming.poll();reply(exchange,packet==null?204:200,packet);}
                default -> reply(exchange,404,null);
            }
            else if(exchange.getRequestMethod().equals("POST")) {
                int limit=route.equals("packet")?StreamProtocol.MAX_FRAME_BYTES:2048;
                byte[] body=exchange.getRequestBody().readNBytes(limit+1);
                if(body.length>limit){reply(exchange,413,null);return;}
                if(route.equals("packet")&&endpoint.encode) {
                    boolean accepted=endpoint.encoded.offer(body);
                    if(!accepted)endpoint.forceKey++;
                    reply(exchange,accepted?200:429,null);
                } else if(route.equals("status")) {
                    var json=com.google.gson.JsonParser.parseString(new String(body,StandardCharsets.UTF_8)).getAsJsonObject();
                    endpoint.lastSeen=System.currentTimeMillis();endpoint.ready=json.has("ready")&&json.get("ready").getAsBoolean();
                    if(json.has("error"))endpoint.error=json.get("error").getAsString();
                    reply(exchange,200,null);
                } else reply(exchange,404,null);
            } else reply(exchange,405,null);
        }catch(Exception failure){WinLandCraftClient.LOGGER.debug("Codec bridge request failed",failure);}
    }
    private static void reply(HttpExchange exchange,int status,byte[] bytes)throws IOException {
        exchange.sendResponseHeaders(status,bytes==null?-1:bytes.length);
        if(bytes!=null)exchange.getResponseBody().write(bytes);
    }
    @Override public void close(){for(var endpoint:List.copyOf(endpoints.values()))endpoint.close();server.stop(0);http.shutdownNow();}
}
