'use strict';

// Host-only test: extract production methods afresh; Android drawing/animation is stubbed.
// Run directly with node. Generated Java and all mutations live only in a temporary directory.
const assert = require('node:assert/strict');
const cp = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const read = relative => fs.readFileSync(path.resolve(__dirname, '../../main/java', relative), 'utf8');
const notifications = read('app/nimarkogram/messenger/notifications/NimarkoInAppNotifications.java');
const panels = read('org/telegram/ui/Components/AnimatedLinearLayout.java');

function extract(source, signature) {
    const start = source.indexOf(signature);
    assert(start >= 0, `Missing production declaration: ${signature}`);
    // Ignore braces in comments and literals, including log strings in show().
    const tokens = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[{}]/g;
    tokens.lastIndex = source.indexOf('{', start);
    let depth = 0;
    for (let token; (token = tokens.exec(source));) {
        if (token[0] === '{') depth++;
        if (token[0] === '}' && --depth === 0) return source.slice(start, tokens.lastIndex);
    }
    throw new Error(`Unterminated production declaration: ${signature}`);
}

const slot = extract(notifications, 'private static final class Slot');
// Deliberately the FIRST onMeasure, not Banner.onMeasure farther down the same file.
const measure = extract(notifications, 'protected void onMeasure(');
assert.equal(measure, extract(slot, 'protected void onMeasure('), 'First onMeasure must belong to Slot');
const background = extract(panels, 'protected float getSharedBackgroundOffset()');
const show = extract(notifications, 'private static boolean show(Banner next)');
const slotMethods = ['static Slot obtain(', 'void attach(', 'void release(', 'public float getLayoutCoverage(', 'public int getCompactHeight(', 'public float getCompactVisibleHeight(']
    .map(signature => extract(slot, signature)).join('\n');
const lifecycle = ['private static void removeCurrent()', 'private static void remove(Banner old)']
    .map(signature => extract(notifications, signature)).join('\n');

