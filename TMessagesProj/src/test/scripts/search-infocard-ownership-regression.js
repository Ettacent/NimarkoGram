const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const read = file => fs.readFileSync(path.resolve(__dirname, '../../main/java', file), 'utf8');
const source = read('org/telegram/ui/Components/FragmentSearchField.java');
const dialogs = read('org/telegram/ui/DialogsActivity.java');
function method(signature) {
    const start = source.indexOf(signature);
    assert(start >= 0, signature);
    let end = source.indexOf('{', start), depth = 1;
    while (depth && ++end < source.length) {
        if (source[end] === '{') depth++;
        if (source[end] === '}') depth--;
    }
    return source.slice(start, end + 1);
}
assert.match(dialogs, /fragmentSearchField.setInfoCardsSuppressed\(hideHomeSearchField && homeInfoCards != null\)/);
const code = `
public class SearchCardTest {
 static final int ANIMATOR_ID_CLOSE_BUTTON_VISIBLE=1,ANIMATOR_ID_SEARCH_ICON_VISIBLE=2,
   ANIMATOR_ID_SEARCH_FILTERS_WIDTH=3,ANIMATOR_ID_INFO_CARDS_VISIBLE=4;
 static class FactorAnimator {}
 static class Animator {float value=1;float getFloatValue(){return value;}}
 static class Card {float factor=1;void setVisibilityFactor(float f){factor=f;}void setRotation(float f){}}
 static class FragmentFloatingButton {static void setAnimatedVisibility(Card c,float f){}}
 Card infoCards=new Card(),closeIcon=new Card(),searchIcon=new Card();
 Animator animatorInfoCardsVisible=new Animator();boolean infoCardsSuppressed;
 void checkUi_editTextPaddings(){}
 ${method('public void setInfoCardsSuppressed(')}
 ${method('public void onFactorChanged(')}
 static int checks;static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
 public static void main(String[] args){
  SearchCardTest t=new SearchCardTest();
  t.setInfoCardsSuppressed(true);
  check(t.infoCards.factor==0,"home capsule immediately takes ownership");
  for(int cycle=0;cycle<20;cycle++)for(int i=0;i<=200;i++){
   float search=i<=100?i/100f:(200-i)/100f;
   float factor=1-search;t.animatorInfoCardsVisible.value=factor;
   t.onFactorChanged(ANIMATOR_ID_INFO_CARDS_VISIBLE,factor,0,null);
   check(t.infoCards.factor==0,"search callbacks cannot restore a duplicate card");
   t.setInfoCardsSuppressed(false);
   check(t.infoCards.factor==factor,"ordinary search row restores current animated state");
   t.onFactorChanged(ANIMATOR_ID_INFO_CARDS_VISIBLE,factor,0,null);
   check(t.infoCards.factor==factor,"ordinary search row remains animated");
   t.setInfoCardsSuppressed(true);
   check(t.infoCards.factor==0,"switching home layout cannot leave the old strip visible");
  }
  t.infoCards=null;t.setInfoCardsSuppressed(false);t.setInfoCardsSuppressed(true);
  t.onFactorChanged(ANIMATOR_ID_INFO_CARDS_VISIBLE,1,0,null);
  System.out.println("PASS: "+checks+" extracted search-card ownership checks");
 }
}`;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-search-cards-'));
function run(java) {
    fs.writeFileSync(path.join(dir, 'SearchCardTest.java'), java);
    const compile = cp.spawnSync('javac', [path.join(dir, 'SearchCardTest.java')], {encoding:'utf8'});
    assert.equal(compile.status, 0, compile.stderr);
    return cp.spawnSync('java', ['-cp', dir, 'SearchCardTest'], {encoding:'utf8'});
}
try {
    const result = run(code);
    assert.equal(result.status, 0, result.stderr);
    process.stdout.write(result.stdout);
    const broken = code.replace('infoCardsSuppressed ? 0f : factor', 'factor');
    assert.notEqual(broken, code);
    const failure = run(broken);
    assert.notEqual(failure.status, 0);
    assert.match(failure.stderr, /cannot restore a duplicate card/);
    console.log('PASS: negative control reproduces duplicate search cards');
} finally { fs.rmSync(dir, {recursive:true,force:true}); }
