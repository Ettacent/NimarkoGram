"""Production-method pixel checks; no Gradle/APK or Android GPU coverage."""
import subprocess
import tempfile
import unittest
from pathlib import Path

import test_image_compositing_continuity as pixels
from test_emoji_first_frame_fade import JAVA, method
from test_sender_infocard_transitions import run_java


SCENARIOS = r'''
    static void roundEndpoint(boolean bg) {
        for (boolean thumbOnly : new boolean[]{false,true}) {
            for (float opacity : new float[]{.2f,.7f,1f}) {
                for (int backing=0;backing<4;backing++) {
                    ImageReceiver r = receiver(); r.isRoundVideo = true;
                    r.currentMediaLocation = new Object(); r.overrideAlpha = opacity;
                    if (backing>0) r.staticThumbDrawable = paint(backing==1
                        ? new SvgHelper.SvgDrawable() : new BitmapDrawable(),"static",0,0,1,1,.5,0);
                    if (!thumbOnly && backing==3) r.currentThumbDrawable = paint(new BitmapDrawable(),"thumb",0,1,0,1,.5,0);
                    r.deliver(preview(),thumbOnly ? ImageReceiver.TYPE_THUMB : ImageReceiver.TYPE_IMAGE,false);
                    r.frame(NOW,bg);
                    Canvas before = at(r,.4f,bg);
                    AnimatedFileDrawable m = paint(new AnimatedFileDrawable(),"video",1,0,0,1,.5,0);
                    r.deliver(m,ImageReceiver.TYPE_MEDIA,false); r.frame(NOW,bg);
                    m.ready=true;
                    Canvas start=r.frame(NOW,bg);
                    close(before,start,1.0/255,"endpoint test retains entry continuity");
                    Canvas end=new Canvas(); end.composite(m,(int)(opacity*255));
                    for (String label : new String[]{"preview","video"}) {
                        Canvas failed=new Canvas(); failed.failOn=label;
                        r.currentAlpha=.5f; r.lastUpdateAlphaTime=NOW;
                        boolean caught=false;
                        try { r.draw(failed,bg ? r.setDrawInBackgroundThread(null,0) : null); }
                        catch (AssertionError expected) { caught=true; }
                        check(caught && failed.stack.isEmpty(),"round layers unwind on "+label+" failure");
                    }
                    if(bg) {
                        r.currentAlpha=.5f; r.lastUpdateAlphaTime=NOW;
                        ImageReceiver.BackgroundThreadDrawHolder holder=r.setDrawInBackgroundThread(null,0);
                        r.imageX=900; r.imageY=800; r.imageW=700; r.imageH=600;
                        Canvas snapshot=new Canvas(); r.draw(snapshot,holder);
                        for(float[] bounds:snapshot.bounds) {
                            float inset=AndroidUtilities.roundMessageInset+1;
                            check(bounds[0]==10-inset && bounds[1]==20-inset
                                && bounds[2]==42+inset && bounds[3]==52+inset,"round layer uses expanded snapshot bounds");
                        }
                        r.imageX=10; r.imageY=20; r.imageW=32; r.imageH=32;
                    }
                    Canvas previous=start;
                    double distance=start.distance(end);
                    for (float progress : new float[]{.01f,.1f,.25f,.5f,.75f,.9f,.99f,.999f,1f}) {
                        Canvas next=at(r,progress,bg);
                        check(next.distance(end)<=distance+1e-9,"distance to endpoint must decrease monotonically");
                        double weight=(int)(progress*255)/255.0;
                        for(int p=0;p<3;p++) for(int c=0;c<4;c++) {
                            double expected=start.pixels[p][c]*(1-weight)+end.pixels[p][c]*weight;
                            check(Math.abs(next.pixels[p][c]-expected)<1e-9,
                                "round must interpolate complete premultiplied endpoints, progress="+progress);
                        }
                        if(progress>=.999f) close(previous,next,3.0/255,"no near-end removal snap");
                        distance=next.distance(end); previous=next;
                        check(next.stack.isEmpty(),"round layers restored");
                        for(float[] bounds:next.bounds) {
                            float inset=AndroidUtilities.roundMessageInset+1;
                            check(bounds[0]==10-inset && bounds[1]==20-inset
                                && bounds[2]==42+inset && bounds[3]==52+inset,"round layer is bounded and includes inset");
                        }
                    }
                    close(previous,end,0,"exact endpoint");
                    check(previous.layerCount==0,"no layers after completion");
                }
            }
        }
    }
    static void roundLifecycle(boolean bg) {
        ImageReceiver r = receiver(); r.isRoundVideo = true;
        r.currentMediaLocation = new Object();
        r.staticThumbDrawable = paint(new BitmapDrawable(), "static", 0,0,1, 1,.5,0);
        r.currentThumbDrawable = paint(new BitmapDrawable(), "thumb", 0,1,0, 1,.5,0);
        r.deliver(preview(), ImageReceiver.TYPE_IMAGE, false); r.frame(NOW,bg);
        r.previousAlpha = .2f;
        Canvas before = at(r,.4f,bg);
        // Cache ownership may be consumed independently of drawn-preview history.
        r.loadingPlaceholderGeneration = -1;
        AnimatedFileDrawable media = paint(new AnimatedFileDrawable(), "video", 1,0,0, 1,1,1);
        media.ready = true;
        r.deliver(media, ImageReceiver.TYPE_MEDIA, true);
        check(r.crossfadeOnReadyDrawable == null, "exercise ready-cache fallback handoff");
        close(before,r.frame(NOW,bg),1.0/255,"ready-cache handoff snapshots both preview weights");
        check(r.roundPreviewPreviousAlpha == .2f && r.roundPreviewWithThumb,"fresh snapshot");
        r.setCrossfadeOnReady(false);
        check(r.roundPreviewPreviousAlpha == 1f && !r.roundPreviewWithThumb,"opt-out resets snapshot");
        r.clearImage(); r.setCrossfadeOnReady(true); r.isRoundVideo = true;
        r.currentImageKey = "new-image"; r.currentMediaKey = "new-media";
        r.currentMediaLocation = new Object();
        AnimatedFileDrawable image = new AnimatedFileDrawable();
        r.deliver(image,ImageReceiver.TYPE_IMAGE,true);
        check(r.crossfadeOnReadyDrawable == image,"image-slot decode owns first gate");
        media = new AnimatedFileDrawable(); media.ready = true; media.blockPromotion = true;
        r.deliver(media,ImageReceiver.TYPE_MEDIA,true);
        check(r.crossfadeOnReadyDrawable == media,"queued media must replace image gate, not cancel it");
        r.frame(NOW,bg);
        check(r.crossfadeOnReadyDrawable == media,"queued media cannot consume first-frame fade");
        media.blockPromotion = false;
        r.frame(NOW,bg);
        check(r.currentAlpha == 0 && r.crossfadeOnReadyDrawable == null,"first renderable media starts at zero");
        r.setCurrentAccount(1);
        check(r.roundPreviewPreviousAlpha == 1f && !r.roundPreviewWithThumb,"account reset");
    }
    static void roundEntry(boolean bg) {
        roundEndpoint(bg);
        roundLifecycle(bg);
        for (boolean cached : new boolean[]{false,true}) {
            for (int backing = 0; backing < 3; backing++) {
                ImageReceiver r = receiver(); r.isRoundVideo = true;
                r.currentMediaLocation = new Object();
                if (backing > 0) r.staticThumbDrawable = paint(backing == 1
                    ? new SvgHelper.SvgDrawable() : new BitmapDrawable(), "static", 0,0,1, 1,.5,0);
                r.deliver(preview(),ImageReceiver.TYPE_THUMB,false);
                Canvas before = at(r,.4f,bg);
                AnimatedFileDrawable m = paint(new AnimatedFileDrawable(),"video",1,0,0,1,1,1);
                r.deliver(m,ImageReceiver.TYPE_MEDIA,cached);
                close(before,r.frame(NOW,bg),1.0/255,"thumb-only delivery continuity");
                Canvas waiting = r.frame(NOW+8,bg);
                check(r.currentAlpha > .4f,"pending thumb fade progresses");
                m.ready = true;
                r.forceNotMedia = true;
                waiting = r.frame(NOW+16,bg);
                check(r.crossfadeOnReadyDrawable == m && r.currentAlpha > .4f,"hidden media does not consume handoff");
                r.forceNotMedia = false;
                close(waiting,r.frame(NOW+16,bg),1.0/255,"thumb-only readiness continuity");
                check(r.currentAlpha == 0 && r.roundPreviewFromThumb,"thumb handoff starts once");
                for (int i=1;i<=30;i++) r.frame(NOW+8+i*16,bg);
                check(!r.roundPreviewFromThumb && r.currentAlpha==1,"thumb handoff completes");
            }
        }
        for (boolean late : new boolean[]{false, true}) {
            for (boolean cached : new boolean[]{false, true}) {
                for (int backing = 0; backing < 4; backing++) {
                    ImageReceiver r = receiver(); r.isRoundVideo = true;
                    r.currentMediaLocation = new Object();
                    r.overrideAlpha = .7f;
                    if (backing > 0) r.staticThumbDrawable = paint(backing == 1
                        ? new SvgHelper.SvgDrawable() : new BitmapDrawable(), "static", 0,0,1, 1,.5,0);
                    if (backing == 3) r.currentThumbDrawable = paint(new BitmapDrawable(), "thumb", 0,1,0, 1,.5,0);
                    BitmapDrawable p = preview();
                    AnimatedFileDrawable m = paint(new AnimatedFileDrawable(), "video", 1,0,0, 1,1,1);
                    m.isWebmSticker = false;
                    if (late) r.deliver(m, ImageReceiver.TYPE_MEDIA, cached);
                    r.deliver(p, ImageReceiver.TYPE_IMAGE, false);
                    r.frame(NOW, bg);
                    r.previousAlpha = .3f;
                    Canvas before = at(r, .4f, bg);
                    if (!late) r.deliver(m, ImageReceiver.TYPE_MEDIA, cached);
                    Canvas waiting = r.frame(NOW, bg);
                    close(before, waiting, 1.0/255, "delivery must preserve partial round composition");
                    r.frame(NOW + 8, bg);
                    check(r.currentAlpha > .4f, "preview clock must not freeze while decoding");
                    Canvas lastPreview = r.frame(NOW + 8, bg);
                    m.ready = true; m.blockPromotion = true;
                    close(lastPreview, r.frame(NOW + 8, bg), 1.0/255,
                        "queued frame must not consume handoff before promotion");
                    check(r.crossfadeOnReadyDrawable == m, "still awaiting rendering buffer");
                    m.blockPromotion = false;
                    r.forceNotMedia = true;
                    lastPreview = r.frame(NOW + 16, bg);
                    check(r.crossfadeOnReadyDrawable == m, "forced preview retains pending handoff");
                    r.forceNotMedia = false;
                    Canvas first = r.frame(NOW + 16, bg);
                    close(lastPreview, first, 1.0/255, "first moving frame must preserve composited preview opacity");
                    check(r.currentAlpha == 0, "first ready snapshot starts at zero");
                    r.deliver(m, ImageReceiver.TYPE_MEDIA, cached);
                    close(first, r.frame(NOW + 16, bg), 0, "duplicate delivery is idempotent");
                    for (int i = 1; i <= 30; i++) r.frame(NOW + 8 + i * 16, bg);
                    check(r.currentAlpha == 1 && !r.crossfadeFromImage, "one-shot fade completes");
                    Canvas endpoint = new Canvas(); endpoint.composite(m, (int)(.7f*255));
                    close(endpoint, r.frame(NOW + 600, bg), 0, "completed fade has no preview residue");
                    r.clearImage();
                    check(r.crossfadeOnReadyDrawable == null && !r.crossfadeFromImage,
                        "recycle clears handoff ownership");
                }
            }
        }
    }
'''


