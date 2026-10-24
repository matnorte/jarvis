import test from 'node:test';
import assert from 'node:assert/strict';
import {AudioClient} from '../src/renderer/audio-client.mjs';

test('playback overflow and output initialization errors use the playback channel, never microphone permission failure',async()=>{
  const failures=[],mic=[];
  class Context {
    sampleRate=24000;state='running';audioWorklet={addModule:async()=>{}};destination={};
    async resume(){} async close(){this.state='closed';}
  }
  class Node {port={postMessage(){}};connect(){}}
  globalThis.AudioContext=Context;globalThis.AudioWorkletNode=Node;
  const client=new AudioClient({playbackFailed:(...args)=>failures.push(args),microphoneFailed:epoch=>mic.push(epoch)});
  try{
    await client.ensureOutput();client.outputNode.port.onmessage({data:{type:'playback-error',generation:0,reason:'capacity'}});
    assert.deepEqual(failures,[[0,'capacity']]);assert.equal(mic.length,0);
    client.flush(1);client.outputNode.port.onmessage({data:{type:'playback-error',generation:0,reason:'capacity'}});
    assert.equal(failures.length,1);
    client.ensureOutput=async()=>{throw Error('synthetic speaker error');};
    await client.play({type:'play',generation:1,sequence:1,bytes:new Uint8Array(48)});
    assert.deepEqual(failures.at(-1),[1,'device']);assert.equal(mic.length,0);
  }finally{delete globalThis.AudioContext;delete globalThis.AudioWorkletNode;}
});

test('failed speaker initialization releases only its own context and permits a fresh retry',async()=>{
  const contexts=[];let rejectOld;
  class Context {
    sampleRate=24000;state='running';destination={};
    constructor(){contexts.push(this);this.audioWorklet={addModule:()=>contexts.length===1?new Promise((_r,reject)=>rejectOld=reject):Promise.resolve()};}
    async resume(){} async close(){this.state='closed';}
  }
  class Node {port={postMessage(){}};connect(){}disconnect(){}}
  globalThis.AudioContext=Context;globalThis.AudioWorkletNode=Node;
  const client=new AudioClient({playbackFailed(){},microphoneFailed(){}});
  try{
    const old=client.ensureOutput();client.resetOutput(contexts[0]);
    const next=client.ensureOutput();rejectOld(Error('old synthetic output failed'));
    await assert.rejects(old);await next;
    assert.equal(contexts[0].state,'closed');assert.equal(contexts[1].state,'running');assert.equal(client.output,contexts[1]);
  }finally{delete globalThis.AudioContext;delete globalThis.AudioWorkletNode;}
});
