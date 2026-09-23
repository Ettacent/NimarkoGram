"""Run the production online-dot state machine with the real AnimatedFloat."""
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

from test_banner_background_stability import block

JAVA = Path(__file__).resolve().parents[2] / "main/java"
CELL = JAVA / "org/telegram/ui/Cells/ChatMessageCell.java"


class GroupOnlineDotTests(unittest.TestCase):
    def source(self):
        cell = CELL.read_text()
        methods = "\n".join(block(cell, s) for s in (
            "private boolean isSenderOnline()", "private boolean recomputeSenderOnlineIfStale(",
            "private void bindOnlineIndicator()", "private void resetOnlineIndicatorOnAttach()",
            "public void invalidateSenderOnlineStatus()",
            "public void drawOnlineIndicator(",
        ))
        animator = (JAVA / "org/telegram/ui/Components/AnimatedFloat.java").read_text()
        animator = animator[animator.index("public class AnimatedFloat"):].replace(
            "public class AnimatedFloat", "class AnimatedFloat", 1)
        field = re.search(r"private final AnimatedFloat onlineIndicatorProgress = [^;]+;", cell)[0]
        return r'''
import java.util.*;
public class Harness extends View {
 FIELD
 METHODS
 boolean onlineIndicatorInvalidateRetargeted, senderIsOnline, isChat=true, isAvatarVisible=true;
 int onlineIndicatorAccount=-1, currentAccount;
 long onlineIndicatorSenderId, onlineIndicatorDialogId, senderOnlineLastComputeMs;
 Paint onlineIndicatorPaint;
 TLRPC.User currentUser=new TLRPC.User(7);
 Message currentMessageObject=new Message();
 View parent=new View();
 View getParent(){return parent;}
 int getThemedColor(int key){return key;}
 static class Message { long dialog=-100; long getDialogId(){return dialog;} }
 float draw(){ Canvas c=new Canvas(); drawOnlineIndicator(c,new ImageReceiver()); return c.radius/4f; }
 static Harness fresh(){
  SystemClock.now=1000; app.nimarkogram.messenger.NimarkoConfig.onlineIndicatorInGroups=true;
  return new Harness();
 }
 static void near(float a,float b,String why){if(Math.abs(a-b)>.001f)throw new AssertionError(why+": "+a+" != "+b);}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static void fullyShow(Harness h){h.draw();SystemClock.now+=300;near(h.draw(),1,"show endpoint");}
 public static void main(String[] args){
  Harness h=fresh(); h.resetOnlineIndicatorOnAttach(); h.bindOnlineIndicator();
  SystemClock.now+=5000; near(h.draw(),0,"first visible frame starts at zero");
  SystemClock.now+=150; near(h.draw(),.5f,"midpoint is visible");
  check(h.parent.invalidations>0,"parent-owned dot gets intermediate frames");
  h.bindOnlineIndicator();near(h.draw(),.5f,"same sender rebind keeps animation");
  SystemClock.now+=150; near(h.draw(),1,"first reveal completes");
  h.bindOnlineIndicator(); near(h.draw(),1,"settled edit does not restart");
  h.resetOnlineIndicatorOnAttach(); SystemClock.now+=5000;
  near(h.draw(),0,"cached reattach starts at first draw, not attach time");fullyShow(h);
  h.currentUser=new TLRPC.User(8);h.currentUser.status.expires=0;h.bindOnlineIndicator();
  near(h.draw(),0,"offline recycled sender never inherits phantom dot");
  h.currentUser=new TLRPC.User(9);h.bindOnlineIndicator();
  near(h.draw(),0,"new online sender starts at zero");fullyShow(h);
  h.currentAccount=1;h.bindOnlineIndicator();near(h.draw(),0,"account ownership resets dot");fullyShow(h);
  h.currentMessageObject.dialog=-200;h.bindOnlineIndicator();near(h.draw(),0,"dialog ownership resets dot");fullyShow(h);
  h.currentUser.status.expires=0;h.invalidateSenderOnlineStatus();
  near(h.draw(),1,"offline update preserves outgoing dot");
  SystemClock.now+=150;near(h.draw(),.5f,"offline transition fades");
  SystemClock.now+=150;near(h.draw(),0,"offline endpoint");
  h.currentUser.status.expires=1000;h.bindOnlineIndicator();fullyShow(h);
  app.nimarkogram.messenger.NimarkoConfig.onlineIndicatorInGroups=false;
  near(h.draw(),1,"setting off starts from visible state");
  SystemClock.now+=300;near(h.draw(),0,"setting off completes");
  app.nimarkogram.messenger.NimarkoConfig.onlineIndicatorInGroups=true;
  near(h.draw(),0,"setting on starts at zero");fullyShow(h);
  h.isAvatarVisible=false;h.bindOnlineIndicator();near(h.draw(),0,"no dot without sender avatar");
  for(int mode=0;mode<4;mode++){
   h=fresh();if(mode==0)h.currentUser.self=true;if(mode==1)h.currentUser.bot=true;
   if(mode==2)h.currentUser.support=true;if(mode==3)h.isChat=false;
   h.bindOnlineIndicator();h.draw();SystemClock.now+=300;near(h.draw(),0,"ineligible sender stays hidden");
  }
  System.out.println("PASS online dot lifecycle");
 }
}
class View {int invalidations;void invalidate(){invalidations++;}}
class Paint {static final int ANTI_ALIAS_FLAG=1;Paint(int flags){}void setColor(int color){}}
class Canvas {float radius;void drawCircle(float x,float y,float r,Paint p){radius=r;}}
class ImageReceiver {float getImageX2(){return 40;}float getImageY2(){return 40;}}
class Theme {static final int key_windowBackgroundWhite=1,key_chats_onlineCircle=2;}
class SystemClock {static long now;static long elapsedRealtime(){return now;}}
interface TimeInterpolator {float getInterpolation(float t);}
class CubicBezierInterpolator {static final TimeInterpolator EASE_OUT=t->t,DEFAULT=t->t;}
class MathUtils {static float clamp(float x,float lo,float hi){return Math.max(lo,Math.min(hi,x));}}
class AndroidUtilities {static float dpf2(float x){return x;}static float lerp(float a,float b,float p){return a+(b-a)*p;}}
class TLRPC {
 static class Status {int expires=1000;}
 static class User {long id;boolean self,bot,support;Status status=new Status();User(long id){this.id=id;}}
}
class MessagesController {
 static final MessagesController instance=new MessagesController();
 Map<Long,Integer> onlinePrivacy=new HashMap<>();
 static MessagesController getInstance(int account){return instance;}
 static boolean isSupportUser(TLRPC.User u){return u.support;}
}
class ConnectionsManager {
 static ConnectionsManager getInstance(int account){return new ConnectionsManager();}
 int getCurrentTime(){return 100;}
}
class app {static class nimarkogram {static class messenger {static class NimarkoConfig {
 static boolean onlineIndicatorInGroups=true;
}}}}
ANIMATOR
'''.replace(" FIELD", field).replace(" METHODS", methods).replace("ANIMATOR", animator)

    def run_java(self, source):
        with tempfile.TemporaryDirectory(prefix="online-dot-") as temp:
            path = Path(temp) / "Harness.java"
            path.write_text(source)
            compile_result = subprocess.run(["javac", str(path)], capture_output=True, text=True, timeout=30)
            self.assertEqual(compile_result.returncode, 0, compile_result.stderr)
            return subprocess.run(["java", "-cp", temp, "Harness"], capture_output=True, text=True, timeout=30)

    def test_real_dot_lifecycle(self):
        result = self.run_java(self.source())
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_old_bind_snap_is_detected(self):
        source = self.source().replace("onlineIndicatorProgress.force(0f);",
                                     "onlineIndicatorProgress.force(recomputeSenderOnlineIfStale(true) ? 1f : 0f);")
        result = self.run_java(source)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("first visible frame starts at zero", result.stderr)

    def test_lifecycle_and_parent_wiring(self):
        cell = CELL.read_text()
        self.assertIn("resetOnlineIndicatorOnAttach();", block(cell, "protected void onAttachedToWindow()"))
        self.assertIn("bindOnlineIndicator();", block(cell, "private void setMessageContent("))
        chat = (JAVA / "org/telegram/ui/ChatActivity.java").read_text()
        self.assertIn("if (cell != null && imageReceiver == cell.getAvatarImage())", chat)
        self.assertEqual(chat.count("invalidateSenderOnlineIndicators();"), 2)
        self.assertIn("invalidateSenderOnlineStatus();", block(chat, "private void invalidateSenderOnlineIndicators()"))
        header = (JAVA / "org/telegram/ui/Components/ChatAvatarContainer.java").read_text()
        self.assertNotIn("revealOnNextDraw", header)
        self.assertIn("view.startCrossfade", block(header, "private void setSubtitleTextSmooth("))


if __name__ == "__main__":
    unittest.main()
