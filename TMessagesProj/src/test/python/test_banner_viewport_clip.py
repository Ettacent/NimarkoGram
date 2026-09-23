"""Live header clipping contract; a JVM Canvas stub, not GPU/device coverage."""
from pathlib import Path
import unittest
from test_search_fallback_entrance import block
from test_sender_infocard_transitions import run_java

JAVA = Path(__file__).resolve().parents[2] / 'main/java'


class BannerViewportClipTests(unittest.TestCase):
    def test_video_and_freeze_use_current_header_not_pending_layout(self):
        profile = (JAVA / 'org/telegram/ui/ProfileActivity.java').read_text()
        top = block(profile, 'private class TopView extends FrameLayout')
        draw = block(top, 'protected boolean drawChild(Canvas canvas, View child, long drawingTime)')
        draw = draw.replace('app.nimarkogram.messenger.banners.NimarkoBannerRenderer.getInstance()', 'renderer')
        renderer = (JAVA / 'app/nimarkogram/messenger/banners/NimarkoBannerRenderer.java').read_text()
        layer = block(renderer, 'public boolean isVideoLayer(View child)')
        run_java('''
class View { boolean fail; }
class Canvas {
 int clip=2000,saved;
 int save(){saved=clip;return 1;}
 void clipRect(int l,int t,int r,int b){clip=Math.min(clip,b);}
 void restoreToCount(int save){clip=saved;}
}
class Host {
 int paintedClip;
 int getWidth(){return 1080;}
 protected boolean drawChild(Canvas canvas,View child,long time){
  paintedClip=canvas.clip;if(child.fail)throw new IllegalStateException();return true;
 }
}
public class Transitions extends Host {
 static class Renderer {
  View videoTexture=new View(),vidFreeze=new View(),vidBlur=new View(),vidContrast=new View(),vidDark=new View();
  LAYER
 }
 Renderer renderer=new Renderer();int bannerViewportHeight;
 DRAW
 public static void main(String[] args){
  Transitions t=new Transitions();Canvas c=new Canvas();
  for(int height:new int[]{700,120,0,500,80}){
   t.bannerViewportHeight=height;
   for(View v:new View[]{t.renderer.videoTexture,t.renderer.vidFreeze,t.renderer.vidBlur,t.renderer.vidContrast,t.renderer.vidDark}){
    t.drawChild(c,v,0);if(t.paintedClip!=height||c.clip!=2000)throw new AssertionError();
   }
   t.drawChild(c,new View(),0);if(t.paintedClip!=2000)throw new AssertionError();
  }
  t.renderer.vidFreeze.fail=true;
  try{t.drawChild(c,t.renderer.vidFreeze,0);throw new AssertionError();}catch(IllegalStateException expected){}
  if(c.clip!=2000)throw new AssertionError();
 }
}
'''.replace('LAYER', layer).replace('DRAW', draw))
        on_draw = block(top, 'protected void onDraw(Canvas canvas)')
        self.assertIn('bannerViewportHeight = Math.max(0, bannerY1);', on_draw)
        self.assertLess(on_draw.index('bannerViewportHeight ='), on_draw.index('applyVideoFx('))


if __name__ == '__main__':
    unittest.main()
