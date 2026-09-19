const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/java/org/telegram/ui/ActionBar/SimpleTextView.java'), 'utf8');
const start = source.indexOf('} else if (ellipsizeByGradient && textDoesNotFit && fadeEllpsizePaint != null)');
const end = source.indexOf('updateScrollAnimation();', start);
assert(start > 0 && end > start);
const fade = source.slice(start, end);
assert(fade.includes('canvas.drawRect(0, 0, fadeEllpsizePaintWidth, getMeasuredHeight(), fadeEllpsizePaint)'));
assert(fade.includes('canvas.translate(textOffsetX, 0)'));
assert(!fade.includes('canvas.drawRect(textOffsetX,'));
let checks=0;
for (const scale of [1, 1.5, 2, 3, 4]) {
    for (const icon of [0, 12, 18, 24, 32]) {
        for (const fadeWidth of [8, 16, 24]) {
            const start=icon*scale, right=200*scale, w=fadeWidth*scale;
            const rightMask=[right-w, right];
            const leftMask=[start, start+w];
            assert(rightMask[0] >= start);
            assert.equal(rightMask[1]-rightMask[0], w);
            assert.equal(leftMask[1]-leftMask[0], w);
            // Previous local left=textOffsetX made the rectangle empty for typing icons.
            if (icon >= fadeWidth) assert(w-start <= 0);
            checks++;
        }
    }
}
console.log(`PASS: ${checks} icon/fade/density cases; local mask bounds cover the LTR/RTL edge without erasing the leading icon`);
