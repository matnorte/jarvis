import {deflateSync} from 'node:zlib';
import {writeFile} from 'node:fs/promises';
import {join} from 'node:path';

export async function writeIcon(directory) {
  const size=256,rows=Buffer.alloc((size*4+1)*size);
  for(let y=0;y<size;y++)for(let x=0;x<size;x++) {
    const dx=(x-(size-1)/2)*64/size,dy=(y-(size-1)/2)*64/size,r=Math.hypot(dx,dy),angle=Math.atan2(dy,dx);
    const ring=Math.exp(-(((r-24)/1.2)**2)),inner=Math.exp(-(((r-14)/.85)**2))*.55;
    const core=Math.exp(-((r/7)**2)),spark=Math.exp(-(((r-24)/3)**2))*Math.max(0,Math.sin(angle*9))**12;
    const glow=Math.exp(-((r/23)**2))*.25,alpha=Math.min(1,ring+inner+core+spark+glow);
    const p=y*(size*4+1)+x*4+1;rows[p]=255;rows[p+1]=Math.round(165+80*core);rows[p+2]=Math.round(45+160*core);rows[p+3]=Math.round(alpha*255);
  }
  function chunk(type,data) {
    const label=Buffer.from(type),payload=Buffer.concat([label,data]);let crc=0xffffffff;
    for(const byte of payload){crc^=byte;for(let b=0;b<8;b++)crc=(crc>>>1)^((crc&1)?0xedb88320:0);}
    const header=Buffer.alloc(4),tail=Buffer.alloc(4);header.writeUInt32BE(data.length);tail.writeUInt32BE((crc^0xffffffff)>>>0);
    return Buffer.concat([header,payload,tail]);
  }
  const ihdr=Buffer.alloc(13);ihdr.writeUInt32BE(size,0);ihdr.writeUInt32BE(size,4);ihdr[8]=8;ihdr[9]=6;
  const png=Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',ihdr),chunk('IDAT',deflateSync(rows)),chunk('IEND',Buffer.alloc(0))]);
  await writeFile(join(directory,'jarvis.png'),png);
  const ico=Buffer.alloc(22);ico.writeUInt16LE(1,2);ico.writeUInt16LE(1,4);ico[6]=0;ico[7]=0;ico.writeUInt16LE(1,10);ico.writeUInt16LE(32,12);ico.writeUInt32LE(png.length,14);ico.writeUInt32LE(22,18);
  await writeFile(join(directory,'jarvis.ico'),Buffer.concat([ico,png]));
}
