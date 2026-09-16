const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/java/org/telegram/ui/Components/ThanosEffect.java'), 'utf8');
const start = source.indexOf('        public void requestDraw() {');
const end = source.indexOf('        public void resize(', start);
assert(start >= 0 && end > start);
assert(source.includes('finally {\n                        drawPending.set(false);'));
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'delete-frame-queue-'));
const java = `
import java.util.concurrent.atomic.AtomicBoolean;
public class FrameQueue {
 final AtomicBoolean alive = new AtomicBoolean(true), drawPending = new AtomicBoolean();
 static final int DO_DRAW = 0;
 static class Handler {
  int messages; boolean accepting = true;
  int obtainMessage(int what) { return what; }
  boolean sendMessage(int what) { if (!accepting) return false; messages++; return true; }
 }
 Handler handler = new Handler();
 Handler getHandler() { return handler; }
 ${source.slice(start, end).replace('android.os.SystemClock.elapsedRealtime()', 'System.currentTimeMillis()')}
 static void check(boolean value) { if (!value) throw new AssertionError(); }
 public static void main(String[] args) {
  FrameQueue q = new FrameQueue();
  for (int i=0;i<10000;i++) q.requestDraw();
  check(q.handler.messages==1);
  q.drawPending.set(false); q.requestDraw(); check(q.handler.messages==2);
  q.drawPending.set(false); q.handler.accepting=false; q.requestDraw(); check(!q.drawPending.get());
  q.handler.accepting=true; q.requestDraw(); check(q.handler.messages==3);
  q.drawPending.set(false); q.alive.set(false); q.requestDraw(); check(q.handler.messages==3);
  q.alive.set(true); q.handler=null; q.requestDraw(); check(!q.drawPending.get());
  System.out.println("PASS: one pending/in-flight frame, resume, rejected dispatch, killed thread, missing handler");
 }
}`;
fs.writeFileSync(path.join(dir, 'FrameQueue.java'), java);
cp.execFileSync('javac', ['FrameQueue.java'], {cwd:dir, stdio:'inherit'});
cp.execFileSync('java', ['FrameQueue'], {cwd:dir, stdio:'inherit'});
