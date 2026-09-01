// Runs only on the mod's unguessable, loopback-only codec endpoint, never inside a website.
(() => {
  'use strict';
  const canvas = document.getElementById('view'), context = canvas.getContext('2d', {alpha:false});
  const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
  let failed = false, ready = false, config, configAt = 0;
  async function status(error) {
    if (error) { failed = true; ready = false; }
    try { await fetch('status', {method:'POST', body:JSON.stringify({ready, ...(error ? {error:String(error).slice(0,500)} : {})})}); } catch (_) {}
  }
  function fail(error) { status(error.message || error); }
  async function settings() {
    if (performance.now() - configAt > 300 || !config) {
      const response = await fetch('config'); if (!response.ok) throw Error('Codec bridge disconnected');
      config = await response.json(); configAt = performance.now();
    }
    return config;
  }
  function pack(kind, chunk, width, height) {
    const bytes = new Uint8Array(24 + chunk.byteLength), header = new DataView(bytes.buffer);
    header.setUint32(0, 0x574c5632); header.setUint8(4,kind); header.setUint8(5,chunk.type === 'key' ? 1 : 0);
    header.setUint16(6,width); header.setUint16(8,height); header.setBigInt64(12,BigInt(Math.max(0,chunk.timestamp)));
    header.setUint32(20,chunk.byteLength); chunk.copyTo(bytes.subarray(24)); return bytes;
  }
  function unpack(bytes) {
    const h=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength);
    if (bytes.length<=24 || h.getUint32(0)!==0x574c5632 || h.getUint32(20)!==bytes.length-24) throw Error('Invalid media packet');
    return {kind:h.getUint8(4), type:h.getUint8(5) ? 'key' : 'delta', w:h.getUint16(6),h:h.getUint16(8),timestamp:Number(h.getBigInt64(12)),data:bytes.subarray(24)};
  }
  async function encoder() {
    if (!self.VideoEncoder || !self.AudioEncoder) throw Error('This Chromium build does not expose WebCodecs encoders');
    let video, audio, videoSignature='', audioBitrate=0, width=0,height=0,keyAt=-Infinity,keyGeneration=-1;
    const outputQueue=[]; let posting=false, keyNeeded=true;
    async function drain() {
      if(posting)return; posting=true;
      try { while(outputQueue.length && !failed) {
        const response=await fetch('packet',{method:'POST',body:outputQueue.shift()});
        if(!response.ok){outputQueue.length=0;keyNeeded=true;}
      }} catch(e){fail(e);} finally {posting=false;}
    }
    function output(packet) {
      if(packet.length>768000 || outputQueue.length>=96){outputQueue.length=0;keyNeeded=true;return;}
      outputQueue.push(packet);drain();
    }
    video=new VideoEncoder({output:chunk=>output(pack(0,chunk,width,height)),error:fail});
    audio=new AudioEncoder({output:chunk=>output(pack(1,chunk,0,0)),error:fail});
    const initial=await settings();
    if(!(await VideoEncoder.isConfigSupported({codec:'vp09.00.10.08',width:1280,height:720,bitrate:initial.bitrate,framerate:initial.fps,latencyMode:'realtime'})).supported)
      throw Error('VP9 video encoding is unavailable');
    if(!(await AudioEncoder.isConfigSupported({codec:'opus',sampleRate:48000,numberOfChannels:2,bitrate:initial.audioBitrate})).supported)
      throw Error('Opus audio encoding is unavailable');
    ready=true;await status();
    (async()=>{while(!failed){
      const c=await settings();
      if(video.encodeQueueSize>2){await sleep(5);continue;}
      const response=await fetch('video');
      if(response.status===204){await sleep(5);continue;}if(!response.ok)throw Error('Video source disconnected');
      const w=Number(response.headers.get('X-Width')),h=Number(response.headers.get('X-Height')),timestamp=Number(response.headers.get('X-Time'));
      const data=await response.arrayBuffer();
      if(w<2||h<2||w>1920||h>1080||data.byteLength!==w*h*4)throw Error('Invalid raw frame');
      const signature=[w,h,c.fps,c.bitrate].join(':');
      if(signature!==videoSignature){
        if(video.state==='configured')await video.flush();
        width=w;height=h;video.configure({codec:'vp09.00.10.08',width:w,height:h,bitrate:c.bitrate,framerate:c.fps,latencyMode:'realtime'});
        videoSignature=signature;keyNeeded=true;
      }
      if(c.forceKey!==keyGeneration){keyGeneration=c.forceKey;keyNeeded=true;}
      const key=keyNeeded||timestamp-keyAt>=1000000;
      const frame=new VideoFrame(data,{format:'RGBA',codedWidth:w,codedHeight:h,timestamp});
      try{video.encode(frame,{keyFrame:key});}finally{frame.close();}
      if(key){keyAt=timestamp;keyNeeded=false;}
    }})().catch(fail);
    (async()=>{while(!failed){
      const c=await settings(),response=await fetch('audio');
      if(response.status===204){await sleep(5);continue;}if(!response.ok)throw Error('Audio source disconnected');
      const frames=Number(response.headers.get('X-Frames')),timestamp=Number(response.headers.get('X-Time')),data=await response.arrayBuffer();
      if(!c.audio||audio.encodeQueueSize>8)continue;
      if(frames<1||frames>49152||data.byteLength!==frames*8)throw Error('Invalid PCM frame');
      if(audioBitrate!==c.audioBitrate){if(audio.state==='configured')await audio.flush();audio.configure({codec:'opus',sampleRate:48000,numberOfChannels:2,bitrate:c.audioBitrate});audioBitrate=c.audioBitrate;}
      const samples=new AudioData({format:'f32-planar',sampleRate:48000,numberOfFrames:frames,numberOfChannels:2,timestamp,data});
      try{audio.encode(samples);}finally{samples.close();}
    }})().catch(fail);
  }
  async function decoder() {
    if(!self.VideoDecoder||!self.AudioDecoder)throw Error('This Chromium build does not expose WebCodecs decoders');
    const sound=new AudioContext({sampleRate:48000,latencyHint:'interactive'});
    document.addEventListener('pointerdown',()=>sound.resume().catch(fail));
    document.addEventListener('mousedown',()=>sound.resume().catch(fail));
    let video, audio, needKey=true, shape='',baseTime=null,baseClock=0,clockAudio=0,lastVideo=0;
    const frames=[],playing=new Set();
    function resetClock(timestamp){
      baseTime=timestamp;baseClock=performance.now()+150;clockAudio=sound.currentTime+.15;
      for(const frame of frames)frame.close();frames.length=0;
      for(const node of playing){try{node.stop();}catch(_){}}playing.clear();
    }
    video=new VideoDecoder({output:frame=>{
      if(baseTime===null)resetClock(frame.timestamp);
      if(frames.length>6){frames.shift().close();}frames.push(frame);
    },error:()=>{needKey=true;}});
    function makeAudio(){return new AudioDecoder({output:samples=>{
      try {
        if(baseTime===null||sound.state!=='running')return;
        const when=clockAudio+(samples.timestamp-baseTime)/1000000;
        if(when<sound.currentTime-.1||when>sound.currentTime+1)return;
        const buffer=sound.createBuffer(samples.numberOfChannels,samples.numberOfFrames,samples.sampleRate);
        for(let c=0;c<samples.numberOfChannels;c++)samples.copyTo(buffer.getChannelData(c),{planeIndex:c,format:'f32-planar'});
        const source=sound.createBufferSource();source.buffer=buffer;source.connect(sound.destination);playing.add(source);
        source.onended=()=>{playing.delete(source);source.disconnect();};source.start(Math.max(sound.currentTime,when));
      }finally{samples.close();}
    },error:()=>{needKey=true;}});}
    audio=makeAudio();
    audio.configure({codec:'opus',sampleRate:48000,numberOfChannels:2});
    const pump=setInterval(()=>{
      if(failed){clearInterval(pump);return;}
      while(frames.length&&performance.now()>=baseClock+(frames[0].timestamp-baseTime)/1000){
        const frame=frames.shift();
        if(canvas.width!==frame.displayWidth||canvas.height!==frame.displayHeight){canvas.width=frame.displayWidth;canvas.height=frame.displayHeight;}
        context.drawImage(frame,0,0,canvas.width,canvas.height);frame.close();
      }
    },5);
    sound.addEventListener('statechange',()=>{if(sound.state==='running'&&baseTime!==null){clockAudio=sound.currentTime+(baseClock-performance.now())/1000;}});
    ready=true;await status();
    while(!failed){
      const response=await fetch('next');if(response.status===204){await sleep(5);continue;}if(!response.ok)throw Error('Stream disconnected');
      const packet=unpack(new Uint8Array(await response.arrayBuffer()));
      if(packet.kind===0){
        if(packet.w<2||packet.h<2||packet.w>1920||packet.h>1080)throw Error('Invalid video dimensions');
        if(lastVideo&&packet.timestamp-lastVideo>2000000)needKey=true;
        if(video.decodeQueueSize>8)needKey=true;
        if(needKey&&packet.type!=='key')continue;
        const nextShape=packet.w+':'+packet.h;
        if(needKey||nextShape!==shape||video.state!=='configured'){
          if(packet.type!=='key'){needKey=true;continue;}
          if(video.state==='closed')video=new VideoDecoder({output:frame=>{if(frames.length>6)frames.shift().close();frames.push(frame);},error:()=>{needKey=true;}});
          else video.reset();
          video.configure({codec:'vp09.00.10.08',codedWidth:packet.w,codedHeight:packet.h,optimizeForLatency:true});
          if(audio.state==='closed'){audio=makeAudio();audio.configure({codec:'opus',sampleRate:48000,numberOfChannels:2});}
          shape=nextShape;needKey=false;resetClock(packet.timestamp);
        }
        if(baseTime!==null&&Math.abs((performance.now()-baseClock)-(packet.timestamp-baseTime)/1000)>1500)resetClock(packet.timestamp);
        lastVideo=packet.timestamp;video.decode(new EncodedVideoChunk(packet));
      }else if(packet.kind===1&&!needKey&&baseTime!==null&&audio.state==='configured'&&audio.decodeQueueSize<16){audio.decode(new EncodedAudioChunk({...packet,type:'key'}));}
    }
  }
  setInterval(()=>{if(!failed)status();},1000);
  (async()=>{const c=await settings();if(c.encode)await encoder();else await decoder();})().catch(fail);
})();
