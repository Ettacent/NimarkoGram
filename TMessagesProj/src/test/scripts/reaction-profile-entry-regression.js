// Run with: node TMessagesProj/src/test/scripts/reaction-profile-entry-regression.js
// Executes extracted entry logic with stubs; no Android build or rendering claims.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const source = fs.readFileSync(path.resolve(__dirname,
    '../../main/java/org/telegram/ui/ChatActivity.java'), 'utf8');

function body(text, marker) {
    const start = text.indexOf(marker);
    assert(start >= 0, `Missing ${marker}`);
    const brace = text.indexOf('{', start);
    const tokens = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|[{}]/g;
    tokens.lastIndex = brace;
    let depth = 0;
    for (let token; (token = tokens.exec(text));) {
        if (token[0] === '{') depth++;
        if (token[0] === '}' && --depth === 0) {
            return text.slice(brace + 1, token.index);
        }
    }
    throw new Error(`Unclosed ${marker}`);
}

function verify(text) {
    const helper = body(text,
        'private void openReactionProfile(long userId, MessageObject message, boolean reportReaction)');
    assert.doesNotMatch(helper, /closeMenu\(|setPlayProfileAnimation\(|runOnUIThread\(|\.post\(/,
        'Keep entry immediate and preserve the default profile animation mode');
    const enter = new Function('userId', 'message', 'reportReaction', 'currentAccount',
        'dialog_id', 'Bundle', 'ProfileActivity', 'dismissReactionUiForNavigation',
        'presentFragment', helper
            .replace(/\bBundle args =/g, 'const args =')
            .replace(/\bProfileActivity fragment =/g, 'const fragment ='));

    const routes = [...text.matchAll(/\.setOnProfileSelectedListener\(/g)].map(match =>
        body(text.slice(match.index), '.setOnProfileSelectedListener('));
    assert.equal(routes.length, 3, 'Cover every reaction profile click route');
    const messageNames = ['primaryMessage', 'primaryMessage', 'messageObject'];
    routes.forEach((route, i) => {
        assert.match(route, new RegExp(`openReactionProfile\\(userId, ${messageNames[i]},`));
        assert.equal((route.match(/openReactionProfile\(/g) || []).length, 1);
        assert.doesNotMatch(route, /closeMenu\(|presentFragment\(|new ProfileActivity/,
            'Each route must have exactly one navigation/dismissal owner');
    });

    // The navigation helper must disable pausing before invoking the no-argument
    // override: the bubble popup does not own cleanup in dismiss(boolean).
    const dismiss = body(text, 'private void dismissReactionUiForNavigation()');
    assert.match(dismiss, /popup\.setPauseNotifications\(false\);/);
    assert.match(dismiss, /popup\.dismiss\(\);/);
    assert(dismiss.indexOf('popup.setPauseNotifications(false)') < dismiss.indexOf('popup.dismiss()'));
    assert.doesNotMatch(dismiss, /popup\.dismiss\(false\)/);

    const primaryMessage = {getId: () => 123};
    const messageObject = {getId: () => 456};
    const dialogId = -4294967381n;
    let cases = 0;
    function exercise(invoke, userId, account, message, report) {
        const events = [];
        let presented;
        class Bundle {
            constructor() { this.values = {}; }
            putBoolean(key, value) { this.values[key] = value; }
            putLong(key, value) { this.values[key] = value; }
            putInt(key, value) { this.values[key] = value; }
        }
        class ProfileActivity {
            constructor(args) {
                events.push('create');
                this.args = args.values;
                this.account = 9; // Deliberately differs from the owning chat.
            }
            setCurrentAccount(value) { this.account = value; events.push('account'); }
        }
        const open = (id, origin, shouldReport) => enter(id, origin, shouldReport,
            account, dialogId, Bundle, ProfileActivity,
            () => events.push('dismiss'),
            fragment => { events.push('present'); presented = fragment; return true; });
        invoke(open);
        if (userId === 0n) {
            assert.deepEqual(events, [], 'Invalid peer must neither dismiss nor navigate');
            assert.equal(presented, undefined);
        } else {
            assert.deepEqual(events, ['create', 'account', 'dismiss', 'present']);
            assert.equal(presented.account, account, 'Use the owning chat account');
            const expected = {from_reaction: true};
            expected[userId > 0n ? 'user_id' : 'chat_id'] = userId > 0n ? userId : -userId;
            if (report && message != null) {
                expected.report_reaction_message_id = message.getId();
                expected.report_reaction_from_dialog_id = dialogId;
            }
            assert.deepEqual(presented.args, expected, 'Preserve peer/report argument semantics');
        }
        cases++;
    }

    routes.forEach((route, index) => {
        const click = new Function('openReactionProfile', 'userId', 'messagePeerReaction',
            'primaryMessage', 'messageObject', route);
        for (const account of [0, 1, 3]) {
            for (const userId of [0n, 42n, -42n, 4294967303n, -4294967303n]) {
                for (const reaction of [null, {reaction: null}, {reaction: {}}]) {
                    const message = index === 2 ? messageObject : primaryMessage;
                    const report = index === 0 || reaction != null && reaction.reaction != null;
                    exercise(open => click(open, userId, reaction, primaryMessage, messageObject),
                        userId, account, message, report);
                }
            }
        }
    });
    for (const report of [false, true]) {
        exercise(open => open(42n, null, report), 42n, 1, null, report);
    }
    return cases;
}

const cases = verify(source);
// Ensure the checks reject the key regressions, not merely the current spelling.
const mutations = [
    ['entry flag', text => text.replace('args.putBoolean("from_reaction", true);',
        'args.putBoolean("from_reaction", false);')],
    ['account ownership', text => text.replace('fragment.setCurrentAccount(currentAccount);',
        'fragment.setCurrentAccount(9);')],
    ['popup ordering', text => text.replace(
        'dismissReactionUiForNavigation();\n        presentFragment(fragment);',
        'presentFragment(fragment);\n        dismissReactionUiForNavigation();')],
    ['tabbed reporting', text => text.replace('openReactionProfile(userId, primaryMessage, true);',
        'openReactionProfile(userId, primaryMessage, false);')],
];
for (const [name, mutate] of mutations) {
    const changed = mutate(source);
    assert.notEqual(changed, source, `Mutation did not apply: ${name}`);
    assert.throws(() => verify(changed), assert.AssertionError, name);
}
console.log(`PASS: ${cases} reaction-profile entry cases; 3 routes, peer/report args, accounts, zero-ID guard, popup ordering; 4 negative controls`);
