const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../../main/java/org/telegram/ui');
const chat = fs.readFileSync(path.join(root, 'ChatActivity.java'), 'utf8');
const animator = fs.readFileSync(path.join(root, 'recyclerview/ChatListItemAnimator.java'), 'utf8');
function method(source, signature) {
    const start = source.indexOf(signature);
    assert(start >= 0, signature);
    let end = source.indexOf('{', start) + 1, depth = 1;
    for (; depth && end < source.length; end++) {
        if (source[end] === '{') depth++;
        if (source[end] === '}') depth--;
    }
    assert.equal(depth, 0);
    return source.slice(start, end);
}

const move = method(animator, 'protected void animateMoveImpl(RecyclerView.ViewHolder holder, MoveInfo moveInfo, boolean withThanos)');
assert.match(move, /view instanceof ChatActionCell && view.getTranslationX\(\) != 0f/);
assert.match(move, /ObjectAnimator.ofFloat\(view, View.TRANSLATION_X, 0f\)/);
const reset = method(animator, 'private void restoreTransitionParams(View view)');
assert.match(reset, /view instanceof ChatActionCell\) \{[^}]*resetAnimation\(\);\s*view.setTranslationX\(0f\);/);
assert.match(move, /onAnimationEnd[\s\S]*restoreTransitionParams\(holder.itemView\)/);
assert.match(method(animator, 'public void endAnimation(RecyclerView.ViewHolder item)'), /restoreTransitionParams\(item.itemView\)/);

const position = method(chat, 'private void updateFloatingDatePosition()');
const expression = position.match(/floatingDateView.setTranslationY\(([\s\S]*?)\);/)[1];
const calculate = new Function('chatListView', 'floatingDateView', 'chatListViewPaddingTop', 'dp', 'return ' + expression);
const floating = chat.slice(chat.indexOf('floatingDateView = new ChatActionCell'), chat.indexOf('floatingDateView.setCustomDate'));
assert(!floating.includes('canvas.clipRect'));
assert.match(floating, /canvas.saveLayerAlpha/);
const fadeExpression = floating.match(/final float visible = ([\s\S]*?);/)[1].replace(/(\d)f\b/g, '$1');
const fade = new Function('floatingDateViewOffset', 'getMeasuredHeight', 'return ' + fadeExpression);
const padding = method(chat, 'private void updateChatListViewTopPadding()');
assert(!padding.includes('searchExpandOffset'));
assert(padding.includes('getTopPanelHeightWithPadding(dp(7))'));
assert(padding.includes('updateFloatingDatePosition();'));
assert.equal((chat.match(/updateFloatingDatePosition\(\);/g) || []).length, 2);

let cases = 0;
for (const density of [1, 1.5, 2, 2.75, 3, 4]) {
    const dp = value => Math.ceil(value * density);
    for (const pinned of [0, 43]) for (const translated of [0, 9, 18, 27, 36]) {
        for (const listTop of [-dp(80), 0, dp(24)]) for (const dateTop of [-dp(80), 0, dp(24)]) {
            for (const search of [0, 12, 30]) {
                const listTranslation = dp(7);
                const safePadding = dp(84 + pinned + translated - search);
                const y = calculate({getY: () => listTop + listTranslation}, {getTop: () => dateTop}, safePadding, dp);
                const safeTop = listTop + listTranslation + safePadding - dp(4);
                assert(Math.abs(dateTop + y - safeTop) < 1e-6);
                let previous = 0;
                for (let offset = -dp(30); offset <= 0; offset++) {
                    const alpha = fade(offset, () => dp(30));
                    assert(alpha >= previous && alpha <= 1);
                    previous = alpha;
                }
                assert.equal(previous, 1);
                cases++;
            }
        }
    }
}
assert.equal(fade(-1, () => 0), 0);
assert.equal(fade(0, () => 0), 1);
assert.equal(fade(10, () => 30), 1);
console.log('PASS: date X animation/reset wiring; ' + cases + ' date-position/fade cases, translation panel, pinned panel, search, distinct parent offsets and zero height. No compilation.');
