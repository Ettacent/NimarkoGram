"""Guard deferred first-attach binding of bot button emoji owners."""
from pathlib import Path
import unittest
from test_recording_composer_lifecycle import method


class BotEmojiAttachOrderTest(unittest.TestCase):
    def test_final_buttons_attach_after_deferred_content(self):
        source = (Path(__file__).resolve().parents[2] /
                  'main/java/org/telegram/ui/Cells/ChatMessageCell.java').read_text()
        attach = method(source, 'protected void onAttachedToWindow()')
        for buttons in ('botButtons', 'transitionParams.transitionBotButtons'):
            call = 'setBotButtonEmojiAttached(' + buttons + ', true);'
            self.assertEqual(attach.count(call), 1)
            self.assertLess(attach.index('setMessageContent(messageObjectToSet'), attach.index(call))
            self.assertLess(attach.index('attachedToWindow = true;'), attach.index(call))
            detach = method(source, 'protected void onDetachedFromWindow()')
            self.assertIn('setBotButtonEmojiAttached(' + buttons + ', false);', detach)


if __name__ == '__main__':
    unittest.main()
