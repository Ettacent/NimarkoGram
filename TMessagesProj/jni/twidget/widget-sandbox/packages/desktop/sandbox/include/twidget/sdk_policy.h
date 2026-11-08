/* Modifications Copyright (C) 2026 Ettacent */

#pragma once

#include "twidget/json.h"
#include "twidget/version.h"

#include <array>
#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace twidget {

using StorageArea = std::map<std::string, std::string>;

struct WidgetStorage {
    StorageArea local;
    StorageArea session;
};

struct WidgetAction {
    enum Kind { Ready, OpenLink, OpenTelegramLink, Haptic } kind = Ready;
    std::string url;
    std::string hapticKind;
    std::string hapticValue;

    bool operator==(const WidgetAction& o) const { return kind == o.kind && url == o.url && hapticKind == o.hapticKind && hapticValue == o.hapticValue; }
    Json toJson() const;
};


struct WidgetData {
    std::optional<std::string> chatData;
    std::optional<std::string> publicData;
    bool operator==(const WidgetData& o) const { return chatData == o.chatData && publicData == o.publicData; }
};

struct WidgetViewer {
    int64_t id = 0;
    std::optional<std::string> firstName;
    std::optional<std::string> lastName;
    std::optional<std::string> username;
    bool isPremium = false;
    bool operator==(const WidgetViewer& o) const {
        return id == o.id && firstName == o.firstName && lastName == o.lastName && username == o.username && isPremium == o.isPremium;
    }
};

struct WidgetContext {
    bool isCopy = false;
    bool canQuery = false;
    bool backgroundQuery = false;
    std::string chatType;
    std::optional<WidgetViewer> viewer;
    bool preview = false;
    bool wallet = false;
};

struct WidgetRequest {
    std::string data;
    bool wallet = false;
    bool operator==(const WidgetRequest& o) const { return data == o.data && wallet == o.wallet; }
};

struct WalletMessage {
    std::string address;
    std::string amount;
    std::optional<std::string> payload;
    std::optional<std::string> stateInit;
    bool operator==(const WalletMessage& o) const { return address == o.address && amount == o.amount && payload == o.payload && stateInit == o.stateInit; }
    Json toJson() const;
};

struct WalletTransaction {
    std::optional<int64_t> validUntil;
    std::optional<std::string> network;
    std::optional<std::string> from;
    std::vector<WalletMessage> messages;
    bool operator==(const WalletTransaction& o) const { return validUntil == o.validUntil && network == o.network && from == o.from && messages == o.messages; }
    Json toJson() const;
};

struct SignDataPayload {
    std::string type;
    std::optional<std::string> text, bytes, schema, cell, network, from;
    bool operator==(const SignDataPayload& o) const {
        return type == o.type && text == o.text && bytes == o.bytes && schema == o.schema && cell == o.cell && network == o.network && from == o.from;
    }
    Json toJson() const;
};

struct WalletCall {
    enum class Method { SendTransaction, SignData } method = Method::SendTransaction;
    WalletTransaction transaction;
    SignDataPayload payload;
    std::string data;
    std::string_view methodName() const { return method == Method::SignData ? "signData" : "sendTransaction"; }
    bool operator==(const WalletCall& o) const {
        return method == o.method && data == o.data && (method == Method::SignData ? payload == o.payload : transaction == o.transaction);
    }
    Json toJson() const;
};

struct WidgetRequestAnswer {
    std::optional<std::string> result;
};

enum class WidgetRequestFailure { None, Timeout, RateLimited, Failed, Rejected, BadRequest };

struct WidgetRequestOutcome {
    WidgetRequestFailure failure = WidgetRequestFailure::None;
    WidgetRequestAnswer answer;
};

