import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {CredentialStore} from '../src/storage.mjs';
import {DEFAULTS} from '../src/core/protocol.mjs';

test('credentials never persist plaintext or enter public state; cache updates preserve encrypted token', async () => {
  const dir=await mkdtemp(join(tmpdir(),'jarvis-vault-')); const file=join(dir,'settings.json');
  const crypto={available:async()=>true,encrypt:async s=>Buffer.from(s.split('').reverse().join('')),decrypt:async b=>b.toString().split('').reverse().join('')};
  try {
    const store=new CredentialStore(file,crypto);
    await store.install({token:'fake-win-credential',device:'win',revision:1,settings:DEFAULTS,server_url:DEFAULTS.server_url},()=>true);
    assert.ok(!(await readFile(file,'utf8')).includes('fake-win-credential'));
    assert.ok(!JSON.stringify(store.publicState).includes('credential'));
    await store.cache(2,{...DEFAULTS,end_silence_ms:900});
    const reloaded=new CredentialStore(file,crypto); await reloaded.load();
    assert.equal(reloaded.token,'fake-win-credential'); assert.equal(reloaded.publicState.revision,2);
  } finally {await rm(dir,{recursive:true,force:true});}
});
test('no plaintext fallback when OS encryption is unavailable; cancelled encryption cannot install', async () => {
  const dir=await mkdtemp(join(tmpdir(),'jarvis-vault-'));
  try {
    const store=new CredentialStore(join(dir,'settings.json'),{available:async()=>false});
    await assert.rejects(store.install({token:'fake-win-credential',device:'win',revision:1,settings:DEFAULTS,server_url:DEFAULTS.server_url},()=>true),/Windows protection/);
    let current=true;
    const cancelled=new CredentialStore(join(dir,'other.json'),{available:async()=>true,encrypt:async()=>{current=false;return Buffer.from('encrypted');}});
    await assert.rejects(cancelled.install({token:'fake-win-credential',device:'win',revision:1,settings:DEFAULTS,server_url:DEFAULTS.server_url},()=>current),/cancelled/);
    assert.equal(cancelled.publicState.paired,false);
  } finally {await rm(dir,{recursive:true,force:true});}
});
