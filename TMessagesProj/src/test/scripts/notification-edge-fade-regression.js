const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname,
    '../../main/java/app/nimarkogram/messenger/notifications/NimarkoInAppNotifications.java'), 'utf8');
const body = source.match(/private float getEdgeFadeHeight\(float offset\)\s*\{([^}]+)\}/)[1];
const fade = new Function('getHeight', 'dp', 'offset', body.replace(/(\d)f\b/g, '$1'));
const visibleBody = source.match(/private float getVisibleCardHeight\(float height, float offset\)\s*\{([^}]+)\}/)[1];
const visible = new Function('getHeight', 'height', 'offset', visibleBody.replace(/(\d)f\b/g, '$1'));
let checks = 0;
for (const density of [1, 1.5, 2.75, 3, 4]) {
    const dp = n => Math.ceil(n * density);
    for (const height of [0, 1, 8, 40, 200, 800]) {
        let previous = 0;
        for (let travel = 0; travel <= 400; travel += .5) {
            const band = fade(() => height, dp, -travel);
            assert.equal(band, Math.min(dp(12), travel));
            assert.ok(band >= previous);
            assert.equal(fade(() => height, dp, travel), 0);
            assert.equal(visible(() => height, 200, -travel), Math.max(0, Math.min(height, 200 - travel)));
            previous = band;
            checks++;
        }
    }
}
for (const remaining of [10, 5, 1, .5, .01, 0]) {
    const offset = remaining - 200;
    const h = visible(() => 200, 200, offset);
    const band = fade(() => 200, n => n, offset);
    assert.ok(h / band <= remaining / 12 + 1e-6);
    if (remaining === 0) assert.equal(h, 0);
}
const draw = source.slice(source.indexOf('@Override protected boolean drawChild', source.indexOf('private static final class Slot')),
    source.indexOf('private NimarkoInAppNotifications()'));
const slot = source.slice(source.indexOf('private static final class Slot'), source.indexOf('private NimarkoInAppNotifications()'));
assert.match(slot, /onSetAlpha\(int alpha\)[\s\S]*?contentAlpha = alpha;[\s\S]*?return true;/);
assert.match(slot, /child.getMatrix\(\).mapRect\(childBounds\)/);
assert.match(slot, /contentBounds.union\(childBounds\)/);
assert.match(slot, /saveLayerAlpha\(contentBounds.left, contentBounds.top,[\s\S]*?contentBounds.right, contentBounds.bottom, contentAlpha\)/);
assert.doesNotMatch(slot, /hasOverlappingRendering/);
assert.match(draw, /getEdgeFadeHeight\(value.pullOffset\)/);
assert.match(draw, /getVisibleCardHeight\(child.getHeight\(\), value.pullOffset\)/);
assert.ok(draw.indexOf('visibleHeight <= 0f) return false') < draw.indexOf('surface.drawShadow'));
assert.match(draw, /saveLayer\(-outset, 0, getWidth\(\) \+ outset, visibleHeight \+ outset, null\)/);
assert.match(draw, /PorterDuff.Mode.DST_OUT/);
assert.match(draw, /Color.BLACK, android.graphics.Color.TRANSPARENT/);
assert.match(draw, /fadeHeight > 0f \? canvas.saveLayer/);
assert.ok(draw.indexOf('canvas.saveLayer') < draw.indexOf('surface.drawShadow'));
assert.ok(draw.indexOf('surface.drawShadow') < draw.indexOf('super.drawChild'));
assert.ok(draw.indexOf('super.drawChild') < draw.indexOf('canvas.drawRect'));
assert.ok(draw.indexOf('canvas.drawRect') < draw.indexOf('restoreToCount(layer)'));
console.log(`PASS: ${checks} edge fade geometry checks, per-card masking, shadow/content isolation; device GPU rendering still requires verification`);