namespace sdk {

inline constexpr int MAX_LINK_LENGTH = 256;
inline constexpr int STORAGE_QUOTA_BYTES = 4096;
inline constexpr int MAX_LOCALE_LENGTH = 35;
inline constexpr std::string_view DEFAULT_LOCALE = "en-US";
inline constexpr int HAPTIC_BURST = 5;
inline constexpr int HAPTIC_PER_SECOND = 10;
inline constexpr double HAPTIC_REPORT_MS = 1000;
inline constexpr double HAPTIC_TOUCH_WINDOW_MS = 5000;
inline constexpr double HAPTIC_SERIES_MS = 1000;
inline constexpr double HAPTIC_SERIES_GAP_MS = 10000;
inline constexpr double HAPTIC_PRESS_MAX_MS = 30000;
inline constexpr int MAX_HANDLERS_PER_EVENT = 16;
inline constexpr int PROFILE_VERSION = version::PROFILE;
inline constexpr size_t MAX_STORAGE_COMMIT_BYTES = 2 * 12 * STORAGE_QUOTA_BYTES + 64;
inline constexpr int MAX_REJECTION_REPORTS = 20;

inline constexpr std::array<std::string_view, 15> THEME_KEYS = {
    "bg_color", "text_color", "hint_color", "link_color", "button_color", "button_text_color",
    "secondary_bg_color", "accent_text_color", "subtitle_text_color", "destructive_text_color",
    "section_bg_color", "section_header_text_color", "section_separator_color", "header_bg_color", "bottom_bar_bg_color",
};
inline constexpr std::array<std::string_view, 5> HAPTIC_IMPACT_STYLES = {"light", "medium", "heavy", "rigid", "soft"};
inline constexpr std::array<std::string_view, 3> HAPTIC_NOTIFICATION_TYPES = {"error", "success", "warning"};
inline constexpr std::array<std::string_view, 3> TELEGRAM_HOSTS = {"t.me", "telegram.me", "telegram.dog"};
inline constexpr std::array<std::string_view, 3> PRIVILEGED_METHODS = {"openLink", "openTelegramLink", "copyText"};
inline constexpr std::array<std::string_view, 2> PREVIEW_METHODS = {"ready", "setResizeAnchor"};
inline constexpr std::array<std::string_view, 2> RESIZE_ANCHORS = {"top", "bottom"};
inline constexpr std::string_view DEFAULT_RESIZE_ANCHOR = "top";


inline constexpr int MAX_WIDGET_DATA_BYTES = 1024;
inline constexpr int MAX_REQUEST_DATA_BYTES = 64;
inline constexpr int MAX_REQUEST_RESULT_BYTES = 1024;
inline constexpr int MAX_COPY_TEXT_LENGTH = 256;
inline constexpr double BACKGROUND_REQUEST_INTERVAL_MS = 5000;
inline constexpr int64_t MAX_REQUEST_ID = 2147483647;
inline constexpr int64_t MAX_VIEWER_ID = 9007199254740991;
inline constexpr int MAX_VIEWER_NAME_LENGTH = 64;
inline constexpr int MIN_VIEWER_USERNAME_LENGTH = 5;
inline constexpr int MAX_VIEWER_USERNAME_LENGTH = 32;
inline constexpr std::array<std::string_view, 4> CHAT_TYPES = {"private", "group", "supergroup", "channel"};
inline constexpr std::array<std::string_view, 8> REQUEST_ERRORS = {"NOT_ALLOWED", "BUSY", "RATE_LIMITED", "TIMEOUT", "FAILED", "INVALID_ANSWER", "REJECTED", "BAD_REQUEST"};


inline constexpr size_t MAX_WALLET_MESSAGES = 255;
inline constexpr size_t MAX_WALLET_BOC_LENGTH = 8192;
inline constexpr size_t MAX_WALLET_BOC_TOTAL = 65536;
inline constexpr size_t MAX_SIGN_DATA_LENGTH = 4096;
inline constexpr size_t MAX_SIGN_DATA_SCHEMA_BYTES = 1024;
inline constexpr double WALLET_GRANT_MS = 10000;
inline constexpr std::array<std::string_view, 2> WALLET_NETWORKS = {"-239", "-3"};

bool isPrivilegedMethod(std::string_view method);
bool allowedInPreview(std::string_view method);

inline size_t utf8Length(std::string_view s) { return s.size(); }
size_t utf16Length(std::string_view s);
bool wellFormed(std::string_view s);

std::optional<std::string> checkLink(std::string_view method, std::string_view url);
std::optional<std::string> checkLinkValue(std::string_view method, const Json& url);

struct HapticCall {
    std::string haptic;
    std::optional<std::string> value;
    bool operator==(const HapticCall& o) const { return haptic == o.haptic && value == o.value; }
};

std::optional<HapticCall> checkHaptic(const Json& args);

std::optional<std::string> checkResizeAnchor(std::string_view edge);
std::optional<std::string> checkResizeAnchorValue(const Json& edge);

struct BridgeCall {
    struct Request {
        int64_t id = 0;
        std::string data;
        bool wallet = false;
        bool operator==(const Request& o) const { return id == o.id && data == o.data && wallet == o.wallet; }
    };
    struct Wallet {
        int64_t id = 0;
        WalletCall call;
        bool operator==(const Wallet& o) const { return id == o.id && call == o.call; }
    };
    std::optional<WidgetAction> action;
    std::optional<std::string> resizeAnchor;
    std::optional<Request> request;
    std::optional<Wallet> wallet;
    std::optional<std::string> copyText;
    std::optional<bool> watchPublicData;

