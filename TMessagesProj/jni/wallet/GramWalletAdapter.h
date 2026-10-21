/* Modifications Copyright (C) 2026 Ettacent */

#pragma once

#include <string>

namespace gram_adapter {
std::string ton_connect_crypto(const std::string &phrase, const std::string &parameters);
bool is_valid_cell_boc(const std::string &boc);
std::string message_body_hash(const std::string &boc);
}
