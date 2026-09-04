// Runs only on the mod's unguessable, loopback-only codec endpoint, never inside a website.
(() => {
  'use strict';
  const canvas=document.getElementById('view'),context=canvas.getContext('2d',{alpha:false});
  const metrics={phase:'boot',audioState:'n/a',note:'',videoIn:0,videoOut:0,videoDrop:0,
    videoCodec:'n/a',videoAcceleration:'n/a',audioIn:0,audioOut:0,audioDrop:0,rendered:0,bytes:0,
    resets:0,batches:0,batchPackets:0,batchMax:0};
  let failed=false,ready=false,config,configRequest,configAt=0;
  const message=error=>String(error&&error.message||error||'Unknown codec error').slice(0,240);
  function note(value){metrics.note=message(value);}
  async function status(error) {
    try {
      await fetch('status',{method:'POST',body:JSON.stringify({ready,phase:metrics.phase,audioState:metrics.audioState,
        note:metrics.note,videoIn:metrics.videoIn,videoOut:metrics.videoOut,videoDrop:metrics.videoDrop,
        videoCodec:metrics.videoCodec,videoAcceleration:metrics.videoAcceleration,
        audioIn:metrics.audioIn,audioOut:metrics.audioOut,audioDrop:metrics.audioDrop,rendered:metrics.rendered,
        bytes:metrics.bytes,resets:metrics.resets,batches:metrics.batches,batchPackets:metrics.batchPackets,
        batchMax:metrics.batchMax,...(error?{error:message(error)}:{})})});
    } catch (_) {}
  }
  function fail(error) {
    if(failed)return;
    failed=true;ready=false;metrics.phase='failed';note(error);status(metrics.note);
  }
  addEventListener('error',event=>fail(event.error||event.message));
  addEventListener('unhandledrejection',event=>fail(event.reason));
  status();
  async function settings() {
    if(config&&performance.now()-configAt<=500)return config;
    if(!configRequest)configRequest=(async()=>{
      const response=await fetch('config');if(!response.ok)throw Error('Codec bridge disconnected');
      const next=await response.json();config=next;configAt=performance.now();return next;
    })();
    try{return await configRequest;}finally{configRequest=null;}
  }
  function normalizeH264Keyframe(bytes) {
    const units=[];
    for(let offset=0;offset+3<bytes.length;){
      let prefix=0;
      if(bytes[offset]===0&&bytes[offset+1]===0){
        if(bytes[offset+2]===1)prefix=3;
        else if(offset+3<bytes.length&&bytes[offset+2]===0&&bytes[offset+3]===1)prefix=4;
      }
      if(prefix){units.push({start:offset,payload:offset+prefix});offset+=prefix;}else offset++;
    }
    if(units.length<2)return false;
    const type=unit=>bytes[unit.payload]&31;
    const firstVcl=units.findIndex(unit=>{const value=type(unit);return value>=1&&value<=5;});
    if(firstVcl<0)return false;
    const moved=[];
    for(let index=firstVcl+1;index<units.length;index++)if(type(units[index])===7||type(units[index])===8)moved.push(index);
    if(!moved.length)return false;
    const movedSet=new Set(moved),order=[];
    for(let index=0;index<firstVcl;index++)order.push(index);
    order.push(...moved,firstVcl);
    for(let index=firstVcl+1;index<units.length;index++)if(!movedSet.has(index))order.push(index);
    const normalized=new Uint8Array(bytes.length);let write=0;
    if(units[0].start){normalized.set(bytes.subarray(0,units[0].start),write);write+=units[0].start;}
    for(const index of order){
      const end=index+1<units.length?units[index+1].start:bytes.length,part=bytes.subarray(units[index].start,end);
      normalized.set(part,write);write+=part.length;
    }
    bytes.set(normalized);return true;
  }
  function pack(kind,chunk,width,height,codec=0) {
    const bytes=new Uint8Array(24+chunk.byteLength),header=new DataView(bytes.buffer);
    header.setUint32(0,0x574c5632);header.setUint8(4,kind);header.setUint8(5,chunk.type==='key'?1:0);
    header.setUint16(6,width);header.setUint16(8,height);header.setUint16(10,codec);
    header.setBigInt64(12,BigInt(Math.max(0,chunk.timestamp)));header.setUint32(20,chunk.byteLength);
    const payload=bytes.subarray(24);chunk.copyTo(payload);
    // Some VA-API drivers return an IDR slice before their packed SPS/PPS.
    // Annex B decoders need those parameter sets first when joining a stream.
    if(kind===0&&codec===1&&chunk.type==='key')normalizeH264Keyframe(payload);
    return bytes;
  }
  function unpack(bytes) {
    const header=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength);
    if(bytes.length<=24||header.getUint32(0)!==0x574c5632||header.getUint32(20)!==bytes.length-24)throw Error('Invalid media packet');
    return {kind:header.getUint8(4),type:header.getUint8(5)?'key':'delta',w:header.getUint16(6),h:header.getUint16(8),codec:header.getUint16(10),
      timestamp:Number(header.getBigInt64(12)),data:bytes.subarray(24)};
  }
  function unpackBatch(bytes) {
    const packets=[];let offset=0;
    while(offset<bytes.length){
      if(bytes.length-offset<4)throw Error('Truncated media batch header');
      const length=new DataView(bytes.buffer,bytes.byteOffset+offset,4).getUint32(0);offset+=4;
      if(length<=24||length>768000||offset+length>bytes.length)throw Error(`Invalid media batch packet length ${length}`);
      packets.push(unpack(bytes.subarray(offset,offset+length)));offset+=length;
    }
    return packets;
  }
  function unpackRawAudioBatch(bytes) {
    const packets=[];let offset=0;
    while(offset<bytes.length){
      if(bytes.length-offset<12)throw Error('Truncated raw audio batch header');
      const header=new DataView(bytes.buffer,bytes.byteOffset+offset,12),frames=header.getUint32(0),
        timestamp=Number(header.getBigInt64(4)),length=frames*8;offset+=12;
      if(frames<1||frames>49152||length>bytes.length-offset)throw Error(`Invalid raw PCM packet: ${frames} frames, ${bytes.length-offset} bytes remain`);
      packets.push({frames,timestamp,data:bytes.subarray(offset,offset+length)});offset+=length;
    }
    return packets;
  }
  const VP9=0,H264=1;
  const codecName=codec=>codec===H264?'H.264':'VP9';
  function encoderConfig(codec,width,height,bitrate,hardwareAcceleration) {
    // Capture cadence is controlled before frames reach WebCodecs. Leaving the
    // optional framerate hint unset avoids excluding otherwise valid hardware
    // encoders when that live quality setting changes.
    const result={codec:codec===H264?'avc1.42E02A':'vp09.00.10.08',width,height,bitrate,
      latencyMode:'realtime',hardwareAcceleration,alpha:'discard'};
    if(codec===H264)result.avc={format:'annexb'};
    return result;
  }
  function decoderConfig(codec,width,height,hardwareAcceleration) {
    return {codec:codec===H264?'avc1.42E02A':'vp09.00.10.08',codedWidth:width,codedHeight:height,
      optimizeForLatency:true,hardwareAcceleration};
  }
  async function selectEncoderConfig(width,height,bitrate,blocked,mode=0) {
    const candidates=[
      {id:'h264-hardware',codec:H264,acceleration:'prefer-hardware'},
      {id:'vp9-hardware',codec:VP9,acceleration:'prefer-hardware'},
      {id:'vp9-software',codec:VP9,acceleration:'prefer-software'}
    ],probes=[];
    const selectedMode=[null,"h264-hardware","vp9-hardware","vp9-software"][mode];
    for(const candidate of candidates){
      if(selectedMode&&candidate.id!==selectedMode)continue;
      if(blocked.has(candidate.id)){probes.push(`${candidate.id}=blocked`);continue;}
      const config=encoderConfig(candidate.codec,width,height,bitrate,candidate.acceleration);
      try {
        const supported=(await VideoEncoder.isConfigSupported(config)).supported;
        probes.push(`${candidate.id}=${supported?'yes':'no'}`);
        if(supported)return {...candidate,config,probes:probes.join(',')};
      } catch(error){probes.push(`${candidate.id}=error:${message(error)}`);}
    }
    throw Error(`No supported H.264/VP9 video encoder is available (${probes.join(',')})`);
  }
  async function selectDecoderConfig(codec,width,height,blocked) {
    if(codec!==VP9&&codec!==H264)throw Error(`Unsupported video codec id ${codec}`);
    const probes=[];
    for(const acceleration of ['prefer-hardware','prefer-software']){
      const id=codec+'-'+acceleration;
      if(blocked.has(id)){probes.push(`${acceleration}=blocked`);continue;}
      const config=decoderConfig(codec,width,height,acceleration);
      try {
        const supported=(await VideoDecoder.isConfigSupported(config)).supported;
        probes.push(`${acceleration}=${supported?'yes':'no'}`);
        if(supported)return {id,codec,acceleration,config,probes:probes.join(',')};
      } catch(error){probes.push(`${acceleration}=error:${message(error)}`);}
    }
    throw Error(`No supported ${codecName(codec)} video decoder is available (${probes.join(',')})`);
  }
  async function encoder() {
    metrics.phase='checking-encoders';
    if(!self.VideoEncoder||!self.AudioEncoder)throw Error('This Chromium build does not expose WebCodecs encoders');
    let video,audio,videoSignature='',videoMode='',videoCodec=VP9,videoBroken=false,videoFailure='',audioBitrate=0;
    let width=0,height=0,keyAt=-Infinity,keyGeneration=-1;
    const blockedVideoModes=new Set();
    const outputQueue=[];let posting=false,keyNeeded=true;
    function countOutputDrop(packet){if(packet[4]===0)metrics.videoDrop++;else metrics.audioDrop++;}
    function clearOutputQueue(){while(outputQueue.length)countOutputDrop(outputQueue.shift());}
    async function drain() {
      if(posting)return;posting=true;
      try {
        while(outputQueue.length&&!failed){
          const packet=outputQueue.shift(),response=await fetch('packet',{method:'POST',body:packet});
          if(!response.ok){
            countOutputDrop(packet);clearOutputQueue();keyNeeded=true;note(`Bridge rejected encoded packet (HTTP ${response.status})`);
          }
        }
      } catch(error){fail(error);} finally {posting=false;}
    }
    function output(packet) {
      if(packet.length>768000||outputQueue.length>=96){
        countOutputDrop(packet);clearOutputQueue();keyNeeded=true;note('Encoded output queue overflow; requesting a new keyframe');return;
      }
      metrics.bytes+=packet.length;outputQueue.push(packet);drain();
    }
    function makeVideo(){return new VideoEncoder({
      output:chunk=>{metrics.videoOut++;output(pack(0,chunk,width,height,videoCodec));},
      error:error=>{
        if(videoMode)blockedVideoModes.add(videoMode);
        videoBroken=true;keyNeeded=true;metrics.videoDrop++;metrics.resets++;
        videoFailure=`${codecName(videoCodec)} ${metrics.videoAcceleration} encoder failed: ${message(error)}`;
        note(videoFailure);
      }
    });}
    video=makeVideo();
    audio=new AudioEncoder({output:chunk=>{metrics.audioOut++;output(pack(1,chunk,0,0));},error:fail});
    const initial=await settings();
    if(!(await AudioEncoder.isConfigSupported({codec:'opus',sampleRate:48000,numberOfChannels:2,bitrate:initial.audioBitrate})).supported)
      throw Error('Opus audio encoding is unavailable');
    metrics.phase='encoding';ready=true;await status();
    (async()=>{while(!failed){
      const current=await settings();
      if(videoBroken||video.state==='closed'){
        if(current.codecMode)throw Error(`${videoFailure||`${codecName(videoCodec)} encoder closed unexpectedly`}. Choose another codec in the pill.`);
        try{if(video.state!=='closed')video.close();}catch(_){}
        video=makeVideo();videoSignature='';videoMode='';videoBroken=false;videoFailure='';keyNeeded=true;
      }
      if(video.encodeQueueSize>2)await video.flush();
      const response=await fetch('video');
      if(response.status===204)continue;if(!response.ok)throw Error('Video source disconnected');
      const frameWidth=Number(response.headers.get('X-Width')),frameHeight=Number(response.headers.get('X-Height')),
        timestamp=Number(response.headers.get('X-Time')),data=await response.arrayBuffer();
      metrics.videoIn++;
      if(frameWidth<2||frameHeight<2||frameWidth>1920||frameHeight>1080||data.byteLength!==frameWidth*frameHeight*4)
        throw Error(`Invalid raw frame ${frameWidth}x${frameHeight} (${data.byteLength} bytes)`);
      const signature=[frameWidth,frameHeight,current.bitrate].join(':');
      if(signature!==videoSignature){
        if(video.state==='configured')await video.flush();
        const selected=await selectEncoderConfig(frameWidth,frameHeight,current.bitrate,blockedVideoModes,current.codecMode);
        width=frameWidth;height=frameHeight;videoCodec=selected.codec;videoMode=selected.id;
        video.configure(selected.config);videoSignature=signature;keyNeeded=true;
        metrics.videoCodec=codecName(videoCodec);metrics.videoAcceleration=selected.acceleration;
        note(`Video configured ${frameWidth}:${frameHeight}:${current.fps}:${current.bitrate}, ${metrics.videoCodec}, ${selected.acceleration}; probes ${selected.probes}`);
      }
      if(current.forceKey!==keyGeneration){keyGeneration=current.forceKey;keyNeeded=true;}
      const key=keyNeeded||timestamp-keyAt>=1000000;
      const frame=new VideoFrame(data,{format:'RGBA',codedWidth:width,codedHeight:height,timestamp,
        layout:[{offset:0,stride:width*4}]});
      try{
        // WebCodecs closes an encoder before dispatching its asynchronous error
        // callback. Do not race that callback and replace the native failure
        // with the generic exception from encode() on an already closed codec.
        if(videoBroken||video.state==='closed')continue;
        video.encode(frame,{keyFrame:key});
      }catch(error){throw Error(videoFailure||message(error));}finally{frame.close();}
      if(key){keyAt=timestamp;keyNeeded=false;}
    }})().catch(fail);
    (async()=>{while(!failed){
      const current=await settings(),response=await fetch('audio');
      if(response.status===204)continue;if(!response.ok)throw Error('Audio source disconnected');
      const packets=unpackRawAudioBatch(new Uint8Array(await response.arrayBuffer()));metrics.audioIn+=packets.length;
      if(!current.audio){metrics.audioDrop+=packets.length;continue;}
      if(audioBitrate!==current.audioBitrate){
        if(audio.state==='configured')await audio.flush();
        audio.configure({codec:'opus',sampleRate:48000,numberOfChannels:2,bitrate:current.audioBitrate});audioBitrate=current.audioBitrate;
      }
      for(const packet of packets){
        // Audio is low-bandwidth and continuity-sensitive. Drain genuine codec
        // backpressure instead of deleting PCM and creating an audible hole.
        if(audio.encodeQueueSize>16)await audio.flush();
        const samples=new AudioData({format:'f32-planar',sampleRate:48000,numberOfFrames:packet.frames,
          numberOfChannels:2,timestamp:packet.timestamp,data:packet.data});
        try{audio.encode(samples);}finally{samples.close();}
      }
    }})().catch(fail);
  }
  async function decoder() {
    metrics.phase='checking-decoders';
    if(!self.VideoDecoder)throw Error('This Chromium build does not expose the WebCodecs video decoder');
    let sound=null;
    if(self.AudioDecoder&&self.AudioContext){
      try{sound=new AudioContext({sampleRate:48000,latencyHint:'interactive'});metrics.audioState=sound.state;}
      catch(error){metrics.audioState='unavailable';note(`Web Audio unavailable: ${message(error)}`);}
    } else {metrics.audioState='unavailable';note('This Chromium build does not expose Opus/Web Audio playback');}
    let video,audio=null,audioBroken=false,videoBroken=false,videoMode='',videoCodec=VP9;
    let needKey=true,shape='',lastVideo=0;
    let audioBaseTime=null,audioBaseClock=0,lastAudio=0;
    const playing=new Set(),blockedVideoModes=new Set();
    function clearAudio() {
      audioBaseTime=null;lastAudio=0;
      for(const node of playing){try{node.stop();}catch(_){}playing.delete(node);try{node.disconnect();}catch(_){}}
    }
    function makeVideo() {return new VideoDecoder({output:frame=>{
      metrics.videoOut++;
      // Chromium throttles timers in hidden OSR pages. Frames already arrive at
      // the sender's capture cadence, so present from the unthrottled WebCodecs
      // callback instead of accumulating them behind a timer-driven queue.
      try {
        if(canvas.width!==frame.displayWidth||canvas.height!==frame.displayHeight){canvas.width=frame.displayWidth;canvas.height=frame.displayHeight;}
        context.drawImage(frame,0,0,canvas.width,canvas.height);metrics.rendered++;
      } catch(error){metrics.videoDrop++;fail(`Video presentation failed: ${message(error)}`);}
      finally {frame.close();}
    },error:error=>{
      if(videoMode)blockedVideoModes.add(videoMode);
      videoBroken=true;needKey=true;metrics.videoDrop++;metrics.resets++;
      note(`${codecName(videoCodec)} ${metrics.videoAcceleration} decoder failed; falling back: ${message(error)}`);
    }});}
    function makeAudio() {return new AudioDecoder({output:samples=>{
      try {
        if(!sound||sound.state!=='running'){metrics.audioDrop++;audioBaseTime=null;return;}
        const timestamp=samples.timestamp;
        if(audioBaseTime===null){audioBaseTime=timestamp;audioBaseClock=sound.currentTime+.12;lastAudio=timestamp;}
        let when=audioBaseClock+(timestamp-audioBaseTime)/1000000;
        if(timestamp<lastAudio||when<sound.currentTime-.12||when>sound.currentTime+1){
          metrics.resets++;note(`Audio clock resynchronized by ${Math.round((when-sound.currentTime)*1000)} ms`);
          clearAudio();audioBaseTime=timestamp;audioBaseClock=sound.currentTime+.12;lastAudio=timestamp;when=audioBaseClock;
        }
        const buffer=sound.createBuffer(samples.numberOfChannels,samples.numberOfFrames,samples.sampleRate);
        for(let channel=0;channel<samples.numberOfChannels;channel++)
          samples.copyTo(buffer.getChannelData(channel),{planeIndex:channel,format:'f32-planar'});
        const source=sound.createBufferSource();source.buffer=buffer;source.connect(sound.destination);playing.add(source);
        source.onended=()=>{playing.delete(source);source.disconnect();};source.start(Math.max(sound.currentTime,when));
        lastAudio=timestamp;metrics.audioOut++;
      } catch(error){metrics.audioDrop++;note(`Audio output failed: ${message(error)}`);} finally {samples.close();}
    },error:error=>{audioBroken=true;metrics.audioDrop++;metrics.resets++;note(`Opus decoder reset: ${message(error)}`);}});}
    video=makeVideo();
    if(sound){audio=makeAudio();audio.configure({codec:'opus',sampleRate:48000,numberOfChannels:2});}
    const resumeSound=()=>{if(sound&&sound.state!=='running')sound.resume().catch(error=>{metrics.audioDrop++;note(`Audio resume failed: ${message(error)}`);status();});};
    document.addEventListener('pointerdown',resumeSound);document.addEventListener('mousedown',resumeSound);
    if(sound)sound.addEventListener('statechange',()=>{
      metrics.audioState=sound.state;clearAudio();note(`Web Audio state changed to ${sound.state}`);status();
    });
    metrics.phase='decoding';ready=true;await status();
    while(!failed){
      const response=await fetch('next');if(response.status===204)continue;if(!response.ok)throw Error('Stream disconnected');
      const packets=unpackBatch(new Uint8Array(await response.arrayBuffer()));
      metrics.batches++;metrics.batchPackets+=packets.length;metrics.batchMax=Math.max(metrics.batchMax,packets.length);
      for(let packetIndex=0;packetIndex<packets.length;packetIndex++){
        const packet=packets[packetIndex];
        if(packet.kind===0){
          metrics.videoIn++;
          if(packet.w<2||packet.h<2||packet.w>1920||packet.h>1080)throw Error(`Invalid video dimensions ${packet.w}x${packet.h}`);
          if(lastVideo&&packet.timestamp-lastVideo>2000000){needKey=true;metrics.videoDrop++;note('Video timestamp gap exceeded two seconds');}
          if(!videoBroken&&video.decodeQueueSize>8){needKey=true;metrics.videoDrop++;note('Video decoder backpressure; waiting for keyframe');}
          if(needKey&&packet.type!=='key'){metrics.videoDrop++;continue;}
          const nextShape=packet.codec+':'+packet.w+':'+packet.h;
          if(needKey||videoBroken||nextShape!==shape||video.state!=='configured'){
            if(packet.type!=='key'){needKey=true;metrics.videoDrop++;continue;}
            try{if(video.state!=='closed')video.close();}catch(_){}
            video=makeVideo();
            const selected=await selectDecoderConfig(packet.codec,packet.w,packet.h,blockedVideoModes);
            videoCodec=packet.codec;videoMode=selected.id;video.configure(selected.config);videoBroken=false;
            metrics.videoCodec=codecName(videoCodec);metrics.videoAcceleration=selected.acceleration;
            shape=nextShape;needKey=false;
            metrics.resets++;note(`Video configured ${packet.w}:${packet.h}, ${metrics.videoCodec}, ${selected.acceleration}; probes ${selected.probes}`);
          }
          lastVideo=packet.timestamp;video.decode(new EncodedVideoChunk(packet));
        } else if(packet.kind===1){
          metrics.audioIn++;
          if(!sound){metrics.audioDrop++;continue;}
          if(audioBroken||!audio||audio.state==='closed'){
            audio=makeAudio();audio.configure({codec:'opus',sampleRate:48000,numberOfChannels:2});audioBroken=false;clearAudio();
          }
          if(audio.decodeQueueSize>=16)await audio.flush();
          audio.decode(new EncodedAudioChunk({...packet,type:'key'}));
        }
      }
    }
  }
  setInterval(()=>{if(!failed)status();},1000);
  (async()=>{const current=await settings();if(current.encode)await encoder();else await decoder();})().catch(fail);
})();
