// Run with Node.js from the repository root. Probes the production worker with mocked capabilities.
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const worker=fs.readFileSync('src/main/resources/assets/winlandcraft/stream-worker.js','utf8');
new vm.Script(worker);
const section=worker.slice(worker.indexOf('  const VP9='),worker.indexOf('  async function encoder()'));
let supported=true,probes=[];
const context=vm.createContext({message:String,VideoEncoder:{isConfigSupported:async config=>{probes.push(config);return {supported};}},VideoDecoder:{isConfigSupported:async()=>({supported:true})}});
vm.runInContext(section+';globalThis.select=selectEncoderConfig;',context);
(async()=>{
  const expected=['h264-hardware','h264-hardware','vp9-hardware','vp9-software'];
  for(let mode=0;mode<4;mode++){probes=[];const result=await context.select(1280,720,2000000,new Set(),mode);assert.equal(result.id,expected[mode]);assert.equal(probes.length,1);}
  assert.equal((await context.select(1280,720,2000000,new Set(['h264-hardware']),0)).id,'vp9-hardware');
  supported=false;probes=[];await assert.rejects(context.select(1280,720,2000000,new Set(),3));assert.equal(probes.length,1);assert.equal(probes[0].codec,'vp09.00.10.08');assert.equal(probes[0].hardwareAcceleration,'prefer-software');
  supported=true;probes=[];await assert.rejects(context.select(1280,720,2000000,new Set(['vp9-software']),3));assert.equal(probes.length,0);
  console.log('Codec selection: explicit modes, unchanged Auto order, unsupported/blocked modes and no silent fallback passed.');
})().catch(error=>{console.error(error);process.exitCode=1;});
