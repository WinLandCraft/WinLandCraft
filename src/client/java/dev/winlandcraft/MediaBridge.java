package dev.winlandcraft;

import com.cinemamod.mcef.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Private loopback binary bridge to Chromium's WebCodecs. No external service or executable. */
final class MediaBridge implements AutoCloseable {
    private static final long LONG_POLL_MILLIS=1_000;
    private static final AtomicInteger NEXT_ENDPOINT=new AtomicInteger();
    private final HttpServer server;
    private final ExecutorService http=Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<String,Endpoint> endpoints=new ConcurrentHashMap<>();
    private final AtomicLong lastFailureLog=new AtomicLong();
    private final String origin;
    MediaBridge() throws IOException {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),16);
        origin="http://127.0.0.1:"+server.getAddress().getPort();
        server.createContext("/",this::handle);server.setExecutor(http);server.start();
        WinLandCraftClient.LOGGER.info("Stream codec bridge listening on loopback for {} {} with Java {}",
                System.getProperty("os.name"),System.getProperty("os.arch"),System.getProperty("java.version"));
    }
    static final class Queue {
        record Batch(byte[][] packets,int bytes){}
        private final ArrayDeque<byte[]> packets=new ArrayDeque<>();
        private int bytes;
        private boolean needKey=true;
        private long offered,accepted,invalid,rejectedForKey,dropped,resets;
        synchronized boolean offer(byte[] packet) {
            offered++;
            var media=StreamMedia.header(packet);if(media==null){invalid++;return false;}
            return offer(packet,media);
        }
        private synchronized boolean offer(byte[] packet,StreamMedia.Header media) {
            if(bytes+packet.length>2_000_000||packets.size()>=128){dropped+=packets.size();packets.clear();bytes=0;needKey=true;resets++;}
            if(needKey) {
                if(media.kind()!=StreamMedia.VIDEO||!media.key()){rejectedForKey++;return false;}
                needKey=false;
            }
            packets.add(packet);bytes+=packet.length;accepted++;notifyAll();return true;
        }
        synchronized byte[] poll(){var packet=packets.poll();if(packet!=null)bytes-=packet.length;return packet;}
        synchronized Batch pollBatch() {
            return drainBatch();
        }
        synchronized Batch pollBatch(long waitMillis) {
            long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(waitMillis);
            while(packets.isEmpty()&&waitMillis>0) {
                try {wait(waitMillis);} catch(InterruptedException interrupted){Thread.currentThread().interrupt();return null;}
                waitMillis=TimeUnit.NANOSECONDS.toMillis(Math.max(0,deadline-System.nanoTime()));
            }
            return drainBatch();
        }
        private Batch drainBatch() {
            if(packets.isEmpty())return null;
            int count=0,total=0;
            for(var packet:packets) {
                int next=total+Integer.BYTES+packet.length;
                if(count>0&&(count>=64||next>1_500_000))break;
                total=next;count++;
            }
            var batch=new byte[count][];
            for(int i=0;i<count;i++) {
                var packet=packets.remove();bytes-=packet.length;
                batch[i]=packet;
            }
            return new Batch(batch,total);
        }
        synchronized void clear(){dropped+=packets.size();packets.clear();bytes=0;needKey=true;resets++;notifyAll();}
        synchronized Stats stats(){return new Stats(offered,accepted,invalid,rejectedForKey,dropped,resets,packets.size(),bytes,needKey);}
        record Stats(long offered,long accepted,long invalid,long rejectedForKey,long dropped,long resets,int queued,int bytes,boolean needKey){}
    }
    record RawVideo(StreamCapture.Pixels pixels,long timeUs){}
    record RawAudio(StreamAudio.Packet packet,int frames,long timeUs){}
    record RawAudioBatch(RawAudio[] packets,int bytes) {
        void release(){for(var packet:packets)packet.packet().release();}
    }
    final class Endpoint implements AutoCloseable {
        final int id=NEXT_ENDPOINT.incrementAndGet();
        final String token=UUID.randomUUID().toString()+UUID.randomUUID();
        final boolean encode;
        final String label;
        private final long startedNanos=System.nanoTime();
        final AtomicReference<RawVideo> video=new AtomicReference<>();
        final ArrayBlockingQueue<RawAudio> rawAudio=new ArrayBlockingQueue<>(32);
        final Queue encoded=new Queue(),incoming=new Queue();
        final AtomicLong rawVideoFrames=new AtomicLong(),rawVideoReplaced=new AtomicLong(),rawAudioPackets=new AtomicLong(),rawAudioDropped=new AtomicLong();
        final AtomicLong rawAudioBatches=new AtomicLong(),rawAudioBatchPackets=new AtomicLong(),rawAudioBatchMax=new AtomicLong();
        final AtomicLong indexRequests=new AtomicLong(),scriptRequests=new AtomicLong(),configRequests=new AtomicLong(),statusRequests=new AtomicLong();
        volatile StreamQuality quality=StreamQuality.current();
        volatile boolean ready,closed;
        volatile boolean renderedVideo;
        volatile String error="",phase="created",audioState="n/a",videoCodec="n/a",videoAcceleration="n/a",workerNote="";
        volatile long workerVideoIn,workerVideoOut,workerVideoDrop,workerAudioIn,workerAudioOut,workerAudioDrop,workerBytes,workerResets,workerRendered;
        volatile long workerBatches,workerBatchPackets,workerBatchMax;
        volatile long lastRequest=System.currentTimeMillis(),lastStatus=lastRequest,forceKey;
        MCEFBrowser browser;
        private int width=2,height=2;
        private long nextGesture,nextHealth;
        private String lastHealthState="";
        Endpoint(boolean encode,String label){this.encode=encode;this.label=label;}
        long elapsedTimeUs(){return Math.max(0,(System.nanoTime()-startedNanos)/1_000);}
        void open() {
            endpoints.put(token,this);
            try {
                browser=MCEF.createBrowser(origin+"/"+token+"/index",false,2,2);
                browser.setCursorChangeListener(cursor->{});browser.setFocus(false);
            }catch(RuntimeException|LinkageError failure){endpoints.remove(token);throw failure;}
        }
        boolean wantsVideo(){return ready&&!closed&&error.isEmpty()&&video.get()==null;}
        synchronized void video(StreamCapture.Pixels pixels){
            if(closed||!encode){pixels.close();return;}
            rawVideoFrames.incrementAndGet();
            var previous=video.getAndSet(new RawVideo(pixels,elapsedTimeUs()));
            if(previous!=null){previous.pixels().close();rawVideoReplaced.incrementAndGet();}
            notifyAll();
        }
        synchronized RawVideo takeVideo(long waitMillis) {
            if(video.get()==null&&!closed)try{wait(waitMillis);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            return video.getAndSet(null);
        }
        RawAudioBatch takeAudioBatch(long waitMillis) {
            RawAudio first;
            try{first=rawAudio.poll(waitMillis,TimeUnit.MILLISECONDS);}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();return null;}
            if(first==null)return null;
            var packets=new ArrayList<RawAudio>(32);packets.add(first);rawAudio.drainTo(packets,31);
            int bytes=0;for(var packet:packets)bytes+=Integer.BYTES+Long.BYTES+packet.packet().data().length;
            rawAudioBatches.incrementAndGet();rawAudioBatchPackets.addAndGet(packets.size());
            rawAudioBatchMax.accumulateAndGet(packets.size(),Math::max);
            return new RawAudioBatch(packets.toArray(RawAudio[]::new),bytes);
        }
        /** Consumes one shared audio-packet owner in all cases. */
        synchronized void audio(StreamAudio.Packet audio,int frames,long timeUs) {
            if(!ready||closed||!quality.audio()){audio.release();return;}
            long current=elapsedTimeUs();
            if(timeUs<current-1_000_000||timeUs>current+1_000_000)timeUs=current;
            rawAudioPackets.incrementAndGet();
            var packet=new RawAudio(audio,frames,timeUs);
            if(!rawAudio.offer(packet)){
                var dropped=rawAudio.poll();
                if(dropped!=null){dropped.packet().release();rawAudioDropped.incrementAndGet();}
                if(!rawAudio.offer(packet)){audio.release();rawAudioDropped.incrementAndGet();}
            }
        }
        synchronized StreamMedia.Header receive(byte[] packet) {
            var media=StreamMedia.header(packet);if(media==null||closed)return null;
            if(media.kind()==StreamMedia.VIDEO&&(media.width()!=width||media.height()!=height)) {
                width=media.width();height=media.height();browser.resize(width,height);
            }
            incoming.offer(packet,media);return media;
        }
        void updateStatus(com.google.gson.JsonObject json) {
            lastStatus=lastRequest=System.currentTimeMillis();ready=readBoolean(json,"ready");
            phase=readString(json,"phase",phase);audioState=readString(json,"audioState",audioState);
            videoCodec=readString(json,"videoCodec",videoCodec);videoAcceleration=readString(json,"videoAcceleration",videoAcceleration);
            workerNote=readString(json,"note",workerNote);
            workerVideoIn=readLong(json,"videoIn");workerVideoOut=readLong(json,"videoOut");workerVideoDrop=readLong(json,"videoDrop");
            workerAudioIn=readLong(json,"audioIn");workerAudioOut=readLong(json,"audioOut");workerAudioDrop=readLong(json,"audioDrop");
            workerBytes=readLong(json,"bytes");workerResets=readLong(json,"resets");workerRendered=readLong(json,"rendered");renderedVideo=workerRendered>0;
            workerBatches=readLong(json,"batches");workerBatchPackets=readLong(json,"batchPackets");workerBatchMax=readLong(json,"batchMax");
            if(json.has("error"))error=readString(json,"error","Unknown codec worker error");
        }
        private static boolean readBoolean(com.google.gson.JsonObject json,String name) {
            try{return json.has(name)&&json.get(name).getAsBoolean();}catch(RuntimeException ignored){return false;}
        }
        private static long readLong(com.google.gson.JsonObject json,String name) {
            try{return json.has(name)?Math.max(0,json.get(name).getAsLong()):0;}catch(RuntimeException ignored){return 0;}
        }
        private static String readString(com.google.gson.JsonObject json,String name,String fallback) {
            try{
                if(!json.has(name))return fallback;
                String value=json.get(name).getAsString().replace('\n',' ').replace('\r',' ');
                return value.substring(0,Math.min(240,value.length()));
            }
            catch(RuntimeException ignored){return fallback;}
        }
        void tick() {
            if(closed)return;
            long now=System.currentTimeMillis();
            long heartbeat=ready?Math.max(lastStatus,lastRequest):lastStatus;
            if(now-heartbeat>15_000&&error.isEmpty())error=ready?"Codec worker stopped responding":"Codec page failed to start";
            // Native CEF input activates Web Audio for the local playback surface. Never sent to a website.
            if(!encode&&ready&&now>=nextGesture) {
                nextGesture=now+1000;
                browser.sendMouseMove(1,1);browser.sendMousePress(1,1,0);browser.sendMouseRelease(1,1,0);
            }
            String state=ready+"/"+phase+"/"+audioState+"/"+error;
            if(!state.equals(lastHealthState)||now>=nextHealth){logHealth("health");lastHealthState=state;nextHealth=now+10_000;}
        }
        void logHealth(String event) {
            var output=encoded.stats();var input=incoming.stats();
            WinLandCraftClient.LOGGER.info("Stream codec {} #{} ({}): ready={}, phase={}, codec={}, acceleration={}, audio={}, bridge requests={}/{}/{}/{} index/script/config/status, worker video={}/{} drop={}, audio={}/{} drop={}, rendered={}, bytes={}, resets={}, receive batches={}/{} packets max={}, raw video={} replaced={}, raw audio={} drop={} batches={}/{} max={}, outgoing={} queued/{} accepted/{} dropped/{} key-wait/{} reset, incoming={} queued/{} accepted/{} dropped/{} key-wait/{} reset{}",
                    event,id,label,ready,phase,videoCodec,videoAcceleration,audioState,
                    indexRequests.get(),scriptRequests.get(),configRequests.get(),statusRequests.get(),
                    workerVideoIn,workerVideoOut,workerVideoDrop,workerAudioIn,workerAudioOut,workerAudioDrop,
                    workerRendered,workerBytes,workerResets,workerBatches,workerBatchPackets,workerBatchMax,
                    rawVideoFrames.get(),rawVideoReplaced.get(),rawAudioPackets.get(),rawAudioDropped.get(),
                    rawAudioBatches.get(),rawAudioBatchPackets.get(),rawAudioBatchMax.get(),
                    output.queued(),output.accepted(),output.dropped(),output.rejectedForKey(),output.resets(),
                    input.queued(),input.accepted(),input.dropped(),input.rejectedForKey(),input.resets(),
                    workerNote.isEmpty()?"":", note="+workerNote);
        }
        @Override public void close() {
            MCEFBrowser closingBrowser;
            synchronized(this) {
                if(closed)return;logHealth("closing");closed=true;endpoints.remove(token);
                var pendingVideo=video.getAndSet(null);if(pendingVideo!=null)pendingVideo.pixels().close();notifyAll();
                RawAudio audio;while((audio=rawAudio.poll())!=null)audio.packet().release();encoded.clear();incoming.clear();
                closingBrowser=browser;browser=null;
            }
            if(closingBrowser!=null)closingBrowser.close();
        }
    }
    Endpoint create(boolean encode){return create(encode,encode?"sender":"receiver");}
    Endpoint create(boolean encode,String label){var endpoint=new Endpoint(encode,label);endpoint.open();return endpoint;}
    private void handle(HttpExchange exchange) {
        try(exchange) {
            if(!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {reply(exchange,403,null);return;}
            String host=exchange.getRequestHeaders().getFirst("Host"),from=exchange.getRequestHeaders().getFirst("Origin");
            if(!origin.substring(7).equals(host)||from!=null&&!origin.equals(from)){reply(exchange,403,null);return;}
            String path=exchange.getRequestURI().getPath();int separator=path.indexOf('/',1);
            var endpoint=separator>1&&path.indexOf('/',separator+1)<0?endpoints.get(path.substring(1,separator)):null;
            if(endpoint==null||endpoint.closed){reply(exchange,404,null);return;}
            exchange.getResponseHeaders().set("Cache-Control","no-store");
            exchange.getResponseHeaders().set("Referrer-Policy","no-referrer");
            exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
            String route=path.substring(separator+1);
            endpoint.lastRequest=System.currentTimeMillis();
            if(exchange.getRequestMethod().equals("GET"))switch(route) {
                case "index" -> {
                    endpoint.indexRequests.incrementAndGet();
                    exchange.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");
                    exchange.getResponseHeaders().set("Content-Security-Policy","default-src 'none'; script-src 'self'; connect-src 'self'; style-src 'unsafe-inline'; media-src blob:");
                    reply(exchange,200,("<!doctype html><meta charset='utf-8'><style>html,body{margin:0;background:#181d29;overflow:hidden}canvas{width:100vw;height:100vh}</style><canvas id='view'></canvas><script src='worker.js'></script>").getBytes(StandardCharsets.UTF_8));
                }
                case "worker.js" -> {
                    endpoint.scriptRequests.incrementAndGet();
                    exchange.getResponseHeaders().set("Content-Type","text/javascript; charset=utf-8");
                    try(var input=MediaBridge.class.getResourceAsStream("/assets/winlandcraft/stream-worker.js")) {
                        if(input==null)throw new IOException("Missing codec worker");reply(exchange,200,input.readAllBytes());
                    }
                }
                case "config" -> {
                    endpoint.configRequests.incrementAndGet();
                    var q=endpoint.quality;
                    String config="{\"encode\":"+endpoint.encode+",\"fps\":"+q.fps()+",\"bitrate\":"+(q.kbps()*1000)+",\"audio\":"+q.audio()+",\"audioBitrate\":"+(q.audioKbps()*1000)+",\"forceKey\":"+endpoint.forceKey+"}";
                    exchange.getResponseHeaders().set("Content-Type","application/json");reply(exchange,200,config.getBytes(StandardCharsets.UTF_8));
                }
                case "video" -> {
                    if(!endpoint.encode){reply(exchange,404,null);return;}
                    var raw=endpoint.takeVideo(LONG_POLL_MILLIS);
                    if(raw==null){reply(exchange,204,null);return;}
                    try(var pixels=raw.pixels()) {
                        exchange.getResponseHeaders().set("X-Width",Integer.toString(pixels.width()));exchange.getResponseHeaders().set("X-Height",Integer.toString(pixels.height()));
                        exchange.getResponseHeaders().set("X-Time",Long.toString(raw.timeUs()));reply(exchange,200,pixels.rgba());
                    }
                }
                case "audio" -> {
                    if(!endpoint.encode){reply(exchange,404,null);return;}
                    var batch=endpoint.takeAudioBatch(LONG_POLL_MILLIS);
                    if(batch==null){reply(exchange,204,null);return;}
                    try {if(!endpoint.quality.audio())reply(exchange,204,null);else reply(exchange,batch);}
                    finally {batch.release();}
                }
                case "next" -> {
                    if(endpoint.encode){reply(exchange,404,null);return;}
                    var batch=endpoint.incoming.pollBatch(LONG_POLL_MILLIS);
                    if(batch==null)reply(exchange,204,null);else reply(exchange,batch);
                }
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
                    endpoint.statusRequests.incrementAndGet();
                    var json=com.google.gson.JsonParser.parseString(new String(body,StandardCharsets.UTF_8)).getAsJsonObject();
                    endpoint.updateStatus(json);
                    reply(exchange,200,null);
                } else reply(exchange,404,null);
            } else reply(exchange,405,null);
        }catch(Exception failure){
            long now=System.currentTimeMillis(),previous=lastFailureLog.get();
            if(now-previous>=10_000&&lastFailureLog.compareAndSet(previous,now))WinLandCraftClient.LOGGER.warn("Codec bridge request failed (further failures are rate-limited)",failure);
        }
    }
    private static void reply(HttpExchange exchange,int status,byte[] bytes)throws IOException {
        exchange.sendResponseHeaders(status,bytes==null?-1:bytes.length);
        if(bytes!=null)exchange.getResponseBody().write(bytes);
    }
    private static void reply(HttpExchange exchange,Queue.Batch batch)throws IOException {
        exchange.sendResponseHeaders(200,batch.bytes());
        var output=new DataOutputStream(exchange.getResponseBody());
        for(var packet:batch.packets()){output.writeInt(packet.length);output.write(packet);}
    }
    private static void reply(HttpExchange exchange,RawAudioBatch batch)throws IOException {
        exchange.sendResponseHeaders(200,batch.bytes());
        var output=new DataOutputStream(exchange.getResponseBody());
        for(var packet:batch.packets()){
            output.writeInt(packet.frames());output.writeLong(packet.timeUs());output.write(packet.packet().data());
        }
    }
    @Override public void close(){for(var endpoint:List.copyOf(endpoints.values()))endpoint.close();server.stop(0);http.shutdownNow();}
}
