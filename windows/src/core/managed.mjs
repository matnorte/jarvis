/** Called by one serial owner; only safe idle phases can start an async settings apply. */
export class ManagedSync {
  constructor(revision,apply,send) { this.revision=revision; this.apply=apply; this.send=send; this.disconnected(); }
  offer(message) { this.pending=message; this.offered=true; this.failed=false; }
  disconnected() { this.pending=null; this.offered=false; this.failed=false; this.lastAck=null; }
  async tick(phase) {
    const safe=phase==='WAITING'||phase==='PAUSED';
    if(safe && this.pending && !this.failed) {
      const value=this.pending;
      if(await this.apply(value.revision,value.settings)) {this.revision=value.revision;this.pending=null;}
      else this.failed=true;
    }
    if(!this.offered) return;
    const ack={type:'config_ack',revision:this.revision,idle:safe && !this.pending};
    const key=JSON.stringify(ack);
    if(key!==this.lastAck && this.send(ack)) this.lastAck=key;
  }
}
