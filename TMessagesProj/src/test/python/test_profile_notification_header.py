"""Notification-only header anchoring; extracted production layout wrapper."""
from pathlib import Path
import unittest
from test_gif_loop_transition import method
from test_sender_infocard_transitions import run_java

JAVA = Path(__file__).resolve().parents[2] / 'main/java'
PROFILE = (JAVA / 'org/telegram/ui/ProfileActivity.java').read_text()
PLACEMENT = (JAVA / 'app/nimarkogram/messenger/notifications/ProfileNotificationPlacement.java').read_text()


class ProfileNotificationHeaderTests(unittest.TestCase):
    def test_notification_layout_cannot_expand_header_and_restores_normal_layout(self):
        layout = method(PROFILE, 'public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state)')
        gate = method(PLACEMENT, 'public boolean shouldPreserveHeaderAnchor()')
        run_java(r'''
public class Transitions {
 static class RecyclerView {static class Recycler{}static class State{}}
 static class Placement {
  boolean released,reservationLayoutPending,preserveHeaderAnchor;
  GATE
 }
 static class NativeLayout {
  boolean fixGap=true,fail;int top=120;
  void setNeedFixGap(boolean v){fixGap=v;}
  public void onLayoutChildren(RecyclerView.Recycler r,RecyclerView.State s){
   if(fail)throw new IllegalStateException();
   if(fixGap)top+=80; // end-gap correction after shrinking a decoration
  }
 }
 static class Layout extends NativeLayout {
  Placement notificationPlacement;
  LAYOUT
 }
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  Layout l=new Layout();Placement p=new Placement();l.notificationPlacement=p;
  p.reservationLayoutPending=true;p.preserveHeaderAnchor=true;
  for(int i=0;i<20;i++){l.onLayoutChildren(null,null);check(l.top==120&&l.fixGap);}
  p.reservationLayoutPending=false;l.onLayoutChildren(null,null);check(l.top==200&&l.fixGap);
  p.reservationLayoutPending=true;p.preserveHeaderAnchor=false;
  l.onLayoutChildren(null,null);check(l.top==280); // scrolled header not artificially pinned
  p.preserveHeaderAnchor=true;p.released=true;l.onLayoutChildren(null,null);check(l.top==360);
  p.released=false;l.fail=true;
  try{l.onLayoutChildren(null,null);}catch(IllegalStateException expected){}
  check(l.fixGap); // no persistent alteration of scrolling after a failed layout
 }
}
'''.replace('GATE', gate).replace('LAYOUT', layout))

    def test_anchor_capture_and_lifecycle_wiring(self):
        capture = method(PLACEMENT, 'public void run()')
        self.assertIn('preserveHeaderAnchor = position == 0;', capture)
        self.assertIn('!layout.hasPendingScrollPosition()', capture)
        self.assertIn('!layout.isSmoothScrolling()', capture)
        self.assertIn('layout.hasPendingScrollPosition(0, preservedHeaderOffset)', capture)
        self.assertIn('header.getTop() >= 0 && header.getTop() < list.getHeight()', capture)
        self.assertIn('first = header;', capture)
        for signature in ('public void onLayoutChange(', 'public void release()'):
            self.assertIn('preserveHeaderAnchor = false;', method(PLACEMENT, signature))
        layout = method(PROFILE, 'protected void onLayout(boolean changed, int l, int t, int r, int b)')
        self.assertLess(layout.index('super.onLayout('), layout.index('checkListViewScroll();'))
