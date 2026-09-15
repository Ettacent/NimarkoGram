const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java');
const source = fs.readFileSync(path.join(root, 'org/telegram/ui/ChatActivity.java'), 'utf8');
const renderer = fs.readFileSync(path.join(root, 'app/nimarkogram/messenger/ui/MessageMenuTelegramPlus.java'), 'utf8');
assert(renderer.includes('return ordinaryTap && NimarkoConfig.telegramPlusMessageMenu;'));
const predicate = source.match(/MessageMenuTelegramPlus.isEnabled\((!suggestEdit[\s\S]*?)\);/)[1];
const eligible = new Function('suggestEdit', 'ordinaryTap', 'message', 'MessageObject', 'return ' + predicate);
for (const enabled of [false,true]) for(const tap of [false,true])
 for(const music of [false,true]) for(const call of [false,true]) for(const edit of [false,true]) {
  const actual=enabled && eligible(edit,tap,{isMusic:()=>music,type:call?16:9},{TYPE_PHONE_CALL:16});
  assert.equal(actual,enabled&&!edit&&(tap||music||call));
 }
assert(source.includes('createMenu(cell, true, false, otherX, otherY, messageObject.isMusic(), false, false, true);'));
const callback=source.slice(source.indexOf('public void didPressOther(ChatMessageCell'),source.indexOf('public boolean canSaveRichDocument('));
assert(callback.includes('if (messageObject.type == MessageObject.TYPE_PHONE_CALL)'));
assert(callback.includes('VoIPHelper.startCall(') && callback.includes('VoIPHelper.joinConference('));
assert(source.indexOf('if (message == null)',source.indexOf('boolean ordinaryTap)'))<source.indexOf('final boolean telegramPlusMessageMenu ='));
console.log('PASS: file options tap, music/call menu entry, disabled setting and suggested edits; call button behavior retained');
