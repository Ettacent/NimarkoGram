/* Modifications Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */
import {clipOutside} from './night-protocol.mjs';
export const CLIP_SIDE = 64;
export const CLIP_CASES = 3;
export const CLIP_BYTES = CLIP_SIDE * CLIP_SIDE * CLIP_CASES * 4;
export const CLIP_PROBE_MAX_PIXELS = 65536;
export function integerRectangle(q) {
  if(!Array.isArray(q)||q.length!==8||!q.every(Number.isSafeInteger))return false;
  const horizontal=q[1]===q[3]&&q[2]===q[4]&&q[5]===q[7]&&q[6]===q[0]&&q[0]!==q[2]&&q[1]!==q[5];
  const vertical=q[0]===q[2]&&q[3]===q[5]&&q[4]===q[6]&&q[7]===q[1]&&q[0]!==q[4]&&q[1]!==q[3];
  return horizontal||vertical;
}
export function clipAllowed(keep, compatible) {
  return compatible===true||keep.every(integerRectangle);
}
export function renderClipCanary(createCanvas) {
  const n=CLIP_SIDE,q=[11.2,3.7,55.8,19.1,40.4,60.3,1.8,35.9],shift=q.map((v,i)=>v+(i%2?-2:3));
  const data=new Uint8ClampedArray(CLIP_BYTES);let source=null,canvas=null;
  try{
    source=createCanvas(n,32);const upload=source.getContext('2d',{willReadFrequently:true,colorSpace:'srgb'});
    upload.fillStyle='black';upload.fillRect(0,0,n,32);
    for(let test=0;test<CLIP_CASES;test++){
      canvas=createCanvas(n,n);const ctx=canvas.getContext('2d',{colorSpace:'srgb'});
      ctx.fillStyle='white';ctx.fillRect(0,0,n,n);ctx.save();
      clipOutside(ctx,test===2?[q,q,shift]:[q],n,n);
      if(test===0){ctx.fillStyle='black';ctx.fillRect(0,0,n,n);}
      else {ctx.drawImage(source,0,0);ctx.drawImage(source,0,32);}
      ctx.restore();data.set(ctx.getImageData(0,0,n,n).data,test*n*n*4);
      canvas.width=canvas.height=0;canvas=null;
    }
    return data;
  }finally{if(canvas)canvas.width=canvas.height=0;if(source)source.width=source.height=0;}
}
export function compareClipCanaries(dom, worker) {
  if(!(dom instanceof Uint8ClampedArray)||!(worker instanceof Uint8ClampedArray)||dom.length!==CLIP_BYTES||worker.length!==CLIP_BYTES)throw Error('clip-canary-contract');
  const result={compatible:true,channels:0,maxDelta:0,cases:[]};
  for(let test=0;test<CLIP_CASES;test++){
    let channels=0,maxDelta=0,min=255,max=0;
    for(let i=test*CLIP_SIDE*CLIP_SIDE*4;i<(test+1)*CLIP_SIDE*CLIP_SIDE*4;i++){
      if(i%4===3){if(dom[i]!==255||worker[i]!==255)throw Error('clip-canary-alpha');}
      else {min=Math.min(min,dom[i]);max=Math.max(max,dom[i]);}
      const delta=Math.abs(dom[i]-worker[i]);if(delta){channels++;maxDelta=Math.max(maxDelta,delta);}
    }
    if(min!==0||max!==255)throw Error('clip-canary-blank');
    result.channels+=channels;result.maxDelta=Math.max(result.maxDelta,maxDelta);result.cases.push({channels,maxDelta});
  }
  result.compatible=result.channels===0;return result;
}
