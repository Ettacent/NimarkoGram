const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java');
const render = fs.readFileSync(path.join(root, 'org/telegram/ui/Components/blur3/drawable/BlurredBackgroundDrawableRenderNode.java'), 'utf8');
const start = render.indexOf('final int effectiveBackgroundColor');
assert.ok(start >= 0);
const body = render.slice(start, render.indexOf('if (strokeColorTop', start)).replace('final int', 'const');
const draw = new Function('Color', 'Build', 'liquidGlassEffect', 'backgroundColor', 'c', 'renderNodeFill', body);
let checks = 0;
for (const background of [0, 0x40000000, 0xff000000, 0xffffffff]) {
    for (const override of [null, 0, 0x40000000, 0x80112233, 0xff123456]) {
        const calls = [];
        draw({alpha: c => c >>> 24}, {VERSION: {SDK_INT: 33}},
            override === null ? null : {getForegroundColor: () => override}, background,
            {drawColor: c => calls.push(['color', c]), drawRenderNode: () => calls.push(['glass'])}, {});
        const color = override === null ? background : override;
        if (color >>> 24 === 255) assert.deepEqual(calls, [['color', color]]);
        else if (override !== null || background === 0) assert.deepEqual(calls, [['glass']]);
        else assert.deepEqual(calls, [['glass'], ['color', background]]);
        checks++;
    }
}
const effect = fs.readFileSync(path.join(root, 'org/telegram/ui/Components/blur3/LiquidGlassEffect.java'), 'utf8');
assert.match(effect, /public int getForegroundColor\(\)\s*\{\s*return foregroundColor;/);
const banner = fs.readFileSync(path.join(root, 'app/nimarkogram/messenger/notifications/NimarkoInAppNotifications.java'), 'utf8');
const tap = banner.slice(banner.indexOf('void animateOpenChat()'), banner.indexOf('void pauseInteraction()'));
assert.match(tap, /setPressed\(false\)/);
assert.match(tap, /animate\(\).withLayer\(\).alpha\(0f\)/);
assert.match(tap, /setDuration\(220\)/);
assert.match(tap, /withEndAction\(\(\) -> \{ if \(banner == this && opening\) openChat\(\);/);
assert.match(tap, /if \(!sample && account != UserConfig.selectedAccount\)\s*\{\s*openChat\(\);\s*return;/);
console.log(`PASS: ${checks} native glass color-routing cases and grouped tap fade contracts; device rendering/plugin integration require verification`);
