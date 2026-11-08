/* Modifications Copyright (C) 2026 Ettacent */

#pragma once

#include <string>

namespace twidget {

class Json;

class IntlProvider {
public:
    virtual ~IntlProvider() = default;
    virtual std::string format(const Json& request) = 0;
};

class BasicIntl : public IntlProvider {
public:
    std::string format(const Json& request) override;
};

}
