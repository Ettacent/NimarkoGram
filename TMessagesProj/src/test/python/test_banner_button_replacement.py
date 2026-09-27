"""Foreground follows the banner still on screen, not its pending file path."""
from pathlib import Path
import unittest
from test_gif_loop_transition import method
from test_sender_infocard_transitions import run_java

SOURCE = (Path(__file__).resolve().parents[2] /
          'main/java/app/nimarkogram/messenger/banners/NimarkoBannerRenderer.java').read_text()


class BannerButtonReplacementTests(unittest.TestCase):
    def test_outgoing_video_and_photo_keep_button_foreground(self):
        body = method(SOURCE, 'public float getForegroundProgress(')
        run_java(r'''
import java.util.*;
public class Transitions {
 static class ViewGroup {}
 static class Bitmap {}
 static class NimarkoBannerConfig {static boolean useAvatar;}
 static class Controller {
  boolean hasNoRealBanner(long id){return false;}
  boolean shouldHideAvatar(long id){return false;}
 }
 Controller ctrl=new Controller();
 Map<Long,String> frameBfByEid=new HashMap<>(),photoFadeKey=new HashMap<>();
 Map<Long,Boolean> frameIvByEid=new HashMap<>();
 Map<Long,Double> photoFadeStart=new HashMap<>();
 Map<Long,Float> avAlpha=new HashMap<>();
 Map<String,Bitmap> bitmaps=new HashMap<>();
 Set<Long> avAnim=new HashSet<>();
 boolean active=true,committed=true,attached=true;
 float visible=1;String curVidPath="old";Bitmap xfadeBmp;double FADE_DUR=1;
 boolean isActiveAccount(int a){return active;}
 boolean isCurrentProfile(ViewGroup v,int a,long e){return committed;}
 boolean isVideoAttachedTo(ViewGroup v){return attached;}
 float videoVisualProgress(){return visible;}
 boolean pathEq(String a,String b){return a!=null&&a.equals(b);}
 boolean okBmp(Bitmap b){return b!=null;}
 String avatarFadeKey(long e){return "a"+e;}
 Bitmap cachedAvatarBitmap(long e){return null;}
 double t(){return 2;}
 float clamp01(double v){return (float)Math.max(0,Math.min(1,v));}
 float getOr(Map<Long,Float> m,long k,float d){return m.getOrDefault(k,d);}
 BODY
 float progress(){return getForegroundProgress(new ViewGroup(),0,1);}
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  Transitions x=new Transitions();x.frameBfByEid.put(1L,"new");x.frameIvByEid.put(1L,true);
  check(x.progress()==1); // old video still visible, new path already resolved
  x.visible=.4f;check(x.progress()==.4f); // no forced white foreground on cold reveal
  x.committed=false;check(x.progress()==0); // another profile cannot borrow old media
  x.committed=true;x.attached=false;check(x.progress()==0);
  x.attached=true;x.visible=1;x.frameIvByEid.put(1L,false);
  check(x.progress()==1); // video -> photo waiting for decode
  x.attached=false;x.xfadeBmp=new Bitmap();check(x.progress()==1); // photo -> photo cover
  x.xfadeBmp=null;x.bitmaps.put("new",new Bitmap());x.photoFadeKey.put(1L,"fnew");
  x.photoFadeStart.put(1L,1.5);check(Math.abs(x.progress()-.5f)<.001f);
  x.photoFadeStart.put(1L,1.0);check(x.progress()==1);
  x.active=false;check(x.progress()==0);
 }
}
'''.replace('BODY', body))
