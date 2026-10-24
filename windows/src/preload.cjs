const {contextBridge,ipcRenderer}=require('electron');
const actions=new Set(['start','talk','pause','pair','cancel-pair','hermes','compact','hide','quit']);
const api={
  state:()=>ipcRenderer.invoke('state'),
  command:action=>actions.has(action)?ipcRenderer.invoke('command',action):Promise.resolve(),
  subscribe:callback=>{const listener=(_event,value)=>callback(value);ipcRenderer.on('event',listener);return()=>ipcRenderer.removeListener('event',listener);},
  frame:(epoch,frame)=>{if(Number.isSafeInteger(epoch)&&frame instanceof Int16Array&&frame.length===512)ipcRenderer.send('audio-frame',epoch,frame);},
  microphoneReady:epoch=>ipcRenderer.send('microphone-ready',epoch),
  microphoneFailed:epoch=>ipcRenderer.send('microphone-failed',epoch),
  playbackFailed:(generation,reason)=>{if(Number.isSafeInteger(generation)&&['capacity','device'].includes(reason))ipcRenderer.send('playback-failed',generation,reason);},
  drained:(generation,sequence)=>ipcRenderer.send('drained',generation,sequence),
};
if(process.argv.includes('--jarvis-smoke'))api.diagnostic=value=>ipcRenderer.send('diagnostic',value);
contextBridge.exposeInMainWorld('jarvis',Object.freeze(api));
