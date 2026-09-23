"""Execute the real capture routing with counting Canvas/list stubs, not a GPU simulation."""
from pathlib import Path
import unittest

from test_sender_infocard_transitions import run_java
from test_recording_composer_lifecycle import method

SOURCE = (Path(__file__).resolve().parents[2] / 'main/java/org/telegram/ui/ChatActivity.java').read_text()


class ChatGlassCaptureCompositionTests(unittest.TestCase):
    def test_parent_background_has_exactly_one_owner(self):
        capture = method(SOURCE, 'private void drawListImpl(Canvas blurCanvas, RectF position)') + '\n' + method(SOURCE, 'private void drawListBackdrop(Canvas blurCanvas, RectF position)')
        run_java(r'''
import java.util.*;
public class Transitions {
 static class SystemClock { static long uptimeMillis() { return 1; } }
 static class RectF {}
 static class Canvas {
   float opacity; int backgrounds; final List<String> order = new ArrayList<>();
   void save() {} void restore() {} void translate(float x, float y) {}
   void background(float alpha) { backgrounds++; opacity += (1-opacity)*alpha; }
 }
 static class View {
   static final int VISIBLE=0, GONE=8;
   int visibility=VISIBLE; boolean outside;
   int getVisibility(){ return visibility; }
   float getX(){return 0;} float getY(){return 0;}
 }
 static class ChatMessageCell extends View {
   static boolean drawingGlassBackdrop;
   boolean parent=true; float alpha=.5f; int starsPriceTopPadding=8;
   boolean drawBackgroundInParent(){return parent;}
   void drawBackgroundInternal(Canvas c, boolean fromParent){c.background(alpha);}
 }
 static class ChatActionCell extends View {}
 static class ListView {
   List<View> children=new ArrayList<>(); boolean edge;
   int childDraws, backgroundPasses, foregroundPasses, edgeCaptures;
   int getChildCount(){return children.size();} View getChildAt(int i){return children.get(i);}
   boolean hasActiveEdgeEffects(){return edge;}
   void drawChatBackgroundElements(Canvas c, RectF r){
     backgroundPasses++; c.order.add("backgrounds");
     for(View v:children) if(v.getVisibility()==View.VISIBLE&&!v.outside&&v instanceof ChatMessageCell){
       ChatMessageCell cell=(ChatMessageCell)v;
       if(cell.drawBackgroundInParent())cell.drawBackgroundInternal(c,true);
     }
   }
   void drawChild(Canvas c,View v,long time){
     childDraws++; c.order.add("child");
     if(v instanceof ChatMessageCell&&!((ChatMessageCell)v).drawBackgroundInParent())
       ((ChatMessageCell)v).drawBackgroundInternal(c,false);
   }
   void drawChatForegroundElements(Canvas c,RectF r){foregroundPasses++;c.order.add("foregrounds");}
   void capture(Canvas c,RectF r){edgeCaptures++;}
 }
 ListView chatListView=new ListView();
 boolean quickRejectChild(View child,RectF position){return child.outside;}
 BODY
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
   Transitions t=new Transitions(); ChatMessageCell cell=new ChatMessageCell();
   t.chatListView.children.add(cell);
   for(float alpha:new float[]{0f,.01f,.25f,.5f,.75f,1f}){
     cell.alpha=alpha; Canvas c=new Canvas(); t.drawListImpl(c,new RectF());
     check(c.backgrounds==1); check(Math.abs(c.opacity-alpha)<.00001f);
     check(c.order.equals(Arrays.asList("backgrounds","child","foregrounds")));
   }
   cell.parent=false; Canvas own=new Canvas();t.drawListImpl(own,new RectF());
   check(own.backgrounds==1); // plain bubble remains owned by its child
   cell.parent=true;
   ChatMessageCell hidden=new ChatMessageCell(); hidden.visibility=View.GONE;
   ChatMessageCell offscreen=new ChatMessageCell(); offscreen.outside=true;
   t.chatListView.children.add(hidden);t.chatListView.children.add(offscreen);
   t.chatListView.children.add(new ChatActionCell());t.chatListView.children.add(new View());
   Canvas mixed=new Canvas();int before=t.chatListView.childDraws;
   t.drawListImpl(mixed,new RectF());check(mixed.backgrounds==1);
   check(t.chatListView.childDraws-before==3);
   t.chatListView.edge=true; Canvas edge=new Canvas();before=t.chatListView.backgroundPasses;
   t.drawListImpl(edge,new RectF());check(t.chatListView.edgeCaptures==1);
   check(t.chatListView.backgroundPasses==before&&edge.backgrounds==0);
 }
}
'''.replace('BODY', capture))

    def test_native_background_transform_remains_shared(self):
        parent = method(SOURCE, 'protected void drawChatBackgroundElements(Canvas canvas, RectF positionF)')
        self.assertIn('cell.getY() + cell.getPaddingTop()', parent)
        self.assertIn('cell.getScaleX(), cell.getScaleY()', parent)
        self.assertIn('cell.drawBackgroundInternal(canvas, true)', parent)
        capture = method(SOURCE, 'private void drawListBackdrop(Canvas blurCanvas, RectF position)')
        self.assertNotIn('drawBackgroundInternal(', capture)
        self.assertEqual(capture.count('chatListView.drawChild('), 1)


if __name__ == '__main__':
    unittest.main()
