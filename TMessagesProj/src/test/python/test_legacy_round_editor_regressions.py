"""Source-bound regressions; camera/GPU behavior still requires device validation."""
from pathlib import Path
import unittest
from test_search_fallback_entrance import block
from test_sender_infocard_transitions import run_java

JAVA = Path(__file__).resolve().parents[2] / 'main/java'
UI = JAVA / 'org/telegram/ui'


class LegacyRoundEditorTests(unittest.TestCase):
    def test_legacy_encoder_readiness_requires_latched_active_frame(self):
        source = (UI / 'Components/InstantCameraView.java').read_text()
        marker = source.index('if (updatedTexImage1) cameraFrameAvailable[0] = true;')
        start = source.rfind('if (!useCameraX)', 0, marker)
        body = block(source[start:], 'if (!useCameraX)')
        run_java('''
public class Transitions {
    boolean useCameraX, updatedTexImage1, updatedTexImage2, cameraTextureAvailable;
    boolean[] cameraFrameAvailable = new boolean[2], cameraFrameLatched = new boolean[2];
    int surfaceIndex;
    void frame() { BODY }
    void check(boolean ready) { frame(); if(cameraTextureAvailable!=ready) throw new AssertionError(); }
    public static void main(String[] args) {
        Transitions t = new Transitions();
        t.check(false); t.updatedTexImage1=true; t.check(false);
        t.cameraFrameLatched[0]=true; t.check(true);
        t.cameraTextureAvailable=false; t.surfaceIndex=1; t.check(false);
        t.updatedTexImage2=true; t.cameraFrameLatched[1]=true; t.check(true);
        t.cameraTextureAvailable=false; t.surfaceIndex=-1; t.check(false);
        t.surfaceIndex=2; t.check(false);
        t.surfaceIndex=0; t.useCameraX=true; t.check(false);
    }
}
'''.replace('BODY', body))

    def test_both_edge_fades_share_pinch_panel_owner(self):
        source = (UI / 'ChatActivity.java').read_text()
        self.assertIn('view == chatActivityFadeView', block(source, 'private boolean isPinchPanel(View view)'))

    def test_glow_excludes_local_and_active_editors(self):
        source = (UI / 'PhotoViewer.java').read_text()
        draw = block(source[source.index('mediaGlowView = new View(activity)'):], 'protected void onDraw(Canvas canvas)')
        guard = block(draw, 'if (!imagesArrLocals.isEmpty()')
        self.assertIn('return;', guard)
        self.assertIn('currentEditMode != EDIT_MODE_NONE', draw)
        self.assertIn('switchingToMode >= 0', draw)
        self.assertLess(draw.index('return;'), draw.index('MediaGlowController'))

    def test_search_animation_preserves_caption_scope_and_password_privacy(self):
        source = (UI / 'Components/EditTextBoldCursor.java').read_text()
        draw = block(source, 'protected void onDraw(Canvas canvas)')
        self.assertIn('this instanceof EditTextCaption', draw)
        self.assertEqual(draw.count('beforeEditorDraw(this)'), 1)
        self.assertEqual(draw.count('afterEditorDraw(this, canvas)'), 1)
        self.assertLess(draw.index('canvas.restoreToCount(save)'), draw.index('afterEditorDraw'))
        animation = (JAVA / 'app/nimarkogram/messenger/textanim/NimarkoTextAnim.java').read_text()
        guard = block(animation, 'private static boolean canAnimateEditor(EditText edit)')
        for value in ('PasswordTransformationMethod', 'TYPE_TEXT_VARIATION_PASSWORD',
                      'TYPE_TEXT_VARIATION_VISIBLE_PASSWORD', 'TYPE_TEXT_VARIATION_WEB_PASSWORD',
                      'TYPE_NUMBER_VARIATION_PASSWORD'):
            self.assertIn(value, guard)


if __name__ == '__main__':
    unittest.main()
