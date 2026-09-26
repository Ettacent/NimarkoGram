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
