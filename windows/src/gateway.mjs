import WebSocket from 'ws';
import {hello,parseServer} from './core/protocol.mjs';

export class Gateway {
  constructor({url,token,createSocket=url=>new WebSocket(url,{handshakeTimeout:10000,maxPayload:2*1024*1024}),
    onConnected=()=>{},onDisconnected=()=>{},onRejected=()=>{},onMessage=()=>{},onAudio=()=>{}}) {
    Object.assign(this,{url,token,createSocket,onConnected,onDisconnected,onRejected,onMessage,onAudio});
    this.socket=null;this.epoch=0;this.closed=false;this.terminal=0;this.attempt=0;this.timer=null;
  }
  connect() {
    if(this.closed||this.terminal===4401)return;
    clearTimeout(this.timer);this.timer=null;const epoch=++this.epoch;
    this.socket?.terminate();this.socket=null;
    let socket;
    try{socket=this.createSocket(this.url);}catch{this.onDisconnected();this.retry();return;}
    this.socket=socket;let finished=false;
    const current=()=>!this.closed&&this.epoch===epoch&&this.socket===socket;
    const end=code=>{
      if(!current()||finished)return;finished=true;clearTimeout(timeout);
      this.socket=null;
      if(code===4401||code===4409){this.terminal=code;this.onRejected(code);}
      else {this.onDisconnected();this.retry();}
    };
    const timeout=setTimeout(()=>{if(current()){end(1006);socket.terminate();}},15000);timeout.unref?.();
    socket.on('open',()=>{
      if(!current())return;clearTimeout(timeout);
      try{socket.send(JSON.stringify(hello(this.token)));this.attempt=0;this.onConnected();}
      catch{end(1006);socket.terminate();}
    });
    socket.on('message',(data,binary)=>{
      if(!current()||finished)return;
      if(binary){if(data.length%2===0&&data.length<=1024*1024)this.onAudio(new Uint8Array(data));}
      else {const message=parseServer(data.toString('utf8'));if(message)this.onMessage(message);}
    });
    socket.on('close',code=>end(code));
    socket.on('error',()=>{end(1006);socket.terminate();});
    this.fail=()=>{end(1006);socket.terminate();};
  }
  retry() {
    if(this.closed||this.terminal)return;
    clearTimeout(this.timer);
    const ms=Math.min(30000,500*2**Math.min(this.attempt++,6));
    this.timer=setTimeout(()=>this.connect(),ms);this.timer.unref?.();
  }
  send(value) {return this.write(JSON.stringify(value));}
  sendAudio(bytes) {return bytes.byteLength===1024&&this.write(bytes);}
  write(value) {
    if(this.closed||this.socket?.readyState!==1)return false;
    if(this.socket.bufferedAmount>32768){this.fail?.();return false;}
    try{this.socket.send(value);return true;}catch{this.fail?.();return false;}
  }
  reconnectNow() {if(this.terminal===4401||this.closed)return;this.terminal=0;this.connect();}
  close() {this.closed=true;this.epoch++;clearTimeout(this.timer);this.socket?.terminate();this.socket=null;this.token=null;}
}
