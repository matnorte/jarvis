/** Serial, transport-independent port of the Android Conversation policy. */
export class Conversation {
  constructor(effects, config = {}, now = () => performance.now()) {
    this.fx = effects; this.now = now;
    this.config = {endSilenceMs:700, noSpeechMs:6000, maxUtteranceMs:30000, followUpMs:6000, interruptBySpeech:false, ...config};
    this.phase = 'WAITING'; this.isConnected = false; this.tokenRejected = false;
    this.pendingWake = false; this.dropAudio = false; this.deep = false;
    this.gotAudio = false; this.errorPending = false; this.serverDone = false; this.playbackPending = false;
    this.phaseStart = 0; this.lastSpeechAt = 0; this.speechSeen = false; this.speechRun = 0;
    this.micLevel = 0; this.outLevel = 0; this.transcript = ''; this.reply = ''; this.notice = null;
    this.preRoll = [];
  }
  get state() {
    const orb = {WAITING:'idle',PAUSED:'idle',LISTENING:'listening',FOLLOW_UP:'listening',THINKING:this.deep?'deep':'thinking',SPEAKING:'speaking',OFFLINE:'error'};
    return {phase:this.phase,orbState:orb[this.phase],connected:this.isConnected,micLevel:this.micLevel,
      outLevel:this.outLevel,transcript:this.transcript,reply:this.reply,notice:this.notice};
  }
  configure(value) {
    if (!['WAITING','PAUSED'].includes(this.phase)) return false;
    this.config = {...this.config,...value}; return true;
  }
  frame(frame, wake, speech) {
    if (this.phase === 'PAUSED' || !(frame instanceof Int16Array) || frame.length !== 512) return;
    this.micLevel = level(frame);
    const t = this.now();
    if (this.phase === 'LISTENING') { this.fx.sendAudio(pcm(frame)); this.listen(t,speech); return; }
    this.preRoll.push(frame.slice()); if (this.preRoll.length > 10) this.preRoll.shift();
    if (['WAITING','OFFLINE'].includes(this.phase)) { if (wake) this.wake(); }
    else if (this.phase === 'THINKING') { if (wake) this.bargeIn(false); }
    else if (this.phase === 'SPEAKING') {
      this.speechRun = speech && this.config.interruptBySpeech ? this.speechRun + 1 : 0;
      if (wake) this.bargeIn(false); else if (this.speechRun >= 10) this.bargeIn(true);
    } else if (this.phase === 'FOLLOW_UP') {
      if (wake) this.wake(); else if (speech) this.startListening(true);
      else if (t - this.phaseStart >= this.config.followUpMs) this.phase = 'WAITING';
    }
  }
  tap() {
    if (['WAITING','FOLLOW_UP','OFFLINE'].includes(this.phase)) this.wake();
    else if (this.inTurn()) this.bargeIn(false);
  }
  server(message) {
    if (this.phase === 'PAUSED') return;
    if (message.type === 'state') { this.serverState(message.state); return; }
    if (this.dropAudio) return;
    if (message.type === 'transcript' && message.final) this.transcript = message.text;
    if (message.type === 'reply_text') this.reply = (this.reply + message.delta).slice(-65536);
    if (message.type === 'error') { this.notice = message.message; this.errorPending = true; }
    if (message.type === 'speaking_done' && this.inTurn()) this.serverFinished();
  }
  audio(bytes) {
    if (this.dropAudio || !this.inTurn()) return;
    this.fx.play(bytes);
    if (this.phase !== 'SPEAKING') this.speechRun = 0;
    this.phase = 'SPEAKING'; this.gotAudio = true; this.playbackPending = true; this.deep = false;
    const view = new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength);
    let squares=0; const n=Math.floor(bytes.byteLength/2);
    for (let i=0;i<n;i++) squares += view.getInt16(i*2,true)**2;
    this.outLevel = n ? Math.min(1,Math.sqrt(squares/n)/32768*4) : 0;
  }
  playbackDone() {
    if (!this.inTurn()) return;
    this.playbackPending = false; if (this.serverDone) this.enterFollowUp();
  }
  playbackFailed(message) {
    if (this.phase === 'PAUSED' || !this.inTurn()) return;
    this.fx.send({type:'interrupt'});this.fx.flush();this.dropAudio=true;
    this.phase='WAITING';this.serverDone=false;this.playbackPending=false;
    this.deep=false;this.outLevel=0;this.notice=message;
  }
  connected() {
    this.isConnected = true; this.tokenRejected = false;
    if (this.phase === 'PAUSED') return;
    this.notice = null;
    if (this.pendingWake) { this.pendingWake = false; this.startListening(false); }
    else if (this.phase === 'OFFLINE') this.phase = 'WAITING';
  }
  disconnected() { this.goOffline(null); }
  rejected(code) {
    if (code === 4401) this.tokenRejected = true;
    this.goOffline(code === 4401 ? 'Pair with Hermes again to connect.' : code === 4409 ? 'Connected in another Windows session.' : null);
  }
  pause() {
    if (this.phase === 'PAUSED') return;
    if (this.phase === 'LISTENING' || this.inTurn()) {
      this.fx.send({type:'interrupt'}); this.dropAudio = true;
      if (this.inTurn()) this.fx.flush();
    }
    this.phase = 'PAUSED'; this.pendingWake = false; this.micLevel = 0; this.outLevel = 0;
  }
  resume() {
    if (this.phase !== 'PAUSED') return;
    this.preRoll = []; this.phase = this.isConnected ? 'WAITING' : 'OFFLINE';
  }
  inTurn() { return this.phase === 'THINKING' || this.phase === 'SPEAKING'; }
  wake() {
    if (this.isConnected) { this.fx.chime(); this.startListening(false); }
    else if (!this.tokenRejected && !this.pendingWake) { this.pendingWake = true; this.fx.reconnect(); this.fx.chime(); }
  }
  bargeIn(speech) { this.fx.send({type:'interrupt'}); this.fx.flush(); this.dropAudio = true; this.startListening(speech); }
  startListening(speech) {
    for (const frame of this.preRoll.splice(0)) this.fx.sendAudio(pcm(frame));
    this.phase = 'LISTENING'; this.phaseStart = this.now(); this.lastSpeechAt = this.phaseStart; this.speechSeen = speech;
    this.reply = ''; this.notice = null; this.outLevel = 0; this.deep = false; this.gotAudio = false;
    this.errorPending = false; this.serverDone = false; this.playbackPending = false; this.speechRun = 0;
  }
  listen(t,speech) {
    if (speech) { this.speechSeen = true; this.lastSpeechAt = t; }
    const elapsed = t-this.phaseStart;
    if (this.speechSeen && (t-this.lastSpeechAt >= this.config.endSilenceMs || elapsed >= this.config.maxUtteranceMs)) {
      this.fx.send({type:'end_utterance'}); this.phase = 'THINKING';
    } else if (!this.speechSeen && (elapsed >= this.config.noSpeechMs || elapsed >= this.config.maxUtteranceMs)) {
      this.fx.send({type:'interrupt'}); this.dropAudio = true; this.phase = 'WAITING';
    }
  }
  serverState(state) {
    if (state === 'thinking' || state === 'deep') {
      if (state === 'thinking') this.dropAudio = false;
      if (!this.dropAudio && this.inTurn()) { this.phase = 'THINKING'; this.deep = state === 'deep'; this.outLevel = 0; }
    } else if (state === 'idle') {
      if (this.errorPending && this.inTurn()) this.serverFinished();
      else if (this.phase === 'THINKING' && !this.gotAudio) this.phase = 'WAITING';
    }
  }
  serverFinished() { this.serverDone = true; if (this.playbackPending) this.phase = 'SPEAKING'; else this.enterFollowUp(); }
  enterFollowUp() { this.phase='FOLLOW_UP'; this.phaseStart=this.now(); this.outLevel=0; this.deep=false; this.errorPending=false; }
  goOffline(notice) {
    const tone = this.isConnected || this.pendingWake;
    this.isConnected=false; this.pendingWake=false; this.dropAudio=false; this.errorPending=false;
    this.serverDone=false; this.playbackPending=false; this.deep=false; this.outLevel=0;
    if (notice !== null) this.notice=notice;
    if (this.phase !== 'PAUSED') { if (this.inTurn()) this.fx.flush(); if (tone) this.fx.errorTone(); this.phase='OFFLINE'; }
  }
}
function level(frame) { let sum=0; for (const v of frame) sum+=v*v; return Math.min(1,Math.sqrt(sum/frame.length)/32768*4); }
function pcm(frame) {
  const bytes=new Uint8Array(frame.length*2), view=new DataView(bytes.buffer);
  for (let i=0;i<frame.length;i++) view.setInt16(i*2,frame[i],true);
  return bytes;
}
