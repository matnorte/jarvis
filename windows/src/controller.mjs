import {Conversation} from './core/conversation.mjs';
import {ManagedSync} from './core/managed.mjs';
import {Gateway} from './gateway.mjs';

/** One serialized owner for control, socket callbacks and native inference results. */
export class Controller {
  constructor({store,worker,send,emit,createGateway=options=>new Gateway(options),onPreferences=()=>{}}) {
    Object.assign(this,{store,worker,send,emit,createGateway,onPreferences});
    this.serial=Promise.resolve();this.closed=false;this.active=false;this.micReady=false;this.captureEpoch=0;
    this.playbackGeneration=0;this.playbackSequence=0;this.gateway=null;this.notice=null;this.talkPending=false;
    this.conversation=new Conversation({send:value=>this.gateway?.send(value),sendAudio:bytes=>this.gateway?.sendAudio(bytes),
      play:bytes=>this.send({type:'play',bytes,generation:this.playbackGeneration,sequence:++this.playbackSequence}),
      flush:()=>this.flush(),chime:()=>this.send({type:'chime'}),errorTone:()=>this.send({type:'error-tone'}),reconnect:()=>this.gateway?.reconnectNow()},
      {endSilenceMs:store.settings.end_silence_ms,interruptBySpeech:store.settings.interrupt_by_speech});
    this.conversation.pause();
    this.managed=new ManagedSync(store.revision,(revision,value)=>this.applyPreferences(revision,value),value=>this.gateway?.send(value)===true);
    worker.onFrame=(frame,result,epoch)=>this.dispatch(()=>{
      if(this.active&&this.micReady&&epoch===this.captureEpoch)this.conversation.frame(frame,result.wake,result.speech);
    });
    worker.onError=()=>this.microphoneFailed(this.captureEpoch,'Offline voice engine stopped. Restart listening to retry.');
  }
  get state() {return {...this.conversation.state,...this.store.publicState,active:this.active,micReady:this.micReady,
    notice:this.conversation.state.notice||this.notice};}
  dispatch(fn) {
    this.serial=this.serial.then(async()=>{
      if(this.closed)return;
      await fn();await this.managed.tick(this.conversation.phase);this.emit(this.state);
    }).catch(()=>{if(!this.closed){this.pauseImmediate();this.notice='Voice stopped. Start listening to retry.';this.emit(this.state);}});
    return this.serial;
  }
  connect() {
    this.gateway?.close();this.gateway=null;this.managed.disconnected();this.conversation.disconnected();
    if(!this.store.token)return;
    let gateway;
    const event=fn=>this.dispatch(()=>{if(this.gateway===gateway)fn();});
    gateway=this.createGateway({url:this.store.settings.server_url,token:this.store.token,
      onConnected:()=>event(()=>{this.managed.disconnected();this.conversation.connected();}),
      onDisconnected:()=>event(()=>{this.managed.disconnected();this.conversation.disconnected();}),
      onRejected:code=>event(()=>{this.managed.disconnected();this.conversation.rejected(code);}),
      onMessage:message=>event(()=>{if(message.type==='config')this.managed.offer(message);else this.conversation.server(message);}),
      onAudio:bytes=>event(()=>this.conversation.audio(bytes))});
    this.gateway=gateway;gateway.connect();
  }
  command(action) {return this.dispatch(async()=>{
    if(action==='pause'){this.pauseImmediate();return;}
    if(action!=='start'&&action!=='talk')return;
    if(!this.store.token){this.notice='Pair with Hermes to connect this PC.';return;}
    if(!this.active) {
      this.notice='Preparing offline voice…';this.emit(this.state);
      await this.worker.start(this.store.settings.wake_sensitivity);
      this.captureEpoch++;this.worker.reset(this.captureEpoch);this.active=true;this.micReady=false;
      this.talkPending=action==='talk';this.conversation.resume();this.notice=null;
      this.send({type:'capture-start',epoch:this.captureEpoch});
    } else if(action==='talk') {
      if(this.micReady)this.conversation.tap();else this.talkPending=true;
    }
  });}
  microphoneReady(epoch) {return this.dispatch(()=>{
    if(!this.active||epoch!==this.captureEpoch)return;
    this.micReady=true;this.lastFrame=performance.now();if(this.talkPending){this.talkPending=false;this.conversation.tap();}
  });}
  microphoneFailed(epoch,message='Microphone unavailable. Check Windows microphone permissions, then start listening.') {
    return this.dispatch(()=>{if(epoch===this.captureEpoch){this.pauseImmediate();this.notice=message;}});
  }
  checkMicrophone() {if(this.active&&this.micReady&&performance.now()-this.lastFrame>3000)this.microphoneFailed(this.captureEpoch,'Microphone stopped responding. Start listening to retry.');}
  frame(frame,epoch) {
    if(this.closed||!this.active||!this.micReady||epoch!==this.captureEpoch||!(frame instanceof Int16Array)||frame.length!==512)return;
    this.lastFrame=performance.now();this.worker.offer(frame,epoch);
  }
  drained(generation,sequence) {return this.dispatch(()=>{
    if(generation===this.playbackGeneration&&sequence===this.playbackSequence)this.conversation.playbackDone();
  });}
  playbackFailed(generation,reason) {return this.dispatch(()=>{
    if(generation!==this.playbackGeneration||!['capacity','device'].includes(reason))return;
    const message=reason==='capacity'?'Reply exceeded the audio buffer. Ask for a shorter answer.':'Audio playback unavailable. Check your speakers and try again.';
    if(this.conversation.inTurn())this.conversation.playbackFailed(message);
    else if(this.active&&!this.micReady){this.pauseImmediate();this.notice=message;}
  });}
  flush() {this.playbackGeneration++;this.send({type:'flush',generation:this.playbackGeneration});}
  pauseImmediate() {
    this.conversation.pause();this.active=false;this.micReady=false;this.talkPending=false;this.captureEpoch++;
    this.worker.stop();this.flush();this.send({type:'capture-stop',epoch:this.captureEpoch});
  }
  async applyPreferences(revision,value) {
    const previous=this.store.settings;
    try {
      if(this.active&&previous.wake_sensitivity!==value.wake_sensitivity)await this.worker.start(value.wake_sensitivity);
      await this.store.cache(revision,value);
      this.conversation.configure({endSilenceMs:value.end_silence_ms,interruptBySpeech:value.interrupt_by_speech});
      this.onPreferences(value);this.notice=null;
      if(previous.server_url!==value.server_url)setImmediate(()=>this.dispatch(()=>this.connect()));
      return true;
    } catch {
      if(this.active&&previous.wake_sensitivity!==value.wake_sensitivity) {
        try{await this.worker.start(previous.wake_sensitivity);}catch{this.pauseImmediate();}
      }
      this.notice='Settings could not be applied. Save again in Hermes to retry.';return false;
    }
  }
  close() {if(this.closed)return;this.closed=true;this.pauseImmediate();this.gateway?.close();this.gateway=null;}
}
