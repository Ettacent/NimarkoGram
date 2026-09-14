const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),cp=require('node:child_process'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../../main/java/org/telegram/ui');
const panels=fs.readFileSync(path.join(root,'Components/DialogsActivityTopPanelLayout.java'),'utf8');
const dialogs=fs.readFileSync(path.join(root,'DialogsActivity.java'),'utf8');
function method(s,name){const a=s.indexOf(name);assert(a>=0);let b=s.indexOf('{',a),n=1;while(n&&++b<s.length){if(s[b]==='{')n++;if(s[b]==='}')n--;}return s.slice(a,b+1);}
const java=`
class Entry {Holder item=new Holder();float visibility;float getVisibility(){return visibility;}}
class Holder {Object view;}
interface IndependentPanel {}
class Metadata {float height,visibility;float getTotalHeight(){return height;}float getTotalVisibility(){return visibility;}}
public class FloatingTest {
 Metadata data=new Metadata();Entry[] entries;float independent,visibility,density=1,scrollYOffset;boolean firstLayout;int lastListPadding=200;
 int dp(float n){return (int)Math.ceil(density*n);}float getSharedBackgroundOffset(){return independent;}
 Metadata getMetadata(){return data;}float getLayoutVisibility(){return visibility;}
 int getEntriesCount(){return entries.length;}Entry getEntry(int i){return entries[i];}
 float getAnimatedHeightWithPadding(float p){return data.height+p*visibility;}
 ${method(panels,'public int getNotificationListInset(')}
 ${method(dialogs,'private int notificationScrollCompensation(')}
 int notificationScrollCompensation(int p,int top,int first,int delta){return notificationScrollCompensation(p,top,first,delta,p==first?top:Integer.MIN_VALUE);}
 static int checks;static void check(boolean v){checks++;if(!v)throw new AssertionError(checks);}
 public static void main(String[] args){
  FloatingTest t=new FloatingTest();Entry e=new Entry();e.item.view=new Object();
  for(float d:new float[]{1,1.5f,2.75f,3})for(float tabs:new float[]{0,.5f,1})for(int nativeHeight:new int[]{0,50,100})for(int i=0;i<=100;i++){
   float f=i/100f;float p=tabs==0?14*d:7*d;t.density=d;
   t.independent=nativeHeight==0?-1:80*d*f;t.entries=nativeHeight==0?new Entry[0]:new Entry[]{e};
   e.visibility=1;t.visibility=nativeHeight==0?f:1;t.data.visibility=t.visibility;t.data.height=nativeHeight*d+80*d*f;
   int delta=t.getNotificationListInset(p,tabs);
   int full=(int)t.getAnimatedHeightWithPadding(p)-t.dp(5*Math.max(tabs,t.visibility));
   int base=(int)(nativeHeight*d+(nativeHeight==0?0:p))-t.dp(5*Math.max(tabs,nativeHeight==0?0:1));
   check(delta==full-base);
   for(int first:new int[]{0,1}){
    check(t.notificationScrollCompensation(first,200,first,delta)==0);
    check(t.notificationScrollCompensation(first+3,120,first,delta)==delta);
    check(t.notificationScrollCompensation(first,140,first,delta)==delta);
    int oldY=120;int newY=200+delta+(oldY-200)-t.notificationScrollCompensation(first+3,oldY,first,delta);check(newY==oldY);
    check(t.notificationScrollCompensation(first+3,oldY,first,-delta)==-delta);
   }
  }
  for(int first:new int[]{0,1})for(int depth=0;depth<=400;depth++)for(int shrink=1;shrink<=260;shrink++){
   t.lastListPadding=400;int position=first+depth/60;int firstTop=400-depth;
   int compensation=t.notificationScrollCompensation(position,firstTop,first,-shrink,firstTop);
   int nextTop=firstTop-shrink-compensation;
   check(nextTop<=400-shrink);
   check(nextTop==Math.min(firstTop,400-shrink));
  }
  int[][] heights={{0,20,80,160,90,40,0},{140,100,130,60,120,0},{60,60,100,100,60,0},{200,0},{0,120,0,120,0}};
  for(int first:new int[]{0,1})for(int initialDepth:new int[]{0,1,10,80,300})for(int gesture:new int[]{-120,-25,0,25,120})for(int[] hs:heights){
   int depth=initialDepth,previous=hs[0];
   for(int i=1;i<hs.length;i++){
    depth=Math.max(0,depth+gesture*(i%2==0?-1:1));
    t.lastListPadding=200+previous;int top=t.lastListPadding-depth;
    int delta=hs[i]-previous;int c=t.notificationScrollCompensation(first+depth/60,top,first,delta,top);
    depth+=c;check(depth>=0);
    if(delta<0)check(c>=delta&&c<=0);
    previous=hs[i];
   }
   depth=0;t.lastListPadding=200;check(t.notificationScrollCompensation(first,200,first,0,200)==0);
  }
  for(int header:new int[]{0,-1,-24,-48,-88,-136,-264})for(int first:new int[]{0,1})for(int depth=0;depth<=400;depth++)for(int shrink:new int[]{1,8,40,80,160,260}){
   t.scrollYOffset=header;t.lastListPadding=500;
   int restingTop=500+header,top=restingTop-depth;
   int c=t.notificationScrollCompensation(first+depth/60,top,first,-shrink,top);
   check(top-shrink-c==Math.min(top,restingTop-shrink));
   check(t.notificationScrollCompensation(first,restingTop,first,shrink,restingTop)==0);
   check(t.notificationScrollCompensation(first+2,restingTop+120,first,shrink,restingTop)==0);
   check(t.notificationScrollCompensation(first+2,top+120,first,shrink,top)==(depth>1?shrink:0));
  }
  for(int[] recorded:new int[][]{{846,488,19},{828,507,18},{810,525,38}}){
   t.scrollYOffset=-243;t.lastListPadding=recorded[0];
   int top=recorded[1],delta=recorded[2],savedOffset=top-t.lastListPadding;
   int c=t.notificationScrollCompensation(0,top,0,delta,top);
   check(c==delta);
   check(t.lastListPadding+delta+savedOffset-c==top);
   check(t.lastListPadding+delta+savedOffset!=top);
   for(int next:new int[]{delta,delta+18,delta,0,-18}){
    int repeated=t.notificationScrollCompensation(0,top,0,next,top);
    check(t.lastListPadding+next+savedOffset-repeated==top);
   }
  }
  t.scrollYOffset=0;t.lastListPadding=200;
  for(int[] recorded:new int[][]{{603,482,17},{620,511,18}}){
   t.lastListPadding=recorded[0];
   int top=recorded[1],delta=recorded[2];
   check(t.notificationScrollCompensation(0,top-211,1,delta,top)==delta);
   check(top+delta-t.notificationScrollCompensation(0,top-211,1,delta,top)==top);
   check(t.notificationScrollCompensation(0,top-211,1,-delta,top)==-delta);
   check(t.notificationScrollCompensation(0,t.lastListPadding-211,1,delta,t.lastListPadding)==0);
  }
  t.lastListPadding=200;
  check(t.notificationScrollCompensation(0,240,0,-100,240)==0);
  check(t.notificationScrollCompensation(0,140,1,-100,140)==-60);
  check(t.notificationScrollCompensation(0,140,1,-100,Integer.MIN_VALUE)==0);
  check(t.notificationScrollCompensation(-1,140,1,20,140)==0);
  check(t.notificationScrollCompensation(-1,0,0,-100,Integer.MIN_VALUE)==0);
  check(t.notificationScrollCompensation(5,-100,0,-100,Integer.MIN_VALUE)==-100);
  t.firstLayout=true;check(t.notificationScrollCompensation(7,0,0,100)==0);
  System.out.println("PASS: "+checks+" floating notification inset and anchor checks; player/call, folders, densities, hidden archive, release and initial layout");
 }
}`;
const dir=fs.mkdtempSync(path.join(os.tmpdir(),'dialogs-floating-'));
function run(code){fs.writeFileSync(path.join(dir,'FloatingTest.java'),code);cp.execFileSync('javac',['FloatingTest.java'],{cwd:dir});return cp.spawnSync('java',['FloatingTest'],{cwd:dir,encoding:'utf8'});}
try{
 const ok=run(java);assert.equal(ok.status,0,ok.stderr);process.stdout.write(ok.stdout);
 const current=method(dialogs,'private int notificationScrollCompensation(');
 const old=current.slice(0,current.indexOf('{'))+'{return !firstLayout && (position > firstChat || top < lastListPadding - 1) ? delta : 0;}';
 const bad=run(java.replace(current,old));assert.notEqual(bad.status,0);assert.match(bad.stderr,/AssertionError/);
 console.log('PASS: previous unrestricted compensation leaves a gap in the return-to-start/shrink test');
 const archiveAnchor=run(java.replace('if (firstLayout || position < 0 || position < firstChat && startTop == Integer.MIN_VALUE) return 0;',
   'if (firstLayout || position < firstChat) return 0;'));
 assert.notEqual(archiveAnchor.status,0);assert.match(archiveAnchor.stderr,/AssertionError/);
 console.log('PASS: archived saved anchor no longer bypasses attached first-chat compensation');
 const missingHeader=run(java.replace('int restingTop = lastListPadding + (int) scrollYOffset;', 'int restingTop = lastListPadding;'));
 assert.notEqual(missingHeader.status,0);assert.match(missingHeader.stderr,/AssertionError/);
 console.log('PASS: previous bounded compensation fails with collapsed stories/search header');
 const previous=current.slice(0,current.indexOf('{'))+`{
  if(firstLayout || position < firstChat)return 0;
  int restingTop=lastListPadding+(int)scrollYOffset;
  if(delta<0 && startTop!=Integer.MIN_VALUE)return -Math.min(-delta,Math.max(0,restingTop-startTop));
  if(position>firstChat)return delta;
  int depth=Math.max(0,restingTop-top);
  return delta<0?-Math.min(-delta,depth):depth>1?delta:0;
 }`;
 const wrongVisiblePosition=run(java.replace(current,previous));
 assert.notEqual(wrongVisiblePosition.status,0);assert.match(wrongVisiblePosition.stderr,/AssertionError/);
 console.log('PASS: attached first chat governs entry even when padded viewport reports a later row');
}finally{fs.rmSync(dir,{recursive:true,force:true});}
assert.match(dialogs,/lastNotificationInset = measuredNotificationInset/);
assert.match(dialogs,/- notificationCompensation\)/);
assert.match(dialogs,/!parentPage.layoutManager.hasPendingScrollPosition\(\)/);
assert.match(dialogs,/listView.restoreUpdateAnchor\(position, \(int\) offset\)/);
assert.match(dialogs,/restoringUpdateAnchor && parentPage.layoutManager.hasPendingScrollPosition\(\)/);
assert.match(dialogs,/measuredNotificationInset - updateAnchorInset/);
assert.match(dialogs,/scrollToPositionWithOffset\(updateAnchorPosition, updateAnchorOffset - compensation\)/);
assert.match(method(dialogs,'public void scrollToPositionWithOffset(int position, int offset, boolean bottom)'),/restoringUpdateAnchor = false/);
assert.match(method(dialogs,'public void scrollToPosition(int position)'),/restoringUpdateAnchor = false/);
assert.doesNotMatch(dialogs,/traceNotificationScroll|NotificationScrollTrace/);
const gift=fs.readFileSync(path.join(root,'Stars/StarGiftSheet.java'),'utf8');
assert.match(method(gift,'private void openInProfile()'),/openProfile\(dialogId, true\)/);
assert.match(method(gift,'private void openProfile(long did)'),/openProfile\(did, false\)/);
assert.match(method(gift,'private void openProfile(long did, boolean openGifts)'),/putBoolean\("open_gifts", openGifts\)/);
console.log('PASS: person links open at profile top; explicit gift links retain gift navigation');
