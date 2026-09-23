// Run with: node TMessagesProj/src/test/scripts/profile-emoji-status-callsite-regression.js
// Source-contract/guard tests, not Android rendering tests. No build or dependencies required.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const root = path.resolve(__dirname, '../../main/java/org/telegram/ui');
const user = fs.readFileSync(path.join(root, 'Cells/UserCell.java'), 'utf8');
const search = fs.readFileSync(path.join(root, 'Cells/ProfileSearchCell.java'), 'utf8');
const profile = fs.readFileSync(path.join(root, 'ProfileActivity.java'), 'utf8');
const header = fs.readFileSync(path.join(root, 'Components/ChatAvatarContainer.java'), 'utf8');
const message = fs.readFileSync(path.join(root, 'Cells/ChatMessageCell.java'), 'utf8');
let cases = 0;

function capture(source, regex) {
    const match = source.match(regex);
    assert(match, `Missing source contract: ${regex}`);
    return match[1];
}

function block(source, marker) {
    const start = source.indexOf(marker);
    assert(start >= 0, `Missing block: ${marker}`);
    const brace = source.indexOf('{', start);
    let depth = 1, end = brace + 1;
    for (; depth && end < source.length; end++) {
        if (source[end] === '{') depth++;
        else if (source[end] === '}') depth--;
    }
    assert.equal(depth, 0);
    return source.slice(start, end);
}

function flags(bits, count) {
    return Array.from({length: count}, (_, i) => !!(bits & (1 << i)));
}

// Execute the Java guard expressions themselves; do not duplicate their implementation.
const userGuard = new Function('isAttachedToWindow', 'emojiStatusUserId', 'currentUser',
    'emojiStatusAccount', 'currentAccount', 'nameTextView', 'emojiStatus',
    'return ' + capture(user, /final boolean animated = (isAttachedToWindow\(\)[\s\S]*?);/));
for (let bits = 0; bits < 16; bits++) {
    const [attached, samePeer, sameAccount, visible] = flags(bits, 4);
    const drawable = {};
    assert.equal(userGuard(() => attached, samePeer ? 42 : 41, {id: 42}, sameAccount ? 1 : 0,
        1, {getRightDrawable: () => visible ? drawable : null}, drawable),
    attached && samePeer && sameAccount && visible);
    cases++;
}
assert.match(user, /boolean continueUpdate = currentUser != null && \(mask & MessagesController.UPDATE_MASK_EMOJI_STATUS\) != 0/);
assert.match(user, /this.currentAccount = currentAccount;/);
assert.match(user, /emojiStatus.setCurrentAccount\(currentAccount\);\s*emojiStatus.set\(DialogObject.getEmojiStatusDocumentId\(currentUser.emoji_status\), animated\)/);
assert.match(user, /emojiStatus.detach\(\);\s*emojiStatus.resetAnimation\(\);\s*emojiStatusUserId = 0;/);

const searchGuard = new Function('animated', 'statusBound', 'isAttachedToWindow',
    'return ' + capture(search, /animated = (animated && statusBound && isAttachedToWindow\(\));/));
for (let bits = 0; bits < 8; bits++) {
    const [requested, bound, attached] = flags(bits, 3);
    assert.equal(searchGuard(requested, bound, () => attached), requested && bound && attached);
    cases++;
}
const TLRPC = {
    User: class { constructor(id) { this.id = id; } },
    Chat: class { constructor(id) { this.id = id; } }
};
const samePeer = new Function('TLRPC', 'object', 'user', 'chat', 'return ' +
    capture(search, /final boolean samePeer = ([\s\S]*?);/).replace(/\(TLRPC\.(User|Chat)\) /g, ''));
for (const Type of [TLRPC.User, TLRPC.Chat]) {
    for (const OldType of [TLRPC.User, TLRPC.Chat]) {
        for (const sameId of [false, true]) {
            const old = new OldType(42), next = new Type(sameId ? 42 : 43);
            assert.equal(samePeer(TLRPC, next, old instanceof TLRPC.User ? old : null,
                old instanceof TLRPC.Chat ? old : null), Type === OldType && sameId);
            cases++;
        }
    }
}
assert.equal(samePeer(TLRPC, null, new TLRPC.User(42), null), false);
cases++;
assert.match(search, /if \(!samePeer \|\| !sameEncryptedChat \|\| savedMessages != saved\)/);
assert.match(search, /statusDrawable.set\(\(Drawable\) null, false\);\s*statusDrawable.resetAnimation\(\)/);
assert.match(search, /updateStatus\(drawCheck, null, chat, !drawCheck\)/);
assert.match(search, /updateStatus\(drawCheck, user, null, !drawCheck\)/);
assert.match(search, /UPDATE_MASK_EMOJI_STATUS[^\n]+&& \(user != null \|\| chat != null\)\) \{\s*\/\/[^\n]+\s*continueUpdate = true;/);
assert.match(search, /statusDrawable.setCurrentAccount\(currentAccount\)/);

const profileGuard = new Function('animated', 'fragmentOpened', 'fragmentViewAttached',
    'openAnimationInProgress', 'transitionAnimationInProress', 'emojiStatusDrawable', 'nameTextView', 'a',
    'return ' + capture(profile, /animated = (animated && fragmentOpened[\s\S]*?);/));
