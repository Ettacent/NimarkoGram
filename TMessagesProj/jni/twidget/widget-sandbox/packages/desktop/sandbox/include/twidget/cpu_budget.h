/* Modifications Copyright (C) 2026 Ettacent */

#pragma once

#include <array>
#include <algorithm>
#include <cmath>
#include <string>
#include <string_view>

namespace twidget {

struct CpuDecision {
    bool run;
    double sliceMs;
    double retryAt;
    bool operator==(const CpuDecision& o) const { return run == o.run && (run ? sliceMs == o.sliceMs : retryAt == o.retryAt); }
};

class CpuBudget {
public:
    static constexpr int kBuckets = 10;
    static bool deferrable(std::string_view kind);
    static constexpr int kQueryLimit = 128;
    static constexpr double kQueryBudgetMs = 1000;
    static std::string_view queryLimitHit(int queries, double queryMs) {
        if (queries > kQueryLimit) return "count";
        if (queryMs > kQueryBudgetMs) return "time";
        return "";
    }
    static std::string queryLimitReason(std::string_view hit, const std::string& kind) {
        return hit == "count"
            ? "Layout query limit exceeded: more than " + std::to_string(kQueryLimit) + " layout queries in one " + kind + " task"
            : "Layout query limit exceeded: host queries blocked the renderer for more than " + std::to_string(int(kQueryBudgetMs)) + " ms in one " + kind + " task";
    }
    static constexpr double kOpCostMs = 0.025;
    static constexpr double kCanvasCommandCostMs = 0.002;
    static double opsCostMs(double ops, double canvasCommands) { return ops * kOpCostMs + canvasCommands * kCanvasCommandCostMs; }

    CpuBudget(double cpuLimitMs, double cpuQuotaMs, double cpuQuotaWindowMs, double cpuKillAfterMs);

    void record(std::string_view kind, double cpuMs, double now);
    double used(double now);
    CpuDecision decide(std::string_view kind, double now);
    double throttledFor(double now) const;
    bool shouldKill(double now) const;

private:
    double oldestStart(double now) const;
    int slot(double b);
    double windowAt(double b) const;
    void settle(double now);

    double cpuLimitMs_, cpuQuotaMs_, windowMs_, cpuKillAfterMs_, bucketMs_;
    std::array<double, kBuckets> starts_{};
    std::array<double, kBuckets> ms_{};
    double debtMs_ = 0;
    double settled_ = -INFINITY;
    double throttledSince_ = -1;
    double lastThrottleAt_ = -1;
};

}
