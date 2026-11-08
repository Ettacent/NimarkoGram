/* Modifications Copyright (C) 2026 Ettacent */

import {reject,decodeCell,encodeCell,cellAt,LIMITS} from './gander-core.mjs';
import {scanXml,parseTree,numeric} from './xlsx-style-guards.mjs';
import {parseTheme,parseStyles,cellCss} from './offline-xlsx-style-donor.mjs';
import {tokenize} from './xlsx-style-tokens.mjs';
const unsupported=reason=>reject(3,reason);
function int(v,max){if(v===null||!/^\d+$/.test(v)||+v>max)unsupported('style-index');return +v;}
export async function readStyles(bytes,JSZip){
 const zip=await JSZip.loadAsync(bytes),file=async path=>{const entry=zip.file(path);if(!entry)unsupported('style-part-missing');return entry.async('string');};
 const themeXML=zip.file('xl/theme/theme1.xml');if(themeXML){const text=await themeXML.async('string');const tree=parseTree(text);for(const n of tree.getElementsByTagNameNS('*','srgbClr'))if(!/^[\da-f]{6}$/i.test(n.attrs.val||''))unsupported('unsafe-theme-color');for(const n of tree.getElementsByTagNameNS('*','sysClr'))if(!/^[\da-f]{6}$/i.test(n.attrs.lastClr||''))unsupported('unsafe-theme-color');}
 const theme=await parseTheme(zip),styles=await parseStyles(zip,theme);if(styles.xfs.length>4096)reject(2,'style-count');
 const defs=styles.xfs.map(xf=>({text:tokenize(cellCss(xf,false,true)),number:tokenize(cellCss(xf,true,true)),noGridText:tokenize(cellCss(xf,false,false)),noGridNumber:tokenize(cellCss(xf,true,false))}));
 const rels=Object.create(null);scanXml(await file('xl/_rels/workbook.xml.rels'),n=>{if(n.close||n.name!=='Relationship')return;if(n.attrs.TargetMode==='External')return;const target=n.attrs.Target;if(!target||/[\\:%?#]|(?:^|\/)\.\.(?:\/|$)/.test(target))unsupported('sheet-path');const path=target.startsWith('/')?target.slice(1):target.startsWith('xl/')?target:'xl/'+target;if(rels[n.attrs.Id])unsupported('duplicate-sheet-relationship');rels[n.attrs.Id]=path;});
 const sheets=Object.create(null),jobs=[];scanXml(await file('xl/workbook.xml'),n=>{if(n.close||n.name!=='sheet')return;const name=n.attrs.name,rid=n.attrs['r:id']||Object.entries(n.attrs).find(([key])=>key.endsWith(':id'))?.[1],path=rels[rid];if(!name||!path||Object.hasOwn(sheets,name))unsupported('style-sheet-mapping');if(jobs.length>=256)reject(2,'style-sheet-count');const layout={styleAt:Object.create(null),rows:Object.create(null),cols:Object.create(null),widthOnlyCols:Object.create(null),gridlines:true,defaultHeight:20};sheets[name]=layout;jobs.push({path,layout});});
 for(const {path,layout} of jobs){let row=null,cellCount=0,metadata=0,expandedCols=0;const seenRows=new Set();const text=await file(path);scanXml(text,n=>{
  if(n.close){if(n.name==='row')row=null;return;}
  const a=n.attrs;
  if(['conditionalFormatting','drawing','legacyDrawing','tableParts','extLst','picture','oleObjects','controls'].includes(n.name))unsupported('unsupported-sheet-feature-'+n.name);
  if(n.name==='cfRule')unsupported('unsupported-sheet-feature-conditionalFormatting');
  if(n.name==='tablePart')unsupported('unsupported-sheet-feature-tableParts');
  if(Object.keys(a).some(key=>/dxfId$/i.test(key.split(':').pop())))unsupported('unsupported-sheet-feature-conditionalFormatting');
  if(n.name==='r')unsupported('unsupported-rich-text');
  if(n.name==='sheetFormatPr'&&a.defaultRowHeight){numeric(a.defaultRowHeight,1,409);layout.defaultHeight=+a.defaultRowHeight*4/3;}
  if(n.name==='sheetView'){layout.gridlines=a.showGridLines!=='0';if(a.rightToLeft==='1')unsupported('unsupported-right-to-left-sheet');}
  if(n.name==='pane'&&['frozen','frozenSplit'].includes(a.state))unsupported('unsupported-freeze-panes');
  if(n.name==='col'){const min=int(a.min,16384),max=int(a.max,16384);if(min<1||max<min)unsupported('column-range');expandedCols+=max-min+1;if(++metadata>16384||expandedCols>16384)reject(2,'style-metadata-limit');if(a.width!==undefined)numeric(a.width,0,255);
   const widthOnly=a.width!==undefined&&+a.width>0&&Object.keys(a).every(key=>['min','max','width','customWidth'].includes(key))&&(a.customWidth===undefined||['0','1','false','true'].includes(a.customWidth));
   for(let c=min-1;c<max;c++){if(a.style)layout.cols[c]=int(a.style,4095);layout.widthOnlyCols[c]=layout.widthOnlyCols[c]!==false&&widthOnly;}
  }
  if(n.name==='row'){row=int(a.r,100000)-1;if(row<0||seenRows.has(row))unsupported('row-reference');seenRows.add(row);if(++metadata>100000)reject(2,'style-metadata-limit');numeric(a.ht??null,0,409);if(a.s!==undefined&&!['1','true'].includes(a.customFormat))unsupported('unsupported-row-style-inheritance');layout.rows[row]={height:a.ht===undefined?null:+a.ht*4/3,hidden:['1','true'].includes(a.hidden),style:a.s===undefined?null:int(a.s,4095)};}
  if(n.name==='c'){if(++cellCount>LIMITS.cells)reject(2,'style-cell-limit');const point=decodeCell(a.r||'');if(point.r!==row)unsupported('style-row-contract');if(a.s!==undefined)layout.styleAt[a.r]=int(a.s,4095);}
 });}
 if(zip.file('xl/sharedStrings.xml'))scanXml(await file('xl/sharedStrings.xml'),n=>{if(!n.close&&n.name==='r')unsupported('unsupported-rich-text');});
 return {sheets,defs,theme};
}
export function admitStyles(styling,workbook,model){
 for(const sheet of model.sheets){const ws=workbook.Sheets[sheet.name],layout=styling.sheets[sheet.name];if(!layout)unsupported('style-sheet-missing');
  const inRange=(r,c)=>sheet.range&&r>=sheet.range.s.r&&r<=sheet.range.e.r&&c>=sheet.range.s.c&&c<=sheet.range.e.c;
  for(const [ref,index] of Object.entries(layout.styleAt)){const p=decodeCell(ref);if(!inRange(p.r,p.c))unsupported('styled-cell-outside-range');if(index>=styling.defs.length)unsupported('missing-cell-style');}
  for(const k of Object.keys(ws['!rows']||{})){if(!/^\d+$/.test(k))continue;if(!sheet.range||+k<sheet.range.s.r||+k>sheet.range.e.r)unsupported('row-metadata-outside-range');}
  for(const k of Object.keys(ws['!cols']||{})){if(!/^\d+$/.test(k))continue;if(!sheet.range||+k<sheet.range.s.c||+k>sheet.range.e.c){if(!sheet.range||+k<=sheet.range.e.c||!layout.widthOnlyCols[k])unsupported('column-metadata-outside-range');}}
  const rows=Object.create(null),cols=Object.create(null);
  for(const [r,meta] of Object.entries(layout.rows)){if(!sheet.range||+r<sheet.range.s.r||+r>sheet.range.e.r){if(meta.height!==null||meta.hidden||meta.style!==null)unsupported('row-layout-outside-range');continue;}if(meta.style!==null&&meta.style>=styling.defs.length)unsupported('missing-row-style');rows[r]={...(ws['!rows']?.[r]||{}),hpx:meta.height??layout.defaultHeight,hidden:meta.hidden};}
  if(sheet.range)for(let r=sheet.range.s.r;r<=sheet.range.e.r;r++)if(!rows[r])rows[r]={hpx:layout.defaultHeight};
  for(const [c,index] of Object.entries(layout.cols))if(index>=styling.defs.length||!sheet.range||+c<sheet.range.s.c||+c>sheet.range.e.c)unsupported('column-style-outside-range');
  for(const [c,meta] of Object.entries(ws['!cols']||{}))if(sheet.range&&+c>=sheet.range.s.c&&+c<=sheet.range.e.c)cols[c]=meta;
  ws['!rows']=rows;ws['!cols']=cols;sheet.rows=rows;sheet.cols=cols;
 }
}
export function pieceStyles(styling,workbook,sheet,k){
 const layout=styling.sheets[sheet.name],ws=workbook.Sheets[sheet.name],piece=sheet.pieces[k],out=Object.create(null);if(!sheet.range)return out;
 for(let r=piece.s;r<=piece.e;r++)for(let c=sheet.range.s.c;c<=sheet.range.e.c;c++){
  const ref=encodeCell(r,c),index=layout.styleAt[ref]??layout.rows[r]?.style??layout.cols[c]??0,cell=cellAt(ws,r,c),numericCell=cell?.t==='n'||cell?.t==='d';
  if(styling.defs.length&&!styling.defs[index])unsupported('missing-inherited-style');
  out[ref]=styling.defs[index]?.[layout.gridlines?(numericCell?'number':'text'):(numericCell?'noGridNumber':'noGridText')]||tokenize(cellCss(null,numericCell,layout.gridlines));
 }return out;
}
