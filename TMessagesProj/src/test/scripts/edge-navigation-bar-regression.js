const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../../main/java');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const base = read('org/telegram/ui/ActionBar/BaseFragment.java');
const layout = read('org/telegram/ui/ActionBar/ActionBarLayout.java');
const chat = read('org/telegram/ui/ChatActivity.java');
const chatEdit = read('org/telegram/ui/ChatEditActivity.java');

const method = base.slice(base.indexOf('public boolean drawEdgeNavigationBar()'), base.indexOf('public WindowInsetsCompat onInsetsInternal'));
if (!/return isSupportEdgeToEdge\(\);/.test(method)) throw new Error('BaseFragment must preserve Telegram edge-to-edge navigation behavior');

const guardedListeners = [...layout.matchAll(/fragmentView != null && ([^\n]+)\) \{[\s\S]{0,500}?setOnApplyWindowInsetsListener/g)];
if (guardedListeners.length < 6) throw new Error(`expected all fragment attach paths, got ${guardedListeners.length}`);
for (const match of guardedListeners) {
    if (!/isSupportEdgeToEdge\(\)/.test(match[1])) throw new Error('insets listener is not gated by edge-to-edge support');
    if (!/drawEdgeNavigationBar\(\)/.test(match[1])) throw new Error('fragment-owned inset handlers must not be replaced by ActionBarLayout');
}

for (const file of ['org/telegram/ui/SettingsActivity.java', 'org/telegram/ui/DialogsActivity.java', 'org/telegram/ui/ProfileActivity.java', 'org/telegram/ui/ChatActivity.java', 'org/telegram/ui/ChatEditActivity.java']) {
    if (!/boolean drawEdgeNavigationBar\(\)[\s\S]{0,100}?return false;/.test(read(file))) {
        throw new Error(`${file} no longer documents the intended floating gesture-indicator behavior`);
    }
}
if (!/setOnApplyWindowInsetsListener\(fragmentView, this::onApplyWindowInsets\)/.test(chat)) {
    throw new Error('ChatActivity must retain its own animated inset pipeline');
}
if (/setOnApplyWindowInsetsListener\(fragmentView, [^\n]*onInsetsInternal/.test(chat)) {
    throw new Error('ChatActivity must not receive the generic BaseFragment inset handler');
}
if (!/setOnApplyWindowInsetsListener\(fragmentView, this::onInsetsInternal\)/.test(chatEdit) ||
    !/requestApplyInsets\(fragmentView\)/.test(chatEdit)) {
    throw new Error('ChatEditActivity must reserve system insets itself when the opaque gradient is disabled');
}
console.log('PASS: chat keeps its animated inset pipeline and channel settings own their transparent navigation inset');
