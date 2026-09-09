const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const file = path.resolve(__dirname, '../../main/java/org/telegram/ui/Components/FilterTabsView.java');
const source = fs.readFileSync(file, 'utf8');
function block(marker) {
    const start = source.indexOf(marker);
    assert(start >= 0, marker);
    const tokens = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[{}]/g;
    tokens.lastIndex = source.indexOf('{', start);
    let depth = 0;
    for (let token; (token = tokens.exec(source));) {
        if (token[0] === '{') depth++;
        if (token[0] === '}' && --depth === 0) return source.slice(start, tokens.lastIndex);
    }
    throw Error(marker);
}
function simplify(code) {
    return code.replaceAll('app.nimarkogram.messenger.NimarkoConfig', 'Config')
        .replaceAll('app.nimarkogram.messenger.preferences.folders.helpers.FolderIconHelper', 'Icons')
        .replaceAll('androidx.core.content.ContextCompat', 'ContextCompat')
        .replaceAll('android.graphics.Rect', 'Rect');
}
const boundIcon = source.match(/final String wantedEmoticon = (.*);/)[1];
const getter = source.includes('public String getIconEmoticon()') ? block('public String getIconEmoticon()') : '';
const animation = simplify(block('if (nmTabModeAC != app.nimarkogram.messenger.NimarkoConfig.TAB_TYPE_TEXT)'));
const java = `
public class FolderIconsTest {
 static class Config { static final int TAB_TYPE_TEXT=0; }
 static class TextUtils { static boolean equals(String a,String b){return java.util.Objects.equals(a,b);} }
 static class Rect { Rect(int a,int b,int c,int d){} }
 static class Drawable {
  final String key; Drawable(String key){this.key=key;}
  Drawable mutate(){return this;} void setBounds(Rect r){} void setTint(int color){}
 }
 static class ContextCompat { static Drawable getDrawable(Object context,String key){return new Drawable(key);} }
 static class Icons {
  static int getIconWidth(){return 28;}
  static String getTabIcon(String s){return s==null||s.isEmpty()?"FOLDER":s;}
 }
 static class Paint { int getColor(){return 1;} }
 static class Tab {
  boolean isDefault; String emoticon;
  Tab(boolean def,String raw){isDefault=def;emoticon=raw==null?"":raw;}
  ${getter}
 }
 Tab currentTab;
 String lastEmoticon;
 float lastIconX=0,animateFromIconX;
 boolean animateIconX,animateIconChange;
 Drawable iconAnimateOutDrawable,iconAnimateInDrawable;
 Paint textPaint=new Paint();
 Object getContext(){return this;} int getMeasuredWidth(){return 100;}
 String boundIcon(){return ${boundIcon};}
 boolean animate(int nmTabModeAC){
  int tabWidth=100; boolean changed=false;
  ${animation}
  return changed;
 }
 static void check(boolean ok,String s){if(!ok)throw new AssertionError(s);}
 static void unchanged(boolean def,String oldRaw,String newRaw,int mode){
  FolderIconsTest t=new FolderIconsTest();t.currentTab=new Tab(def,oldRaw);
  t.lastEmoticon=t.boundIcon();t.currentTab=new Tab(def,newRaw);
  String bound=t.boundIcon();t.animate(mode);
  check(!t.animateIconChange,"Unchanged folder animates to wrong icon: "+t.lastEmoticon+" -> "+newRaw);
  check(bound.equals(t.lastEmoticon),"Bound glyph changed unexpectedly");
 }
 public static void main(String[] args){
  for(int mode:new int[]{0,1,2}) for(String oldRaw:new String[]{null,"","💬","🐱"})
   for(String newRaw:new String[]{null,"","💬","🐱"}) unchanged(true,oldRaw,newRaw,mode);
  for(int mode:new int[]{0,1,2}) for(String icon:new String[]{null,"","🐱","📢","💬"}) unchanged(false,icon,icon,mode);
  for(int mode:new int[]{1,2}){
   FolderIconsTest t=new FolderIconsTest();t.currentTab=new Tab(false,"🐱");t.lastEmoticon=t.boundIcon();
   t.currentTab=new Tab(false,"📢");t.animate(mode);
   check(t.animateIconChange,"Custom icon change lost its animation");
   check(t.iconAnimateInDrawable.key.equals(t.boundIcon()),"Incoming glyph differs from final glyph");
   check(t.iconAnimateOutDrawable.key.equals("🐱"),"Outgoing glyph differs from previous glyph");
  }
  System.out.println("PASS: default-folder icon remains stable across refreshes; custom-icon changes still animate");
 }
}`;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-folder-icon-'));
fs.writeFileSync(path.join(dir, 'FolderIconsTest.java'), java);
cp.execFileSync('javac', [path.join(dir, 'FolderIconsTest.java')], {stdio: 'inherit'});
cp.execFileSync('java', ['-cp', dir, 'FolderIconsTest'], {stdio: 'inherit'});
