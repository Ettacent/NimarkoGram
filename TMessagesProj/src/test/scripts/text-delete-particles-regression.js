const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/java/app/nimarkogram/messenger/textanim/NimarkoTextAnim.java'), 'utf8');
const start = source.indexOf('        if (delCount > MASS_DELETE_THRESHOLD)');
const end = source.indexOf('        st.hasDeleteGeometry = false;', start);
assert(start >= 0 && end > start);
const condition = 'i < PARTICLE_COUNT && st.particles.size() < MAX_DELETE_PARTICLES';
assert(source.includes(condition));
function method(name) {
 const start = source.indexOf('    private static float '+name+'(');
 const end = source.indexOf('\n    }', start);
 assert(start>=0 && end>start);
 return source.slice(start,end+6);
}
const constants = ['MASS_DELETE_THRESHOLD','MAX_DELETE_GLYPHS','MAX_DELETE_PARTICLES','PARTICLE_COUNT'].map(name => {
 const match = source.match(new RegExp('private static final int '+name+' = \\d+;'));
 assert(match); return match[0];
}).join('\n');
const java = `
import java.util.ArrayList;
public class DeleteParticlesTest {
 ${constants}
 ${method('easeOutQuint')}
 ${method('appearanceProgress')}
 static class State {
  boolean hasDeleteGeometry;
  float[] deleteX=new float[MASS_DELETE_THRESHOLD],deleteY=new float[MASS_DELETE_THRESHOLD];
  ArrayList<Integer> particles=new ArrayList<>();
 }
 static void spawnDeleteParticles(Object edit, State st, float x, float y, String ch) {
  if (ch.trim().isEmpty()) return;
  for(int i=0; ${condition}; i++) st.particles.add(1);
 }
 static void change(State st,int delCount,int insCount,boolean deleteEnabled) {
  int prefix=0,suffix=0,before=delCount; Object edit=null;
  String removed="a".repeat(delCount);
  st.hasDeleteGeometry=delCount<=MASS_DELETE_THRESHOLD;
  ${source.slice(start,end)}
 }
 static void check(boolean v){if(!v)throw new AssertionError();}
 public static void main(String[] args){
  State s=new State(); change(s,1,0,true); check(s.particles.size()==PARTICLE_COUNT);
  for(int i=0;i<1000;i++) change(s,1,0,true);
  check(s.particles.size()==MAX_DELETE_PARTICLES);
  for(int n:new int[]{25,30,10000}) {change(s,n,0,true);check(s.particles.isEmpty());}
  change(s,3,3,true); check(s.particles.size()==3*PARTICLE_COUNT);
  s.particles.clear();
  change(s,2,0,false); check(s.particles.isEmpty());
  change(s,3,0,true);check(s.particles.size()==3*PARTICLE_COUNT);
  s.particles.clear(); change(s,20,18,true);check(s.particles.size()==MAX_DELETE_GLYPHS*PARTICLE_COUNT);
  check(appearanceProgress(100,200,true)==0.5f);
  // Cubic easing keeps a visible tail; quintic used to complete >90% halfway.
  check(appearanceProgress(100,200,false)==0.875f);
  check(appearanceProgress(-10,200,true)==0f && appearanceProgress(500,200,true)==1f);
  for(int hz:new int[]{60,90,120,144}) {
   float previous=0;
   for(int frame=0;frame<100;frame++) {
    float progress=appearanceProgress(frame*1000L/hz,200,true);
    check(progress>=previous && progress<=1);previous=progress;
   }
  }
  System.out.println("PASS: bulk deletion, replacements, fast backspace particle budget, disabled effect and ordinary deletion");
 }
}`;
const dir=fs.mkdtempSync(path.join(os.tmpdir(),'text-delete-particles-'));
fs.writeFileSync(path.join(dir,'DeleteParticlesTest.java'),java);
cp.execFileSync('javac',['DeleteParticlesTest.java'],{cwd:dir,stdio:'inherit'});
cp.execFileSync('java',['DeleteParticlesTest'],{cwd:dir,stdio:'inherit'});