function harness(measureMethod = measure, backgroundMethod = background, showMethod = show) {
    return `import java.util.*;
import java.lang.ref.WeakReference;

class View {
    static final int VISIBLE=0, INVISIBLE=4, GONE=8, IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS=4;
    int visibility=VISIBLE, measuredWidth, measuredHeight, minimumHeight, desiredHeight;
    float translationY; ViewGroup parent;
    static class MeasureSpec {
        static final int EXACTLY=0x40000000, AT_MOST=0x80000000, UNSPECIFIED=0;
        static int makeMeasureSpec(int size,int mode){return size | mode;}
        static int getSize(int spec){return spec & 0x3fffffff;}
    }
    Object getContext(){return this;}
    int getVisibility(){return visibility;} void setVisibility(int value){visibility=value;}
    int getMeasuredHeight(){return measuredHeight;} int getMeasuredWidth(){return measuredWidth;}
    int getHeight(){return measuredHeight;} int getMinimumHeight(){return minimumHeight;}
    void setMinimumHeight(int value){minimumHeight=value;}
    void setMeasuredDimension(int width,int height){measuredWidth=width;measuredHeight=height;}
    protected void onMeasure(int width,int height){setMeasuredDimension(MeasureSpec.getSize(width),desiredHeight);}
    void measure(int width,int height){onMeasure(width,height);}
    ViewGroup getParent(){return parent;} boolean isAttachedToWindow(){return true;}
    void setPressed(boolean value){} void setImportantForAccessibility(int value){}
    void setAlpha(float value){} void setScaleX(float value){} void setScaleY(float value){}
    void setTranslationY(float value){translationY=value;}
    void removeCallbacks(Runnable runnable){} void postDelayed(Runnable runnable,long delay){}
    void postOnAnimation(Runnable runnable){} // Geometry tests never advance the entrance animation.
    final Animation animation=new Animation(); Animation animate(){return animation;}
}
class Animation {
    Runnable end;
    void cancel(){} // Explicit finish()/saved callbacks control retiring animation completion.
    Animation alpha(float value){return this;} Animation translationY(float value){return this;}
    Animation scaleX(float value){return this;} Animation scaleY(float value){return this;}
    Animation setDuration(long value){return this;} Animation setInterpolator(Object value){return this;}
    Animation withEndAction(Runnable action){end=action;return this;} void start(){}
    void finish(){Runnable action=end;end=null;if(action!=null)action.run();}
}
class ViewGroup extends View {
    final ArrayList<View> children=new ArrayList<>();
    void addView(View child,Object params){
        if(child.parent!=null)throw new AssertionError("child already attached");
        children.add(child);child.parent=this;
    }
    void removeView(View child){if(children.remove(child))child.parent=null;}
    int getChildCount(){return children.size();} View getChildAt(int index){return children.get(index);}
}
class FrameLayout extends ViewGroup {
    FrameLayout(Object context){}
    protected void onMeasure(int width,int height){
        int tallest=getMinimumHeight();
        for(View child:children)if(child.getVisibility()!=GONE){
            child.measure(width,MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
            tallest=Math.max(tallest,child.getMeasuredHeight());
        }
        setMeasuredDimension(MeasureSpec.getSize(width),tallest);
    }
}
class LayoutHelper {
    static final int MATCH_PARENT=-1, WRAP_CONTENT=-2;
    static Object createFrame(int width,int height,int gravity){return new Object();}
}
class Gravity {static final int TOP=1, CENTER_HORIZONTAL=2;}
class CubicBezierInterpolator {static final Object EASE_BOTH=new Object(), Emphasized=new Object();}
class SystemClock {static long elapsedRealtime(){return 1000;}}
class RectF {float top,bottom;RectF(float top,float bottom){this.top=top;this.bottom=bottom;}}
class ListAnimator<T> extends ArrayList<ListAnimator.Entry<T>> {
    static class Entry<T> {
        final T item;float visibility;final RectF rect;
        Entry(T item,float visibility,float top,float bottom){this.item=item;this.visibility=visibility;rect=new RectF(top,bottom);}
        float getVisibility(){return visibility;} RectF getRectF(){return rect;}
    }
}
class AnimatedLinearLayout extends FrameLayout {
    interface IndependentPanel {boolean isDirectResize();}
    static class Holder {final View view;Holder(View view){this.view=view;}}
    final ListAnimator<Holder> listAnimator=new ListAnimator<>();
    final Map<View,Boolean> requestedVisible=new IdentityHashMap<>();
    AnimatedLinearLayout(){super(null);}
    void setPriority(View view,int priority){} void setTrackChildSize(View view){}
    void setViewVisible(View view,boolean visible,boolean animated){requestedVisible.put(view,visible);}
    boolean isViewVisible(View view){return Boolean.TRUE.equals(requestedVisible.get(view));}
    ListAnimator.Entry<Holder> entry(View view,float visibility,float top,float bottom){
        ListAnimator.Entry<Holder> entry=new ListAnimator.Entry<>(new Holder(view),visibility,top,bottom);
        listAnimator.add(entry);return entry;
    }
    ${backgroundMethod}
}

public class NotificationSlotGeometryTest {
    static int checks;
    static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    static void equal(float actual,float expected,String reason){
        check(Math.abs(actual-expected)<0.0001f,reason+": expected "+expected+", got "+actual);
    }
    static int dp(int value){return value;}
    static class LaunchActivity {}
    static class NotificationColorTrace {static Object startPreview(Object view){return new Object();}}
    static class Delivery {boolean isActive(){return true;}}
    static WeakReference<LaunchActivity> host=new WeakReference<>(new LaunchActivity());
    static AnimatedLinearLayout currentPanel;
    static boolean contentGesture;
    static Banner banner,retiringBanner;
    static AnimatedLinearLayout resolvePanel(Banner value){return currentPanel;}
    static boolean isCurrent(int account,long owner,long session){return true;}
    static boolean allowed(int account,long owner,long dialog,boolean sample){return true;}
    static class Slot extends FrameLayout implements AnimatedLinearLayout.IndependentPanel {
        final AnimatedLinearLayout panel;
        boolean directResize;
        float retainedCoverage = 1f;
        int retainedCompactHeight;
        float retainedCompactVisibleHeight;
        public boolean isDirectResize(){return directResize;}
        // Constructor-only Android wiring shim; geometry/lifecycle below is extracted verbatim.
        Slot(AnimatedLinearLayout panel){super(panel.getContext());this.panel=panel;panel.addView(this,null);}
        ${slotMethods}
        ${measureMethod}
    }
    static class Banner extends View {
        Object previewTrace;
        float pullOffset;Slot slot;Delivery delivery;
        int collapsedHeight = 68;
        boolean closing,opening,touching,sample;int account;long owner,loginSession,dialogId,expiresAt;
        final Runnable watch=()->{};
        Banner(int height,float pull){desiredHeight=height;pullOffset=pull;}
        void tracePreview(String stage){} void cancelExpansion(){} void cancelContentTransition(){}
    }
    ${showMethod}
    ${lifecycle}

    static void measure(Slot slot,int width){
        slot.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(4096,View.MeasureSpec.AT_MOST));
        equal(slot.getMeasuredWidth(),width,"slot preserves measured width");
    }
    static void geometry(){
        for(int width:new int[]{240,360,720})for(int height:new int[]{64,101,260}){
            AnimatedLinearLayout panel=new AnimatedLinearLayout();Slot slot=Slot.obtain(panel);
            check(Slot.obtain(panel)==slot&&panel.getChildCount()==1,"one slot per panel");
            Banner card=new Banner(height,0);slot.attach(card);measure(slot,width);
            equal(slot.getMeasuredHeight(),height,"resting card height");
            for(float pull:new float[]{0.49f,0.5f,1.6f,17,48.75f}){
                card.pullOffset=pull;measure(slot,width);
                equal(slot.getMeasuredHeight(),height+Math.round(pull),"positive pull reserves shifted bottom");
            }
            // Entrance translation is decoration, not the pull reservation.
            card.pullOffset=0;card.setTranslationY(-8);measure(slot,width);
            equal(slot.getMeasuredHeight(),height,"entrance translation does not collapse slot");
            for(float pull:new float[]{-0.49f,-0.5f,-1.6f,-17,-height,-height-80}){
                card.pullOffset=pull;measure(slot,width);
                equal(slot.getMeasuredHeight(),Math.max(0,height+Math.round(pull)),"negative pull collapses without negative height");
                check(Math.abs(slot.getLayoutCoverage()-Math.max(0,Math.min(1,(height+pull)/height)))<.0001f,"spacing follows the same unrounded visible fraction");
            }
            card.pullOffset=23;measure(slot,width);int retained=slot.getMeasuredHeight();
            slot.release(card);measure(slot,width);
            equal(slot.getMinimumHeight(),retained,"last release retains minimum");
            equal(slot.getMeasuredHeight(),retained,"empty retiring slot reserves last height");
            check(Boolean.FALSE.equals(panel.requestedVisible.get(slot)),"empty slot requests animated removal");
            Banner replacement=new Banner(40,-5);slot.attach(replacement);measure(slot,width);
            equal(slot.getMinimumHeight(),0,"attach clears retained minimum");
            equal(slot.getMeasuredHeight(),35,"replacement may shrink retained reservation");
            slot.setMinimumHeight(50);measure(slot,width);
            equal(slot.getMeasuredHeight(),50,"minimum is a floor even with a child");
        }
        Slot slot=Slot.obtain(new AnimatedLinearLayout());
        Banner first=new Banner(80,20),second=new Banner(130,-10);slot.attach(first);slot.attach(second);
        measure(slot,360);equal(slot.getMeasuredHeight(),120,"overlap reserves max bottom not sum");
        second.setVisibility(View.GONE);measure(slot,360);
        equal(slot.getMeasuredHeight(),100,"GONE retiring child does not reserve space");
        second.setVisibility(View.INVISIBLE);measure(slot,360);
        equal(slot.getMeasuredHeight(),120,"INVISIBLE child still participates in measurement");
        slot.release(first);measure(slot,360);
        equal(slot.getMinimumHeight(),0,"removing one of two does not pin minimum");
        second.pullOffset=-second.getMeasuredHeight();
        check(slot.getLayoutCoverage()==0,"fully dismissed card no longer reserves padding");
        slot.release(second);measure(slot,360);
        check(slot.getLayoutCoverage()==0&&slot.getMinimumHeight()==0,"detached empty slot keeps zero coverage during native cleanup");
    }
    static void spacing(){
        AnimatedLinearLayout panel=new AnimatedLinearLayout();Slot slot=Slot.obtain(panel);
        View pinned=new View(),translation=new View();panel.addView(pinned,null);panel.addView(translation,null);
        Banner card=new Banner(80,0);slot.attach(card);
        measure(slot,360);equal(slot.getMeasuredHeight(),80,"hidden panels add no gap");
        panel.setViewVisible(pinned,true,true);
        measure(slot,360);equal(slot.getMeasuredHeight(),88,"visible pinned panel adds gap");
        panel.setViewVisible(translation,true,true);
        measure(slot,360);equal(slot.getMeasuredHeight(),88,"multiple shared panels add only one gap");
        for(int pull=0;pull>=-80;pull--){
            card.pullOffset=pull;measure(slot,360);
            equal(slot.getMeasuredHeight(),80+pull+Math.round(8*(80+pull)/80f),"gap collapses with swipe coverage");
        }
        slot.release(card);measure(slot,360);
        equal(slot.getMeasuredHeight(),0,"fully swiped notification leaves no gap");
        card=new Banner(80,0);slot.attach(card);measure(slot,360);
        slot.release(card);measure(slot,360);equal(slot.getMeasuredHeight(),88,"animated removal retains gap exactly once");
        measure(slot,360);equal(slot.getMeasuredHeight(),88,"repeated removal measurement cannot accumulate gap");
        slot.attach(card);measure(slot,360);equal(slot.getMeasuredHeight(),88,"reattachment resets retained spacing");
        Banner next=new Banner(64,0);slot.attach(next);measure(slot,360);
        equal(slot.getMeasuredHeight(),88,"overlapping notifications share one gap");
        slot.release(card);measure(slot,360);equal(slot.getMeasuredHeight(),72,"replacement uses its own height plus gap");
        panel.setViewVisible(pinned,false,true);panel.setViewVisible(translation,false,true);
        measure(slot,360);equal(slot.getMeasuredHeight(),64,"hiding shared panels removes gap");
        Slot other=Slot.obtain(new AnimatedLinearLayout());
        other.attach(new Banner(64,0));measure(other,360);equal(other.getMeasuredHeight(),64,"standalone notification has no extra gap");
    }
    static void replacement(){
        currentPanel=new AnimatedLinearLayout();banner=retiringBanner=null;
        check(show(new Banner(80,24)),"first show accepted");Slot slot=banner.slot;measure(slot,360);
        Banner first=banner;check(show(new Banner(64,0)),"second show accepted");
        measure(slot,360);equal(slot.getMeasuredHeight(),104,"retiring pulled card keeps shifted reservation");
        Runnable staleEnd=first.animate().end;
        check(staleEnd!=null,"replacement uses actual retirement callback");
        for(int i=0;i<12;i++){
            Banner displaced=retiringBanner;
            check(show(new Banner(70+i,i-4)),"burst replacement accepted");
            check(slot.getChildCount()==2,"replacement retains at most two children");
            check(displaced.getParent()==null,"older retiring child detached before replacement");
            measure(slot,360);
            equal(slot.getMeasuredHeight(),Math.max(Math.max(0,banner.desiredHeight+Math.round(banner.pullOffset)),
                    Math.max(0,retiringBanner.desiredHeight+Math.round(retiringBanner.pullOffset))),"burst uses current plus retiring max");
        }
        Banner current=banner,retiring=retiringBanner;staleEnd.run();
        check(banner==current&&retiringBanner==retiring&&slot.getChildCount()==2,"stale retirement cannot remove replacement");
        retiring.animate().finish();measure(slot,360);
        check(slot.getChildCount()==1&&retiringBanner==null,"retirement finishes with one child");
        equal(slot.getMeasuredHeight(),current.desiredHeight+Math.round(current.pullOffset),"reservation shrinks after retirement");
        int retained=slot.getMeasuredHeight();removeCurrent();measure(slot,360);
        check(slot.getChildCount()==0,"removeCurrent detaches both references");
        equal(slot.getMeasuredHeight(),retained,"final cleanup retains outgoing geometry");
    }
    static void backgrounds(){
        AnimatedLinearLayout panel=new AnimatedLinearLayout();
        equal(panel.getSharedBackgroundOffset(),0,"no slot and no panels starts at zero");
        View shared=new View();ListAnimator.Entry<AnimatedLinearLayout.Holder> nativeEntry=panel.entry(shared,1,12,200);
        equal(panel.getSharedBackgroundOffset(),0,"native panels without slot start at zero");
        panel.listAnimator.clear();Slot slot=Slot.obtain(panel);
        ListAnimator.Entry<AnimatedLinearLayout.Holder> own=panel.entry(slot,1,0,104.5f);
        equal(panel.getSharedBackgroundOffset(),-1,"own-only slot has no shared background");
        nativeEntry=panel.entry(shared,1,89.25f,220);
        equal(panel.getSharedBackgroundOffset(),89.25f,"shared background follows current native entry top, not slot bottom");
        // The outgoing slot retains its full height while the music panel moves upward.
        // Its animator rect bottom is deliberately fixed and different from nativeEntry.top.
        own.visibility=0.5f;slot.setVisibility(View.GONE);
        for(float top:new float[]{80,64.5f,32,12.25f,0}){
            nativeEntry.rect.top=top;
            equal(panel.getSharedBackgroundOffset(),top,"retained notification removal follows music panel upward");
        }
        own.visibility=0;
        equal(panel.getSharedBackgroundOffset(),0,"no jump when independent entry finishes retiring at native top zero");
        nativeEntry.rect.top=71.25f;own.rect.bottom=260;own.visibility=0.01f;
        equal(panel.getSharedBackgroundOffset(),71.25f,"retiring slot follows animator visibility not View visibility");
        own.visibility=0;equal(panel.getSharedBackgroundOffset(),0,"fully retired slot no longer offsets native background");
        own.visibility=-0.1f;equal(panel.getSharedBackgroundOffset(),0,"nonpositive animator entry ignored");
        own.visibility=1;nativeEntry.visibility=0.01f;shared.setVisibility(View.GONE);
        equal(panel.getSharedBackgroundOffset(),71.25f,"retiring shared panel still needs background");
        nativeEntry.visibility=0;equal(panel.getSharedBackgroundOffset(),-1,"own-only after shared retirement hides background");
        own.visibility=0;equal(panel.getSharedBackgroundOffset(),0,"all entries retired resets background");
        own.visibility=0.5f;nativeEntry.visibility=1;
        Slot other=new Slot(panel);panel.entry(other,0.2f,10,400);
        equal(panel.getSharedBackgroundOffset(),71.25f,"independent bottoms and fractional visibility do not scale native offset");
        ListAnimator.Entry<AnimatedLinearLayout.Holder> secondNative=panel.entry(new View(),0.2f,32.5f,90);
        equal(panel.getSharedBackgroundOffset(),32.5f,"multiple shared panels use minimum current top");
        Collections.reverse(panel.listAnimator);
        equal(panel.getSharedBackgroundOffset(),32.5f,"minimum shared top is independent of entry order");
        secondNative.visibility=0;
        equal(panel.getSharedBackgroundOffset(),71.25f,"fully retired shared top is ignored");
        secondNative.visibility=-0.1f;secondNative.rect.top=-100;
        equal(panel.getSharedBackgroundOffset(),71.25f,"nonpositive shared visibility cannot affect offset");
        secondNative.visibility=0.01f;secondNative.rect.top=-4;
        equal(panel.getSharedBackgroundOffset(),0,"negative current shared top clamps to zero");
    }
    public static void main(String[] args){
        geometry();spacing();replacement();backgrounds();
        System.out.println("PASS: "+checks+" extracted slot geometry, replacement and shared-background checks");
    }
}
`;
}

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-notification-slot-geometry-'));
try {
    function run(java) {
        fs.writeFileSync(path.join(dir, 'NotificationSlotGeometryTest.java'), java);
        const compiled = cp.spawnSync('javac', ['-encoding', 'UTF-8', 'NotificationSlotGeometryTest.java'],
            {cwd: dir, encoding: 'utf8', timeout: 30000});
        assert.equal(compiled.status, 0, `javac must succeed (also for negative controls): ${compiled.error || compiled.stderr}`);
        return cp.spawnSync('java', ['-cp', dir, 'NotificationSlotGeometryTest'],
            {cwd: dir, encoding: 'utf8', timeout: 10000});
    }
    const result = run(harness());
    assert.equal(result.status, 0, result.error || result.stderr);
    process.stdout.write(result.stdout);

    // Restore the old constant-height behavior: keep all real code except the pull term.
    const constant = measure.replace(/\s*\+\s*Math\.round\(child\.pullOffset\)/g, '');
    assert.notEqual(constant, measure, 'Constant-measure mutation must affect extracted Slot.onMeasure');
    const constantResult = run(harness(constant));
    assert.equal(constantResult.status, 1, constantResult.error || constantResult.stderr);
    assert.match(constantResult.stderr, /AssertionError: positive pull reserves shifted bottom/);
    console.log('PASS: compiled old-constant-measure negative control rejected');
    const noGap = measure.replace('height += Math.round(dp(8) * getLayoutCoverage());', 'height += 0;');
    assert.notEqual(noGap, measure);
    const noGapResult = run(harness(noGap));
    assert.equal(noGapResult.status, 1, noGapResult.error || noGapResult.stderr);
    assert.match(noGapResult.stderr, /AssertionError: visible pinned panel adds gap/);
    console.log('PASS: missing shared-panel gap negative control rejected');
    const fixedGap = measure.replace('dp(8) * getLayoutCoverage()', 'dp(8) * 1f');
    assert.notEqual(fixedGap, measure);
    const fixedGapResult = run(harness(fixedGap));
    assert.equal(fixedGapResult.status, 1, fixedGapResult.error || fixedGapResult.stderr);
    assert.match(fixedGapResult.stderr, /AssertionError: gap collapses with swipe coverage/);
    console.log('PASS: fixed gap during dismissal negative control rejected');

    const zero = background.slice(0, background.indexOf('{')) + '{ return 0f; }';
    assert.notEqual(zero, background, 'Always-zero mutation must affect extracted background method');
    const zeroResult = run(harness(measure, zero));
    assert.equal(zeroResult.status, 1, zeroResult.error || zeroResult.stderr);
    assert.match(zeroResult.stderr, /AssertionError: own-only slot has no shared background/);
    console.log('PASS: compiled always-zero-background negative control rejected');

    // Previous production behavior: follow the retained independent bottom instead of
    // the shared panel's CURRENT top. It must compile but fail the no-jump geometry.
    const retainedBottom = background.slice(0, background.indexOf('{')) + `{
        float offset=0;boolean independent=false,shared=false;
        for(ListAnimator.Entry<Holder> entry:listAnimator){
            if(entry.getVisibility()<=0)continue;
            if(entry.item.view instanceof IndependentPanel){
                independent=true;offset=Math.max(offset,entry.getRectF().bottom);
            }else shared=true;
        }
        return independent&&!shared?-1:offset;
    }`;
    assert.notEqual(retainedBottom, background, 'Retained-bottom mutation must affect background method');
    const retainedBottomResult = run(harness(measure, retainedBottom));
    assert.equal(retainedBottomResult.status, 1, retainedBottomResult.error || retainedBottomResult.stderr);
    assert.match(retainedBottomResult.stderr, /AssertionError: shared background follows current native entry top, not slot bottom/);
    console.log('PASS: compiled retained-slot-bottom negative control rejected');

    const accumulating = show.replace('remove(retiringBanner);', '/* negative control: retain older child */');
    assert.notEqual(accumulating, show, 'Replacement mutation must affect extracted show');
    const replacementResult = run(harness(measure, background, accumulating));
    assert.equal(replacementResult.status, 1, replacementResult.error || replacementResult.stderr);
    assert.match(replacementResult.stderr, /AssertionError: replacement retains at most two children/);
    console.log('PASS: compiled accumulating-replacements negative control rejected');
} finally {
    fs.rmSync(dir, {recursive: true, force: true});
}
