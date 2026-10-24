import {randomBytes} from 'node:crypto';
import {GATEWAY,preferences} from './core/protocol.mjs';

/** The private claim stays in this main-process operation; only public IDs reach UI. */
export class PairingFlow {
  constructor({fetch=globalThis.fetch,install,onState,now=()=>performance.now(),delay=ms=>new Promise(r=>setTimeout(r,ms))}) {
    this.fetch=fetch;this.install=install;this.onState=onState;this.now=now;this.delay=delay;this.epoch=0;this.controller=null;
  }
  cancel() { this.epoch++;this.controller?.abort();this.controller=null;this.onState({phase:'idle'}); }
  async start(serverUrl=GATEWAY) {
    this.cancel(); const epoch=this.epoch; const controller=new AbortController(); this.controller=controller;
    const current=()=>this.epoch===epoch && !controller.signal.aborted;
    const claim=randomBytes(32).toString('base64url');
    const base=new URL(serverUrl);base.protocol='https:';base.pathname='/';base.search='';base.hash='';
    const post=async(path,body)=>{
      const response=await this.fetch(new URL(path,base).href,{method:'POST',headers:{'Content-Type':'application/json'},
        body:JSON.stringify(body),redirect:'error',signal:AbortSignal.any([controller.signal,AbortSignal.timeout(10000)])});
      if(!response.ok||!response.body)throw Error();
      const reader=response.body.getReader();let bytes=0;const chunks=[];
      try{while(true){const item=await reader.read();if(item.done)break;bytes+=item.value.length;if(bytes>8192)throw Error();chunks.push(item.value);}}
      finally{await reader.cancel().catch(()=>{});}
      return JSON.parse(Buffer.concat(chunks).toString('utf8'));
    };
    this.onState({phase:'connecting'});
    try {
      const request=await post('/pairing/request',{claim,device:'win'});
      if(!current())return;
      if(!/^[a-f0-9]{8}$/.test(request.id)||!Number.isInteger(request.expires_in)||request.expires_in<1||request.expires_in>180)throw Error();
      const deadline=this.now()+request.expires_in*1000;
      this.onState({phase:'waiting',id:request.id,expiresAt:Date.now()+request.expires_in*1000});
      while(current()&&this.now()<deadline) {
        const result=await post('/pairing/claim',{id:request.id,claim});
        if(!current())return;
        if(result.status==='expired')break;
        if(result.status==='paired') {
          if(result.device!=='win')throw Error();
          result.settings=preferences(result.settings);
          await this.install(result,current);
          if(current()){this.onState({phase:'paired'});this.controller=null;}
          return;
        }
        if(result.status!=='pending')throw Error();
        await this.delay(2000);
      }
      if(current())this.onState({phase:'expired'});
    } catch {
      if(current())this.onState({phase:'error',message:'Pairing unavailable. Keep Hermes open and try again.'});
    } finally {if(this.epoch===epoch)this.controller=null;}
  }
}
