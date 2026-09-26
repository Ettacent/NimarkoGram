"""Focused production-method JVM checks; no Android/Gradle build required."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

JAVA = Path(__file__).resolve().parents[2] / "main/java"


def source(name):
    return (JAVA / name).read_text()


def method(text, signature):
    start = text.index(signature)
    brace = text.index("{", start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]


def run_java(body):
    code = """import java.util.*; import java.util.regex.*;
public class AuditHarness {
 static void check(boolean value) { if (!value) throw new AssertionError(); }
""" + body + "\n}"
    with tempfile.TemporaryDirectory(prefix="nm-audit-9-12-") as folder:
        path = Path(folder) / "AuditHarness.java"
        path.write_text(code)
        for command in (["javac", str(path)], ["java", "-cp", folder, "AuditHarness"]):
            result = subprocess.run(command, capture_output=True, text=True)
            if result.returncode:
                raise AssertionError(result.stdout + result.stderr)


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
class ChangelogAuditTests(unittest.TestCase):
    def test_wallpaper_detaches_old_before_attaching_new(self):
        production = method(source("org/telegram/ui/Components/SizeNotifierFrameLayout.java"),
                            "public void setBackgroundImage(")
        run_java(r"""
 static List<String> events=new ArrayList<>();
 static class Drawable {}
 static class MotionBackgroundDrawable extends Drawable {
  String name; boolean attached; MotionBackgroundDrawable(String n){name=n;}
  void setParentView(Object v){}
  void onAttachedToWindow(){attached=true;events.add(name+"+");}
  void onDetachedFromWindow(){attached=false;events.add(name+"-");}
 }
 static class ChatBackgroundDrawable extends Drawable {
  void onAttachedToWindow(Object v){events.add("chat+");}
  void onDetachedFromWindow(Object v){events.add("chat-");}
 }
 static class BackgroundView {
  BackgroundView(Object c){} void invalidate(){}
 }
 static class LayoutHelper {
  static int MATCH_PARENT=-1; static Object createFrame(int w,int h){return null;}
 }
 static class Host {
  boolean attached=true; Drawable backgroundDrawable; BackgroundView backgroundView;
  Object getContext(){return null;} void addView(Object v,int i,Object lp){}
  void checkLayerType(){} void checkMotion(){} void onUpdateBackgroundDrawable(Drawable d){}
 METHOD
 }
 public static void main(String[] args){
  Host h=new Host();MotionBackgroundDrawable a=new MotionBackgroundDrawable("a"),b=new MotionBackgroundDrawable("b");
  h.setBackgroundImage(a,false);check(a.attached);
  events.clear();h.setBackgroundImage(b,false);
  check(events.equals(Arrays.asList("a-","b+"))&&!a.attached&&b.attached);
  events.clear();h.setBackgroundImage(b,false);check(events.isEmpty());
  h.setBackgroundImage(null,false);check(events.equals(Arrays.asList("b-"))&&!b.attached);
  events.clear();h.attached=false;h.setBackgroundImage(a,false);h.setBackgroundImage(b,false);
  check(events.isEmpty());
  h.attached=true;h.backgroundDrawable=new ChatBackgroundDrawable();
  h.setBackgroundImage(a,false);check(events.equals(Arrays.asList("chat-","a+")));
  events.clear();h.setBackgroundImage(new ChatBackgroundDrawable(),false);
  check(events.equals(Arrays.asList("a-","chat+")));
 }
""".replace("METHOD", production))

    def test_archive_reveal_uses_base_silhouette(self):
        text = source("org/telegram/ui/Components/AvatarDrawable.java")
        self.assertIn("drawArchivedBackground(canvas, size, backgroundPaint);", text)
        production = method(text, "private void drawArchivedBackground(")
        production = production.replace("app.nimarkogram.messenger.NimarkoConfig", "Config")
        run_java(r"""
 static class Rect {float l,t,r,b;void set(float a,float c,float d,float e){l=a;t=c;r=d;b=e;}}
 static class AndroidUtilities {static Rect rectTmp=new Rect();}
 static class Config {static int radius;static int getAvatarCorners(int s,boolean px){return radius;}}
 static class Paint {}
 static class Canvas {
  float left,right,radius;
  void drawRoundRect(Rect r,float x,float y,Object p){left=r.l;right=r.r;radius=x;check(x==y);}
 }
 float archivedAvatarProgress;int roundRadius=-1;
 METHOD
 public static void main(String[] args){
  AuditHarness h=new AuditHarness();Canvas c=new Canvas();Paint paint=new Paint();
  for(int radius:new int[]{0,8,28})for(float p:new float[]{0,.25f,.5f,1}){
   Config.radius=radius;h.archivedAvatarProgress=p;h.drawArchivedBackground(c,56,paint);
   check(c.left==28*(1-p)&&c.right==56-c.left&&c.radius==radius*p);
  }
  h.roundRadius=12;h.archivedAvatarProgress=1;h.drawArchivedBackground(c,56,paint);
  check(c.left==0&&c.right==56&&c.radius==12);
  h.archivedAvatarProgress=2;h.drawArchivedBackground(c,56,paint);check(c.left==0&&c.radius==12);
  h.archivedAvatarProgress=-1;h.drawArchivedBackground(c,56,paint);check(c.left==28&&c.right==28&&c.radius==0);
 }
""".replace("METHOD", production))

    def test_existing_link_prefill_does_not_pick_semantic_or_mixed_spans(self):
        text = source("org/telegram/ui/Components/EditTextCaption.java")
        entry = method(text, "public void makeSelectedUrl(Runnable onApplied)")
        self.assertIn("getSelectedLinkUrl(text, start, end)", entry)
        self.assertIn("end <= start", entry)
        run_java(r"""
 static class URLSpan {String url;int start,end;URLSpan(String u,int s,int e){url=u;start=s;end=e;}String getURL(){return url;}}
 static class URLSpanReplacement extends URLSpan {URLSpanReplacement(String u,int s,int e){super(u,s,e);}}
 static class URLSpanBrowser extends URLSpan {URLSpanBrowser(String u,int s,int e){super(u,s,e);}}
 static class Mention extends URLSpan {Mention(){super("1234",0,10);}}
 static class DateSpan extends URLSpan {DateSpan(){super("date",0,10);}}
 static class TextUtils {static boolean isEmpty(String s){return s==null||s.isEmpty();}}
 static class Spanned {
  URLSpan[] spans;Spanned(URLSpan... s){spans=s;}
  URLSpan[] getSpans(int start,int end,Class<?> c){return spans;}
  int getSpanStart(URLSpan s){return s.start;}int getSpanEnd(URLSpan s){return s.end;}
 }
 METHOD
 public static void main(String[] args){
  URLSpan a=new URLSpanReplacement("https://example.org/a?q=1#x",0,10);
  check(getSelectedLinkUrl(new Spanned(a),0,10).equals(a.url));
  check(getSelectedLinkUrl(new Spanned(a),2,7).equals(a.url));
  check(getSelectedLinkUrl(new Spanned(a),0,11).equals("https://"));
  check(getSelectedLinkUrl(new Spanned(a,new URLSpanReplacement("other",10,20)),0,20).equals("https://"));
  check(getSelectedLinkUrl(new Spanned(a,new URLSpanReplacement("other",0,10)),0,10).equals("https://"));
  check(getSelectedLinkUrl(new Spanned(a,new URLSpanReplacement(a.url,0,10)),0,10).equals(a.url));
  check(getSelectedLinkUrl(new Spanned(new Mention(),new DateSpan()),0,10).equals("https://"));
  check(getSelectedLinkUrl(new Spanned(new URLSpanReplacement("http://example.org/?a=1#x",0,10)),0,10).equals("http://example.org/?a=1#x"));
  check(getSelectedLinkUrl(new Spanned(new URLSpan("http://example.org",0,10)),0,10).equals("http://example.org"));
  check(getSelectedLinkUrl(new Spanned(new URLSpan("mailto:a@example.org",0,10)),0,10).startsWith("mailto:"));
  check(getSelectedLinkUrl(new Spanned(new URLSpanBrowser("tg://resolve?domain=test",0,10)),0,10).startsWith("tg:"));
  check(getSelectedLinkUrl(new Spanned(new URLSpanReplacement("",0,10)),0,10).equals("https://"));
  check(getSelectedLinkUrl(new Spanned(),0,10).equals("https://"));
 }
""".replace("METHOD", method(text, "private static String getSelectedLinkUrl(")))

    def test_slash_paths_not_commands_and_other_patterns_unchanged(self):
        text = source("org/telegram/messenger/MessageObject.java")
        literal = re.search(r'urlPattern = Pattern.compile\(("(?:\\.|[^"\\])*")\);', text).group(1)
        run_java(r"""
 static Pattern pattern=Pattern.compile(PATTERN);
 static List<String> matches(String text){List<String> out=new ArrayList<>();Matcher m=pattern.matcher(text);while(m.find())out.add(m.group().trim());return out;}
 public static void main(String[] args){
  for(String path:new String[]{"/usr/bin"," /usr/bin/ ","/a/","/start@bot/path","//usr/bin","/a_b/c","/"+"a".repeat(256),"/"+"a".repeat(255)+"/b"})check(matches(path).isEmpty());
  check(matches("/start /cmd_12@SomeBot\n/help!").equals(Arrays.asList("/start","/cmd_12@SomeBot","/help")));
  check(matches("/usr/bin /help").equals(Arrays.asList("/help")));
  check(matches("/"+"a".repeat(255)).size()==1);
  check(matches("@user #topic $USD").equals(Arrays.asList("@user","#topic","$USD")));
  check(matches("https://example.org/path").isEmpty());
 }
""".replace("PATTERN", literal))


if __name__ == "__main__":
    unittest.main()
