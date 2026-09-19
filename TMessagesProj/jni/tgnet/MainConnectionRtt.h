#ifndef MAIN_CONNECTION_RTT_H
#define MAIN_CONNECTION_RTT_H

#include <atomic>
#include <cstdint>
#include <limits>

// Sent/received/reset are network-thread-only. The UI reads the published sample atomically.
class MainConnectionRtt {
public:
    enum Result { Ignored, WarmedUp, Measured };

    void reset() {
        published.store(0);
        sampledAt.store(0);
        pending = false;
        warmed = false;
        token = dc = 0;
    }

    void sent(int64_t id, uint32_t connectionToken, uint32_t dcId, int64_t now) {
        if (token != connectionToken || dc != dcId) reset();
        token = connectionToken;
        dc = dcId;
        pingId = id;
        sentAt = now;
        pending = true;
    }

    bool matches(int64_t id, uint32_t connectionToken, uint32_t dcId) const {
        return pending && token != 0 && id == pingId && connectionToken == token && dcId == dc;
    }

    Result received(int64_t id, uint32_t connectionToken, uint32_t dcId, int64_t now) {
        if (!matches(id, connectionToken, dcId)) return Ignored;
        pending = false;
        int64_t elapsed = now - sentAt;
        if (elapsed < 0 || elapsed > std::numeric_limits<int32_t>::max()) return Ignored;
        // The local SOCKS connection can open before remote WS/TLS setup finishes.
        // Only the next round trip is guaranteed to use an established end-to-end tunnel.
        if (!warmed) {
            warmed = true;
            return WarmedUp;
        }
        int32_t sample = elapsed > 0 ? static_cast<int32_t>(elapsed) : 1;
        int32_t previous = value(now);
        published.store(previous > 0 ? static_cast<int32_t>((int64_t(previous) + sample) / 2) : sample);
        sampledAt.store(now);
        return Measured;
    }

    int32_t value(int64_t now) const {
        int64_t timestamp = sampledAt.load();
        return timestamp > 0 && now >= timestamp && now - timestamp < 60000 ? published.load() : 0;
    }

private:
    int64_t pingId = 0, sentAt = 0;
    uint32_t token = 0, dc = 0;
    bool pending = false, warmed = false;
    std::atomic<int32_t> published{0};
    std::atomic<int64_t> sampledAt{0};
};

#endif
