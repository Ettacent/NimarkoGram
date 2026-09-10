const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ui = path.resolve(__dirname, '../../main/java/org/telegram/ui');
const poll = fs.readFileSync(path.join(ui, 'PollItemMenu.java'), 'utf8');
for (const removed of ['reactionsView', 'ReactionsContainerLayout', 'isReactionsAvailable',
    'chatActivity.selectReaction(', 'getReactionsWindow()', 'getTotalWidth()']) {
    assert(!poll.includes(removed), removed);
}
for (const preserved of ['tabsView.addTab(0,', 'tabsView.addTab(1,',
    'setupMessageOptions(', 'PollMenuTabOption', 'PollMenuTabPoll',
    'menuContainer.addView(messageOptionsView,', 'menuContainer.addView(taskOptionsView,',
    'viewPager.cancelTouches();', 'isReactionsViewAvailable', 'MessageSeenView']) {
    assert(poll.includes(preserved), preserved);
}
const chat = fs.readFileSync(path.join(ui, 'ChatActivity.java'), 'utf8');
assert(chat.includes('scrimPopupContainerLayout.setReactionsLayout(reactionsLayout);'));
const todo = fs.readFileSync(path.join(ui, 'TodoItemMenu.java'), 'utf8');
assert(todo.includes('private ReactionsContainerLayout reactionsView;'));
console.log('PASS: poll picker creation, sizing, positioning and dismissal removed; both tabs, actions, readers and other menus preserved (source checks only).');
