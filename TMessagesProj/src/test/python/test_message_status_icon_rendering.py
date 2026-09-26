"""Production factory/span exercised with deferred draw stubs, not device pixels."""
from pathlib import Path
import re
import unittest

from test_sender_infocard_transitions import run_java

JAVA = Path(__file__).resolve().parents[2] / 'main/java'
HELPERS = JAVA / 'app/nimarkogram/messenger/utils/chats'


def local_source(path):
    source = path.read_text()
    source = re.sub(r'^(?:package|import) .*;\n', '', source, flags=re.M)
    return source.replace('public class ColoredImageSpan', 'class ColoredImageSpan')


class MessageStatusIconRenderingTest(unittest.TestCase):
    def test_deferred_selection_passes_and_shared_layouts(self):
        run_java('''import java.util.*;
import java.util.function.IntSupplier;
@interface NonNull {} @interface Nullable {}
class Color {static int alpha(int c){return c>>>24;}}
class Paint {
 int color=0xffffffff;
 int getColor(){return color;} int getAlpha(){return color>>>24;}
 void setAlpha(int a){color=(color&0xffffff)|(a<<24);}
 static class FontMetricsInt {int ascent,descent,top,bottom;}
}
class TextPaint extends Paint {int linkColor=0xffffffff;}
class PorterDuff {enum Mode {SRC_IN}}
class PorterDuffColorFilter {int color;PorterDuffColorFilter(int c,PorterDuff.Mode m){color=c;}}
class Rect {
 int left,top,right,bottom;int height(){return bottom-top;}int centerX(){return (left+right)/2;}
 int centerY(){return (top+bottom)/2;}
}
class Canvas {
 final List<IntSupplier> draws=new ArrayList<>();final Bitmap bitmap;
 Canvas(){bitmap=null;} Canvas(Bitmap b){bitmap=b;}
 void save(){}void restore(){}void translate(float x,float y){}
 void scale(float x,float y,float a,float b){}void rotate(float r,float x,float y){}
}
class Drawable {
 int alpha=255,w=12,h=12;Rect bounds=new Rect();PorterDuffColorFilter tint;
 Drawable mutate(){return this;}int getIntrinsicWidth(){return w;}int getIntrinsicHeight(){return h;}
 void setBounds(int l,int t,int r,int b){bounds.left=l;bounds.top=t;bounds.right=r;bounds.bottom=b;}
 Rect getBounds(){return bounds;}void setAlpha(int a){alpha=a;}
 void setColorFilter(PorterDuffColorFilter f){tint=f;}
 void draw(Canvas c){
  if(c.bitmap!=null){c.bitmap.rasterized++;return;}
  // HWUI VectorDrawable playback consumes shared root alpha, while color
  // filter/paint is recorded for the operation (AOSP VectorDrawable.cpp).
  final int tintAlpha=tint==null?255:Color.alpha(tint.color);
  c.draws.add(()->alpha*tintAlpha/255);
 }
}
class Bitmap {
 enum Config {ARGB_8888}int w,h,density,rasterized;
 static Bitmap createBitmap(int w,int h,Config c){Bitmap b=new Bitmap();b.w=w;b.h=h;return b;}
 void setDensity(int d){density=d;}
}
class BitmapDrawable extends Drawable {
 final Bitmap bitmap;
 BitmapDrawable(Resources r,Bitmap b){bitmap=b;w=b.w;h=b.h;}
 void draw(Canvas c){final int a=alpha*(tint==null?255:Color.alpha(tint.color))/255;c.draws.add(()->a);}
}
class SparseArray<T> {Map<Integer,T> m=new HashMap<>();void clear(){m.clear();}T get(int k){return m.get(k);}void put(int k,T v){m.put(k,v);}}
class DisplayMetrics {int densityDpi=160;}
class Resources {DisplayMetrics metrics=new DisplayMetrics();DisplayMetrics getDisplayMetrics(){return metrics;}}
class Context {Resources r=new Resources();Resources getResources(){return r;}}
class ApplicationLoader {static Context applicationContext=new Context();}
class ContextCompat {
 static int loads;
 static Drawable getDrawable(Context c,int id){loads++;Drawable d=new Drawable();d.w=d.h=12*c.r.metrics.densityDpi/160;return d;}
}
class AndroidUtilities {static int dp(int n){return n;}}
class Theme {static int getColor(int key){return 0xffabcdef;}}
abstract class ReplacementSpan {
 abstract int getSize(Paint p,CharSequence t,int a,int b,Paint.FontMetricsInt m);
 abstract void draw(Canvas c,CharSequence t,int a,int b,float x,int top,int y,int bottom,Paint p);
}
FACTORY
SPAN
public class Transitions {
 static void check(boolean b){if(!b)throw new AssertionError();}
 static void draw(ColoredImageSpan s,Canvas c,int a){Paint p=new Paint();p.setAlpha(a);s.draw(c,"x",0,1,0,0,12,16,p);}
 public static void main(String[] args){
  // Reproduce the old failure using production ColoredImageSpan.draw:
  // quote highlight draws normal status, then a zero-alpha selected status.
  ColoredImageSpan old=new ColoredImageSpan(new Drawable());Canvas before=new Canvas();
  draw(old,before,255);draw(old,before,0);
  check(before.draws.get(0).getAsInt()==0);
  Drawable first=MessageStatusIcons.create(1),second=MessageStatusIcons.create(1);
  check(first!=second && ((BitmapDrawable)first).bitmap==((BitmapDrawable)second).bitmap);
  check(ContextCompat.loads==1 && first.getIntrinsicWidth()==12);
  ColoredImageSpan span=new ColoredImageSpan(first);Canvas after=new Canvas();
  for(int a:new int[]{255,0,128,64,255,0})draw(span,after,a);
  int[] expected={255,0,128,64,255,0};
  for(int i=0;i<expected.length;i++)check(after.draws.get(i).getAsInt()==expected[i]);
  check(span.getSize(new Paint(),"x",0,1,null)==12);
  // Other layouts and theme changes cannot rewrite earlier recorded opacity.
  draw(new ColoredImageSpan(second),new Canvas(),0);
  check(after.draws.get(0).getAsInt()==255);
  MessageStatusIcons.create(2);check(ContextCompat.loads==2);
  Bitmap oldMask=((BitmapDrawable)first).bitmap;
  ApplicationLoader.applicationContext.r.metrics.densityDpi=320;
  Drawable dense=MessageStatusIcons.create(1);
  check(dense.getIntrinsicWidth()==24 && ((BitmapDrawable)dense).bitmap!=oldMask);
  check(oldMask.rasterized==1 && ContextCompat.loads==3);
 }
}
'''.replace('FACTORY', local_source(HELPERS / 'MessageStatusIcons.java'))
           .replace('SPAN', local_source(JAVA / 'org/telegram/ui/Components/ColoredImageSpan.java')))

    def test_both_status_helpers_use_stable_icons(self):
        for name in ('NimarkoChatHelper.java', 'NimarkoChatsHelper.java'):
            source = (HELPERS / name).read_text()
            self.assertIn('MessageStatusIcons.create(R.drawable.forwards_solar)', source)
            self.assertIn('MessageStatusIcons.create(R.drawable.msg_edited)', source)
            self.assertNotIn('getDrawable(ApplicationLoader.applicationContext, R.drawable.forwards_solar)', source)

    def test_debug_and_wrong_button_workarounds_removed(self):
        files = ['org/telegram/ui/Cells/ChatMessageCell.java', 'org/telegram/ui/ChatActivity.java',
                 'org/telegram/ui/Components/SizeNotifierFrameLayout.java',
                 'org/telegram/ui/Components/InstantCameraView.java',
                 'org/telegram/messenger/camera/CameraView.java',
                 'org/telegram/messenger/camera/Camera2Session.java']
        for file in files:
            source = (JAVA / file).read_text()
            for marker in ('RepostRenderTrace', 'traceRepost', 'RepostPresentationProbe',
                           'Camera2Geometry', 'Camera2OesProbe', 'shareButtonInCell',
                           'getUnobscuredShareButtonY', 'outboundsOwnerChanged'):
                self.assertNotIn(marker, source, file)


if __name__ == '__main__':
    unittest.main()
