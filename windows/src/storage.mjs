import {readFile} from 'node:fs/promises';
import {mkdirSync,writeFileSync,renameSync,unlinkSync} from 'node:fs';
import {dirname} from 'node:path';
import {preferences,DEFAULTS} from './core/protocol.mjs';

/** Windows safeStorage adapter is injected by main, never exposed through preload. */
export class CredentialStore {
  constructor(file,crypto) { this.file=file; this.crypto=crypto; this.token=null; this.encrypted=null; this.revision=0; this.settings={...DEFAULTS}; }
  get publicState() { return {paired:!!this.token,revision:this.revision,settings:{...this.settings}}; }
  async load() {
    try {
      const raw=await readFile(this.file,'utf8');
      if(raw.length>8192) throw Error();
      const value=JSON.parse(raw), settings=preferences(value.settings);
      if(!Number.isSafeInteger(value.revision)||value.revision<0||typeof value.encrypted!=='string'||value.encrypted.length>4096) throw Error();
      if(!await this.crypto.available()) throw Error();
      const token=await this.crypto.decrypt(Buffer.from(value.encrypted,'base64'));
      if(typeof token!=='string'||!/^[A-Za-z0-9_-]{8,512}$/.test(token)) throw Error();
      this.token=token;this.encrypted=value.encrypted;this.revision=value.revision;this.settings=settings;
    } catch(error) {
      if(error.code!=='ENOENT') throw new Error('Saved pairing could not be opened. Pair with Hermes again.');
    }
  }
  async install(value,isCurrent) {
    const settings=preferences(value.settings);
    if(value.device!=='win'||value.server_url!==settings.server_url||!Number.isSafeInteger(value.revision)||value.revision<0||typeof value.token!=='string'||!/^[A-Za-z0-9_-]{8,512}$/.test(value.token)) throw Error('Invalid pairing response');
    if(!await this.crypto.available()) throw Error('Windows protection is unavailable. Pairing was not saved.');
    const encrypted=(await this.crypto.encrypt(value.token)).toString('base64');
    if(!isCurrent()) throw Error('Pairing cancelled');
    this.persist(value.revision,settings,encrypted,isCurrent);
    this.encrypted=encrypted;this.token=value.token;this.revision=value.revision;this.settings=settings;
  }
  async cache(revision,settings) {
    settings=preferences(settings);
    if(!Number.isSafeInteger(revision)||revision<0) throw Error('Invalid revision');
    this.persist(revision,settings,this.encrypted,()=>true);
    this.settings=settings;this.revision=revision;
  }
  persist(revision,settings,encrypted,isCurrent) {
    // Tiny protected file: commit synchronously after encryption so Cancel cannot
    // interleave an asynchronous rename with installation of a new credential.
    mkdirSync(dirname(this.file),{recursive:true});
    const temporary=this.file+'.tmp';
    try {
      writeFileSync(temporary,JSON.stringify({version:1,encrypted,revision,settings}),{mode:0o600});
      if(!isCurrent()) throw Error('Pairing cancelled');
      renameSync(temporary,this.file);
    } finally {try{unlinkSync(temporary);}catch{}}
  }
}
