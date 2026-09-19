const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java');
const title = fs.readFileSync(path.join(root, 'org/telegram/ui/ActionBar/SimpleTextView.java'), 'utf8');
const container = fs.readFileSync(path.join(root, 'org/telegram/ui/Components/ChatAvatarContainer.java'), 'utf8');
const start = title.indexOf('private void updateScrollAnimation()');
const method = title.slice(start, title.indexOf('public void invalidateDrawable', start));
assert(method.indexOf('lastUpdateTime = newUpdateTime;') < method.indexOf('if (currentScrollDelay > 0)'));
assert(method.includes('Math.max(0, newUpdateTime - lastUpdateTime)'));
const shrink = container.slice(container.indexOf('private void fadeOutToLessWidth'), container.indexOf('private void clearLargerTextCopies'));
assert(shrink.includes('if (glassMode || centerChatTitle || useChatTitleLayoutOutsideChat)'));
assert(shrink.indexOf('clearLargerTextCopies();') < shrink.indexOf('new SimpleTextView('));
function remaining(passes, oldClock) {
    let last = 0, delay = 500;
    for(let frame=1;frame<=20;frame++) for(let pass=0;pass<passes;pass++) {
        const now = frame*16;
        const dt = Math.min(17, Math.max(0, now-last));
        if(!oldClock) last=now;
        delay -= dt;
    }
    return delay;
}
for (const passes of [1,2,3,5]) assert.equal(remaining(passes, false), 180);
assert(remaining(3, true) < 0, 'negative control: captures prematurely consume the old pause');
console.log('PASS: glass title has one owner; marquee delay counts elapsed time, not capture passes');
