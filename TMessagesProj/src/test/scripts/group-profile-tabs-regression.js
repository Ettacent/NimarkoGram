const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../../main/java');
const profile=fs.readFileSync(path.join(root,'org/telegram/ui/ProfileActivity.java'),'utf8');
const shared=fs.readFileSync(path.join(root,'org/telegram/ui/Components/SharedMediaLayout.java'),'utf8');
const controller=fs.readFileSync(path.join(root,'org/telegram/messenger/MediaDataController.java'),'utf8');
assert(!profile.includes('usersForceShowingIn'));
assert.match(profile, /final boolean openGroupUsers = shouldShowGroupUsersTab\(\);/);
assert.match(profile, /else if \(openGroupUsers\) \{\s*initialTab = SharedMediaLayout\.TAB_GROUPUSERS;/);
assert.match(profile, /int count = getKnownParticipantsCount\(\);\s*return count == 0 \|\| count > 5;/);
assert.match(profile, /chatInfo = getMessagesController\(\)\.getChatFull\(chatId\);[\s\S]*?sortedUsers = new ArrayList<>\(\);[\s\S]*?getChannelParticipants\(true\);/);
assert.match(profile, /boolean hasMedia = openGifts;\s*if \(shouldShowGroupUsersTab\(\)\) \{\s*hasMedia = true;/);
assert.match(shared, /initialTab == TAB_COMMON_GROUPS \|\| initialTab == TAB_GROUPUSERS/);
assert.match(shared, /public void setGroupUsersTabVisible\(boolean visible, int expectedCount\)/);
const receive=shared.slice(shared.indexOf('if (id == NotificationCenter.mediaCountsDidLoad)'),shared.indexOf('} else if (id == NotificationCenter.mediaCountDidLoad)'));
const merge=receive.slice(receive.indexOf('for (int a = 0; a < Math.min'),receive.indexOf('for (int a = 0; a < counts.length'));
const apply=new Function('counts','targetCounts',merge.replace('int a','let a'));
let known=[4,8,-1];apply([-1,-1,-1],known);assert.deepEqual(known,[4,8,-1]);
apply([0,12,3],known);assert.deepEqual(known,[0,12,3]);
assert(receive.indexOf('Boolean.FALSE.equals(args[3])')<receive.indexOf('int[] counts'));
assert.match(receive,/!mediaCountRetryPosted && mediaCountRetryCount < 2/);
assert.match(receive,/mediaCount\[a\] > 0/);
assert(!receive.includes('Math.max(mediaMergeCount[a], 0)'));
assert.match(shared,/destroyed = true;\s*AndroidUtilities.cancelRunOnUIThread\(mediaCountRetry\)/);
assert.match(shared,/if \(!destroyed\) loadMediaCounts\(\)/);
const request=controller.slice(controller.indexOf('public void getMediaCounts('),controller.indexOf('public void getMediaCount('));
assert(request.indexOf('if (!(response instanceof Vector))')<request.indexOf('counts[i] = 0;'));
assert.match(request,/countsFinal, false\)\);\s*return;/);
console.log('PASS: group-only member tabs, small groups/topics, late/unknown counts, authoritative zero and bounded lifetime-scoped retries');

const onlineStart = profile.indexOf('if (user != null && !user.bot', profile.indexOf('private void updateOnlineCount'));
const onlineCondition = profile.slice(onlineStart + 4, profile.indexOf(') {', onlineStart));
const isOnline = new Function('user', 'currentTime', 'getUserConfig', 'getMessagesController', 'return ' + onlineCondition);
const config = () => ({getClientUserId: () => 1});
const controllerFor = ids => () => ({onlinePrivacy: {containsKey: id => ids.includes(id)}});
for (const [user, temporary, expected] of [
    [null, [], false],
    [{id: 1, bot: false, status: null}, [], true],
    [{id: 2, bot: false, status: {expires: 101}}, [], true],
    [{id: 2, bot: false, status: {expires: 99}}, [], false],
    [{id: 2, bot: false, status: null}, [2], true],
    [{id: 2, bot: true, status: {expires: 101}}, [2], false],
]) assert.equal(isOnline(user, 100, config, controllerFor(temporary)), expected);

assert(!shared.includes('pendingInitialGroupUsersTab'));
assert.match(shared, /showGroupUsersTab = this\.initialTab == TAB_GROUPUSERS;/);
assert.match(shared, /if \(showGroupUsersTab \|\| chatUsersAdapter\.chatInfo != null\)/);
assert.match(profile, /onlineCount = Math\.max\(onlineCount, serverOnlineCount\);/);
assert.match(profile, /SystemClock\.elapsedRealtime\(\) - lastChannelParticipantsRequestTime < 1500/);
assert(!shared.includes('System.identityHashCode(chatInfo.participants)'));
assert.match(shared, /Long\.hashCode\(participant\.user_id\)/);
assert.match(profile, /fromTopics && fragmentView != null\) \{\s*fragmentView\.postOnAnimation\(profileTransitionStartRunnable\)/);
assert(!profile.includes('GroupProfileDebugLog'));
assert(!shared.includes('GroupProfileDebugLog'));
console.log('PASS: consistent online status, coalesced participant loads and smooth stable members tab');
