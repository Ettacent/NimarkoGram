package app.nimarkogram.messenger;

public final class DownloadSpeedPolicyTest {

    public static void main(String[] args) {
        assertProfile(DownloadSpeedPolicy.BOOST_NONE, false, false, 128 * 1024, 4);
        assertProfile(DownloadSpeedPolicy.BOOST_AVERAGE, false, false, 512 * 1024, 8);
        assertProfile(DownloadSpeedPolicy.BOOST_EXTREME, false, false, 1024 * 1024, 12);

        assertProfile(DownloadSpeedPolicy.BOOST_NONE, false, true, 128 * 1024, 4);
        assertProfile(DownloadSpeedPolicy.BOOST_AVERAGE, false, true, 256 * 1024, 4);
        assertProfile(DownloadSpeedPolicy.BOOST_EXTREME, false, true, 512 * 1024, 4);

        assertProfile(DownloadSpeedPolicy.BOOST_NONE, true, false, 512 * 1024, 8);
        assertProfile(DownloadSpeedPolicy.BOOST_NONE, true, true, 256 * 1024, 4);
        assertProfile(DownloadSpeedPolicy.BOOST_EXTREME, true, false, 1024 * 1024, 12);
        assertProfile(DownloadSpeedPolicy.BOOST_EXTREME, true, true, 512 * 1024, 4);

        require(DownloadSpeedPolicy.normalizeBoost(-1) == DownloadSpeedPolicy.BOOST_NONE,
                "negative persisted value must be disabled");
        require(DownloadSpeedPolicy.normalizeBoost(99) == DownloadSpeedPolicy.BOOST_NONE,
                "unknown persisted value must be disabled");
    }

    private static void assertProfile(int boost, boolean telegramLarge, boolean bypass,
                                      int expectedChunk, int expectedRequests) {
        DownloadSpeedPolicy.Profile profile =
                DownloadSpeedPolicy.resolve(boost, telegramLarge, bypass);
        require(profile.chunkSize == expectedChunk,
                "unexpected chunk: " + profile.chunkSize + " expected " + expectedChunk);
        require(profile.maxRequests == expectedRequests,
                "unexpected request count: " + profile.maxRequests + " expected " + expectedRequests);
        require(profile.chunkSize <= 1024 * 1024,
                "Telegram limit must stay at or below 1 MiB");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private DownloadSpeedPolicyTest() {
    }
}
