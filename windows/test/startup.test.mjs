import test from 'node:test';
import assert from 'node:assert/strict';
import {startupOptions} from '../src/startup.mjs';

test('portable startup targets the persistent launcher, rather than its deleted extraction directory',()=>{
  const result=startupOptions({packaged:true,smoke:false,enabled:true,executable:'C:/Temp/extracted/Jarvis.exe',portable:'C:/Apps/Jarvis-portable.exe'});
  assert.equal(result.path,'C:/Apps/Jarvis-portable.exe');assert.deepEqual(result.args,['--startup']);assert.equal(result.openAtLogin,true);
});
test('installed startup uses the installed executable; tests and development never write login settings',()=>{
  const options={packaged:true,smoke:false,enabled:false,executable:'C:/Programs/Jarvis/Jarvis.exe'};
  assert.equal(startupOptions(options).path,options.executable);assert.equal(startupOptions(options).openAtLogin,false);
  assert.equal(startupOptions({...options,smoke:true}),null);assert.equal(startupOptions({...options,packaged:false}),null);
});
