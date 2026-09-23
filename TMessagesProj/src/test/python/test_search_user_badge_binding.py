"""Execute bounded badge binding methods; Android drawing is deliberately stubbed."""
import pathlib
import subprocess
import tempfile
import unittest

JAVA = pathlib.Path(__file__).resolve().parents[2] / 'main/java/org/telegram'


def method(source, signature):
    start = source.index(signature)
    pos = source.index('{', start)
    depth = 1
    end = pos + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


class BadgeBindingTest(unittest.TestCase):
    def test_production_binding(self):
        search = (JAVA / 'ui/Cells/ProfileSearchCell.java').read_text()
        user = (JAVA / 'ui/Cells/UserCell.java').read_text()
        text_view = (JAVA / 'ui/ActionBar/SimpleTextView.java').read_text()
        methods = (method(search, 'public void updateStatus(') + '\n'
                   + method(user, 'private boolean bindBadgeOwner(') + '\n'
                   + method(user, 'private void applyNimarkoBadge(') + '\n'
                   + method(text_view, 'private boolean isRightDrawableVisible(') + '\n'
                   + method(text_view, 'private boolean isRightDrawableInteractive('))
        for original, fake in {
            'app.nimarkogram.messenger.api.dto.BadgeDTO': 'BadgeDTO',
            'app.nimarkogram.messenger.badges.BadgesController': 'BadgesController',
            'org.telegram.tgnet.TLObject': 'Peer',
            'AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable': 'Swap',
        }.items():
            methods = methods.replace(original, fake)
        source = r'''
class Harness {
 static class Drawable {}
 static class CombinedDrawable extends Drawable { CombinedDrawable(Object a,Object b,int x,int y){} }
 static class Peer { long icon; Long emoji_status=0L; }
 static class TLRPC { static class User extends Peer {} static class Chat extends Peer {} }
 static class BadgeDTO { long getDocumentId(){return 7;} }
 static class BadgesController { static BadgeDTO badge; static BadgesController getInstance(){return new BadgesController();} BadgeDTO i(Peer p){return badge;} }
 static class Theme { static Object dialogs_verifiedDrawable,dialogs_verifiedCheckDrawable; static int key_chats_verifiedBackground; static int getColor(int k,Object p){return 1;} }
 static class LocaleController { static boolean isRTL; }
 static class DialogObject { static long getEmojiStatusDocumentId(Long l){return l;} static long getBotVerificationIcon(Peer p){return p.icon;} }
 static class MessagesController { static MessagesController getInstance(int a){return new MessagesController();} boolean isPremiumUser(Object u){return false;} }
 static class PremiumGradient { Drawable premiumStarDrawableMini=new Drawable(); static PremiumGradient getInstance(){return new PremiumGradient();} }
 static class Swap extends Drawable { Object target; int changes,particlesCalls,resets,account; boolean center,particles,animated; float visible;
   Swap(){} Swap(Object v,int size){} void attach(){} float isNotEmpty(){return visible;} boolean hasRenderableContent(){return visible>0;} boolean isEmpty(){return target==null;}
   void setCurrentAccount(int a){account=a;} void resetAnimation(){resets++;}
   void set(Object d,boolean a){ if(!java.util.Objects.equals(d,target)){changes++;target=d;} animated=a; if(d!=null)visible=1; else if(!a)visible=0; }
   void setColor(Object c){} void setParticles(boolean p,boolean a){particlesCalls++;particles=p;}
 }
 Swap statusDrawable=new Swap(),botVerificationDrawable=new Swap(),botVerification=new Swap(),nimarkoBadgeEmoji=new Swap();
 boolean statusBound,savedMessages,attached=true,badgeOwnerBound; int currentAccount=2,badgeOwnerAccount=-1; long badgeOwnerId;
 Object resourcesProvider; BadgeDTO currentNimarkoBadge; CombinedDrawable verifiedStatusDrawable;
 float rightDrawableScale=1; boolean rightDrawableHidden;
 static class Name { Drawable right,right2; Drawable getRightDrawable(){return right;} void setRightDrawable(Drawable d){right=d;} void setRightDrawable2(Drawable d){right2=d;} void setRightDrawableTopPadding(float p){} }
 Name nameTextView=new Name(); int dp(float f){return (int)f;}
 boolean isAttachedToWindow(){return attached;} boolean shouldAllowEmojiStatus(){return true;}
 ''' + methods + r'''
 static void check(boolean v,String s){if(!v)throw new AssertionError(s);}
 public static void main(String[] args){
   Harness h=new Harness(); TLRPC.User u=new TLRPC.User();
   h.updateStatus(true,u,null,false); Object verified=h.statusDrawable.target;
   h.updateStatus(true,u,null,true);
   check(h.statusDrawable.target==verified && h.statusDrawable.changes==1,"stable verified identity");
   BadgesController.badge=new BadgeDTO(); h.updateStatus(false,u,null,true);
   int calls=h.statusDrawable.particlesCalls;
   h.updateStatus(false,u,null,true);
   check(h.statusDrawable.particlesCalls==calls+1 && h.statusDrawable.particles,"one final particle assignment");
   check(h.statusDrawable.changes==2,"unchanged badge stable");
   check(!h.bindBadgeOwner(10),"initial immediate"); int resets=h.botVerification.resets;
   check(h.bindBadgeOwner(10),"same visible owner animate");
   check(h.botVerification.resets==resets,"same owner does not reset active swap");
   check(!h.bindBadgeOwner(11),"recycle immediate");
   h.currentAccount=3; check(!h.bindBadgeOwner(11),"account replacement immediate");
   check(h.botVerification.account==3 && h.nimarkoBadgeEmoji.account==3,"account pinned");
   h.attached=false; check(!h.bindBadgeOwner(11),"detached immediate");
   h.badgeOwnerBound=false; h.attached=true; check(!h.bindBadgeOwner(11),"warm reattach no replay");
   check(!h.bindBadgeOwner(0) && !h.bindBadgeOwner(0),"unowned row never animates old owner");
   h.nameTextView.right=null; h.applyNimarkoBadge(u,false);
   check(h.nameTextView.right==h.nimarkoBadgeEmoji && h.nameTextView.right2==null,"badge alone first slot only");
   check(h.nimarkoBadgeEmoji.account==3,"badge explicit account");
   h.nameTextView.right=new Drawable(); h.applyNimarkoBadge(u,true);
   check(h.nameTextView.right2==h.nimarkoBadgeEmoji && h.nimarkoBadgeEmoji.animated,"premium keeps first slot and live badge animated");
   BadgesController.badge=null; h.applyNimarkoBadge(u,true);
   check(h.currentNimarkoBadge==null && !h.nimarkoBadgeEmoji.particles,"removal clears semantics and particles");
   check(h.nameTextView.right2==h.nimarkoBadgeEmoji,"removal retains outgoing drawable host");
   check(h.isRightDrawableVisible(h.nameTextView.right2),"SimpleTextView renders retained outgoing badge");
   check(!h.isRightDrawableInteractive(h.nameTextView.right2),"removed badge not interactive while fading");
   h.nimarkoBadgeEmoji.visible=0; h.applyNimarkoBadge(u,true);
   check(h.nameTextView.right2==null,"finished removal clears second slot");
   check(!h.isRightDrawableVisible(h.nimarkoBadgeEmoji),"completed outgoing no longer rendered");
 }
}
'''
        with tempfile.TemporaryDirectory(prefix='badge-binding-') as directory:
            file = pathlib.Path(directory) / 'Harness.java'
            file.write_text(source)
            compiled = subprocess.run(['javac', str(file)], capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            run = subprocess.run(['java', '-cp', directory, 'Harness'], capture_output=True, text=True)
            self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
            for name, broken in {
                'recreated verified icon': source.replace('if (verifiedStatusDrawable == null)', 'if (true)'),
                'staged particles': source.replace('boolean particles = false;', 'boolean particles = false; statusDrawable.setParticles(false, animated);'),
                'cross-account animation': source.replace('badgeOwnerAccount == currentAccount', 'true'),
                'logical emptiness hides outgoing': source.replace('((Swap) drawable).hasRenderableContent()', '!((Swap) drawable).isEmpty()'),
            }.items():
                with self.subTest(negative_control=name):
                    file.write_text(broken)
                    compiled = subprocess.run(['javac', str(file)], capture_output=True, text=True)
                    self.assertEqual(compiled.returncode, 0, compiled.stderr)
                    run = subprocess.run(['java', '-cp', directory, 'Harness'], capture_output=True, text=True)
                    self.assertNotEqual(run.returncode, 0, 'Regression was not detected: ' + name)


if __name__ == '__main__':
    unittest.main()
