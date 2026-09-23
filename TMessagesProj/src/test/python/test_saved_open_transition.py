"""Execute the production delay decision and start guard with minimal Java dependencies."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SOURCE = (Path(__file__).resolve().parents[2] / "main/java/org/telegram/ui/ChatActivity.java").read_text()


class SavedOpenTransitionTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
    def test_actual_decision_and_callback_guard(self):
        decision = SOURCE.split("public boolean needDelayOpenAnimation()", 1)[1].split(
            "private void finishSmoothSavedMessagesTransition()", 1)[0].strip()
        guard = SOURCE.split("public void onTransitionAnimationStart(boolean isOpen, boolean backward) {", 1)[1].split(
            "super.onTransitionAnimationStart(isOpen, backward);", 1)[0]
        java = '''import java.util.ArrayList;
public class SavedOpenHarness {
  static class BaseFragment {}
  static class Layout {
    ArrayList<BaseFragment> stack = new ArrayList<>();
    ArrayList<BaseFragment> getFragmentStack() { return stack; }
  }
  static class ChatActivity extends BaseFragment {
    static final int MODE_SCHEDULED = 1;
    boolean smoothSavedMessagesInitialOpen, savedMessagesOpenTransitionActive;
    boolean fragmentOpened, firstLoading = true, own = true, isInsideContainer, keyboard;
    int chatMode, starts;
    Layout layout;
    boolean isOwnSavedMessagesChat() { return own; }
    boolean isKeyboardVisible() { return keyboard; }
    Layout getParentLayout() { return layout; }
    public boolean needDelayOpenAnimation() DECISION
    void begin(boolean isOpen, boolean backward) { GUARD
      starts++; fragmentOpened = true;
    }
  }
  static void check(boolean value, String name) { if (!value) throw new AssertionError(name); }
  public static void main(String[] args) {
    ChatActivity c = new ChatActivity();
    check(!c.needDelayOpenAnimation(), "first saved open is immediate");
    c.begin(true, false);
    check(!c.needDelayOpenAnimation(), "decision stable after fragmentOpened changes");
    c.begin(true, false);
    check(c.starts == 1, "keyboard repeat cannot replace notification barrier");
    c.firstLoading = false;
    check(!c.needDelayOpenAnimation(), "loading completion cannot change decision");
    c.begin(false, false);
    check(c.starts == 2 && !c.savedMessagesOpenTransitionActive, "back interruption is not swallowed");
    ChatActivity reopened = new ChatActivity();
    check(!reopened.needDelayOpenAnimation(), "new opening has fresh state");
    reopened.begin(true, false);
    check(reopened.starts == 1, "reopening starts normally");
    ChatActivity other = new ChatActivity(); other.own = false;
    check(other.needDelayOpenAnimation(), "ordinary chat delay preserved");
    other.firstLoading = false;
    check(!other.needDelayOpenAnimation(), "cached ordinary chat not delayed");
    ChatActivity embedded = new ChatActivity(); embedded.isInsideContainer = true;
    check(embedded.needDelayOpenAnimation(), "embedded chat policy preserved");
    ChatActivity keyboard = new ChatActivity(); keyboard.own = false;
    keyboard.layout = new Layout();
    ChatActivity previous = new ChatActivity(); previous.keyboard = true;
    keyboard.layout.stack.add(previous); keyboard.layout.stack.add(keyboard);
    check(!keyboard.needDelayOpenAnimation(), "keyboard policy preserved");
    System.out.println("PASS");
  }
}'''.replace("DECISION", decision).replace("GUARD", guard)
        with tempfile.TemporaryDirectory(prefix="saved-open-test-") as folder:
            path = Path(folder) / "SavedOpenHarness.java"
            path.write_text(java)
            subprocess.run(["javac", str(path)], check=True, capture_output=True, text=True)
            result = subprocess.run(["java", "-cp", folder, "SavedOpenHarness"], check=True, capture_output=True, text=True)
            self.assertIn("PASS", result.stdout)

    def test_destroy_releases_barrier_and_guard(self):
        method = SOURCE.split("public void onFragmentDestroy()", 1)[1].split("public View createView(", 1)[0]
        self.assertIn("savedMessagesOpenTransitionActive = false", method)
        self.assertIn("onAnimationFinish(transitionAnimationIndex)", method)

    def test_diagnostics_removed(self):
        self.assertNotIn("traceSavedOpen", SOURCE)
        self.assertNotIn("SavedOpenTrace", SOURCE)


if __name__ == "__main__":
    unittest.main()
