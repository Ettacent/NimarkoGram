const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),cp=require('node:child_process'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../../main/java/org/telegram/ui');
const source=fs.readFileSync(path.join(root,'Components/SharedMediaLayout.java'),'utf8');
const start=source.indexOf('iBlur3Capture = (canvas, position) -> {');
assert(start>=0);const end=source.indexOf('\n        };',start);assert(end>start);
const capture=source.slice(start,end+11);
const java=`
interface Capture {void capture(Object canvas,Object position);}
class View {static final int VISIBLE=0;int visibility;int getVisibility(){return visibility;}boolean isShown(){return visibility==0;}}
class MediaPage extends View {View listView=new View();Capture iBlur3Capture;}
class Gifts extends View {Capture iBlur3Capture;}
public class ProfileBlurVisibilityTest {
 static final int VISIBLE=0;MediaPage[] mediaPages={new MediaPage(),new MediaPage()};Gifts giftsContainer=new Gifts();Capture iBlur3Capture;
 int pages,gifts;ProfileBlurVisibilityTest(){for(MediaPage p:mediaPages)p.iBlur3Capture=(c,r)->pages++;giftsContainer.iBlur3Capture=(c,r)->gifts++;${capture}}
 public static void main(String[] args){int checks=0;
  for(int a:new int[]{0,4,8})for(int b:new int[]{0,4,8})for(int l:new int[]{0,4,8})for(int g:new int[]{0,4,8}){
   ProfileBlurVisibilityTest t=new ProfileBlurVisibilityTest();t.mediaPages[0].visibility=a;t.mediaPages[1].visibility=b;t.mediaPages[0].listView.visibility=l;t.giftsContainer.visibility=g;
   t.iBlur3Capture.capture(null,null);checks++;
   if(t.pages!=((a==0&&l==0?1:0)+(b==0?1:0)))throw new AssertionError("hidden page must not contaminate glass");
   if(t.gifts!=(g==0?1:0))throw new AssertionError("hidden gift container must not contaminate glass");
  }
  System.out.println("PASS: "+checks+" extracted glass capture visibility scenarios");
 }
}`;
const dir=fs.mkdtempSync(path.join(os.tmpdir(),'nimarko-profile-blur-'));
function run(code){fs.writeFileSync(path.join(dir,'ProfileBlurVisibilityTest.java'),code);const c=cp.spawnSync('javac',[path.join(dir,'ProfileBlurVisibilityTest.java')],{encoding:'utf8'});assert.equal(c.status,0,c.stderr);return cp.spawnSync('java',['-cp',dir,'ProfileBlurVisibilityTest'],{encoding:'utf8'});}
try{const result=run(java);assert.equal(result.status,0,result.stdout+result.stderr);process.stdout.write(result.stdout);const broken=java.replace('mediaPage.getVisibility() == VISIBLE && mediaPage.listView.getVisibility() == VISIBLE && ','');assert.notEqual(java,broken);const failure=run(broken);assert.notEqual(failure.status,0);assert.match(failure.stderr,/hidden page must not/);console.log('PASS: previous capture includes hidden pages (negative control)');}finally{fs.rmSync(dir,{recursive:true,force:true});}
const gifts=fs.readFileSync(path.join(root,'Gifts/ProfileGiftsContainer.java'),'utf8');
assert.match(gifts.slice(gifts.indexOf('public void initBlurCapture')),/view instanceof Page && view.getVisibility\(\) == VISIBLE/);
