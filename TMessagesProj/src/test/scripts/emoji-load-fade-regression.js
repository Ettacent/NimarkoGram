const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java');
const emoji = fs.readFileSync(path.join(root, 'org/telegram/messenger/Emoji.java'), 'utf8');
const cell = fs.readFileSync(path.join(root, 'org/telegram/ui/Cells/ChatMessageCell.java'), 'utf8');
assert(emoji.includes('if (drawingView.get() != null) drewLoadingPlaceholder = true;'));
assert(emoji.includes('if (drewLoadingPlaceholder && fadeOwner != null)'));
assert(emoji.includes('loadAlpha = progress * progress * (3f - 2f * progress);'));
assert(emoji.includes('fadeOwner.postInvalidateOnAnimation();'));
assert(emoji.includes('if (view == null) drawingView.remove();'));
assert(cell.includes('finally {\n            Emoji.setDrawingView(previousEmojiView);'));
const draw = emoji.slice(emoji.indexOf('public static class SimpleEmojiDrawable'), emoji.indexOf('private static class DrawableInfo'));
assert(!draw.includes('postNotificationName'));
assert(draw.includes('paint.setAlpha(previousAlpha)'));
assert(draw.includes('textPaint.setAlpha(previousAlpha)'));
let previous = 0;
for(let elapsed=0;elapsed<=360;elapsed++) {
    const p = Math.min(1, Math.max(0, elapsed / 180));
    const alpha = p*p*(3-2*p);
    assert(alpha >= previous && alpha <= 1);
    assert(alpha - previous < .009);
    if(elapsed === 0) assert.equal(alpha, 0);
    if(elapsed >= 180) assert.equal(alpha, 1);
    previous = alpha;
}
console.log('PASS: cold-load-only fade, bounded smooth alpha, cached fast path and scoped per-cell invalidation guards');
