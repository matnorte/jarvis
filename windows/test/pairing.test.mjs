import test from 'node:test';
import assert from 'node:assert/strict';
import {PairingFlow} from '../src/pairing.mjs';
import {DEFAULTS} from '../src/core/protocol.mjs';

const response=body=>new Response(JSON.stringify(body),{status:200});
const paired={status:'paired',device:'win',token:'fake-win-credential',server_url:DEFAULTS.server_url,revision:1,settings:DEFAULTS};
test('Windows private claim only goes in POST bodies; public UI never receives credentials', async () => {
  const requests=[],states=[],installed=[];
  const flow=new PairingFlow({fetch:async(url,options)=>{requests.push([url,options]);return response(requests.length===1?{id:'aabb1234',expires_in:180}:paired);},
    install:async value=>installed.push(value),onState:s=>states.push(s)});
  await flow.start();
  assert.equal(installed[0].token,'fake-win-credential');
  assert.equal(JSON.parse(requests[0][1].body).device,'win');
  const claim=JSON.parse(requests[0][1].body).claim; assert.ok(claim.length>=43);
  assert.equal(JSON.parse(requests[1][1].body).claim,claim);
  assert.ok(requests.every(([url,options])=>!url.includes(claim)&&options.redirect==='error'));
  assert.ok(!JSON.stringify(states).includes(claim)); assert.ok(!JSON.stringify(states).includes('credential'));
  assert.equal(states.at(-1).phase,'paired');
});
test('cancelled/stale fetch cannot install even if it ignores abort; retry gets fresh proof', async () => {
  let finish, calls=0, installed=0; const claims=[];
  const flow=new PairingFlow({fetch:async(_url,options)=>{
    const body=JSON.parse(options.body); if(body.device){claims.push(body.claim);return response({id:'aabb1234',expires_in:180});}
    calls++; if(calls===1)return new Promise(resolve=>finish=()=>resolve(response(paired))); return response(paired);
  },install:async()=>installed++,onState:()=>{}});
  const run=flow.start(); await new Promise(resolve=>setImmediate(resolve)); flow.cancel(); finish(); await run;
  assert.equal(installed,0); await flow.start(); assert.equal(installed,1); assert.notEqual(claims[0],claims[1]);
});
test('expired/declined requests terminate; malformed and oversize errors are fixed and redacted', async () => {
  for(const bad of [{status:'expired'},'oversize',{...paired,device:'android'}]) {
    let n=0;const states=[];const flow=new PairingFlow({fetch:async()=>++n===1?response({id:'aabb1234',expires_in:180}):bad==='oversize'?new Response('private'.repeat(2000)):response(bad),install:async()=>assert.fail(),onState:s=>states.push(s)});
    await flow.start(); assert.ok(['expired','error'].includes(states.at(-1).phase)); assert.ok(!JSON.stringify(states).includes('private'));
  }
});
