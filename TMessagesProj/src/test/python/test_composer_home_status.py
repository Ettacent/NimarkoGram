import shutil
import unittest
from pathlib import Path

from test_recording_wave_geometry import wave_harness
from test_recording_composer_lifecycle import method as extract_method
from test_sender_infocard_transitions import run_java

JAVA = Path(__file__).resolve().parents[2] / "main/java/org/telegram/ui"


class ComposerHomeStatusTests(unittest.TestCase):
    def test_recording_does_not_expand_center_pill(self):
        source = (JAVA / "Components/chat/ChatInputViewsContainer.java").read_text()
        method = source.split("public void getInputBubbleDrawableBounds", 1)[1].split("private float separatedComposerProgress", 1)[0]
        self.assertNotIn("recordingComposerProgress", method)
        self.assertIn("leadingComposerExpansionProgress", method)
        sync = source.split("private void syncLeadingComposerExpansion()", 1)[1]
        self.assertIn("recordingComposerAnchor.getVisibility() == VISIBLE", sync)
        self.assertIn("&& !recordingPanelVisible", sync)
        self.assertNotIn("recordingComposerTarget", sync.split("final float target", 1)[0])
        source = (JAVA / "Components/ChatActivityEnterView.java").read_text()
        overlay = extract_method(source, "private void createRecordCircle()")
        self.assertIn("sizeNotifierLayout.addView(recordCircle", overlay)
        draw = extract_method(source.split("public class RecordCircle", 1)[1],
                              "protected void onDraw(Canvas canvas)")
        self.assertNotIn("requestLayout(", draw)
        self.assertNotIn("setLayoutParams(", draw)

    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
    def test_recording_waves_react_and_share_cancel_origin(self):
        # Execute the real draw path: nominal radii alone missed waves covered
        # by the core and the offset introduced by the overlay's parent.
        run_java(wave_harness())

    def test_stories_header_retains_outgoing_status(self):
        source = (JAVA / "Stories/DialogStoriesCell.java").read_text()
        helper = source.split("private void setHeaderText", 1)[1].split("public void setClipTop", 1)[0]
        self.assertIn("if (TextUtils.isEmpty(text))", helper)
        self.assertIn("return;", helper)
        self.assertIn("animatorHasTitleText.getFloatValue() >= 0.999f", helper)
        self.assertIn("setAnimationProperties(0f, 0, 300", source)
        self.assertIn("animatorHasTitleText.setValue(hasOverlayText || !TextUtils.isEmpty(currentTitle), true)", source)
        self.assertNotIn("titleView.setText(currentTitle,", source)

    def test_cancellation_panel_reserves_both_side_controls(self):
        source = (JAVA / "Components/ChatActivityEnterView.java").read_text()
        method = source.split("private void attachRecordPanelToComposerHost()", 1)[1].split(
            "public boolean onInterceptTouchEvent", 1)[0]
        self.assertIn("params.leftMargin = params.rightMargin = dp(", method)
        self.assertIn("if (separatedComposerLayout)", method)
        self.assertNotIn("ChatObject", method)  # same geometry in groups and private chats
        self.assertIn("? textFieldContainer : messageEditTextContainer", method)

    def test_home_status_crossfades_without_vertical_motion(self):
        source = (JAVA / "ActionBar/ActionBar.java").read_text()
        method = source.split("public void setTitleOverlayText(String title, int titleId, boolean gilroy", 1)[1].split(
            "public boolean isSearchFieldVisible", 1)[0]
        self.assertIn("parentFragment instanceof org.telegram.ui.DialogsActivity", method)
        self.assertIn("homeStatusCrossfade ? 300 : 220", method)
        self.assertIn("setTranslationY(homeStatusCrossfade ? 0 : -dp(20))", method)
        self.assertIn("titleTextView[1].setTranslationY(0)", method)
        self.assertEqual(method.count("setDuration(overlayDuration)"), 2)
        self.assertIn("overlayTitleToSet[0]", method)  # latest rapid update remains queued


if __name__ == "__main__":
    unittest.main()
