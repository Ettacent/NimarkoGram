/* Modifications Copyright (C) 2026 Ettacent */

import {reject} from './gander-core.mjs';
export const DEFAULT_INDEXED_COLORS=Object.freeze([
 '000000','FFFFFF','FF0000','00FF00','0000FF','FFFF00','FF00FF','00FFFF',
 '000000','FFFFFF','FF0000','00FF00','0000FF','FFFF00','FF00FF','00FFFF',
 '800000','008000','000080','808000','800080','008080','C0C0C0','808080',
 '9999FF','993366','FFFFCC','CCFFFF','660066','FF8080','0066CC','CCCCFF',
 '000080','FF00FF','FFFF00','00FFFF','800080','800000','008080','0000FF',
 '00CCFF','CCFFFF','CCFFCC','FFFF99','99CCFF','FF99CC','CC99FF','FFCC99',
 '3366FF','33CCCC','99CC00','FFCC00','FF9900','FF6600','666699','969696',
 '003366','339966','003300','333300','993300','993366','333399','333333'
]);
const bad=reason=>reject(3,reason);
export function numeric(value,min,max){if(value===null)return; if(!/^-?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(value)||!Number.isFinite(+value)||+value<min||+value>max)bad('invalid-style-number');}
function entities(s){return s.replace(/&([^;]*);/g,(_,v)=>{const known={amp:'&',lt:'<',gt:'>',quot:'"',apos:"'"};if(v in known)return known[v];let n;if(/^#\d+$/.test(v))n=+v.slice(1);else if(/^#x[\da-f]+$/i.test(v))n=parseInt(v.slice(2),16);else bad('xml-entity');if(!Number.isInteger(n)||n<32&&![9,10,13].includes(n)||n>0x10ffff||n>=0xd800&&n<=0xdfff)bad('xml-codepoint');return String.fromCodePoint(n);});}
export function scanXml(text,visit,visitText){
 if(typeof text!=='string'||text.length>24*1024*1024)reject(2,'xml-size');
 const stack=[];let p=0,nodes=0,root=0;
 while(p<text.length){const i=text.indexOf('<',p);const tail=text.slice(p,i<0?text.length:i);if(tail.includes('&')&&/&(?!(?:amp|lt|gt|quot|apos|#\d+|#x[\da-f]+);)/i.test(tail))bad('xml-entity');if(!stack.length&&tail.trim())bad('xml-root-text');if(/[^\t\n\r ]/.test(tail))visitText?.();if(i<0){p=text.length;break;}
  if(text.startsWith('<!--',i)){const end=text.indexOf('-->',i+4);if(end<0||text.slice(i+4,end).includes('--'))bad('xml-comment');p=end+3;continue;}
  if(text.startsWith('<?',i)){const end=text.indexOf('?>',i+2);if(end<0)bad('xml-declaration');p=end+2;continue;}
  if(text.startsWith('<!',i))bad('xml-declaration-unsupported');
  let end=i+1,quote=null;for(;end<text.length;end++){const ch=text[end];if(quote){if(ch===quote)quote=null;}else if(ch==='"'||ch==="'")quote=ch;else if(ch==='>')break;else if(ch==='<')bad('xml-tag');}
  if(end===text.length)bad('xml-unclosed');
  const raw=text.slice(i+1,end),close=raw[0]==='/',self=/\/$/.test(raw),m=/^\/?([A-Za-z_][\w.:-]*)/.exec(raw);if(!m)bad('xml-name');const qname=m[1],name=qname.split(':').pop();
  if(close){if(raw!==('/'+qname)||stack.pop()!==qname)bad('xml-nesting');visit({name,close:true,depth:stack.length});}
  else {if(++nodes>1500000||stack.length>=64)reject(2,'xml-complexity');if(!stack.length&&++root!==1)bad('xml-multiple-roots');
   const attrs=Object.create(null),rest=raw.slice(m[0].length,self?-1:undefined);let k=0,count=0;
   while(k<rest.length){if(!rest.slice(k).trim())break;const a=/\s+([A-Za-z_][\w.:-]*)\s*=\s*("[^"<]*"|'[^'<]*')/y;a.lastIndex=k;const am=a.exec(rest);if(!am||++count>64||Object.hasOwn(attrs,am[1])||am[2].length>4096)bad('xml-attribute');attrs[am[1]]=entities(am[2].slice(1,-1));k=a.lastIndex;}
   const node={name,qname,attrs,close:false,depth:stack.length,getAttribute:key=>Object.hasOwn(attrs,key)?attrs[key]:null};visit(node);if(!self)stack.push(qname);else visit({name,close:true,depth:stack.length});
  }p=end+1;
 }if(stack.length||root!==1)bad('xml-incomplete');
}
export function parseTree(text){if(text.length>2*1024*1024)reject(2,'style-xml-size');let root;const stack=[];let count=0;scanXml(text,node=>{if(node.close){stack.pop();return;}if(++count>50000)reject(2,'style-node-limit');node.localName=node.name;node.children=[];if(stack.length)stack.at(-1).children.push(node);else root=node;stack.push(node);},()=>{if(stack.length)stack.at(-1).hasText=true;});return {documentElement:root,getElementsByTagNameNS:(_,name)=>{const out=[];function walk(n){if(n.localName===name)out.push(n);for(const c of n.children)walk(c);}walk(root);return out;}};}
export function enabled(node){return !!node&&!['0','false','none'].includes(node.getAttribute('val'));}
export function validateColor(node){if(!node)return;const rgb=node.getAttribute('rgb');if(rgb!==null&&!/^(?:[\da-f]{6}|[\da-f]{8})$/i.test(rgb))bad('unsafe-color');numeric(node.getAttribute('tint'),-1,1);numeric(node.getAttribute('theme'),0,11);numeric(node.getAttribute('indexed'),0,65);for(const n of ['theme','indexed']){const v=node.getAttribute(n);if(v!==null&&!/^\d+$/.test(v))bad('invalid-color-index');}}
function namespaces(node,parent){const scope=Object.create(parent||null);for(const [key,value] of Object.entries(node.attrs)){if(key==='xmlns')scope['']=value;else if(key.startsWith('xmlns:'))scope[key.slice(6)]=value;}return scope;}
function namespaceOf(node,scope){if(!/^[A-Za-z_][\w.-]*(?::[A-Za-z_][\w.-]*)?$/.test(node.qname))bad('xml-name');const parts=node.qname.split(':');if(parts.length>1&&['xml','xmlns'].includes(parts[0]))bad('xml-name');return scope[parts.length===1?'':parts[0]];}
function onlyAttrs(node,allowed){return Object.keys(node.attrs).every(key=>key==='xmlns'||key.startsWith('xmlns:')||allowed.includes(key));}
function validateDefaultStyleExtensions(node,scope){
 const main='http://schemas.openxmlformats.org/spreadsheetml/2006/main';
 if(namespaceOf(node,scope)!==main||node.hasText||!onlyAttrs(node,[]))bad('unsupported-style-extension');
 const defaults={
  '{EB79DEF2-80B8-43E5-95BD-54CBDDF9020C}':['slicerStyles','http://schemas.microsoft.com/office/spreadsheetml/2009/9/main','defaultSlicerStyle'],
  '{9260A510-F301-46A8-8635-F512D64BE5F5}':['timelineStyles','http://schemas.microsoft.com/office/spreadsheetml/2010/11/main','defaultTimelineStyle']
 },seen=new Set();
 for(const ext of node.children){
  const extScope=namespaces(ext,scope),uri=ext.attrs.uri?.toUpperCase(),spec=defaults[uri];
  if(ext.name!=='ext'||namespaceOf(ext,extScope)!==main||!spec||seen.has(uri)||ext.hasText||!onlyAttrs(ext,['uri'])||ext.children.length!==1)bad('unsupported-style-extension');
  seen.add(uri);const child=ext.children[0],childScope=namespaces(child,extScope),[name,ns,attr]=spec,value=child.attrs[attr];
  if(child.name!==name||namespaceOf(child,childScope)!==ns||child.children.length||child.hasText||!onlyAttrs(child,[attr])||!value||value.length>255)bad('unsupported-style-extension');
 }
}
export function validateStyleTree(root){
 let hasStyleExtensions=false,hasIndexedPalette=false;
 const allowed=new Set(['styleSheet','numFmts','numFmt','fonts','font','b','i','u','strike','sz','color','name','family','scheme','charset','vertAlign','fills','fill','patternFill','fgColor','bgColor','borders','border','left','right','top','bottom','diagonal','color','cellXfs','xf','alignment','protection','cellStyleXfs','cellStyles','cellStyle','dxfs','dxf','tableStyles','tableStyle','extLst']);
 function walk(n,parent,parentScope){const scope=namespaces(n,parentScope);
  if(n.name==='colors'){
   const main='http://schemas.openxmlformats.org/spreadsheetml/2006/main';
   if(hasIndexedPalette||parent!==root||root.name!=='styleSheet'||namespaceOf(root,namespaces(root))!==main
     ||namespaceOf(n,scope)!==main||n.hasText||!onlyAttrs(n,[])||n.children.length!==1)bad('unsupported-indexed-palette');
   hasIndexedPalette=true;
   const palette=n.children[0],paletteScope=namespaces(palette,scope);
   if(palette.name!=='indexedColors'||namespaceOf(palette,paletteScope)!==main||palette.hasText
     ||!onlyAttrs(palette,[])||palette.children.length!==DEFAULT_INDEXED_COLORS.length)bad('unsupported-indexed-palette');
   for(const [index,color] of palette.children.entries()){
    const rgb=color.getAttribute('rgb');
    if(color.name!=='rgbColor'||namespaceOf(color,namespaces(color,paletteScope))!==main||color.children.length
      ||color.hasText||!onlyAttrs(color,['rgb'])||!/^00[\da-f]{6}$/i.test(rgb||'')
      ||rgb.slice(2).toUpperCase()!==DEFAULT_INDEXED_COLORS[index])bad('unsupported-indexed-palette');
   }
   return;
  }
  if(!allowed.has(n.name))bad('unsupported-style-element');
  if(n.name==='extLst'){
   if(parent!==root||root.name!=='styleSheet'||namespaceOf(root,namespaces(root))!=='http://schemas.openxmlformats.org/spreadsheetml/2006/main'||hasStyleExtensions)bad('unsupported-style-extension');
   hasStyleExtensions=true;validateDefaultStyleExtensions(n,scope);return;
  }
  if(n.name==='dxf'||n.name==='tableStyle')bad('unsupported-style-extension');
  if(n.name==='font'&&parent.name!=='fonts')bad('style-font-context');
  if(n.name==='name'&&!/^[\p{L}\p{N} ._-]{1,96}$/u.test(n.getAttribute('val')||''))bad('unsafe-font');
  if(n.name==='sz')numeric(n.getAttribute('val'),1,409);
  if(n.name==='patternFill'&&!['none','solid','gray125',null].includes(n.getAttribute('patternType')))bad('unsupported-pattern-fill');
  if(n.name==='border'&&['diagonalUp','diagonalDown'].some(k=>['1','true'].includes(n.getAttribute(k))))bad('unsupported-diagonal-border');
  if(n.name==='alignment'){const a=n.attrs;if(a.horizontal&&!['general','left','center','right','justify','centerContinuous','distributed','fill'].includes(a.horizontal))bad('unsupported-alignment');if(a.vertical&&!['top','center','bottom','justify','distributed'].includes(a.vertical))bad('unsupported-alignment');numeric(n.getAttribute('indent'),0,250);if(a.textRotation&&+a.textRotation!==0)bad('unsupported-text-rotation');if(a.shrinkToFit&&a.shrinkToFit!=='0')bad('unsupported-shrink-to-fit');}
  if(n.name==='xf'){for(const k of ['fontId','fillId','borderId','numFmtId','xfId']){numeric(n.getAttribute(k),0,65535);if(n.attrs[k]&&!/^\d+$/.test(n.attrs[k]))bad('style-index');}for(const [apply,id] of [['applyFont','fontId'],['applyFill','fillId'],['applyBorder','borderId']])if(['0','false'].includes(n.attrs[apply])&&+(n.attrs[id]||0)!==0)bad('unsupported-disabled-style');}
  if(['color','fgColor','bgColor'].includes(n.name))validateColor(n);
  for(const c of n.children)walk(c,n,scope);
 }walk(root,{name:''});
 const counts=Object.fromEntries(['fonts','fills','borders'].map(name=>[name,root.children.find(n=>n.name===name)?.children.length||0]));
 const bases=root.children.find(n=>n.name==='cellStyleXfs')?.children.length||0;for(const xf of root.children.find(n=>n.name==='cellXfs')?.children||[])if(xf.attrs.xfId!==undefined&&+xf.attrs.xfId>=bases)bad('missing-base-style');
 for(const group of root.children.filter(n=>['cellXfs','cellStyleXfs'].includes(n.name)))for(const xf of group.children)for(const [key,groupName] of [['fontId','fonts'],['fillId','fills'],['borderId','borders']])if(xf.attrs[key]!==undefined&&+xf.attrs[key]>=counts[groupName])bad('missing-style-component');
 const base=root.children.find(n=>n.name==='cellStyleXfs'),cellXfs=root.children.find(n=>n.name==='cellXfs');
 if(base)for(const [index,xf] of base.children.entries()){
  if(['fillId','borderId','numFmtId'].some(k=>+(xf.attrs[k]||0)!==0)||xf.children.length)bad('unsupported-base-style-inheritance');
  if(+(xf.attrs.fontId||0)!==0)for(const child of cellXfs?.children||[])if(+(child.attrs.xfId||0)===index&&(!['1','true'].includes(child.attrs.applyFont)||child.attrs.fontId===undefined))bad('unsupported-base-style-inheritance');
 }
}
