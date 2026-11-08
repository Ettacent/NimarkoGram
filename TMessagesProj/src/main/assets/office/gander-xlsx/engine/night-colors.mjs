/* Modifications Copyright (C) 2026 Ettacent */

/* Gander night-colour matrix adaptation, MIT, Copyright (c) 2026 Arjun Maniyani.
 * mokshablr/gander@4e60c1ea460d871012576e7e330e585efd7514ec
 * viewer/docx-night.js VW_NIGHT_MATRIX/vwNightColour. Full GANDER_LICENSE.txt.
 * Local modifications Copyright (C) 2026 Ettacent.
 */
export function nightColor(value){
    if(!/^#[\da-f]{3}(?:[\da-f]{3})?$/i.test(value))throw Error('validated hex required');
    const hex=value.length===4?value.slice(1).split('').map(c=>c+c).join(''):value.slice(1);
    const rgb=[0,2,4].map(i=>parseInt(hex.slice(i,i+2),16));
    if(Math.max(...rgb)-Math.min(...rgb)<=8){if(Math.max(...rgb)<=64)return 'var(--office-ink)';if(Math.min(...rgb)>=240)return 'var(--office-paper)';}
    return '#'+[[.574,-1.430,-.144],[-.426,-.430,-.144],[-.426,-1.430,.856]].map(row=>
        Math.round(Math.max(0,Math.min(255,255+row.reduce((sum,n,i)=>sum+n*rgb[i],0)))).toString(16).padStart(2,'0')).join('');
}
export function prepareNightTokens(td,tokens){
    const properties=[];
    for(const [key,value]of Object.entries(tokens)){
        if(key==='color'||key==='background'){
            td.style.setProperty('--office-night-'+key,nightColor(value));properties.push(key);
        }else if(/^border(?:-(?:top|right|bottom|left))?$/.test(key)&&value!=='0'){
            td.style.setProperty('--office-night-'+key,value.replace(/#[\da-f]+$/i,nightColor));properties.push(key);
        }
    }
    if(tokens.background){
        const fill=nightColor(tokens.background),ink=tokens.color?nightColor(tokens.color):'var(--office-ink)';
        const paired=nightInkForFill(ink,fill);
        if(paired!==ink){td.style.setProperty('--office-night-color',paired);if(!properties.includes('color'))properties.push('color');}
    }
    if(properties.length)td.setAttribute('data-office-night',properties.join(' '));
}

export function nightInkForFill(ink,fill){
    if(!/^#[\da-f]{6}$/i.test(fill))return ink;
    const light=value=>[1,3,5].map(i=>parseInt(value.slice(i,i+2),16)/255)
        .map(v=>v<=.04045?v/12.92:((v+.055)/1.055)**2.4)
        .reduce((sum,v,i)=>sum+v*[.2126,.7152,.0722][i],0);
    const b=light(fill);
    if(/^#[\da-f]{6}$/i.test(ink)){
        const a=light(ink);if((Math.max(a,b)+.05)/(Math.min(a,b)+.05)>=4.5)return ink;
    }else if(!['var(--office-ink)','var(--office-paper)'].includes(ink))return ink;
    return (b+.05)/.05>=1.05/(b+.05)?'#000000':'#ffffff';
}
