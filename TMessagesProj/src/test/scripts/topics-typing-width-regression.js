const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ui = path.resolve(__dirname, '../../main/java/org/telegram/ui');
const header = fs.readFileSync(path.join(ui, 'Components/ChatAvatarContainer.java'), 'utf8');
const text = fs.readFileSync(path.join(ui, 'ActionBar/SimpleTextView.java'), 'utf8');
const setter = text.split('public void setEllipsizeByGradient(boolean value, Boolean forceLeft) {')[1]
    .split('\n    }')[0];
assert(setter.includes('ellipsizeByGradient == value'));
assert(setter.includes('forceEllipsizeByGradientLeft.equals(forceLeft)'));
assert(!setter.includes('scrollNonFitText'));
assert(setter.includes('requestLayout();'));
assert(setter.includes('invalidate();'));
assert(text.includes('TextUtils.ellipsize(text, textPaint, width, TextUtils.TruncateAt.END)'));
assert(!header.includes('!centerChatTitle && !useChatTitleLayoutOutsideChat'));
assert(!header.includes('!value && !useChatTitleLayoutOutsideChat'));
assert.equal((header.match(/subtitleTextView\.setEllipsizeByGradient\(\s*true,/g) || []).length, 2);
assert(header.includes('if (compactContentWidth > 0)'));
assert(header.includes('Math.min(centeredSubtitleCapacity,'));
assert(header.includes('(inlineCenteredAvatar ? avatarImageView.getMeasuredWidth() + dp(8) : 0)'));
assert(header.includes('Math.ceil(getInlineDesiredWidth(subtitleTextView))'));
assert(header.includes('if (centerChatTitle && subtitleTextView.getVisibility() != GONE)'));

let cases = 0;
for (const scale of [1, 1.5, 2, 3, 4]) {
    const dp = n => Math.ceil(n * scale);
    for (const inline of [false, true]) {
        for (const available of [100, 160, 280, 520].map(dp)) {
            for (const compact of [0, 70, 110, 180, 300].map(dp)) {
                for (const desired of [32, 140, 400, 1600].map(dp)) {
                    const avatar = dp(42) - 2;
                    const content = compact - dp(4) * 2 - (inline ? avatar + dp(8) : 0);
                    const capacity = compact > 0 ? Math.min(available, Math.max(0, content)) : available;
                    const measured = Math.max(1, Math.min(capacity, desired));
                    assert(measured <= available);
                    if (!compact) assert.equal(measured, Math.min(available, desired));
                    if (content > 0 && compact > 0) {
                        const center = 500 * scale;
                        const left = Math.round(center - measured / 2);
                        assert(left >= center - content / 2 - 0.5);
                        assert(left + measured <= center + content / 2 + 0.5);
                    }
                    cases++;
                }
            }
        }
    }
}
console.log(`PASS: typing ellipsis source checks and ${cases} width-model cases; no Android rendering test.`);
