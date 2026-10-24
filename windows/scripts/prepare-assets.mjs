import {createHash} from 'node:crypto';
import {readFile,mkdir,writeFile,cp,copyFile} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {join} from 'node:path';
import {writeIcon} from './icon.mjs';

const root=fileURLToPath(new URL('..',import.meta.url));
const assets=join(root,'assets'),models=join(assets,'models'),cache=join(root,'build');
const base='https://github.com/k2-fsa/sherpa-onnx/releases/download/';
await mkdir(models,{recursive:true});await mkdir(cache,{recursive:true});
const sha=bytes=>createHash('sha256').update(bytes).digest('hex');
async function download(url,path,expected,alternative) {
  let bytes;
  for(const candidate of [path,alternative].filter(Boolean)) {
    try{const data=await readFile(candidate);if(sha(data)===expected){bytes=data;break;}}catch{}
  }
  if(!bytes) {
    const response=await fetch(url,{signal:AbortSignal.timeout(180000)});
    if(!response.ok)throw Error('Model download failed');
    bytes=Buffer.from(await response.arrayBuffer());
    if(sha(bytes)!==expected)throw Error('Model checksum mismatch');
  }
  await writeFile(path,bytes);
}
const archive=join(cache,'kws-model.tar.bz2');
await download(base+'kws-models/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20.tar.bz2',archive,
  '68447f4fbc67e70eee3a93961f36e81e98f47aef73ce7e7ca00885c6cd3616a6',join(root,'../android/app/build/wake-download/kws-model.tar.bz2'));
const hashes={
  'encoder-epoch-13-avg-2-chunk-8-left-64.int8.onnx':'2ca84d6bfe73e1ea3c9c49f600f7cad1c9ddd423c53c906b8bfe802444dd78d5',
  'decoder-epoch-13-avg-2-chunk-8-left-64.onnx':'63a22dd60f40fff082ac3e09afa507f6787da36df76ded2fbe145fa233e22c21',
  'joiner-epoch-13-avg-2-chunk-8-left-64.int8.onnx':'190d4067b4cc20b72a42a1916e69d92052000fb7051a427ebb1bc72a69207dc1',
  'tokens.txt':'2d3f32311f9b692b964da3c90e830258d3e78e013cb0c992dbfb15cd5a1a71b0',
};
execFileSync('tar',['-xjf',archive,'-C',models,'--strip-components','1',...Object.keys(hashes).map(name=>'sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20/'+name)]);
for(const [name,expected] of Object.entries(hashes))if(sha(await readFile(join(models,name)))!==expected)throw Error('Extracted wake model checksum mismatch');
await download(base+'asr-models/silero_vad.onnx',join(models,'silero_vad.onnx'),'9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6');
await writeFile(join(models,'keywords.txt'),'JH AA1 R V AH0 S @JARVIS\n');
await cp(join(root,'../ui/orb'),join(assets,'orb'),{recursive:true});
await copyFile(join(root,'../android/app/src/androidTest/assets/jarvis.wav'),join(cache,'jarvis.wav'));
await writeIcon(assets);
console.log('Prepared pinned offline wake/VAD models and shared orb.');
