const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const os = require('node:os');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/java/org/telegram/ui/Components/FilterTabsView.java'), 'utf8');
function method(signature) {
    const start = source.indexOf(signature);
    assert(start >= 0);
    let depth = 0;
    for(let i=source.indexOf('{',start);i<source.length;i++) {
        if(source[i]==='{') depth++;
        if(source[i]==='}' && --depth===0) return source.slice(start,i+1);
    }
    throw Error(signature);
}
const java = `import java.util.*;
public class OverlayTest {
 static int checks;
 static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
 static float lerp(float a,float b,float p){return a+(b-a)*p;}
 static float density=1;
 static int dp(float n){return (int)Math.ceil(n*density);}
 static final int TAB_PADDING_WIDTH=32;
 static class View {int left,right;int getLeft(){return left;}int getRight(){return right;}}
 static class Manager {
  View end;int anchors;boolean bottom;
  View findViewByPosition(int p){return p==6?end:null;}
  void scrollToPositionWithOffset(int p,int offset,boolean b){anchors++;bottom=b;check(p==6&&offset==0);}
 }
 static class ListView {
  int left=8,right=8,layouts,width=360;
  int getPaddingLeft(){return left;}int getPaddingRight(){return right;}
  void setPadding(int l,int t,int r,int b){left=l;right=r;layouts++;}
  int getWidth(){return width;}
 }
 static class Tabs {
  ListView listView=new ListView();int listViewPaddingH=8,additionalTabWidth;
  int pageScrollFrom=-1,pageScrollTo=-1;
  Manager layoutManager=new Manager();List<Integer> tabs=Arrays.asList(0,1,2,3,4,5,6);
  int contentWidth=900;int getTabContentWidth(){return contentWidth;}
  HashMap<Integer,Integer> positionToX=new HashMap<>();
  ${method('public void setTrailingOverlayInset(')}
  ${method('private float getIndicatorX(')}
 }
 public static void main(String[] args){
  for(boolean rtl:new boolean[]{false,true})for(int inset:new int[]{0,66,96,112}){
   Tabs t=new Tabs();t.setTrailingOverlayInset(inset,rtl);int n=t.listView.layouts;
   for(int i=0;i<400;i++)t.setTrailingOverlayInset(inset,rtl);
   check(t.listView.layouts==n);
   int expected=8+Math.max(0,inset-dp(6.666f));
   check(t.listView.left==(rtl?expected:8)&&t.listView.right==(rtl?8:expected));
   t.setTrailingOverlayInset(0,rtl);check(t.listView.left==8&&t.listView.right==8);
  }
  for(boolean rtl:new boolean[]{false,true})for(int old:new int[]{48,72,96})for(int next:new int[]{48,72,96}){
   Tabs t=new Tabs();t.setTrailingOverlayInset(old,rtl);
   View end=t.layoutManager.end=new View();end.left=t.listView.left;end.right=360-t.listView.right;
   t.setTrailingOverlayInset(next,rtl);
   check(t.layoutManager.anchors==(old==next?0:1));
   if(old!=next)check(t.layoutManager.bottom==!rtl);
   t=new Tabs();t.setTrailingOverlayInset(old,rtl);end=t.layoutManager.end=new View();
   end.left=t.listView.left+20;end.right=340-t.listView.right;
   t.setTrailingOverlayInset(next,rtl);check(t.layoutManager.anchors==0);
   t=new Tabs();t.contentWidth=100;t.setTrailingOverlayInset(old,rtl);end=t.layoutManager.end=new View();
   end.left=t.listView.left;end.right=360-t.listView.right;
   t.setTrailingOverlayInset(next,rtl);check(t.layoutManager.anchors==0);
  }
  for(int extra:new int[]{0,1,12,40,100})for(int first=0;first<4;first++)for(int left=-400;left<100;left+=7){
   Tabs t=new Tabs();t.additionalTabWidth=extra;
   for(int i=0;i<7;i++)t.positionToX.put(i,8+i*(120+extra)+extra/2);
   for(float progress:new float[]{0,.1f,.5f,.9f,1}){
    float expected=left+(2-first)*(120+extra)+extra/2f+16+(120+extra)*progress;
    check(Math.abs(t.getIndicatorX(2,3,first,left,progress)-expected)<.001f);
   }
  }
  density=3;
  for(boolean rtl:new boolean[]{false,true}){
   Tabs t=new Tabs();t.listViewPaddingH=35;t.listView.width=1056;
   t.setTrailingOverlayInset(253,rtl);
   check((rtl?t.listView.left:t.listView.right)==268);
   float outer=rtl?253:803;
   float cellEdge=rtl?t.listView.left:1056-t.listView.right;
   float cellLeft=rtl?cellEdge:cellEdge-237;
   float textLeft=cellLeft+36,textRight=textLeft+165;
   float nativeLeft=textLeft-38,nativeRight=textRight+38;
   float actualLeft=rtl?Math.max(nativeLeft,outer+13):nativeLeft;
   float actualRight=rtl?nativeRight:Math.min(nativeRight,outer-13);
   check(actualLeft==nativeLeft&&actualRight==nativeRight);
   check(textLeft-actualLeft==actualRight-textRight);
   check((rtl?actualLeft-outer:outer-actualRight)==13);
  }
  for(float d:new float[]{1,1.5f,2,2.625f,3,4})for(int card:new int[]{48,72,84,96})for(boolean rtl:new boolean[]{false,true}){
   density=d;Tabs t=new Tabs();t.listViewPaddingH=dp(11.5f);t.listView.width=dp(360)-2*dp(4);
   int inset=dp(card)+dp(8);t.setTrailingOverlayInset(inset,rtl);
   float outer=rtl?inset:t.listView.width-inset;
   float cellEdge=rtl?t.listView.left:t.listView.width-t.listView.right;
   float overshoot=dp(12.5f)-dp(24)/2f;
   float nativeEdge=cellEdge+(rtl?-overshoot:overshoot);
   float gap=rtl?nativeEdge-outer:outer-nativeEdge;
   float vertical=(dp(50)-dp(28))/2f-dp(6.666f);
   check(gap>=vertical&&gap-vertical<=1);
   t.setTrailingOverlayInset(0,rtl);check(t.listView.left==dp(11.5f)&&t.listView.right==dp(11.5f));
  }
  System.out.println("PASS: "+checks+" checks: recorded 15px clipping, symmetric label insets, stable overlay, RTL/reset and end anchoring");
 }
}`;
const tmp=fs.mkdtempSync(path.join(os.tmpdir(),'nimarko-overlay-'));
try {
    function run(code){
        fs.writeFileSync(path.join(tmp,'OverlayTest.java'),code);
        cp.execFileSync('javac',['OverlayTest.java'],{cwd:tmp,stdio:'pipe'});
        return cp.execFileSync('java',['OverlayTest'],{cwd:tmp,encoding:'utf8',stdio:'pipe'});
    }
    process.stdout.write(run(java));
    assert.throws(()=>run(java.replace('listViewPaddingH + Math.max(0, inset - dp(6.666f))','Math.max(listViewPaddingH, inset)')));
    assert.throws(()=>run(java.replace('- positionToX.get(first) + firstLeft + additionalTabWidth / 2f','')));
    console.log('PASS: previous selector without scroll compensation fails the geometry checks');
} finally {fs.rmSync(tmp,{recursive:true,force:true});}
