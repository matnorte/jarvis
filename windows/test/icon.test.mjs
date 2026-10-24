import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {writeIcon} from '../scripts/icon.mjs';

test('Windows installer icon contains the required 256px PNG representation',async()=>{
  const dir=await mkdtemp(join(tmpdir(),'jarvis-icon-'));
  try{
    await writeIcon(dir);const png=await readFile(join(dir,'jarvis.png')),ico=await readFile(join(dir,'jarvis.ico'));
    assert.equal(png.readUInt32BE(16),256);assert.equal(png.readUInt32BE(20),256);
    assert.equal(ico[6],0);assert.equal(ico[7],0);assert.equal(ico.readUInt16LE(12),32);
    assert.deepEqual(ico.subarray(ico.readUInt32LE(18)),png);
  }finally{await rm(dir,{recursive:true,force:true});}
});
