"""Exercise production arrow ownership and drawing with minimal view stubs."""
from pathlib import Path
import unittest
from test_gif_loop_transition import method
from test_sender_infocard_transitions import run_java

JAVA = Path(__file__).resolve().parents[2] / 'main/java'


class CommunityArrowTests(unittest.TestCase):
    def test_title_and_avatar_modes(self):
        header = (JAVA / 'org/telegram/ui/Components/ChatAvatarContainer.java').read_text()
        profile = (JAVA / 'org/telegram/ui/ProfileActivity.java').read_text()
        header_methods = '\n'.join(method(header, s) for s in (
            'public boolean shouldUseInlineCommunityIndicator()',
            'public void drawProfileTransitionText('))
        profile_method = method(profile, 'private void updateCommunityArrowItem()')
        run_java(r'''
public class Transitions {
 static final int VISIBLE=0;
 static class View {
  static final int VISIBLE=0,GONE=8;
  int visibility;Object tag;
  int getVisibility(){return visibility;}
  void setVisibility(int v){visibility=v;}
  void setTag(Object v){tag=v;}
 }
 static class Canvas {int save(){return 0;}void restoreToCount(int s){}void scale(float a,float b,float x,float y){}}
 static class Bounce {float getScale(float v){return 1;}}
 static class ActionBar {static int getCurrentActionBarHeight(){return 56;}}
 static class TLRPC {static class Chat {long linked_community_id=1;}}
 static class ChatAvatarContainer {
  boolean centerChatTitle,inlineAvatar;View communityItem=new View(),titleTextView=new View(),subtitle=new View();
  Bounce bounce=new Bounce();int arrows,titles;
  boolean isInlineCenteredAvatar(){return inlineAvatar;}
  float getPivotX(){return 0;}int getHeight(){return 56;}long getDrawingTime(){return 0;}
  View getSubtitleTextView(){return subtitle;}
  void drawChild(Canvas c,View v,long time){if(v==communityItem)arrows++;if(v==titleTextView)titles++;}
  HEADER
 }
 static class ChatActivity {
  TLRPC.Chat chat=new TLRPC.Chat();ChatAvatarContainer header=new ChatAvatarContainer();
  TLRPC.Chat getCurrentChat(){return chat;}ChatAvatarContainer getAvatarContainer(){return header;}
 }
 View communityItem=new View();Object previousTransitionFragment;
 PROFILE
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  Transitions p=new Transitions();ChatActivity chat=new ChatActivity();p.previousTransitionFragment=chat;
  ChatAvatarContainer h=chat.header;
  for(boolean centred:new boolean[]{false,true})for(boolean inline:new boolean[]{false,true}){
   h.centerChatTitle=centred;h.inlineAvatar=inline;
   boolean titleOwner=centred&&!inline;
   for(int frame=0;frame<200;frame++){
    h.arrows=0;p.updateCommunityArrowItem();
    h.drawProfileTransitionText(new Canvas(),h.titleTextView,h.subtitle);
    check(h.arrows==(titleOwner?1:0));
    check((p.communityItem.visibility==VISIBLE)==!titleOwner);
    check((p.communityItem.tag!=null)==!titleOwner);
   }
  }
  h.centerChatTitle=true;h.inlineAvatar=false;h.communityItem.visibility=View.GONE;
  check(!h.shouldUseInlineCommunityIndicator());
  h.arrows=0;h.drawProfileTransitionText(new Canvas(),h.titleTextView,h.subtitle);check(h.arrows==0);
  chat.chat.linked_community_id=0;p.updateCommunityArrowItem();check(p.communityItem.visibility==View.GONE);
  chat.chat=null;p.updateCommunityArrowItem();check(p.communityItem.tag==null);
  p.previousTransitionFragment=null;p.updateCommunityArrowItem();check(p.communityItem.visibility==View.GONE);
 }
}
'''.replace('HEADER', header_methods).replace('PROFILE', profile_method))
