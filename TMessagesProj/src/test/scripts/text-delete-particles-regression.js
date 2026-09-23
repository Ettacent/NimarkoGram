// Keep the existing entry point; execute current production edit bookkeeping.
const path = require('node:path');
const cp = require('node:child_process');
cp.execFileSync('python3', ['-B', '-m', 'unittest', 'test_text_animation_current', '-v'], {
  cwd: path.resolve(__dirname, '../python'), stdio: 'inherit',
});
