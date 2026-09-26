"""Regression guards for recipient changes and keyboard media parameters."""
from pathlib import Path
import unittest
from test_search_fallback_entrance import block
from test_sender_infocard_transitions import run_java

JAVA = Path(__file__).resolve().parents[2] / 'main/java/org/telegram'


class ForwardKeyboardTest(unittest.TestCase):
    def test_preview_selection_survives_recipient_change(self):
        preview = (JAVA / 'messenger/MessagePreviewParams.java').read_text()
        run_java('''
import java.util.*;
public class Transitions {
 static class MessageObject {int id;MessageObject(int n){id=n;}int getId(){return id;}}
 static class Selection {Set<Integer> ids=new HashSet<>();boolean get(int id,boolean fallback){return ids.contains(id);}}
 ArrayList<MessageObject> messages=new ArrayList<>();Selection selectedIds=new Selection();
 SELECT
 public static void main(String[] args){
  Transitions t=new Transitions();for(int i=1;i<=4;i++)t.messages.add(new MessageObject(i));
  t.selectedIds.ids.add(1);t.selectedIds.ids.add(4);
  ArrayList<MessageObject> out=new ArrayList<>();out.add(new MessageObject(99));
  t.getSelectedMessages(out);
  if(out.size()!=2||out.get(0).id!=1||out.get(1).id!=4)throw new AssertionError();
  t.selectedIds.ids.clear();t.getSelectedMessages(out);if(!out.isEmpty())throw new AssertionError();
 }
}
'''.replace('SELECT', block(preview, 'public void getSelectedMessages(')))
        chat = (JAVA / 'ui/ChatActivity.java').read_text()
        choose = block(chat, 'protected void selectAnotherChat(boolean forward)')
        self.assertIn('selectedMessagesIds[0].clear()', choose)
        self.assertIn('selectedMessagesIds[1].clear()', choose)
        self.assertIn('forwardMessages.selectedIds.get(messageObject.getId(), false)', choose)
        self.assertIn('recipientForwardSource.forwardMessages.getSelectedMessages(fmessages)', chat)

    def test_keyboard_preserves_schedule_and_notify(self):
        enter = (JAVA / 'ui/Components/ChatActivityEnterView.java').read_text()
        send = block(enter, 'private void send(InputContentInfoCompat inputContentInfo, boolean notify, int scheduleDate, int scheduleRepeatPeriod)')
        calls = [line for line in send.splitlines() if 'SendMessagesHelper.prepareSending' in line]
        self.assertEqual(len(calls), 2)
        self.assertTrue(all('notify, scheduleDate, scheduleRepeatPeriod,' in line for line in calls))
        self.assertIn('delegate.onMessageSend(null, notify, scheduleDate, scheduleRepeatPeriod, 0)', send)

    def test_photo_and_document_helpers_forward_metadata(self):
        helper = (JAVA / 'messenger/SendMessagesHelper.java').read_text()
        self.assertIn('replyToMsg, replyToTopMsg, null, quote, entities, stickers, inputContent, ttl, editingMessageObject, null, notify, scheduleDate, scheduleRepeatPeriod,', helper)
        self.assertIn('prepareSendingMedia(accountInstance, infos, dialogId, replyToMsg, replyToTopMsg, storyItem, quote, forceDocument, false, editingMessageObject, notify, scheduleDate, scheduleRepeatPeriod,', helper)
        self.assertIn('editingMessageObject, notify, scheduleDate, scheduleRepeatPeriod, inputContent, sendMessageChatArguments, 0, invertMedia, 0, 0, null)', helper)


if __name__ == '__main__':
    unittest.main()
