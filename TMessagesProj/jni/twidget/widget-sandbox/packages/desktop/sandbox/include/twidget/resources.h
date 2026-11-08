/* Modifications Copyright (C) 2026 Ettacent */

#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace twidget {

class SandboxResources {
public:
    static std::shared_ptr<SandboxResources> fromDirectory(const std::string& dir);

    std::string sandboxBundle;
    std::string validatorBundle;
    std::string rendererBundle;
    std::vector<uint8_t> sandboxBytecode;
    std::vector<uint8_t> validatorBytecode;
};

}
