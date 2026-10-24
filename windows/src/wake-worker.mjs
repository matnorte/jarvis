import {fileURLToPath} from 'node:url';
import {FrameInbox} from './core/audio.mjs';

export class WakeWorker {
  constructor(models,{fork}) {this.models=models;this.fork=fork;this.child=null;this.candidates=new Set();this.version=0;this.counter=0;this.inflight=null;this.inbox=new FrameInbox(8);}
  async start(sensitivity) {
    const version=++this.version;
    const child=this.fork(fileURLToPath(new URL('wake-utility.mjs',import.meta.url)),[this.models,String(sensitivity)],{stdio:'pipe',serviceName:'Jarvis offline voice'});
    this.candidates.add(child);child.stdout?.resume();child.stderr?.resume();
    await new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>{child.kill();reject(Error('Voice engine startup timed out'));},20000);
      child.once('exit',()=>{clearTimeout(timer);this.candidates.delete(child);reject(Error('Voice engine unavailable'));});
      child.once('message',message=>{
        clearTimeout(timer);
        if(message?.type!=='ready'||version!==this.version){child.kill();reject(Error('Voice engine unavailable'));return;}
        const old=this.child;this.child=child;this.candidates.delete(child);this.inflight=null;this.inbox.reset(this.inbox.epoch);old?.kill();
        child.on('message',async result=>{
          if(this.child!==child||result?.type!=='frame'||result.id!==this.inflight?.id||this.inflight.delivering)return;
          const packet=this.inflight;packet.delivering=true;
          try{await this.onFrame?.(packet.frame,{wake:result.wake===true,speech:result.speech===true},packet.epoch);}
          finally{if(this.child===child&&this.inflight===packet){this.inflight=null;this.pump();}}
        });
        child.once('exit',()=>{if(this.child===child){this.child=null;this.inflight=null;this.onError?.();}});
        this.pump();resolve();
      });
    });
  }
  reset(epoch) {this.inbox.reset(epoch);this.inflight=null;}
  offer(frame,epoch) {this.inbox.offer(frame,epoch);this.pump();}
  pump() {
    if(!this.child||this.inflight)return;
    const item=this.inbox.take();if(!item)return;
    this.inflight={...item,id:++this.counter};
    this.child.postMessage({type:'frame',id:this.inflight.id,frame:item.frame});
  }
  stop() {this.version++;const old=this.child;this.child=null;old?.kill();for(const child of this.candidates)child.kill();this.candidates.clear();this.inflight=null;this.inbox.reset(this.inbox.epoch);}
}
