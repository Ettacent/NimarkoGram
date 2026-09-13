const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java');
const read = name => fs.readFileSync(path.join(root, name), 'utf8');
const base = 'app/nimarkogram/messenger/notifications/';
assert(!fs.existsSync(path.join(root, base + 'NotificationColorTrace.java')));
for (const file of [base + 'NotificationGlassSurface.java', base + 'NimarkoInAppNotifications.java',
    'org/telegram/ui/DialogsActivity.java', 'org/telegram/ui/Components/AnimatedLinearLayout.java',
    'org/telegram/ui/Components/blur3/RenderNodeWithHash.java',
    'org/telegram/ui/Components/blur3/drawable/BlurredBackgroundDrawableRenderNode.java',
    'org/telegram/ui/Components/blur3/source/BlurredBackgroundSourceRenderNode.java']) {
    assert.doesNotMatch(read(file), /NotificationColorTrace|recordColorFrame|traceNotificationList|tracePanelMotion|colorTracing/, file);
}
const surface = read(base + 'NotificationGlassSurface.java');
assert.match(surface, /incoming.rendered && incoming.isReady\(\)/);
assert.match(surface, /incoming.rendered = false/);
assert.match(surface, /incoming.glass.draw\(canvas\);\s*incoming.rendered = true/);
console.log('PASS: notification diagnostics removed; glass readiness retained');
