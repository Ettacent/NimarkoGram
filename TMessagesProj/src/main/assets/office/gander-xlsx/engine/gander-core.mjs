/* Modifications Copyright (C) 2026 Ettacent */

/* Gander XLSX reader adaptation, MIT, Copyright (c) 2026 Arjun Maniyani.
 * Source: mokshablr/gander@4e60c1ea460d871012576e7e330e585efd7514ec
 * app/src/main/assets/viewer/xlsx.js. See GANDER_LICENSE.txt and provenance.json.
 * Local modifications Copyright (C) 2026 Ettacent.
 */
export const LIMITS = Object.freeze({bytes: 24*1024*1024, inflated: 96*1024*1024,
    entries: 4096, rows: 100000, cells: 250000, utf8: 16*1024*1024,
    pieceCells: 5000, pieceRowsMin: 20, pieceRowsMax: 500, pieceHardCells: 20000});
export function reject(code, reason) { throw Object.assign(new Error(reason), {officeCode: code}); }

export function inspectArchive(buffer) {
    const b = buffer instanceof Uint8Array ? buffer : new Uint8Array(buffer);
    if (b.byteLength > LIMITS.bytes) reject(2, 'compressed-limit');
    if (b[0] === 0xd0 && b[1] === 0xcf) reject(4, 'encrypted-ole');
    const d = new DataView(b.buffer, b.byteOffset, b.byteLength);
    const u16 = p => p >= 0 && p+2 <= b.length ? d.getUint16(p,true) : reject(1,'zip-bounds');
    const u32 = p => p >= 0 && p+4 <= b.length ? d.getUint32(p,true) : reject(1,'zip-bounds');
    let end = b.length-22;
    while (end >= Math.max(0,b.length-65557) && (u32(end)!==0x06054b50 || end+22+u16(end+20)!==b.length)) end--;
    if (end < Math.max(0,b.length-65557)) reject(1,'zip-end');
    const count = u16(end+10), size = u32(end+12), central = u32(end+16);
    if (count > LIMITS.entries) reject(2,'zip-entry-limit');
    if (!count || u16(end+4) || u16(end+6) || u16(end+8)!==count || central+size!==end) reject(1,'zip-directory');
    const names = new Set(), offsets = new Set(), features = new Set();
    let p = central, inflated = 0;
    for (let i=0; i<count; i++) {
        if (p+46 > end || u32(p)!==0x02014b50) reject(1,'zip-entry');
        const flags=u16(p+8), method=u16(p+10), n=u16(p+28), extra=u16(p+30), comment=u16(p+32), local=u32(p+42);
        if (flags & 0x41) reject(4,'encrypted-zip');
        if (!n || n>1024 || p+46+n+extra+comment>end || ![0,8].includes(method) || u16(p+34)) reject(1,'zip-entry-shape');
        let name;
        try { name = new TextDecoder('utf-8',{fatal:true}).decode(b.subarray(p+46,p+46+n)); }
        catch (_) { reject(1,'zip-name-encoding'); }
        if (/^[\/]|[\\:%?#\x00-\x1f\x7f]/.test(name) || name.split('/').some((part,j,a)=>part==='.'||part==='..'||!part&&j!==a.length-1)
                || names.has(name) || offsets.has(local)) reject(1,'zip-name');
        names.add(name); offsets.add(local);
        const length=u32(p+24), compressed=u32(p+20);
        inflated+=length;
        if (length>LIMITS.bytes || inflated>LIMITS.inflated) reject(2,'inflated-limit');
        if (local+30>central || u32(local)!==0x04034b50 || u16(local+6)!==flags || u16(local+8)!==method || u16(local+26)!==n
                || local+30+n+u16(local+28)+compressed>central) reject(1,'zip-local');
        for (let j=0;j<n;j++) if (b[local+30+j]!==b[p+46+j]) reject(1,'zip-name-mismatch');
        if (/^xl\/(drawings|charts|media|diagrams|pivotTables|pivotCache|externalLinks|embeddings|activeX|ctrlProps|slicers|threadedComments)\//i.test(name)
                || /^xl\/(vbaProject\.bin|comments[^/]*\.xml)$/i.test(name)) features.add(name.split('/')[1]);
        p+=46+n+extra+comment;
    }
    if (p!==end || !names.has('[Content_Types].xml') || !names.has('xl/workbook.xml')) reject(1,'xlsx-parts');
    return {entries:count, declaredInflatedBytes:inflated, unsupported:[...features].sort()};
}

export function cutSheet(ws, range) {
    if (!range) return [{s:0,e:-1}];
    const cols=range.e.c-range.s.c+1;
    const step=Math.max(LIMITS.pieceRowsMin,Math.min(LIMITS.pieceRowsMax,Math.floor(LIMITS.pieceCells/cols)));
    const tall=(ws['!merges']||[]).filter(m=>m.e.r>m.s.r).sort((a,b)=>a.s.r-b.s.r);
    const pieces=[];
    let next=0;
    for(let r=range.s.r;r<=range.e.r;) {
        let end=Math.min(r+step-1,range.e.r);
        while(next<tall.length&&tall[next].s.r<r) next++;
        for(let i=next;i<tall.length&&tall[i].s.r<=end;i++) if(tall[i].e.r>end) end=Math.min(tall[i].e.r,range.e.r);
        if ((end-r+1)*cols>LIMITS.pieceHardCells) reject(2,'merged-piece-limit');
        pieces.push({s:r,e:end}); r=end+1;
    }
    return pieces;
}

export function cellAt(ws,r,c) { return ws['!data'] ? ws['!data'][r]?.[c] : Array.isArray(ws) ? ws[r]?.[c] : ws[encodeCell(r,c)]; }
export function encodeCell(r,c) { let col=''; for(c++;c;c=Math.floor((c-1)/26)) col=String.fromCharCode(65+(c-1)%26)+col; return col+(r+1); }
export function decodeCell(ref) {
    const m=/^([A-Z]{1,3})([1-9][0-9]{0,6})$/.exec(ref); if(!m) reject(1,'cell-reference');
    let c=0; for(const ch of m[1]) c=c*26+ch.charCodeAt(0)-64;
    if(c>16384 || +m[2]>1048576) reject(1,'cell-reference');
    return {r:+m[2]-1,c:c-1};
}
export function decodeRange(ref) {
    const parts=ref.split(':'); if(parts.length>2) reject(1,'sheet-range');
    const s=decodeCell(parts[0]),e=decodeCell(parts[1]||parts[0]);
    if(e.r<s.r||e.c<s.c) reject(1,'sheet-range'); return {s,e};
}

export function normalizedMetadata(ws,range) {
    const rows=Object.create(null),cols=Object.create(null);
    if(!range)return {rows,cols};
    const copy=(source,start,end,target,fields)=>{
        for(let i=start;i<=end;i++){
            const value=source?.[i];if(!value||typeof value!=='object')continue;
            const item={};if(value.hidden===true)item.hidden=true;
            for(const field of fields){const n=value[field];if(Number.isFinite(n)&&n>=0&&n<=4096)item[field]=n;}
            if(Object.keys(item).length)target[i]=item;
        }
    };
    copy(ws['!rows'],range.s.r,range.e.r,rows,['hpx','hpt']);
    copy(ws['!cols'],range.s.c,range.e.c,cols,['wpx','wch','width']);
    return {rows,cols};
}

export function admitWorkbook(wb) {
    if (!wb || !Array.isArray(wb.SheetNames) || !wb.SheetNames.length || wb.SheetNames.length>256) reject(2,'sheet-count');
    let cells=0, utf8=0, names=0; const encoder=new TextEncoder(), seen=new Set();
    const sheets=wb.SheetNames.map((name,index)=>{
        if(typeof name!=='string'||name.length>256||seen.has(name)) reject(1,'sheet-name');
        seen.add(name); names+=name.length; if(names>65536) reject(2,'sheet-name-limit');
        const ws=wb.Sheets[name]; if(!ws) reject(1,'missing-sheet');
        const range=ws['!ref']?decodeRange(ws['!ref']):null;
        if(range && (range.e.r>=LIMITS.rows || (range.e.r-range.s.r+1)*(range.e.c-range.s.c+1)>LIMITS.cells)) reject(2,'sheet-range-limit');
        if(range) {
            cells+=(range.e.r-range.s.r+1)*(range.e.c-range.s.c+1); if(cells>LIMITS.cells) reject(2,'workbook-cell-limit');
            for(let r=range.s.r;r<=range.e.r;r++) for(let c=range.s.c;c<=range.e.c;c++) {
                const cell=cellAt(ws,r,c); if(!cell) continue;
                utf8+=encoder.encode(String(cell.w??cell.v??'')).length;
                if(utf8>LIMITS.utf8) reject(2,'owned-text-limit');
                if (cell.f && cell.v === undefined) reject(3,'uncached-formula');
            }
        }
        const merges=ws['!merges']||[];
        if(merges.length>4096) reject(2,'merge-count');
        for(let i=0;i<merges.length;i++) {
            const m=merges[i];
            if(!range || ![m.s.r,m.s.c,m.e.r,m.e.c].every(Number.isInteger) || m.s.r<range.s.r||m.s.c<range.s.c||m.e.r>range.e.r||m.e.c>range.e.c||m.e.r<m.s.r||m.e.c<m.s.c) reject(1,'merge-range');
            for(let j=0;j<i;j++) { const n=merges[j]; if(m.s.r<=n.e.r&&m.e.r>=n.s.r&&m.s.c<=n.e.c&&m.e.c>=n.s.c) reject(1,'overlapping-merges'); }
            for(let r=m.s.r;r<=m.e.r;r++)if(ws['!rows']?.[r]?.hidden)reject(3,'hidden-merge-layout');
            for(let c=m.s.c;c<=m.e.c;c++)if(ws['!cols']?.[c]?.hidden)reject(3,'hidden-merge-layout');
        }
        const hidden=Number(wb.Workbook?.Sheets?.[index]?.Hidden||0)!==0;
        const pieces=cutSheet(ws,range);
        return {name,index,hidden,range,pieces,...normalizedMetadata(ws,range)};
    });
    if(!sheets.some(s=>!s.hidden)) reject(1,'no-visible-sheets');
    return {sheets,cells,utf8};
}

export function foldText(text, sensitive=false) {
    if(sensitive) return {text,map:null};
    let folded='', map=[];
    for(let i=0;i<text.length;) { const cp=String.fromCodePoint(text.codePointAt(i)), lower=cp.toLowerCase();
        for(let j=0;j<lower.length;j++) map.push({start:i,end:i+cp.length}); folded+=lower; i+=cp.length; }
    return {text:folded,map};
}
export function occurrences(text,query,sensitive=false,limit=1001) {
    if(!query) return [];
    const f=foldText(text,sensitive), q=foldText(query,sensitive).text, result=[];
    for(let p=f.text.indexOf(q);p!==-1&&result.length<limit;p=f.text.indexOf(q,p+q.length)) result.push({
        start:f.map?f.map[p].start:p, end:f.map?f.map[p+q.length-1].end:p+q.length});
    return result;
}
