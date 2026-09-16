const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ui = path.resolve(__dirname, '../../main/java/org/telegram/ui');
const text = fs.readFileSync(path.join(ui, 'ActionBar/SimpleTextView.java'), 'utf8');
const bar = fs.readFileSync(path.join(ui, 'ActionBar/ActionBar.java'), 'utf8');
const profile = fs.readFileSync(path.join(ui, 'ProfileActivity.java'), 'utf8');
for (const view of ['subtitleTextView', 'additionalSubtitleTextView', 'titleTextView[i]']) {
    assert(bar.includes(`${view}.setEllipsizeByGradient(true, LocaleController.isRTL)`));
}
assert(profile.includes('onlineTextView[a].setEllipsizeByGradient(true)'));
assert(text.includes('textOverflow = Math.max(0f, layout.getLineWidth(0) + rightDrawableWidth - (width - paddingRight))'));
assert(text.includes('textOverflow / Math.max(1, fadeEllpsizePaintWidth)'));
assert(text.includes('ellipsizeByGradient && !scrollNonFitText && textWidth + rightDrawableWidth > width'));

let cases = 0;
for (const density of [1, 1.5, 2, 3, 4]) {
    const fadeWidth = Math.ceil(16 * density);
    let previous = 255;
    for (let remaining = fadeWidth * 4; remaining >= 0; remaining--) {
        const overflow = remaining / 4;
        const alpha = Math.round(255 * Math.min(1, overflow / Math.max(1, fadeWidth)));
        assert(alpha <= previous);
        assert(previous - alpha <= Math.ceil(255 / fadeWidth / 4));
        previous = alpha;
        cases++;
    }
    assert.equal(previous, 0);
    for (const rtl of [false, true]) {
        for (const width of [1, 80, 160, 300]) {
            for (const content of [1, 79, 160, 400]) {
                const offset = content > width ? (rtl ? width - content : 0) : (width - content) / 2;
                if (content > width) {
                    assert.equal(rtl ? offset + content : offset, rtl ? width : 0);
                } else {
                    assert(offset >= 0 && offset + content <= width);
                }
                cases++;
            }
        }
    }
}
console.log(`PASS: header gradient source checks and ${cases} geometry/fade cases; no Android rendering test.`);
