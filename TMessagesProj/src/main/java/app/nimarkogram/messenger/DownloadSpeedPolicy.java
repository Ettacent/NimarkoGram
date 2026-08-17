package app.nimarkogram.messenger;

public final class DownloadSpeedPolicy {

    public static final int BOOST_NONE = 0;
    public static final int BOOST_AVERAGE = 1;
    public static final int BOOST_EXTREME = 2;

    private static final int KIB = 1024;

    private static final Profile STOCK = new Profile(128 * KIB, 4);
    private static final Profile DIRECT_AVERAGE = new Profile(512 * KIB, 8);
    private static final Profile DIRECT_EXTREME = new Profile(1024 * KIB, 12);

    private static final Profile BYPASS_AVERAGE = new Profile(256 * KIB, 4);
    private static final Profile BYPASS_EXTREME = new Profile(512 * KIB, 4);

    public static int normalizeBoost(int value) {
        return value >= BOOST_NONE && value <= BOOST_EXTREME ? value : BOOST_NONE;
    }

    public static Profile resolve(int configuredBoost, boolean telegramRequestsLargeChunks,
                                  boolean wsBypassActive) {
        int tier = normalizeBoost(configuredBoost);
        if (tier == BOOST_NONE && telegramRequestsLargeChunks) {
            tier = BOOST_AVERAGE;
        }
        if (tier == BOOST_EXTREME) {
            return wsBypassActive ? BYPASS_EXTREME : DIRECT_EXTREME;
        }
        if (tier == BOOST_AVERAGE) {
            return wsBypassActive ? BYPASS_AVERAGE : DIRECT_AVERAGE;
        }
        return STOCK;
    }

    public static final class Profile {
        public final int chunkSize;
        public final int maxRequests;

        private Profile(int chunkSize, int maxRequests) {
            this.chunkSize = chunkSize;
            this.maxRequests = maxRequests;
        }

        public long inFlightBytes() {
            return (long) chunkSize * maxRequests;
        }
    }

    private DownloadSpeedPolicy() {
    }
}
