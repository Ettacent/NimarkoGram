/* Modifications Copyright (C) 2026 Ettacent */

#pragma once

#include "twidget/intl.h"

#include <string>

namespace twidget {

class IcuIntl : public IntlProvider {
public:
    explicit IcuIntl(std::string fallbackLocale = "en-US");
    std::string format(const Json& request) override;

private:
    std::string fallback_;
    BasicIntl basic_;
};

}
