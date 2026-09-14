// Run with node; requires javac/java. Executes production Java getter bodies,
// not JS translations. Geometry fixtures model the priority -100 leading Slot.
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java/org/telegram/ui');
const panel = fs.readFileSync(path.join(root, 'Components/DialogsActivityTopPanelLayout.java'), 'utf8');
const animated = fs.readFileSync(path.join(root, 'Components/AnimatedLinearLayout.java'), 'utf8');
const dialogs = fs.readFileSync(path.join(root, 'DialogsActivity.java'), 'utf8');

function method(source, signature) {
    const start = source.indexOf(signature);
    assert.ok(start >= 0, `Missing ${signature}`);
    const open = source.indexOf('{', start);
    let depth = 1, end = open + 1;
    for (; end < source.length && depth; end++) {
        if (source[end] === '{') depth++;
        if (source[end] === '}') depth--;
    }
    assert.equal(depth, 0, `Unclosed ${signature}`);
    return source.slice(start, end);
}

const height = method(panel, 'public float getSharedContentHeight(');
const visibility = method(panel, 'public float getSharedContentVisibility(');
const position = method(dialogs, 'private void updateContextViewPosition(');
const inputs = position.match(/topPanelsVisibility = topPanelLayout\.[^;]+;\s*topPanelsHeight = topPanelLayout\.[^;]+;/);
assert.ok(inputs, 'Missing host fade inputs');
const fade = method(position, 'if (topBubblesFadeView != null)');
assert.match(inputs[0], /getSharedContentVisibility\(\)/);
assert.match(inputs[0], /getSharedContentHeight\(\)/);
// Shared metrics must not replace the total metrics used for list/layout space.
assert.equal((dialogs.match(/\.getSharedContentHeight\(/g) || []).length, 1);
assert.equal((dialogs.match(/\.getSharedContentVisibility\(/g) || []).length, 1);
assert.match(dialogs, /topPanelLayout\.getAnimatedHeightWithPadding\(/);

const java = `
import java.util.*;
interface IndependentPanel { float getLayoutCoverage(); }
class NotificationSlot implements IndependentPanel {
    float coverage;
    NotificationSlot(float c) { coverage=c; }
    public float getLayoutCoverage() { return coverage; }
}
class Holder { Object view; Holder(Object v) { view=v; } }
class Rect { float top; Rect(float t) { top=t; } }
class ListAnimator {
    static class Entry<T> {
        T item; float visibility; Rect rect; boolean affectingList=true;
        Entry(T i,float v,float t) { item=i; visibility=v; rect=new Rect(t); }
        float getVisibility() { return visibility; }
        boolean isAffectingList() { return affectingList; }
        Rect getRectF() { return rect; }
    }
}
class Metadata {
    float height, visibility;
    float getTotalHeight() { return height; }
    float getTotalVisibility() { return visibility; }
}
class Fade {
    float y,start,height,alpha;
    void setTranslationY(float v) { y=v; }
    void setPosition(float s,float h) { start=s; height=h; }
    void setAlpha(float a) { alpha=a; }
}
public class HostFadeTest {
    ArrayList<ListAnimator.Entry<Holder>> listAnimator=new ArrayList<>();
    Metadata data=new Metadata();
    float density=1;
    Metadata getMetadata() { return data; }
    int getEntriesCount() { return listAnimator.size(); }
    ListAnimator.Entry<Holder> getEntry(int i) { return listAnimator.get(i); }
    int dp(float v) { return (int)Math.ceil(v*density); }
    float lerp(float a,float b,float f) { return a+(b-a)*f; }
    ${method(animated, 'protected float getSharedBackgroundOffset(')}
    ${method(animated, 'public float getLayoutVisibility(')}
    ${method(animated, 'public float getAnimatedHeightWithPadding(float padding)')}
    ${height}
    ${visibility}
    Fade fade(float tabs) {
        HostFadeTest topPanelLayout=this;
        float topPanelsVisibility,topPanelsHeight;
        ${inputs[0]}
        float filtersTabVisibility=tabs,filtersTabHeight=dp(36+7)*tabs;
        float fadeViewT=17*density,searchOffset=4*density;
        Fade topBubblesFadeView=new Fade();
        ${fade}
        return topBubblesFadeView;
    }
    void entry(Object view,float v,float top) {
        listAnimator.add(new ListAnimator.Entry<>(new Holder(view),v,top));
    }
    void entry(Object view,float v,float top,boolean affecting) {
        entry(view,v,top);
        listAnimator.get(listAnimator.size()-1).affectingList=affecting;
    }
    // Native panels stay at their chosen animation state while the independent
    // leading Slot arrives/retires. Metadata and entry positions move together.
    static HostFadeTest fixture(float d,int[] natives,float nv,boolean slot,float f,float coverage,int card) {
        HostFadeTest t=new HostFadeTest(); t.density=d;
        float offset=slot?card*d*f:0;
        if(slot) t.entry(new NotificationSlot(coverage),f,0);
        float h=0;
        for(int n:natives) { t.entry(new Object(),nv,offset+h); h+=n*d*nv; }
        t.data.height=offset+h;
        t.data.visibility=Math.max(natives.length==0?0:nv,slot?f:0);
        return t;
    }
    static int checks;
    static void equal(float actual,float expected,String label) {
        checks++;
        if(!Float.isFinite(actual)||Math.abs(actual-expected)>.002f)
            throw new AssertionError(label+": "+actual+" != "+expected);
    }
    static void sameFade(Fade a,Fade b) {
        equal(a.y,b.y,"translation"); equal(a.start,b.start,"start");
        equal(a.height,b.height,"length"); equal(a.alpha,b.alpha,"alpha");
        if(a.height<0) throw new AssertionError("negative fade length");
    }
    public static void main(String[] args) {
        // Empty, player, call, and simultaneous native panels. Fractions run
        // forward and backward, including a zero-visibility retained entry.
        int[][] configurations={{},{36},{48},{36,48}};
        float[] arrival={0,.001f,.05f,.17f,.5f,.9f,1,.9f,.5f,.17f,.05f,.001f,0};
        for(float d:new float[]{1,1.5f,2.75f,3,4})
        for(int[] natives:configurations)
        for(float nv:new float[]{.125f,.5f,1})
        for(float tabs:new float[]{0,.5f,1}) {
            HostFadeTest base=fixture(d,natives,nv,false,0,1,68);
            float expectedHeight=0;
            for(int n:natives) expectedHeight+=n*d*nv;
            equal(base.getSharedContentHeight(),expectedHeight,"native height baseline");
            equal(base.getSharedContentVisibility(),natives.length==0?0:nv,"native visibility baseline");
            for(int card:new int[]{1,68,240})
            for(float coverage:new float[]{0,.25f,1})
            for(float f:arrival) {
                HostFadeTest t=fixture(d,natives,nv,true,f,coverage,card);
                equal(t.getSharedContentHeight(),base.getSharedContentHeight(),"notification-independent height");
                equal(t.getSharedContentVisibility(),base.getSharedContentVisibility(),
                    "notification-independent visibility (nativeCount="+natives.length+
                    ", nativeFraction="+nv+", slotFraction="+f+", coverage="+coverage+")");
                sameFade(t.fade(tabs),base.fade(tabs));
            }
            sameFade(fixture(d,natives,nv,false,0,1,68).fade(tabs),base.fade(tabs));
        }
        HostFadeTest empty=fixture(1,new int[]{},1,false,0,1,68);
        equal(empty.fade(0).height,0,"empty clamp");
        equal(empty.fade(0).alpha,0,"no folders/native: invisible");
        equal(empty.fade(1).start,7,"folders-only start");
        equal(empty.fade(1).height,36,"folders-only length");
        equal(empty.fade(1).alpha,1,"folders-only alpha");
        // Each group's max is independent of aggregate metadata, including
        // zero-visibility retiring entries and unequal native fractions.
        HostFadeTest mixed=new HostFadeTest();
        mixed.entry(new NotificationSlot(1),1,0);
        mixed.entry(new Object(),.2f,68); mixed.entry(new Object(),.3f,80);
        mixed.entry(new Object(),0,90,false); mixed.data.height=100; mixed.data.visibility=1;
        for(float metadata:new float[]{0,.1f,.4f,1}) {
            mixed.data.visibility=metadata;
            equal(mixed.getSharedContentVisibility(),.3f,"max native fraction; metadata ignored");
        }
        mixed.entry(new Object(),.4f,90,false);
        mixed.entry(new Object(),.1f,95,false);
        equal(mixed.getSharedContentVisibility(),.7f,"sum group maxima, not all entries");
        mixed.entry(new Object(),.9f,96,false);
        equal(mixed.getSharedContentVisibility(),1,"group sum capped at one");
        // Complementary appearing/retiring groups must hold visibility and
        // host fade geometry constant, with or without a persistent player.
        for(boolean player:new boolean[]{false,true})
        for(float f:new float[]{0,.125f,.25f,.5f,.75f,.875f,1})
        for(float tabs:new float[]{0,.5f,1}) {
            HostFadeTest nativeOnly=new HostFadeTest();
            nativeOnly.entry(new Object(),1-f,0,false);
            nativeOnly.entry(new Object(),f,0);
            if(player) nativeOnly.entry(new Object(),1,48);
            nativeOnly.data.height=player?84:48;
            nativeOnly.data.visibility=1;
            float expected=1;
            equal(nativeOnly.getSharedContentVisibility(),expected,"native group crossfade stays one");
            equal(nativeOnly.fade(tabs).alpha,Math.max(tabs,expected),"crossfade host alpha");
            HostFadeTest settled=fixture(1,player?new int[]{48,36}:new int[]{48},1,false,0,1,68);
            sameFade(nativeOnly.fade(tabs),settled.fade(tabs));
            for(boolean notificationAffecting:new boolean[]{false,true})
            for(float notification:new float[]{0,.2f,.7f,1}) {
                HostFadeTest withSlot=new HostFadeTest();
                float offset=68*notification;
                withSlot.entry(new NotificationSlot(1),notification,0,notificationAffecting);
                for(ListAnimator.Entry<Holder> e:nativeOnly.listAnimator)
                    withSlot.entry(e.item.view,e.visibility,e.rect.top+offset,e.isAffectingList());
                withSlot.data.height=nativeOnly.data.height+offset;
                withSlot.data.visibility=1;
                equal(withSlot.getSharedContentVisibility(),expected,"crossfade excludes notification");
                equal(withSlot.getSharedContentHeight(),nativeOnly.getSharedContentHeight(),"crossfade native height");
                sameFade(withSlot.fade(tabs),nativeOnly.fade(tabs));
            }
        }
        System.out.println("PASS: "+checks+" host fade checks; folders on/off/partial, player/call, entry/retirement, coverage, densities and clamp");
    }
}
`;

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'notification-host-fade-'));
function run(source) {
    fs.writeFileSync(path.join(dir, 'HostFadeTest.java'), source);
    cp.execFileSync('javac', ['HostFadeTest.java'], {cwd: dir, stdio: 'pipe'});
    return cp.spawnSync('java', ['HostFadeTest'], {cwd: dir, encoding: 'utf8'});
}
try {
    const result = run(java);
    assert.equal(result.status, 0, result.stderr);
    process.stdout.write(result.stdout);
    const mutations = [
        ['old aggregate fade inputs', inputs[0], 'topPanelsVisibility = topPanelLayout.getLayoutVisibility(); topPanelsHeight = topPanelLayout.getAnimatedHeightWithPadding(0);'],
        ['height includes notification', height, 'public float getSharedContentHeight() { return getMetadata().getTotalHeight(); }'],
        ['visibility includes notification', visibility, 'public float getSharedContentVisibility() { return getLayoutVisibility(); }'],
        ['single max pulses during native crossfade', visibility, 'public float getSharedContentVisibility() { float v=0; for(var e:listAnimator) if(!(e.item.view instanceof IndependentPanel)) v=Math.max(v,e.getVisibility()); return v; }'],
        ['missing nonnegative clamp', 'Math.max(0f, Math.min(dp(40), topPanelsHeight + filtersTabHeight - s))', 'Math.min(dp(40), topPanelsHeight + filtersTabHeight - s)'],
    ];
    for (const [label, before, after] of mutations) {
        assert.ok(java.includes(before), `Negative control no longer matches: ${label}`);
        const bad = run(java.replace(before, after));
        assert.notEqual(bad.status, 0, `Regression escaped: ${label}`);
        assert.match(bad.stderr, /AssertionError/, bad.stderr);
        console.log(`PASS: negative control rejected: ${label}`);
    }
} finally {
    fs.rmSync(dir, {recursive: true, force: true});
}
console.log('Scope: getter/fade arithmetic and wiring, not Android GPU rendering or native-panel spatial alignment.');
