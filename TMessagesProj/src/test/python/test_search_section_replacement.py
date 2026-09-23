"""Structural guards for the Recent -> results transition (device video still required)."""
from pathlib import Path
import unittest
from test_search_fallback_entrance import block

UI = Path(__file__).resolve().parents[2] / "main/java/org/telegram/ui"


class SearchSectionReplacementTests(unittest.TestCase):
    def test_recent_and_results_do_not_move_shared_peers_across_modes(self):
        source = (UI / "Components/SearchViewPager.java").read_text()
        snapshot = block(source, "private static ArrayList<SearchRow> snapshotSearchRows(")
        self.assertIn('adapter.isSearchWas() ? "results:" : "recent:"', snapshot)
        self.assertIn('String baseKey = presentation + type + ":" + identity', snapshot)
        # Header identity is derived from the already namespaced result key.
        self.assertIn('"section-for:" + rows.get(firstResult).key', snapshot)

    def test_section_batch_does_not_change_normal_result_updates(self):
        source = (UI / "Components/SearchViewPager.java").read_text()
        method = block(source, "public void runPendingAnimations()")
        self.assertIn("hasSectionRows(mPendingRemovals)", method)
        self.assertIn("!mPendingRemovals.isEmpty() && hasSectionRows(mPendingAdditions)", method)
        self.assertIn("setRemoveDuration(110)", method)
        self.assertIn("finally", method)
        self.assertIn("setRemoveDuration(removeDuration)", method)
        self.assertIn("setDelayAnimations(false)", method)
        delay = block(source, "protected long getAddAnimationDelay(")
        self.assertIn("replacingSearchSections ? removeDuration", delay)

    def test_avatars_use_ready_frame_policy_only_in_search(self):
        source = (UI / "Adapters/DialogsSearchAdapter.java").read_text()
        create = block(source.split("private EmptyLayout messagesEmptyLayout;", 1)[1],
                       "public RecyclerView.ViewHolder onCreateViewHolder(")
        self.assertIn("view instanceof ProfileSearchCell", create)
        self.assertIn("avatarImage.setCrossfadeOnReady(true)", create)
        self.assertNotIn("setForceCrossfade", create)


if __name__ == "__main__":
    unittest.main()
