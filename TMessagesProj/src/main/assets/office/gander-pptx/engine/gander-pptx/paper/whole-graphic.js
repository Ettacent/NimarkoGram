/* Modifications Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent. */
'use strict';
function artGraphicEvidence(d,w,h){
 const n=w*h,no={bounded:false};if(!Number.isInteger(w)||!Number.isInteger(h)||w<8||h<8||w>2048||h>2048||n>350000||d.length!==n*4)return no;
 const bins=new Uint32Array(4096),fine=new Uint32Array(64),grayTones=new Uint32Array(256),whiteTones=new Uint32Array(256);let border=0,whiteBorder=0,paper=0,foreground=0,accent=0,flat=0,gray=0,smooth=0,residual=0;
 for(let p=0;p<n;p++){const o=p*4;if(d[o+3]!==255)return {bounded:true,opaque:false};const r=d[o],g=d[o+1],b=d[o+2],hi=Math.max(r,g,b),lo=Math.min(r,g,b),white=lo>=248&&hi-lo<=10,x=p%w,y=Math.floor(p/w);
  if(!x||!y||x===w-1||y===h-1){border++;if(white){whiteBorder++;if(hi-lo<=3)whiteTones[lo]++;}}if(white){paper++;continue;}foreground++;
  if(hi-lo>80&&hi>=180){accent++;bins[(r>>4)*256+(g>>4)*16+(b>>4)]++;if(x&&y&&x<w-1&&y<h-1){let delta=0;for(let j=0;j<4;j++){const q=o+(j===0?-4:j===1?4:j===2?-w*4:w*4);for(let k=0;k<3;k++)delta=Math.max(delta,Math.abs(d[o+k]-d[q+k]));}if(delta<=8)flat++;}}
  if(hi-lo<=16&&lo>=48&&hi<=240&&x&&y&&x<w-1&&y<h-1){let eligible=true,sum=0;for(let j=0;j<4;j++){const q=o+(j===0?-4:j===1?4:j===2?-w*4:w*4),high=Math.max(d[q],d[q+1],d[q+2]),low=Math.min(d[q],d[q+1],d[q+2]);if(high-low>24||Math.abs((high+low-hi-lo)/2)>16){eligible=false;break;}sum+=(d[q]+d[q+1]+d[q+2])/3;}
   if(eligible){gray++;grayTones[Math.round((r+g+b)/3)]++;const error=Math.abs((r+g+b)/3-sum/4);residual+=error;if(error<=2)smooth++;}
  }
 }
 let top=0;for(let i=1;i<bins.length;i++)if(bins[i]>bins[top])top=i;
 for(let o=0;o<d.length;o+=4){const r=d[o],g=d[o+1],b=d[o+2];if(Math.max(r,g,b)-Math.min(r,g,b)>80&&Math.max(r,g,b)>=180&&((r>>4)*256+(g>>4)*16+(b>>4))===top)fine[((r>>2)&3)*16+((g>>2)&3)*4+((b>>2)&3)]++;}
 const ordered=Array.from(bins).sort((a,b)=>b-a),peak=Math.max(...fine);
 function quantile(f){let count=0;for(let i=0;i<256;i++){count+=grayTones[i];if(count>=Math.max(1,gray*f))return i;}return 255;}
 let whitePoint=255;for(let i=248;i<256;i++)if(whiteTones[i]>whiteTones[whitePoint])whitePoint=i;
 return {bounded:true,opaque:true,pixels:n,whitePoint,whitePointSupport:whiteTones[whitePoint]/Math.max(1,border),whiteBorder:whiteBorder/Math.max(1,border),paperFraction:paper/n,foregroundFraction:foreground/n,accentFraction:accent/Math.max(1,foreground),flatAccent:flat/Math.max(1,accent),coarse4Accent:(ordered[0]+ordered[1]+ordered[2]+ordered[3])/Math.max(1,accent),fineAccentPeak:peak/Math.max(1,accent),grayFraction:gray/Math.max(1,foreground),smoothGray:smooth/Math.max(1,gray),grayResidual:residual/Math.max(1,gray),grayRange:quantile(.95)-quantile(.05)};
}

