// Run with: node TMessagesProj/src/test/scripts/chat-glass-item-animation-regression.js
// Executes the production pre-draw control flow with host stubs, not Android/GPU
// rendering. No Java compilation, application build, timers or temporary files.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const java = path.resolve(__dirname, '../../main/java');
const chat = fs.readFileSync(path.join(java, 'org/telegram/ui/ChatActivity.java'), 'utf8');
const postDraw = fs.readFileSync(path.join(java, 'org/telegram/messenger/utils/OnPostDrawView.java'), 'utf8');
const itemAnimator = fs.readFileSync(path.join(java, 'androidx/recyclerview/widget/DefaultItemAnimator.java'), 'utf8');

function body(source, signature) {
    const start = source.indexOf(signature);
    assert(start >= 0, `Missing production method: ${signature}`);
    const brace = source.indexOf('{', start);
    const tokens = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|[{}]/g;
    tokens.lastIndex = brace;
    let depth = 0;
    for (let token; (token = tokens.exec(source));) {
        if (token[0] === '{') depth++;
        if (token[0] === '}' && --depth === 0) return source.slice(brace + 1, token.index);
    }
    throw Error(`Unclosed production method: ${signature}`);
}

const host = body(chat, 'invalidateBlurredSourcesView = new OnPostDrawView(');
const bridge = body(host, 'public boolean onPreDraw()');
assert.match(host, /private boolean wasListItemAnimatorRunning;/);
assert.match(bridge, /return super\.onPreDraw\(\);/);
assert(!/postDelayed|postOnAnimation|ValueAnimator|setAlpha|setIntensity|BLUR_INVALIDATE_FLAG_POSITIONS/.test(bridge),
    'Bridge must only request content capture, without a timer, material fade or crop rebuild');

// Translate only Java local declarations and field qualification; execute the
// actual production branches and ordering, including OnPostDrawView flag drain.
function executable(source, fields) {
    return source.replace(/\bfinal (boolean|int)\b/g, 'const')
        .replace(new RegExp(`\\b(${fields.join('|')})\\b`, 'g'), 'this.$1');
}
const preDraw = new Function(executable(bridge.replace('super.onPreDraw()', 'basePreDraw()'), [
    'chatListItemAnimator', 'wasListItemAnimatorRunning',
    'invalidateMergedVisibleBlurredPositionsAndSources', 'BLUR_INVALIDATE_FLAG_SCROLL', 'basePreDraw'
]));
const basePreDraw = new Function(executable(body(postDraw, 'public boolean onPreDraw()'), [
    'onPreDrawMode', 'invalidateFlags', 'callback'
]));
const markInvalid = new Function('flags', executable(body(postDraw, 'public void invalidate(int flags)'), [
    'invalidateFlags', 'invalidate'
]));
const runningBody = body(itemAnimator, 'public boolean isRunning()');
const lists = [...new Set(runningBody.match(/\bm\w+(?=\.isEmpty\(\))/g))];
assert(lists.includes('mPendingAdditions') && lists.includes('mAddAnimations') && lists.includes('mAdditionsList'),
    'Pending, delayed and running additions must all count as animation lifetime');
const isRunning = new Function(executable(runningBody, lists));

function animator(activeLists = []) {
    const state = {isRunning};
    for (const name of lists) state[name] = {isEmpty: () => !activeLists.includes(name)};
    return state;
}

function harness() {
    const h = {
        BLUR_INVALIDATE_FLAG_SCROLL: 1,
        chatListItemAnimator: null,
        wasListItemAnimatorRunning: false,
        onPreDrawMode: true,
        invalidateFlags: 0,
        captures: [],
        invalidations: 0,
        alpha: 0,
        scale: 0.9,
        basePreDraw,
        preDraw,
        invalidate() { this.invalidations++; },
        invalidateMergedVisibleBlurredPositionsAndSources(flags) { markInvalid.call(this, flags); }
    };
    h.callback = {onPostDraw(flags) { h.captures.push({flags, alpha: h.alpha, scale: h.scale}); }};
    return h;
}

// Idle and absent animator do not schedule any work, even across many frames.
const idle = harness();
for (let frame = 0; frame < 100; frame++) assert.equal(idle.preDraw(), true);
idle.chatListItemAnimator = animator();
for (let frame = 0; frame < 100; frame++) idle.preDraw();
assert.equal(idle.captures.length, 0);
assert.equal(idle.invalidations, 0);

// Every category counted by the actual item animator requests one capture in
// THIS pre-draw, after the current frame's properties have been applied.
for (const list of lists) {
    const h = harness();
    h.chatListItemAnimator = animator([list]);
    h.alpha = 0.4;
    h.scale = 0.94;
    assert.equal(h.preDraw(), true);
    assert.deepEqual(h.captures, [{flags: 1, alpha: 0.4, scale: 0.94}], list);
    assert.equal(h.invalidateFlags, 0);
}

// Lifetime is not tied to the auxiliary animator's 370ms timer. Delayed or
// pending work stays active, then the reset properties get ONE final capture.
const delayed = harness();
for (let frame = 0; frame < 60; frame++) {
    delayed.chatListItemAnimator = animator([frame < 30 ? 'mPendingAdditions' : 'mAddAnimations']);
    delayed.alpha = frame / 60;
    delayed.preDraw();
    assert.equal(delayed.captures.length, frame + 1);
    assert.equal(delayed.captures.at(-1).alpha, delayed.alpha);
}
delayed.chatListItemAnimator = animator();
delayed.alpha = 1;
delayed.scale = 1;
delayed.preDraw();
assert.deepEqual(delayed.captures.at(-1), {flags: 1, alpha: 1, scale: 1});
for (let frame = 0; frame < 100; frame++) delayed.preDraw();
assert.equal(delayed.captures.length, 61);
assert.equal(delayed.invalidations, 61, 'No idle invalidation loop after settle');

// Cancel/removal of the animator still drains the final reset frame; re-entry
// starts a new bounded lifetime, and a new host inherits no stale running bit.
const cancelled = harness();
cancelled.chatListItemAnimator = animator(['mAddAnimations']);
cancelled.preDraw();
cancelled.chatListItemAnimator = null;
cancelled.alpha = cancelled.scale = 1;
cancelled.preDraw();
cancelled.preDraw();
assert.equal(cancelled.captures.length, 2);
assert.equal(cancelled.captures[1].alpha, 1);
cancelled.chatListItemAnimator = animator(['mMoveAnimations']);
cancelled.preDraw();
cancelled.chatListItemAnimator = animator();
cancelled.preDraw();
cancelled.preDraw();
assert.equal(cancelled.captures.length, 4);
assert.equal(harness().wasListItemAnimatorRunning, false);

// Existing geometry/clip flags coalesce with content; invalidations raised by
// capture remain queued rather than being erased by the bridge or flag drain.
const coalesced = harness();
coalesced.invalidateFlags = 2 | 4;
coalesced.chatListItemAnimator = animator(['mAddAnimations']);
coalesced.callback.onPostDraw = flags => {
    coalesced.captures.push(flags);
    coalesced.invalidateMergedVisibleBlurredPositionsAndSources(2);
};
coalesced.preDraw();
assert.deepEqual(coalesced.captures, [7]);
assert.equal(coalesced.invalidateFlags, 2);
const external = harness();
external.invalidateFlags = 2;
external.preDraw();
assert.equal(external.captures[0].flags, 2, 'Idle bridge preserves external capture requests');

console.log(`PASS: ${lists.length} animator states; same-frame capture, delayed tail, final/cancel settle, idle and flag coalescing`);
