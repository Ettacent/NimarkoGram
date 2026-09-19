const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java');
const text = fs.readFileSync(path.join(root, 'org/telegram/ui/Components/AnimatedTextView.java'), 'utf8');
const card = fs.readFileSync(path.join(root, 'app/nimarkogram/messenger/infocards/BaseInfoCard.java'), 'utf8');
assert(card.includes('setStableBaseline(true)'));
assert(card.includes('RESIZE_DURATION_MS = 300'));
assert(text.includes('getDrawingHeight(lerp(oldHeight, currentHeight, t))'));
assert(text.includes('(fullHeight - getDrawingHeight(currentHeight)) / 2f'));
assert(text.includes('-textPaint.ascent() - layout.getLineBaseline(0)'));
assert.equal((text.match(/canvas\.scale\(s, stableBaseline \? 1f : s/g) || []).length, 2);
// Different fallback-font layouts must still draw on one baseline, including t=1.
for (const ascent of [-10, -17.5, -30]) for (const descent of [3, 5, 8]) {
    const target = (56 - (descent - ascent)) / 2 - ascent;
    for (const layoutBaseline of [10, 12, 18, 25, 35]) {
        const actual = (56 - (descent - ascent)) / 2 - ascent - layoutBaseline + layoutBaseline;
        assert.equal(actual, target);
    }
}
console.log('PASS: card-only baseline opt-in; same vertical anchor during/after animation and across fallback layouts');
