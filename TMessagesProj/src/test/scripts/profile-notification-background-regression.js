const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const notifications = 'app/nimarkogram/messenger/notifications/';
const profile = read('org/telegram/ui/ProfileActivity.java');
assert.doesNotMatch(profile, /drawNotificationGap|drawNotificationBackdrop|drawNotificationVacatedArea/);
const start = profile.indexOf('class TopView');
assert(start >= 0);
let end = profile.indexOf('{', start), depth = 1;
while (depth && ++end < profile.length) {
    if (profile[end] === '{') depth++;
    if (profile[end] === '}') depth--;
}
assert.equal(depth, 0);
assert.doesNotMatch(profile.slice(start, end + 1), /profileNotificationHeight/);
assert.match(read(notifications + 'NimarkoInAppNotifications.java'), /setClipToOutline\(true\)/);
assert.match(read(notifications + 'NotificationGlassSurface.java'), /outline\.setRoundRect\(0, 0, v\.getWidth\(\), v\.getHeight\(\), AndroidUtilities\.dp\(18\)\)/);
assert(!fs.existsSync(path.join(root, notifications + 'ProfileNotificationTrace.java')));
for (const file of ['org/telegram/ui/ProfileActivity.java', ...['NotificationGlassSurface', 'NotificationInlinePanel', 'NimarkoInAppNotifications'].map(n => notifications + n + '.java')]) {
    assert.doesNotMatch(read(file), /ProfileNotificationTrace|traceProfilePanel|traceProfileBanner|traceProfileNotification|Nimarko-profile-notification-/);
}
console.log('PASS: no rectangular backing or header extension; native rounded glass retained; diagnostics absent (source contracts).');