    bool operator==(const BridgeCall& o) const {
        return action == o.action && resizeAnchor == o.resizeAnchor && request == o.request && wallet == o.wallet && copyText == o.copyText && watchPublicData == o.watchPublicData;
    }
    Json toJson() const;
};

std::optional<BridgeCall> checkBridgeCall(const Json& method, const Json& args);

std::optional<bool> checkRequestOptions(const Json& options);
std::optional<std::string> checkWalletAddress(const Json& address);
std::optional<std::string> checkWalletDestination(const Json& address);
std::optional<WalletTransaction> checkWalletTransaction(const Json& tx);
std::optional<SignDataPayload> checkSignDataPayload(const Json& payload);

std::optional<std::string> checkRequestData(std::string_view data);
std::optional<std::string> checkRequestDataValue(const Json& data);
std::optional<std::string> checkCopyText(std::string_view text);
std::optional<std::string> checkCopyTextValue(const Json& text);
std::optional<std::string> checkWidgetDataValue(std::string_view value);

struct CheckedWidgetData {
    std::optional<std::string> chatData;
    std::optional<std::string> publicData;
    std::vector<std::string> rejected;
    Json toJson() const;
};

CheckedWidgetData checkWidgetData(const WidgetData& data, bool copy);

struct CheckedViewer {
    std::optional<WidgetViewer> viewer;
    std::vector<std::string> rejected;
    Json toJson() const;
};

CheckedViewer checkViewer(const std::optional<WidgetViewer>& viewer);
Json viewerJson(const WidgetViewer& viewer);
std::string_view viewerFieldRule(std::string_view field);
std::optional<std::string> checkChatType(std::string_view chatType);

struct CheckedAnswer {
    std::optional<std::string> result;
    Json toJson() const;
};

std::optional<CheckedAnswer> checkRequestAnswer(const WidgetRequestAnswer& answer);

std::string_view requestFailureCode(WidgetRequestFailure failure);
std::string_view walletFailureCode(WidgetRequestFailure failure);

enum class RequestVerdict { Foreground, Background, Held, Granted, Disabled, NoWallet, Busy, NoActivation, RateLimited };
std::string_view requestVerdictName(RequestVerdict verdict);
std::optional<std::string_view> requestRefusal(RequestVerdict verdict);

class RequestGate {
public:
    struct Conditions {
        bool gesture = false;
        bool visible = false;
        bool canQuery = false;
        bool backgroundQuery = false;
        bool withWallet = false;
        bool wallet = false;
    };
    struct WalletConditions {
        bool gesture = false;
        bool visible = false;
        bool canQuery = false;
        bool wallet = false;
    };
    RequestVerdict decide(double nowMs, const Conditions& s);
    RequestVerdict decideWallet(double nowMs, const WalletConditions& s);
    void finish(std::optional<double> answeredAtMs = std::nullopt) {
        if (granting_ && answeredAtMs) grantAt_ = answeredAtMs;
        inFlight_ = held_ = granting_ = false;
    }
    bool show(double nowMs);
    bool busy() const { return inFlight_; }

private:
    RequestVerdict out(RequestVerdict verdict) {
        inFlight_ = granting_ = true;
        grantAt_.reset();
        return verdict;
    }
    bool inFlight_ = false;
    bool held_ = false;
    std::optional<double> lastBackground_;
    bool granting_ = false;
    std::optional<double> grantAt_;
};

class HapticGate {
public:
    enum class Decision { Play, Drop, Report };
    Decision decide(double nowMs, bool visible);
    void press(double nowMs) { down_ = nowMs; }
    void release(double nowMs) {
        down_.reset();
        lifted_ = nowMs;
    }
    static std::string_view name(Decision d) { return d == Decision::Play ? "play" : d == Decision::Drop ? "drop" : "report"; }

private:
    double tokens_ = HAPTIC_BURST;
    std::optional<double> last_;
    std::optional<double> reported_;
    std::optional<double> down_;
    std::optional<double> lifted_;
    std::optional<double> series_;
};

std::map<std::string, std::string> checkThemeParams(const Json& params);
std::map<std::string, std::string> checkThemeParams(const std::map<std::string, std::string>& params);
Json themeParamsJson(const std::map<std::string, std::string>& checked);

std::optional<std::string> checkLocale(std::string_view locale);
std::optional<std::string> checkLocaleValue(const Json& locale);

size_t storageBytes(const StorageArea& area);

std::optional<StorageArea> checkStorageArea(const Json& area);
bool storageAreaFits(const StorageArea& area);

struct StorageCommit {
    std::optional<StorageArea> local;
    std::optional<StorageArea> session;
    Json toJson() const;
};

std::optional<StorageCommit> checkStorageCommit(const Json& commit);

Json storagePreloadJson(const WidgetStorage& storage, bool* accepted = nullptr);

}
}
