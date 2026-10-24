import {Resampler,PlaybackQueue} from './core/audio.mjs';

class Capture extends AudioWorkletProcessor {
  constructor(options){super();this.epoch=options.processorOptions.epoch;this.resampler=new Resampler(sampleRate);}
  process(inputs) {
    const samples=inputs[0]?.[0];
    if(samples)for(const frame of this.resampler.push(samples))this.port.postMessage({epoch:this.epoch,frame},[frame.buffer]);
    return true;
  }
}
class Playback extends AudioWorkletProcessor {
  constructor() {
    super();this.queue=new PlaybackQueue(24000);this.sequence=0;this.failed=false;
    this.port.onmessage=({data})=>{
      if(data.type==='flush'&&data.generation>this.queue.generation){this.queue.flush();this.queue.generation=data.generation;this.failed=false;}
      else if(data.type==='play'&&!this.failed&&data.generation===this.queue.generation) {
        const bytes=data.bytes, view=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength),samples=new Int16Array(bytes.byteLength/2);
        for(let i=0;i<samples.length;i++)samples[i]=view.getInt16(i*2,true);
        if(!this.queue.enqueue(samples,data.generation)) {
          this.failed=true;
          this.port.postMessage({type:'playback-error',generation:data.generation,reason:'capacity'});
        }
        else this.sequence=data.sequence;
      }
    };
  }
  process(_inputs,outputs) {
    if(this.failed){outputs[0][0].fill(0);return true;}
    if(this.queue.render(outputs[0][0]))this.port.postMessage({type:'drained',generation:this.queue.generation,sequence:this.sequence});
    return true;
  }
}
registerProcessor('jarvis-capture',Capture);registerProcessor('jarvis-playback',Playback);
