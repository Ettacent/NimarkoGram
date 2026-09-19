const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java');
const bar = fs.readFileSync(path.join(root, 'org/telegram/ui/ActionBar/ActionBar.java'), 'utf8');
const header = fs.readFileSync(path.join(root, 'org/telegram/ui/Components/ChatAvatarContainer.java'), 'utf8');
const start = bar.indexOf('public int getChatAvatarAnimatedContentRight(');
const end = bar.indexOf('public float getChatAvatarOvalCenterInContainer', start);
const getter = bar.slice(start, end);
assert(getter.includes('!container.isLaidOut()'));
assert(getter.includes('calculateChatAvatarOvalBounds(chatAvatarOvalBounds)'));
assert(getter.includes('chatAvatarOvalBounds.right - container.getX()'));
assert(header.includes('glassMode && !centerChatTitle && !useChatTitleLayoutOutsideChat'));
assert(header.includes('availableWidth = Math.max(0, contentRight - textLeft - dp(10));'));
assert(bar.includes('|| callee == animatorMenuItemsWidth\n                || animatorHasMenuItems.isAnimating()'));
for(const scale of [1,1.5,2,3,4]) for(const delta of [-48,48]) {
    const origin = 60*scale, textLeft = 50*scale, slot = 24*scale;
    let previous;
    for(let frame=0;frame<=32;frame++) {
        const progress=1-Math.pow(1-frame/32,5);
        const edge=(350-delta*progress)*scale;
        const available=Math.max(0,edge-origin-6*scale-textLeft-10*scale);
        const badge=origin+textLeft+available-slot;
        assert(Math.abs((edge-badge)-(40*scale))<1e-6);
        if(previous!==undefined) assert(delta>0 ? badge<=previous : badge>=previous);
        previous=badge;
    }
}
console.log('PASS: non-centered text/badge viewport follows animated glass edge for growing/shrinking menu; initial layout guarded');
