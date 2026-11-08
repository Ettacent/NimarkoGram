/* Modifications Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent.
 * Adapts Mozilla PDF.js v6.3.289 helpers; Mozilla 2012/2018/2021 Apache notices and
 * full license travel with mozilla-find.mjs / MOZILLA_SEARCH_NOTICE.txt.
 */
import { normalize, getOriginalIndex, queryPattern, convertMatches } from './mozilla-find.mjs';

const whitespace = text => text.replace(/\s/g, ' ');

export function extract(items) {
  const strBuf=[];
  let glyphLength=0;
  for(const item of items){
    if(typeof item.str!=='string')continue;
    glyphLength+=item.str.length;
    strBuf.push(whitespace(item.str));
    if(item.hasEOL)strBuf.push('\n');
  }
  const [text,diffs,hasDiacritics]=normalize(strBuf.join(''));
  const bytes=text.length*2+diffs[0].byteLength+diffs[1].byteLength+128;
  return {text,diffs,hasDiacritics,glyphLength,bytes};
}

export function* matches(page,query) {
  if(!page||!query)return;
  const [normalizedQuery]=normalize(whitespace(query));
  const [,pattern]=queryPattern(normalizedQuery,page.hasDiacritics);
  if(!pattern)return;
  const matcher=new RegExp(pattern,'giu');
  let match;
  while((match=matcher.exec(page.text))!==null){
    if(!match[0].length){matcher.lastIndex++;continue;}
    const [at,len]=getOriginalIndex(page.diffs,match.index,match[0].length);
    if(len>0)yield {at,len};
  }
}

export function ranges(divs,strings,retained,document,glyphLength) {
  if(divs.length!==strings.length)throw new Error('PDF text mapping unavailable');
  const length=strings.reduce((n,str)=>n+str.length,0);
  if(length!==glyphLength)throw new Error('PDF text extraction differs from text layer');
  for(const match of retained){
    if(!Number.isInteger(match.at)||!Number.isInteger(match.len)||match.at<0||match.len<1||match.at+match.len>length)throw new Error('PDF match outside glyph text');
  }
  const converted=convertMatches(strings,retained.map(m=>m.at),retained.map(m=>m.len));
  function textNode(index){
    const node=divs[index]?.firstChild;
    if(!node||node.nodeType!==3||node.length!==strings[index].length||node.data!==strings[index])throw new Error('PDF text span mapping unavailable');
    return node;
  }
  return converted.map((match,i)=>{
    const range=document.createRange();
    range.setStart(textNode(match.begin.divIdx),match.begin.offset);
    range.setEnd(textNode(match.end.divIdx),match.end.offset);
    return {range,match:retained[i]};
  });
}
