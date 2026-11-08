/* Modifications Copyright (C) 2026 Ettacent */

/* Gander XLSX adaptation, MIT, Copyright (c) 2026 Arjun Maniyani.
 * Local modifications Copyright (C) 2026 Ettacent. See GANDER_LICENSE.txt.
 */
'use strict';
let workbook, model, searchGeneration=0, api;
const mergeRows=new WeakMap();
const core=import('./gander-core.mjs');
const stylesModule=import('./xlsx-style-layer.mjs');
const cacheBudget=import('./sheet-cache-budget.mjs');
let styling;
const yieldTask=()=>new Promise(resolve=>setTimeout(resolve,0));
function visibleCell(ws,r,c) { return !ws['!rows']?.[r]?.hidden&&!ws['!cols']?.[c]?.hidden; }
function covered(ws,r,c) {
    let rows=mergeRows.get(ws);
    if(!rows){rows=new Map();for(const m of ws['!merges']||[])for(let row=m.s.r;row<=m.e.r;row++){
        if(!rows.has(row))rows.set(row,[]);rows.get(row).push(m);
    }mergeRows.set(ws,rows);}
    return (rows.get(r)||[]).some(m=>c>=m.s.c&&c<=m.e.c&&(r!==m.s.r||c!==m.s.c));
}
function displayText(ws,r,c) { const cell=api.cellAt(ws,r,c); return cell?String(XLSX.utils.format_cell(cell)):''; }

function cellMatches(text,foldedQuery,sensitive,limit){
    const folded=api.foldText(text,sensitive),parts=[],offsets=[];
    for(let i=0;i<folded.text.length;){
        const start=i;let end=i+1;
        if(/\s/u.test(folded.text[i])){while(end<folded.text.length&&/\s/u.test(folded.text[end]))end++;parts.push(' ');}
        else parts.push(folded.text[i]);
        offsets.push({start:folded.map?folded.map[start].start:start,end:folded.map?folded.map[end-1].end:end});i=end;
    }
    const normalized=parts.join(''),hits=[];
    for(let at=normalized.indexOf(foldedQuery);at!==-1&&hits.length<limit;at=normalized.indexOf(foldedQuery,at+foldedQuery.length)){
        hits.push({start:offsets[at].start,end:offsets[at+foldedQuery.length-1].end});
    }
    return hits;
}

function rowsHtml(sheet,k) {
    const ws=workbook.Sheets[sheet.name], piece=sheet.pieces[k];
    if(!piece||piece.e<piece.s) return '';
    const safe={'!ref':XLSX.utils.encode_range({s:{r:piece.s,c:sheet.range.s.c},e:{r:piece.e,c:sheet.range.e.c}}),
        '!merges':ws['!merges']||[]};
    for(let r=piece.s;r<=piece.e;r++) for(let c=sheet.range.s.c;c<=sheet.range.e.c;c++) {
        const cell=api.cellAt(ws,r,c); if(!cell) continue;
        const text=displayText(ws,r,c);
        safe[api.encodeCell(r,c)]={t:'s',v:text,w:text};
    }
    return XLSX.utils.sheet_to_html(safe,{editable:false,header:'',footer:'',id:'gander-cell'})
        .replace(/^<table[^>]*>/,'').replace(/<\/table>$/,'');
}

