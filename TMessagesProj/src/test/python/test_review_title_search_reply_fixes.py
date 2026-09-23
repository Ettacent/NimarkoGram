"""Execute extracted production methods on JVM fakes; no Android/Gradle build.

Each behavioral harness is also run with the pre-fix behavior restored and must
fail an assertion. Fakes model framework boundaries, not the policy under test.
"""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2] / "main/java/org/telegram"


def method(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


def execute(body, old=False):
    with tempfile.TemporaryDirectory(prefix="review-three-fixes-") as tmp:
        path = Path(tmp) / "Harness.java"
        path.write_text("import java.util.*;\npublic class Harness {\n"
                        "static void check(boolean b) { if (!b) throw new AssertionError(); }\n"
                        + body + "\n}")
        built = subprocess.run(["javac", "-d", tmp, str(path)], capture_output=True, text=True, timeout=30)
        if built.returncode:
            raise AssertionError(built.stdout + built.stderr)
        ran = subprocess.run(["java", "-ea", "-cp", tmp, "Harness"], capture_output=True, text=True, timeout=20)
        if old:
            if ran.returncode == 0 or "AssertionError" not in ran.stderr:
                raise AssertionError("Pre-fix behavior did not fail an assertion: " + ran.stderr)
        elif ran.returncode:
            raise AssertionError(ran.stdout + ran.stderr)


class ReviewFixes(unittest.TestCase):
    def title_harness(self, old=False):
        source = (ROOT / "ui/ActionBar/ActionBar.java").read_text()
        methods = "\n".join(method(source, signature) for signature in (
            "public void setTitleAnimated(CharSequence title, boolean fromBottom, long duration, Interpolator interpolator)",
            "public void setTitleAnimatedX(",
            "private boolean updateTitleBehindOverlay(",
        ))
        if old:
            methods = methods.replace("if (updateTitleBehindOverlay(title, null)) return;", "")
            methods = methods.replace("if (updateTitleBehindOverlay(title, rightDrawable)) return;", "")
        return r'''
static class Drawable {}
static class Animator {}
static class AnimatorListenerAdapter { public void onAnimationEnd(Animator a) {} }
interface Interpolator {}
static class TextUtils { static boolean isEmpty(CharSequence s){return s==null||s.length()==0;} }
static class View {
 static final int VISIBLE=0,GONE=8; int visibility; float alpha=1;
 ViewGroup parent=new ViewGroup(); ViewPropertyAnimator animator=new ViewPropertyAnimator();
 ViewPropertyAnimator animate(){return animator;} Object getParent(){return parent;}
 void setAlpha(float a){alpha=a;} void setTranslationX(float x){} void setTranslationY(float y){}
 int getVisibility(){return visibility;} void setVisibility(int v){visibility=v;}
}
static class ViewGroup { void removeView(View v){v.parent=null;} }
static class SimpleTextView extends View { CharSequence text; }
static class ViewPropertyAnimator {
 AnimatorListenerAdapter listener; boolean running;int starts;
 ViewPropertyAnimator setListener(AnimatorListenerAdapter l){listener=l;return this;}
 void cancel(){if(running)end();} void start(){running=true;starts++;}
 void end(){running=false;if(listener!=null)listener.onAnimationEnd(new Animator());}
 ViewPropertyAnimator alpha(float f){return this;} ViewPropertyAnimator translationX(float f){return this;}
 ViewPropertyAnimator translationY(float f){return this;} ViewPropertyAnimator setDuration(long d){return this;}
 ViewPropertyAnimator setInterpolator(Interpolator i){return this;}
}
SimpleTextView[] titleTextView={new SimpleTextView(),null}; SimpleTextView subtitleTextView=new SimpleTextView();
boolean titleOverlayShown,overlayTitleAnimationInProgress,overlayTitleAnimation,fromBottom,titleAnimationRunning;
int titleAnimationGeneration; CharSequence subtitle,lastTitle; Drawable lastRightDrawable;
int dp(int x){return x;} void requestLayout(){}
void setTitle(CharSequence s){setTitle(s,null,false);}
void setTitle(CharSequence s,Drawable d){setTitle(s,d,false);}
void setTitle(CharSequence s,Drawable d,boolean gilroy){
 if(titleTextView[0]==null)titleTextView[0]=new SimpleTextView();
 titleTextView[0].text=lastTitle=s;lastRightDrawable=d;
}
METHODS
void change(boolean horizontal, String title, Drawable badge){
 if(horizontal)setTitleAnimatedX(title,badge,true,80,true);
 else setTitleAnimated(title,false,80,null);
}
public static void main(String[] args){
 for(boolean horizontal:new boolean[]{true,false}){
  Harness h=new Harness();h.setTitle("Connecting");h.lastTitle="Old folder";
  h.titleOverlayShown=true;h.overlayTitleAnimationInProgress=true;
  h.titleTextView[1]=new SimpleTextView();
  ViewPropertyAnimator overlay=h.titleTextView[1].animate();
  overlay.setListener(new AnimatorListenerAdapter(){public void onAnimationEnd(Animator a){
   h.overlayTitleAnimationInProgress=false;h.titleTextView[1]=null;
  }});overlay.start();
  Drawable badge=new Drawable();h.change(horizontal,"Folder A",badge);h.change(horizontal,"Folder B",badge);
  check(overlay.starts==1&&h.titleTextView[0].animate().starts==0); // no restart/replay loop
  check("Folder B".contentEquals(h.lastTitle));
  check("Connecting".contentEquals(h.titleTextView[0].text));
  overlay.end();check(!h.overlayTitleAnimationInProgress);
  // An already-visible status still owns the title after its entrance completes.
  h.change(horizontal,"Folder C",badge);check("Connecting".contentEquals(h.titleTextView[0].text));
  // Clearing is already displaying the underlying title: latest folder must win.
  h.titleOverlayShown=false;h.overlayTitleAnimationInProgress=true;h.setTitle("Folder C",badge);
  h.change(horizontal,"Folder D",badge);check("Folder D".contentEquals(h.titleTextView[0].text));
  check(h.lastRightDrawable==(horizontal?badge:null));
  h.overlayTitleAnimationInProgress=false;
  h.change(horizontal,"Normal",badge);check(h.titleAnimationRunning);
  h.titleTextView[1].animate().end();check(!h.titleAnimationRunning&&h.titleTextView[1]==null);
 }
}
'''.replace("METHODS", methods)

    def test_title_overlay_ownership_and_clear_race(self):
        execute(self.title_harness())

    def test_title_harness_rejects_pre_fix_cancellation(self):
        execute(self.title_harness(True), old=True)

    def search_harness(self, old=False):
        source = (ROOT / "ui/Adapters/DialogsSearchAdapter.java").read_text()
        methods = "\n".join(method(source, signature) for signature in (
            "private int globalSearchPosition()", "public void removeAd(", "public void removeAllAds()"))
        if old:
            methods = methods.replace("private int globalSearchPosition() {",
                                      "private int globalSearchPosition() { if(waitingResponseCount==3)return 0;")
        return r'''
static class TLRPC { static class TL_sponsoredPeer {} }
static class Helper {
 ArrayList<Object> local=new ArrayList<>(),global=new ArrayList<>();
 ArrayList<Object> getLocalServerSearch(){return local;} ArrayList<Object> getGlobalSearch(){return global;}
}
Helper searchAdapterHelper=new Helper();
ArrayList<Object> publicPosts=new ArrayList<>(),searchResultHashtags=new ArrayList<>(),searchTopics=new ArrayList<>(),
 searchContacts=new ArrayList<>(),searchResult=new ArrayList<>();
ArrayList<TLRPC.TL_sponsoredPeer> sponsoredPeers=new ArrayList<>();
ArrayList<Object> rendered=new ArrayList<>();Object header=new Object();
int waitingResponseCount,recent;boolean searchWas=true,globalSearchCollapsed=true;
boolean isRecentSearchDisplayed(){return recent>0;}int getRecentItemsCount(){return recent;}
int getItemCount(){return rendered.size();}
void notifyItemRemoved(int p){rendered.remove(p);}
void notifyItemRangeRemoved(int p,int n){while(n-->0)rendered.remove(p);}
METHODS
public static void main(String[] args){
 for(int waiting:new int[]{3,2,0})for(boolean all:new boolean[]{true,false})for(boolean global:new boolean[]{true,false}){
  Harness h=new Harness();h.waitingResponseCount=waiting;
  Object local=new Object();h.searchResult.add(local);h.rendered.add(local);
  h.rendered.add(h.header);
  for(int i=0;i<3;i++){TLRPC.TL_sponsoredPeer p=new TLRPC.TL_sponsoredPeer();h.sponsoredPeers.add(p);h.rendered.add(p);}
  Object result=new Object();if(global){h.searchAdapterHelper.global.add(result);h.rendered.add(result);}
  if(all)h.removeAllAds();else h.removeAd(h.sponsoredPeers.get(1));
  ArrayList<Object> expected=new ArrayList<>();expected.add(local);
  if(!h.sponsoredPeers.isEmpty()||global)expected.add(h.header);
  expected.addAll(h.sponsoredPeers);if(global)expected.add(result);
  check(h.rendered.equals(expected));
  h.removeAllAds();h.removeAllAds(); // last ad removes header once, empty repeat is harmless
  check(h.rendered.get(0)==local&&h.rendered.size()==(global?3:1));
 }
}
'''.replace("METHODS", methods)

    def test_search_removal_positions_while_query_pending(self):
        execute(self.search_harness())

    def test_search_harness_rejects_pre_fix_zero_offset(self):
        execute(self.search_harness(True), old=True)

    def reply_harness(self, old=False, premature_switch=False):
        source = (ROOT / "ui/Cells/ChatMessageCell.java").read_text()
        update = method(source, "private void updateReplyPhotoFallback(")
        choose = method(source, "private ImageReceiver getReplyImageForDraw()")
        if old:
            update = "private void updateReplyPhotoFallback(MessageObject m) {}"
        if premature_switch:
            choose = """private ImageReceiver getReplyImageForDraw() {
                return !replyImageReceiver.hasImageLoaded() && replyPhotoFallback.hasImageLoaded()
                    ? replyPhotoFallback : replyImageReceiver;
            }"""
        return r'''
static class Drawable {}
static class TextUtils {static boolean equals(CharSequence a,CharSequence b){return Objects.equals(a,b);}}
static class TLRPC {
 static class Photo {} static class PhotoSize {int size=12345;}
 static class Reply {Object reply_media;int story_id;}
 static class Message {Reply reply_to;}
}
static class MessageObject {
 MessageObject replyMessageObject;boolean mediaExists,secret,spoiler;
 int currentAccount,id=1;long dialogId=10;
 int getId(){return id;}long getDialogId(){return dialogId;}
 Object photoThumbsObject=new TLRPC.Photo();
 ArrayList<TLRPC.PhotoSize> photoThumbs=new ArrayList<>(),photoThumbs2;
 TLRPC.Message messageOwner=new TLRPC.Message();
 boolean isSecretMedia(){return secret;} boolean hasMediaSpoilers(){return spoiler;}
}
static class AndroidUtilities {static int getPhotoSize(){return 1280;}}
static class FileLoader {
 static TLRPC.PhotoSize getClosestPhotoSizeWithSize(ArrayList<TLRPC.PhotoSize> p,int size){return p.isEmpty()?null:p.get(p.size()-1);}
}
static class ImageLocation {
 Object key;ImageLocation(Object k){key=k;}
 static ImageLocation getForObject(Object p,Object owner){return new ImageLocation(p);}
}
static class ImageReceiver {
 Object key,owner;int guid,cacheType;long size;String filter;boolean loaded;float alpha=.4f;
 boolean hasImageLoaded(){return loaded;}
 String getImageKey(){return key==null?null:System.identityHashCode(key)+"@"+filter;}
 void setImageBitmap(Drawable d){key=null;loaded=false;guid++;}
 void setImage(ImageLocation l,String f,Drawable d,long s,String ext,Object o,int cache){
  MessageObject before=(MessageObject)owner,after=(MessageObject)o;
  if(key!=l.key||!Objects.equals(f,filter)||before==null||before.id!=after.id
      ||before.currentAccount!=after.currentAccount||before.dialogId!=after.dialogId){guid++;loaded=false;}
  key=l.key;filter=f;size=s;owner=o;cacheType=cache;
 }
 void deliver(int token){if(token==guid&&key!=null)loaded=true;}
}
ImageReceiver replyImageReceiver=new ImageReceiver(),replyPhotoFallback=new ImageReceiver();boolean needReplyImage=true;
MessageObject replyPhotoFallbackOwner;boolean replyPhotoFallbackPinned;
METHODS
public static void main(String[] args){
 Harness h=new Harness();MessageObject m=new MessageObject(),reply=m.replyMessageObject=new MessageObject();
 TLRPC.PhotoSize full=new TLRPC.PhotoSize();reply.photoThumbs.add(full);
 Object primary=new Object();h.replyImageReceiver.key=primary;h.replyImageReceiver.guid=42;
 h.updateReplyPhotoFallback(m);check(h.replyPhotoFallback.key==null); // do not fetch undownloaded full media
 reply.mediaExists=true;h.updateReplyPhotoFallback(m);
 check(h.replyPhotoFallback.key==full&&h.replyPhotoFallback.size==full.size&&h.replyPhotoFallback.cacheType==0);
 check("50_50".equals(h.replyPhotoFallback.filter)&&h.replyPhotoFallback.owner==reply);
 check(h.replyImageReceiver.key==primary&&h.replyImageReceiver.guid==42&&h.replyImageReceiver.alpha==.4f);
 check(h.getReplyImageForDraw()==h.replyImageReceiver); // pending decode cannot blank primary preview
 int token=h.replyPhotoFallback.guid;h.replyPhotoFallback.deliver(token);
 check(h.getReplyImageForDraw()==h.replyPhotoFallback); // offline full-photo decode wins over missing thumb
 h.updateReplyPhotoFallback(m);check(h.replyPhotoFallback.guid==token&&h.replyPhotoFallback.loaded);
 // Primary delivery starts at alpha zero: it must not replace an already visible fallback.
 h.replyPhotoFallback.alpha=1;h.replyImageReceiver.loaded=true;h.replyImageReceiver.alpha=0;
 check(h.getReplyImageForDraw()==h.replyPhotoFallback);
 for(int i=0;i<5;i++){
  h.updateReplyPhotoFallback(m);check(h.replyPhotoFallback.guid==token&&h.replyPhotoFallback.alpha==1);
  check(h.getReplyImageForDraw()==h.replyPhotoFallback);
 }
 // A newly allocated MessageObject for the same account/dialog/message retains presentation.
 MessageObject rebound=new MessageObject();rebound.mediaExists=true;rebound.photoThumbs.add(full);
 m.replyMessageObject=rebound;h.updateReplyPhotoFallback(m);
 check(h.replyPhotoFallback.guid==token&&h.getReplyImageForDraw()==h.replyPhotoFallback);
 // Same photo key is insufficient ownership: account, dialog and reply id all matter.
 for(int boundary=0;boundary<3;boundary++){
  MessageObject different=new MessageObject();different.mediaExists=true;different.photoThumbs.add(full);
  if(boundary==0)different.id=2;if(boundary==1)different.dialogId=20;if(boundary==2)different.currentAccount=1;
  m.replyMessageObject=different;h.updateReplyPhotoFallback(m);h.replyPhotoFallback.deliver(token);
  check(!h.replyPhotoFallback.loaded&&h.getReplyImageForDraw()==h.replyImageReceiver);
 }
 m.replyMessageObject=reply;
 h.replyImageReceiver.loaded=false;reply.spoiler=true;h.updateReplyPhotoFallback(m);
 check("5_5_b".equals(h.replyPhotoFallback.filter)); // never expose unblurred spoiler pixels
 for(int kind=0;kind<6;kind++){
  reply.spoiler=false;reply.secret=false;h.needReplyImage=true;reply.photoThumbs2=null;m.messageOwner.reply_to=null;
  h.updateReplyPhotoFallback(m);int pending=h.replyPhotoFallback.guid;
  if(kind==0)h.needReplyImage=false;
  if(kind==1)reply.secret=true;
  if(kind==2){m.messageOwner.reply_to=new TLRPC.Reply();m.messageOwner.reply_to.reply_media=new Object();}
  if(kind==3){m.messageOwner.reply_to=new TLRPC.Reply();m.messageOwner.reply_to.story_id=7;}
  if(kind==4){reply.photoThumbs2=new ArrayList<>();reply.photoThumbs2.add(full);}
  if(kind==5)m.replyMessageObject=null;
  h.updateReplyPhotoFallback(m);h.replyPhotoFallback.deliver(pending);
  check(h.replyPhotoFallback.key==null&&!h.replyPhotoFallback.loaded);
  check(h.getReplyImageForDraw()==h.replyImageReceiver);
 }
}
'''.replace("METHODS", update + "\n" + choose)

    def test_reply_fallback_request_and_recycle_boundaries(self):
        execute(self.reply_harness())

    def test_reply_harness_rejects_pre_fix_missing_fallback(self):
        execute(self.reply_harness(True), old=True)

    def test_reply_harness_rejects_premature_primary_switch(self):
        execute(self.reply_harness(premature_switch=True), old=True)

    def placeholder_harness(self, old=False):
        source = (ROOT / "ui/Cells/ChatMessageCell.java").read_text()
        body = method(source, "private void markReplyPhotoFallbackPlaceholder(")
        if old:
            body = body.replace("replyPhotoFallback.markLoadingPlaceholderPresented();", "")
        return r'''
static class Canvas {
 enum EdgeType {AA} boolean rejected;
 boolean quickReject(float a,float b,float c,float d,EdgeType edge){return rejected;}
}
static class SizeNotifierFrameLayout {static boolean drawingBlur;static class SimplerCanvas extends Canvas {}}
static class ImageReceiver {
 boolean loaded,visible=true;int marks;float width=34,height=34;String key="pending";
 boolean hasImageLoaded(){return loaded;}boolean getVisible(){return visible;}
 String getImageKey(){return key;}float getImageWidth(){return width;}float getImageHeight(){return height;}
 float getImageX(){return 1;}float getImageY(){return 2;}float getImageX2(){return 35;}float getImageY2(){return 36;}
 void markLoadingPlaceholderPresented(){marks++;}
}
ImageReceiver replyImageReceiver=new ImageReceiver(),replyPhotoFallback=new ImageReceiver();
boolean attachedToWindow=true,shown=true,drawForBlur,drawingToBitmap;float cellAlpha=1;
float getAlpha(){return cellAlpha;}boolean isShown(){return shown;}
METHOD
public static void main(String[] args){
 for(int boundary=0;boundary<15;boundary++){
  Harness h=new Harness();Canvas c=new Canvas();ImageReceiver drawn=h.replyImageReceiver;float alpha=1;
  if(boundary==0)alpha=0;if(boundary==1)h.cellAlpha=0;if(boundary==2)h.attachedToWindow=false;
  if(boundary==3)h.shown=false;if(boundary==4)drawn.visible=false;if(boundary==5)h.drawForBlur=true;
  if(boundary==6)SizeNotifierFrameLayout.drawingBlur=true;if(boundary==7)c=new SizeNotifierFrameLayout.SimplerCanvas();
  if(boundary==8)drawn.loaded=true;if(boundary==9)h.replyPhotoFallback.loaded=true;
  if(boundary==10)h.replyPhotoFallback.key=null;if(boundary==11)drawn.width=0;
  if(boundary==12)c.rejected=true;if(boundary==13)drawn=h.replyPhotoFallback;if(boundary==14)h.drawingToBitmap=true;
  h.markReplyPhotoFallbackPlaceholder(c,drawn,alpha);check(h.replyPhotoFallback.marks==0);
  SizeNotifierFrameLayout.drawingBlur=false;
 }
 Harness visible=new Harness();visible.markReplyPhotoFallbackPlaceholder(new Canvas(),visible.replyImageReceiver,.5f);
 check(visible.replyPhotoFallback.marks==1); // actual visible primary blank/preview transfers presentation
}
'''.replace("METHOD", body)

    def test_pending_fallback_presentation_requires_visible_primary_draw(self):
        execute(self.placeholder_harness())

    def test_placeholder_harness_rejects_missing_transfer(self):
        execute(self.placeholder_harness(True), old=True)

    def test_transferred_placeholder_arms_real_receiver_late_cache_fade(self):
        import test_sticker_first_frame_fade as receiver_tests
        receiver, fixture = receiver_tests.source()
        # The shared harness omits this setter. Extract it locally rather than
        # expanding the shared fixture or assigning implementation fields directly.
        receiver_source = (ROOT / "messenger/ImageReceiver.java").read_text()
        setter = method(receiver_source, "public void setCrossfadeDuration(int duration)")
        receiver = receiver.replace("class ImageReceiver {", "class ImageReceiver {\n" + setter, 1)
        scenario = r'''
    static void transferredReplyPresentation() {
        ImageReceiver fallback = receiver();
        fallback.setCrossfadeDuration(180);
        fallback.setImage(null, null, new ImageLocation("reply-full"), "50_50",
                null, null, null, 12345, null, new MessageObject(), 0);
        // No fallback.frame() before delivery: another receiver presented the blank.
        fallback.markLoadingPlaceholderPresented();
        BitmapDrawable decoded = new BitmapDrawable();
        check(fallback.deliver(decoded, ImageReceiver.TYPE_IMAGE, true), "late cached decode accepted");
        check(fallback.currentAlpha == 0f, "transferred presentation arms fade even for late memory delivery");
        fallback.frame(1000, false);
        fallback.frame(1090, false);
        fallback.frame(1300, false);
        check(fallback.currentAlpha > 0f, "fade advances on visible fallback draws");
    }
'''
        fixture = fixture.replace("public static void main(String[] args) {",
                                  scenario + "public static void main(String[] args) { transferredReplyPresentation();")
        with tempfile.TemporaryDirectory(prefix="reply-presentation-") as tmp:
            receiver_tests.compile_sources(tmp, (receiver, fixture))
            ran = subprocess.run(["java", "-ea", "-cp", tmp, "StickerFirstFrameHarness", "scope"],
                                 capture_output=True, text=True, timeout=20)
        self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)

    def test_reply_lifecycle_wiring_and_no_ui_file_access(self):
        source = (ROOT / "ui/Cells/ChatMessageCell.java").read_text()
        body = method(source, "private void updateReplyPhotoFallback(")
        for forbidden in (".exists(", "getPathTo", "new File(", "BitmapFactory", "decodeFile", "replyImageReceiver.setImage("):
            self.assertNotIn(forbidden, body)
        self.assertIn("updateReplyPhotoFallback(messageObject);", method(source, "private void setMessageObjectInternal("))
        for suffix in ("onAttachedToWindow();", "onDetachedFromWindow();"):
            self.assertIn("replyImageReceiver." + suffix + "\n                replyPhotoFallback." + suffix, source)
        self.assertIn("replyPhotoFallback.setCurrentAccount(currentAccount);", source)
        self.assertIn("replyPhotoFallback.setAllowLoadingOnAttachedOnly(true);", source)
        self.assertIn("replyPhotoFallback.setCrossfadeDuration(180);", source)
        self.assertIn("replyPhotoFallback.setCrossfadeOnReady(true);", source)
        self.assertNotIn("replyImageReceiver.draw(canvas);", source)
        self.assertIn("replyImageForDraw.draw(canvas);\n                    markReplyPhotoFallbackPlaceholder(canvas, replyImageForDraw, alpha * replyForwardAlpha);", source)
        self.assertIn("replyImageForDraw.setRoundRadius(replyImageReceiver.getRoundRadius());", source)
        self.assertIn("replyImageForDraw.setAlpha(replyForwardAlpha);", source)
        for value in ("true", "false"):
            self.assertEqual(source.count("replyImageReceiver.setIgnoreImageSet(" + value + ");"),
                             source.count("replyPhotoFallback.setIgnoreImageSet(" + value + ");"))

    def test_title_update_does_not_replay_status_recursively(self):
        source = (ROOT / "ui/ActionBar/ActionBar.java").read_text()
        body = method(source, "private boolean updateTitleBehindOverlay(")
        self.assertNotIn("setTitleOverlayText(", body)
        self.assertNotIn(".animate()", body)
        self.assertNotIn("setTitleAnimated", body)


if __name__ == "__main__":
    unittest.main()
