import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {Gateway} from '../src/gateway.mjs';

class Socket extends EventEmitter {
  readyState=0;bufferedAmount=0;sent=[];
  send(value){this.sent.push(value);}
  terminate(){this.readyState=3;}
  close(){this.readyState=3;}
}
test('hello precedes connected callback; stale client callbacks are ignored after replacement', () => {
  const sockets=[],events=[];const gateway=new Gateway({url:'wss://example.test/ws',token:'fake-test-token',createSocket:()=>{const s=new Socket();sockets.push(s);return s;},onConnected:()=>events.push('connected'),onDisconnected:()=>events.push('offline'),onMessage:m=>events.push(m.type)});
  gateway.connect();const old=sockets[0];old.readyState=1;old.emit('open');
  assert.equal(JSON.parse(old.sent[0]).device,'win');assert.equal(events[0],'connected');
  gateway.connect();old.emit('message',Buffer.from('{"type":"state","state":"thinking"}'),false);old.emit('close',1006);
  assert.equal(events.length,1);gateway.close();
});
test('terminal rejection does not reconnect; bounded backpressure fails cleanly', () => {
  const events=[],sockets=[];const gateway=new Gateway({url:'wss://example.test/ws',token:'fake-test-token',createSocket:()=>{const s=new Socket();sockets.push(s);return s;},onRejected:c=>events.push(c),onDisconnected:()=>events.push('offline')});
  gateway.connect();sockets[0].emit('close',4401);gateway.reconnectNow();assert.equal(sockets.length,1);assert.deepEqual(events,[4401]);gateway.close();
  const socket=new Socket(),other=new Gateway({url:'wss://example.test/ws',token:'fake-test-token',createSocket:()=>socket,onDisconnected:()=>events.push('overflow')});
  other.connect();socket.readyState=1;socket.emit('open');socket.bufferedAmount=100000;
  assert.equal(other.sendAudio(new Uint8Array(1024)),false);assert.ok(events.includes('overflow'));other.close();
});
