import test from 'node:test';
import assert from 'node:assert/strict';
import {ManagedSync} from '../src/core/managed.mjs';
import {DEFAULTS} from '../src/core/protocol.mjs';

test('managed changes wait through playback/follow-up, acknowledge safe idle and retain latest revision', async () => {
  const applied=[],acks=[]; const sync=new ManagedSync(1, async (r,p)=>{applied.push([r,p]);return true;},v=>{acks.push(v);return true;});
  sync.offer({revision:2,settings:DEFAULTS}); await sync.tick('SPEAKING');
  assert.equal(applied.length,0); assert.deepEqual(acks.at(-1),{type:'config_ack',revision:1,idle:false});
  sync.offer({revision:3,settings:{...DEFAULTS,end_silence_ms:900}}); await sync.tick('FOLLOW_UP'); assert.equal(applied.length,0);
  await sync.tick('WAITING'); assert.equal(applied[0][0],3); assert.equal(acks.at(-1).idle,true);
  const n=acks.length; await sync.tick('WAITING'); assert.equal(acks.length,n);
  await sync.tick('LISTENING'); assert.equal(acks.at(-1).idle,false);
});
test('failed apply does not spin writes; disconnected drops old pending offers; dropped ack retries', async () => {
  let writes=0, sends=0; const sync=new ManagedSync(0,async()=>{writes++;return false;},()=>++sends>1);
  sync.offer({revision:1,settings:DEFAULTS}); await sync.tick('PAUSED'); await sync.tick('PAUSED');
  assert.equal(writes,1); assert.equal(sends,2); sync.disconnected(); await sync.tick('WAITING'); assert.equal(writes,1);
  sync.offer({revision:2,settings:DEFAULTS}); await sync.tick('WAITING'); assert.equal(writes,2);
});
