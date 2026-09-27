"""Run the actual platform-independent Material geometry on the host JVM."""
from pathlib import Path
import subprocess
import tempfile
import unittest


class MaterialSharedAxisTest(unittest.TestCase):
    def test_geometry_and_reverse(self):
        source = Path(__file__).resolve().parents[2] / "main/java/org/telegram/ui/ActionBar/MaterialSharedAxisMotion.java"
        harness = """
package org.telegram.ui.ActionBar;
public class MaterialSharedAxisCheck {
 static void eq(float a,float b){if(Math.abs(a-b)>0.00001f)throw new AssertionError(a+" != "+b);}
 public static void main(String[] args){
  eq(MaterialSharedAxisMotion.enteringOffset(true,0),1);
  eq(MaterialSharedAxisMotion.leavingOffset(true,1),-1);
  eq(MaterialSharedAxisMotion.enteringOffset(false,0),-1);
  eq(MaterialSharedAxisMotion.leavingOffset(false,1),1);
  for(int i=0;i<=1000;i++){
   float p=i/1000f;
   eq(MaterialSharedAxisMotion.enteringOffset(true,p),-MaterialSharedAxisMotion.enteringOffset(false,p));
   eq(MaterialSharedAxisMotion.leavingOffset(true,p),-MaterialSharedAxisMotion.leavingOffset(false,p));
   eq(MaterialSharedAxisMotion.enteringOffset(true,p)-MaterialSharedAxisMotion.leavingOffset(true,p),1);
   eq(MaterialSharedAxisMotion.position(MaterialSharedAxisMotion.phaseForPosition(p)),p);
   eq(MaterialSharedAxisMotion.dragProgress(p*1080,1080),p);
  }
  eq(MaterialSharedAxisMotion.enteringAlpha(-1),0);
  eq(MaterialSharedAxisMotion.enteringAlpha(2),1);
  eq(MaterialSharedAxisMotion.enteringOffset(true,1),0);
  eq(MaterialSharedAxisMotion.enteringOffset(false,1),0);
  eq(MaterialSharedAxisMotion.gestureCornerProgress(0),0);
  eq(MaterialSharedAxisMotion.gestureCornerProgress(.2f),1);
  eq(MaterialSharedAxisMotion.gestureCornerProgress(1),1);
  float lastCorner=0;
  for(int i=0;i<=1000;i++) {
   float corner=MaterialSharedAxisMotion.gestureCornerProgress(i/1000f);
   if(corner<lastCorner||corner<0||corner>1)throw new AssertionError("invalid gesture corners");
   lastCorner=corner;
  }
  if(MaterialSharedAxisMotion.DURATION_MS!=360)throw new AssertionError("duration");
  eq(MaterialSharedAxisMotion.slideDistance(1080),270);
  eq(MaterialSharedAxisMotion.slideDistance(600),150);
  eq(MaterialSharedAxisMotion.slideDistance(0),0);
  float lastPosition=0,lastEnter=0,lastExit=1;
  for(int i=0;i<=10000;i++){
   float t=i/10000f;
   float p=MaterialSharedAxisMotion.position(t),a=MaterialSharedAxisMotion.enteringAlpha(t),b=MaterialSharedAxisMotion.leavingAlpha(t);
   if(p<lastPosition-.000001f||a<lastEnter-.000001f||b>lastExit+.000001f)throw new AssertionError("curve reverses");
   if(p<0||p>1||a<0||a>1||b<0||b>1)throw new AssertionError("curve overshoots");
   if(a+b<=0)throw new AssertionError("empty middle frame");
   for(boolean enteringOnTop:new boolean[]{true,false}) {
    float upper=MaterialSharedAxisMotion.topAlpha(enteringOnTop,a);
    // SRC_OVER onto an opaque page must keep identical colors unchanged.
    for(float color:new float[]{0,.1f,.5f,1})eq(color*upper+color*(1-upper),color);
    // A BLACK backing must not dim matching WHITE page content. Testing only
    // pages equal to the backing misses the double-alpha regression.
    for(float color:new float[]{0,.1f,.5f,1}) {
     float lowerAlpha=1,upperAlpha=enteringOnTop?a:b;
     float lower=color*lowerAlpha+0*(1-lowerAlpha);
     eq(color*upperAlpha+lower*(1-upperAlpha),color);
    }
   }
   for(float width:new float[]{320,600,1080,2400})for(float dir:new float[]{-1,1}) {
    float s=MaterialSharedAxisMotion.gestureScale(t);
    float dx=dir*MaterialSharedAxisMotion.gestureOffset(width,t);
    float left=width*(1-s)/2+dx,right=width*(1+s)/2+dx;
    if(left<-.001f||right>width+.001f)throw new AssertionError("back preview cropped");
   }
   lastPosition=p;lastEnter=a;lastExit=b;
  }
  if(MaterialSharedAxisMotion.position(100f/360)<.85f)throw new AssertionError("slow initial movement");
  float midAlpha=MaterialSharedAxisMotion.enteringAlpha(100f/360);
  if(midAlpha<.5f||midAlpha>.65f)throw new AssertionError("abrupt visibility ramp");
  for(float frameMs:new float[]{1000f/60,1000f/90,1000f/120}) {
   for(float ms=0;ms<200;ms+=1) {
    float change=MaterialSharedAxisMotion.enteringAlpha((ms+frameMs)/360)-MaterialSharedAxisMotion.enteringAlpha(ms/360);
    if(change>.15f)throw new AssertionError("large per-frame opacity jump");
   }
  }
  for(int ms=200;ms<=360;ms++){
   eq(MaterialSharedAxisMotion.enteringAlpha(ms/360f),1);
   eq(MaterialSharedAxisMotion.leavingAlpha(ms/360f),0);
  }
  // Reference displacement includes the small tail, independently of page visibility.
  float[] ms={25,50,101,151,200,250,300,358};
  float[] remaining={199,94,39,20,10,5,2,0};
  for(int i=0;i<ms.length;i++){
   float px=270*MaterialSharedAxisMotion.enteringOffset(true,ms[i]/360);
   if(Math.abs(px-remaining[i])>2)throw new AssertionError("reference timing mismatch: "+ms[i]+" "+px);
  }
  if(270*MaterialSharedAxisMotion.enteringOffset(true,300f/360)>3)throw new AssertionError("excessive final displacement");
  if(MaterialSharedAxisMotion.position(1)-MaterialSharedAxisMotion.position(.999f)>.00001f)throw new AssertionError("abrupt final stop");
  if(!MaterialSharedAxisMotion.cancelDrag(.1f,0,0))throw new AssertionError("short cancel");
  if(MaterialSharedAxisMotion.cancelDrag(.6f,0,0))throw new AssertionError("long commit");
  if(MaterialSharedAxisMotion.cancelDrag(.1f,4000,0))throw new AssertionError("fling commit");
  if(!MaterialSharedAxisMotion.cancelDrag(.8f,-4000,0))throw new AssertionError("reverse fling cancel");
  float[] sequence={0,.1f,.6f,.4f,.01f,0,.2f,.9f,1};
  for(float p:sequence)eq(MaterialSharedAxisMotion.dragProgress(p*600,600),p);
  for(boolean cancel:new boolean[]{true,false})for(float v:new float[]{-20000,0,20000}){
   long d=MaterialSharedAxisMotion.settleDuration(.4f,cancel,v,1080);
   if(d<80||d>240)throw new AssertionError("unbounded settle");
   nearSettle(cancel,v,d);
  }
 }
 static void nearSettle(boolean cancel,float v,long d){
  float previous=.4f;
  for(int i=0;i<=1000;i++){
   float p=MaterialSharedAxisMotion.settleProgress(.4f,cancel,v,1080,d,i/1000f);
   if(p<0||p>1||(cancel?p>previous+.000001f:p<previous-.000001f))throw new AssertionError("settle overshoot");
   if(i==0)eq(p,.4f);
   previous=p;
  }
  eq(previous,cancel?0:1);
 }
}
"""
        with tempfile.TemporaryDirectory() as directory:
            runner = Path(directory) / "MaterialSharedAxisCheck.java"
            runner.write_text(harness)
            subprocess.run(["javac", "-d", directory, str(source), str(runner)], check=True)
            subprocess.run(["java", "-cp", directory, "org.telegram.ui.ActionBar.MaterialSharedAxisCheck"], check=True)


if __name__ == "__main__":
    unittest.main()
