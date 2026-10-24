import {join} from 'node:path';
import sherpa from 'sherpa-onnx-node';

/** Native handles live only in the disposable utility process, never the renderer. */
export class WakeEngine {
  constructor(models,sensitivity=.6) {
    if(!Number.isFinite(sensitivity)||sensitivity<0||sensitivity>1)throw Error('Invalid wake sensitivity');
    this.spotter=new sherpa.KeywordSpotter({featConfig:{sampleRate:16000,featureDim:80},
      modelConfig:{transducer:{encoder:join(models,'encoder-epoch-13-avg-2-chunk-8-left-64.int8.onnx'),
        decoder:join(models,'decoder-epoch-13-avg-2-chunk-8-left-64.onnx'),joiner:join(models,'joiner-epoch-13-avg-2-chunk-8-left-64.int8.onnx')},
        tokens:join(models,'tokens.txt'),numThreads:1,provider:'cpu',debug:false,modelType:'zipformer2',modelingUnit:'phone+ppinyin'},
      keywordsFile:join(models,'keywords.txt'),keywordsThreshold:.55-sensitivity*.5,keywordsScore:1.5,numTrailingBlanks:1});
    this.vad=new sherpa.Vad({sileroVad:{model:join(models,'silero_vad.onnx'),threshold:.5,minSilenceDuration:0,
      minSpeechDuration:0,windowSize:512,maxSpeechDuration:30},sampleRate:16000,numThreads:1,provider:'cpu',debug:false},2);
    this.reset();
  }
  reset() {this.stream=this.spotter.createStream();this.vad.reset();}
  process(frame) {
    if(!(frame instanceof Int16Array)||frame.length!==512)throw Error('Invalid audio frame');
    const samples=Float32Array.from(frame,v=>v/32768);let wake=false;
    this.stream.acceptWaveform({samples,sampleRate:16000});
    while(this.spotter.isReady(this.stream)) {
      this.spotter.decode(this.stream);
      if(this.spotter.getResult(this.stream).keyword?.trim()){wake=true;this.spotter.reset(this.stream);}
    }
    this.vad.acceptWaveform(samples);const speech=this.vad.isDetected();
    while(!this.vad.isEmpty())this.vad.pop();
    return {wake,speech};
  }
}
