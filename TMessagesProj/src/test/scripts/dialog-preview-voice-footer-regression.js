const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java/org/telegram/ui/Cells');
const dialog = fs.readFileSync(path.join(root, 'DialogCell.java'), 'utf8');
const chat = fs.readFileSync(path.join(root, 'ChatMessageCell.java'), 'utf8');
function block(source, marker) {
    const start = source.indexOf(marker);
    assert(start >= 0, marker);
    const tokens = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|[{}]/g;
    tokens.lastIndex = source.indexOf('{', start);
    let depth = 0;
    for (let t; (t = tokens.exec(source));) {
        if (t[0] === '{') depth++;
        if (t[0] === '}' && --depth === 0) return source.slice(start, tokens.lastIndex);
    }
    throw new Error(marker);
}
const preview = block(dialog.slice(dialog.indexOf('// Removing links and bold spans')), 'if (messageString instanceof Spanned)');
const voice = chat.slice(chat.indexOf('} else if (messageObject.type == MessageObject.TYPE_VOICE)'));
const start = voice.indexOf('int timeMore =');
const end = voice.indexOf('totalHeight += reactionsLayoutInBubble.totalHeight;', start);
assert(start >= 0 && end > start);
const footer = voice.slice(start, end);
const java = `import java.util.*;
public class PreviewFooterTest {
 static class android {static class graphics {static class Typeface {static int BOLD=1;}}}
 static class ClickableSpan {} static class URLSpanMono {}
 static class CodeHighlighting {static class Span {}static class ColorSpan {}}
 static class TypefaceSpan {} static class QuoteSpan {static class QuoteStyleSpan {}}
 static class StyleSpan {int style;StyleSpan(int s){style=s;}int getStyle(){return style;}}
 static class EmojiSpan {}static class SpoilerSpan {}static class SearchSpan {}
 interface Spanned extends CharSequence {<T>T[] getSpans(int a,int b,Class<T> c);}
 interface Spannable extends Spanned {void removeSpan(Object span);}
 static class Text implements Spanned {
  String value;ArrayList<Object> spans=new ArrayList<>();
  Text(String s,Object... list){value=s;spans.addAll(Arrays.asList(list));}
  public int length(){return value.length();}public char charAt(int i){return value.charAt(i);}
  public CharSequence subSequence(int a,int b){return value.substring(a,b);}public String toString(){return value;}
  public <T>T[] getSpans(int a,int b,Class<T> c){return spans.stream().filter(c::isInstance).map(c::cast).toArray(n->(T[])java.lang.reflect.Array.newInstance(c,n));}
 }
 static class SpannableStringBuilder extends Text implements Spannable {
  static int copies;
  SpannableStringBuilder(CharSequence s){super(s.toString(),((Spanned)s).getSpans(0,s.length(),Object.class));copies++;}
  public void removeSpan(Object span){spans.remove(span);}
 }
 int currentDialogCommunityId;boolean folder;
 boolean isFolderCell(){return folder;}
 CharSequence preview(CharSequence messageString){${preview}return messageString;}
 static float density;static int dp(float v){return (int)Math.ceil(v*density);}
 static class Message {boolean out;boolean isQuickReply(){return false;}boolean isSendError(){return false;}boolean isOutOwner(){return out;}}
 static class Reactions {int lastLineX,totalHeight,positionOffsetY;}
 static int extra;static int getExtraTimeX(){return extra;}
 static int reserve(int backgroundWidth,int timeWidth,int lastX,boolean out){
  Message messageObject=new Message();messageObject.out=out;
  Reactions reactionsLayoutInBubble=new Reactions();reactionsLayoutInBubble.lastLineX=lastX;
  ${footer}
  if(reactionsLayoutInBubble.totalHeight!=-reactionsLayoutInBubble.positionOffsetY)throw new AssertionError("height and offset must match");
  return reactionsLayoutInBubble.totalHeight;
 }
 static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
 public static void main(String[] args){
  PreviewFooterTest test=new PreviewFooterTest();
  for(boolean folder:new boolean[]{false,true})for(int community:new int[]{0,1}){
   test.folder=folder;test.currentDialogCommunityId=community;
   Text text=new Text("(80.97 - 69.65) * 86 = 973.52",new URLSpanMono(),new CodeHighlighting.Span(),new CodeHighlighting.ColorSpan(),new EmojiSpan(),new SpoilerSpan(),new SearchSpan(),new TypefaceSpan());
   int before=SpannableStringBuilder.copies;Text previewText=(Text)test.preview(text);
   check(previewText.toString().equals(text.toString()),"text changed");
   check(previewText.getSpans(0,text.length(),URLSpanMono.class).length==0,"bubble text color leaked into preview");
   check(text.getSpans(0,text.length(),URLSpanMono.class).length==1,"original message spans mutated");
   check(previewText.getSpans(0,text.length(),EmojiSpan.class).length==1,"emoji removed");
   check(previewText.getSpans(0,text.length(),SpoilerSpan.class).length==1,"spoiler removed");
   check(previewText.getSpans(0,text.length(),SearchSpan.class).length==1,"search color removed");
   check(previewText.getSpans(0,text.length(),TypefaceSpan.class).length==(folder||community!=0?1:0),"folder/community style");
   check(SpannableStringBuilder.copies==before+1,"single copy for multiple spans");
  }
  Text clean=new Text("plain",new EmojiSpan());int before=SpannableStringBuilder.copies;
  check(test.preview(clean)==clean&&SpannableStringBuilder.copies==before,"no allocation for clean preview");
  int cases=0;
  for(float d:new float[]{1,1.5f,2,2.75f,3,4}){
   density=d;
   for(boolean out:new boolean[]{false,true})for(int bubble:new int[]{180,220,270})
    for(int time=30;time<=180;time+=5)for(int reaction=35;reaction<=150;reaction+=5)
     for(int radiusExtra:new int[]{0,4,10}){
      extra=dp(radiusExtra);int width=dp(bubble),tw=dp(time),rx=dp(reaction);
      int reserved=reserve(width,tw,rx,out);
      int reactionLeft=out?dp(11):dp(3)+dp(17);
      int reactionRight=reactionLeft+rx-dp(4);
      float timeLeft=width-tw-(out?dp(38.5f):dp(9))-extra;
      if(reserved==0)check(reactionRight<=timeLeft,"voice reaction overlaps footer");
      else check(reserved==dp(12),"unexpected added row");
      cases++;
     }
  }
  System.out.println("PASS: preview span isolation and "+cases+" voice footer geometry cases");
 }
}`;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-preview-footer-'));
try {
    const file = path.join(dir, 'PreviewFooterTest.java');
    const run = code => {
        fs.writeFileSync(file, code);
        cp.execFileSync('javac', ['-nowarn', file], {stdio:'pipe'});
        return cp.spawnSync('java', ['-cp', dir, 'PreviewFooterTest'], {encoding:'utf8'});
    };
    const result = run(java);
    assert.equal(result.status, 0, result.stderr);
    console.log(result.stdout.trim());
    for (const [name, broken] of [
        ['monospace color', java.replace('span instanceof URLSpanMono || ', '')],
        ['footer insets', java.replace('timeWidth + dp(32)', 'timeWidth + dp(6)')],
    ]) {
        assert.notEqual(broken, java);
        assert.notEqual(run(broken).status, 0, `${name}: old code must fail`);
    }
    console.log('PASS: both previous implementations fail negative controls; host checks do not replace device rendering');
} finally {
    fs.rmSync(dir, {recursive:true, force:true});
}
