const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/java/app/nimarkogram/messenger/banners/NimarkoBannerRenderer.java'), 'utf8');
function block(marker) {
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
const quick = source.match(/if \((extra == frameLastExtra[\s\S]*?)\) \{/)[1];
const java = `import java.util.*;
public class BannerAvatarTest {
 Map<Long,Float> avAlpha=new HashMap<>(),avBase=new HashMap<>();
 Map<Long,Double> avTimes=new HashMap<>(); Set<Long> avAnim=new HashSet<>(),avShow=new HashSet<>();
 boolean profileExitActive,collapseSettling,vidReady=true;double vidFirstFrameTime=1;long profileExitEid,avatarResumeHoldDialogId,viewedProfileId=1;
 Object avatarResumeHoldView,currentTopView=new Object();double avatarResumeHoldUntil,now;
 float lastFadeGifts=-1,painted=1;static final double AV_HIDE_DUR=1;
 static class Controller{boolean shouldHideAvatar(long id){return true;}}Controller ctrl=new Controller();
 double t(){return now;}void postInv(){}void clearAvatarResumeHold(){avatarResumeHoldView=null;}
 void setAvAlpha(float a,float g){painted=a;lastFadeGifts=g;}
 static float getOr(Map<Long,Float> m,long k,float d){return m.getOrDefault(k,d);}
 static double getOr(Map<Long,Double> m,long k,double d){return m.getOrDefault(k,d);}
 static float clamp01(float f){return Math.max(0,Math.min(1,f));}
 ${block('private void updateAvFade(')}
 ${block('private boolean isVideoAvatarStateSettled(')}
 ${block('public void setCollapseSettling(')}
 ${block('private static double smoothFade(')}
 ${block('private static double fadeProgressForAlpha(')}
 ${block('private void anchorFadeStart(')}
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 static void close(float a,float b,String s){check(Math.abs(a-b)<0.0031f,s+": "+a+" -> "+b);}
 void step(boolean hide,float exp,double dt){now+=dt;updateAvFade(1,hide,now,false,0,exp);check(Float.isFinite(painted)&&painted>=0&&painted<=1,"alpha range");}
 static boolean cached(float expand,float frameLastExpand){
  BannerAvatarTest owner=new BannerAvatarTest();owner.avAlpha.put(1L,expand);long eid=1;
  float extra=180,frameLastExtra=180;Set<Long> avAnim=new HashSet<>();double blurFadeStart=0;
  Object videoPlayer=new Object(),topView=new Object();String curVidPath="video",curBf="video";
  boolean showingPh=false,curLoading=false,openAnimDone=true,openAnim=false,transAnim=false;
  return ${quick.replace('isVideoAvatarStateSettled(eid, expand)', 'owner.isVideoAvatarStateSettled(eid, expand)')};
 }
 static boolean isVideoAttachedTo(Object v){return true;}
 public static void main(String[] args){
  String mode=args.length==0?"all":args[0];
  if(mode.equals("all")||mode.equals("initial"))for(float exp:new float[]{0.06f,0.3f,0.6f,0.95f}){
   BannerAvatarTest r=new BannerAvatarTest();r.step(true,exp,0);float a=r.painted;
   r.step(true,exp,0.008);close(a,r.painted,"unchanged initial gesture must not reveal twice");
  }
  if(mode.equals("all")||mode.equals("settle"))for(float base:new float[]{0,0.1f,0.4f,0.8f}){
   BannerAvatarTest r=new BannerAvatarTest();r.avAlpha.put(1L,base);r.avBase.put(1L,base);
   r.step(true,0.5f,0);float a=r.painted;
   for(int i=0;i<30;i++){
    r.setCollapseSettling(true);r.setCollapseSettling(false);r.step(true,0.5f,0.008);
    close(a,r.painted,"settle cancellation must not compound gesture alpha");
   }
   for(int i=49;i>=1;i--){float prev=r.painted;r.step(true,i/100f,0.008);check(r.painted<=prev+0.0031f,"collapse monotonic");}
   float prev=r.painted;r.step(true,0,0.008);close(prev,r.painted,"handoff to timed fade");
   for(int i=0;i<140;i++){prev=r.painted;r.step(true,0,0.008);check(r.painted<=prev+0.0031f,"hide monotonic");}
   close(0,r.painted,"hide must finish");
  }
  if(mode.equals("all")||mode.equals("cache")){
   BannerAvatarTest cold=new BannerAvatarTest();
   check(!cold.isVideoAvatarStateSettled(1,0),"decoded frame cannot skip unstarted avatar hide");
   cold.step(true,0,0);
   check(!cold.isVideoAvatarStateSettled(1,0),"visible avatar still needs hide frames");
   for(int i=0;i<140;i++)cold.step(true,0,.008);
   check(cold.isVideoAvatarStateSettled(1,0),"settled hidden avatar can cache");
   check(cached(0.5f,0.5f),"stationary video keeps fast path");
   check(!cached(0.4f,0.5f),"expansion changed while header height stayed fixed");
  }
  if(mode.equals("all")){
   for(float base:new float[]{0,0.25f,0.75f}){
    BannerAvatarTest g=new BannerAvatarTest();g.avAlpha.put(1L,base);g.avBase.put(1L,base);
    for(int turn=0;turn<80;turn++)for(int i=1;i<=20;i++){
     float exp=turn%2==0?i/20f:1-i/21f;
     g.step(true,exp,1.0/120);close(base+exp*(1-base),g.painted,"rapid gesture retains original base");
     g.setCollapseSettling(true);g.setCollapseSettling(false);
    }
    g.step(false,0,0);float a=g.painted;
    g.setCollapseSettling(true);g.setCollapseSettling(false);g.step(false,0,0);
    close(a,g.painted,"settle must not reverse the show curve");
    g.profileExitActive=true;g.profileExitEid=1;g.step(true,0,0.1);
    close(a,g.painted,"profile exit retains alpha ownership");
   }
   BannerAvatarTest r=new BannerAvatarTest();r.step(true,0,0);
   for(int i=0;i<30;i++)r.step(true,0,0.008);
   for(int turn=0;turn<50;turn++){
    boolean hide=turn%2!=0;float a=r.painted;r.step(hide,0,0);close(a,r.painted,"direction reversal");
    for(int i=0;i<9;i++){a=r.painted;r.step(hide,0,0.008);check(hide?r.painted<=a+0.0031f:r.painted>=a-0.0031f,"fade direction");}
   }
   for(int i=0;i<140;i++)r.step(false,0,0.008);
   close(1,r.painted,"show must finish");check(r.avAlpha.isEmpty()&&r.avAnim.isEmpty()&&r.avBase.isEmpty(),"show cleanup");
  }
  System.out.println("PASS: avatar gesture/fade continuity, repeated settle cancellation, reversals and frame cache");
 }
}`;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-avatar-fade-'));
try {
    const file = path.join(dir, 'BannerAvatarTest.java');
    fs.writeFileSync(file, java);
    cp.execFileSync('javac', [file]);
    const result = cp.spawnSync('java', ['-cp', dir, 'BannerAvatarTest', ...process.argv.slice(2)], {encoding:'utf8'});
    assert.equal(result.status, 0, result.stderr);
    console.log(result.stdout.trim());
    if (process.argv.length === 2) {
        const negatives = [
            [java.replace('avBase.put(eid, 0f);', ''), 'initial', 'unchanged initial gesture'],
            [java.replace('expand == frameLastExpand && ', ''), 'cache', 'expansion changed'],
            [java.replace('if (ca > 0.001f)', 'if (ca > 0.01f)'), 'settle', 'handoff'],
            [java.replace('&& !avBase.containsKey(eid)', '').replace('&& !avShow.contains(eid)', '')
                .replace('if (!hasBl) avBase.put(eid, ca);', 'if (isA || !hasBl) avBase.put(eid, ca);'),
                'settle', 'settle cancellation'],
        ];
        for (const [broken, mode, expected] of negatives) {
            assert.notEqual(broken, java);
            fs.writeFileSync(file, broken);
            cp.execFileSync('javac', [file]);
            const negative = cp.spawnSync('java', ['-cp', dir, 'BannerAvatarTest', mode], {encoding:'utf8'});
            assert.notEqual(negative.status, 0, `negative control: ${mode}`);
            assert(negative.stderr.includes(expected), negative.stderr);
        }
        console.log('PASS: negative controls reproduce initialization, settle, tail and cached-frame failures');
    }
} finally {
    fs.rmSync(dir, {recursive:true, force:true});
}
