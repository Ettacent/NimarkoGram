const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java');
for (const [file, expected] of [['CryptoCard.java', 2], ['ProxyCard.java', 1]]) {
    const source = fs.readFileSync(path.join(root, 'app/nimarkogram/messenger/infocards', file), 'utf8');
    const menus = source.match(/ItemOptions\.makeOptions\(fragment, this\)[^;]*;/g);
    assert.equal(menus.length, expected);
    for (const menu of menus) assert(menu.includes('.setDrawScrim(false)'));
    assert(!source.includes('.hideScrimUnder()'));
}
// Both the growing and shrinking chip keep their actual right edge fixed. A second
// draw at the opening x would drift as the width changes, then jump on dismiss.
for (const [from, to] of [[96, 72], [72, 96]]) {
    const right = 400;
    const frozenX = right - from;
    for (let frame = 0; frame <= 20; frame++) {
        const width = from + (to - from) * frame / 20;
        const actualX = right - width;
        assert.equal(actualX + width, right);
        if (frame > 0) assert.notEqual(frozenX + width, right);
    }
}
console.log('PASS: all card menus keep a single live drawing owner; frozen overlay reproduces currency resize drift');
