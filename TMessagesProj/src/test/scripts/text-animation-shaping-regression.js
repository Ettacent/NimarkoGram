// Real Android text shaping, via app_process and a dex jar (no APK).
// Usage: node text-animation-shaping-regression.js emulator-5584
// Requires a dedicated API 31+ userdebug emulator (su 0 initializes system fonts).
// Android Layout/Paint/Editable are real; editor scheduling and particles are not.
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const source = fs.readFileSync(path.resolve(__dirname,
  '../../main/java/app/nimarkogram/messenger/textanim/NimarkoTextAnim.java'), 'utf8');
function block(signature) {
  const start = source.indexOf(signature);
  if (start < 0) throw new Error(signature);
  let depth = 0;
  for (let i = source.indexOf('{', start); i < source.length; i++) {
    if (source[i] === '{') depth++;
    if (source[i] === '}' && --depth === 0) return source.slice(start, i + 1);
  }
  throw new Error('Unclosed ' + signature);
}
const constants = ['APPEAR_DURATION', 'REPLACE_DURATION', 'SPOILER_DURATION',
  'BLUR_TEXT_DELAY_PCT', 'SLIDE_DIST_PX', 'BLUR_RADIUS', 'BLUR_DURATION',
  'MAX_TEXT_DIFF_WINDOW', 'MASS_DELETE_THRESHOLD', 'MAX_DELETE_GLYPHS',
  'MAX_DELETE_PARTICLES', 'MASS_INSERT_THRESHOLD', 'MAX_ANIMATING_GLYPHS'].map(name =>
  source.match(new RegExp('private static final int ' + name + ' = \\d+;'))[0]).join('\n') + '\n' +
  ['BLUR_BITMAP_MAX_BYTES','BLUR_CACHE_MAX_BYTES'].map(name =>
    source.match(new RegExp('private static final long ' + name + ' = [^;]+;'))[0]).join('\n');
const production = ['private static final class CharData',
  'private static float appearanceProgress(',
  'private static float easeOutQuint(', 'private static final class ShapedRun',
  'private static void rebuildShapedRuns(', 'private static boolean sameRunPaint(',
  'private static boolean supportsRunStyles(', 'private static final class RunStyleWatcher',
  'private static void drawShapedRuns(', 'private static long bitmapBytes(',
  'private static float glyphVisualLeft(',
  'private static final class State',
  'private static void handleTextChanged(', 'private static void updateHiddenSpan(',
  'private static void removeSpan(', 'private static void retireChar(',
  'private static void startPendingGlyphs(',
  'private static boolean canAnimateGlyph(', 'private static boolean canDiffEdit(',
  'private static boolean isClusterContinuation(', 'private static boolean hasReplacementSpan('].map(block).join('\n')
  // Fault injection at the production allocation boundary; no real OOM pressure.
  // This harness verifies shaping/ownership, not Telegram's native blur kernel.
  .replace('org.telegram.messenger.Utilities.stackBlurBitmap(blur, BLUR_RADIUS);', '')
  .replace('crisp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);',
    'if (failRaster) { allocationFailures++; throw new OutOfMemoryError("injected"); }\n'
    + 'crisp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);');
