"""Pinch has one image owner; glass must not retain a stationary thumbnail."""
from pathlib import Path
import unittest
from test_search_fallback_entrance import block
from test_sender_infocard_transitions import run_java

UI = Path(__file__).resolve().parents[2] / "main/java/org/telegram/ui"


class PinchBackdropTests(unittest.TestCase):
    def test_glass_samples_moving_image_without_advancing_or_recursing(self):
        helper = (UI / 'PinchToZoomHelper.java').read_text()
        draw = block(helper, 'public void drawBackdrop(Canvas canvas, View sourceRoot)')
        self.assertIn('overlayView.drawImage(canvas, false)', draw)
        self.assertIn('hasMediaSpoiler', draw)
        self.assertIn('childTextureViewContainer != null', draw)
        self.assertIn('childImage.setSkipUpdateFrame(true)', draw)
        self.assertIn('finally', draw)
        self.assertIn('childImage.setSkipUpdateFrame(false)', draw)
        self.assertNotIn('dispatchDraw', draw)
        render = block(helper, 'private void drawImage(Canvas canvas, boolean advanceFrame)')
        self.assertIn('advanceFrame && progressToFullView != 1', render)
        self.assertIn('progressToFullView * (finishTransition != null ? finishProgress : 1f)', render)
        chat = (UI / 'ChatActivity.java').read_text()
        self.assertIn('pinchToZoomHelper.drawBackdrop(blurCanvas, parent)', chat)

    def test_cached_decor_is_hidden_before_photo_returns(self):
        helper = (UI / 'PinchToZoomHelper.java').read_text()
        clear = block(helper, 'public void clear()')
        hidden = clear.index('overlayView.setVisibility(View.INVISIBLE)')
        self.assertLess(hidden, clear.index('callback.onZoomFinished'))
        self.assertLess(hidden, clear.index('inOverlayMode = false'))
        self.assertLess(hidden, clear.index('AndroidUtilities.removeFromParent'))

    def test_capture_follows_gesture_and_final_handoff_but_not_idle(self):
        source = (UI / "ChatActivity.java").read_text()
        start = source.index("final boolean pinchVisible = pinchToZoomHelper != null")
        end = source.index("return super.onPreDraw();", start)
        body = source[start:end]
        run_java(r'''
public class Transitions {
    static final int BLUR_INVALIDATE_FLAG_SCROLL=1, BLUR_INVALIDATE_FLAG_POSITIONS=2;
    static class Helper { boolean active; boolean isInOverlayMode(){return active;} }
    Helper pinchToZoomHelper;
    boolean wasPinchOverlayVisible; int flags;
    void invalidateMergedVisibleBlurredPositionsAndSources(int f){flags |= f;}
    void frame(){flags=0; BODY }
    void check(int expected){frame();if(flags!=expected)throw new AssertionError(flags+" != "+expected);}
    public static void main(String[] args){
        Transitions t=new Transitions();t.check(0);
        t.pinchToZoomHelper=new Helper();t.pinchToZoomHelper.active=true;t.check(3);
        for(int i=0;i<120;i++)t.check(1);
        t.pinchToZoomHelper.active=false;t.check(3);t.check(0);t.check(0);
        t.pinchToZoomHelper.active=true;t.check(3);
        t.pinchToZoomHelper=null;t.check(3);t.check(0);
    }
}
'''.replace('BODY', body))

    def test_floating_panels_have_one_owner_during_pinch(self):
        source = (UI / "ChatActivity.java").read_text()
        panels = block(source, "private boolean isPinchPanel(View view)")
        for name in ("topPanelLayout", "actionBar", "actionBarSearchTags", "hashtagSearchTabs", "chatInputViewsContainer"):
            self.assertIn("view == " + name, panels)
        draw = block(source, "private void drawPinchPanels(Canvas canvas, float alpha)")
        self.assertIn("super.drawChild(canvas, panel, drawingTime)", draw)
        self.assertIn("panel.getVisibility() == VISIBLE", draw)
        self.assertIn("canvas.restoreToCount(save)", draw)
        self.assertIn("contentView.drawPinchPanels(canvas, alpha)", source)
        self.assertIn("if (isPinchPanel(child)", source)
        # Move the composer as one owner, not its child controls a second time.
        self.assertNotIn("view == bottomChannelButtonsLayout", panels)
        self.assertIn("chatInputBubbleContainer.addView(bottomChannelButtonsLayout", source)
        self.assertIn("chatInputBubbleContainer.addView(chatActivityEnterView", source)

    def test_floating_header_padding_is_not_a_pinch_clip(self):
        source = (UI / "ChatActivity.java").read_text()
        listener = block(source, "pinchToZoomHelper.setClipBoundsListener(topBottom ->")
        self.assertIn("topBottom[0] = Math.max(0, chatListView.getTop())", listener)
        self.assertNotIn("chatListViewPaddingTop", listener)
        self.assertNotIn("drawUnzoomedEdges", (UI / "Cells/ChatMessageCell.java").read_text())
        self.assertNotIn("drawUnzoomedEdges", (UI / "PinchToZoomHelper.java").read_text())

    def test_all_photo_branches_exclude_stationary_pinch_copy(self):
        source = (UI / "Cells/ChatMessageCell.java").read_text()
        self.assertIn("final boolean pinchToZoomDrawing = isPhotoInPinchOverlay();", source)
        self.assertNotIn("!drawingGlassBackdrop && isPhotoInPinchOverlay()", source)
        self.assertNotIn("if (!pinchToZoomDrawing || fitPhotoImage)", source)
        self.assertEqual(source.count("if (!isPhotoInPinchOverlay())"), 3)
        draw = block(source, "protected boolean drawPhotoImage(Canvas canvas)")
        self.assertIn("return false;", block(draw, "if (isPhotoInPinchOverlay())"))
        self.assertLess(draw.index("if (isPhotoInPinchOverlay())"), draw.index("drawPhotoImageInternal(canvas)"))

    def test_hidden_image_capture_keeps_privacy_and_frame_guards(self):
        source = (UI / "Cells/ChatMessageCell.java").read_text()
        draw = block(source, "protected boolean drawPhotoImage(Canvas canvas)")
        self.assertNotIn("|| drawingGlassBackdrop && isPhotoInPinchOverlay()", draw)
        self.assertIn("&& PhotoViewer.isShowingImage(currentMessageObject)", draw)
        for guard in ("!currentMessageObject.needDrawBluredPreview()",
                      "!currentMessageObject.hasMediaSpoilers()",
                      "!SecretMediaViewer.getInstance().isShowingImage(currentMessageObject)",
                      "!StoryViewer.isShowingImage(currentMessageObject)"):
            self.assertIn(guard, draw)
            self.assertLess(draw.index(guard), draw.index("if (drawingGlassBackdrop)"))
        capture = block(draw, "if (drawingGlassBackdrop)")
        self.assertIn("photoImage.setSkipUpdateFrame(true)", capture)
        self.assertIn("return drawn | photoImage.drawIgnoringVisibility(canvas)", capture)
        self.assertNotIn("setVisible(true", draw)


if __name__ == "__main__":
    unittest.main()
