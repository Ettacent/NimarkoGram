const fs = require('node:fs'), path = require('node:path'), os = require('node:os');
const cp = require('node:child_process'), assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/java/org/telegram/messenger/NotificationsController.java'), 'utf8');
function extract(signature) {
    const start = source.indexOf(signature);
    assert(start >= 0, signature);
    let depth = 0;
    for (let i = source.indexOf('{', start); i < source.length; i++) {
        if (source[i] === '{') depth++;
        if (source[i] === '}' && --depth === 0) return source.slice(start, i + 1);
    }
    throw new Error(signature);
}
const java = `public class ForegroundDeliveryTest {
 static int checks;
 static void check(boolean b, String label) { checks++; if (!b) throw new AssertionError(label); }
 static class NimarkoInAppNotifications { static boolean available; static boolean isAvailable() { return available; } }
 static class SystemClock { static long now; static long elapsedRealtime() { return now; } }
 static class BuildVars { static final boolean LOGS_ENABLED = false; }
 static class FileLog { static void d(String s) {} static void e(Exception e) {} }
 static class Preferences { boolean popup = true; boolean getBoolean(String key, boolean fallback) { return popup; } }
 static class Account { Preferences p = new Preferences(); Preferences getNotificationsSettings() { return p; } }
 static class Queue {
  long due; Runnable task; int posts;
  void cancelRunnable(Runnable r) { if (task == r) task = null; }
  void postRunnable(Runnable r, long delay) { task = r; due = SystemClock.now + delay; posts++; }
 }
 static class Lock { int acquires; void acquire(long n) { acquires++; } }
 Account account = new Account(); Account getAccountInstance() { return account; }
 Queue notificationsQueue = new Queue(); Lock notificationDelayWakelock = new Lock();
 boolean notifyCheck; int delivered;
 Runnable notificationDelayRunnable = () -> delivered++;
 void showOrUpdateNotification(boolean notify) { delivered++; }
 ${extract('private void scheduleNotificationDelay(')}
 public static void main(String[] args) {
  ForegroundDeliveryTest c = new ForegroundDeliveryTest();
  NimarkoInAppNotifications.available = true;
  SystemClock.now = 1000; c.scheduleNotificationDelay(false);
  check(c.delivered == 1, "foreground delivers without an artificial delay");
  check(c.notificationsQueue.posts == 0 && c.notificationDelayWakelock.acquires == 0, "no foreground timer or wakelock");
  SystemClock.now = 1050; c.scheduleNotificationDelay(true);
  check(c.delivered == 2, "another-device online state does not delay foreground banner");
  SystemClock.now = 1099; c.scheduleNotificationDelay(false);
  check(c.delivered == 3 && c.notificationsQueue.task == null, "no pending duplicate during bursts");
  SystemClock.now = 1120; c.scheduleNotificationDelay(false);
  check(c.delivered == 4, "next batch also delivers immediately");
  NimarkoInAppNotifications.available = false; c.scheduleNotificationDelay(false);
  check(c.notificationsQueue.due == 2120, "background restores native debounce");
  c.scheduleNotificationDelay(true);
  check(c.notificationsQueue.due == 4120, "background other-device policy preserved");
  NimarkoInAppNotifications.available = true; c.scheduleNotificationDelay(false);
  check(c.delivered == 5 && c.notificationsQueue.task == null, "foreground cancels an old background timer");
  NimarkoInAppNotifications.available = true; c.account.p.popup = false; c.scheduleNotificationDelay(false);
  check(c.notificationsQueue.due == 2120, "disabled in-app feature preserves native timing");
  System.out.println("PASS: " + checks + " foreground scheduling assertions");
 }
}`;
assert(!source.includes('inAppNotificationDeadline'), 'no obsolete foreground debounce state');
const update = extract('private void showOrUpdateNotification(boolean notifyAboutLast, Boolean inAppHandled)');
assert.equal((update.match(/offerInAppNotification\(/g) || []).length, 1, 'one offer, not before and after OS preparation');
assert(update.indexOf('offerInAppNotification(') < update.indexOf('String customSoundPath;'));
assert.match(update, /notifyDisabled \|= Boolean.TRUE.equals\(inAppHandled\) && NimarkoInAppNotifications.isAvailable\(\);/);
const extra = extract('private void showExtraNotifications(');
assert(extra.indexOf('notificationBuilder.setSilent(isSilent)') < extra.indexOf('notificationBuilder.build()'));
assert.match(extra, /new NotificationCompat.Builder\(ApplicationLoader.applicationContext\)\s*\.setSilent\(isSilent\)/);
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-foreground-delivery-'));
try {
    fs.writeFileSync(path.join(dir, 'ForegroundDeliveryTest.java'), java);
    cp.execFileSync('javac', ['ForegroundDeliveryTest.java'], {cwd: dir});
    process.stdout.write(cp.execFileSync('java', ['ForegroundDeliveryTest'], {cwd: dir}));
} finally { fs.rmSync(dir, {recursive: true, force: true}); }
