const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../../main');
const files = [
    'java/org/telegram/ui/DialogsActivity.java',
    'java/org/telegram/ui/Components/FilterTabsView.java',
    'java/app/nimarkogram/messenger/infocards/BaseInfoCard.java',
    'java/app/nimarkogram/messenger/infocards/InfoCardStripView.java',
    'java/app/nimarkogram/messenger/preferences/DebugPreferencesActivity.java',
    ...['values', 'values-ru', 'values-zh-rCN'].map(lang => `res/${lang}/strings_nimarko.xml`),
];
assert(!fs.existsSync(path.join(root, 'java/app/nimarkogram/messenger/diagnostics/FolderMotionDebug.java')));
for (const file of files) {
    const source = fs.readFileSync(path.join(root, file), 'utf8');
    assert.doesNotMatch(source, /FolderMotionDebug|NM_FolderDebug|recordFolderGeometry|traceFolderState|folderGeometry|folder(?:Draw|Update|Measure|Switch)Start/, file);
}
const notifications = fs.readFileSync(path.join(root, 'java/app/nimarkogram/messenger/notifications/NimarkoInAppNotifications.java'), 'utf8');
assert.match(notifications, /bindAvatar\(avatar, account, dialogId, avatarHeading, preview, sample\)/);
assert.match(notifications, /avatar\.setImageDrawable\(placeholder\)/);
assert.doesNotMatch(notifications, /R\.drawable\.(?:msg_send|msg_notifications)\b/);
assert.match(notifications, /if \(closing\) setAlpha\(fromAlpha \* \(1f - progress\)\)/);
assert.doesNotMatch(notifications, /NotificationColorTrace|tracePreview|traceListMotion/);
assert.match(notifications, /next\.animate\(\)[^;]*setDuration\(360\)/);
assert.match(fs.readFileSync(path.join(root, 'res/values-ru/strings_nimarko.xml'), 'utf8'), /NM_InAppNotificationsSample">Здесь появятся новые сообщения<\/string>/);
console.log('Diagnostic removal and notification presentation checks passed');
