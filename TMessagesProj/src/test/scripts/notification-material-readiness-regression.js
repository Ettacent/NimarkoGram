const fs = require('node:fs'), path = require('node:path'), cp = require('node:child_process');
const os = require('node:os'), assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java');
const read = name => fs.readFileSync(path.join(root, name), 'utf8');
const noise = read('org/telegram/ui/Components/blur3/DownscaleScrollableNoiseSuppressor.java');
const source = read('org/telegram/ui/Components/blur3/source/BlurredBackgroundSourceRenderNode.java');
function method(text, signature) {
    const start = text.indexOf(signature);
    assert(start >= 0, signature);
    let depth = 0;
    for (let i = text.indexOf('{', start); i < text.length; i++) {
        if (text[i] === '{') depth++;
        if (text[i] === '}' && --depth === 0) return text.slice(start, i + 1);
    }
    throw Error(signature);
}
assert.match(method(noise, 'public void drawInline('), /resolveInlineIndex\(index\)/);
const java = `import java.util.*;
class RenderNode {boolean ready=true;int width=1080,height=144;boolean hasDisplayList(){return ready;}int getWidth(){return width;}int getHeight(){return height;}}
class Build {static class VERSION {static int SDK_INT=36;}static class VERSION_CODES {static final int S=31;}}
class Buffers {RenderNode[] renderNodeRestored={new RenderNode(),new RenderNode()};}
class SourcePart {Buffers renderNodesForGlass,renderNodesForBlur=new Buffers();}
class Noise {
 boolean isLiquidGlassEnabled,simpleMode;Object recordingPos;int rectRenderNodesCount;
 static final int DRAW_GLASS=-2,DRAW_FROSTED_GLASS=-3,DRAW_FROSTED_GLASS_NO_SATURATION=-4;
 ArrayList<SourcePart> rectRenderNodes=new ArrayList<>();
 ${method(noise, 'private int resolveInlineIndex(')}
 ${method(noise, 'private RenderNode getRenderNode(')}
 ${method(noise, 'public boolean isDisplayListReady(')}
 RenderNode selected(int index,int b){return getRenderNode(resolveInlineIndex(index),b);}
}
class Source {
 boolean inRecording;RenderNode renderNode=new RenderNode();Noise scrollableNoiseSuppressor;int scrollableNoiseSuppressorIndex=-3;
 ${method(source, 'public boolean isDisplayListReady()')}
}
public class ReadinessTest {
 static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 public static void main(String[] args){
  for(boolean liquid:new boolean[]{false,true})for(boolean simple:new boolean[]{false,true})for(int mode:new int[]{-2,-3,-4}){
   Noise n=new Noise();n.isLiquidGlassEnabled=liquid;n.simpleMode=simple;
   Source s=new Source();s.scrollableNoiseSuppressor=n;s.scrollableNoiseSuppressorIndex=mode;
   s.renderNode.ready=false;s.renderNode.width=s.renderNode.height=0;
   check(!s.isDisplayListReady(),"empty capture cannot become ready");
   for(int i=0;i<2;i++){SourcePart p=new SourcePart();if(liquid)p.renderNodesForGlass=new Buffers();n.rectRenderNodes.add(p);}
   n.rectRenderNodesCount=2;
   check(s.isDisplayListReady(),"recorded chat: both inline parts ready while unused source node stays 0x0");
   int expected=!liquid&&simple?0:mode==-2?(liquid?0:1):mode==-4?0:1;
   SourcePart p=n.rectRenderNodes.get(1);
   RenderNode target=liquid&&expected==0?p.renderNodesForGlass.renderNodeRestored[0]:p.renderNodesForBlur.renderNodeRestored[liquid?0:expected];
   check(n.selected(mode,1)==target,"readiness inspects the buffer selected by native drawing");
   target.ready=false;check(!s.isDisplayListReady(),"part still preparing blocks promotion");target.ready=true;
   target.width=0;check(!s.isDisplayListReady(),"zero width blocks promotion");target.width=1080;
   target.height=0;check(!s.isDisplayListReady(),"zero height blocks promotion");target.height=144;
   n.recordingPos=new Object();check(!s.isDisplayListReady(),"inline capture in progress blocks promotion");n.recordingPos=null;
   s.inRecording=true;check(!s.isDisplayListReady(),"outer capture in progress blocks promotion");s.inRecording=false;
   check(s.isDisplayListReady(),"complete capture unblocks promotion");
   check(!n.isDisplayListReady(999)||!liquid&&simple,"invalid mode follows existing simple-mode drawing semantics");
  }
  Source plain=new Source();check(plain.isDisplayListReady(),"standalone render node ready");plain.renderNode.ready=false;
  check(!plain.isDisplayListReady(),"cold standalone node still waits");plain.renderNode.ready=true;plain.renderNode.width=0;
  check(!plain.isDisplayListReady(),"empty standalone node still waits");plain.renderNode.width=12;plain.inRecording=true;
  check(!plain.isDisplayListReady(),"standalone recording guard");
  System.out.println("PASS: "+checks+" production readiness checks, including recorded 0x0-node chat path");
 }
}`;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-material-ready-'));
try {
    const run = text => {
        fs.writeFileSync(path.join(dir, 'ReadinessTest.java'), text);
        cp.execFileSync('javac', ['ReadinessTest.java'], {cwd: dir});
        return cp.spawnSync('java', ['ReadinessTest'], {cwd: dir, encoding: 'utf8'});
    };
    const result = run(java); assert.equal(result.status, 0, result.stderr); process.stdout.write(result.stdout);
    const old = java.replace(method(source, 'public boolean isDisplayListReady()'),
        'public boolean isDisplayListReady(){return !inRecording&&renderNode.hasDisplayList()&&renderNode.getWidth()>0&&renderNode.getHeight()>0;}');
    assert.notEqual(old, java); assert.notEqual(run(old).status, 0, 'old unused-node guard must reproduce the stuck fallback');
    const forced = java.replace('return scrollableNoiseSuppressor.isDisplayListReady(scrollableNoiseSuppressorIndex);', 'return true;');
    assert.notEqual(forced, java); assert.notEqual(run(forced).status, 0, 'forcing ready must fail incomplete-capture checks');
    console.log('PASS: previous unused-node and premature-ready negative controls rejected');
} finally { fs.rmSync(dir, {recursive: true, force: true}); }
