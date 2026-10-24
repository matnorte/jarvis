export const GATEWAY = 'wss://jarvis.openclawchip.tech/ws';
export const HERMES = 'https://jarvis.openclawchip.tech/jarvis?profile=fast';
export const DEFAULTS = Object.freeze({server_url:GATEWAY, wake_sensitivity:.6, end_silence_ms:700,
  interrupt_by_speech:false, start_on_boot:false, vision_enabled:true, front_camera:false});

export function preferences(value) {
  try {
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw Error();
    if (typeof value.server_url !== 'string' || /\s/.test(value.server_url)) throw Error();
    const url = new URL(value.server_url);
    if (url.protocol !== 'wss:' || !url.hostname || url.username || url.password || url.search || url.hash) throw Error();
    if (typeof value.wake_sensitivity !== 'number' || !Number.isFinite(value.wake_sensitivity) || value.wake_sensitivity < 0 || value.wake_sensitivity > 1) throw Error();
    if (!Number.isSafeInteger(value.end_silence_ms) || value.end_silence_ms < 100 || value.end_silence_ms > 5000) throw Error();
    for (const key of ['interrupt_by_speech','start_on_boot','vision_enabled','front_camera']) if (typeof value[key] !== 'boolean') throw Error();
    return Object.fromEntries(Object.keys(DEFAULTS).map(key => [key, value[key]]));
  } catch { throw new Error('Invalid managed preferences'); }
}

export const hello = token => ({type:'hello',token,device:'win',client_vad:true,client_config:true});
export function parseServer(raw) {
  if (typeof raw !== 'string' || raw.length > 65536) return null;
  try {
    const v = JSON.parse(raw);
    if (!v || Array.isArray(v)) return null;
    if (v.type === 'config' && Number.isSafeInteger(v.revision) && v.revision >= 0) return {type:v.type,revision:v.revision,settings:preferences(v.settings)};
    if (v.type === 'state' && ['idle','listening','thinking','deep','speaking','error'].includes(v.state)) return {type:v.type,state:v.state};
    if (v.type === 'transcript' && typeof v.text === 'string' && v.text.length <= 8000 && typeof v.final === 'boolean') return {type:v.type,text:v.text,final:v.final};
    if (v.type === 'reply_text' && typeof v.delta === 'string' && v.delta.length <= 32768) return {type:v.type,delta:v.delta};
    if (v.type === 'error' && typeof v.message === 'string' && v.message.length <= 1024) return {type:v.type,message:v.message};
    if (v.type === 'speaking_done') return {type:v.type};
  } catch {}
  return null;
}
