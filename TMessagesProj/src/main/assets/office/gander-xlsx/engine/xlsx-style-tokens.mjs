/* Modifications Copyright (C) 2026 Ettacent */

import {reject} from './gander-core.mjs';
import {prepareNightTokens} from './night-colors.mjs';
const color='#[0-9a-fA-F]{3}(?:[0-9a-fA-F]{3})?';
const rules={background:new RegExp('^'+color+'$'),color:new RegExp('^'+color+'$'),'font-weight':/^700$/,'font-style':/^italic$/,'text-decoration':/^(underline|line-through|underline line-through)$/,'font-size':/^(?:\d+(?:\.\d+)?pt|smaller)$/,'font-family':/^'[\p{L}\p{N} ._-]{1,96}',sans-serif$/u,'text-align':/^(left|right|center|justify)$/,'vertical-align':/^(top|middle|bottom|super|sub)$/,'white-space':/^(pre-wrap|nowrap)$/,'padding-left':/^\d+px$/,border:new RegExp('^(?:0|[123]px (?:solid|dashed|dotted|double) '+color+')$')};
for(const side of ['top','right','bottom','left'])rules['border-'+side]=rules.border;
export function tokenize(css){const tokens=Object.create(null);for(const part of css.split(';')){if(!part)continue;const i=part.indexOf(':'),key=part.slice(0,i),value=part.slice(i+1);if(!rules[key]?.test(value)||value.length>160)reject(3,'unsafe-style-token');tokens[key]=value;}return tokens;}
export function applyCellTokens(td,tokens){if(!tokens)return;if(typeof tokens!=='object'||Object.keys(tokens).length>20)reject(3,'style-token-shape');for(const [key,value] of Object.entries(tokens)){if(typeof value!=='string'||!rules[key]?.test(value)||value.length>160)reject(3,'unsafe-style-token');td.style.setProperty(key,value);}prepareNightTokens(td,tokens);
    if(tokens.border==='1px solid #dcdfe4')td.style.setProperty('--office-night-border','1px solid var(--office-border)');
}
