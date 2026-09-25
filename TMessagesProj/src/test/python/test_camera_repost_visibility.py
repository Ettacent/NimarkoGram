"""Regression checks for the lazy camera and outbounds drawing ownership."""
from pathlib import Path
import unittest
from test_search_fallback_entrance import block
from test_sender_infocard_transitions import run_java

ROOT = Path(__file__).resolve().parents[2] / 'main/java/org/telegram/ui'


class CameraRepostVisibilityTest(unittest.TestCase):
    def test_lazy_camera_revealed_before_texture_start(self):
        source = (ROOT / 'Components/ChatAttachAlertPhotoLayout.java').read_text()
        opening = block(source, 'public void openCamera(boolean animated)')
        self.assertLess(opening.index('return;'), opening.index('cameraView.setAlpha(1f);'))
        self.assertLess(opening.index('cameraView.setAlpha(1f);'), opening.index('cameraView.initTexture();'))
        callback = block(source, 'public void onCameraInit()')
        self.assertNotIn('if (current == null || next == null) return;', callback)
        self.assertIn('current == null || next == null || current.equals(next)', callback)

    def test_parent_invalidated_only_on_opacity_boundary(self):
        source = (ROOT / 'Cells/ChatMessageCell.java').read_text()
        method = block(source, 'public void setAlpha(float alpha)')
        run_java('''
class Base {float alpha=1;public void setAlpha(float a){alpha=a;}}
public class Transitions extends Base {
 boolean ALPHA_PROPERTY_WORKAROUND, enterTransitionInProgress;
 float alphaInternal=1;int parentInvalidations;float observedAlpha;
 Object replyNameLayout,replyTextLayout;
 static class Position {int minX,minY,flags;}
 Position currentPosition;
 static class MessageObject {static final int POSITION_FLAG_BOTTOM=1,POSITION_FLAG_LEFT=2;boolean isVoice(){return false;}}
 MessageObject currentMessageObject=new MessageObject();
 static class Reactions {boolean isSmall=true;}
 Reactions reactionsLayoutInBubble=new Reactions();
 float getAlpha(){return ALPHA_PROPERTY_WORKAROUND?alphaInternal:alpha;}
 void invalidate(){}
 void invalidateOutbounds(){parentInvalidations++;observedAlpha=getAlpha();}
 METHOD
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  for(boolean workaround:new boolean[]{false,true}){
   Transitions t=new Transitions();t.ALPHA_PROPERTY_WORKAROUND=workaround;
   t.setAlpha(0);check(t.parentInvalidations==1 && t.observedAlpha==0);
   t.setAlpha(.3f);t.setAlpha(.9f);check(t.parentInvalidations==1);
   t.setAlpha(1);check(t.parentInvalidations==2 && t.observedAlpha==1);
   t.setAlpha(1);check(t.parentInvalidations==2);
   t.setAlpha(.5f);check(t.parentInvalidations==3 && t.observedAlpha==.5f);
  }
 }
}
'''.replace('METHOD', method))
