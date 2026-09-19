const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const os = require('node:os');
const cp = require('node:child_process');
const source = fs.readFileSync(path.resolve(__dirname,
    '../../main/java/org/telegram/ui/Components/ChatAvatarContainer.java'), 'utf8');
const smooth = source.slice(source.indexOf('private void setSubtitleTextSmooth('),
    source.indexOf('public ImageView getTimeItem()'));
const request = source.slice(source.indexOf('private void setTypingAnimation('),
    source.indexOf('private void applyTypingAnimation()'));
assert(source.includes('setSubtitleTextSmooth(newSubtitle);'));
assert(smooth.includes('subtitleTransitionTypingType == targetTypingType'));
assert(smooth.includes('appliedTypingType == targetTypingType'));
assert(smooth.includes('final int targetTypingType = getSubtitleTypingType();'));
assert(smooth.includes('transitionGeneration != subtitleTransitionGeneration'));
assert(smooth.includes('applyTypingAnimation();\n                        subtitleTextView.setText(nextSubtitle);'));
assert(smooth.includes('subtitleTransitionRunning && subtitleFadingOut'));
assert(smooth.includes('final CharSequence nextSubtitle = subtitleTransitionTarget;'));
assert(!request.includes('setLeftDrawable('));
assert(!request.includes('statusDrawables[type].start()'));
assert(source.includes('protected boolean useAnimatedSubtitle() {\n        return false;'));
assert(source.includes('clearLargerTextCopies();\n        subtitleTransitionGeneration++;\n        subtitleTransitionRunning = false;'));
const connectionStart = source.indexOf('private void updateCurrentConnectionState()');
const connection = source.slice(connectionStart,
    source.indexOf('public void onInitializeAccessibilityNodeInfo', connectionStart));
assert(!connection.includes('subtitleTextView.setText('));
assert(connection.includes('setSubtitleTextSmooth(restoredSubtitle);'));
assert(connection.includes('setSubtitleTextSmooth(title);'));
assert(connection.includes('subtitleTransitionRunning ? subtitleTransitionTarget : subtitleTextView.getText()'));
const typeStart = source.indexOf('private int getSubtitleTypingType()');
const typeEnd = source.indexOf('private void applyTypingAnimation()', typeStart);
const method = source.slice(typeStart, typeEnd).replaceAll(
    'app.nimarkogram.messenger.NimarkoConfig.hideActionBarStatus', 'hidden');
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-subtitle-'));
try {
    fs.writeFileSync(path.join(tmp, 'SubtitleTest.java'), `class SubtitleTest {
      CharSequence lastSubtitle; boolean hidden; int requestedTypingType;
      ${method}
      public static void main(String[] args) {
        SubtitleTest s = new SubtitleTest();
        for(int type=-1;type<=6;type++) {
          s.requestedTypingType=type;s.lastSubtitle=null;s.hidden=false;
          if(s.getSubtitleTypingType()!=type)throw new AssertionError("normal status");
          s.lastSubtitle="typing";
          if(s.getSubtitleTypingType()!=-1)throw new AssertionError("connecting must hide dots");
          s.requestedTypingType=-1;s.lastSubtitle=null;
          if(s.getSubtitleTypingType()!=-1)throw new AssertionError("typing stopped while offline");
          s.requestedTypingType=type;s.hidden=true;
          if(s.getSubtitleTypingType()!=-1)throw new AssertionError("hidden status");
        }
      }
    }`);
    cp.execFileSync('javac', ['SubtitleTest.java'], {cwd:tmp});
    cp.execFileSync('java', ['SubtitleTest'], {cwd:tmp});
} finally {
    fs.rmSync(tmp, {recursive:true, force:true});
}
console.log('PASS: source guards for shared subtitle path, deferred typing icon, repeated targets and detached animation cancellation');
