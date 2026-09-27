"""Exercise the cell's actual placeholder drawing, not only ImageReceiver blending."""
from pathlib import Path
import unittest
from test_recording_composer_lifecycle import method
from test_sender_infocard_transitions import run_java


class RoundPlaceholderEndpointTest(unittest.TestCase):
    def test_round_fade_never_adds_message_color_underlay(self):
        source = (Path(__file__).resolve().parents[2] /
                  'main/java/org/telegram/ui/Cells/ChatMessageCell.java').read_text()
        draw = method(source, 'private boolean drawPhotoImageWithRoundVideoBackground(')
        run_java('''
public class Transitions {
 static class Paint {
  int color=0xAABBCCDD;
  int getColor(){return color;} void setColor(int c){color=c;}
  int getAlpha(){return color>>>24;}void setAlpha(int a){color=(color&0xffffff)|(a<<24);}
 }
 static class Theme {static Object chat_roundVideoShadow=new Object();
  static Paint chat_docBackPaint=new Paint();static int key_chat_outBubble=1,key_chat_inBubble=2;}
 static class AndroidUtilities {static int roundMessageInset=2;}
 static class Message {boolean round=true;boolean isRoundVideo(){return round;}boolean isOutOwner(){return true;}}
 static class Receiver {
  float progress,alpha=1;boolean bitmap=true;
  boolean hasBitmapImage(){return bitmap;}float getCurrentAlpha(){return progress;}float getAlpha(){return alpha;}
  float getImageX(){return 0;}float getImageY(){return 0;}float getImageX2(){return 100;}
  float getImageY2(){return 100;}float getCenterX(){return 50;}float getCenterY(){return 50;}
  float getImageWidth(){return 100;}
 }
 static class Canvas {
  int layers,alpha,shadows,images;boolean fail;
  int saveLayerAlpha(float l,float t,float r,float b,int a){layers++;return layers;}
  void restoreToCount(int s){layers--;}
  void drawCircle(float x,float y,float r,Paint p){alpha=p.getAlpha();if(fail)throw new IllegalStateException();}
 }
 Message currentMessageObject=new Message();Receiver photoImage=new Receiver();
 int dp(int v){return v;}int getThemedColor(int key){return 0xff4488cc;}
 void drawRoundVideoShadow(Canvas c){c.shadows++;}
 boolean drawPhotoImageInternal(Canvas c){c.images++;if(c.fail)throw new IllegalStateException();return true;}
 DRAW
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  Transitions t=new Transitions();int original=Theme.chat_docBackPaint.getColor();
  for(float opacity:new float[]{.2f,.7f,1f}){
   t.photoImage.alpha=opacity;
   for(int frame=0;frame<=100;frame++){
    t.photoImage.progress=frame/100f;Canvas c=new Canvas();t.drawPhotoImageWithRoundVideoBackground(c,.5f);
    check(c.alpha==0&&c.shadows==1&&c.images==1);
    check(c.layers==0&&Theme.chat_docBackPaint.getColor()==original);
   }
  }
  t.photoImage.bitmap=false;t.photoImage.alpha=1;Canvas c=new Canvas();
  t.drawPhotoImageWithRoundVideoBackground(c,1);check(c.alpha==0&&c.images==1);
  c.fail=true;try{t.drawPhotoImageWithRoundVideoBackground(c,.5f);throw new AssertionError();}
  catch(IllegalStateException expected){}check(c.layers==0&&Theme.chat_docBackPaint.getColor()==original);
  t.currentMessageObject.round=false;c=new Canvas();t.drawPhotoImageWithRoundVideoBackground(c,1);check(c.alpha==0);
 }
}'''.replace('DRAW', draw))


if __name__ == '__main__':
    unittest.main()
