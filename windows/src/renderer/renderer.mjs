import {AudioClient} from './audio-client.mjs';
const api=window.jarvis,audio=new AudioClient(api),$=id=>document.getElementById(id);
let state=null,orbReady=false;
const phases={WAITING:['Listening for “Jarvis”.','WAITING FOR WAKE','Say “Jarvis” to begin'],LISTENING:['I’m listening.','LISTENING','Go ahead, I’m here'],THINKING:['A moment, sir.','THINKING','Finding the right words'],SPEAKING:['Here’s what I found.','SPEAKING','Interrupt whenever you need'],FOLLOW_UP:['Anything else?','READY FOR MORE','No wake word needed'],PAUSED:['At your service.','MICROPHONE OFF','Start when you’re ready'],OFFLINE:['Finding my way back.','OFFLINE','Wake or tap to reconnect']};
function update(value) {
  state=value;const phase=phases[value.phase]||phases.PAUSED;
  $('headline').textContent=phase[0];$('phase').textContent=phase[1];$('wake-hint').textContent=phase[2];
  $('connection-label').textContent=!value.paired?'Ready to pair':value.connected?'Connected to Hermes':value.notice?.includes('Pair with Hermes')?'Pairing required':'Reconnecting…';
  $('connection').classList.toggle('connected',value.connected);document.body.classList.toggle('live',value.active&&value.micReady);
  $('mic-status').textContent=value.active?(value.micReady?'Microphone on':'Starting microphone…'):'Microphone off';
  $('start').textContent=value.active?'Pause listening':'Start listening';$('start').disabled=!value.paired;
  $('talk').disabled=!value.paired;$('orb-talk').disabled=!value.paired;
  $('compact').disabled=!value.paired;
  $('transcript').textContent=value.transcript||'Your next idea starts with a word.';
  $('reply').textContent=value.reply||'Say “Jarvis” or use the talk shortcut. I’ll take it from there.';
  const notice=value.notice||value.systemNotice;$('notice').hidden=!notice;$('notice').textContent=notice||'';
  $('revision').textContent=value.revision?'HERMES SETTINGS · REV '+value.revision:'OFFLINE WAKE · NO ACCOUNT OR KEY';
  const pairing=value.pairing||{phase:'idle'},busy=['connecting','waiting'].includes(pairing.phase);
  $('request').hidden=pairing.phase!=='waiting';$('pair').hidden=busy;$('cancel-pair').hidden=!busy;
  $('pair-title').textContent=busy?'One quick approval.':value.paired?'This PC is yours.':'Make this PC yours.';
  $('pair-description').textContent=value.paired?'Paired with Hermes. Your preferences stay in sync; listening starts only when you choose.':'Pair once in Hermes. Your voice, listening preferences and assistant stay together.';
  $('pair').textContent=value.paired?'Pair again':'Pair with Hermes';
  $('request-id').textContent=pairing.id?.toUpperCase()||'';
  $('pair-message').textContent=pairing.phase==='connecting'?'Creating a secure pairing request…':pairing.phase==='waiting'?'Open Hermes → Jarvis and approve this Windows PC.':pairing.phase==='expired'?'Request expired or declined. Start pairing again.':pairing.phase==='error'?pairing.message:value.paired?'Listening controls are ready.':'No API keys or device tokens to copy.';
  tickPairing();
  if(orbReady){const orb=$('orb').contentWindow.JarvisOrb;orb?.setActive(value.visible!==false);orb?.setState(value.orbState);orb?.setLevel(value.phase==='SPEAKING'?value.outLevel:value.micLevel);}
}
function tickPairing(){const expires=state?.pairing?.expiresAt;$('expires').textContent=expires?'Expires in '+Math.max(0,Math.ceil((expires-Date.now())/1000))+' seconds':'';}
$('orb').addEventListener('load',()=>{orbReady=true;if(state)update(state);api.diagnostic?.({stage:'ui-ready',nodeAbsent:typeof window.require==='undefined'&&typeof window.process==='undefined',orbReady:!!$('orb').contentWindow.JarvisOrb});});
for(const id of ['hermes','compact','hide','pair','cancel-pair'])$(id).onclick=()=>api.command(id);
$('start').onclick=()=>api.command(state?.active?'pause':'start');$('talk').onclick=$('orb-talk').onclick=()=>api.command('talk');
api.subscribe(event=>{
  if(event.type==='state')update(event.state);
  if(event.type==='capture-start')audio.start(event.epoch);
  if(event.type==='capture-stop')audio.stop(event.epoch);
  if(event.type==='flush')audio.flush(event.generation);
  if(event.type==='play')audio.play(event);
  if(event.type==='chime')audio.tone();
  if(event.type==='error-tone')audio.tone(true);
});
api.state().then(update);setInterval(tickPairing,1000);
