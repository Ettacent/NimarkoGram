const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/java/org/telegram/ui/Components/SectionsScrollView.java'), 'utf8');
const clip = source.slice(source.indexOf('private void clipChild('));
const expressions = clip.match(/rectTmp\.set\(\s*child\.getX\(\),\s*(.*),\s*child\.getX\(\) \+ child\.getWidth\(\),\s*(.*)\s*\);/);
assert(expressions, 'clip rectangle');
const evaluate = expression => new Function('getScrollY', 'contentView', 'dp', 'child', 'getHeight', `return ${expression};`);
const top = evaluate(expressions[1]);
const bottom = evaluate(expressions[2]);
let checks = 0;
for (const density of [1, 2, 3]) {
    for (const offset of [0, 4, 40, 80, 180]) {
        for (const scroll of [0, 20, 150, 500]) {
            for (const y of [4, 60, 200, 700]) {
                const contentView = {getY: () => offset * density};
                const child = {getY: () => y * density, getHeight: () => 100 * density};
                const args = [() => scroll * density, contentView, v => v * density, child, () => 400 * density];
                const screenTop = top(...args) + contentView.getY() - scroll * density;
                const screenBottom = bottom(...args) + contentView.getY() - scroll * density;
                assert.equal(screenTop, Math.max(-16, offset + y - scroll) * density);
                assert.equal(screenBottom, Math.min(416, offset + y + 100 - scroll) * density);
                checks++;
            }
        }
    }
}
const oldTop = evaluate('Math.max(getScrollY() - dp(16), contentView.getY() + child.getY())');
assert.notEqual(oldTop(() => 0, {getY: () => 80}, v => v, {getY: () => 4}, () => 400), 4,
    'old rectangle must reproduce clipping into the first row');
class View {
    constructor(parent, x, y) { this.parent = parent; this.x = x; this.y = y; }
    getParent() { return this.parent; }
    getX() { return this.x; }
    getY() { return this.y; }
}
for (const axis of ['X', 'Y']) {
    const body = source.match(new RegExp(`private float getChild${axis}\\(View child\\) \\{([\\s\\S]*?)\\n    \\}`))[1]
        .replaceAll('(View)', '');
    const relative = new Function('contentView', 'View', `return function getChild${axis}(child) {${body}};`);
    for (const offset of [0, 4, 80, 200]) {
        const content = new View(null, offset, offset);
        const block = new View(content, 12, 40);
        const button = new View(block, 0, 56);
        const coordinate = relative(content, View);
        assert.equal(coordinate(content), 0, 'content origin must not be counted twice');
        assert.equal(coordinate(block), axis === 'X' ? 12 : 40);
        assert.equal(coordinate(button), axis === 'X' ? 12 : 96);
    }
}
console.log(`PASS: ${checks} section clip coordinate cases, including notification padding, scrolling and densities`);
