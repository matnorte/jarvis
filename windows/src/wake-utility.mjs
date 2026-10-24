import {WakeEngine} from './wake-engine.mjs';

try {
  const engine=new WakeEngine(process.argv[2],Number(process.argv[3]));
  process.parentPort.on('message',({data})=>{
    if(data?.type!=='frame'||!(data.frame instanceof Int16Array)||data.frame.length!==512)return;
    try{process.parentPort.postMessage({type:'frame',id:data.id,...engine.process(data.frame)});}
    catch{process.exit(1);}
  });
  process.parentPort.postMessage({type:'ready'});
} catch {process.parentPort.postMessage({type:'failed'});process.exit(1);}
