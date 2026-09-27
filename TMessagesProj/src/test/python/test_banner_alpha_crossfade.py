"""Production alpha-photo compositor against a premultiplied pixel Canvas model.

No Android build: saveLayer/PorterDuff boundaries are faked, production drawing
and paint weights are extracted unchanged. This does not validate GPU drivers.
"""
import subprocess
import tempfile
import unittest
from pathlib import Path

from test_banner_background_stability import block, RENDERER


class AlphaPhotoCrossfadeTests(unittest.TestCase):
    def harness(self):
        source = RENDERER.read_text()
        return TEMPLATE.replace('/* PRODUCTION */', '\n'.join(block(source, s) for s in (
            'private void drawAlphaPhotoCrossfade(', 'private void drawCachedPhotoBlur(',
        )))

    def execute(self, source):
        with tempfile.TemporaryDirectory(prefix='banner-alpha-') as directory:
            java = Path(directory) / 'AlphaPhoto.java'
            java.write_text(source)
            compiled = subprocess.run(['javac', str(java)], capture_output=True, text=True, timeout=30)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            return subprocess.run(['java', '-ea', '-cp', directory, 'AlphaPhoto'],
                                  capture_output=True, text=True, timeout=30)

    def test_pixel_endpoints_and_intermediate_weights(self):
        result = self.execute(self.harness())
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_negative_controls(self):
        source = self.harness()
        for before, after in (
            ('new PorterDuffXfermode(PorterDuff.Mode.ADD)', 'new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)'),
            ('255 - alpha', '255'),
            ('photoAddPaint.setAlpha(alpha)', 'photoAddPaint.setAlpha(255)'),
            ('int composite = canvas.saveLayer(0, 0, w, y1, null)', 'int composite = canvas.save()'),
        ):
            with self.subTest(mutation=before):
                self.assertEqual(source.count(before), 1)
                result = self.execute(source.replace(before, after))
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('pixel', result.stderr)

    def test_dispatch_and_stable_transparent_backdrop(self):
        source = RENDERER.read_text()
        draw = block(source, 'public void drawImageBanner(')
        self.assertIn('if (bmp.hasAlpha()) {\n                                drawAlphaPhotoCrossfade(', draw)
        self.assertIn('if (!alphaComposite) {', draw)
        self.assertIn('if (!alphaComposite && !lite', draw)
        self.assertEqual(source.count('okBmp(cached) && !cached.hasAlpha() && pfs > 0'), 2)
        method = block(source, 'private void drawAlphaPhotoCrossfade(')
        self.assertNotIn('Bitmap.create', method)
        self.assertEqual(method.count('canvas.restoreToCount('), 3)