class RoundEntryCrossfadeTest(unittest.TestCase):
    def test_real_rendering_readiness(self):
        drawable = (JAVA / 'ui/Components/AnimatedFileDrawable.java').read_text()
        run_java('''public class Transitions {
            Object frameLock = new Object(), renderingBuffer, nextRenderingBuffer;
            boolean destroyWhenDone, isRunning, loadable = true;
            boolean canLoadFrames() { return loadable; }
            READINESS
            static void check(boolean b) { if (!b) throw new AssertionError(); }
            public static void main(String[] args) {
                Transitions t = new Transitions();
                check(!t.hasRenderingBitmap());
                // Paused decoder with only a queued next buffer is not renderable.
                t.isRunning = false;
                t.nextRenderingBuffer = new Object(); check(!t.hasRenderingBitmap());
                t.renderingBuffer = t.nextRenderingBuffer; check(t.hasRenderingBitmap());
                t.destroyWhenDone = true; check(!t.hasRenderingBitmap());
                t.destroyWhenDone = false; t.loadable = false; check(!t.hasRenderingBitmap());
                t.loadable = true; t.renderingBuffer = null; check(!t.hasRenderingBitmap());
            }
        }'''.replace('READINESS', method(drawable, 'public boolean hasRenderingBitmap(')))

    def test_round_entry_pixels(self):
        # Reuse the premultiplied pixel oracle and verbatim production methods.
        pixels.ImageCompositingContinuityTest.setUpClass()
        self.addCleanup(pixels.ImageCompositingContinuityTest.doClassCleanups)
        source = pixels.ImageCompositingContinuityTest.production_source
        source = source.replace('public class EmojiFirstFrameHarness {',
                                'public class EmojiFirstFrameHarness {' + SCENARIOS)
        source = source.replace('switch (args[0]) {',
                                'switch (args[0]) { case "roundEntry": roundEntry(bg); break;')
        self.assertIn('case "roundEntry"', source)
        with tempfile.TemporaryDirectory(prefix='round-entry-') as tmp:
            java = Path(tmp) / 'EmojiFirstFrameHarness.java'
            java.write_text(source)
            compiled = subprocess.run(['javac', '-d', tmp, str(java)], capture_output=True, text=True, timeout=30)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            for bg in ('false', 'true'):
                with self.subTest(background=bg):
                    result = subprocess.run(['java', '-ea', '-cp', tmp, 'EmojiFirstFrameHarness',
                                             'roundEntry', bg], capture_output=True, text=True, timeout=30)
                    self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            for label, old, new in (
                ('opaque promotion', 'previousAlpha = isRoundVideo ? currentAlpha : 1f;', 'previousAlpha = 1f;'),
                ('delivery reset', 'if (hasRoundVideoPreview()) return;', ''),
                ('ready-cache snapshot', '''if (startedImageHandoff) {
            roundPreviewPreviousAlpha = previousAlpha;
            roundPreviewWithThumb = crossfadeWithThumb;''', 'if (startedImageHandoff) {'),
                ('queued readiness', 'return ((AnimatedFileDrawable) drawable).hasRenderingBitmap();',
                 'return ((AnimatedFileDrawable) drawable).hasBitmap();'),
                ('outgoing endpoint weight',
                 'canvas.saveLayerAlpha(left, top, right, bottom, 255 - incomingAlpha)',
                 'canvas.saveLayerAlpha(left, top, right, bottom, 255)'),
            ):
                with self.subTest(negative_control=label):
                    self.assertEqual(source.count(old), 1)
                    java.write_text(source.replace(old, new))
                    compiled = subprocess.run(['javac', '-d', tmp, str(java)],
                                              capture_output=True, text=True, timeout=30)
                    self.assertEqual(compiled.returncode, 0, compiled.stderr)
                    result = subprocess.run(['java', '-ea', '-cp', tmp, 'EmojiFirstFrameHarness',
                                             'roundEntry', 'false'], capture_output=True, text=True, timeout=30)
                    self.assertNotEqual(result.returncode, 0, 'regression mutation survived')
                    self.assertIn('AssertionError', result.stderr)


if __name__ == '__main__':
    unittest.main()