for (let bits = 0; bits < 128; bits++) {
    const [requested, opened, attached, opening, transition, exists, visible] = flags(bits, 7);
    const drawable = exists ? {} : null;
    assert.equal(profileGuard(requested, opened, attached, opening, transition, [drawable],
        [{getRightDrawable: () => visible ? drawable : {}}], 0),
    requested && opened && attached && !opening && !transition && exists && visible);
    cases++;
}
assert.equal([...profile.matchAll(/getEmojiStatusDrawable\((?:user|chat)\.emoji_status, (?:true|false), true, a\)/g)].length, 4);
assert.match(profile, /emojiStatusDrawable\[a\].setCurrentAccount\(currentAccount\)/);
assert(!block(profile, 'private Drawable getEmojiStatusDrawable(').includes('resetAnimation()'),
    'A profile metadata refresh must not force an unchanged in-flight status to its final frame');
const premiumStar = block(profile, 'private Drawable getPremiumCrossfadeDrawable(int a)');
assert.match(premiumStar, /R.drawable.msg_premium_liststar/);
assert(!premiumStar.includes('SwapAnimatedEmojiDrawable'));
assert.equal([...profile.matchAll(/setRightDrawable\(getPremiumCrossfadeDrawable\(a\)\)/g)].length, 2,
    'Both user-profile premium stars must remain direct, not 24dp status wrappers');
assert.match(user, /WrapSizeDrawable\(premiumDrawable, dp\(14\), dp\(14\)\)/);

// Cache updates mutate the latest object, but the row may still hold a replaced instance.
// Translate only local declarations in the actual refresh blocks to execute against a fake cache.
const maskGuard = 'if ((mask & MessagesController.UPDATE_MASK_EMOJI_STATUS) != 0)';
const userUpdate = block(user, 'public void update(int mask)');
const searchUpdate = block(search, 'public void update(int mask)');
const javaLocalsToJs = source => source.replace(/TLRPC\.(?:User|Chat) (updatedUser|updatedChat) =/g, 'const $1 =');
const refreshUser = new Function('mask', 'MessagesController', 'currentAccount', 'currentUser', 'currentObject',
    javaLocalsToJs(block(userUpdate, maskGuard)) + '; return {currentUser, currentObject};');
const refreshSearch = new Function('mask', 'MessagesController', 'currentAccount', 'user', 'chat',
    javaLocalsToJs(block(searchUpdate, maskGuard)) + '; return {user, chat};');
assert(userUpdate.indexOf(maskGuard) < userUpdate.indexOf('if (currentUser.photo != null)'),
    'Refresh before reading the user photo/status');
assert(searchUpdate.indexOf(maskGuard) < searchUpdate.indexOf('avatarDrawable.setInfo'),
    'Refresh before reading the search peer');

const EMOJI = 524288, AVATAR = 2;
for (const account of [0, 1]) {
    for (const mask of [0, AVATAR, EMOJI, EMOJI | AVATAR]) {
        for (const cacheState of ['missing', 'same', 'replaced']) {
            for (const kind of ['user', 'chat']) {
                const old = {id: 42, emoji_status: 'old'};
                const cached = cacheState === 'missing' ? null : cacheState === 'same' ? old : {id: 42, emoji_status: 'new'};
                const calls = [];
                const cache = {
                    UPDATE_MASK_EMOJI_STATUS: EMOJI,
                    getInstance(requestedAccount) {
                        assert.equal(requestedAccount, account);
                        return {
                            getUser(id) { calls.push(['user', id]); return cached; },
                            getChat(id) { calls.push(['chat', id]); return cached; }
                        };
                    }
                };
                const expected = (mask & EMOJI) && cached != null ? cached : old;
                if (kind === 'user') {
                    const result = refreshUser(mask, cache, account, old, old);
                    assert.equal(result.currentUser, expected);
                    assert.equal(result.currentObject, expected, 'Persist the replacement for future updates');
                    assert.deepEqual(calls, mask & EMOJI ? [['user', 42]] : []);
                    calls.length = 0;
                    cases++;
                }
                const result = refreshSearch(mask, cache, account, kind === 'user' ? old : null, kind === 'chat' ? old : null);
                assert.equal(result[kind], expected);
                assert.equal(result[kind === 'user' ? 'chat' : 'user'], null);
                assert.deepEqual(calls, mask & EMOJI ? [[kind, 42]] : []);
                cases++;
            }
        }
    }
}
// Statistics/settings/search headers legitimately have no owning fragment.
const headerAccount = new Function('baseFragment', 'currentAccount', 'return ' + capture(header,
    /emojiStatusDrawable\.setCurrentAccount\(([^;]+)\);/));
for (const fallback of [0, 1, 3]) {
    assert.equal(headerAccount(null, fallback), fallback);
    assert.equal(headerAccount({getCurrentAccount: () => 2}, fallback), 2);
    cases += 2;
}
const messageGuard = new Function('attachedToWindow', 'nameStatusBoundAccount', 'currentAccount',
    'nameStatusBoundDialog', 'nameStatusBoundMessage', 'messageObject', 'return ' + capture(message,
    /final boolean animateNameStatus = ([\s\S]*?);/));
for (let bits = 0; bits < 16; bits++) {
    const [attached, account, dialog, id] = flags(bits, 4);
    assert.equal(messageGuard(attached, account ? 1 : 0, 1, dialog ? 42 : 41, id ? 5 : 4,
        {getDialogId: () => 42, getId: () => 5}), attached && account && dialog && id);
    cases++;
}
console.log(`PASS: ${cases} emoji-status guard/binding/cache cases; mask routing, recycling, accounts, profile opening guards and direct premium stars`);
