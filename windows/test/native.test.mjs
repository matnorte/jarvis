import test from 'node:test';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
import {join} from 'node:path';
import sherpa from 'sherpa-onnx-node';
import {WakeEngine} from '../src/wake-engine.mjs';

const root=fileURLToPath(new URL('..',import.meta.url));
test('packaged native Windows engine rejects silence and detects synthetic Jarvis locally', () => {
  const engine=new WakeEngine(join(root,'assets/models'),.6);
  for(let i=0;i<60;i++){const result=engine.process(new Int16Array(512));assert.equal(result.wake,false);assert.equal(result.speech,false);}
  engine.reset();
  const wave=sherpa.readWave(join(root,'build/jarvis.wav'));
  assert.equal(wave.sampleRate,16000);
  let wakes=0, speech=0;
  const floats=wave.samples;
  for(let i=0;i<floats.length+16000;i+=512){const frame=new Int16Array(512);for(let j=0;j<512;j++)frame[j]=Math.round((floats[i+j]||0)*32767);const result=engine.process(frame);if(result.wake)wakes++;if(result.speech)speech++;}
  assert.ok(wakes>=1);assert.ok(speech>=5);
});
