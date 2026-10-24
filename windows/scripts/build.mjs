import './prepare-assets.mjs';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

// Level 3 keeps repeatable preview builds practical on the development PC.
const cli=fileURLToPath(new URL('../node_modules/electron-builder/cli.js',import.meta.url));
const result=spawnSync(process.execPath,[cli,'--win','portable','nsis','--x64','--publish','never'],{
  stdio:'inherit',env:{...process.env,ELECTRON_BUILDER_COMPRESSION_LEVEL:'3',CSC_IDENTITY_AUTO_DISCOVERY:'false'},
});
process.exitCode=result.status??1;
