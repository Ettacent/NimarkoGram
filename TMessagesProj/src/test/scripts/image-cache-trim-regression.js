const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/java/org/telegram/messenger/ApplicationLoader.java'), 'utf8');
const method = source.match(/public void onTrimMemory\(int level\) \{([\s\S]*?)\n    \}/)[1];
const constants = {TRIM_MEMORY_RUNNING_LOW:10, TRIM_MEMORY_UI_HIDDEN:20, TRIM_MEMORY_BACKGROUND:40};
function run(body, level) {
 let cleared=0;
 new Function('level','ComponentCallbacks2','ImageLoader',body.replace('super.onTrimMemory(level);',''))
  (level,constants,{clearMemoryIfInitialized(){cleared++;}});
 return cleared;
}
for(let level=0;level<=100;level++) {
 assert.equal(run(method,level),level>=40||(level>=10&&level<20)?1:0,`trim level ${level}`);
}
for(let n=0;n<10;n++) assert.equal(run(method,20),0,'ordinary background/resume must retain bounded image caches');
assert.equal(run('if(level>=ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)ImageLoader.clearMemoryIfInitialized();',20),1);
console.log('PASS: lifecycle trim preserves images; low/critical/background memory pressure still clears caches');