self.onmessage=async ({data})=>{
    const id=data.id;
    try {
        api=await core;
        if(data.type==='parse') {
            if(!(data.bytes instanceof ArrayBuffer)) api.reject(1,'worker-message');
            const archive=api.inspectArchive(data.bytes);
            if(archive.unsupported.length) api.reject(3,'unsupported-workbook-features');
            if(!self.XLSX) importScripts('./gander-vendor/xlsx.full.min.js');
            if(!self.XLSX?.read||!self.XLSX?.utils?.sheet_to_html) api.reject(3,'sheetjs-unavailable');
            if(!self.JSZip) importScripts('./gander-vendor/jszip3.min.js');
            const layer=await stylesModule;
            styling=await layer.readStyles(data.bytes,JSZip);
            workbook=XLSX.read(new Uint8Array(data.bytes),{type:'array',dense:true,cellDates:false,
                cellHTML:false,cellStyles:true,cellNF:true,sheetStubs:true,bookVBA:false,sheetRows:0});
            model=api.admitWorkbook(workbook);
            layer.admitStyles(styling,workbook,model);
            const {CACHE,pieceCells}=await cacheBudget;
            for(const sheet of model.sheets)for(const piece of sheet.pieces){
                let units=0,breaks=0;
                if(!sheet.hidden&&sheet.range){const ws=workbook.Sheets[sheet.name];
                    for(let r=piece.s;r<=piece.e;r++)for(let c=sheet.range.s.c;c<=sheet.range.e.c;c++){
                        const text=displayText(ws,r,c);units+=text.length;for(let i=0;i<text.length;i++)if(text.charCodeAt(i)===10)breaks++;
                    }
                }
                piece.textUnits=units;piece.htmlBound=pieceCells(sheet,piece)*256+units*20+sheet.pieces.length*16;
                if(!sheet.hidden&&(units>CACHE.pieceText||breaks>CACHE.pieceBreaks||piece.htmlBound>CACHE.pieceHTML))api.reject(3,'native-piece-dom-budget');
            }
            for(const sheet of model.sheets){
                const ws=workbook.Sheets[sheet.name];
                ws['!rows']=sheet.rows;ws['!cols']=sheet.cols;
            }
            self.postMessage({id,type:'parsed',model:{...model,sheets:model.sheets.map(sheet=>{
                const ws=workbook.Sheets[sheet.name];
                return {...sheet,rows:ws['!rows']||[],cols:ws['!cols']||[],merges:ws['!merges']||[],gridlines:styling.sheets[sheet.name].gridlines,defaultHeight:styling.sheets[sheet.name].defaultHeight};
            })},archive,version:XLSX.version});
        } else if(data.type==='piece') {
            const sheet=model?.sheets[data.sheet];
            if(!sheet||sheet.hidden||!sheet.pieces[data.piece]) api.reject(1,'piece-request');
            const layer=await stylesModule;
            self.postMessage({id,type:'piece',html:rowsHtml(sheet,data.piece),styles:layer.pieceStyles(styling,workbook,sheet,data.piece)});
        } else if(data.type==='cancelFind') {
            ++searchGeneration;
        } else if(data.type==='find') {
            const generation=++searchGeneration, matches=[];
            if(typeof data.query!=='string'||data.query.length>4096) api.reject(1,'query-limit');
            const normalizedQuery=data.query.replace(/\s+/g,' ').trim();
            if(!normalizedQuery){self.postMessage({id,type:'find',matches:[]});return;}
            const foldedQuery=api.foldText(normalizedQuery,!!data.caseSensitive).text;
            let work=0;
            outer: for(const sheet of model.sheets) {
                if(sheet.hidden||!sheet.range) continue;
                const ws=workbook.Sheets[sheet.name];
                for(let r=sheet.range.s.r;r<=sheet.range.e.r;r++) for(let c=sheet.range.s.c;c<=sheet.range.e.c;c++) {
                    if(++work%64===0) { await yieldTask(); if(generation!==searchGeneration) { self.postMessage({id,type:'cancelled'}); return; } }
                    if(!visibleCell(ws,r,c)||covered(ws,r,c)) continue;
                    for(const hit of cellMatches(displayText(ws,r,c),foldedQuery,!!data.caseSensitive,1001-matches.length)) {
                        matches.push({location:{sheet:sheet.index,ref:api.encodeCell(r,c)},...hit});
                        if(matches.length===1001) break outer;
                    }
                }
            }
            if(generation!==searchGeneration) {self.postMessage({id,type:'cancelled'}); return;}
            self.postMessage({id,type:'find',matches});
        } else api.reject(1,'worker-command');
    } catch(error) { self.postMessage({id,type:'error',officeCode:error.officeCode||3,
        reason:error.officeCode?error.message:'sheetjs-load-or-parse'}); }
};
