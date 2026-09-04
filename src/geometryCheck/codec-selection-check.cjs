// Run with Node.js from the repository root. Probes the production worker with mocked capabilities.
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const worker=fs.readFileSync('src/main/resources/assets/winlandcraft/stream-worker.js','utf8');
new vm.Script(worker);
const normalizeSection=worker.slice(worker.indexOf('  function normalizeH264Keyframe'),worker.indexOf('  function pack('));
const normalizeContext=vm.createContext({Uint8Array,Set});
vm.runInContext(normalizeSection+';globalThis.normalize=normalizeH264Keyframe;',normalizeContext);
const section=worker.slice(worker.indexOf('  const VP9='),worker.indexOf('  async function encoder()'));
let supported=true,probes=[];
const context=vm.createContext({message:String,VideoEncoder:{isConfigSupported:async config=>{probes.push(config);return {supported};}},VideoDecoder:{isConfigSupported:async()=>({supported:true})}});
vm.runInContext(section+';globalThis.select=selectEncoderConfig;globalThis.AudioTimelineForTest=AudioTimeline;',context);

async function exerciseAsyncEncoderFailure(codecMode){
  const configured=[],statuses=[],posted=[];let videoRequests=0,completed=false;
  class VideoEncoder {
    static async isConfigSupported(config){return {supported:true,config};}
    constructor(callbacks){this.callbacks=callbacks;this.state='unconfigured';this.encodeQueueSize=0;}
    configure(config){this.config=config;this.state='configured';configured.push(config.codec);}
    encode(frame){
      if(this.config.codec.startsWith('avc1')){
        this.state='closed';
        queueMicrotask(()=>this.callbacks.error(Error('Unable to create a mappable shared image')));
      }else{
        completed=true;
        this.callbacks.output({byteLength:1,type:'key',timestamp:frame.timestamp,copyTo:bytes=>{bytes[0]=1;}});
      }
    }
    async flush(){}
    close(){this.state='closed';}
  }
  class AudioEncoder {
    static async isConfigSupported(config){return {supported:true,config};}
    constructor(){this.state='unconfigured';this.encodeQueueSize=0;}
    configure(){this.state='configured';}
    async flush(){}
    close(){this.state='closed';}
  }
  class VideoFrame {
    constructor(_data,config){this.timestamp=config.timestamp;}
    close(){}
  }
  const response=(status,extra={})=>({status,ok:status>=200&&status<300,...extra});
  const sandbox={message:String,VideoEncoder,AudioEncoder,VideoFrame,AudioData:class{},queueMicrotask,
    performance:require('node:perf_hooks').performance,setInterval:()=>0,addEventListener:()=>{},
    document:{getElementById:()=>({getContext:()=>({})}),addEventListener:()=>{}},
    fetch:async(route,options={})=>{
      if(route==='status'){
        statuses.push(JSON.parse(options.body));return response(200);
      }
      if(route==='config')return response(200,{json:async()=>({encode:true,audio:false,audioBitrate:96000,
        bitrate:2000000,fps:30,codecMode,forceKey:0})});
      if(route==='packets'){posted.push({sequence:options.headers['X-WinLandCraft-Sequence'],body:new Uint8Array(options.body)});return response(200);}
      if(route==='audio')return response(204);
      if(route==='video'){
        videoRequests++;
        if(videoRequests<=2&&!completed)return response(200,{headers:{get:name=>name==='X-Width'||name==='X-Height'?'2':'1000'},
          arrayBuffer:async()=>new Uint8Array(16).buffer});
        throw Error('mock stream complete');
      }
      throw Error(`Unexpected route ${route}`);
    }};
  const workerContext=vm.createContext(sandbox);workerContext.self=workerContext;
  vm.runInContext(worker,workerContext);
  for(let i=0;i<100&&!statuses.some(status=>status.phase==='failed');i++)await new Promise(resolve=>setTimeout(resolve,1));
  return {configured,statuses,posted};
}

(async()=>{
  const near=(actual,expected)=>assert.ok(Math.abs(actual-expected)<1e-9,`${actual} != ${expected}`);
  const nal=(type,value)=>Uint8Array.of(0,0,0,1,type,value),join=(...parts)=>{
    const result=new Uint8Array(parts.reduce((size,part)=>size+part.length,0));let offset=0;
    for(const part of parts){result.set(part,offset);offset+=part.length;}return result;
  },types=bytes=>{
    const result=[];for(let index=0;index+4<bytes.length;index++)if(bytes[index]===0&&bytes[index+1]===0&&bytes[index+2]===0&&bytes[index+3]===1)result.push(bytes[index+4]&31);
    return result;
  };
  const broken=join(nal(5,1),nal(7,2),nal(8,3));
  assert.equal(normalizeContext.normalize(broken),true);assert.deepEqual(types(broken),[7,8,5]);
  const valid=join(nal(9,1),nal(7,2),nal(8,3),nal(5,4));
  assert.equal(normalizeContext.normalize(valid),false);assert.deepEqual(types(valid),[9,7,8,5]);
  const delta=join(nal(1,1));assert.equal(normalizeContext.normalize(delta),false);assert.deepEqual(types(delta),[1]);
  const expected=['h264-hardware','h264-hardware','vp9-hardware','vp9-software'];
  for(let mode=0;mode<4;mode++){probes=[];const result=await context.select(1280,720,2000000,new Set(),mode);assert.equal(result.id,expected[mode]);assert.equal(probes.length,1);}
  assert.equal((await context.select(1280,720,2000000,new Set(['h264-hardware']),0)).id,'vp9-hardware');
  supported=false;probes=[];await assert.rejects(context.select(1280,720,2000000,new Set(),3));assert.equal(probes.length,1);assert.equal(probes[0].codec,'vp09.00.10.08');assert.equal(probes[0].hardwareAcceleration,'prefer-software');
  supported=true;probes=[];await assert.rejects(context.select(1280,720,2000000,new Set(['vp9-software']),3));assert.equal(probes.length,0);
  const timeline=new context.AudioTimelineForTest();
  const first=timeline.plan(1_000_000,.02,10);near(first.when,10.15);assert.equal(first.underrun,false);
  const second=timeline.plan(1_020_000,.02,10.01);near(second.when,10.17);assert.equal(second.underrun,false);
  assert.equal(timeline.plan(1_020_000,.02,10.02).drop,true);
  const recovered=timeline.plan(1_040_000,.02,10.3);assert.equal(recovered.underrun,true);near(recovered.when,10.45);
  const explicit=await exerciseAsyncEncoderFailure(1),failure=explicit.statuses.find(status=>status.phase==='failed');
  assert.ok(failure.error.includes('Unable to create a mappable shared image'));assert.ok(!failure.error.includes('closed codec'));
  const automatic=await exerciseAsyncEncoderFailure(0);
  assert.deepEqual(automatic.configured.slice(0,2),['avc1.42E02A','vp09.00.10.08']);
  assert.equal(automatic.posted[0].sequence,'0');
  assert.equal(new DataView(automatic.posted[0].body.buffer).getUint32(0),25);
  console.log('Codec selection: Annex B keyframes, explicit modes, Auto fallback, ordered batched transport, stable audio scheduling, and asynchronous native error preservation passed.');
})().catch(error=>{console.error(error);process.exitCode=1;});
