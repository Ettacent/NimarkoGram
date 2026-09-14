const fs = require('node:fs'), path = require('node:path'), os = require('node:os');
const cp = require('node:child_process'), assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/java/app/nimarkogram/messenger/notifications/NotificationSectionsScrollView.java'), 'utf8')
    .replace(/^package .*;|^import .*;/gm, '').replace('public final class', 'final class');
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'notification-focus-'));
const java = `
class Context {} class Rect {} class LinearLayout {} class KeyEvent {}
class Theme {interface ResourcesProvider {}}
class MotionEvent {static final int ACTION_DOWN=0;int getActionMasked(){return ACTION_DOWN;}}
class TextUtils {static boolean equals(CharSequence a,CharSequence b){return a.toString().equals(b.toString());}}
class View {int width=800,height=1600;boolean accessible;
 int getWidth(){return width;}int getHeight(){return height;}boolean isAccessibilityFocused(){return accessible;}}
class TextView extends View {String text="Channel";int start=7,end=7;
 CharSequence getText(){return text;}int getSelectionStart(){return start;}int getSelectionEnd(){return end;}}
class SectionsScrollView extends View {
 View focus;Runnable posted;
 SectionsScrollView(Context c,LinearLayout l,Theme.ResourcesProvider p,boolean padding){}
 View findFocus(){return focus;}void removeCallbacks(Runnable r){if(posted==r)posted=null;}
 boolean post(Runnable r){posted=r;return true;}
 protected void onLayout(boolean changed,int l,int t,int r,int b){}
 boolean requestChildRectangleOnScreen(View child,Rect rect,boolean immediate){return true;}
 boolean dispatchTouchEvent(MotionEvent e){return true;}boolean dispatchKeyEvent(KeyEvent e){return true;}
 protected void onDetachedFromWindow(){}
}
${source}
public class FocusInsetTest {
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  NotificationSectionsScrollView s=new NotificationSectionsScrollView(new Context(),new LinearLayout(),null);
  TextView field=new TextView();s.focus=field;Rect rect=new Rect();
  check(s.requestChildRectangleOnScreen(field,rect,false));
  s.prepareNotificationInset();s.onLayout(false,0,0,800,1600);
  check(!s.requestChildRectangleOnScreen(field,rect,false));
  check(!s.requestChildRectangleOnScreen(field,rect,false));
  check(s.requestChildRectangleOnScreen(field,rect,true));
  s.posted.run();check(s.requestChildRectangleOnScreen(field,rect,false));
  s.prepareNotificationInset();field.text="Edited";check(s.requestChildRectangleOnScreen(field,rect,false));
  s.prepareNotificationInset();field.start=2;check(s.requestChildRectangleOnScreen(field,rect,false));
  s.prepareNotificationInset();field.end=4;check(s.requestChildRectangleOnScreen(field,rect,false));
  s.prepareNotificationInset();s.height=900;check(s.requestChildRectangleOnScreen(field,rect,false));
  s.prepareNotificationInset();field.height=100;check(s.requestChildRectangleOnScreen(field,rect,false));
  s.prepareNotificationInset();field.accessible=true;check(s.requestChildRectangleOnScreen(field,rect,false));field.accessible=false;
  s.prepareNotificationInset();s.focus=new TextView();check(s.requestChildRectangleOnScreen(field,rect,false));s.focus=field;
  s.prepareNotificationInset();s.dispatchTouchEvent(new MotionEvent());check(s.requestChildRectangleOnScreen(field,rect,false));
  s.prepareNotificationInset();s.dispatchKeyEvent(new KeyEvent());check(s.requestChildRectangleOnScreen(field,rect,false));
  s.prepareNotificationInset();s.onDetachedFromWindow();check(s.requestChildRectangleOnScreen(field,rect,false));
  s.focus=null;s.prepareNotificationInset();check(s.requestChildRectangleOnScreen(field,rect,false));
  System.out.println("PASS: inset-only cursor requests suppressed; text, selection, keyboard geometry, focus and user input remain functional");
 }
}`;
try {
 fs.writeFileSync(path.join(dir, 'FocusInsetTest.java'), java);
 cp.execFileSync('javac', [path.join(dir, 'FocusInsetTest.java')]);
 const result=cp.spawnSync('java',['-cp',dir,'FocusInsetTest'],{encoding:'utf8'});
 assert.equal(result.status,0,result.stderr);process.stdout.write(result.stdout);
} finally {fs.rmSync(dir,{recursive:true,force:true});}
