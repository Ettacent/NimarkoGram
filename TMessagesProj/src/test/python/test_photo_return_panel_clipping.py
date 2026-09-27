"""Exercise real transition math; these are not Android visual tests."""
from pathlib import Path
import unittest

from test_recording_composer_lifecycle import method
from test_photo_story_shared_media_handoff import run_java

UI = Path(__file__).resolve().parents[2] / 'main/java/org/telegram/ui'


class PhotoReturnPanelClippingTests(unittest.TestCase):
    def test_return_viewport_tracks_window_not_bitmap(self):
        source = (UI / 'Components/ClippingImageView.java').read_text()
        methods = '\n'.join(method(source, name) for name in (
            'public void setAnimationValues(',
            'public void setTransitionViewport(',
            'public void setAnimationProgress('))
        run_java('''public class Transitions {
            boolean in, fade, hasTransitionViewport;
            float animationProgress, viewportTop, viewportBottom, viewportHeight;
            float[][] animationValues;
            int clipTop, clipBottom; int[] radius = new int[4];
            float x, y, sx, sy, additionalTranslationX;
            void setScaleX(float v){sx=v;} void setScaleY(float v){sy=v;}
            void setTranslationX(float v){x=v;} void setTranslationY(float v){y=v;}
            float getTranslationY(){return y;} float getScaleY(){return sy;}
            int getHeight(){return 1000;}
            void setClipTop(int v){clipTop=v;} void setClipBottom(int v){clipBottom=v;}
            void setClipHorizontal(int v){} void setRadius(int[] r){}
            void setImageX(int v){} void setImageY(int v){}
            void setAlpha(float v){} void invalidate(){}
            METHODS
            public static void main(String[] args){
                for(float targetY:new float[]{-300, 0, 80, 250, 950}) {
                    Transitions t=new Transitions();
                    float[][] values = new float[2][13];
                    values[0][0]=values[0][1]=1.6f; values[0][3]=-40;
                    values[1][0]=values[1][1]=.5f; values[1][3]=targetY;
                    t.setAnimationValues(values,false,false);
                    t.setTransitionViewport(180,1100,1400);
                    for(int i=0;i<=1000;i++){
                        float p=i/1000f; t.setAnimationProgress(p);
                        assert t.y+t.clipTop >= 180*p-.51f;
                        assert t.y+1000*t.sy-t.clipBottom <= 1400-300*p+.51f;
                    }
                    // Reused for profiles/media grids: no stale chat viewport.
                    t.setAnimationValues(values,false,false);
                    t.setAnimationProgress(1);
                    assert t.clipTop==0 && t.clipBottom==0;
                    t.setAnimationValues(values,true,false);
                    t.setTransitionViewport(180,1100,1400);
                    t.setAnimationProgress(.5f);
                    assert t.clipTop==0 && t.clipBottom==0;
                }
            }
        }'''.replace('METHODS',methods))

    def test_backdrop_pixels_are_outside_cached_cell_draw(self):
        cell = (UI / 'Cells/ChatMessageCell.java').read_text()
        photo = method(cell, 'protected boolean drawPhotoImage(')
        self.assertNotIn('if (drawingGlassBackdrop)', photo)
        chat = (UI / 'ChatActivity.java').read_text()
        capture = method(chat, 'private void drawListImpl(')
        self.assertLess(capture.index('drawListBackdrop('), capture.index('drawPhotoViewerBackdrop('))
        restore = method(chat, 'private void drawPhotoViewerBackdrop(')
        self.assertIn('canvas.concat(child.getMatrix())',restore)
        self.assertIn('canvas.restoreToCount(save)',restore)
        self.assertEqual(chat.count('public void onPreClose() {\n            refreshGlassAfterPhotoViewerClose();'),2)
        viewer = (UI / 'PhotoViewer.java').read_text()
        self.assertIn('if (object.clipTransitionToParent)', viewer)


if __name__ == '__main__':
    unittest.main()
