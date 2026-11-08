/* Modifications Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent. Gander (MIT); see GANDER_LICENSE.txt. */
export const CACHE=Object.freeze({baseTables:3,visibleTables:32,totalTables:33,
    visibleCells:60000,totalCells:80000,visibleText:3*1024*1024,totalText:4*1024*1024,
    pieceText:1024*1024,pieceHTML:8*1024*1024,pieceBreaks:20000,minScale:.1});
export const pieceCells=(sheet,p)=>Math.max(0,p.e-p.s+1)*(sheet.range?sheet.range.e.c-sheet.range.s.c+1:0);
export function withinVisible(sheet,indices){let cells=0,text=0;for(const k of indices){cells+=pieceCells(sheet,sheet.pieces[k]);text+=sheet.pieces[k].textUnits||0;}return indices.length<=CACHE.visibleTables&&cells<=CACHE.visibleCells&&text<=CACHE.visibleText;}
export function maxViewportHeight(win){const vv=win.visualViewport;return Math.max(vv?.height||0,
    Math.max(win.screen?.width||0,win.screen?.height||0,(vv?.width||win.innerWidth)*(vv?.scale||1),(vv?.height||win.innerHeight)*(vv?.scale||1))/CACHE.minScale);}
export function rowLowerHeights(sheet){
    if(!sheet.range)return sheet.pieces.map(()=>1);
    const {s,e}=sheet.range,prefix=[0];
    for(let c=s.c;c<=e.c;c++)prefix.push(prefix.at(-1)+(sheet.cols?.[c]?.hidden?0:1));
    const visible=prefix.at(-1),events=new Map();
    const add=(r,delta)=>events.set(r,(events.get(r)||0)+delta);
    for(const m of sheet.merges||[]){
        if(m.e.r===m.s.r&&!sheet.cols?.[m.s.c]?.hidden)continue;
        const first=Math.max(s.c,m.s.c),last=Math.min(e.c,m.e.c);
        const covered=first<=last?prefix[last-s.c+1]-prefix[first-s.c]:0;
        if(covered){add(Math.max(s.r,m.s.r),covered);add(Math.min(e.r,m.e.r)+1,-covered);}
    }
    const changes=[...events].sort((a,b)=>a[0]-b[0]);let event=0,covered=0;
    return sheet.pieces.map(piece=>{let height=0;
        for(let r=piece.s;r<=piece.e;r++){
            while(event<changes.length&&changes[event][0]<=r)covered+=changes[event++][1];
            const row=sheet.rows?.[r];if(row?.hidden)continue;
            if(visible>covered&&covered>=0){const declared=Number.isFinite(row?.hpx)?row.hpx:Number.isFinite(row?.hpt)?row.hpt*4/3:1;height+=Math.max(8,declared);}
            else height+=1;
        }return Math.max(1,height);
    });
}
export function admitViewport(sheet,height){
    if(sheet.hidden)return true;const all=sheet.pieces.map((_,k)=>k);if(withinVisible(sheet,all))return true;
    const lower=rowLowerHeights(sheet);
    for(let start=0;start<lower.length;start++){let middle=0;const needed=[];
        for(let end=start;end<lower.length;end++){if(end>start+1)middle+=lower[end-1];if(middle>height)break;needed.push(end);if(!withinVisible(sheet,needed))return false;}
    }return true;
}
