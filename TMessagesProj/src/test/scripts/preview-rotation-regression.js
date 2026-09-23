const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),cp=require('node:child_process'),assert=require('node:assert/strict');
const source=fs.readFileSync(path.resolve(__dirname,'../../main/java/org/telegram/ui/ActionBar/ActionBarLayout.java'),'utf8');
function method(signature){
 const start=source.indexOf('    '+signature); assert(start>=0,signature);
 let depth=0,end=source.indexOf('{',start);
 do{if(source[end]==='{')depth++;if(source[end]==='}')depth--;end++;}while(depth>0);
 return source.slice(start,end).replace('@Override','');
}
const close=method('public void closeLastFragment(boolean animated, boolean forceNoAnimation)');
const prefix=close.slice(0,close.indexOf('        if (parentActivity.getCurrentFocus()'));
assert(prefix.includes('getLastFragment() != fragment'));
assert(source.includes('schedulePreviewCloseAfterRotation();'));
const dir=fs.mkdtempSync(path.join(os.tmpdir(),'preview-rotation-'));
fs.writeFileSync(path.join(dir,'RotationTest.java'),`import java.util.*;
public class RotationTest {
 static class BaseFragment { boolean closeLastFragment(){return false;} }
 static class AndroidUtilities {static void cancelRunOnUIThread(Runnable r){} }
 static class SystemClock {static long uptimeMillis(){return System.nanoTime()/1000000;}}
 interface Delegate {boolean needCloseLastFragment(RotationTest layout);}
 ArrayList<BaseFragment> fragmentsStack=new ArrayList<>(); ArrayList<Runnable> posted=new ArrayList<>();
 BaseFragment previewRotationCloseFragment; Delegate delegate;
 Runnable onCloseAnimationEndRunnable,onOpenAnimationEndRunnable,delayedOpenAnimationRunnable;
 boolean inPreviewMode=true,transitionAnimationPreviewMode,transitionAnimationInProgress,animationInProgress;
 boolean startedTracking,predictiveBackInProgress,predictiveInput;
 long transitionAnimationStartTime; int closes;
 RotationTest(){fragmentsStack.add(new BaseFragment());fragmentsStack.add(new BaseFragment());}
 BaseFragment getLastFragment(){return fragmentsStack.isEmpty()?null:fragmentsStack.get(fragmentsStack.size()-1);}
 boolean isInPreviewMode(){return inPreviewMode||transitionAnimationPreviewMode;}
 void post(Runnable r){posted.add(r);}
 void drain(){ArrayList<Runnable> copy=new ArrayList<>(posted);posted.clear();for(Runnable r:copy)r.run();}
 void onAnimationEndCheck(boolean force){
  if(onCloseAnimationEndRunnable!=null){Runnable r=onCloseAnimationEndRunnable;onCloseAnimationEndRunnable=null;r.run();}
  else if(onOpenAnimationEndRunnable!=null){onOpenAnimationEndRunnable=null;transitionAnimationPreviewMode=false;inPreviewMode=true;}
  transitionAnimationInProgress=false;
 }
 ${method('public boolean checkTransitionAnimation()')}
 ${method('private void schedulePreviewCloseAfterRotation()')}
 ${method('public void finishPreviewFragment()')}
 void closeLastFragment(boolean animated){closeLastFragment(animated,false);}
 ${prefix}
  closes++; transitionAnimationInProgress=true; transitionAnimationStartTime=SystemClock.uptimeMillis();
  final BaseFragment closing=getLastFragment();
  onCloseAnimationEndRunnable=()->{fragmentsStack.remove(closing);inPreviewMode=false;transitionAnimationPreviewMode=false;};
 }
 static void check(boolean v){if(!v)throw new AssertionError();}
 public static void main(String[]args){
  for(boolean opening:new boolean[]{false,true}){
   RotationTest t=new RotationTest();BaseFragment root=t.fragmentsStack.get(0);
   if(opening){t.inPreviewMode=false;t.transitionAnimationPreviewMode=true;t.transitionAnimationInProgress=true;t.onOpenAnimationEndRunnable=()->{};}
   t.schedulePreviewCloseAfterRotation();t.schedulePreviewCloseAfterRotation();check(t.posted.size()==1&&t.closes==0);
   t.drain();check(t.closes==1);t.finishPreviewFragment();check(t.closes==1);
   t.schedulePreviewCloseAfterRotation();t.drain();check(t.closes==1);
   t.onAnimationEndCheck(true);check(t.fragmentsStack.size()==1&&t.getLastFragment()==root);
  }
  RotationTest t=new RotationTest();t.schedulePreviewCloseAfterRotation();BaseFragment other=new BaseFragment();t.fragmentsStack.add(other);t.drain();check(t.closes==0&&t.getLastFragment()==other);
  t=new RotationTest();t.schedulePreviewCloseAfterRotation();t.previewRotationCloseFragment=null;t.drain();check(t.closes==0);
  t=new RotationTest();t.closeLastFragment(true);t.closeLastFragment(true);check(t.closes==1&&t.fragmentsStack.size()==1);
  for(int gesture=0;gesture<3;gesture++){
   t=new RotationTest();t.startedTracking=gesture==0;t.predictiveBackInProgress=gesture==1;t.predictiveInput=gesture==2;
   t.closeLastFragment(true);check(t.closes==0&&t.fragmentsStack.size()==2);
  }
  System.out.println("PASS: double container rotation, opening/closing previews, repeated close, stale stack callbacks and root preservation");
 }
}`);
cp.execFileSync('javac',['RotationTest.java'],{cwd:dir,stdio:'inherit'});
cp.execFileSync('java',['RotationTest'],{cwd:dir,stdio:'inherit'});
