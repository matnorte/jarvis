import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {WakeWorker} from '../src/wake-worker.mjs';

class Child extends EventEmitter {sent=[];killed=false;postMessage(message){this.sent.push(message);}kill(){this.killed=true;this.emit('exit',0);}}
test('native result delivery also stays bounded while the serial conversation owner is busy',async()=>{
  const children=[];const worker=new WakeWorker('models',{fork:()=>{const c=new Child();children.push(c);return c;}});
  const boot=worker.start(.6);children[0].emit('message',{type:'ready'});await boot;worker.reset(1);
  let release;worker.onFrame=()=>new Promise(resolve=>release=resolve);
  worker.offer(new Int16Array(512),1);const child=children[0];child.emit('message',{type:'frame',id:child.sent[0].id});
  for(let i=0;i<30;i++)worker.offer(new Int16Array(512),1);
  assert.equal(child.sent.length,1);assert.equal(worker.inbox.size,8);
  release();await new Promise(resolve=>setImmediate(resolve));assert.equal(child.sent.length,2);worker.stop();
});
test('failed replacement keeps the working engine; stopped worker ignores late native results',async()=>{
  const children=[];const worker=new WakeWorker('models',{fork:()=>{const c=new Child();children.push(c);return c;}});
  let results=0;worker.onFrame=async()=>results++;
  const boot=worker.start(.6);children[0].emit('message',{type:'ready'});await boot;
  const replacement=worker.start(.8);children[1].emit('exit',1);await assert.rejects(replacement);
  assert.equal(children[0].killed,false);assert.equal(worker.child,children[0]);
  worker.reset(2);worker.offer(new Int16Array(512),2);const id=children[0].sent[0].id;
  worker.stop();children[0].emit('message',{type:'frame',id,wake:true,speech:true});assert.equal(results,0);
});