function artAdmitGraphic(e){return !!(e.bounded&&e.opaque&&e.whitePoint>=252&&e.whiteBorder>=.85&&e.whitePointSupport>=.80&&e.paperFraction>=.20&&e.foregroundFraction>=.03&&e.foregroundFraction<=.85&&e.accentFraction>=.25&&e.flatAccent>=.50&&e.coarse4Accent>=.75&&e.fineAccentPeak>=.20&&e.grayFraction>=.15&&e.smoothGray>=.85&&e.grayResidual<=1.25&&e.grayRange>=48);}

'use strict';
function artPreparePalette(d,w,h,whitePoint=255){
  const n=w*h;if(!Number.isInteger(w)||!Number.isInteger(h)||w<8||h<8||w>2048||h>2048||n>350000||d.length!==n*4)return false;
  if(!Number.isInteger(whitePoint)||whitePoint<252||whitePoint>255)return false;
  for(let o=3;o<d.length;o+=4)if(d[o]!==255)return false;
  const scale=255/whitePoint;
  for(let o=0;o<d.length;o+=4){
    const r=Math.min(255,d[o]*scale),g=Math.min(255,d[o+1]*scale),b=Math.min(255,d[o+2]*scale);
    const min=Math.min(r,g,b),max=Math.max(r,g,b),a=255-min,k=255-max;
    if(!a){d[o]=d[o+1]=d[o+2]=255;d[o+3]=0;continue;}
    d[o]=Math.round(255*(r-min+k)/a);d[o+1]=Math.round(255*(g-min+k)/a);d[o+2]=Math.round(255*(b-min+k)/a);
    d[o+3]=Math.round(a);
  }return true;
}
function artComposePalette(d,paper){
  if(!Array.isArray(paper)||paper.length!==3||!paper.every(c=>Number.isInteger(c)&&c>=0&&c<=255))throw Error('paper');
  const out=d.slice();for(let o=0;o<out.length;o+=4){const a=out[o+3]/255;for(let c=0;c<3;c++)out[o+c]=Math.round(out[o+c]*a+paper[c]*(1-a));out[o+3]=255;}return out;
}

