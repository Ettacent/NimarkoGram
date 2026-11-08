/* Modifications Copyright (C) 2026 Ettacent */

#pragma once

#include <optional>
#include <string>

namespace twidget {

class SandboxResources;

struct BundleStamp {
    std::string version;
    int protocol = 0;
    int profile = 0;
    int sdk = 0;

    static BundleStamp glue();
    static std::optional<BundleStamp> parse(const std::string& source);
    static std::optional<std::string> refusal(const std::string& source, const std::string& name);
    static std::optional<std::string> refusal(const SandboxResources& resources);

    std::string describe() const;
    std::string line() const;
    bool operator==(const BundleStamp&) const = default;
};

}
