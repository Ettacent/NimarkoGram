const fs = require('node:fs'), path = require('node:path'), os = require('node:os');
const cp = require('node:child_process'), assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java/org/telegram/ui');
const chat = fs.readFileSync(path.join(root, 'ChatActivity.java'), 'utf8');
const input = fs.readFileSync(path.join(root, 'Components/chat/ChatInputViewsContainer.java'), 'utf8');
function method(source, signature) {
    const start = source.indexOf(signature);
    assert(start >= 0, signature);
    const tokens = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|[{}]/g;
    tokens.lastIndex = source.indexOf('{', start);
    let depth = 0;
    for (let token; (token = tokens.exec(source));) {
        if (token[0] === '{') depth++;
        if (token[0] === '}' && --depth === 0) return source.slice(start, tokens.lastIndex);
    }
    throw Error(signature);
}
const update = method(chat, 'private void onBottomItemsVisibilityChanged()');
const visibility = method(chat, 'private void checkBottomViewVisibility(');
const setter = method(input, 'public void setInputBubbleAlpha(int alpha)');
const render = method(input, 'private void drawComposerBackground(');
const earlyReturn = render.slice(0, render.indexOf('syncLeadingComposerExpansion();'));
for (const name of ['inputCenterTouchBounds', 'inputLeadingTouchBounds', 'inputTrailingTouchBounds']) {
    assert(earlyReturn.includes(name + '.setEmpty();'), 'hidden surface must clear touch bounds');
}
for (const name of ['blurredBackgroundDrawable', 'leadingComposerDrawable', 'trailingComposerDrawable']) {
    assert(setter.includes(name + '.setAlpha(inputBubbleAlpha)'), 'all three surfaces must share visibility');
}
const java = `
class View {
 static int VISIBLE=0,GONE=8;float alpha=1,y,total;int visibility;
 void setAlpha(float value){alpha=value;}void setTranslationY(float value){y=value;}
 void setTotalVisibilityFactor(float value){total=value;}int getVisibility(){return visibility;}
 void setVisibility(int value){visibility=value;}void invalidate(){}
}
class Input extends View {int alpha;void setInputBubbleAlpha(int value){alpha=value;}void setInputBubbleTranslationY(float value){y=value;}}
class Animator {float value;float getFloatValue(){return value;}}
class Controller {float[] values={0,1,0,0,0,0};float getVisibility(int id){return values[id];}}
class Pull {float progressToBottomPanel;}
public class ChatPullSurfaceTest {
 static final int MESSAGE_INPUT_CONTAINER=1,BOTTOM_OVERLAY_TEXT_CONTAINER=2,BOTTOM_OVERLAY_CHAT_CONTAINER=3,MESSAGE_SEARCH_CONTAINER=4,MESSAGE_ACTION_CONTAINER=5;
 Controller bottomViewsVisibilityController=new Controller();
 Animator animatorPullingDownContainerVisibility=new Animator(),animatorPollAddAnswerVisibility=new Animator();
 View actionsButtonsLayout=new View(),chatActivityEnterView=new View(),searchContainer=new View(),bottomChannelButtonsLayout=new View(),bottomOverlay=new View(),fragmentView=new View();
 Input chatInputViewsContainer=new Input();Pull pullingDownDrawable=new Pull();
 int dp(int value){return value;}void checkUi_inputIslandHeight(){}
 ${visibility}
 ${update}
 static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 public static void main(String[] args){
  ChatPullSurfaceTest t=new ChatPullSurfaceTest();
  for(float action:new float[]{0,.5f,1})for(float poll:new float[]{0,.5f,1})for(int step=0;step<=200;step++){
   float pull=(step<=100?step:200-step)/100f;
   t.bottomViewsVisibilityController.values[MESSAGE_ACTION_CONTAINER]=action;
   t.animatorPollAddAnswerVisibility.value=poll;t.animatorPullingDownContainerVisibility.value=pull;
   t.onBottomItemsVisibilityChanged();
   float expected=(1-action)*(1-poll)*(1-pull);
   check(Math.abs(t.chatInputViewsContainer.alpha-255*expected)<1.01f,"composer material follows pull, selection and poll factors");
   check(Math.abs(t.chatActivityEnterView.alpha-(1-poll)*(1-pull))<.0001f,"input contents follow pull");
   check(t.pullingDownDrawable.progressToBottomPanel==pull,"hint and composer share one animation");
   check(t.chatInputViewsContainer.y==54*(1-(1-action)*(1-poll)),"pull does not move composer geometry");
   if(pull==1)check(t.chatInputViewsContainer.alpha==0&&t.chatActivityEnterView.alpha==0,"no empty button outlines behind hint");
  }
  t.chatInputViewsContainer=null;t.pullingDownDrawable=null;t.chatActivityEnterView=null;t.onBottomItemsVisibilityChanged();
  System.out.println("PASS: "+checks+" channel pull surface checks, reversal and nullable lifecycle");
 }
}`;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-chat-pull-'));
try {
    const file = path.join(dir, 'ChatPullSurfaceTest.java');
    function run(source) {
        fs.writeFileSync(file, source);
        cp.execFileSync('javac', [file], {stdio: 'pipe'});
        return cp.spawnSync('java', ['-cp', dir, 'ChatPullSurfaceTest'], {encoding: 'utf8'});
    }
    const result = run(java);
    assert.equal(result.status, 0, result.stderr);
    process.stdout.write(result.stdout);
    const broken = java.replace('* (1f - animatorPullingDownContainerVisibility.getFloatValue())));', '));');
    assert.notEqual(broken, java);
    const negative = run(broken);
    assert.notEqual(negative.status, 0);
    assert.match(negative.stderr, /composer material follows pull/);
    console.log('PASS: always-visible composer negative control');
} finally {
    fs.rmSync(dir, {recursive: true, force: true});
}
