"""Consumer regressions using verbatim production code and existing JVM fixtures.

Includes late-document presentation policy; pixel blending remains covered by
the shared-render owner's tests. Fixture adaptations are in-memory only.
"""
import unittest

import test_emoji_first_frame_fade as first_frame
import test_emoji_status_crossfade as status_crossfade

JAVA, method = first_frame.JAVA, first_frame.method


class ConsumerPresentationTests(unittest.TestCase):
    def late_document_harness(self):
        runner = first_frame.EmojiFirstFrameFadeTest()
        source = runner.source()
        emoji = (JAVA / "ui/Components/AnimatedEmojiDrawable.java").read_text()
        draw = "\n".join(method(emoji, signature) for signature in (
            "public void markMissingDocumentPresented()",
            "private void draw(Canvas canvas, boolean ownsLoadFade)",
            "public void draw(Canvas canvas, Rect drawableBounds, float alpha)",
            "public void draw(Canvas canvas, ImageReceiver.BackgroundThreadDrawHolder"))
        source = source.replace("class Drawable {", "class Drawable { Rect getBounds() { return new Rect(); }")
        source = source.replace("    private void createImageReceiver() {",
            "    float alpha = 1;\n" + draw + "\n    private void createImageReceiver() {")
        source = source.replace("    void invalidate() { invalidations++; }", """
    void setImageCoords(Rect r) {} void setAlpha(float a) { overrideAlpha = a; }
    void invalidate() { invalidations++; }
""")
        # Execute actual null-receiver draws, marker, factory, delivery, receiver
        # rendering and lifecycle resets. No parallel fixture edits on disk.
        # Document fetch and ImageLoader are represented by explicit cache delivery.
        source = source.replace("private void draw(Canvas canvas, boolean ownsLoadFade)",
                                "void draw(Canvas canvas, boolean ownsLoadFade)")
        start = source.index("    public static void main(String[] args) {")
        source = source[:start] + """
    public static void main(String[] args) {
        for (boolean webm : new boolean[]{false, true}) {
            for (int presentation = 0; presentation < 10; presentation++) {
                AnimatedEmojiDrawable emoji = new AnimatedEmojiDrawable(
                    presentation == 9 ? AnimatedEmojiDrawable.CACHE_TYPE_RENDERING_VIDEO :
                        AnimatedEmojiDrawable.CACHE_TYPE_FORUM_TOPIC, 0, 99);
                Canvas blank = new Canvas();
                switch (presentation) {
                    case 1: emoji.draw(blank, false); break;
                    case 2: emoji.alpha = 0; emoji.draw(blank, false); break;
                    case 3: emoji.draw(blank, true); break; // swap-owned opacity
                    case 4: emoji.alpha = 0; emoji.draw(blank, new Rect(), 1); break;
                    case 5: emoji.draw(blank, new Rect(), 0); break;
                    case 6: emoji.draw(blank, (ImageReceiver.BackgroundThreadDrawHolder)null, false); break;
                    case 7: emoji.alpha = 0; emoji.draw(blank, (ImageReceiver.BackgroundThreadDrawHolder)null, false); break;
                    case 8: emoji.markMissingDocumentPresented(); break; // direct-receiver host hook
                    case 9: emoji.draw(blank, false); break; // rendering-video exclusion
                }
                check(blank.alphas.isEmpty() && emoji.imageReceiver == null,
                    "missing document never creates a receiver by drawing");
                emoji.alpha = 1;
                boolean fade = presentation == 1 || presentation == 4 || presentation == 6 || presentation == 8;
                ImageReceiver receiver = emoji.receiver();
                check(!emoji.missingDocumentPresented, "factory consumes pre-receiver marker once");
                Drawable ready;
                if (webm) { AnimatedFileDrawable d = new AnimatedFileDrawable(); d.ready = true; ready = d; }
                else { RLottieDrawable d = new RLottieDrawable(); d.ready = true; ready = d; }
                check(receiver.deliver(ready, ImageReceiver.TYPE_MEDIA, true), "cache delivery accepted");
                Canvas first = new Canvas(); emoji.draw(first, false);
                check(first.alpha(ready) == (fade ? 0 : 255),
                    "presentation=" + presentation + " first alpha=" + first.alpha(ready));
                if (fade) completeFade(receiver, ready, SystemClock.now, false);
                emoji.markMissingDocumentPresented(); // already-ready shared consumer must not refade
                receiver.markLoadingPlaceholderPresented();
                receiver.deliver(ready, ImageReceiver.TYPE_MEDIA, true);
                check(receiver.frame(SystemClock.now + 100, false).alpha(ready) == 255,
                    "ready shared consumer and duplicate cache delivery never replay");
                receiver.onDetachedFromWindow();
                receiver.currentMediaKey = "media"; receiver.attachedToWindow = true;
                receiver.deliver(ready, ImageReceiver.TYPE_MEDIA, true);
                check(receiver.frame(SystemClock.now + 100, false).alpha(ready) == 255,
                    "genuine warm reattach never replays presentation fade");
                System.out.println("PASS: presentation=" + presentation + " webm=" + webm);
            }
        }
        for (int lifecycle = 0; lifecycle < 3; lifecycle++) {
            AnimatedEmojiDrawable emoji = new AnimatedEmojiDrawable(
                AnimatedEmojiDrawable.CACHE_TYPE_FORUM_TOPIC, 0, 99);
            emoji.draw(new Canvas(), false);
            ImageReceiver receiver = emoji.receiver();
            if (lifecycle == 0) receiver.onDetachedFromWindow();
            if (lifecycle == 1) receiver.setCurrentAccount(1);
            if (lifecycle == 2) receiver.clearImage();
            receiver.currentMediaKey = "media"; receiver.attachedToWindow = true;
            RLottieDrawable ready = new RLottieDrawable(); ready.ready = true;
            receiver.deliver(ready, ImageReceiver.TYPE_MEDIA, true);
            check(receiver.frame(SystemClock.now + 100, false).alpha(ready) == 255,
                "old blank marker cannot leak across lifecycle=" + lifecycle);
        }
        ImageReceiver ordinary = new ImageReceiver(); // no emoji/sticker opt-in
        ordinary.markLoadingPlaceholderPresented();
        BitmapDrawable photo = new BitmapDrawable();
        ordinary.deliver(photo, ImageReceiver.TYPE_IMAGE, true);
        check(ordinary.frame(SystemClock.now + 100, false).alpha(photo) == 255,
            "emoji presentation policy must not introduce a photo cache fade");
    }
}
"""
        return source

    def test_late_document_ready_cache_regression(self):
        run = first_frame.EmojiFirstFrameFadeTest().run_harness(self.late_document_harness())
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertEqual(run.stdout.count("PASS: presentation="), 20)

    def test_late_document_negative_controls(self):
        source = self.late_document_harness()
        for before, after, failure in [
            ("imageReceiver.markLoadingPlaceholderPresented();", "/* lost pre-receiver presentation */",
             "presentation=1 first alpha=255"),
            ("if (!ownsLoadFade && alpha > 0)", "if (!ownsLoadFade)", "presentation=2 first alpha=0"),
            ("if (!ownsLoadFade && alpha > 0)", "if (alpha > 0)", "presentation=3 first alpha=0"),
        ]:
            with self.subTest(failure=failure):
                self.assertEqual(source.count(before), 1)
                run = first_frame.EmojiFirstFrameFadeTest().run_harness(source.replace(before, after, 1))
                self.assertNotEqual(run.returncode, 0)
                self.assertIn(failure, run.stderr)

    def missing_document_host_lifecycle_harness(self):
        source = self.late_document_harness()
        emoji = (JAVA / "ui/Components/AnimatedEmojiDrawable.java").read_text()
        lifecycle = "\n".join(method(emoji, signature) for signature in (
            "private void updateAttachState()",
            "private boolean hasAttachedHosts()",
            "private void setReceiverAttached(boolean attach)",
            "private void requestDocument()",
            "public void addView(View callback)",
            "public void addView(AnimatedEmojiSpan.InvalidateHolder holder)",
            "public void removeView(View view)",
            "public void removeView(AnimatedEmojiSpan.InvalidateHolder holder)"))
        # Exercise the whole production outer lifecycle, not a copy of its
        # null-receiver branch. Only unused Android/diagnostic dependencies fake.
        source = source.replace("void updateAttachState() {}", lifecycle)
        detach_start = emoji.index("    private final Runnable detachRunnable =")
        detach_end = emoji.index("\n    };", detach_start) + len("\n    };")
        source = source.replace("    ImageReceiver imageReceiver;",
                                "    ImageReceiver imageReceiver;\n" + emoji[detach_start:detach_end])
        source = source.replace("class AnimatedEmojiDrawable extends Drawable {", """
class AnimatedEmojiDrawable extends Drawable {
    ArrayList<View> views;
    ArrayList<AnimatedEmojiSpan.InvalidateHolder> holders;
    boolean attached;
    boolean detachPending, documentRequestPending;
    Object document;
    interface ReceivedDocument { void run(Object document); }
    static class Fetcher {
        ReceivedDocument pending;
        void fetchDocumentInternal(long id, ReceivedDocument callback) { pending = callback; }
    }
    static Fetcher fetcher = new Fetcher();
    static Fetcher getDocumentFetcher(int account) { return fetcher; }
    void initDocument(boolean force) { createImageReceiver(); }
    static boolean LOG_MEMORY_LEAK;
    static int attachedCount;
    static ArrayList<AnimatedEmojiDrawable> attachedDrawable;
    static Runnable cleanup = () -> {};
""")
        source = source.replace("class AndroidUtilities {", """
class AndroidUtilities {
    static void cancelRunOnUIThread(Runnable r) {}
    static void runOnUIThread(Runnable r, long delay) {}
""")
        source = source.replace("class ImageReceiver {", """
class ImageReceiver {
    void onAttachedToWindow() { attachedToWindow = true; }
""")
        source += """
class AnimatedEmojiSpan { interface InvalidateHolder { void invalidate(); } }
class SelectAnimatedEmojiDialog { static class EmojiListView extends View {} }
class Log { static void d(String tag, String text) {} }
class Looper {
    static final Thread main = Thread.currentThread();
    static Looper getMainLooper() { return new Looper(); }
    Thread getThread() { return main; }
}
"""
        source = source.replace("    public static void main(String[] args) {", """
    public static void main(String[] args) {
        for (boolean webm : new boolean[]{false, true}) {
            for (int hosts = 0; hosts < 3; hosts++) {
                AnimatedEmojiDrawable emoji = new AnimatedEmojiDrawable(
                    AnimatedEmojiDrawable.CACHE_TYPE_FORUM_TOPIC, 0, 99);
                View first = new View();
                AnimatedEmojiSpan.InvalidateHolder other = () -> {};
                emoji.addView(first);
                if (hosts != 0) emoji.addView(other);
                emoji.draw(new Canvas(), false);
                check(emoji.missingDocumentPresented, "visible blank records pre-receiver marker");
                emoji.removeView(first);
                if (hosts == 2) {
                    check(emoji.missingDocumentPresented, "remaining holder retains blank marker");
                    emoji.removeView(other);
                }
                boolean retained = hosts == 1;
                check(emoji.imageReceiver == null, "host release occurs before metadata creates receiver");
                check(emoji.missingDocumentPresented == retained,
                    retained ? "remaining holder retains blank marker" : "last host release clears blank marker");
                // A later host arrives while metadata is still pending. This
                // attachment alone must not count as a newly presented blank.
                View returning = new View(); emoji.addView(returning);
                ImageReceiver receiver = emoji.receiver();
                emoji.addView(returning); // actual attach after receiver creation
                Drawable ready;
                if (webm) { AnimatedFileDrawable d = new AnimatedFileDrawable(); d.ready = true; ready = d; }
                else { RLottieDrawable d = new RLottieDrawable(); d.ready = true; ready = d; }
                check(receiver.deliver(ready, ImageReceiver.TYPE_MEDIA, true), "ready delivery accepted");
                Canvas firstFrame = new Canvas(); emoji.draw(firstFrame, false);
                check(firstFrame.alpha(ready) == (retained ? 0 : 255),
                    "host lifecycle must distinguish retained predecessor from warm reattach");
                System.out.println("PASS: missing-document-hosts=" + hosts + " webm=" + webm);
            }
        }
""", 1)
        return source

    def test_missing_document_host_lifecycle(self):
        run = first_frame.EmojiFirstFrameFadeTest().run_harness(self.missing_document_host_lifecycle_harness())
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertEqual(run.stdout.count("PASS: missing-document-hosts="), 6)

    def test_missing_document_host_lifecycle_negative_controls(self):
        source = self.missing_document_host_lifecycle_harness()
        for before, after, failure in [
            ("if (!attach) {", "if (false) {", "last host release clears blank marker"),
            ("if (!attach) {", "if (true) {", "remaining holder retains blank marker"),
        ]:
            with self.subTest(failure=failure):
                self.assertEqual(source.count(before), 1)
                run = first_frame.EmojiFirstFrameFadeTest().run_harness(source.replace(before, after, 1))
                self.assertNotEqual(run.returncode, 0)
                self.assertIn(failure, run.stderr)

    def layout_harness(self):
        source = (JAVA / "ui/Cells/DialogCell.java").read_text()
        layout = method(source, "if (currentDialogFolderId == 0 && !isTopic) {")
        binding = "\n".join(method(source, signature) for signature in (
            "private long badgePeerId()", "private void recordBadgePresentation()",
            "private boolean updateBadgeDrawables(boolean animated)"))
        # Include the notification wiring, not a hand-written equivalent.
        live = method(source, "if ((mask & MessagesController.UPDATE_MASK_EMOJI_STATUS) != 0) {")
        for qualified, fake in {
            "app.nimarkogram.messenger.NimarkoConfig": "NimarkoConfig",
            "app.nimarkogram.messenger.api.dto.BadgeDTO": "BadgeDTO",
            "app.nimarkogram.messenger.badges.BadgesController": "BadgesController",
            "org.telegram.tgnet.TLObject": "Peer",
        }.items():
            layout = layout.replace(qualified, fake)
            binding = binding.replace(qualified, fake)
        harness = status_crossfade.EmojiStatusCrossfadeTests().harness_source()
        identity = method((JAVA / "ui/Components/AnimatedEmojiDrawable.java").read_text(),
                          "public boolean isSameEmoji(")
        harness = harness.replace("class AnimatedEmojiDrawable extends Drawable {",
                                  "class AnimatedEmojiDrawable extends Drawable {\n" + identity)
        harness = harness.replace("static int selectedAccount;", """
            static int selectedAccount; long clientUserId = 999;
            static UserConfig getInstance(int a) { return new UserConfig(); }
        """)
        harness = harness.replace("interface ResourcesProvider {}", """
            interface ResourcesProvider {}
            static Scam dialogs_scamDrawable = new Scam(), dialogs_fakeDrawable = new Scam();
        """)
        harness += """
class Peer { long id = 10, bot_verification_icon; Long emoji_status = 1L; boolean scam, fake, verified, premium = true; }
class Scam { void checkText() {} }
class NimarkoConfig { static boolean disablePremiumStatuses; }
class LocaleController { static boolean isRTL; }
class SharedConfig { static boolean enabled = true; static boolean animationsEnabled() { return enabled; } }
class BadgeDTO { long id = 2; BadgeDTO() {} BadgeDTO(long id) { this.id = id; } long getDocumentId() { return id; } }
class BadgesController {
    static BadgeDTO badge = new BadgeDTO();
    static Peer requiredTarget;
    static BadgesController getInstance() { return new BadgesController(); }
    BadgeDTO i(Peer p) { return requiredTarget == null || p == requiredTarget ? badge : null; }
}
class DialogObject {
    static long getBotVerificationIcon(Peer p) { return p == null ? 0 : p.bot_verification_icon; }
    static long getEmojiStatusDocumentId(Long s) { return s == null ? 0 : s; }
    static boolean isEmojiStatusCollectible(Long s) { return false; }
}
class UserObject {
    static boolean isUserSelf(Peer p) { return false; }
    static Long getEmojiStatusDocumentId(Peer p) { return p.emoji_status; }
}
class MessagesController {
    static final int UPDATE_MASK_EMOJI_STATUS = 1;
    static Peer fresh;
    static MessagesController getInstance(int a) { return new MessagesController(); }
    boolean isPremiumUser(Peer p) { return p.premium; }
    Peer getUser(long id) { return fresh; } Peer getChat(long id) { return fresh; }
}
class PremiumGradient {
    Drawable premiumStarDrawableMini = new Drawable();
    static final PremiumGradient instance = new PremiumGradient();
    static PremiumGradient getInstance() { return instance; }
}
class Consumer {
    int currentDialogFolderId, currentAccount, drawScam;
    boolean isTopic, drawPremium, nameLayoutEllipsizeByGradient, drawVerified, forbidVerified, drawBotVerified;
    int badgeBoundAccount = -1;
    long currentDialogId = 10, badgeBoundDialogId, badgeBoundPeerId;
    boolean badgeOwnerDrawn, badgeParticles, badgeLayoutDirty;
    boolean attachedToWindow = true, visibleOnScreen = true;
    Object customDialog;
    Peer user, chat;
    AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable emojiStatus = Harness.swap;
    AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable botVerification =
        new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(new View(), 17);
    void layout() { LAYOUT }
    BINDING
    boolean live(boolean animated) {
        int mask = MessagesController.UPDATE_MASK_EMOJI_STATUS;
        boolean continueUpdate = false, invalidate = false;
        LIVE
        if (continueUpdate) layout();
        return continueUpdate;
    }
    Canvas frame(long time) { recordBadgePresentation(); return Harness.draw(time); }
}
""".replace("LAYOUT", layout).replace("BINDING", binding).replace("LIVE", live)
        start = harness.index("    public static void main(String[] args) {")
        end = start + len("    public static void main(String[] args) {")
        harness = harness[:end] + """
        for (boolean chat : new boolean[]{false, true}) {
            setup();
            b.imageReceiver.loaded = true;
            Consumer consumer = new Consumer();
            if (chat) consumer.chat = new Peer(); else consumer.user = new Peer();
            consumer.layout();
            swap.set(2L, false); swap.setParticles(true, false);
            int adds = b.adds, removes = b.removes;
            consumer.layout(); consumer.layout();
            check(b.adds == adds && b.removes == removes,
                "unchanged badge layout must not detach and reattach its receiver");
            swap.set(1L, false); swap.set(2L, true); swap.setParticles(true, true);
            draw(0); float before = draw(100).alpha("e2");
            consumer.layout();
            eq(draw(100).alpha("e2"), before);
            check(!swap.isStable(), "layout must preserve an in-flight badge transition");
            b.imageReceiver.loaded = false;
            swap.set(1L, false); swap.set(2L, true);
            consumer.layout(); eq(draw(1000).alpha("e1"), 1);
            BadgesController.badge = null;
            consumer.layout();
            check(swap.getDrawable() == a, "no badge keeps Telegram status fallback");
            BadgesController.badge = new BadgeDTO();
        }
        System.out.println("PASS: consumer layout bindings");
""" + harness[end:]
        return harness

    def test_layout_binds_only_final_badge(self):
        run = status_crossfade.EmojiStatusCrossfadeTests().run_harness(self.layout_harness())
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertIn("PASS: consumer layout bindings", run.stdout)

    def test_layout_double_bind_negative_control(self):
        source = self.layout_harness().replace("final Drawable current = emojiStatus.getDrawable();",
            "emojiStatus.set(1L, false);\n        final Drawable current = emojiStatus.getDrawable();")
        run = status_crossfade.EmojiStatusCrossfadeTests().run_harness(source)
        self.assertNotEqual(run.returncode, 0)
        self.assertIn("unchanged badge layout must not detach and reattach", run.stderr)

    def live_harness(self):
        source = self.layout_harness()
        source = source.replace("    public static void main(String[] args) {", """
    static Consumer liveConsumer(boolean chat) {
        BadgesController.badge = null;
        BadgesController.requiredTarget = null;
        SharedConfig.enabled = true;
        Consumer consumer = new Consumer();
        Peer peer = new Peer();
        if (chat) { consumer.chat = peer; consumer.currentDialogId = -peer.id; }
        else consumer.user = peer;
        MessagesController.fresh = peer;
        consumer.layout(); consumer.frame(0);
        return consumer;
    }
    public static void main(String[] args) {
        for (boolean isChat : new boolean[]{false, true}) {
            test("live ready badge and duplicate preserve progress", () -> {
                Consumer row = liveConsumer(isChat);
                b.imageReceiver.loaded = true;
                BadgesController.badge = new BadgeDTO(2);
                check(row.live(true), "new badge requests layout");
                eq(row.frame(0).alpha("e1"), 1);
                eq(row.frame(0).alpha("e2"), 0);
                float partial = row.frame(100).alpha("e2");
                check(partial > 0 && partial < 1, "actual visible badge change fades");
                int adds = b.adds, removes = b.removes;
                row.live(false); row.layout();
                eq(row.frame(100).alpha("e2"), partial);
                check(b.adds == adds && b.removes == removes, "duplicate retains receiver identity");
            });
            test("cold badge holds predecessor and particles across layout", () -> {
                Consumer row = liveConsumer(isChat);
                BadgesController.badge = new BadgeDTO(2);
                row.live(true); row.live(false); row.layout();
                Canvas waiting = row.frame(1000);
                eq(waiting.alpha("e1"), 1); eq(waiting.alpha("e2"), 0);
                eq(waiting.alpha("particles"), 0);
                b.imageReceiver.loaded = true;
                row.frame(1000);
                check(row.frame(1100).alpha("e2") > 0, "ready badge begins transition");
            });
            test("badge id swap and return after intervening fallback", () -> {
                Consumer row = liveConsumer(isChat);
                b.imageReceiver.loaded = c.imageReceiver.loaded = true;
                BadgesController.badge = new BadgeDTO(2); row.live(false); row.frame(0);
                BadgesController.badge = new BadgeDTO(3); row.live(true);
                eq(row.frame(0).alpha("e2"), 1); eq(row.frame(0).alpha("e3"), 0);
                check(row.frame(100).alpha("e3") > 0, "different badge id crossfades");
                BadgesController.badge = null; row.live(false);
                check(swap.getDrawable() == a, "intervening fallback applied");
                BadgesController.badge = new BadgeDTO(3); row.live(true);
                check(swap.getDrawable() == c && !swap.isStable(), "returning id is not suppressed by stale doc guard");
            });
            test("badge removal restores Telegram fallback without snap", () -> {
                Consumer row = liveConsumer(isChat);
                b.imageReceiver.loaded = true;
                BadgesController.badge = new BadgeDTO(2); row.live(false); row.frame(0);
                BadgesController.badge = null; row.live(true);
                eq(row.frame(0).alpha("e2"), 1); eq(row.frame(0).alpha("e1"), 0);
                row.frame(100); eq(row.frame(400).alpha("e1"), 1);
            });
            test("winner suppresses bot and refreshes peer before selecting", () -> {
                Consumer row = liveConsumer(isChat);
                Peer refreshed = new Peer(); refreshed.bot_verification_icon = 3;
                MessagesController.fresh = refreshed;
                BadgesController.requiredTarget = refreshed;
                b.imageReceiver.loaded = true;
                BadgesController.badge = new BadgeDTO(2);
                row.live(true);
                check((isChat ? row.chat : row.user) == refreshed, "fresh peer used by resolver");
                check(row.drawPremium && !row.drawBotVerified && !row.drawVerified,
                    "Nimarko wins final visible priority");
                check(row.botVerification.getDrawable() == null, "bot is not rebound after badge wins");
                BadgesController.requiredTarget = null;
            });
            test("account and peer recycling cannot borrow outgoing history", () -> {
                Consumer row = liveConsumer(isChat);
                b.imageReceiver.loaded = true;
                BadgesController.badge = new BadgeDTO(2); row.live(true); row.frame(100);
                row.currentAccount = 1;
                AnimatedEmojiDrawable next = AnimatedEmojiDrawable.make(1, 7, 2);
                next.imageReceiver = new ImageReceiver(); next.imageReceiver.loaded = true;
                row.live(true);
                check(swap.getDrawable() == next && swap.isStable(), "account change is immediate and pinned");
                eq(row.frame(100).alpha("e2"), 1);
                Peer peer = new Peer(); peer.id = 20; MessagesController.fresh = peer;
                row.currentDialogId = isChat ? -20 : 20;
                row.live(true);
                check(swap.isStable(), "same badge id on different owner does not replay");
                check(b.holders.isEmpty(), "previous account receiver released");
            });
            test("late badge addition from a genuinely empty visible slot", () -> {
                Consumer row = liveConsumer(isChat);
                Peer plain = MessagesController.fresh;
                plain.premium = false; plain.emoji_status = null;
                row.live(false); row.frame(0);
                check(swap.isEmpty(), "plain peer has no phantom star");
                b.imageReceiver.loaded = true;
                BadgesController.badge = new BadgeDTO(2); row.live(true);
                eq(row.frame(0).alpha("e2"), 0);
                check(row.drawPremium, "addition installs visible host");
                row.frame(100); row.frame(400);
                BadgesController.badge = null; row.live(true);
                check(row.drawPremium && swap.isEmpty(), "removal retains outgoing host");
                eq(row.frame(400).alpha("e2"), 1);
                row.frame(500); row.frame(800); row.layout();
                check(!row.drawPremium, "finished removal releases slot width");
            });
        }
        for (int mode = 0; mode < 4; mode++) {
            final int policy = mode;
            test("no replay before visible draw or while detached/offscreen/disabled", () -> {
                Consumer row = liveConsumer(false);
                if (policy == 0) row.badgeOwnerDrawn = false;
                if (policy == 1) row.attachedToWindow = false;
                if (policy == 2) row.visibleOnScreen = false;
                if (policy == 3) SharedConfig.enabled = false;
                b.imageReceiver.loaded = true;
                BadgesController.badge = new BadgeDTO(2); row.live(true);
                check(swap.isStable(), "nonvisible binding must stay immediate");
                eq(row.frame(0).alpha("e2"), 1);
                SharedConfig.enabled = true;
            });
        }
        BadgesController.badge = new BadgeDTO();
        System.out.println("PASS: 18 live consumer scenarios");
""", 1)
        # The existing fixture asserts its own scenario count; our extra tests use
        # its setup/assertions too, but do not alter that fixture on disk.
        source = source.replace('"PASS: " + passed + " production swap scenarios"',
                                '"PASS: " + (passed - 18) + " production swap scenarios"')
        return source

    def test_live_badge_updates(self):
        run = status_crossfade.EmojiStatusCrossfadeTests().run_harness(self.live_harness())
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertIn("PASS: 18 live consumer scenarios", run.stdout)

    def test_live_badge_negative_controls(self):
        source = self.live_harness()
        mutations = [
            ("hardcoded nonanimated", "if (documentId != 0) emojiStatus.set(documentId, animated);",
             "if (documentId != 0) emojiStatus.set(documentId, false);", "live ready badge"),
            ("layout flushes pending particles", "if (!sameStatus || particlesChanged || !sameOwner) {",
             "if (true) {", "cold badge holds predecessor"),
            ("bot rebound despite winner", "botIcon = 0;\n            documentId = badge.getDocumentId();",
             "botIcon = DialogObject.getBotVerificationIcon(user != null ? user : chat);\n            documentId = badge.getDocumentId();",
             "winner suppresses bot"),
            ("missing account pin", "emojiStatus.setCurrentAccount(currentAccount);",
             "emojiStatus.setCurrentAccount(UserConfig.selectedAccount);", "account and peer recycling"),
        ]
        for label, before, after, failure in mutations:
            with self.subTest(label=label):
                self.assertEqual(source.count(before), 1)
                run = status_crossfade.EmojiStatusCrossfadeTests().run_harness(source.replace(before, after, 1))
                self.assertNotEqual(run.returncode, 0)
                self.assertIn(failure, run.stderr)

    def test_dialog_lifecycle_wiring(self):
        source = (JAVA / "ui/Cells/DialogCell.java").read_text()
        self.assertIn("badgeOwnerDrawn = false", method(source, "public void setVisible(boolean visibleOnScreen)"))
        self.assertIn("badgeOwnerDrawn = false", method(source, "protected void onDetachedFromWindow()"))
        self.assertIn("recordBadgePresentation();", method(source, "protected void onDraw(Canvas canvas)"))
        self.assertIn("!badgeLayoutDirty", method(source, "public void buildLayout()"))
        self.assertIn("if (emojiStatus.isEmpty()) DialogCell.this.invalidate();", source)


if __name__ == "__main__":
    unittest.main()