const java = `
import android.graphics.*;
import android.text.*;
import android.text.style.*;
import android.os.Build;
import java.util.*;
import java.text.Bidi;
import java.lang.ref.WeakReference;
public class TextAnimationShapingTest {
  static boolean masterEnabled=true, appearEnabled=true, spoilerEnabled=false, deleteEnabled=false;
  static boolean failRaster; static int allocationFailures;
  static class SpoilerParticle {}
  static class Particle {}
  static class SystemClock { static long now; static long uptimeMillis(){return now;} }
  static class EditText {
    final SpannableStringBuilder text=new SpannableStringBuilder();
    final TextPaint paint=new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final State state=new State();
    final DynamicLayout layout;
    EditText(float size,float spacing,Typeface font,int width) {
      paint.setTextSize(size); paint.setLetterSpacing(spacing); paint.setTypeface(font);
      paint.setColor(Color.BLACK);
      layout=DynamicLayout.Builder.obtain(text,paint,width).setIncludePad(false).build();
    }
    Editable getText(){return text;} TextPaint getPaint(){return paint;}
    Layout getLayout(){return layout;}
    int getCompoundPaddingLeft(){return 0;} int getTotalPaddingTop(){return 0;}
    void invalidate(){}
  }
  static State getState(EditText edit){return edit.state;}
  static void startAnimationLoop(EditText edit,State st){}
  static void spawnDeleteParticles(EditText edit,State st,float x,float y,String s){}
  ${constants}
  ${production}
  static int frames;
  static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
  static void close(float a,float b,String why){check(Math.abs(a-b)<.011f,why+": "+a+" != "+b);}
  static StaticLayout layout(CharSequence s,TextPaint p,int width) {
    return StaticLayout.Builder.obtain(s,0,s.length(),p,width).setIncludePad(false).build();
  }
  static void edit(EditText e,int start,int before,String added,long now) {
    SystemClock.now=now;
    State st=e.state;
    st.hasTextChange=true; st.changeStart=start; st.changeCount=before;
    st.removedText=TextUtils.substring(e.text,start,start+before);
    e.text.replace(start,start+before,added);
    handleTextChanged(e,e.text,start,before,added.length());
  }
  static void verify(EditText e,long now) { verify(e,now,false); }
  static void verify(EditText e,long now,boolean preExpiry) {
    SystemClock.now=now;
    updateHiddenSpan(e,e.state);
    SpannableStringBuilder nativeText=new SpannableStringBuilder(e.text);
    for(RunStyleWatcher span:nativeText.getSpans(0,nativeText.length(),RunStyleWatcher.class)) nativeText.removeSpan(span);
    for(ShapedRun span:nativeText.getSpans(0,nativeText.length(),ShapedRun.class)) nativeText.removeSpan(span);
    Layout plain=layout(nativeText,e.paint,e.layout.getWidth());
    check(e.layout.getLineCount()==plain.getLineCount(),"line count "+e.text);
    for(int line=0;line<plain.getLineCount();line++) {
      check(e.layout.getLineStart(line)==plain.getLineStart(line),"line break");
      check(e.layout.getLineBaseline(line)==plain.getLineBaseline(line),"baseline");
      close(e.layout.getLineWidth(line),plain.getLineWidth(line),"line width "+e.text+" at "+now);
    }
    for(int i=0;i<=e.text.length();i++) {
      close(e.layout.getPrimaryHorizontal(i),plain.getPrimaryHorizontal(i),"caret "+e.text+"["+i+"] at "+now);
    }
    Bitmap actual=Bitmap.createBitmap(e.layout.getWidth(),e.layout.getHeight()+24,Bitmap.Config.ARGB_8888);
    e.layout.draw(new Canvas(actual));
    drawShapedRuns(e,new Canvas(actual),e.state,now);
    if(e.state.charStartTimes.isEmpty() || preExpiry) {
      Bitmap expected=Bitmap.createBitmap(actual.getWidth(),actual.getHeight(),Bitmap.Config.ARGB_8888);
      plain.draw(new Canvas(expected));
      if(!actual.sameAs(expected)) {
        int pixels=0,maxDelta=0;
        for(int y=0;y<actual.getHeight();y++) for(int x=0;x<actual.getWidth();x++) {
          int a=actual.getPixel(x,y),b=expected.getPixel(x,y);
          if(a!=b) pixels++;
          maxDelta=Math.max(maxDelta,Math.abs(Color.alpha(a)-Color.alpha(b)));
        }
        throw new AssertionError("native raster handoff "+e.text+" preExpiry="+preExpiry
          +" size="+e.paint.getTextSize()+" spacing="+e.paint.getLetterSpacing()
          +" pixels="+pixels+" maxAlphaDelta="+maxDelta);
      }
      expected.recycle();
    }
    actual.recycle(); frames++;
  }
  public static void main(String[] args) {
    try { runTests(); } catch(Throwable t) { t.printStackTrace(System.out); System.exit(1); }
  }
  static void runTests() throws Exception {
    // app_process does not receive ActivityThread's application/font-map binding.
    Typeface.class.getMethod("loadPreinstalledSystemFontMap").invoke(null);
    // Negative control: reproduce the original unguarded production spans.
    TextPaint p=new TextPaint(Paint.ANTI_ALIAS_FLAG); p.setTextSize(48);
    SpannableStringBuilder broken=new SpannableStringBuilder("Тиш");
    for(int i=0;i<3;i++) {
      final int index=i;
      broken.setSpan(new CharacterStyle() {
        public void updateDrawState(TextPaint p){p.setAlpha(200-index*40);p.baselineShift=-index;}
      },i,i+1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    SystemClock.now=1100;
    float oldWidth=layout(broken,p,600).getLineWidth(0),nativeWidth=layout("Тиш",p,600).getLineWidth(0);
    check(Math.abs(oldWidth-nativeWidth)>1,"negative control must detect real shaping regression");
    System.out.println("CONTROL Тиш unguarded="+oldWidth+" native="+nativeWidth);
    for(boolean spoiler:new boolean[]{false,true}) {
      spoilerEnabled=spoiler;
      for(float size:new float[]{32,48}) for(float spacing:new float[]{0,.04f})
      for(Typeface font:new Typeface[]{Typeface.DEFAULT,Typeface.SERIF})
      for(int width:new int[]{110,600}) for(int hz:new int[]{60,90,120,144})
      for(String word:new String[]{"Тиш","AV","office","مرحبا","e\\u0301","😀ш","abc"}) {
        EditText e=new EditText(size,spacing,font,width);
        long now=1000;
        // Normal typing, including removing a previously safe span once a
        // following character introduces kerning or a ligature.
        for(int i=0;i<word.length();) {
          int next=i+Character.charCount(word.codePointAt(i));
          edit(e,i,0,word.substring(i,next),now); verify(e,now+16); now+=33; i=next;
        }
        for(int frame=0;frame*1000L/hz<500;frame++) verify(e,now+frame*1000L/hz);
        verify(e,now+500);
        // Composing-word replacement and backspace; run the real diff/rebase
        // and reconciliation methods, not a duplicate eligibility simulation.
        edit(e,0,e.text.length(),"Тиш",now+600); verify(e,now+616);
        Object composing=new UnderlineSpan(); e.text.setSpan(composing,0,3,Spanned.SPAN_INCLUSIVE_INCLUSIVE|Spanned.SPAN_COMPOSING);
        edit(e,0,3,"Тиша",now+633); verify(e,now+649);
        check(e.text.getSpanStart(composing)>=0,"IME composing span preserved");
        edit(e,3,1,"",now+666); verify(e,now+682); verify(e,now+1200);
      }
    }
    EditText safe=new EditText(48,0,Typeface.DEFAULT,600);
    edit(safe,0,0,"abc",1000); verify(safe,1050);
    check(safe.state.hasAnimatingChars,"safe letters must retain animation");
    EditText cyrillic=new EditText(48,0,Typeface.DEFAULT,600);
    edit(cyrillic,0,0,"Тиш",1000); verify(cyrillic,1050);
    check(cyrillic.state.charStartTimes.size()==3 && cyrillic.state.hiddenSpans.size()==1,
      "Тиш must retain all three letter effects, not suppress kerning-sensitive animations");
    Bitmap cached=cyrillic.state.hiddenSpans.get(0).crisp;
    verify(cyrillic,1060); verify(cyrillic,1070);
    check(cached==cyrillic.state.hiddenSpans.get(0).crisp,"steady frames reuse shaped raster");
    // Crucially compare BEFORE expiry, while native text is still hidden and
    // the cached renderer supplies all pixels. Post-expiry alone is tautological.
    int handoffs=0;
    for(boolean spoiler:new boolean[]{false,true}) for(boolean replace:new boolean[]{false,true})
    for(float size:new float[]{32,48}) for(float spacing:new float[]{0,.04f})
    for(Typeface font:new Typeface[]{Typeface.DEFAULT,Typeface.SERIF})
    for(String word:new String[]{"Тиш","AV","office","مرحبا","abc"}) {
      spoilerEnabled=spoiler;
      EditText e=new EditText(size,spacing,font,600);
      if(replace) edit(e,0,0,"zzz",500);
      edit(e,0,e.text.length(),word,1000);
      verify(e,1000);
      check(!e.state.hiddenSpans.isEmpty(),"handoff must exercise cached rendering");
      ShapedRun run=e.state.hiddenSpans.get(0);
      Bitmap crisp=run.crisp,blur=run.blur;
      int duration=(replace?REPLACE_DURATION:APPEAR_DURATION)+(spoiler?SPOILER_DURATION:0);
      verify(e,1000+duration-1,true);
      check(e.state.hiddenSpans.get(0).crisp==crisp && !crisp.isRecycled() && !blur.isRecycled(),
        "pre-expiry cache remains live and reused");
      verify(e,1000+duration);
      check(e.state.hiddenSpans.isEmpty(),"expired cache detached");
      check(e.text.getSpanStart(run)==-1,"expired hiding span detached");
      check(run.crisp==null && run.blur==null && !crisp.isRecycled() && !blur.isRecycled(),
        "released cache must not recycle bitmaps possibly referenced by RenderNodes");
      handoffs++;
    }
    System.out.println("PASS: "+handoffs+" pre-expiry pixel-exact native handoffs");
    spoilerEnabled=false;
    EditText delayed=new EditText(48,0,Typeface.DEFAULT,600);
    edit(delayed,0,0,"Т",1000);
    startPendingGlyphs(delayed.state,1400);
    check(delayed.state.charStartTimes.get(0).startTime==1400,"late first draw starts full effect");
    verify(delayed,1400);
    edit(delayed,1,0,"и",1450);
    startPendingGlyphs(delayed.state,1450);
    check(delayed.state.charStartTimes.get(0).startTime==1400,"next key preserves existing clock");
    verify(delayed,1450);
    ShapedRun expiryRun=delayed.state.hiddenSpans.get(0);
    Bitmap expiryBitmap=expiryRun.crisp;
    verify(delayed,1700);
    check(delayed.state.hiddenSpans.get(0)==expiryRun && expiryRun.crisp==expiryBitmap,
      "one glyph expiry must not rebuild surviving run/raster");
    verify(delayed,1750);
    System.out.println("PASS: delayed first frame, continuous clocks and zero raster rebuild on individual expiry");
    EditText styles=new EditText(48,0,Typeface.DEFAULT,600);
    edit(styles,0,0,"Тиш",1000); verify(styles,1010);
    SuggestionSpan suggestion = new SuggestionSpan(Locale.US, new String[]{"Тише"}, SuggestionSpan.FLAG_EASY_CORRECT);
    styles.text.setSpan(suggestion,0,3,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    verify(styles,1011);
    check(!styles.state.hiddenSpans.isEmpty(),"IME suggestions must retain animation");
    styles.text.removeSpan(suggestion);
    CharacterStyle wrapped = CharacterStyle.wrap(new UnderlineSpan());
    styles.text.setSpan(wrapped,0,3,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    verify(styles,1012);
    check(!styles.state.hiddenSpans.isEmpty(),"wrapped IME underline must retain animation");
    styles.text.removeSpan(wrapped);
    verify(styles,1013);
    ShapedRun old=styles.state.hiddenSpans.get(0); Bitmap oldBitmap=old.crisp;
    UnderlineSpan underline=new UnderlineSpan();
    styles.text.setSpan(underline,0,3,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    check(styles.state.spansDirty,"style-only update invalidates cached raster");
    verify(styles,1020); verify(styles,1299,true);
    check(!oldBitmap.isRecycled() && old.crisp==null,"rebuild releases without recycling");
    ForegroundColorSpan colour=new ForegroundColorSpan(Color.RED);
    styles.text.setSpan(colour,0,3,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    verify(styles,1250,true);
    check(styles.state.hiddenSpans.isEmpty(),"foreground alpha cannot override hidden run");
    styles.text.removeSpan(colour); verify(styles,1260);
    check(!styles.state.hiddenSpans.isEmpty(),"removing unsupported style restores effect");
    final int[] notifications={0};
    SpanWatcher observer=new SpanWatcher() {
      public void onSpanAdded(Spannable s,Object o,int a,int b){notifications[0]++;}
      public void onSpanRemoved(Spannable s,Object o,int a,int b){notifications[0]++;}
      public void onSpanChanged(Spannable s,Object o,int a,int b,int c,int d){notifications[0]++;}
    };
    styles.text.setSpan(observer,0,3,Spanned.SPAN_INCLUSIVE_INCLUSIVE);
    notifications[0]=0;
    ShapedRun stable=styles.state.hiddenSpans.get(0);
    for(int frame=1261;frame<1299;frame++) verify(styles,frame);
    check(notifications[0]==0 && styles.state.hiddenSpans.get(0)==stable,
      "steady frames must not rewrite spans or rebuild shaped runs");
    TextPaint comparison=new TextPaint(styles.paint);
    check(sameRunPaint(styles.paint,comparison),"identical typography");
    comparison.setTextLocale(java.util.Locale.JAPANESE);
    check(!sameRunPaint(styles.paint,comparison),"locale invalidation");
    comparison.set(styles.paint); comparison.setLetterSpacing(.1f);
    check(!sameRunPaint(styles.paint,comparison),"spacing invalidation");
    comparison.set(styles.paint); comparison.setFontFeatureSettings("kern off");
    check(!sameRunPaint(styles.paint,comparison),"font feature invalidation");
    EditText large=new EditText(500,0,Typeface.DEFAULT,4000);
    edit(large,0,0,"Тиш",1000); verify(large,1010);
    check(large.state.hiddenSpans.isEmpty(),"oversized raster remains native before allocation");
    EditText failed=new EditText(48,0,Typeface.DEFAULT,600);
    edit(failed,0,0,"Тиш",1000); failRaster=true;
    verify(failed,1010,true);
    check(allocationFailures==1 && failed.state.hiddenSpans.isEmpty() && !failed.state.spansDirty,
      "failed allocation falls back to native and completes reconciliation");
    verify(failed,1020,true); verify(failed,1030,true);
    check(allocationFailures==1,"no per-frame allocation retry after failure");
    failRaster=false;
    edit(failed,3,0,"а",1040); verify(failed,1050);
    check(!failed.state.hiddenSpans.isEmpty(),"next edit can recover raster animation");
    System.out.println("PASS: injected raster OOM falls back native without steady-frame retries");
    System.out.println("PASS: style-only invalidation, typography keys, bitmap lifetime/bounds, zero steady-frame span notifications");
    System.out.println("PASS: "+frames+" Android layout/raster frames; typing, IME, deletion, expiry; native positions preserved");
  }
}
`;
const serial=process.argv[2];
if (!serial || !serial.startsWith('emulator-')) throw new Error('Pass a dedicated emulator serial');
const sdk=process.env.ANDROID_SDK_ROOT || path.join(os.homedir(), 'android-sdk');
const adb=path.join(sdk,'platform-tools/adb');
const run=(exe,args)=>cp.execFileSync(exe,args,{stdio:'inherit'});
const dir=fs.mkdtempSync(path.join(os.tmpdir(),'text-shaping-'));
console.log('Artifacts: '+dir);
fs.mkdirSync(path.join(dir,'classes')); fs.mkdirSync(path.join(dir,'dex'));
fs.writeFileSync(path.join(dir,'TextAnimationShapingTest.java'),java);
const androidJar=path.join(sdk,'platforms/android-36/android.jar');
run('javac',['-cp',androidJar,'-d',path.join(dir,'classes'),path.join(dir,'TextAnimationShapingTest.java')]);
run('jar',['cf',path.join(dir,'classes.jar'),'-C',path.join(dir,'classes'),'.']);
run(path.join(sdk,'build-tools/35.0.0/d8'),['--min-api','31','--lib',androidJar,
  '--output',path.join(dir,'dex'),path.join(dir,'classes.jar')]);
const remote='/data/local/tmp/'+path.basename(dir)+'.dex';
run(adb,['-s',serial,'push',path.join(dir,'dex/classes.dex'),remote]);
try { run(adb,['-s',serial,'shell','su','0','env','CLASSPATH='+remote,'app_process','/system/bin','TextAnimationShapingTest']); }
finally { run(adb,['-s',serial,'shell','rm',remote]); }
