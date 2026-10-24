import electron from 'electron';
import {fileURLToPath} from 'node:url';
import {join,extname,resolve,sep} from 'node:path';
import {readFileSync,mkdtempSync} from 'node:fs';
import {tmpdir} from 'node:os';
import assert from 'node:assert/strict';
import {CredentialStore} from './storage.mjs';
import {Controller} from './controller.mjs';
import {PairingFlow} from './pairing.mjs';
import {WakeWorker} from './wake-worker.mjs';
import {startupOptions} from './startup.mjs';
import {HERMES,DEFAULTS} from './core/protocol.mjs';

const {app,BrowserWindow,Tray,Menu,nativeImage,nativeTheme,globalShortcut,ipcMain,safeStorage,session,protocol,powerMonitor,shell}=electron;
const root=fileURLToPath(new URL('..',import.meta.url)),APP_URL='jarvis://app/index.html';
const smoke=process.argv.includes('--smoke-test'),startup=process.argv.includes('--startup');
const longSmoke=smoke&&process.argv.includes('--smoke-long-reply');
if(smoke){app.setPath('userData',mkdtempSync(join(tmpdir(),'jarvis-smoke-')));app.commandLine.appendSwitch('use-fake-device-for-media-stream');app.commandLine.appendSwitch('use-fake-ui-for-media-stream');}
app.commandLine.appendSwitch('autoplay-policy','no-user-gesture-required');
protocol.registerSchemesAsPrivileged([{scheme:'jarvis',privileges:{standard:true,secure:true,supportFetchAPI:true,stream:true,corsEnabled:true}}]);
let window,tray,controller,pairing,quitting=false,compact=false,pairState={phase:'idle'},systemNotice=null,latest=null,lastTray='',smokeFrames=0,smokeInferences=0,smokePausing=false;
let smokeCallbacks=null,smokeLongVerified=false;
if(!app.requestSingleInstanceLock())app.quit();
else {
  app.on('second-instance',()=>show());
  app.whenReady().then(ready).catch(()=>{console.error('Jarvis could not start.');app.exit(1);});
}
function show(){if(window){window.show();if(window.isMinimized())window.restore();window.focus();}}
function send(value){if(window&&!window.isDestroyed())window.webContents.send('event',value);}
function state(){return {...controller.state,pairing:pairState,systemNotice,visible:!!window?.isVisible()&&!window?.isMinimized()};}
function publish(){latest=state();updateTray();}
function trusted(event){return window&&!window.isDestroyed()&&event.sender===window.webContents&&event.senderFrame===window.webContents.mainFrame&&event.senderFrame.url===APP_URL;}
function updateTray() {
  if(!tray||!controller)return;
  const value=controller.state,key=value.phase+value.active+value.paired;
  if(key===lastTray)return;lastTray=key;
  tray.setToolTip('Jarvis · '+(value.active?value.phase.toLowerCase().replace('_',' '):'microphone off'));
  tray.setContextMenu(Menu.buildFromTemplate([
    {label:'Show Jarvis',click:show},{label:'Talk · Ctrl+Shift+Space',click:()=>talk()},
    {label:value.active?'Pause listening':'Start listening',enabled:value.paired,click:()=>controller.command(value.active?'pause':'start')},
    {type:'separator'},{label:'Open Hermes settings',click:()=>shell.openExternal(HERMES)},
    {type:'separator'},{label:'Quit Jarvis',click:()=>app.quit()},
  ]));
}
function talk(){if(!controller.store.token){show();return;}controller.command('talk');}
function setStartup(preferences){const options=startupOptions({packaged:app.isPackaged,smoke,enabled:preferences.start_on_boot,
  executable:process.execPath,portable:process.env.PORTABLE_EXECUTABLE_FILE});if(options)app.setLoginItemSettings(options);}
