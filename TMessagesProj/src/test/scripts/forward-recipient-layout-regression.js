const fs = require('node:fs'), path = require('node:path'), os = require('node:os');
const cp = require('node:child_process'), assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java/org/telegram/ui');
const chat = fs.readFileSync(path.join(root, 'ChatActivity.java'), 'utf8');
const dialogs = fs.readFileSync(path.join(root, 'DialogsActivity.java'), 'utf8');
function method(source, signature) {
    const start = source.indexOf(signature); assert(start >= 0, signature);
    let depth = 0;
    const tokens = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|[{}]/g;
    tokens.lastIndex = source.indexOf('{', start);
    for (let t; (t = tokens.exec(source));) {
        if (t[0] === '{') depth++;
        if (t[0] === '}' && --depth === 0) return source.slice(start, tokens.lastIndex);
    }
    throw Error(signature);
}
assert.match(chat, /args.putBoolean\("change_forward_recipient", forward\)/);
assert.match(chat, /showFieldPanelForForward\(true, fmessages, ngForwardOptions\);\s*clearTransferredForward\(recipientForwardSource\)/);
assert.match(dialogs, /private boolean foldersAtBottom\(\) \{ return NimarkoConfig.foldersAtBottom; \}/);
const visibility = method(dialogs, 'private void checkUi_searchFieldVisibility()');
const gate = visibility.slice(visibility.indexOf('final boolean show ='), visibility.indexOf(';', visibility.indexOf('final boolean show =')));
assert.match(gate, /!filterTabsBootstrapPending/);
assert.doesNotMatch(gate, /alpha <=/);
assert.match(method(dialogs, 'private void finishFilterTabsBootstrap()'), /checkUi_searchFieldVisibility\(\)/);
assert.match(method(dialogs, 'private void checkInsets()'), /updateBottomFolderMargin\(\)/);
assert.match(dialogs, /setInputBubbleHeight\(h\);\s*updateBottomFolderMargin\(\)/);
const java = `
public class ForwardRecipientTest {
 static int checks;static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
 static class MessagePreviewParams {boolean forward=true,reply=true;void updateForward(Object o,long id){forward=false;}}
 static class Chat {
  MessagePreviewParams messagePreviewParams;long dialog_id=42;boolean forbidForwardingWithDismiss=true;int fallbacks;
  void fallbackFieldPanel(){fallbacks++;}
  ${method(chat, 'private void clearTransferredForward(')}
 }
 static class Input {float height;float getInputBubbleHeight(){return height;}}
 static class Insets {float height;float getAnimatedMaxBottomInset(){return height;}}
 static class Animator {float value=1;float getFloatValue(){return value;}}
 static class Dialogs {
  Object commentView;Input chatInputViewsContainer=new Input();Insets windowInsetsStateHolder=new Insets();
  Animator animatorForwardButtonVisible=new Animator();
  int navigationBarHeight=24,additionNavigationBarHeight=64;float density=1,folderOffset;
  int dp(int value){return Math.round(value*density);}float getBottomFolderOffset(){return folderOffset;}
  int communityId;
  ${method(dialogs, 'private int getBottomFolderMargin()')}
  ${method(dialogs, 'private int calculateListViewPaddingBottom()')}
 }
 public static void main(String[] args) {
  Chat c=new Chat();MessagePreviewParams source=new MessagePreviewParams();c.messagePreviewParams=source;
  c.clearTransferredForward(null);check(source.forward&&c.fallbacks==0);
  c.clearTransferredForward(new MessagePreviewParams());check(source.forward&&c.fallbacks==0);
  c.clearTransferredForward(source);check(!source.forward&&source.reply&&c.fallbacks==1&&!c.forbidForwardingWithDismiss);
  for(float density:new float[]{1,1.5f,3,4})for(int keyboard:new int[]{0,24,300,700})for(int input:new int[]{0,48,96,180}){
   Dialogs d=new Dialogs();d.density=density;d.commentView=new Object();d.windowInsetsStateHolder.height=keyboard;
   d.chatInputViewsContainer.height=input;d.folderOffset=d.dp(50);
   check(d.getBottomFolderMargin()==keyboard+d.dp(16)+input);
   check(d.calculateListViewPaddingBottom()==d.getBottomFolderMargin()+d.dp(2)+d.dp(50));
   d.folderOffset=0;check(d.calculateListViewPaddingBottom()==d.getBottomFolderMargin()+d.dp(2));
   d.animatorForwardButtonVisible.value=0;check(d.getBottomFolderMargin()==keyboard);
   d.animatorForwardButtonVisible.value=.5f;check(d.getBottomFolderMargin()==Math.round(keyboard+(d.dp(16)+input)*.5f));
   d.commentView=null;check(d.getBottomFolderMargin()==88&&d.calculateListViewPaddingBottom()==88);
  }
  System.out.println("PASS: "+checks+" recipient ownership, retained reply and keyboard/folder layout checks");
 }
}`;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-forward-layout-'));
try {
    function run(code) {
        fs.writeFileSync(path.join(dir, 'ForwardRecipientTest.java'), code);
        const compiled = cp.spawnSync('javac', ['ForwardRecipientTest.java'], {cwd:dir, encoding:'utf8'});
        assert.equal(compiled.status, 0, compiled.stderr);
        return cp.spawnSync('java', ['ForwardRecipientTest'], {cwd:dir, encoding:'utf8'});
    }
    const result=run(java);assert.equal(result.status,0,result.stderr);process.stdout.write(result.stdout);
    const negative=run(java.replace('source != messagePreviewParams','false'));
    assert.notEqual(negative.status,0,'stale recipient must not clear current composer');
} finally {fs.rmSync(dir,{recursive:true,force:true});}
