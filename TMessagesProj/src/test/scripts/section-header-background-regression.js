const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java/org/telegram/ui');
const cell = fs.readFileSync(path.join(root, 'Cells/GraySectionCell.java'), 'utf8');
const list = fs.readFileSync(path.join(root, 'Components/RecyclerListView.java'), 'utf8');

function member(source, signature) {
    const start = source.indexOf(signature);
    assert(start >= 0, signature);
    const tokens = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[{}]/g;
    tokens.lastIndex = source.indexOf('{', start);
    let depth = 0;
    for (let m; (m = tokens.exec(source));) {
        if (m[0] === '{') depth++;
        if (m[0] === '}' && --depth === 0) return source.slice(start, tokens.lastIndex);
    }
    throw new Error(signature);
}

assert(cell.includes('setBackground(new SectionBackground('));
assert(member(list, 'private View getSectionHeaderView(').includes('setSectionHeaderOwner(this)'));
assert(member(list, 'public void disableSections()').includes('invalidateSectionHeaders()'));
assert(list.includes('addItemDecoration(sectionsItemDecoration = new ListSectionsDecoration(this, isSectionView, padding, topPadding));\n        invalidateSectionHeaders();'));
assert(list.includes('pinnedHeaderShadowDrawable != null && !(hasSections() && pinnedHeader instanceof GraySectionCell)'));
assert(member(list, 'private void invalidateSectionHeaders()').includes('pinnedHeader.invalidate()'));
assert(!member(cell, 'private class SectionBackground').includes('clipRect'), 'background must not clip text or actions');

const harness = `
import java.lang.ref.WeakReference;
public class SectionHeaderHarness {
    static class PixelFormat { static final int TRANSLUCENT = -3; }
    static class Rect {
        int left, top, right, bottom;
        int width() { return right-left; }
        int height() { return bottom-top; }
    }
    static class Paint {
        static final int ANTI_ALIAS_FLAG = 1;
        int color; Object filter;
        Paint(int flags) {}
        void setColor(int c) { color = c; }
        void setColorFilter(Object f) { filter = f; }
    }
    static class Canvas {
        int flat, rounded, color; float left, right, radius;
        void drawRoundRect(float l, float t, float r, float b, float rx, float ry, Paint p) {
            rounded++; left=l; right=r; radius=rx; color=p.color;
            check(l<=r && t<=b && rx>=0 && rx<=Math.min(r-l,b-t)/2, "invalid rounded bounds");
        }
    }
    static class ColorDrawable {
        int color; Rect bounds = new Rect();
        ColorDrawable(int c) { color=c; }
        public void draw(Canvas c) { c.flat++; c.color=color; }
        public int getOpacity() { return 0; }
        int getColor() { return color; }
        Object getColorFilter() { return null; }
        Rect getBounds() { return bounds; }
    }
    static class View {
        Object parent; ColorDrawable background;
        Object getParent() { return parent; }
        void invalidate() {}
        protected void onAttachedToWindow() {}
        public void setBackgroundColor(int color) { background.color=color; }
    }
    static class RecyclerListView {
        boolean sections; int padding; float radius;
        boolean hasSections() { return sections; }
        int getSectionPadding() { return padding; }
        float getSectionRadius() { return radius; }
    }
    static class Cell extends View {
        boolean noBackground;
        WeakReference<RecyclerListView> sectionHeaderOwner;
        ${member(cell, 'public void setSectionHeaderOwner(')}
        ${member(cell, 'protected void onAttachedToWindow()')}
        ${member(cell, 'public void setBackgroundColor(')}
        ${member(cell, 'private class SectionBackground')}
        Cell() { background = new SectionBackground(0xffeeeeee); }
        Canvas draw(int width) {
            background.bounds.right=width; background.bounds.bottom=32;
            Canvas canvas=new Canvas(); background.draw(canvas); return canvas;
        }
    }
    static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        int cases=0;
        for(int width:new int[]{0,1,24,320,720,1080,2400}) {
            for(int padding:new int[]{0,12,36}) {
                for(int color:new int[]{0xffeeeeee,0xff181820,0x80abcdef}) {
                    Cell cell=new Cell();
                    cell.setBackgroundColor(color);
                    check(cell.draw(width).flat==1,"standalone header changed");
                    RecyclerListView owner=new RecyclerListView();
                    owner.padding=padding; owner.radius=16;
                    cell.parent=owner;
                    check(cell.draw(width).flat==1,"flat row changed");
                    owner.sections=true;
                    Canvas row=cell.draw(width);
                    check(row.flat==0 && row.rounded==0,"full-width strip in rounded list");
                    cell.parent=null; cell.setSectionHeaderOwner(owner);
                    Canvas pinned=cell.draw(width);
                    check(pinned.rounded==1 && pinned.flat==0 && pinned.color==color,"unreadable pinned header");
                    check(pinned.left==Math.min(padding,width/2f) && pinned.right==width-pinned.left,"wrong insets");
                    owner.sections=false;
                    check(cell.draw(width).flat==1,"flat header not restored");
                    owner.sections=true;
                    cell.noBackground=true; cell.setBackgroundColor(0xffffffff);
                    check(cell.background.color==0,"theme change resurrects hidden background");
                    check(cell.draw(width).rounded==0 && cell.draw(width).flat==0,"hidden pinned background");
                    cell.noBackground=false; cell.setBackgroundColor(color);
                    cell.parent=new Object();
                    check(cell.draw(width).flat==1,"stale owner leaked into non-list container");
                    cell.onAttachedToWindow();
                    check(cell.sectionHeaderOwner==null,"recycled cell retained old owner");
                    check(cell.background.getOpacity()==PixelFormat.TRANSLUCENT,"background incorrectly opaque");
                    cases++;
                }
            }
        }
        System.out.println("Section headers: "+cases+" scenarios passed (host drawing harness)");
    }
}
`;
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'section-headers-'));
try {
    const java = path.join(tmp, 'SectionHeaderHarness.java');
    fs.writeFileSync(java, harness);
    cp.execFileSync('javac', [java], {stdio:'inherit'});
    cp.execFileSync('java', ['-cp', tmp, 'SectionHeaderHarness'], {stdio:'inherit'});
    fs.writeFileSync(java, harness.replace('!owner.hasSections()', 'true'));
    cp.execFileSync('javac', [java], {stdio:'inherit'});
    const negative = cp.spawnSync('java', ['-cp', tmp, 'SectionHeaderHarness'], {encoding:'utf8'});
    assert.notEqual(negative.status, 0, 'old full-width background must fail');
    assert(negative.stderr.includes('full-width strip in rounded list'));
} finally {
    fs.rmSync(tmp, {recursive:true, force:true});
}
