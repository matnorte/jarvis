export class AudioClient {
  constructor(api){this.api=api;this.epoch=0;this.generation=0;this.capture=null;this.stream=null;this.output=null;this.outputNode=null;this.outputPromise=null;this.accepting=false;}
  resetOutput(context=this.output) {
    if(context!==this.output)return;
    if(this.outputNode){this.outputNode.port.onmessage=null;this.outputNode.onprocessorerror=null;this.outputNode.disconnect?.();}
    this.outputNode=null;this.output=null;this.outputPromise=null;context?.close().catch(()=>{});
  }
  async ensureOutput() {
    if(!this.outputPromise){
      let context;
      this.outputPromise=(async()=>{
      context=new AudioContext({sampleRate:24000,latencyHint:'interactive'});this.output=context;
      if(context.sampleRate!==24000)throw Error('Unsupported playback rate');
      await context.audioWorklet.addModule('/audio-worklets.mjs');
      if(this.output!==context)throw Error('Output replaced');
      const node=new AudioWorkletNode(context,'jarvis-playback',{numberOfInputs:0,numberOfOutputs:1,outputChannelCount:[1]});this.outputNode=node;
      node.port.onmessage=({data})=>{
        if(this.output!==context)return;
        if(data.type==='drained') {
          const latency=(context.baseLatency||0)+(context.outputLatency||0)+128/24000;
          setTimeout(()=>{if(data.generation===this.generation)this.api.drained(data.generation,data.sequence);},Math.ceil(latency*1000));
        }
        if(data.type==='playback-error'&&data.generation===this.generation)this.api.playbackFailed(data.generation,data.reason);
      };
      node.connect(context.destination);
      node.onprocessorerror=()=>{if(this.output===context){this.resetOutput(context);this.api.playbackFailed(this.generation,'device');}};
      node.port.postMessage({type:'flush',generation:this.generation});
    })().catch(error=>{this.resetOutput(context);throw error;});
    }
    const pending=this.outputPromise;await pending;
    const context=this.output;
    if(!context||this.outputPromise!==pending)throw Error('Output replaced');
    try{await context.resume();}catch(error){this.resetOutput(context);throw error;}
  }
  async start(epoch) {
    this.stopCapture(epoch);this.epoch=epoch;this.accepting=true;
    let stage='playback';
    try {
      await this.ensureOutput();
      if(this.epoch!==epoch||!this.accepting)return;
      stage='capture';
      const stream=await navigator.mediaDevices.getUserMedia({audio:{channelCount:1,echoCancellation:true,noiseSuppression:true,autoGainControl:true},video:false});
      if(this.epoch!==epoch||!this.accepting){for(const track of stream.getTracks())track.stop();return;}
      this.stream=stream;this.capture=new AudioContext({latencyHint:'interactive'});const context=this.capture;
      await context.audioWorklet.addModule('/audio-worklets.mjs');
      if(this.epoch!==epoch||!this.accepting){await context.close();return;}
      const node=new AudioWorkletNode(context,'jarvis-capture',{numberOfInputs:1,numberOfOutputs:1,outputChannelCount:[1],processorOptions:{epoch}});
      node.port.onmessage=({data})=>{if(this.accepting&&this.epoch===data.epoch)this.api.frame(data.epoch,data.frame);};
      context.createMediaStreamSource(stream).connect(node);node.connect(context.destination);
      await context.resume();
      for(const track of stream.getTracks()) {
        const failed=()=>{if(this.accepting&&this.epoch===epoch)this.api.microphoneFailed(epoch);};
        track.onended=failed;track.onmute=failed;
      }
      if(this.epoch===epoch&&this.accepting)this.api.microphoneReady(epoch);
    } catch {if(this.epoch===epoch&&this.accepting){this.stopCapture(epoch);if(stage==='capture')this.api.microphoneFailed(epoch);else this.api.playbackFailed(this.generation,'device');}}
  }
  stopCapture(epoch) {
    this.accepting=false;this.epoch=epoch;
    for(const track of this.stream?.getTracks()||[]){track.onended=null;track.onmute=null;track.stop();}
    this.stream=null;const capture=this.capture;this.capture=null;capture?.close().catch(()=>{});
  }
  async stop(epoch) {
    this.stopCapture(epoch);await this.output?.suspend().catch(()=>{});
    this.api.diagnostic?.({stage:'capture-stopped',tracks:this.stream?.getTracks().filter(t=>t.readyState==='live').length||0});
  }
  flush(generation) {this.generation=generation;this.outputNode?.port.postMessage({type:'flush',generation});}
  async play(packet) {
    if(packet.generation!==this.generation)return;
    try{await this.ensureOutput();if(packet.generation===this.generation)this.outputNode.port.postMessage(packet);}
    catch{if(packet.generation===this.generation)this.api.playbackFailed(packet.generation,'device');}
  }
  async tone(error=false) {
    try {
      await this.ensureOutput();const oscillator=this.output.createOscillator(),gain=this.output.createGain(),time=this.output.currentTime;
      oscillator.frequency.value=error?280:740;gain.gain.setValueAtTime(.0001,time);gain.gain.exponentialRampToValueAtTime(.045,time+.01);
      gain.gain.exponentialRampToValueAtTime(.0001,time+.12);oscillator.connect(gain);gain.connect(this.output.destination);oscillator.start(time);oscillator.stop(time+.13);
    } catch {}
  }
}
