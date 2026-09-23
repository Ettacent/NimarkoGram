"""Source-level regressions for Settings and group/channel administration.

These checks intentionally avoid an Android build and exercise the scoped UI
contracts that can otherwise regress without a device test.
"""

from pathlib import Path
import unittest


JAVA = Path(__file__).resolve().parents[2] / "main" / "java" / "org" / "telegram" / "ui"


def source(name):
    return (JAVA / name).read_text(encoding="utf-8")


def section(text, start, end):
    begin = text.index(start)
    return text[begin:text.index(end, begin + len(start))]


class SettingsAdminControlsRegression(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.settings = source("SettingsActivity.java")
        cls.tabs = source("MainTabsActivity.java")
        cls.users = source("ChatUsersActivity.java")
        cls.rights = source("ChatRightsEditActivity.java")
        cls.edit = source("ChatEditActivity.java")

    def test_settings_first_open_does_not_create_search_until_requested(self):
        settings = self.settings
        lazy_search = section(settings, "private ProfileActivity.SearchAdapter ensureSearchAdapter(", "private ImageUpdater imageUpdater;")
        create_view = section(settings, "public View createView(Context context)", "public void onFragmentDestroy()")
        self.assertIn("search.loadFaqWebPage();", lazy_search)
        self.assertNotIn("search.loadFaqWebPage();", create_view)
        self.assertIn("setText(getVersionName());", create_view)
        self.assertIn("protected void onAttachedToWindow()", create_view)
        balances = section(settings, "if (getMessagesController().starsPurchaseAvailable())", "TLRPC.TL_attachMenuBots menuBots")
        self.assertEqual(2, balances.count(".getBalance()"))
        self.assertIn("StarsIntroActivity.formatStarsAmount(balance,", balances)
        self.assertIn("StarsIntroActivity.formatStarsAmount(tonBalance,", balances)
        self.assertIn("MediaDataController.getInstance(currentAccount).getAttachMenuBots()", settings)

    def test_tab_search_waits_for_navigation_completion(self):
        tabs = self.tabs
        open_search = section(tabs, "private void openSearchChats()", "private void openPendingChatsSearch()")
        settled = section(tabs, "protected void onViewPagerScrollEnd()", "protected void onViewPagerTabAnimationUpdate(")
        self.assertIn("pendingChatsSearch = true;", open_search)
        self.assertIn("viewPager.isPageTransitionRunning()", open_search)
        self.assertIn("openPendingChatsSearch();", settled)
        self.assertIn("viewPager.scrollToPosition(posChats());", settled)
        self.assertNotIn("runOnUIThread(openSearchChatsRunnable, 100)", tabs)
        self.assertIn("visibleFragment instanceof DialogsActivity", tabs)
        self.assertIn("pendingChatsSearch = false;", section(tabs, "public void onFragmentDestroy()", "public void onFactorChanged("))

    def test_participants_use_peer_fallback_and_stable_sort(self):
        users = self.users
        load = section(users, "private void loadChatParticipants(int offset, int count, boolean reset)", "private static long getParticipantPeerId(")
        sort = section(users, "private void sortUsers(ArrayList<TLObject> objects)", "public void onResume()")
        self.assertIn("long peerId = getParticipantPeerId(participant);", load)
        self.assertIn("peerId == 0 || selectType != SELECT_TYPE_MEMBERS && peerId == selfId", load)
        self.assertIn("map.get(peerId) != null", load)
        self.assertIn("boolean canSearch = ChatObject.isChannel(currentChat)", load)
        self.assertEqual(3, sort.count("return Long.compare(peer1, peer2);"))
        self.assertEqual(1, users.count("MessageObject.getPeerId(channelParticipant.peer)"))

    def test_removing_restrictions_removes_stale_list_entry(self):
        users = self.users
        self.assertIn("delegate.didRemoveParticipantFromList(user_id);", users)
        self.assertIn("removeParticipants(selectedPeerId);", users)
        self.assertIn("type == ChatRightsEditActivity.TYPE_BANNED && rights == 2", users)
        self.assertIn("type == ChatRightsEditActivity.TYPE_ADMIN && rights == 0", users)
        self.assertIn("if (rights == 2) {\n                                removeParticipants(peerId);", users)
        self.assertIn("if (isFinished || peerId == 0)", users)
        self.assertIn("info.kicked_count = Math.max(0, info.kicked_count - 1);", users)

    def test_channel_permission_group_toggle_matches_displayed_state(self):
        rights = self.rights
        for group, fields in (
            ("Messages", ("post_messages", "edit_messages", "delete_messages")),
            ("Stories", ("post_stories", "edit_stories", "delete_stories")),
        ):
            setter = section(rights, f"private void setChannel{group}Enabled(boolean enabled)", "private int getChannel" if group == "Messages" else "private boolean allDefaultMediaBanned()")
            for field in fields:
                self.assertIn(f"adminRights.{field} = enabled;", setter)
                self.assertNotIn(f"adminRights.{field} = !enabled;", setter)
            self.assertIn(f"setChannel{group}Enabled(enabled);", rights)
        self.assertGreaterEqual(rights.count("boolean enabled = !checkCell.isChecked();"), 2)

    def test_autotranslation_spinner_covers_request(self):
        toggle = section(self.edit, "final TLRPC.TL_channels_toggleAutotranslation req", "ConnectionsManager.RequestFlagInvokeAfter")
        send = toggle.index("getConnectionsManager().sendRequest(req")
        self.assertNotIn("progressDialog.dismiss();", toggle[:send])
        self.assertGreaterEqual(toggle[send:].count("progressDialog.dismiss();"), 2)
        self.assertIn("if (isFinished || loading[0] || getParentActivity() == null) return;", self.edit)


if __name__ == "__main__":
    unittest.main()
