const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/java/org/telegram/ui/Components/glass/GlassTabView.java'), 'utf8');
const begin = source.indexOf('        if (imageView.getAnimatedDrawable() == null) {', source.indexOf('private void checkPlayAnimation'));
const end = source.indexOf('\n    public static GlassTabView createMainTab', begin);
assert(begin > 0 && end > begin);
const method = source.slice(begin, end);
const java = `
class RLottieDrawable {
 int frame, target, frames=6, seeks, plays, stops; boolean reverse;
 int getFramesCount(){return frames;}int getCurrentFrame(){return frame;}
 void setCurrentFrame(int f){if(f<0||f>=frames)throw new AssertionError("invalid frame "+f);frame=f;seeks++;}
 void setCustomEndFrame(int f){if(f<0||f>=frames)throw new AssertionError("invalid end frame");target=f;}
 void setPlayInDirectionOfCustomEndFrame(boolean b){reverse=b;}
}
class ImageView {
 RLottieDrawable drawable;
 RLottieDrawable getAnimatedDrawable(){return drawable;}
 void setAnimation(int id,int w,int h){drawable=new RLottieDrawable();}
 void playAnimation(){drawable.plays++;}void stopAnimation(){drawable.stops++;}
}
public class TabIconTest {
 boolean lastIsSelected;ImageView imageView=new ImageView();
 static class Animation {int iconToFilled=1;}Animation tabAnimation=new Animation();
 void select(boolean isSelected,boolean animated){
 ${method}
 static int checks;
 static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 public static void main(String[] args){
  TabIconTest t=new TabIconTest();t.select(false,false);RLottieDrawable d=t.imageView.drawable;
  check(d.frame==0&&d.plays==0,"initial outline does not animate");
  t.select(true,false);check(d.frame==5&&d.target==5&&d.plays==0,"initial active icon uses valid last frame without animation");
  t.select(false,true);check(d.frame==5&&d.target==0&&d.reverse,"deselection keeps current frame");
  d.frame=2;int seeks=d.seeks;t.select(true,true);
  check(d.frame==2&&d.seeks==seeks&&d.target==5,"rapid reversal must not restart at zero");
  d.frame=3;seeks=d.seeks;t.select(false,true);
  check(d.frame==3&&d.seeks==seeks&&d.target==0,"rapid deselection must not jump to full icon");
  int plays=d.plays;t.select(false,true);check(d.plays==plays,"repeated pager updates do not restart animation");
  t.select(false,false);check(d.frame==0&&d.stops>0,"nonanimated update stops pending playback");
  for(int frames=2;frames<=120;frames++)for(int frame=0;frame<frames;frame++){
   d.frames=frames;d.frame=frame;t.lastIsSelected=false;seeks=d.seeks;t.select(true,true);
   check(d.frame==frame&&d.target==frames-1&&d.seeks==seeks,"selection preserves partial animation");
   t.select(false,true);check(d.frame==frame&&d.target==0&&d.seeks==seeks,"reversal preserves partial animation");
  }
  System.out.println("PASS: "+checks+" tab icon checks: valid frames, rapid reversals, initialization and repeated updates");
 }
}`;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-tab-icon-'));
try {
 const run = code => {
  fs.writeFileSync(path.join(dir, 'TabIconTest.java'), code);
  cp.execFileSync('javac', ['TabIconTest.java'], {cwd: dir, stdio: 'pipe'});
  return cp.spawnSync('java', ['TabIconTest'], {cwd: dir, encoding: 'utf8'});
 };
 const result = run(java);
 assert.equal(result.status, 0, result.stderr);
 process.stdout.write(result.stdout);
 const broken = java.replace('imageView.playAnimation();', 'drawable.setCurrentFrame(isSelected ? 0 : drawable.getFramesCount() - 1);imageView.playAnimation();');
 assert.notEqual(broken, java);
 const bad = run(broken);
 assert.notEqual(bad.status, 0);
 assert.match(bad.stderr, /rapid reversal must not restart/);
 console.log('PASS: forced endpoint reset negative control');
} finally {
 fs.rmSync(dir, {recursive: true, force: true});
}