async function ready() {
  nativeTheme.themeSource='dark';app.setAppUserModelId('com.openclawchip.jarvis.windows');Menu.setApplicationMenu(null);
  const assets=app.isPackaged?join(process.resourcesPath,'app.asar.unpacked','assets'):join(root,'assets');
  const models=join(assets,'models');
  protocol.handle('jarvis',request=>{
    try {
      const url=new URL(request.url);if(url.hostname!=='app')return new Response('Not found',{status:404});
      const routes={'/index.html':'renderer/index.html','/ui.css':'renderer/ui.css','/renderer.mjs':'renderer/renderer.mjs',
        '/audio-client.mjs':'renderer/audio-client.mjs','/audio-worklets.mjs':'renderer/audio-worklets.mjs','/core/audio.mjs':'core/audio.mjs'};
      let path;
      if(routes[url.pathname])path=join(root,'src',routes[url.pathname]);
      else if(url.pathname.startsWith('/orb/')){
        const base=resolve(root,'assets','orb'),relative=decodeURIComponent(url.pathname.slice(5));path=resolve(base,relative);
        if(!path.startsWith(base+sep)||!['.html','.js','.css'].includes(extname(path)))return new Response('Not found',{status:404});
      } else return new Response('Not found',{status:404});
      const mime={'.html':'text/html; charset=utf-8','.js':'text/javascript','.mjs':'text/javascript','.css':'text/css'};
      return new Response(readFileSync(path),{headers:{'Content-Type':mime[extname(path)],'Cache-Control':'no-store',
        'Content-Security-Policy':url.pathname.startsWith('/orb/')?"default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'none'; media-src 'none'; object-src 'none'":"default-src 'self'; object-src 'none'; base-uri 'none'"}});
    } catch{return new Response('Not found',{status:404});}
  });
  const crypto={available:()=>safeStorage.isAsyncEncryptionAvailable(),encrypt:value=>safeStorage.encryptStringAsync(value),decrypt:value=>safeStorage.decryptStringAsync(value)};
  const store=new CredentialStore(join(app.getPath('userData'),'pairing.json'),crypto);
  try{await store.load();}catch{systemNotice='Saved pairing could not be opened. Pair with Hermes again.';}
  if(smoke)await store.install({device:'win',token:'smoke-only-fake-credential',server_url:DEFAULTS.server_url,revision:1,settings:DEFAULTS},()=>true);
  const worker=new WakeWorker(models,{fork:(...args)=>electron.utilityProcess.fork(...args)});
  const options={store,worker,send,emit:publish,onPreferences:setStartup};
  if(smoke)options.createGateway=callbacks=>{smokeCallbacks=callbacks;return {connect(){queueMicrotask(()=>{callbacks.onConnected();callbacks.onMessage({type:'config',revision:1,settings:DEFAULTS});});},send(){return true;},sendAudio(){return true;},close(){},reconnectNow(){}};};
  controller=new Controller(options);
  if(smoke){const onFrame=worker.onFrame;worker.onFrame=(...args)=>{smokeInferences++;return onFrame(...args);};}
  pairing=new PairingFlow({install:async(value,current)=>{
    await controller.serial;await store.install(value,current);controller.conversation.configure({endSilenceMs:store.settings.end_silence_ms,interruptBySpeech:store.settings.interrupt_by_speech});
    systemNotice=null;controller.connect();
  },onState:value=>{pairState=value;publish();}});
  window=new BrowserWindow({title:'Jarvis',width:1080,height:810,minWidth:850,minHeight:690,show:!startup&&!smoke,backgroundColor:'#090b0e',
    icon:join(root,'assets','jarvis.png'),autoHideMenuBar:true,
    webPreferences:{preload:join(root,'src','preload.cjs'),nodeIntegration:false,contextIsolation:true,sandbox:true,webviewTag:false,webSecurity:true,
      backgroundThrottling:false,additionalArguments:smoke?['--jarvis-smoke']:[]}});
  const ownsMedia=(contents,permission,origin)=>controller.active&&contents===window.webContents&&permission==='media'&&/^jarvis:\/\/app(?:\/index\.html|\/)?$/.test(origin||'');
  session.defaultSession.setPermissionCheckHandler((contents,permission,origin,details)=>ownsMedia(contents,permission,origin)&&details.mediaType==='audio');
  session.defaultSession.setPermissionRequestHandler((contents,permission,callback,details)=>callback(ownsMedia(contents,permission,details.requestingUrl)&&
    details.isMainFrame!==false&&Array.isArray(details.mediaTypes)&&details.mediaTypes.length>0&&details.mediaTypes.every(type=>type==='audio')));
  window.webContents.setWindowOpenHandler(()=>({action:'deny'}));
  window.webContents.on('will-navigate',event=>event.preventDefault());window.webContents.on('will-redirect',event=>event.preventDefault());
  window.webContents.on('will-attach-webview',event=>event.preventDefault());
  window.webContents.on('render-process-gone',()=>{controller.command('pause');systemNotice='Window stopped. Reopen Jarvis to resume.';});
  window.on('close',event=>{if(!quitting){event.preventDefault();window.hide();}});
  for(const name of ['show','hide','minimize','restore'])window.on(name,publish);
  const icon=nativeImage.createFromPath(join(root,'assets','jarvis.png'));if(smoke)assert.ok(!icon.isEmpty());
  tray=new Tray(icon);tray.on('click',show);tray.on('double-click',show);
  if(!globalShortcut.register('CommandOrControl+Shift+Space',talk))systemNotice='Ctrl+Shift+Space is in use. Talk and tray controls still work.';
  for(const name of ['suspend','lock-screen'])powerMonitor.on(name,()=>controller.command('pause'));
  ipcMain.handle('state',event=>trusted(event)?state():null);
  ipcMain.handle('command',async(event,action)=>{
    if(!trusted(event))return;
    if(['start','talk','pause'].includes(action))return controller.command(action);
    if(action==='hermes')return shell.openExternal(HERMES);
    if(action==='hide')window.hide();
    if(action==='quit')app.quit();
    if(action==='compact'){
      compact=!compact;window.setMinimumSize(compact?360:850,compact?480:690);window.setSize(compact?420:1080,compact?585:810);window.setAlwaysOnTop(compact);show();
    }
    if(action==='cancel-pair')pairing.cancel();
    if(action==='pair'&&!['connecting','waiting'].includes(pairState.phase)){
      await controller.command('pause');controller.gateway?.close();controller.gateway=null;controller.managed.disconnected();
      pairing.start(store.settings.server_url);
    }
    publish();
  });
  ipcMain.on('audio-frame',(event,epoch,frame)=>{
    if(!trusted(event)||!Number.isSafeInteger(epoch)||!(frame instanceof Int16Array)||frame.length!==512)return;
    controller.frame(frame,epoch);
    if(smoke&&++smokeFrames>=8&&smokeInferences>=3&&!smokePausing){
      smokePausing=true;
      if(longSmoke) {
        controller.dispatch(()=>{controller.conversation.phase='THINKING';}).then(async()=>{
          for(let i=0;i<120;i++)smokeCallbacks.onAudio(new Uint8Array(48000));
          await controller.serial;
          setTimeout(()=>{
            if(!controller.active||!controller.micReady||controller.state.phase!=='SPEAKING'){console.error('FAIL: long reply stopped capture or playback');app.exit(1);return;}
            smokeLongVerified=true;controller.command('pause');
          },1000);
        });
      } else controller.command('pause');
    }
  });
  ipcMain.on('microphone-ready',(event,epoch)=>{if(trusted(event)&&Number.isSafeInteger(epoch))controller.microphoneReady(epoch);});
  ipcMain.on('microphone-failed',(event,epoch)=>{if(trusted(event)&&Number.isSafeInteger(epoch)){
    if(smoke){console.error('FAIL: synthetic capture reported a microphone failure');app.exit(1);return;}
    controller.microphoneFailed(epoch);
  }});
  ipcMain.on('playback-failed',(event,generation,reason)=>{
    if(trusted(event)&&Number.isSafeInteger(generation)&&['capacity','device'].includes(reason)){
      if(smoke){console.error('FAIL: synthetic reply reported a playback failure');app.exit(1);return;}
      controller.playbackFailed(generation,reason);
    }
  });
  ipcMain.on('drained',(event,generation,sequence)=>{
    if(!trusted(event)||!Number.isSafeInteger(generation)||!Number.isSafeInteger(sequence))return;
    controller.drained(generation,sequence);
    if(smoke&&sequence===999){assert.ok(smokeFrames>=8&&smokeInferences>=3);if(longSmoke)assert.ok(smokeLongVerified);console.log(longSmoke?'PASS: two-minute reply burst preserves capture; flush and short playback drain recover correctly':'PASS: sandboxed UI/orb, Windows DPAPI, native inference, synthetic microphone, pause cleanup and actual short playback drain');app.quit();}
  });
  if(smoke){
    const deadline=setTimeout(()=>{console.error('FAIL: desktop smoke timed out');app.exit(1);},40000);deadline.unref();
    ipcMain.on('diagnostic',async(event,value)=>{
      if(!trusted(event))return;
      if(value?.stage==='ui-ready'){
        const prefs=window.webContents.getLastWebPreferences();assert.ok(value.nodeAbsent&&value.orbReady&&prefs.sandbox&&prefs.contextIsolation&&!prefs.nodeIntegration);
        await controller.command('start');
      }
      if(value?.stage==='capture-stopped'&&smokePausing&&(!longSmoke||smokeLongVerified)){assert.equal(value.tracks,0);send({type:'play',bytes:new Uint8Array(4800),generation:controller.playbackGeneration,sequence:999});}
    });
  }
  const ticker=setInterval(()=>{if(latest){send({type:'state',state:latest});latest=null;}controller.checkMicrophone();},100);
  app.on('before-quit',()=>{quitting=true;clearInterval(ticker);controller.close();pairing.cancel();globalShortcut.unregisterAll();tray.destroy();});
  setStartup(store.settings);controller.connect();publish();await window.loadURL(APP_URL);
}
