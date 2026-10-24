import test from 'node:test';
import assert from 'node:assert/strict';
import {Controller} from '../src/controller.mjs';
import {DEFAULTS} from '../src/core/protocol.mjs';

function setup() {
  const messages=[],gateways=[];
  const store={token:'fake-win-token',settings:{...DEFAULTS},revision:0,get publicState(){return {paired:!!this.token,revision:this.revision,settings:this.settings};},async cache(r,p){this.revision=r;this.settings=p;}};
  const worker={running:false,async start(){this.running=true;},stop(){this.running=false;},reset(){},offer(frame,epoch){return this.onFrame(frame,{wake:false,speech:false},epoch);}};
  const createGateway=options=>{const g={options,sent:[],closed:false,connect(){},send(v){this.sent.push(v);return true;},sendAudio(){return true;},close(){this.closed=true;},reconnectNow(){}};gateways.push(g);return g;};
  const c=new Controller({store,worker,createGateway,send:m=>messages.push(m),emit:()=>{}});
  c.connect();gateways[0].options.onConnected();
  return {c,store,worker,messages,gateways};
}
test('paired startup stays stopped; explicit start owns microphone epoch, pause releases worker/capture',async()=>{
  const {c,worker,messages}=setup();await c.serial;
  assert.equal(c.state.phase,'PAUSED');assert.equal(worker.running,false);
  await c.command('start');const epoch=c.captureEpoch;
  assert.equal(worker.running,true);assert.ok(messages.some(m=>m.type==='capture-start'&&m.epoch===epoch));
  await c.command('pause');assert.equal(c.state.phase,'PAUSED');assert.equal(worker.running,false);
  c.frame(new Int16Array(512).fill(1000),epoch);await c.serial;
  assert.equal(c.state.micLevel,0);c.close();
});
test('stale playback drain cannot start follow-up after a newer chunk/flush',async()=>{
  const {c,gateways}=setup();await c.serial;await c.command('start');await c.microphoneReady(c.captureEpoch);
  await c.command('talk');c.conversation.phase='THINKING';
  gateways[0].options.onAudio(new Uint8Array(4800));await c.serial;
  const generation=c.playbackGeneration,sequence=c.playbackSequence;
  gateways[0].options.onAudio(new Uint8Array(4800));gateways[0].options.onMessage({type:'speaking_done'});await c.serial;
  await c.drained(generation,sequence);assert.equal(c.state.phase,'SPEAKING');
  await c.drained(generation,c.playbackSequence);assert.equal(c.state.phase,'FOLLOW_UP');
  await c.command('pause');await c.drained(generation,c.playbackSequence);assert.equal(c.state.phase,'PAUSED');c.close();
});
test('replaced socket callbacks cannot change the new conversation; managed settings apply while stopped',async()=>{
  const {c,gateways,store}=setup();await c.serial;c.connect();
  gateways[0].options.onRejected(4401);await c.serial;assert.notEqual(c.state.notice,'Pair with Hermes again to connect.');
  gateways[1].options.onConnected();gateways[1].options.onMessage({type:'config',revision:3,settings:{...DEFAULTS,end_silence_ms:900}});await c.serial;
  assert.equal(store.revision,3);assert.equal(c.state.phase,'PAUSED');assert.ok(gateways[1].sent.some(v=>v.type==='config_ack'&&v.idle));c.close();
});

test('reply playback failure cancels that reply and keeps the healthy microphone and wake engine running',async()=>{
  const {c,gateways,worker}=setup();await c.serial;await c.command('start');await c.microphoneReady(c.captureEpoch);
  await c.command('talk');c.conversation.phase='THINKING';
  gateways[0].options.onAudio(new Uint8Array(4800));await c.serial;
  const oldGeneration=c.playbackGeneration;
  await c.playbackFailed(oldGeneration,'capacity');
  assert.equal(c.state.phase,'WAITING');assert.equal(c.state.active,true);assert.equal(c.state.micReady,true);assert.equal(worker.running,true);
  assert.match(c.state.notice,/reply|playback/i);assert.doesNotMatch(c.state.notice,/microphone/i);
  gateways[0].options.onAudio(new Uint8Array(4800));await c.serial;assert.equal(c.state.phase,'WAITING');
  await c.command('talk');assert.equal(c.state.phase,'LISTENING');
  await c.playbackFailed(oldGeneration,'device');assert.equal(c.state.phase,'LISTENING');c.close();
});

test('extended deep thinking keeps capture alive; a late long reply still waits for its final audible drain',async()=>{
  const {c,gateways}=setup();await c.serial;await c.command('start');await c.microphoneReady(c.captureEpoch);
  await c.command('talk');c.conversation.phase='THINKING';
  gateways[0].options.onMessage({type:'state',state:'deep'});await c.serial;
  let time=0;c.conversation.now=()=>time;
  for(let i=0;i<2000;i++){time+=32;c.frame(new Int16Array(512),c.captureEpoch);await c.serial;}
  assert.equal(c.state.phase,'THINKING');assert.equal(c.state.active,true);assert.equal(c.state.notice,null);
  for(let i=0;i<120;i++)gateways[0].options.onAudio(new Uint8Array(48000));
  gateways[0].options.onMessage({type:'speaking_done'});await c.serial;
  assert.equal(c.state.phase,'SPEAKING');assert.equal(c.state.active,true);
  await c.drained(c.playbackGeneration,c.playbackSequence);assert.equal(c.state.phase,'FOLLOW_UP');c.close();
});
