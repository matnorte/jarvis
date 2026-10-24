/** Streaming anti-aliased resampling; owns phase/filter history across device chunks. */
export class Resampler {
  constructor(inputRate) {
    if (!Number.isFinite(inputRate) || inputRate < 8000 || inputRate > 192000) throw Error('Unsupported microphone rate');
    this.step=inputRate/16000; this.index=0; this.next=0; this.previous=0;
    this.partial=new Int16Array(512); this.count=0;
    if (inputRate>16000) {
      const n=63, cutoff=7200/inputRate;
      this.filter=Float64Array.from({length:n},(_,i)=>{
        const x=i-(n-1)/2;
        return (x===0?2*cutoff:Math.sin(2*Math.PI*cutoff*x)/(Math.PI*x))*(.54-.46*Math.cos(2*Math.PI*i/(n-1)));
      });
      const total=this.filter.reduce((a,b)=>a+b,0); this.filter=this.filter.map(v=>v/total);
      this.history=new Float64Array(n); this.cursor=0;
    }
  }
  get bufferedSamples() { return this.count; }
  push(input) {
    const frames=[];
    for (const raw of input) {
      let value=Number.isFinite(raw)?Math.max(-1,Math.min(1,raw)):0;
      if (this.filter) {
        this.history[this.cursor]=value; value=0;
        for (let k=0;k<this.filter.length;k++) value+=this.filter[k]*this.history[(this.cursor-k+this.filter.length)%this.filter.length];
        this.cursor=(this.cursor+1)%this.filter.length;
      }
      while (this.next<=this.index+1e-7) {
        const fraction=Math.max(0,Math.min(1,this.next-(this.index-1)));
        const sample=Math.max(-1,Math.min(1,this.previous+(value-this.previous)*fraction));
        this.partial[this.count++]=Math.round(sample*(sample<0?32768:32767));
        if (this.count===512) { frames.push(this.partial); this.partial=new Int16Array(512); this.count=0; }
        this.next+=this.step;
      }
      this.previous=value; this.index++;
    }
    return frames;
  }
}

export class FrameInbox {
  constructor(capacity=8) { this.capacity=capacity; this.epoch=0; this.queue=[]; }
  get size() { return this.queue.length; }
  reset(epoch) { this.epoch=epoch; this.queue=[]; }
  offer(frame,epoch) {
    if (epoch!==this.epoch || !(frame instanceof Int16Array) || frame.length!==512) return false;
    if (this.queue.length===this.capacity) this.queue.shift();
    this.queue.push({frame:frame.slice(),epoch}); return true;
  }
  take() { return this.queue.shift(); }
}

/** Worklet-safe PCM FIFO. A flush also invalidates already-in-flight IPC audio. */
export class PlaybackQueue {
  // TTS can deliver minutes of speech ahead of real-time playback. Ten minutes
  // stays bounded at 28.8 MB of mono PCM16, rather than truncating normal replies.
  constructor(rate=24000,maxSeconds=600) { this.limit=rate*maxSeconds; this.generation=0; this.queue=[]; this.offset=0; this.queuedSamples=0; this.active=false; }
  enqueue(samples,generation) {
    if (generation!==this.generation) return false;
    if (!(samples instanceof Int16Array) || samples.length+this.queuedSamples>this.limit) return false;
    if (samples.length) { this.queue.push(samples.slice()); this.queuedSamples+=samples.length; this.active=true; }
    return true;
  }
  flush() { this.generation++; this.queue=[]; this.offset=0; this.queuedSamples=0; this.active=false; }
  render(out) {
    out.fill(0);
    for(let i=0;i<out.length && this.queue.length;i++) {
      const current=this.queue[0]; out[i]=current[this.offset++]/32768; this.queuedSamples--;
      if(this.offset===current.length){this.queue.shift();this.offset=0;}
    }
    if(this.active && !this.queuedSamples){this.active=false;return true;}
    return false;
  }
}