TEMPLATE = r'''
import java.util.*;
public class AlphaPhoto {
 static class Bitmap {
  double[] pixel;
  Bitmap(double r,double g,double b,double a){pixel=new double[]{r*a,g*a,b*a,a};}
  int getWidth(){return 17;}int getHeight(){return 11;}
 }
 static class Matrix {}
 static class PorterDuff {enum Mode {ADD,SRC_OVER}}
 static class PorterDuffXfermode {PorterDuff.Mode mode;PorterDuffXfermode(PorterDuff.Mode m){mode=m;}}
 static class Paint {
  int alpha=255;PorterDuff.Mode mode=PorterDuff.Mode.SRC_OVER;
  void setAlpha(int a){alpha=a;}void setXfermode(PorterDuffXfermode x){mode=x.mode;}
 }
 static class NimarkoBannerConfig {static boolean liteMode;}
 static class PhotoBlur {Bitmap bitmap;PhotoBlur(Bitmap b){bitmap=b;}float progress(double now){return .6f;}}
 Paint pBmp=new Paint(),pBlur=new Paint(),photoAddPaint;
 Bitmap xfadeBmp;Matrix xfadeMatrix=new Matrix(),matrix=new Matrix();
 Map<String,PhotoBlur> blurBmps=new HashMap<>();
 static boolean okBmp(Bitmap b){return b!=null;}
 static double[] scale(double[] p,double s){double[] r=p.clone();for(int i=0;i<4;i++)r[i]*=s;return r;}
 static double[] over(double[] src,double[] dst){double[] r=new double[4];
  for(int i=0;i<4;i++)r[i]=src[i]+dst[i]*(1-src[3]);return r;}
 static class Layer {
  double[] parent;int alpha;PorterDuff.Mode mode;boolean isolated;
  Layer(double[] p,int a,PorterDuff.Mode m,boolean iso){parent=p;alpha=a;mode=m;isolated=iso;}
 }
 static class Canvas {
  double[] pixel;List<Layer> layers=new ArrayList<>();int saves,maxDepth,draws,throwOnDraw;
  Canvas(double[] p){pixel=p.clone();}
  int save(){int count=layers.size()+1;layers.add(new Layer(pixel,255,PorterDuff.Mode.SRC_OVER,false));return count;}
  int saveLayer(int l,int t,int w,int h,Paint p){
   if(l!=0||t!=0||w!=17||h!=11)throw new AssertionError("viewport bounds");
   int count=layers.size()+1;layers.add(new Layer(pixel,p==null?255:p.alpha,
    p==null?PorterDuff.Mode.SRC_OVER:p.mode,true));pixel=new double[4];saves++;
   maxDepth=Math.max(maxDepth,layers.size());return count;
  }
  int saveLayerAlpha(int l,int t,int w,int h,int alpha){Paint p=new Paint();p.setAlpha(alpha);return saveLayer(l,t,w,h,p);}
  void restoreToCount(int count){while(layers.size()>=count){Layer l=layers.remove(layers.size()-1);
   if(!l.isolated)continue;
   double[] src=scale(pixel,l.alpha/255d);
   if(l.mode==PorterDuff.Mode.ADD){pixel=new double[4];for(int i=0;i<4;i++)pixel[i]=Math.min(1,src[i]+l.parent[i]);}
   else pixel=over(src,l.parent);
  }}
  void drawBitmap(Bitmap b,Matrix m,Paint p){if(++draws==throwOnDraw)throw new IllegalStateException("injected");
   pixel=over(scale(b.pixel,p.alpha/255d),pixel);}
 }
 /* PRODUCTION */
 static void equal(double[] actual,double[] expected,String message){for(int i=0;i<4;i++)
  if(Math.abs(actual[i]-expected[i])>1e-7)throw new AssertionError("pixel "+message+" channel="+i+" actual="+actual[i]+" expected="+expected[i]);}
 static double[] blend(double[] old,double[] next,int alpha){double p=alpha/255d;double[] r=new double[4];
  for(int i=0;i<4;i++)r[i]=old[i]*(1-p)+next[i]*p;return r;}
 public static void main(String[] args){
  boolean bounded=true;
  for(boolean blur:new boolean[]{false,true})for(boolean lite:new boolean[]{false,true})
   for(double oldAlpha:new double[]{0,.4,1})for(double newAlpha:new double[]{0,.25,1})
   for(double bgAlpha:new double[]{0,.5,1}) {
    AlphaPhoto r=new AlphaPhoto();r.xfadeBmp=new Bitmap(1,.1,.2,oldAlpha);
    Bitmap next=new Bitmap(.1,.3,1,newAlpha),oldBlur=new Bitmap(.6,.2,.4,oldAlpha),nextBlur=new Bitmap(.4,.6,.2,newAlpha);
    double[] background=new Bitmap(.8,.7,.6,bgAlpha).pixel;
    NimarkoBannerConfig.liteMode=lite;
    if(blur)r.blurBmps.put(System.identityHashCode(r.xfadeBmp)+":17x11",new PhotoBlur(oldBlur));
    float coll=.8f,progress=.6f;int blurAlpha=(int)(coll*255*progress);
    double[] old=blur&&!lite?over(scale(oldBlur.pixel,blurAlpha/255d),r.xfadeBmp.pixel):r.xfadeBmp.pixel;
    double[] incoming=blur&&!lite?over(scale(nextBlur.pixel,blurAlpha/255d),next.pixel):next.pixel;
    for(int alpha:new int[]{0,1,64,127,128,192,254,255}){
     Canvas c=new Canvas(background);
     r.drawAlphaPhotoCrossfade(c,next,blur?nextBlur:null,17,11,alpha,coll,progress,1);
     equal(c.pixel,over(blend(old,incoming,alpha),background),"linear premultiplied blend p="+alpha);
     if(alpha==255)equal(c.pixel,over(incoming,background),"endpoint equals drawing new alone after clearXfade");
     if(alpha==0)equal(c.pixel,over(old,background),"endpoint equals decode-gap old");
     bounded &= c.layers.isEmpty()&&c.maxDepth==2&&c.saves==3;
     if(r.pBmp.mode!=PorterDuff.Mode.SRC_OVER||r.pBlur.mode!=PorterDuff.Mode.SRC_OVER)throw new AssertionError("shared paint leaked ADD");
    }
    // Ending at p=254 then clearing the old snapshot can differ by at most
    // one alpha quantum, never by the old PNG hole's full color contribution.
    double[] near=over(blend(old,incoming,254),background),end=over(incoming,background);
    for(int i=0;i<4;i++)if(Math.abs(near[i]-end[i])>1d/255+1e-7)throw new AssertionError("pixel cleanup jump");
   }
  if(!bounded)throw new AssertionError("bounded balanced layers");
  AlphaPhoto r=new AlphaPhoto();r.xfadeBmp=new Bitmap(1,0,0,1);
  for(int failure:new int[]{1,2}){
   Canvas c=new Canvas(new double[4]);c.throwOnDraw=failure;
   try{r.drawAlphaPhotoCrossfade(c,new Bitmap(0,0,0,0),null,17,11,127,0,0,1);
    throw new AssertionError("expected injected failure");}catch(IllegalStateException expected){}
   if(!c.layers.isEmpty())throw new AssertionError("exception leaked layer");
  }
 }
}
'''


if __name__ == '__main__':
    unittest.main()
