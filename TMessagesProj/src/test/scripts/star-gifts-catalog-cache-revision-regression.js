const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const source = fs.readFileSync(path.resolve(__dirname,
    '../../main/java/org/telegram/ui/Stars/StarsController.java'), 'utf8');
const read = source.slice(source.indexOf('    private void getStarGiftsCached('),
    source.indexOf('    private void saveStarGiftsCached('));
const start = read.indexOf('final android.content.SharedPreferences preferences');
const select = read.indexOf('cursor = db.queryFinalized(');
assert(start > read.indexOf('storage.getStorageQueue().postRunnable('));
assert(start > read.indexOf('try {'));
assert(select > start, 'revision checked before catalog read');
assert.match(read.slice(0, start), /int hash = 0;\s*long time = 0;/);
assert.match(read, /catch \(Exception e\) \{\s*FileLog.e\(e\);/);
assert.match(read, /whenDone.run\(result, finalHash, finalTime, users, chats\)/);
assert.match(source, /giftsHash = hash;/);
assert.match(source, /getStarGiftsRemote\(giftsHash,/);
const revision = Number(source.match(/STAR_GIFTS_CATALOG_CACHE_REVISION = (\d+);/)[1]);
// Execute the actual migration block with only its Java local declaration adapted.
const migrate = new Function('MessagesController', 'currentAccount', 'db',
    'STAR_GIFTS_CATALOG_CACHE_REVISION', read.slice(start, select)
        .replace('final android.content.SharedPreferences', 'const'));
const accounts = new Map();
function account(id) {
    if (!accounts.has(id)) accounts.set(id, {
        revision: 0, catalog: ['old'], owned: ['purchased'], profile: ['received'],
        deletes: 0, failDelete: false, failCommit: false, events: []
    });
    return accounts.get(id);
}
const controller = {
    getMainSettings(id) {
        const state = account(id);
        return {
            getInt(key, fallback) {
                assert.equal(key, 'starGiftsCatalogCacheRevision');
                assert.equal(fallback, 0);
                return state.revision;
            },
            edit() {
                return { putInt(key, value) {
                    assert.equal(key, 'starGiftsCatalogCacheRevision');
                    return { commit() {
                        state.events.push('mark');
                        assert.equal(state.catalog.length, 0, 'mark only after deletion');
                        if (state.failCommit) return false;
                        state.revision = value;
                        return true;
                    } };
                } };
            }
        };
    }
};
function run(id) {
    const state = account(id);
    const db = { executeFast(sql) {
        assert.equal(sql, 'DELETE FROM star_gifts2', 'only server catalog may be deleted');
        return { stepThis() {
            state.events.push('delete');
            if (state.failDelete) throw new Error('disk unavailable');
            state.catalog = [];
            state.deletes++;
            return { dispose() {} };
        } };
    } };
    migrate(controller, id, db, revision);
    assert.deepEqual(state.owned, ['purchased']);
    assert.deepEqual(state.profile, ['received']);
}
run(0);
assert.deepEqual(account(0).events, ['delete', 'mark']);
assert.equal(account(0).revision, revision);
account(0).catalog = ['server'];
run(0);
assert.equal(account(0).deletes, 1, 'one-time invalidation');
assert.deepEqual(account(0).catalog, ['server']);
run(1);
assert.equal(account(1).deletes, 1, 'independent account revision');
account(2).failDelete = true;
assert.throws(() => run(2), /disk unavailable/);
assert.equal(account(2).revision, 0);
assert.deepEqual(account(2).events, ['delete'], 'failed deletion never marks revision');
account(2).failDelete = false;
run(2);
assert.equal(account(2).revision, revision, 'retry succeeds');
account(3).failCommit = true;
run(3);
assert.equal(account(3).revision, 0);
account(3).failCommit = false;
run(3);
assert.equal(account(3).deletes, 2, 'unpersisted revision safely repeats invalidation');
account(4).revision = revision + 1;
run(4);
assert.equal(account(4).deletes, 0, 'newer revisions remain valid');
console.log('PASS: catalog revision ordering, account isolation, retries, one-time deletion, owned/profile preservation');
