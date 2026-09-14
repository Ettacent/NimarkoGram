const fs = require('node:fs'), path = require('node:path'), os = require('node:os');
const cp = require('node:child_process'), assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/java/org/telegram/ui/ProfileActivity.java'), 'utf8');
const method = source.slice(source.indexOf('    private void collapseAvatarInstant()'), source.indexOf('    private boolean expandAvatar()'));
const statusStart = source.indexOf('    private void flushPendingProfileRowsUpdate()');
const statusEnd = source.indexOf('    private void updateListAnimated(boolean updateOnlineCount, boolean triedInLayout)', statusStart);
const statusBody = source.slice(statusStart, statusEnd).replace(/^[\s\S]*?\{/, '').replace(/\}\s*$/, '').replace(/\bboolean /g, 'let ');
function refresh(body, transitioning = false, online = true) {
    const calls = [];
    new Function('calls', 'transitionAnimationInProress', 'pendingProfileRowsOnlineCount', `
        let profileRowsUpdatePosted=true, profileLifecycleDestroyed=false, pendingProfileRowsUpdate=true;
        let openAnimationInProgress=false, pendingProfileRowsSelectedMediaText=false;
        const updateListAnimated=()=>calls.push('rows');
        const updateProfileData=reload=>calls.push(reload ? 'reload' : 'status');
        const updateSelectedMediaTabText=()=>calls.push('tabs');
        ${body}
    `)(calls, transitioning, online);
    return calls;
}
assert.deepEqual(refresh(statusBody), ['rows', 'status']);
assert.deepEqual(refresh(statusBody, true), []);
assert.deepEqual(refresh(statusBody, false, false), ['rows']);
assert.deepEqual(refresh(statusBody.replace('updateProfileData(false);', '')), ['rows'], 'old implementation leaves the header stale');
const java = `
class ListView {Runnable pending;void post(Runnable r){pending=r;}int getPaddingTop(){return 1000;}}
class Layout {void scrollToPositionWithOffset(int p,int o){}}
class Animator {boolean running=true;boolean isRunning(){return running;}void cancel(){running=false;}}
public class CollapseTest {
 boolean allowPullingDown=true,profileLifecycleDestroyed;int profileLifecycleGeneration=1,layoutCalls,finalizations;
 float currentExpandAnimatorValue=1,extraHeight=900;float[] expandAnimatorValues={0,1};
 ListView listView=new ListView();Layout layoutManager=new Layout();Animator expandAnimator=new Animator();
 int getHeaderExtraHeight(){return 200;}
 void needLayout(boolean animated){if(extraHeight!=200)throw new AssertionError("stale header geometry");layoutCalls++;}
 void setAvatarExpandProgress(float f){currentExpandAnimatorValue=expandAnimatorValues[0]+(expandAnimatorValues[1]-expandAnimatorValues[0])*f;}
 void finalizeCollapsePager(){finalizations++;}
 ${method}
 static void check(boolean v){if(!v)throw new AssertionError();}
 public static void main(String[] args){
  for(float end:new float[]{0,.3f,1}){
   CollapseTest t=new CollapseTest();t.expandAnimatorValues[1]=end;t.collapseAvatarInstant();t.listView.pending.run();
   check(t.currentExpandAnimatorValue==0&&t.extraHeight==200&&t.finalizations==1&&!t.expandAnimator.running);
  }
  for(int state=0;state<3;state++){
   CollapseTest t=new CollapseTest();t.collapseAvatarInstant();Runnable callback=t.listView.pending;
   if(state==0)t.profileLifecycleDestroyed=true;else if(state==1)t.profileLifecycleGeneration++;else t.listView=null;
   callback.run();check(t.layoutCalls==0&&t.finalizations==0);
  }
  System.out.println("PASS: instant avatar collapse resets stale expand endpoints and ignores destroyed/recreated views");
 }
}`;
const dir=fs.mkdtempSync(path.join(os.tmpdir(),'profile-collapse-'));
function run(text){fs.writeFileSync(path.join(dir,'CollapseTest.java'),text);cp.execFileSync('javac',['CollapseTest.java'],{cwd:dir});return cp.spawnSync('java',['CollapseTest'],{cwd:dir,encoding:'utf8'});}
try {
 const result=run(java);assert.equal(result.status,0,result.stderr);process.stdout.write(result.stdout);
 for(const removed of ['extraHeight = getHeaderExtraHeight();','expandAnimatorValues[1] = 0f;']) {
  assert.notEqual(run(java.replace(removed,'')).status,0,'old collapse must fail: '+removed);
 }
} finally {fs.rmSync(dir,{recursive:true,force:true});}