'use strict';
function artGraphicContrast(d,w,h,e){
 if(!artAdmitGraphic(e)||d.length!==w*h*4)return {safe:false,reason:'evidence'};
 const weights=[.2126,.7152,.0722],linear=new Float64Array(256);
 for(let i=0;i<256;i++){const x=i/255;linear[i]=x<=.04045?x/12.92:Math.pow((x+.055)/1.055,2.4);}
 function luminance(r,g,b){function channel(x){x/=255;return x<=.04045?x/12.92:Math.pow((x+.055)/1.055,2.4);}return weights[0]*channel(r)+weights[1]*channel(g)+weights[2]*channel(b);}
 const maxPaper=weights.map(weight=>{const x=Math.min(1,.04/weight),s=x<=.0031308?12.92*x:1.055*Math.pow(x,1/2.4)-.055;return Math.min(255,Math.ceil(255*s));});
 function stats(size){const lo=[new Float64Array(size),new Float64Array(size),new Float64Array(size)],hi=[new Float64Array(size),new Float64Array(size),new Float64Array(size)],oldLo=new Float64Array(size),oldHi=new Float64Array(size);for(const a of lo)a.fill(Infinity);oldLo.fill(Infinity);return{lo,hi,oldLo,oldHi,whiteHi:new Float64Array(size),count:new Uint32Array(size),flat:new Uint32Array(size)};}
 const colours=stats(4096),neutral=stats(256),scale=255/e.whitePoint;let darkChromatic=0;
 function add(s,p,r,g,b,old){const nr=Math.min(255,r*scale),ng=Math.min(255,g*scale),nb=Math.min(255,b*scale),white=Math.min(nr,ng,nb),black=255-Math.max(nr,ng,nb),a=nr-white+black,z=ng-white+black,v=nb-white+black;s.count[p]++;s.oldLo[p]=Math.min(s.oldLo[p],old);s.oldHi[p]=Math.max(s.oldHi[p],old);s.whiteHi[p]=Math.max(s.whiteHi[p],white);s.lo[0][p]=Math.min(s.lo[0][p],a);s.lo[1][p]=Math.min(s.lo[1][p],z);s.lo[2][p]=Math.min(s.lo[2][p],v);s.hi[0][p]=Math.max(s.hi[0][p],a);s.hi[1][p]=Math.max(s.hi[1][p],z);s.hi[2][p]=Math.max(s.hi[2][p],v);}
 for(let o=0;o<d.length;o+=4){const r=d[o],g=d[o+1],b=d[o+2],chroma=Math.max(r,g,b)-Math.min(r,g,b),old=weights[0]*linear[r]+weights[1]*linear[g]+weights[2]*linear[b];
  if(chroma<=16)add(neutral,Math.round((r+g+b)/3),r,g,b,old);
  else{
   if(old<=.04)darkChromatic++;
   const p=(r>>4)*256+(g>>4)*16+(b>>4);add(colours,p,r,g,b,old);const pixel=o/4,x=pixel%w,y=Math.floor(pixel/w);
   if(x&&y&&x<w-1&&y<h-1){let delta=0;for(let j=0;j<4;j++){const q=o+(j===0?-4:j===1?4:j===2?-w*4:w*4);for(let k=0;k<3;k++)delta=Math.max(delta,Math.abs(d[o+k]-d[q+k]));}if(delta<=8)colours.flat[p]++;}
  }
 }
 function interval(s,p){const lo=[],hi=[];for(let k=0;k<3;k++){lo[k]=Math.max(0,s.lo[k][p]-3);hi[k]=Math.min(255,s.hi[k][p]+s.whiteHi[p]*maxPaper[k]/255+3);}return[Math.max(0,luminance(lo[0],lo[1],lo[2])-1e-12),Math.min(1,luminance(hi[0],hi[1],hi[2])+1e-12)];}
 const inks=[];for(let v=0;v<256;v++)if(neutral.count[v]>=4)inks.push({oldLo:neutral.oldLo[v],next:interval(neutral,v)});
 let chromaticInkRoles=0;
 const darkDetailMax=1.05/4.5-.05;
 for(let p=0;p<4096;p++)if(colours.count[p]>=4&&colours.oldLo[p]<=darkDetailMax+1e-12){const next=interval(colours,p);chromaticInkRoles++;inks.push({oldLo:colours.oldLo[p],next});}
 let checked=0,pairAttempts=0,minBound=100;const supportMinimum=Math.max(16,Math.ceil(w*h*.001));
 for(let p=0;p<4096;p++)if(colours.flat[p]>=supportMinimum){const next=interval(colours,p);
  for(const ink of inks){if(++pairAttempts>1048576)return{safe:false,reason:'contrast-cost'};if(ink.oldLo>=colours.oldHi[p]||(colours.oldHi[p]+.05)/(ink.oldLo+.05)<4.5-1e-10)continue;checked++;let bound=0;
   if(next[1]<ink.next[0])bound=(ink.next[0]+.05)/(next[1]+.05);else if(ink.next[1]<next[0])bound=(next[0]+.05)/(ink.next[1]+.05);
   minBound=Math.min(minBound,bound);if(bound<4.5+1e-10)return{safe:false,reason:'colour-detail-interval',checked,lowerContrastBound:bound,intervalsOverlap:bound===0,maxPaper,byteAllowance:3};
  }
 }
 return{safe:true,checked,pairAttempts,minLowerContrastBound:checked?minBound:null,maxPaper,byteAllowance:3,darkChromaticPixels:darkChromatic,chromaticInkRoles,domain:'all RGB with linear Y<=.04 (conservative independent-channel overbound)'};
}
