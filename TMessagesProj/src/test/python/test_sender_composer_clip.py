"""Execute composer bounds/clip helpers against geometric JVM stubs.

This checks containment and coordinate mapping, not Android rasterization.
"""
import shutil
import unittest

from test_recording_composer_lifecycle import ENTER, CONTAINER, method
from test_sender_infocard_transitions import run_java


class SenderComposerClipTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which('javac'), 'JDK required')
    def test_moving_avatar_stays_inside_actual_pill(self):
        host = '\n'.join(method(CONTAINER, name).replace('@NonNull ', '') for name in (
            'public void getInputBubbleDrawableBounds(',
            'public void getInputBubbleContentBounds(',
        ))
        clip = method(ENTER, 'private void clipSenderToComposer(')
        run_java(r'''
import java.awt.Shape;
import java.awt.geom.*;
public class Transitions {
 static float density;
 static int dp(float v){return (int)Math.ceil(v*density);}
 static float lerp(float a,float b,float p){return a+(b-a)*p;}
 static class Rect {int left,top,right,bottom;void set(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}}
 static class RectF {
  float left,top,right,bottom;
  void set(Rect r){left=r.left;top=r.top;right=r.right;bottom=r.bottom;}
  void inset(float x,float y){left+=x;right-=x;top+=y;bottom-=y;}
 }
 interface ViewParent {ViewParent getParent();}
 static class Matrix {AffineTransform t=new AffineTransform();void reset(){t.setToIdentity();}}
 static class View implements ViewParent {
  ViewParent parent;float x,y,scale=1;
  public ViewParent getParent(){return parent;}
  AffineTransform global(){return new AffineTransform(scale,0,0,scale,x,y);}
  void transformMatrixToGlobal(Matrix m){m.t.preConcatenate(global());}
  void transformMatrixToLocal(Matrix m){try{m.t.preConcatenate(global().createInverse());}catch(Exception e){throw new AssertionError(e);}}
 }
 static class Path {
  enum Direction{CW} Shape shape;
  void rewind(){shape=null;}
  void addRoundRect(RectF r,float rx,float ry,Direction d){shape=new RoundRectangle2D.Float(r.left,r.top,r.right-r.left,r.bottom-r.top,2*rx,2*ry);}
  void transform(Matrix m){shape=m.t.createTransformedShape(shape);}
 }
 static class Canvas {Shape clip;void clipPath(Path p){clip=p.shape;}}
 static class ChatInputViewsContainer extends View {
  static final int INPUT_BUBBLE_RADIUS=22,INPUT_BUBBLE_DRAWABLE_PADDING=7,SEPARATED_COMPOSER_SIDE_SIZE=44,SEPARATED_COMPOSER_GAP=4;
  int width,height,currentBlurredHeight,inputBubbleHeightRound;
  float inputBubbleOffsetLeft,inputBubbleOffsetRight,bubbleInputTranlationY,leadingComposerExpansionProgress,separatedComposerProgress;
  final Rect tmpRect=new Rect();
  int getMeasuredWidth(){return width;}int getMeasuredHeight(){return height;}
  float getTrailingComposerTakeoverProgress(){return 0;}
  HOST
 }
 View messageEditTextContainer=new View();
 final RectF senderComposerClipBounds=new RectF();
 final Path senderComposerClipPath=new Path();
 final Matrix senderComposerClipMatrix=new Matrix();
 CLIP
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[] args){
  int overflowWithoutClip=0;
  for(float d:new float[]{1,1.5f,2.625f,3,4})for(int separated=0;separated<=1;separated++)
   for(int lines:new int[]{44,88,144})for(int keyboard:new int[]{0,260})for(float scale:new float[]{1,.92f}){
    density=d;Transitions t=new Transitions();ChatInputViewsContainer h=new ChatInputViewsContainer();
    h.width=dp(400);h.height=dp(800);h.inputBubbleHeightRound=dp(lines);
    h.currentBlurredHeight=dp(lines+keyboard+9);h.separatedComposerProgress=separated;
    h.x=17;h.y=-23;h.scale=scale;
    RectF bounds=new RectF();h.getInputBubbleContentBounds(bounds);
    View mid=new View();mid.parent=h;t.messageEditTextContainer.parent=mid;
    View field=t.messageEditTextContainer;
    field.x=h.x+scale*bounds.left;field.y=h.y+scale*bounds.top;field.scale=scale;
    Canvas c=new Canvas();t.clipSenderToComposer(c);check(c.clip!=null,"ancestor host found");
    Rectangle2D viewport=c.clip.getBounds2D();
    check(Math.abs(viewport.getX())<.001 && Math.abs(viewport.getY())<.001,"host to local offset");
    check(Math.abs(viewport.getWidth()-(bounds.right-bounds.left))<.001,"no shadow overscan");
    float avatarX=dp(4.66f),avatarY=dp(lines)-dp(4)-dp(36),size=dp(36);
    float travel=-size-dp(4.66f)-dp(2);
    for(int frame=0;frame<=120;frame++){
     float p=frame/120f;
     Shape avatar=new Ellipse2D.Float(avatarX+travel*p,avatarY,size,size);
     Shape expectedPill=new Path2D.Double(new RoundRectangle2D.Float(0,0,bounds.right-bounds.left,bounds.bottom-bounds.top,2*dp(22),2*dp(22)));
     int outside=0,visible=0;
     for(float y=avatarY+.5f;y<avatarY+size;y+=dp(2))
      for(float x=avatarX+travel*p+.5f;x<avatarX+travel*p+size;x+=dp(2))if(avatar.contains(x,y)){
       boolean drawn=c.clip.contains(x,y);
       if(!drawn)outside++;
       else{visible++;check(expectedPill.contains(x,y),"moving pixel outside visible pill");}
      }
     if(outside>0)overflowWithoutClip++;
     if(frame==0){check(outside==0,"resting avatar must remain whole");}
     if(frame==120){check(visible==0,"hidden endpoint is really hidden");}
    }
   }
  check(overflowWithoutClip>100,"old unclipped draw exposes avatar outside pill");
  Transitions embedded=new Transitions();Canvas c=new Canvas();embedded.clipSenderToComposer(c);
  check(c.clip==null,"embedded composer does not inherit another chat contour");
 }
}
'''.replace('HOST', host).replace('CLIP', clip))

    def test_only_avatar_is_clipped_without_global_clip_children(self):
        field = ENTER[ENTER.index('FrameLayout frameLayout = messageEditTextContainer'):]
        draw = method(field, 'protected boolean drawChild(')
        self.assertIn('child == senderSelectView', draw)
        self.assertLess(draw.index('canvas.save()'), draw.index('clipSenderToComposer(canvas)'))
        self.assertLess(draw.index('clipSenderToComposer(canvas)'), draw.index('canvas.restoreToCount(save)'))
        self.assertIn('frameLayout.setClipChildren(false)', field)


if __name__ == '__main__':
    unittest.main()
