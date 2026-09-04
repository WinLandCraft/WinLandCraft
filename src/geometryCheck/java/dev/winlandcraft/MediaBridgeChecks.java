package dev.winlandcraft;

import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;

/** Exercise the actual HTTP handler without starting native CEF. */
final class MediaBridgeChecks {
    @SuppressWarnings("unchecked")
    static void run() throws Exception {
        int oldMode=ModSettings.streamCodecMode;ModSettings.streamCodecMode=3;
        try(var bridge=new MediaBridge();var client=HttpClient.newHttpClient()) {
            var endpoint=bridge.new Endpoint(true,"HTTP regression");
            var endpoints=MediaBridge.class.getDeclaredField("endpoints");endpoints.setAccessible(true);
            ((Map<String,MediaBridge.Endpoint>)endpoints.get(bridge)).put(endpoint.token,endpoint);
            var originField=MediaBridge.class.getDeclaredField("origin");originField.setAccessible(true);
            String origin=(String)originField.get(bridge),base=origin+"/"+endpoint.token+"/";
            var config=client.send(HttpRequest.newBuilder(URI.create(base+"config")).timeout(Duration.ofSeconds(3)).build(),HttpResponse.BodyHandlers.ofString());
            check(config.statusCode()==200,"config GET accepted");
            check(com.google.gson.JsonParser.parseString(config.body()).getAsJsonObject().get("codecMode").getAsInt()==3,"sender mode reaches worker config");
            check(bridge.new Endpoint(false,"decoder policy").codecMode==0,"sender preference does not override viewer decoder policy");
            byte[] key=new StreamMedia(StreamMedia.VIDEO,true,1,1280,720,new byte[]{1}).pack();
            byte[] audio=new StreamMedia(StreamMedia.AUDIO,true,2,0,0,new byte[]{2}).pack();
            var later=client.send(HttpRequest.newBuilder(URI.create(base+"packets")).timeout(Duration.ofSeconds(3))
                    .header("Origin","null").header("X-WinLandCraft-Sequence","1")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(batch(audio))).build(),HttpResponse.BodyHandlers.discarding());
            check(later.statusCode()==200&&endpoint.encoded.poll()==null,"out-of-order codec batch waits for its predecessor");
            var first=client.send(HttpRequest.newBuilder(URI.create(base+"packets")).timeout(Duration.ofSeconds(3))
                    .header("Origin","null").header("X-WinLandCraft-Sequence","0")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(batch(key))).build(),HttpResponse.BodyHandlers.discarding());
            check(first.statusCode()==200,"first codec batch accepted");
            check(java.util.Arrays.equals(endpoint.encoded.poll(),key)&&java.util.Arrays.equals(endpoint.encoded.poll(),audio),"concurrent codec batches restored to sequence order");
            check(endpoint.mediaRequests.get()==2&&endpoint.mediaRequestPackets.get()==2&&endpoint.mediaRequestReordered.get()==1,"codec batch metrics recorded");
            var request=HttpRequest.newBuilder(URI.create(base+"status")).timeout(Duration.ofSeconds(3))
                    .header("Origin",origin).POST(HttpRequest.BodyPublishers.ofString("{\"ready\":true,\"phase\":\"encoding\"}"));
            var status=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
            check(status.statusCode()==200&&endpoint.ready&&endpoint.statusRequests.get()==1,"status POST starts codec");
            var foreign=client.send(request.setHeader("Origin","https://example.invalid").build(),HttpResponse.BodyHandlers.ofString());
            check(foreign.statusCode()==403&&endpoint.statusRequests.get()==1,"foreign origin rejected");
            var opaque=client.send(request.setHeader("Origin","null").build(),HttpResponse.BodyHandlers.ofString());
            check(opaque.statusCode()==200&&endpoint.statusRequests.get()==2,"CEF opaque status origin accepted for valid endpoint");
            var unknown=client.send(HttpRequest.newBuilder(URI.create(base+"unknown")).timeout(Duration.ofSeconds(3))
                    .header("Origin","null").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());
            check(unknown.statusCode()==403,"opaque origin rejected outside codec POST routes");
            var missing=client.send(HttpRequest.newBuilder(URI.create(origin+"/invalid-token/status")).timeout(Duration.ofSeconds(3))
                    .header("Origin","null").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());
            check(missing.statusCode()==404&&endpoint.statusRequests.get()==2,"opaque origin cannot bypass endpoint token");
        }finally{ModSettings.streamCodecMode=oldMode;}
    }
    private static byte[] batch(byte[]...packets) {
        int bytes=0;for(byte[] packet:packets)bytes+=Integer.BYTES+packet.length;
        var result=ByteBuffer.allocate(bytes);for(byte[] packet:packets){result.putInt(packet.length);result.put(packet);}return result.array();
    }
    private static void check(boolean valid,String message){if(!valid)throw new AssertionError(message);}
}
