"""The incoming account is ready after data + layout, not merely root creation."""
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_emoji_first_frame_fade import method


JAVA = Path(__file__).resolve().parents[2] / "main/java/org/telegram/ui"


class AccountSwitchContentReadinessTest(unittest.TestCase):
    def test_production_fragment_and_list_readiness(self):
        launch = (JAVA / "LaunchActivity.java").read_text()
        dialogs = (JAVA / "DialogsActivity.java").read_text()
        source = r"""
public class Ready {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static class View {boolean pending;boolean isLayoutRequested(){return pending;}}
 static class RecyclerListView extends View {boolean updates;boolean hasPendingAdapterUpdates(){return updates;}}
 static class DialogsAdapter {boolean isCalculatingDiff,updateListPending;ADAPTER}
 static class ViewPage {boolean updating;RecyclerListView listView=new RecyclerListView();DialogsAdapter dialogsAdapter=new DialogsAdapter();}
 static class Controller {boolean dialogsLoaded;}
 static class BaseFragment {
  int account=1;View fragmentView=new View();
  int getCurrentAccount(){return account;}View getFragmentView(){return fragmentView;}
 }
 static class ViewPagerActivity extends BaseFragment {
  BaseFragment current;BaseFragment getCurrentVisibleFragment(){return current;}
 }
 static class Layout {BaseFragment last;BaseFragment getLastFragment(){return last;}}
 static class Transition {boolean preparing;boolean isPreparing(){return preparing;}}
 static class LaunchActivity {
  Layout actionBarLayout=new Layout();Transition accountSwitchTransition=new Transition();
  LAUNCH
 }
 static class DialogsActivity extends BaseFragment {
  boolean dialogsLifecycleDestroyed,filterTabsBootstrapPending;
  ViewPage[] viewPages={new ViewPage()};Controller controller=new Controller();
  Object parent;Object getParentActivity(){return parent;}Controller getMessagesController(){return controller;}
  DIALOGS
 }
 public static void main(String[] args){
  LaunchActivity a=new LaunchActivity();DialogsActivity d=new DialogsActivity();
  ViewPagerActivity pager=new ViewPagerActivity();a.actionBarLayout.last=pager;
  check(!a.isAccountSwitchContentReady(1),"pager without a visible fragment is not ready");
  pager.current=d;d.parent=a;
  check(!a.isAccountSwitchContentReady(1),"cold empty list cannot be revealed");
  d.controller.dialogsLoaded=true;
  check(a.isAccountSwitchContentReady(1),"cached or genuinely empty account is ready");
  check(!a.isAccountSwitchContentReady(2),"another account cannot satisfy readiness");
  d.fragmentView.pending=true;check(!a.isAccountSwitchContentReady(1),"incoming root needs layout");d.fragmentView.pending=false;
  d.viewPages[0].listView.pending=true;check(!a.isAccountSwitchContentReady(1),"incoming rows need layout");d.viewPages[0].listView.pending=false;
  d.viewPages[0].listView.updates=true;check(!a.isAccountSwitchContentReady(1),"pending adapter cannot be revealed");d.viewPages[0].listView.updates=false;
  d.viewPages[0].updating=true;check(!a.isAccountSwitchContentReady(1),"36ms queued row update is not a ready list");d.viewPages[0].updating=false;
  d.viewPages[0].dialogsAdapter.isCalculatingDiff=true;check(!a.isAccountSwitchContentReady(1),"background diff still owns old adapter data");d.viewPages[0].dialogsAdapter.isCalculatingDiff=false;
  d.viewPages[0].dialogsAdapter.updateListPending=true;check(!a.isAccountSwitchContentReady(1),"coalesced update must commit before reveal");d.viewPages[0].dialogsAdapter.updateListPending=false;
  d.filterTabsBootstrapPending=true;check(!a.isAccountSwitchContentReady(1),"folder geometry must settle");d.filterTabsBootstrapPending=false;
  d.dialogsLifecycleDestroyed=true;check(!a.isAccountSwitchContentReady(1),"dead list must not signal readiness");d.dialogsLifecycleDestroyed=false;
  a.accountSwitchTransition.preparing=true;check(d.isCoveredByAccountSwitch(),"suppress only covered row-enter animation");
  a.accountSwitchTransition.preparing=false;check(!d.isCoveredByAccountSwitch(),"live rows still animate normally");
  d.parent=null;check(!d.isCoveredByAccountSwitch(),"detached fragment has no transition cover");
  pager.current=new BaseFragment();check(a.isAccountSwitchContentReady(1),"profile/settings start does not wait on hidden chats");
  a.actionBarLayout.last=d;check(a.isAccountSwitchContentReady(1),"standalone dialogs supported");
  a.actionBarLayout.last=null;check(!a.isAccountSwitchContentReady(1),"absent fragment is not ready");
  System.out.println("PASS: account content readiness");
 }
}
"""
        source = source.replace("LAUNCH", "\n".join(method(launch, s) for s in (
            "public boolean isAccountSwitchPreparing(", "private boolean isAccountSwitchContentReady(")))
        source = source.replace("DIALOGS", "\n".join(method(dialogs, s) for s in (
            "public boolean isReadyForAccountSwitch(", "private boolean isCoveredByAccountSwitch(")))
        source = source.replace("ADAPTER", method((JAVA / "Adapters/DialogsAdapter.java").read_text(), "public boolean hasPendingListUpdates("))
        with tempfile.TemporaryDirectory(prefix="account-ready-") as tmp:
            file = Path(tmp) / "Ready.java"
            file.write_text(source)
            build = subprocess.run(["javac", str(file)], capture_output=True, text=True, timeout=30)
            self.assertEqual(build.returncode, 0, build.stderr)
            run = subprocess.run(["java", "-cp", tmp, "Ready"], capture_output=True, text=True, timeout=30)
            self.assertEqual(run.returncode, 0, run.stdout + run.stderr)

    def test_readiness_is_wired_to_cache_and_shared_clock(self):
        launch = (JAVA / "LaunchActivity.java").read_text()
        switch = method(launch, "public void switchToAccountAnimated(int account, org.telegram.ui.Components.AccountSwitchTransition.Overlay popup)")
        self.assertIn("targetController.loadDialogs(0, 0, 100, true)", switch)
        self.assertLess(switch.index("targetController.loadDialogs"), switch.index("accountSwitchTransition.start"))
        self.assertIn("popup, () -> isAccountSwitchContentReady(account)", switch)
        dialogs = (JAVA / "DialogsActivity.java").read_text()
        reload = method(dialogs, "private void reloadViewPageDialogs(")
        # Row-enter animations must stay suppressed for the full account
        # transition, not only its initial cover/preparation frame.
        self.assertEqual(reload.count("&& !isAccountSwitchAnimating()"), 2)
        self.assertIn("isCoveredByAccountSwitch()", method(dialogs, "private void updateFilterTabsVisibility(boolean animated)"))


if __name__ == "__main__":
    unittest.main()
