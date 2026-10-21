/* Modifications Copyright (C) 2026 Ettacent */

#pragma once

#include <algorithm>
#include <bit>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <exception>
#include <functional>
#include <iostream>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <streambuf>
#include <type_traits>
#include <variant>
#include <vector>

#include "wallet_engine_scaffolding.hpp"

#ifndef UNIFFI_CPP_RUST_STREAM
#define UNIFFI_CPP_RUST_STREAM
namespace uniffi {
struct RustStreamBuffer: std::basic_streambuf<char> {
    RustStreamBuffer(RustBuffer *buf) {
        char* data = reinterpret_cast<char*>(buf->data);
        this->setg(data, data, data + buf->len);
        this->setp(data, data + buf->capacity);
    }
    ~RustStreamBuffer() = default;

private:
    RustStreamBuffer() = delete;
    RustStreamBuffer(const RustStreamBuffer &) = delete;
    RustStreamBuffer(RustStreamBuffer &&) = delete;

    RustStreamBuffer &operator=(const RustStreamBuffer &) = delete;
    RustStreamBuffer &operator=(RustStreamBuffer &&) = delete;
};

struct RustStream: std::basic_iostream<char> {
    RustStream(RustBuffer *buf):
        std::basic_iostream<char>(&streambuf), streambuf(RustStreamBuffer(buf)) { }

    template <typename T, typename = std::enable_if_t<std::is_arithmetic_v<T>>>
    RustStream &operator>>(T &val) {
        read(reinterpret_cast<char *>(&val), sizeof(T));

        if (std::endian::native != std::endian::big) {
            auto bytes = reinterpret_cast<char *>(&val);

            std::reverse(bytes, bytes + sizeof(T));
        }

        return *this;
    }

    template <typename T, typename = std::enable_if_t<std::is_arithmetic_v<T>>>
    RustStream &operator<<(T val) {
        if (std::endian::native != std::endian::big) {
            auto bytes = reinterpret_cast<char *>(&val);

            std::reverse(bytes, bytes + sizeof(T));
        }

        write(reinterpret_cast<char *>(&val), sizeof(T));

        return *this;
    }
private:
    RustStreamBuffer streambuf;
};

}
#endif

namespace wallet_engine {
struct TonConnectDerivedSession;
struct TonConnectSession;
struct WalletClient;
struct WalletHttpHost;
struct WalletLifecycle;
struct WalletPlatformHost;
struct WalletStatuslessHost;
struct AccountSnapshot;
struct ActivityCursor;
struct ActivityItem;
struct ActivityList;
struct CreateEncryptedCommentRequest;
struct CreateWalletRequest;
struct CreatedWallet;
struct DecryptCommentRequest;
struct DomainError;
struct EncryptedCommentRecipientRequest;
struct HttpHeader;
struct HttpRequest;
struct HttpRequestId;
struct HttpResponse;
struct ImportWalletRequest;
struct JournalCompareExchange;
struct JournalCompareExchangeResult;
struct JournalKey;
struct JournalRecord;
struct NftCollectionDescriptor;
struct NftItem;
struct NftList;
struct NftTransferIntent;
struct NftTransferPreviewRequest;
struct NftTransferRequest;
struct ParsedTonTransferLink;
struct PrepareKeyRotationRequest;
struct PrepareNftTransferRequest;
struct PrepareTransferRequest;
struct PreparedKeyRotation;
struct PreparedTransfer;
struct ProtectedSecretRead;
struct ProtectedSecretRef;
struct ProtectedSecretStore;
struct ProviderConfig;
struct RecoveryPhrase;
struct ResolutionInfo;
struct ResourceState;
struct SendBocRequest;
struct SendEmulation;
struct SendEmulationAction;
struct SendEmulationTransaction;
struct SendIntent;
struct SendMessage;
struct SendPreview;
struct SendPreviewRequest;
struct SendRequest;
struct SendResult;
struct SendSnapshot;
struct SignMessagePreview;
struct SignMessageRequest;
struct SignMessageResult;
struct TonAddressInfo;
struct TonConnectAccountInfo;
struct TonConnectConnectPrompt;
struct TonConnectDerivedRequest;
struct TonConnectDerivedSessionRequest;
struct TonConnectDevice;
struct TonConnectManifest;
struct TonConnectPreparedPost;
struct TonConnectProofReply;
struct TonConnectProofSignRequest;
struct TonConnectProofSignature;
struct TonConnectSessionConfig;
struct TonConnectSignDataRequest;
struct TonConnectSignDataSignRequest;
struct TonConnectSignedData;
struct WalletClientConfig;
struct WalletDescriptor;
struct WalletSnapshot;
struct WalletUpdate;
enum class AccountStatus;
enum class ActivityDirection;
enum class ActivityStatus;
enum class ErrorCategory;
enum class ErrorCode;
struct HttpHostError;
enum class HttpHostErrorKind;
enum class HttpMethod;
struct JournalHostError;
enum class JournalHostErrorKind;
enum class KeyRotationMessageKind;
enum class MnemonicScheme;
enum class Network;
struct NftTransferFunding;
struct NftTransferPayload;
enum class PendingReason;
struct ProtectedSecretHostError;
enum class ProtectedSecretHostErrorKind;
enum class ResourcePhase;
enum class RetryAdvice;
enum class SecretAccessReason;
struct SendAmount;
struct SendExpiration;
struct SendMessageBody;
enum class SendPhase;
struct StatuslessHostError;
enum class StatuslessHostErrorKind;
struct TonAddressError;
struct TonAddressFormat;
enum class TonConnectConnectErrorCode;
enum class TonConnectDevicePlatform;
struct TonConnectIncomingRequest;
enum class TonConnectRpcErrorCode;
struct TonConnectSessionError;
enum class TonConnectSessionPhase;
struct TonConnectSignDataPayload;
struct TonTransferAsset;
struct TonTransferLinkError;
struct TonTransferPayload;
struct WalletClientError;
struct WalletLifecycleError;
enum class WalletOperationOutcome;
typedef std::string Base64Hash;
typedef std::string Boc;
typedef std::string NonEmptyString;
typedef std::string TonAddressString;
typedef std::string UnsignedDecimalString;


enum class AccountStatus: int32_t {
    kNonexistent = 1,
    kUninitialized = 2,
    kActive = 3,
    kFrozen = 4,
    kUnknown = 5
};


enum class WalletOperationOutcome: int32_t {
    kCompleted = 1,
    kPartiallyCompleted = 2,
    kFailed = 3,
    kCancelled = 4,
    kSuperseded = 5,
    kSkipped = 6
};


enum class HttpMethod: int32_t {
    kGet = 1,
    kPost = 2
};


enum class HttpHostErrorKind: int32_t {
    kOffline = 1,
    kTimeout = 2,
    kConnectionLost = 3,
    kDns = 4,
    kTls = 5,
    kPolicyViolation = 6,
    kResponseTooLarge = 7,
    kCancelled = 8,
    kOther = 9
};


enum class ProtectedSecretHostErrorKind: int32_t {
    kNotFound = 1,
    kAuthenticationFailed = 2,
    kCancelled = 3,
    kUnavailable = 4,
    kPolicyViolation = 5,
    kOther = 6
};

namespace uniffi {
struct FfiConverterTonAddressFormat;
}

struct TonAddressFormat {
    friend uniffi::FfiConverterTonAddressFormat;
    struct kRaw {
    };
    struct kUserFriendly {
        bool bounceable;
        bool testnet;
    };
    TonAddressFormat(kRaw variant): variant(variant) {}
    TonAddressFormat(kUserFriendly variant): variant(variant) {}

    TonAddressFormat(const TonAddressFormat &other): variant(other.variant) {}
    TonAddressFormat(TonAddressFormat &&other): variant(std::move(other.variant)) {}

    TonAddressFormat &operator=(const TonAddressFormat &other) {
        variant = other.variant;
        return *this;
    }

    TonAddressFormat &operator=(TonAddressFormat &&other) {
        variant = std::move(other.variant);
        return *this;
    }

    const std::variant<kRaw, kUserFriendly> &get_variant() const {
        return variant;
    }

private:
    std::variant<kRaw, kUserFriendly> variant;

    TonAddressFormat();
};


enum class TonConnectRpcErrorCode: int32_t {
    kUnknown = 1,
    kBadRequest = 2,
    kUnknownApp = 3,
    kUserDeclined = 4,
    kMethodNotSupported = 5
};


struct ProtectedSecretRef {
    std::string value;
};


struct HttpRequestId {
    uint64_t value;
};


struct HttpHeader {
    std::string name;
    std::string value;
};


enum class KeyRotationMessageKind: int32_t {
    kExternal = 1,
    kInternal = 2
};


struct RecoveryPhrase {
    std::string phrase;
};


enum class TonConnectDevicePlatform: int32_t {
    kIphone = 1,
    kIpad = 2,
    kAndroid = 3,
    kWindows = 4,
    kMac = 5,
    kLinux = 6,
    kBrowser = 7
};


enum class ActivityDirection: int32_t {
    kSent = 1,
    kReceived = 2
};


enum class SendPhase: int32_t {
    kIdle = 1,
    kValidating = 2,
    kAuthorizing = 3,
    kPreparing = 4,
    kPersisting = 5,
    kReadyToSubmit = 6,
    kSubmitting = 7,
    kSubmissionUnknown = 8,
    kSubmitted = 9,
    kHandedOff = 10,
    kConfirmed = 11,
    kReplaced = 12,
    kSequenceNumberConsumed = 13,
    kExpired = 14,
    kSuperseded = 15,
    kFailed = 16,
    kCancelled = 17
};


enum class JournalHostErrorKind: int32_t {
    kUnavailable = 1,
    kCorruptData = 2,
    kCancelled = 3,
    kOther = 4
};

namespace uniffi {
struct FfiConverterSendExpiration;
}

struct SendExpiration {
    friend uniffi::FfiConverterSendExpiration;
    struct kEngineDefault {
    };
    struct kExact {
        uint64_t unix_timestamp;
    };
    SendExpiration(kEngineDefault variant): variant(variant) {}
    SendExpiration(kExact variant): variant(variant) {}

    SendExpiration(const SendExpiration &other): variant(other.variant) {}
    SendExpiration(SendExpiration &&other): variant(std::move(other.variant)) {}

    SendExpiration &operator=(const SendExpiration &other) {
        variant = other.variant;
        return *this;
    }

    SendExpiration &operator=(SendExpiration &&other) {
        variant = std::move(other.variant);
        return *this;
    }

    const std::variant<kEngineDefault, kExact> &get_variant() const {
        return variant;
    }

private:
    std::variant<kEngineDefault, kExact> variant;

    SendExpiration();
};


enum class StatuslessHostErrorKind: int32_t {
    kOffline = 1,
    kTimeout = 2,
    kConnectionLost = 3,
    kPolicyViolation = 4,
    kResponseTooLarge = 5,
    kCancelled = 6,
    kOther = 7
};

namespace uniffi {
struct FfiConverterTonConnectSignDataPayload;
}

struct TonConnectSignDataPayload {
    friend uniffi::FfiConverterTonConnectSignDataPayload;
    struct kText {
        std::string text;
    };
    struct kBinary {
        std::string bytes;
    };
    struct kCell {
        std::string schema;
        std::string cell;
    };
    TonConnectSignDataPayload(kText variant): variant(variant) {}
    TonConnectSignDataPayload(kBinary variant): variant(variant) {}
    TonConnectSignDataPayload(kCell variant): variant(variant) {}

    TonConnectSignDataPayload(const TonConnectSignDataPayload &other): variant(other.variant) {}
    TonConnectSignDataPayload(TonConnectSignDataPayload &&other): variant(std::move(other.variant)) {}

    TonConnectSignDataPayload &operator=(const TonConnectSignDataPayload &other) {
        variant = other.variant;
        return *this;
    }

    TonConnectSignDataPayload &operator=(TonConnectSignDataPayload &&other) {
        variant = std::move(other.variant);
        return *this;
    }

    const std::variant<kText, kBinary, kCell> &get_variant() const {
        return variant;
    }

private:
    std::variant<kText, kBinary, kCell> variant;

    TonConnectSignDataPayload();
};


enum class ErrorCode: int32_t {
    kInvalidProviderResponse = 1,
    kHttpRejected = 2,
    kRateLimited = 3,
    kTransportFailed = 4,
    kHostCancelled = 5,
    kResponseTooLarge = 6,
    kHostPolicyViolation = 7
};


enum class RetryAdvice: int32_t {
    kNone = 1,
    kSafe = 2,
    kAfterDelay = 3
};


enum class Network: int32_t {
    kMainnet = 1,
    kTestnet = 2
};


enum class SecretAccessReason: int32_t {
    kCreateWallet = 1,
    kSignTransfer = 2,
    kSignTonConnectProof = 3,
    kPrepareKeyRotation = 4,
    kEncryptComment = 5,
    kDecryptComment = 6,
    kRevealRecoveryPhrase = 7,
    kDeriveTonConnectSessionKey = 8,
    kSignTonConnectData = 9
};


struct JournalRecord {
    uint64_t version;
    std::vector<uint8_t> payload;
};


struct JournalKey {
    std::string record_id;
    std::string slot;
};


enum class PendingReason: int32_t {
    kInMempool = 1,
    kAwaitingWindow = 2
};


enum class ResourcePhase: int32_t {
    kIdle = 1,
    kLoading = 2,
    kReady = 3,
    kFailed = 4
};


enum class ActivityStatus: int32_t {
    kSuccess = 1,
    kFailed = 2,
    kBounced = 3
};


enum class ErrorCategory: int32_t {
    kTransport = 1,
    kProviderProtocol = 2,
    kRateLimit = 3,
    kCancellation = 4,
    kHostPolicy = 5
};


struct ProtectedSecretStore {
    ProtectedSecretRef secret_ref;
    std::vector<uint8_t> bytes;
    bool require_user_presence;
};


struct SendBocRequest {
    NonEmptyString operation_id;
    bool force;
    Boc signed_boc;
    uint32_t seqno;
    uint64_t valid_until;
};


struct AccountSnapshot {
    UnsignedDecimalString balance_nanograms;
    AccountStatus status;
    uint64_t sync_utime;
};


struct ProtectedSecretRead {
    ProtectedSecretRef secret_ref;
    SecretAccessReason reason;
    std::string prompt;
};


struct TonAddressInfo {
    std::string raw;
    int32_t workchain;
    TonAddressFormat format;
};


struct ActivityItem {
    std::string id;
    Base64Hash transaction_hash;
    UnsignedDecimalString logical_time;
    uint64_t timestamp;
    ActivityDirection direction;
    UnsignedDecimalString amount_nanograms;
    UnsignedDecimalString transaction_fee_nanograms;
    ActivityStatus status;
    std::optional<std::string> comment;
    std::optional<Boc> encrypted_comment;
    std::optional<TonAddressString> counterparty;
};


struct JournalCompareExchange {
    JournalKey key;
    std::optional<uint64_t> expected_version;
    JournalRecord replacement;
};


struct CreateEncryptedCommentRequest {
    TonAddressString recipient;
    std::string comment;
    std::optional<std::vector<uint8_t>> recipient_public_key = std::nullopt;
};


struct DecryptCommentRequest {
    TonAddressString sender;
    Boc body;
};


struct ActivityCursor {
    UnsignedDecimalString logical_time;
    Base64Hash hash;
};

namespace uniffi {
struct FfiConverterTonTransferPayload;
}

struct TonTransferPayload {
    friend uniffi::FfiConverterTonTransferPayload;
    struct kNone {
    };
    struct kText {
        std::string text;
    };
    struct kBoc {
        Boc boc;
    };
    TonTransferPayload(kNone variant): variant(variant) {}
    TonTransferPayload(kText variant): variant(variant) {}
    TonTransferPayload(kBoc variant): variant(variant) {}

    TonTransferPayload(const TonTransferPayload &other): variant(other.variant) {}
    TonTransferPayload(TonTransferPayload &&other): variant(std::move(other.variant)) {}

    TonTransferPayload &operator=(const TonTransferPayload &other) {
        variant = other.variant;
        return *this;
    }

    TonTransferPayload &operator=(TonTransferPayload &&other) {
        variant = std::move(other.variant);
        return *this;
    }

    const std::variant<kNone, kText, kBoc> &get_variant() const {
        return variant;
    }

private:
    std::variant<kNone, kText, kBoc> variant;

    TonTransferPayload();
};


struct SendEmulationAction {
    Base64Hash action_id;
    NonEmptyString kind;
    bool succeeded;
    std::vector<TonAddressString> accounts;
    std::vector<Base64Hash> transaction_hashes;
    std::string details_json;
};


struct HttpRequest {
    HttpRequestId id;
    HttpMethod method;
    std::string url;
    std::vector<HttpHeader> headers;
    std::vector<uint8_t> body;
    uint64_t timeout_ms;
};

namespace uniffi {
struct FfiConverterNftTransferPayload;
}

struct NftTransferPayload {
    friend uniffi::FfiConverterNftTransferPayload;
    struct kEmpty {
    };
    struct kComment {
        std::string text;
    };
    struct kRawPayload {
        Boc boc;
    };
    NftTransferPayload(kEmpty variant): variant(variant) {}
    NftTransferPayload(kComment variant): variant(variant) {}
    NftTransferPayload(kRawPayload variant): variant(variant) {}

    NftTransferPayload(const NftTransferPayload &other): variant(other.variant) {}
    NftTransferPayload(NftTransferPayload &&other): variant(std::move(other.variant)) {}

    NftTransferPayload &operator=(const NftTransferPayload &other) {
        variant = other.variant;
        return *this;
    }

    NftTransferPayload &operator=(NftTransferPayload &&other) {
        variant = std::move(other.variant);
        return *this;
    }

    const std::variant<kEmpty, kComment, kRawPayload> &get_variant() const {
        return variant;
    }

private:
    std::variant<kEmpty, kComment, kRawPayload> variant;

    NftTransferPayload();
};


struct PreparedTransfer {
    NonEmptyString operation_id;
    Boc external_boc;
    Boc internal_boc;
    uint32_t seqno;
    uint64_t valid_until;
};

namespace uniffi {
struct FfiConverterTonTransferAsset;
}

struct TonTransferAsset {
    friend uniffi::FfiConverterTonTransferAsset;
    struct kGram {
    };
    struct kJetton {
        TonAddressString master;
    };
    TonTransferAsset(kGram variant): variant(variant) {}
    TonTransferAsset(kJetton variant): variant(variant) {}

    TonTransferAsset(const TonTransferAsset &other): variant(other.variant) {}
    TonTransferAsset(TonTransferAsset &&other): variant(std::move(other.variant)) {}

    TonTransferAsset &operator=(const TonTransferAsset &other) {
        variant = other.variant;
        return *this;
    }

    TonTransferAsset &operator=(TonTransferAsset &&other) {
        variant = std::move(other.variant);
        return *this;
    }

    const std::variant<kGram, kJetton> &get_variant() const {
        return variant;
    }

private:
    std::variant<kGram, kJetton> variant;

    TonTransferAsset();
};


struct TonConnectDevice {
    TonConnectDevicePlatform platform;
    std::string app_name;
    std::string app_version;
};


struct PrepareKeyRotationRequest {
    uint64_t valid_until;
    KeyRotationMessageKind message_kind;
};


struct ImportWalletRequest {
    std::string record_id;
    Network network;
    std::vector<std::string> recovery_words;
};


struct SendResult {
    NonEmptyString operation_id;
    Base64Hash message_hash;
    Boc signed_boc;
    SendPhase phase;
};


struct NftCollectionDescriptor {
    TonAddressString address;
    std::optional<std::string> name;
    std::optional<std::string> description;
    std::optional<std::string> image;
    std::unordered_map<std::string, std::string> content;
};


struct HttpResponse {
    uint16_t status;
    std::vector<HttpHeader> headers;
    std::vector<uint8_t> body;
    std::string final_url;
};

namespace uniffi {
struct FfiConverterSendMessageBody;
}

struct SendMessageBody {
    friend uniffi::FfiConverterSendMessageBody;
    struct kEmpty {
    };
    struct kComment {
        std::string text;
    };
    struct kRawPayload {
        Boc boc;
    };
    SendMessageBody(kEmpty variant): variant(variant) {}
    SendMessageBody(kComment variant): variant(variant) {}
    SendMessageBody(kRawPayload variant): variant(variant) {}

    SendMessageBody(const SendMessageBody &other): variant(other.variant) {}
    SendMessageBody(SendMessageBody &&other): variant(std::move(other.variant)) {}

    SendMessageBody &operator=(const SendMessageBody &other) {
        variant = other.variant;
        return *this;
    }

    SendMessageBody &operator=(SendMessageBody &&other) {
        variant = std::move(other.variant);
        return *this;
    }

    const std::variant<kEmpty, kComment, kRawPayload> &get_variant() const {
        return variant;
    }

private:
    std::variant<kEmpty, kComment, kRawPayload> variant;

    SendMessageBody();
};

namespace uniffi {
struct FfiConverterProtectedSecretHostError;
}

struct ProtectedSecretHostError: std::runtime_error {
    friend uniffi::FfiConverterProtectedSecretHostError;

    ProtectedSecretHostError() : std::runtime_error("") {}
    ProtectedSecretHostError(const std::string &what_arg) : std::runtime_error(what_arg) {}

    virtual ~ProtectedSecretHostError() = default;

    virtual void throw_underlying() {
        throw *this;
    }

protected:
    virtual int32_t get_variant_idx() const {
        return 0;
    };
};
namespace protected_secret_host_error {

struct Failed: ProtectedSecretHostError {
    ProtectedSecretHostErrorKind kind;
    std::string diagnostic;

    Failed() : ProtectedSecretHostError("") {}
    Failed(const std::string &what_arg) : ProtectedSecretHostError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 1;
    }
};
}


struct ProviderConfig {
    std::string toncenter_base_url;
    std::optional<TonAddressString> dns_root_address;
    uint64_t request_timeout_ms;
};


struct CreateWalletRequest {
    std::string record_id;
    Network network;
};

namespace uniffi {
struct FfiConverterWalletClientError;
}

struct WalletClientError: std::runtime_error {
    friend uniffi::FfiConverterWalletClientError;

    WalletClientError() : std::runtime_error("") {}
    WalletClientError(const std::string &what_arg) : std::runtime_error(what_arg) {}

    virtual ~WalletClientError() = default;

    virtual void throw_underlying() {
        throw *this;
    }

protected:
    virtual int32_t get_variant_idx() const {
        return 0;
    };
};
namespace wallet_client_error {

struct InvalidLocalSecretReference: WalletClientError {

    InvalidLocalSecretReference() : WalletClientError("") {}
    InvalidLocalSecretReference(const std::string &what_arg) : WalletClientError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 1;
    }
};

struct InvalidWalletPublicKey: WalletClientError {

    InvalidWalletPublicKey() : WalletClientError("") {}
    InvalidWalletPublicKey(const std::string &what_arg) : WalletClientError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 2;
    }
};

struct WalletIdentityMismatch: WalletClientError {

    WalletIdentityMismatch() : WalletClientError("") {}
    WalletIdentityMismatch(const std::string &what_arg) : WalletClientError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 3;
    }
};

struct InvalidProviderBaseUrl: WalletClientError {

    InvalidProviderBaseUrl() : WalletClientError("") {}
    InvalidProviderBaseUrl(const std::string &what_arg) : WalletClientError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 4;
    }
};

struct InvalidSendRequest: WalletClientError {

    InvalidSendRequest() : WalletClientError("") {}
    InvalidSendRequest(const std::string &what_arg) : WalletClientError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 5;
    }
};

struct NftTransferUnavailable: WalletClientError {
    std::string diagnostic;

    NftTransferUnavailable() : WalletClientError("") {}
    NftTransferUnavailable(const std::string &what_arg) : WalletClientError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 6;
    }
};

struct NftTransferEmulationRejected: WalletClientError {
    std::string diagnostic;

    NftTransferEmulationRejected() : WalletClientError("") {}
    NftTransferEmulationRejected(const std::string &what_arg) : WalletClientError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 7;
    }
};

struct EncryptedCommentUnavailable: WalletClientError {
    std::string diagnostic;

    EncryptedCommentUnavailable() : WalletClientError("") {}
    EncryptedCommentUnavailable(const std::string &what_arg) : WalletClientError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 8;
    }
};

struct DnsResolutionUnavailable: WalletClientError {
    std::string diagnostic;

    DnsResolutionUnavailable() : WalletClientError("") {}
    DnsResolutionUnavailable(const std::string &what_arg) : WalletClientError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 9;
    }
};

struct KeyRotationUnavailable: WalletClientError {
    std::string diagnostic;

    KeyRotationUnavailable() : WalletClientError("") {}
    KeyRotationUnavailable(const std::string &what_arg) : WalletClientError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 10;
    }
};

struct LocalSigningUnavailable: WalletClientError {

    LocalSigningUnavailable() : WalletClientError("") {}
    LocalSigningUnavailable(const std::string &what_arg) : WalletClientError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 11;
    }
};

struct IdentifierExhausted: WalletClientError {

    IdentifierExhausted() : WalletClientError("") {}
    IdentifierExhausted(const std::string &what_arg) : WalletClientError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 12;
    }
};

struct StateUnavailable: WalletClientError {

    StateUnavailable() : WalletClientError("") {}
    StateUnavailable(const std::string &what_arg) : WalletClientError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 13;
    }
};

struct SendAlreadyInProgress: WalletClientError {

    SendAlreadyInProgress() : WalletClientError("") {}
    SendAlreadyInProgress(const std::string &what_arg) : WalletClientError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 14;
    }
};

struct SendPreviewAlreadyInProgress: WalletClientError {

    SendPreviewAlreadyInProgress() : WalletClientError("") {}
    SendPreviewAlreadyInProgress(const std::string &what_arg) : WalletClientError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 15;
    }
};

struct PreviousSubmissionUnresolved: WalletClientError {

    PreviousSubmissionUnresolved() : WalletClientError("") {}
    PreviousSubmissionUnresolved(const std::string &what_arg) : WalletClientError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 16;
    }
};

struct WalletSeqnoNotAdvanced: WalletClientError {

    WalletSeqnoNotAdvanced() : WalletClientError("") {}
    WalletSeqnoNotAdvanced(const std::string &what_arg) : WalletClientError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 17;
    }
};

struct SendAccountUnavailable: WalletClientError {
    AccountStatus status;

    SendAccountUnavailable() : WalletClientError("") {}
    SendAccountUnavailable(const std::string &what_arg) : WalletClientError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 18;
    }
};

struct InsufficientBalance: WalletClientError {
    UnsignedDecimalString available_nanograms;
    UnsignedDecimalString requested_nanograms;

    InsufficientBalance() : WalletClientError("") {}
    InsufficientBalance(const std::string &what_arg) : WalletClientError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 19;
    }
};

struct InsufficientBalanceForFees: WalletClientError {
    UnsignedDecimalString available_nanograms;
    UnsignedDecimalString requested_nanograms;
    UnsignedDecimalString estimated_fee_nanograms;

    InsufficientBalanceForFees() : WalletClientError("") {}
    InsufficientBalanceForFees(const std::string &what_arg) : WalletClientError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 20;
    }
};

struct InvalidProtectedSecret: WalletClientError {

    InvalidProtectedSecret() : WalletClientError("") {}
    InvalidProtectedSecret(const std::string &what_arg) : WalletClientError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 21;
    }
};

struct SendPreviewFailed: WalletClientError {
    std::string diagnostic;

    SendPreviewFailed() : WalletClientError("") {}
    SendPreviewFailed(const std::string &what_arg) : WalletClientError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 22;
    }
};

struct EmulationFailed: WalletClientError {
    std::string diagnostic;

    EmulationFailed() : WalletClientError("") {}
    EmulationFailed(const std::string &what_arg) : WalletClientError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 23;
    }
};

struct EmulationMessageNotAccepted: WalletClientError {
    std::string diagnostic;

    EmulationMessageNotAccepted() : WalletClientError("") {}
    EmulationMessageNotAccepted(const std::string &what_arg) : WalletClientError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 24;
    }
};

struct EmulationRejected: WalletClientError {
    std::string diagnostic;
    std::optional<int32_t> compute_exit_code;
    std::optional<int32_t> action_result_code;

    EmulationRejected() : WalletClientError("") {}
    EmulationRejected(const std::string &what_arg) : WalletClientError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 25;
    }
};

struct SendFailed: WalletClientError {
    std::string diagnostic;

    SendFailed() : WalletClientError("") {}
    SendFailed(const std::string &what_arg) : WalletClientError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 26;
    }
};

struct SubmissionUnknown: WalletClientError {
    std::string diagnostic;

    SubmissionUnknown() : WalletClientError("") {}
    SubmissionUnknown(const std::string &what_arg) : WalletClientError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 27;
    }
};

struct SendCancellationTooLate: WalletClientError {

    SendCancellationTooLate() : WalletClientError("") {}
    SendCancellationTooLate(const std::string &what_arg) : WalletClientError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 28;
    }
};

struct Shutdown: WalletClientError {

    Shutdown() : WalletClientError("") {}
    Shutdown(const std::string &what_arg) : WalletClientError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 29;
    }
};

struct EncryptedCommentLookupFailed: WalletClientError {
    std::string diagnostic;

    EncryptedCommentLookupFailed() : WalletClientError("") {}
    EncryptedCommentLookupFailed(const std::string &what_arg) : WalletClientError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 30;
    }
};
}


struct SendEmulationTransaction {
    TonAddressString account;
    bool succeeded;
    bool is_root;
};


struct ResolutionInfo {
    std::optional<Base64Hash> transaction_hash;
    std::optional<UnsignedDecimalString> transaction_lt;
    std::optional<PendingReason> pending_reason;
    bool can_force_retry;
    std::optional<uint64_t> retry_after_hint_ms;
};

namespace uniffi {
struct FfiConverterSendAmount;
}

struct SendAmount {
    friend uniffi::FfiConverterSendAmount;
    struct kExact {
        UnsignedDecimalString nanograms;
    };
    struct kAll {
    };
    SendAmount(kExact variant): variant(variant) {}
    SendAmount(kAll variant): variant(variant) {}

    SendAmount(const SendAmount &other): variant(other.variant) {}
    SendAmount(SendAmount &&other): variant(std::move(other.variant)) {}

    SendAmount &operator=(const SendAmount &other) {
        variant = other.variant;
        return *this;
    }

    SendAmount &operator=(SendAmount &&other) {
        variant = std::move(other.variant);
        return *this;
    }

    const std::variant<kExact, kAll> &get_variant() const {
        return variant;
    }

private:
    std::variant<kExact, kAll> variant;

    SendAmount();
};

namespace uniffi {
struct FfiConverterHttpHostError;
}

struct HttpHostError: std::runtime_error {
    friend uniffi::FfiConverterHttpHostError;

    HttpHostError() : std::runtime_error("") {}
    HttpHostError(const std::string &what_arg) : std::runtime_error(what_arg) {}

    virtual ~HttpHostError() = default;

    virtual void throw_underlying() {
        throw *this;
    }

protected:
    virtual int32_t get_variant_idx() const {
        return 0;
    };
};
namespace http_host_error {

struct Failed: HttpHostError {
    HttpHostErrorKind kind;
    std::string diagnostic;

    Failed() : HttpHostError("") {}
    Failed(const std::string &what_arg) : HttpHostError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 1;
    }
};
}


struct DomainError {
    ErrorCode code;
    ErrorCategory category;
    RetryAdvice retry;
    std::string developer_message;
    std::optional<uint16_t> provider_status;
    std::optional<uint64_t> retry_after_ms;
    std::optional<HttpHostErrorKind> host_kind;
};

namespace uniffi {
struct FfiConverterJournalHostError;
}

struct JournalHostError: std::runtime_error {
    friend uniffi::FfiConverterJournalHostError;

    JournalHostError() : std::runtime_error("") {}
    JournalHostError(const std::string &what_arg) : std::runtime_error(what_arg) {}

    virtual ~JournalHostError() = default;

    virtual void throw_underlying() {
        throw *this;
    }

protected:
    virtual int32_t get_variant_idx() const {
        return 0;
    };
};
namespace journal_host_error {

struct Failed: JournalHostError {
    JournalHostErrorKind kind;
    std::string diagnostic;

    Failed() : JournalHostError("") {}
    Failed(const std::string &what_arg) : JournalHostError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 1;
    }
};
}


struct TonConnectSignDataRequest {
    TonConnectSignDataPayload payload;
    std::optional<std::string> network;
    std::optional<std::string> from;
};


struct WalletDescriptor {
    std::string record_id;
    TonAddressString address;
    std::vector<uint8_t> public_key;
    Network network;
    ProtectedSecretRef secret_ref;
};


struct SignMessageResult {
    NonEmptyString operation_id;
    Boc internal_boc;
    uint64_t valid_until;
    SendPhase phase;
};


struct JournalCompareExchangeResult {
    bool applied;
    std::optional<JournalRecord> current;
};

namespace uniffi {
struct FfiConverterStatuslessHostError;
}

struct StatuslessHostError: std::runtime_error {
    friend uniffi::FfiConverterStatuslessHostError;

    StatuslessHostError() : std::runtime_error("") {}
    StatuslessHostError(const std::string &what_arg) : std::runtime_error(what_arg) {}

    virtual ~StatuslessHostError() = default;

    virtual void throw_underlying() {
        throw *this;
    }

protected:
    virtual int32_t get_variant_idx() const {
        return 0;
    };
};
namespace statusless_host_error {

struct Failed: StatuslessHostError {
    StatuslessHostErrorKind kind;
    std::string diagnostic;

    Failed() : StatuslessHostError("") {}
    Failed(const std::string &what_arg) : StatuslessHostError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 1;
    }
};
}

namespace uniffi {
struct FfiConverterWalletLifecycleError;
}

struct WalletLifecycleError: std::runtime_error {
    friend uniffi::FfiConverterWalletLifecycleError;

    WalletLifecycleError() : std::runtime_error("") {}
    WalletLifecycleError(const std::string &what_arg) : std::runtime_error(what_arg) {}

    virtual ~WalletLifecycleError() = default;

    virtual void throw_underlying() {
        throw *this;
    }

protected:
    virtual int32_t get_variant_idx() const {
        return 0;
    };
};
namespace wallet_lifecycle_error {

struct InvalidRecordId: WalletLifecycleError {

    InvalidRecordId() : WalletLifecycleError("") {}
    InvalidRecordId(const std::string &what_arg) : WalletLifecycleError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 1;
    }
};

struct InvalidRecoveryPhrase: WalletLifecycleError {

    InvalidRecoveryPhrase() : WalletLifecycleError("") {}
    InvalidRecoveryPhrase(const std::string &what_arg) : WalletLifecycleError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 2;
    }
};

struct AddressDerivationFailed: WalletLifecycleError {

    AddressDerivationFailed() : WalletLifecycleError("") {}
    AddressDerivationFailed(const std::string &what_arg) : WalletLifecycleError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 3;
    }
};

struct TonConnectSigningFailed: WalletLifecycleError {

    TonConnectSigningFailed() : WalletLifecycleError("") {}
    TonConnectSigningFailed(const std::string &what_arg) : WalletLifecycleError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 4;
    }
};

struct SecretWalletMismatch: WalletLifecycleError {

    SecretWalletMismatch() : WalletLifecycleError("") {}
    SecretWalletMismatch(const std::string &what_arg) : WalletLifecycleError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 5;
    }
};

struct ProtectedSecretHost: WalletLifecycleError {
    ProtectedSecretHostErrorKind kind;
    std::string diagnostic;

    ProtectedSecretHost() : WalletLifecycleError("") {}
    ProtectedSecretHost(const std::string &what_arg) : WalletLifecycleError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 6;
    }
};

struct InvalidTonConnectSessionInput: WalletLifecycleError {

    InvalidTonConnectSessionInput() : WalletLifecycleError("") {}
    InvalidTonConnectSessionInput(const std::string &what_arg) : WalletLifecycleError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 7;
    }
};
}

namespace uniffi {
struct FfiConverterNftTransferFunding;
}

struct NftTransferFunding {
    friend uniffi::FfiConverterNftTransferFunding;
    struct kExact {
        UnsignedDecimalString attached_nanograms;
        UnsignedDecimalString forward_nanograms;
    };
    NftTransferFunding(kExact variant): variant(variant) {}

    NftTransferFunding(const NftTransferFunding &other): variant(other.variant) {}
    NftTransferFunding(NftTransferFunding &&other): variant(std::move(other.variant)) {}

    NftTransferFunding &operator=(const NftTransferFunding &other) {
        variant = other.variant;
        return *this;
    }

    NftTransferFunding &operator=(NftTransferFunding &&other) {
        variant = std::move(other.variant);
        return *this;
    }

    const std::variant<kExact> &get_variant() const {
        return variant;
    }

private:
    std::variant<kExact> variant;

    NftTransferFunding();
};


struct PreparedKeyRotation {
    RecoveryPhrase replacement_recovery_phrase;
    std::vector<uint8_t> new_public_key;
    Boc signed_boc;
    Boc external_boc;
    Boc internal_boc;
    uint32_t seqno;
    uint64_t valid_until;
    KeyRotationMessageKind message_kind;
};


struct EncryptedCommentRecipientRequest {
    TonAddressString recipient;
    std::optional<std::vector<uint8_t>> recipient_public_key = std::nullopt;
};


struct TonConnectSignDataSignRequest {
    WalletDescriptor descriptor;
    TonConnectSignDataRequest request;
    std::string domain;
    uint64_t timestamp;
};


struct CreatedWallet {
    WalletDescriptor descriptor;
    RecoveryPhrase recovery_phrase;
};


struct NftItem {
    TonAddressString address;
    std::optional<TonAddressString> collection_address;
    std::optional<NftCollectionDescriptor> collection;
    std::optional<TonAddressString> owner_address;
    std::optional<TonAddressString> real_owner;
    std::optional<TonAddressString> sale_contract_address;
    std::optional<TonAddressString> auction_contract_address;
    UnsignedDecimalString index;
    UnsignedDecimalString last_transaction_lt;
    bool initialized;
    bool on_sale;
    std::string code_hash;
    std::string data_hash;
    std::unordered_map<std::string, std::string> content;
    std::optional<bool> is_nsfw;
    std::optional<bool> is_scam;
};


struct TonConnectSignedData {
    TonConnectSignDataRequest request;
    std::string domain;
    uint64_t timestamp;
    std::vector<uint8_t> signature;
    std::vector<uint8_t> public_key;
};


struct SendMessage {
    TonAddressString destination;
    SendAmount amount;
    SendMessageBody body;
    bool bounce;
    std::optional<Boc> state_init;
};


struct ResourceState {
    ResourcePhase phase;
    std::optional<DomainError> error;
};


struct SendSnapshot {
    std::optional<NonEmptyString> operation_id;
    SendPhase phase;
    std::optional<std::string> error_message;
    std::optional<ResolutionInfo> resolution;
};


struct ParsedTonTransferLink {
    TonAddressString recipient;
    TonTransferAsset asset;
    std::optional<UnsignedDecimalString> amount;
    TonTransferPayload payload;
    SendExpiration expiration;
};


struct TonConnectDerivedSessionRequest {
    WalletDescriptor descriptor;
    std::string dapp_client_id;
    std::vector<uint8_t> nonce;
};


struct TonConnectProofSignRequest {
    WalletDescriptor descriptor;
    std::string domain;
    uint64_t timestamp;
    std::string payload;
};


struct WalletClientConfig {
    NonEmptyString record_id;
    TonAddressString address;
    std::vector<uint8_t> public_key;
    std::optional<ProtectedSecretRef> local_secret_ref;
    Network network;
    uint64_t send_validity_seconds;
    uint64_t resolution_margin_seconds;
    ProviderConfig providers;
};


struct SendEmulation {
    uint32_t mc_block_seqno;
    UnsignedDecimalString wallet_fees_nanograms;
    UnsignedDecimalString trace_fees_nanograms;
    uint64_t transaction_count;
    std::vector<SendEmulationAction> actions;
    std::vector<SendEmulationTransaction> transactions;
    bool trace_succeeded;
    bool is_incomplete;
};


struct NftTransferIntent {
    TonAddressString nft_address;
    TonAddressString recipient;
    NftTransferFunding funding;
    NftTransferPayload payload;
    SendExpiration expiration;
};


struct NftTransferPreviewRequest {
    NonEmptyString operation_id;
    NftTransferIntent intent;
};


struct SendIntent {
    SendExpiration expiration;
    std::vector<SendMessage> messages;
};


struct SignMessagePreview {
    std::vector<SendMessage> messages;
    uint64_t valid_until;
    bool needs_state_init;
};


struct ActivityList {
    std::vector<ActivityItem> items;
    ResourceState resource;
    ResourceState pagination_resource;
    bool has_more;
};


struct NftTransferRequest {
    NonEmptyString operation_id;
    bool force;
    NftTransferIntent intent;
};


struct PrepareNftTransferRequest {
    NonEmptyString operation_id;
    NftTransferIntent intent;
};


struct NftList {
    std::vector<NftItem> items;
    ResourceState resource;
    ResourceState pagination_resource;
    bool has_more;
};


struct SendPreview {
    std::vector<SendMessage> messages;
    uint64_t valid_until;
    Boc message_boc_base64;
    SendEmulation emulation;
};


struct PrepareTransferRequest {
    NonEmptyString operation_id;
    SendIntent intent;
};


struct SendPreviewRequest {
    SendIntent intent;
};


struct WalletSnapshot {
    uint64_t revision;
    NonEmptyString record_id;
    TonAddressString address;
    Network network;
    std::optional<AccountSnapshot> account;
    ResourceState account_resource;
    ActivityList activity;
    std::optional<ActivityCursor> activity_cursor;
    NftList nfts;
    SendSnapshot send;
};


struct SignMessageRequest {
    NonEmptyString operation_id;
    bool force;
    SendIntent intent;
};


struct SendRequest {
    NonEmptyString operation_id;
    bool force;
    SendIntent intent;
};


struct WalletUpdate {
    WalletOperationOutcome outcome;
    uint64_t activity_items_added;
    uint64_t nft_items_added;
    WalletSnapshot snapshot;
};

namespace uniffi {
struct FfiConverterTonConnectIncomingRequest;
}

struct TonConnectIncomingRequest {
    friend uniffi::FfiConverterTonConnectIncomingRequest;
    struct kSendTransaction {
        std::string id;
        std::string method;
        SendRequest request;
    };
    struct kSignMessage {
        std::string id;
        std::string method;
        SignMessageRequest request;
    };
    struct kDisconnect {
        std::string id;
        std::string method;
    };
    struct kUnsupported {
        std::string id;
        std::string method;
        TonConnectRpcErrorCode error_code;
        std::string error_message;
    };
    struct kSignData {
        std::string id;
        std::string method;
        TonConnectSignDataRequest request;
    };
    TonConnectIncomingRequest(kSendTransaction variant): variant(variant) {}
    TonConnectIncomingRequest(kSignMessage variant): variant(variant) {}
    TonConnectIncomingRequest(kDisconnect variant): variant(variant) {}
    TonConnectIncomingRequest(kUnsupported variant): variant(variant) {}
    TonConnectIncomingRequest(kSignData variant): variant(variant) {}

    TonConnectIncomingRequest(const TonConnectIncomingRequest &other): variant(other.variant) {}
    TonConnectIncomingRequest(TonConnectIncomingRequest &&other): variant(std::move(other.variant)) {}

    TonConnectIncomingRequest &operator=(const TonConnectIncomingRequest &other) {
        variant = other.variant;
        return *this;
    }

    TonConnectIncomingRequest &operator=(TonConnectIncomingRequest &&other) {
        variant = std::move(other.variant);
        return *this;
    }

    const std::variant<kSendTransaction, kSignMessage, kDisconnect, kUnsupported, kSignData> &get_variant() const {
        return variant;
    }

private:
    std::variant<kSendTransaction, kSignMessage, kDisconnect, kUnsupported, kSignData> variant;

    TonConnectIncomingRequest();
};


struct TonConnectDerivedRequest {
    TonConnectIncomingRequest request;
    std::optional<int64_t> request_id;
};


namespace uniffi {
    struct FfiConverterTonConnectDerivedSession;
}

struct TonConnectDerivedSession



{
    friend uniffi::FfiConverterTonConnectDerivedSession;

    TonConnectDerivedSession() = delete;

    TonConnectDerivedSession(TonConnectDerivedSession &&) = delete;

    TonConnectDerivedSession &operator=(const TonConnectDerivedSession &) = delete;
    TonConnectDerivedSession &operator=(TonConnectDerivedSession &&) = delete;

    ~TonConnectDerivedSession();
    std::vector<uint8_t> decrypt_envelope(const std::vector<uint8_t> &body);
    TonConnectDerivedRequest decrypt_request(const std::vector<uint8_t> &body, uint64_t now);
    std::vector<uint8_t> encrypt_connect_error(uint64_t event_id, const TonConnectConnectErrorCode &code, const std::string &message);
    std::vector<uint8_t> encrypt_connect_event(uint64_t event_id, const TonConnectAccountInfo &account, std::optional<TonConnectProofReply> proof, const TonConnectDevice &device);
    std::vector<uint8_t> encrypt_disconnect_event(uint64_t event_id);
    std::vector<uint8_t> encrypt_disconnect_success(const std::string &request_id);
    std::vector<uint8_t> encrypt_envelope(const std::vector<uint8_t> &body);
    std::vector<uint8_t> encrypt_error(const std::string &request_id, const TonConnectRpcErrorCode &code, const std::string &message);
    std::vector<uint8_t> encrypt_send_success(const std::string &request_id, const std::string &signed_boc);
    std::vector<uint8_t> encrypt_sign_data_success(const std::string &request_id, const TonConnectSignedData &signed_data);
    std::vector<uint8_t> open_challenge(const std::vector<uint8_t> &challenge);
    std::string public_key_hex();
    std::vector<uint8_t> signing_public_key();

    private:
    TonConnectDerivedSession(const TonConnectDerivedSession &);

    TonConnectDerivedSession(uint64_t);

    uint64_t _uniffi_internal_clone_pointer() const;

    uint64_t instance = 0;
};


namespace uniffi {
    struct FfiConverterTonConnectSession;
}

struct TonConnectSession



{
    friend uniffi::FfiConverterTonConnectSession;

    TonConnectSession() = delete;

    TonConnectSession(TonConnectSession &&) = delete;

    TonConnectSession &operator=(const TonConnectSession &) = delete;
    TonConnectSession &operator=(TonConnectSession &&) = delete;

    ~TonConnectSession();
    TonConnectPreparedPost approve_connect(const TonConnectAccountInfo &account, std::optional<TonConnectProofReply> proof, const TonConnectDevice &device);
    std::string begin_events_subscription();
    void complete_pending_post();
    std::optional<TonConnectConnectPrompt> connect_prompt();
    TonConnectPreparedPost disconnect();
    std::vector<TonConnectIncomingRequest> ingest_sse_chunk(const std::vector<uint8_t> &chunk, uint64_t now);
    std::optional<TonConnectPreparedPost> pending_post();
    std::vector<TonConnectIncomingRequest> pending_requests(uint64_t now);
    std::string persisted();
    TonConnectSessionPhase phase();
    TonConnectPreparedPost prepare_disconnect_success(const std::string &request_id);
    TonConnectPreparedPost prepare_error(const std::string &request_id, const TonConnectRpcErrorCode &code, const std::string &message);
    TonConnectPreparedPost prepare_send_success(const std::string &request_id, const std::string &signed_boc);
    TonConnectPreparedPost prepare_sign_message_success(const std::string &request_id, const std::string &internal_boc);
    TonConnectPreparedPost reject_connect(const std::string &message);

    private:
    TonConnectSession(const TonConnectSession &);

    TonConnectSession(uint64_t);

    uint64_t _uniffi_internal_clone_pointer() const;

    uint64_t instance = 0;
};


namespace uniffi {
    struct FfiConverterWalletClient;
}

struct WalletClient



{
    friend uniffi::FfiConverterWalletClient;

    WalletClient() = delete;

    WalletClient(WalletClient &&) = delete;

    WalletClient &operator=(const WalletClient &) = delete;
    WalletClient &operator=(WalletClient &&) = delete;

    ~WalletClient();
    static std::shared_ptr<WalletClient> init(const WalletClientConfig &config, const std::shared_ptr<WalletHttpHost> &http_host, const std::shared_ptr<WalletPlatformHost> &platform_host);
    static std::shared_ptr<WalletClient> new_statusless(const WalletClientConfig &config, const std::shared_ptr<WalletStatuslessHost> &statusless_host, const std::shared_ptr<WalletPlatformHost> &platform_host);
    void cancel_load_more_activity();
    WalletUpdate load_more_activity();
    void shutdown();
    WalletSnapshot snapshot();
    WalletSnapshot wait_for_change(uint64_t after_revision);
    std::optional<TonAddressString> resolve_dns(const std::string &name);
    Boc create_encrypted_comment(const CreateEncryptedCommentRequest &request);
    std::string decrypt_comment(const DecryptCommentRequest &request);
    std::vector<uint8_t> resolve_encrypted_comment_recipient(const EncryptedCommentRecipientRequest &request);
    PreparedKeyRotation prepare_key_rotation(const PrepareKeyRotationRequest &request);
    void cancel_load_more_nfts();
    void cancel_refresh_nfts();
    WalletUpdate load_more_nfts();
    WalletUpdate refresh_nfts();
    PreparedTransfer prepare_nft_transfer(const PrepareNftTransferRequest &request);
    SendPreview preview_nft_transfer(const NftTransferPreviewRequest &request);
    SendResult send_nft_transfer(const NftTransferRequest &request);
    PreparedTransfer prepare_transfer(const PrepareTransferRequest &request);
    void cancel_send_preview();
    SendPreview preview_key_rotation();
    SendPreview preview_send(const SendPreviewRequest &request);
    SendPreview preview_send_boc(const SendBocRequest &request);
    SignMessagePreview preview_sign_message(const SendPreviewRequest &request);
    SendPreview preview_ton_connect(const SendRequest &request);
    void cancel_refresh();
    WalletUpdate refresh();
    SendSnapshot resolve_pending();
    void cancel_send();
    SendResult send(const SendRequest &request);
    SendResult send_boc(const SendBocRequest &request);
    SignMessageResult sign_message(const SignMessageRequest &request);

    private:
    WalletClient(const WalletClient &);

    WalletClient(uint64_t);

    uint64_t _uniffi_internal_clone_pointer() const;

    uint64_t instance = 0;
};




struct WalletHttpHost {
    virtual ~WalletHttpHost() {}
    virtual
    HttpResponse execute_http(const HttpRequest &request) = 0;
    virtual
    void cancel_http(const HttpRequestId &request_id) = 0;
};

namespace uniffi {
    struct UniffiCallbackInterfaceWalletHttpHost {
        static void execute_http(uint64_t uniffi_handle,RustBuffer request,void * uniffi_future_callback,uint64_t uniffi_callback_data,UniffiForeignFutureDroppedCallbackStruct & uniffi_out_dropped_callback);
        static void cancel_http(uint64_t uniffi_handle,RustBuffer request_id,void * uniffi_future_callback,uint64_t uniffi_callback_data,UniffiForeignFutureDroppedCallbackStruct & uniffi_out_dropped_callback);

        static void uniffi_free(uint64_t uniffi_handle);
        static uint64_t uniffi_clone(uint64_t uniffi_handle);
        static void init();
    private:
        static inline UniffiVTableCallbackInterfaceWalletHttpHost vtable = UniffiVTableCallbackInterfaceWalletHttpHost {
            .uniffi_free = reinterpret_cast<void *>(&uniffi_free),
            .uniffi_clone = reinterpret_cast<void *>(&uniffi_clone),
            .execute_http = reinterpret_cast<void *>(&execute_http),
            .cancel_http = reinterpret_cast<void *>(&cancel_http)
        };
    };
}

namespace uniffi {
    struct FfiConverterWalletHttpHost;
}

struct WalletHttpHostImpl

 : public WalletHttpHost

{
    friend uniffi::FfiConverterWalletHttpHost;

    WalletHttpHostImpl() = delete;

    WalletHttpHostImpl(WalletHttpHostImpl &&) = delete;

    WalletHttpHostImpl &operator=(const WalletHttpHostImpl &) = delete;
    WalletHttpHostImpl &operator=(WalletHttpHostImpl &&) = delete;

    ~WalletHttpHostImpl();
    HttpResponse execute_http(const HttpRequest &request);
    void cancel_http(const HttpRequestId &request_id);

    private:
    WalletHttpHostImpl(const WalletHttpHostImpl &);

    WalletHttpHostImpl(uint64_t);

    uint64_t _uniffi_internal_clone_pointer() const;

    uint64_t instance = 0;
};


namespace uniffi {
    struct FfiConverterWalletLifecycle;
}

struct WalletLifecycle



{
    friend uniffi::FfiConverterWalletLifecycle;

    WalletLifecycle() = delete;

    WalletLifecycle(WalletLifecycle &&) = delete;

    WalletLifecycle &operator=(const WalletLifecycle &) = delete;
    WalletLifecycle &operator=(WalletLifecycle &&) = delete;

    ~WalletLifecycle();
    static std::shared_ptr<WalletLifecycle> init(const std::shared_ptr<WalletPlatformHost> &platform_host);
    CreatedWallet create_wallet(const CreateWalletRequest &request);
    void delete_wallet(const WalletDescriptor &descriptor);
    std::shared_ptr<TonConnectDerivedSession> derive_ton_connect_session(const TonConnectDerivedSessionRequest &request);
    WalletDescriptor import_wallet(const ImportWalletRequest &request);
    RecoveryPhrase reveal_recovery_phrase(const WalletDescriptor &descriptor);
    TonConnectSignedData sign_ton_connect_data(const TonConnectSignDataSignRequest &request);
    TonConnectProofSignature sign_ton_connect_proof(const TonConnectProofSignRequest &request);
    TonConnectAccountInfo ton_connect_account(const WalletDescriptor &descriptor);

    private:
    WalletLifecycle(const WalletLifecycle &);

    WalletLifecycle(uint64_t);

    uint64_t _uniffi_internal_clone_pointer() const;

    uint64_t instance = 0;
};




struct WalletPlatformHost {
    virtual ~WalletPlatformHost() {}
    virtual
    std::vector<uint8_t> read_protected_secret(const ProtectedSecretRead &request) = 0;
    virtual
    void store_protected_secret(const ProtectedSecretStore &request) = 0;
    virtual
    void delete_protected_secret(const ProtectedSecretRef &secret_ref) = 0;
    virtual
    std::optional<JournalRecord> load_journal(const JournalKey &key) = 0;
    virtual
    JournalCompareExchangeResult compare_exchange_journal(const JournalCompareExchange &mutation) = 0;
};

namespace uniffi {
    struct UniffiCallbackInterfaceWalletPlatformHost {
        static void read_protected_secret(uint64_t uniffi_handle,RustBuffer request,void * uniffi_future_callback,uint64_t uniffi_callback_data,UniffiForeignFutureDroppedCallbackStruct & uniffi_out_dropped_callback);
        static void store_protected_secret(uint64_t uniffi_handle,RustBuffer request,void * uniffi_future_callback,uint64_t uniffi_callback_data,UniffiForeignFutureDroppedCallbackStruct & uniffi_out_dropped_callback);
        static void delete_protected_secret(uint64_t uniffi_handle,RustBuffer secret_ref,void * uniffi_future_callback,uint64_t uniffi_callback_data,UniffiForeignFutureDroppedCallbackStruct & uniffi_out_dropped_callback);
        static void load_journal(uint64_t uniffi_handle,RustBuffer key,void * uniffi_future_callback,uint64_t uniffi_callback_data,UniffiForeignFutureDroppedCallbackStruct & uniffi_out_dropped_callback);
        static void compare_exchange_journal(uint64_t uniffi_handle,RustBuffer mutation,void * uniffi_future_callback,uint64_t uniffi_callback_data,UniffiForeignFutureDroppedCallbackStruct & uniffi_out_dropped_callback);

        static void uniffi_free(uint64_t uniffi_handle);
        static uint64_t uniffi_clone(uint64_t uniffi_handle);
        static void init();
    private:
        static inline UniffiVTableCallbackInterfaceWalletPlatformHost vtable = UniffiVTableCallbackInterfaceWalletPlatformHost {
            .uniffi_free = reinterpret_cast<void *>(&uniffi_free),
            .uniffi_clone = reinterpret_cast<void *>(&uniffi_clone),
            .read_protected_secret = reinterpret_cast<void *>(&read_protected_secret),
            .store_protected_secret = reinterpret_cast<void *>(&store_protected_secret),
            .delete_protected_secret = reinterpret_cast<void *>(&delete_protected_secret),
            .load_journal = reinterpret_cast<void *>(&load_journal),
            .compare_exchange_journal = reinterpret_cast<void *>(&compare_exchange_journal)
        };
    };
}

namespace uniffi {
    struct FfiConverterWalletPlatformHost;
}

struct WalletPlatformHostImpl

 : public WalletPlatformHost

{
    friend uniffi::FfiConverterWalletPlatformHost;

    WalletPlatformHostImpl() = delete;

    WalletPlatformHostImpl(WalletPlatformHostImpl &&) = delete;

    WalletPlatformHostImpl &operator=(const WalletPlatformHostImpl &) = delete;
    WalletPlatformHostImpl &operator=(WalletPlatformHostImpl &&) = delete;

    ~WalletPlatformHostImpl();
    std::vector<uint8_t> read_protected_secret(const ProtectedSecretRead &request);
    void store_protected_secret(const ProtectedSecretStore &request);
    void delete_protected_secret(const ProtectedSecretRef &secret_ref);
    std::optional<JournalRecord> load_journal(const JournalKey &key);
    JournalCompareExchangeResult compare_exchange_journal(const JournalCompareExchange &mutation);

    private:
    WalletPlatformHostImpl(const WalletPlatformHostImpl &);

    WalletPlatformHostImpl(uint64_t);

    uint64_t _uniffi_internal_clone_pointer() const;

    uint64_t instance = 0;
};




struct WalletStatuslessHost {
    virtual ~WalletStatuslessHost() {}
    virtual
    std::vector<uint8_t> execute_statusless(const HttpRequest &request) = 0;
    virtual
    void cancel_statusless(const HttpRequestId &request_id) = 0;
};

namespace uniffi {
    struct UniffiCallbackInterfaceWalletStatuslessHost {
        static void execute_statusless(uint64_t uniffi_handle,RustBuffer request,void * uniffi_future_callback,uint64_t uniffi_callback_data,UniffiForeignFutureDroppedCallbackStruct & uniffi_out_dropped_callback);
        static void cancel_statusless(uint64_t uniffi_handle,RustBuffer request_id,void * uniffi_future_callback,uint64_t uniffi_callback_data,UniffiForeignFutureDroppedCallbackStruct & uniffi_out_dropped_callback);

        static void uniffi_free(uint64_t uniffi_handle);
        static uint64_t uniffi_clone(uint64_t uniffi_handle);
        static void init();
    private:
        static inline UniffiVTableCallbackInterfaceWalletStatuslessHost vtable = UniffiVTableCallbackInterfaceWalletStatuslessHost {
            .uniffi_free = reinterpret_cast<void *>(&uniffi_free),
            .uniffi_clone = reinterpret_cast<void *>(&uniffi_clone),
            .execute_statusless = reinterpret_cast<void *>(&execute_statusless),
            .cancel_statusless = reinterpret_cast<void *>(&cancel_statusless)
        };
    };
}

namespace uniffi {
    struct FfiConverterWalletStatuslessHost;
}

struct WalletStatuslessHostImpl

 : public WalletStatuslessHost

{
    friend uniffi::FfiConverterWalletStatuslessHost;

    WalletStatuslessHostImpl() = delete;

    WalletStatuslessHostImpl(WalletStatuslessHostImpl &&) = delete;

    WalletStatuslessHostImpl &operator=(const WalletStatuslessHostImpl &) = delete;
    WalletStatuslessHostImpl &operator=(WalletStatuslessHostImpl &&) = delete;

    ~WalletStatuslessHostImpl();
    std::vector<uint8_t> execute_statusless(const HttpRequest &request);
    void cancel_statusless(const HttpRequestId &request_id);

    private:
    WalletStatuslessHostImpl(const WalletStatuslessHostImpl &);

    WalletStatuslessHostImpl(uint64_t);

    uint64_t _uniffi_internal_clone_pointer() const;

    uint64_t instance = 0;
};


struct TonConnectAccountInfo {
    std::string address;
    std::string network;
    std::string wallet_state_init;
    std::vector<uint8_t> public_key;
};


struct TonConnectConnectPrompt {
    std::string manifest_url;
    std::optional<std::string> requested_network;
    std::optional<std::string> proof_payload;
};


struct TonConnectManifest {
    std::string url;
    std::string name;
    std::string icon_url;
    std::string domain;
};


struct TonConnectPreparedPost {
    std::string url;
    std::string body;
};


struct TonConnectProofReply {
    uint64_t timestamp;
    std::string domain;
    std::string payload;
    std::vector<uint8_t> signature;
};


struct TonConnectProofSignature {
    std::vector<uint8_t> signature;
    std::vector<uint8_t> public_key;
};


struct TonConnectSessionConfig {
    std::string bridge_url;
    uint64_t max_event_bytes;
    uint32_t message_ttl_seconds;
};


enum class MnemonicScheme: int32_t {
    kRotation = 1,
    kTon = 2,
    kBip39 = 3
};

namespace uniffi {
struct FfiConverterTonAddressError;
}

struct TonAddressError: std::runtime_error {
    friend uniffi::FfiConverterTonAddressError;

    TonAddressError() : std::runtime_error("") {}
    TonAddressError(const std::string &what_arg) : std::runtime_error(what_arg) {}

    virtual ~TonAddressError() = default;

    virtual void throw_underlying() {
        throw *this;
    }

protected:
    virtual int32_t get_variant_idx() const {
        return 0;
    };
};
namespace ton_address_error {

struct InvalidAddress: TonAddressError {

    InvalidAddress() : TonAddressError("") {}
    InvalidAddress(const std::string &what_arg) : TonAddressError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 1;
    }
};

struct UnsupportedWorkchain: TonAddressError {

    UnsupportedWorkchain() : TonAddressError("") {}
    UnsupportedWorkchain(const std::string &what_arg) : TonAddressError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 2;
    }
};
}


enum class TonConnectConnectErrorCode: int32_t {
    kManifestNotFound = 1,
    kManifestContent = 2,
    kUserDeclined = 3
};

namespace uniffi {
struct FfiConverterTonConnectSessionError;
}

struct TonConnectSessionError: std::runtime_error {
    friend uniffi::FfiConverterTonConnectSessionError;

    TonConnectSessionError() : std::runtime_error("") {}
    TonConnectSessionError(const std::string &what_arg) : std::runtime_error(what_arg) {}

    virtual ~TonConnectSessionError() = default;

    virtual void throw_underlying() {
        throw *this;
    }

protected:
    virtual int32_t get_variant_idx() const {
        return 0;
    };
};
namespace ton_connect_session_error {

struct Failed: TonConnectSessionError {
    std::string diagnostic;

    Failed() : TonConnectSessionError("") {}
    Failed(const std::string &what_arg) : TonConnectSessionError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 1;
    }
};
}


enum class TonConnectSessionPhase: int32_t {
    kPendingConnect = 1,
    kConnected = 2,
    kDisconnected = 3
};

namespace uniffi {
struct FfiConverterTonTransferLinkError;
}

struct TonTransferLinkError: std::runtime_error {
    friend uniffi::FfiConverterTonTransferLinkError;

    TonTransferLinkError() : std::runtime_error("") {}
    TonTransferLinkError(const std::string &what_arg) : std::runtime_error(what_arg) {}

    virtual ~TonTransferLinkError() = default;

    virtual void throw_underlying() {
        throw *this;
    }

protected:
    virtual int32_t get_variant_idx() const {
        return 0;
    };
};
namespace ton_transfer_link_error {

struct NotTonTransfer: TonTransferLinkError {

    NotTonTransfer() : TonTransferLinkError("") {}
    NotTonTransfer(const std::string &what_arg) : TonTransferLinkError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 1;
    }
};

struct InvalidUrl: TonTransferLinkError {

    InvalidUrl() : TonTransferLinkError("") {}
    InvalidUrl(const std::string &what_arg) : TonTransferLinkError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 2;
    }
};

struct MissingRecipient: TonTransferLinkError {

    MissingRecipient() : TonTransferLinkError("") {}
    MissingRecipient(const std::string &what_arg) : TonTransferLinkError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 3;
    }
};

struct InvalidRecipient: TonTransferLinkError {

    InvalidRecipient() : TonTransferLinkError("") {}
    InvalidRecipient(const std::string &what_arg) : TonTransferLinkError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 4;
    }
};

struct DuplicateParameter: TonTransferLinkError {
    std::string name;

    DuplicateParameter() : TonTransferLinkError("") {}
    DuplicateParameter(const std::string &what_arg) : TonTransferLinkError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 5;
    }
};

struct UnsupportedParameter: TonTransferLinkError {
    std::string name;

    UnsupportedParameter() : TonTransferLinkError("") {}
    UnsupportedParameter(const std::string &what_arg) : TonTransferLinkError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 6;
    }
};

struct InvalidAmount: TonTransferLinkError {

    InvalidAmount() : TonTransferLinkError("") {}
    InvalidAmount(const std::string &what_arg) : TonTransferLinkError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 7;
    }
};

struct InvalidExpiration: TonTransferLinkError {

    InvalidExpiration() : TonTransferLinkError("") {}
    InvalidExpiration(const std::string &what_arg) : TonTransferLinkError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 8;
    }
};

struct InvalidJettonMaster: TonTransferLinkError {

    InvalidJettonMaster() : TonTransferLinkError("") {}
    InvalidJettonMaster(const std::string &what_arg) : TonTransferLinkError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 9;
    }
};

struct InvalidBinaryPayload: TonTransferLinkError {

    InvalidBinaryPayload() : TonTransferLinkError("") {}
    InvalidBinaryPayload(const std::string &what_arg) : TonTransferLinkError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 10;
    }
};

struct ConflictingPayloads: TonTransferLinkError {

    ConflictingPayloads() : TonTransferLinkError("") {}
    ConflictingPayloads(const std::string &what_arg) : TonTransferLinkError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 11;
    }
};

struct BinaryJettonConflict: TonTransferLinkError {

    BinaryJettonConflict() : TonTransferLinkError("") {}
    BinaryJettonConflict(const std::string &what_arg) : TonTransferLinkError(what_arg) {}

    void throw_underlying() override {
        throw *this;
    }

protected:
    int32_t get_variant_idx() const override {
        return 12;
    }
};
}

namespace uniffi {
using ::uniffi::RustStream;
using ::uniffi::RustStreamBuffer;

RustBuffer rustbuffer_alloc(uint64_t);
RustBuffer rustbuffer_from_bytes(const ForeignBytes &);
void rustbuffer_free(RustBuffer);
template <typename T> struct HandleMap {
    HandleMap() = default;

    std::shared_ptr<T> at(uint64_t handle) {
        std::lock_guard<std::mutex> guard(this->mutex);

        return this->map.at(handle);
    }

    uint64_t insert(std::shared_ptr<T> impl) {
        std::lock_guard<std::mutex> guard(this->mutex);

        auto handle = this->cur_handle;

        this->map.insert({ handle, impl });
        this->cur_handle += 1;

        return handle;
    }

    void erase(uint64_t handle) {
        std::shared_ptr<T> cleanup;
        {
            std::lock_guard<std::mutex> guard(this->mutex);
            auto it = this->map.find(handle);
            if (it != this->map.end()) {
                cleanup = it->second;
                this->map.erase(it);
            }
        }
    }
    private:
        HandleMap(const HandleMap<T> &) = delete;
        HandleMap(HandleMap<T> &&) = delete;

        HandleMap<T> &operator=(const HandleMap<T> &) = delete;
        HandleMap<T> &operator=(HandleMap<T> &&) = delete;

        std::mutex mutex;
        uint64_t cur_handle = 0;
        std::map<uint64_t, std::shared_ptr<T>> map;
};
struct FfiConverterUInt16 {
    static uint16_t lift(uint16_t);
    static uint16_t lower(uint16_t);
    static uint16_t read(RustStream &);
    static void write(RustStream &, uint16_t);
    static uint64_t allocation_size(uint16_t);
};
struct FfiConverterUInt32 {
    static uint32_t lift(uint32_t);
    static uint32_t lower(uint32_t);
    static uint32_t read(RustStream &);
    static void write(RustStream &, uint32_t);
    static uint64_t allocation_size(uint32_t);
};
struct FfiConverterInt32 {
    static int32_t lift(int32_t);
    static int32_t lower(int32_t);
    static int32_t read(RustStream &);
    static void write(RustStream &, int32_t);
    static uint64_t allocation_size(int32_t);
};
struct FfiConverterUInt64 {
    static uint64_t lift(uint64_t);
    static uint64_t lower(uint64_t);
    static uint64_t read(RustStream &);
    static void write(RustStream &, uint64_t);
    static uint64_t allocation_size(uint64_t);
};
struct FfiConverterInt64 {
    static int64_t lift(int64_t);
    static int64_t lower(int64_t);
    static int64_t read(RustStream &);
    static void write(RustStream &, int64_t);
    static uint64_t allocation_size(int64_t);
};
struct FfiConverterBool {
    static bool lift(uint8_t);
    static uint8_t lower(bool);
    static bool read(RustStream &);
    static void write(RustStream &, bool);
    static uint64_t allocation_size(bool);
};
struct FfiConverterString {
    static std::string lift(RustBuffer buf);
    static RustBuffer lower(const std::string &);
    static std::string read(RustStream &);
    static void write(RustStream &, const std::string &);
    static uint64_t allocation_size(const std::string &);
};

struct FfiConverterBytes {
    static std::vector<uint8_t> lift(RustBuffer);
    static RustBuffer lower(const std::vector<uint8_t> &);
    static std::vector<uint8_t> read(RustStream &);
    static void write(RustStream &, const std::vector<uint8_t> &);
    static uint64_t allocation_size(const std::vector<uint8_t> &);
};


struct FfiConverterTonConnectDerivedSession {
    static std::shared_ptr<TonConnectDerivedSession> lift(uint64_t);
    static uint64_t lower(const std::shared_ptr<TonConnectDerivedSession> &);
    static std::shared_ptr<TonConnectDerivedSession> read(RustStream &);
    static void write(RustStream &, const std::shared_ptr<TonConnectDerivedSession> &);
    static uint64_t allocation_size(const std::shared_ptr<TonConnectDerivedSession> &);
private:
};


struct FfiConverterTonConnectSession {
    static std::shared_ptr<TonConnectSession> lift(uint64_t);
    static uint64_t lower(const std::shared_ptr<TonConnectSession> &);
    static std::shared_ptr<TonConnectSession> read(RustStream &);
    static void write(RustStream &, const std::shared_ptr<TonConnectSession> &);
    static uint64_t allocation_size(const std::shared_ptr<TonConnectSession> &);
private:
};


struct FfiConverterWalletClient {
    static std::shared_ptr<WalletClient> lift(uint64_t);
    static uint64_t lower(const std::shared_ptr<WalletClient> &);
    static std::shared_ptr<WalletClient> read(RustStream &);
    static void write(RustStream &, const std::shared_ptr<WalletClient> &);
    static uint64_t allocation_size(const std::shared_ptr<WalletClient> &);
private:
};


struct FfiConverterWalletHttpHost {
    static std::shared_ptr<WalletHttpHost> lift(uint64_t);
    static uint64_t lower(const std::shared_ptr<WalletHttpHost> &);
    static std::shared_ptr<WalletHttpHost> read(RustStream &);
    static void write(RustStream &, const std::shared_ptr<WalletHttpHost> &);
    static uint64_t allocation_size(const std::shared_ptr<WalletHttpHost> &);
private:
    friend struct UniffiCallbackInterfaceWalletHttpHost;
    inline static HandleMap<WalletHttpHost> handle_map = {};
};


struct FfiConverterWalletLifecycle {
    static std::shared_ptr<WalletLifecycle> lift(uint64_t);
    static uint64_t lower(const std::shared_ptr<WalletLifecycle> &);
    static std::shared_ptr<WalletLifecycle> read(RustStream &);
    static void write(RustStream &, const std::shared_ptr<WalletLifecycle> &);
    static uint64_t allocation_size(const std::shared_ptr<WalletLifecycle> &);
private:
};


struct FfiConverterWalletPlatformHost {
    static std::shared_ptr<WalletPlatformHost> lift(uint64_t);
    static uint64_t lower(const std::shared_ptr<WalletPlatformHost> &);
    static std::shared_ptr<WalletPlatformHost> read(RustStream &);
    static void write(RustStream &, const std::shared_ptr<WalletPlatformHost> &);
    static uint64_t allocation_size(const std::shared_ptr<WalletPlatformHost> &);
private:
    friend struct UniffiCallbackInterfaceWalletPlatformHost;
    inline static HandleMap<WalletPlatformHost> handle_map = {};
};


struct FfiConverterWalletStatuslessHost {
    static std::shared_ptr<WalletStatuslessHost> lift(uint64_t);
    static uint64_t lower(const std::shared_ptr<WalletStatuslessHost> &);
    static std::shared_ptr<WalletStatuslessHost> read(RustStream &);
    static void write(RustStream &, const std::shared_ptr<WalletStatuslessHost> &);
    static uint64_t allocation_size(const std::shared_ptr<WalletStatuslessHost> &);
private:
    friend struct UniffiCallbackInterfaceWalletStatuslessHost;
    inline static HandleMap<WalletStatuslessHost> handle_map = {};
};

struct FfiConverterTypeAccountSnapshot {
    static AccountSnapshot lift(RustBuffer);
    static RustBuffer lower(const AccountSnapshot &);
    static AccountSnapshot read(RustStream &);
    static void write(RustStream &, const AccountSnapshot &);
    static uint64_t allocation_size(const AccountSnapshot &);
};

struct FfiConverterTypeActivityCursor {
    static ActivityCursor lift(RustBuffer);
    static RustBuffer lower(const ActivityCursor &);
    static ActivityCursor read(RustStream &);
    static void write(RustStream &, const ActivityCursor &);
    static uint64_t allocation_size(const ActivityCursor &);
};

struct FfiConverterTypeActivityItem {
    static ActivityItem lift(RustBuffer);
    static RustBuffer lower(const ActivityItem &);
    static ActivityItem read(RustStream &);
    static void write(RustStream &, const ActivityItem &);
    static uint64_t allocation_size(const ActivityItem &);
};

struct FfiConverterTypeActivityList {
    static ActivityList lift(RustBuffer);
    static RustBuffer lower(const ActivityList &);
    static ActivityList read(RustStream &);
    static void write(RustStream &, const ActivityList &);
    static uint64_t allocation_size(const ActivityList &);
};

struct FfiConverterTypeCreateEncryptedCommentRequest {
    static CreateEncryptedCommentRequest lift(RustBuffer);
    static RustBuffer lower(const CreateEncryptedCommentRequest &);
    static CreateEncryptedCommentRequest read(RustStream &);
    static void write(RustStream &, const CreateEncryptedCommentRequest &);
    static uint64_t allocation_size(const CreateEncryptedCommentRequest &);
};

struct FfiConverterTypeCreateWalletRequest {
    static CreateWalletRequest lift(RustBuffer);
    static RustBuffer lower(const CreateWalletRequest &);
    static CreateWalletRequest read(RustStream &);
    static void write(RustStream &, const CreateWalletRequest &);
    static uint64_t allocation_size(const CreateWalletRequest &);
};

struct FfiConverterTypeCreatedWallet {
    static CreatedWallet lift(RustBuffer);
    static RustBuffer lower(const CreatedWallet &);
    static CreatedWallet read(RustStream &);
    static void write(RustStream &, const CreatedWallet &);
    static uint64_t allocation_size(const CreatedWallet &);
};

struct FfiConverterTypeDecryptCommentRequest {
    static DecryptCommentRequest lift(RustBuffer);
    static RustBuffer lower(const DecryptCommentRequest &);
    static DecryptCommentRequest read(RustStream &);
    static void write(RustStream &, const DecryptCommentRequest &);
    static uint64_t allocation_size(const DecryptCommentRequest &);
};

struct FfiConverterTypeDomainError {
    static DomainError lift(RustBuffer);
    static RustBuffer lower(const DomainError &);
    static DomainError read(RustStream &);
    static void write(RustStream &, const DomainError &);
    static uint64_t allocation_size(const DomainError &);
};

struct FfiConverterTypeEncryptedCommentRecipientRequest {
    static EncryptedCommentRecipientRequest lift(RustBuffer);
    static RustBuffer lower(const EncryptedCommentRecipientRequest &);
    static EncryptedCommentRecipientRequest read(RustStream &);
    static void write(RustStream &, const EncryptedCommentRecipientRequest &);
    static uint64_t allocation_size(const EncryptedCommentRecipientRequest &);
};

struct FfiConverterTypeHttpHeader {
    static HttpHeader lift(RustBuffer);
    static RustBuffer lower(const HttpHeader &);
    static HttpHeader read(RustStream &);
    static void write(RustStream &, const HttpHeader &);
    static uint64_t allocation_size(const HttpHeader &);
};

struct FfiConverterTypeHttpRequest {
    static HttpRequest lift(RustBuffer);
    static RustBuffer lower(const HttpRequest &);
    static HttpRequest read(RustStream &);
    static void write(RustStream &, const HttpRequest &);
    static uint64_t allocation_size(const HttpRequest &);
};

struct FfiConverterTypeHttpRequestId {
    static HttpRequestId lift(RustBuffer);
    static RustBuffer lower(const HttpRequestId &);
    static HttpRequestId read(RustStream &);
    static void write(RustStream &, const HttpRequestId &);
    static uint64_t allocation_size(const HttpRequestId &);
};

struct FfiConverterTypeHttpResponse {
    static HttpResponse lift(RustBuffer);
    static RustBuffer lower(const HttpResponse &);
    static HttpResponse read(RustStream &);
    static void write(RustStream &, const HttpResponse &);
    static uint64_t allocation_size(const HttpResponse &);
};

struct FfiConverterTypeImportWalletRequest {
    static ImportWalletRequest lift(RustBuffer);
    static RustBuffer lower(const ImportWalletRequest &);
    static ImportWalletRequest read(RustStream &);
    static void write(RustStream &, const ImportWalletRequest &);
    static uint64_t allocation_size(const ImportWalletRequest &);
};

struct FfiConverterTypeJournalCompareExchange {
    static JournalCompareExchange lift(RustBuffer);
    static RustBuffer lower(const JournalCompareExchange &);
    static JournalCompareExchange read(RustStream &);
    static void write(RustStream &, const JournalCompareExchange &);
    static uint64_t allocation_size(const JournalCompareExchange &);
};

struct FfiConverterTypeJournalCompareExchangeResult {
    static JournalCompareExchangeResult lift(RustBuffer);
    static RustBuffer lower(const JournalCompareExchangeResult &);
    static JournalCompareExchangeResult read(RustStream &);
    static void write(RustStream &, const JournalCompareExchangeResult &);
    static uint64_t allocation_size(const JournalCompareExchangeResult &);
};

struct FfiConverterTypeJournalKey {
    static JournalKey lift(RustBuffer);
    static RustBuffer lower(const JournalKey &);
    static JournalKey read(RustStream &);
    static void write(RustStream &, const JournalKey &);
    static uint64_t allocation_size(const JournalKey &);
};

struct FfiConverterTypeJournalRecord {
    static JournalRecord lift(RustBuffer);
    static RustBuffer lower(const JournalRecord &);
    static JournalRecord read(RustStream &);
    static void write(RustStream &, const JournalRecord &);
    static uint64_t allocation_size(const JournalRecord &);
};

struct FfiConverterTypeNftCollectionDescriptor {
    static NftCollectionDescriptor lift(RustBuffer);
    static RustBuffer lower(const NftCollectionDescriptor &);
    static NftCollectionDescriptor read(RustStream &);
    static void write(RustStream &, const NftCollectionDescriptor &);
    static uint64_t allocation_size(const NftCollectionDescriptor &);
};

struct FfiConverterTypeNftItem {
    static NftItem lift(RustBuffer);
    static RustBuffer lower(const NftItem &);
    static NftItem read(RustStream &);
    static void write(RustStream &, const NftItem &);
    static uint64_t allocation_size(const NftItem &);
};

struct FfiConverterTypeNftList {
    static NftList lift(RustBuffer);
    static RustBuffer lower(const NftList &);
    static NftList read(RustStream &);
    static void write(RustStream &, const NftList &);
    static uint64_t allocation_size(const NftList &);
};

struct FfiConverterTypeNftTransferIntent {
    static NftTransferIntent lift(RustBuffer);
    static RustBuffer lower(const NftTransferIntent &);
    static NftTransferIntent read(RustStream &);
    static void write(RustStream &, const NftTransferIntent &);
    static uint64_t allocation_size(const NftTransferIntent &);
};

struct FfiConverterTypeNftTransferPreviewRequest {
    static NftTransferPreviewRequest lift(RustBuffer);
    static RustBuffer lower(const NftTransferPreviewRequest &);
    static NftTransferPreviewRequest read(RustStream &);
    static void write(RustStream &, const NftTransferPreviewRequest &);
    static uint64_t allocation_size(const NftTransferPreviewRequest &);
};

struct FfiConverterTypeNftTransferRequest {
    static NftTransferRequest lift(RustBuffer);
    static RustBuffer lower(const NftTransferRequest &);
    static NftTransferRequest read(RustStream &);
    static void write(RustStream &, const NftTransferRequest &);
    static uint64_t allocation_size(const NftTransferRequest &);
};

struct FfiConverterTypeParsedTonTransferLink {
    static ParsedTonTransferLink lift(RustBuffer);
    static RustBuffer lower(const ParsedTonTransferLink &);
    static ParsedTonTransferLink read(RustStream &);
    static void write(RustStream &, const ParsedTonTransferLink &);
    static uint64_t allocation_size(const ParsedTonTransferLink &);
};

struct FfiConverterTypePrepareKeyRotationRequest {
    static PrepareKeyRotationRequest lift(RustBuffer);
    static RustBuffer lower(const PrepareKeyRotationRequest &);
    static PrepareKeyRotationRequest read(RustStream &);
    static void write(RustStream &, const PrepareKeyRotationRequest &);
    static uint64_t allocation_size(const PrepareKeyRotationRequest &);
};

struct FfiConverterTypePrepareNftTransferRequest {
    static PrepareNftTransferRequest lift(RustBuffer);
    static RustBuffer lower(const PrepareNftTransferRequest &);
    static PrepareNftTransferRequest read(RustStream &);
    static void write(RustStream &, const PrepareNftTransferRequest &);
    static uint64_t allocation_size(const PrepareNftTransferRequest &);
};

struct FfiConverterTypePrepareTransferRequest {
    static PrepareTransferRequest lift(RustBuffer);
    static RustBuffer lower(const PrepareTransferRequest &);
    static PrepareTransferRequest read(RustStream &);
    static void write(RustStream &, const PrepareTransferRequest &);
    static uint64_t allocation_size(const PrepareTransferRequest &);
};

struct FfiConverterTypePreparedKeyRotation {
    static PreparedKeyRotation lift(RustBuffer);
    static RustBuffer lower(const PreparedKeyRotation &);
    static PreparedKeyRotation read(RustStream &);
    static void write(RustStream &, const PreparedKeyRotation &);
    static uint64_t allocation_size(const PreparedKeyRotation &);
};

struct FfiConverterTypePreparedTransfer {
    static PreparedTransfer lift(RustBuffer);
    static RustBuffer lower(const PreparedTransfer &);
    static PreparedTransfer read(RustStream &);
    static void write(RustStream &, const PreparedTransfer &);
    static uint64_t allocation_size(const PreparedTransfer &);
};

struct FfiConverterTypeProtectedSecretRead {
    static ProtectedSecretRead lift(RustBuffer);
    static RustBuffer lower(const ProtectedSecretRead &);
    static ProtectedSecretRead read(RustStream &);
    static void write(RustStream &, const ProtectedSecretRead &);
    static uint64_t allocation_size(const ProtectedSecretRead &);
};

struct FfiConverterTypeProtectedSecretRef {
    static ProtectedSecretRef lift(RustBuffer);
    static RustBuffer lower(const ProtectedSecretRef &);
    static ProtectedSecretRef read(RustStream &);
    static void write(RustStream &, const ProtectedSecretRef &);
    static uint64_t allocation_size(const ProtectedSecretRef &);
};

struct FfiConverterTypeProtectedSecretStore {
    static ProtectedSecretStore lift(RustBuffer);
    static RustBuffer lower(const ProtectedSecretStore &);
    static ProtectedSecretStore read(RustStream &);
    static void write(RustStream &, const ProtectedSecretStore &);
    static uint64_t allocation_size(const ProtectedSecretStore &);
};

struct FfiConverterTypeProviderConfig {
    static ProviderConfig lift(RustBuffer);
    static RustBuffer lower(const ProviderConfig &);
    static ProviderConfig read(RustStream &);
    static void write(RustStream &, const ProviderConfig &);
    static uint64_t allocation_size(const ProviderConfig &);
};

struct FfiConverterTypeRecoveryPhrase {
    static RecoveryPhrase lift(RustBuffer);
    static RustBuffer lower(const RecoveryPhrase &);
    static RecoveryPhrase read(RustStream &);
    static void write(RustStream &, const RecoveryPhrase &);
    static uint64_t allocation_size(const RecoveryPhrase &);
};

struct FfiConverterTypeResolutionInfo {
    static ResolutionInfo lift(RustBuffer);
    static RustBuffer lower(const ResolutionInfo &);
    static ResolutionInfo read(RustStream &);
    static void write(RustStream &, const ResolutionInfo &);
    static uint64_t allocation_size(const ResolutionInfo &);
};

struct FfiConverterTypeResourceState {
    static ResourceState lift(RustBuffer);
    static RustBuffer lower(const ResourceState &);
    static ResourceState read(RustStream &);
    static void write(RustStream &, const ResourceState &);
    static uint64_t allocation_size(const ResourceState &);
};

struct FfiConverterTypeSendBocRequest {
    static SendBocRequest lift(RustBuffer);
    static RustBuffer lower(const SendBocRequest &);
    static SendBocRequest read(RustStream &);
    static void write(RustStream &, const SendBocRequest &);
    static uint64_t allocation_size(const SendBocRequest &);
};

struct FfiConverterTypeSendEmulation {
    static SendEmulation lift(RustBuffer);
    static RustBuffer lower(const SendEmulation &);
    static SendEmulation read(RustStream &);
    static void write(RustStream &, const SendEmulation &);
    static uint64_t allocation_size(const SendEmulation &);
};

struct FfiConverterTypeSendEmulationAction {
    static SendEmulationAction lift(RustBuffer);
    static RustBuffer lower(const SendEmulationAction &);
    static SendEmulationAction read(RustStream &);
    static void write(RustStream &, const SendEmulationAction &);
    static uint64_t allocation_size(const SendEmulationAction &);
};

struct FfiConverterTypeSendEmulationTransaction {
    static SendEmulationTransaction lift(RustBuffer);
    static RustBuffer lower(const SendEmulationTransaction &);
    static SendEmulationTransaction read(RustStream &);
    static void write(RustStream &, const SendEmulationTransaction &);
    static uint64_t allocation_size(const SendEmulationTransaction &);
};

struct FfiConverterTypeSendIntent {
    static SendIntent lift(RustBuffer);
    static RustBuffer lower(const SendIntent &);
    static SendIntent read(RustStream &);
    static void write(RustStream &, const SendIntent &);
    static uint64_t allocation_size(const SendIntent &);
};

struct FfiConverterTypeSendMessage {
    static SendMessage lift(RustBuffer);
    static RustBuffer lower(const SendMessage &);
    static SendMessage read(RustStream &);
    static void write(RustStream &, const SendMessage &);
    static uint64_t allocation_size(const SendMessage &);
};

struct FfiConverterTypeSendPreview {
    static SendPreview lift(RustBuffer);
    static RustBuffer lower(const SendPreview &);
    static SendPreview read(RustStream &);
    static void write(RustStream &, const SendPreview &);
    static uint64_t allocation_size(const SendPreview &);
};

struct FfiConverterTypeSendPreviewRequest {
    static SendPreviewRequest lift(RustBuffer);
    static RustBuffer lower(const SendPreviewRequest &);
    static SendPreviewRequest read(RustStream &);
    static void write(RustStream &, const SendPreviewRequest &);
    static uint64_t allocation_size(const SendPreviewRequest &);
};

struct FfiConverterTypeSendRequest {
    static SendRequest lift(RustBuffer);
    static RustBuffer lower(const SendRequest &);
    static SendRequest read(RustStream &);
    static void write(RustStream &, const SendRequest &);
    static uint64_t allocation_size(const SendRequest &);
};

struct FfiConverterTypeSendResult {
    static SendResult lift(RustBuffer);
    static RustBuffer lower(const SendResult &);
    static SendResult read(RustStream &);
    static void write(RustStream &, const SendResult &);
    static uint64_t allocation_size(const SendResult &);
};

struct FfiConverterTypeSendSnapshot {
    static SendSnapshot lift(RustBuffer);
    static RustBuffer lower(const SendSnapshot &);
    static SendSnapshot read(RustStream &);
    static void write(RustStream &, const SendSnapshot &);
    static uint64_t allocation_size(const SendSnapshot &);
};

struct FfiConverterTypeSignMessagePreview {
    static SignMessagePreview lift(RustBuffer);
    static RustBuffer lower(const SignMessagePreview &);
    static SignMessagePreview read(RustStream &);
    static void write(RustStream &, const SignMessagePreview &);
    static uint64_t allocation_size(const SignMessagePreview &);
};

struct FfiConverterTypeSignMessageRequest {
    static SignMessageRequest lift(RustBuffer);
    static RustBuffer lower(const SignMessageRequest &);
    static SignMessageRequest read(RustStream &);
    static void write(RustStream &, const SignMessageRequest &);
    static uint64_t allocation_size(const SignMessageRequest &);
};

struct FfiConverterTypeSignMessageResult {
    static SignMessageResult lift(RustBuffer);
    static RustBuffer lower(const SignMessageResult &);
    static SignMessageResult read(RustStream &);
    static void write(RustStream &, const SignMessageResult &);
    static uint64_t allocation_size(const SignMessageResult &);
};

struct FfiConverterTypeTonAddressInfo {
    static TonAddressInfo lift(RustBuffer);
    static RustBuffer lower(const TonAddressInfo &);
    static TonAddressInfo read(RustStream &);
    static void write(RustStream &, const TonAddressInfo &);
    static uint64_t allocation_size(const TonAddressInfo &);
};

struct FfiConverterTypeTonConnectAccountInfo {
    static TonConnectAccountInfo lift(RustBuffer);
    static RustBuffer lower(const TonConnectAccountInfo &);
    static TonConnectAccountInfo read(RustStream &);
    static void write(RustStream &, const TonConnectAccountInfo &);
    static uint64_t allocation_size(const TonConnectAccountInfo &);
};

struct FfiConverterTypeTonConnectConnectPrompt {
    static TonConnectConnectPrompt lift(RustBuffer);
    static RustBuffer lower(const TonConnectConnectPrompt &);
    static TonConnectConnectPrompt read(RustStream &);
    static void write(RustStream &, const TonConnectConnectPrompt &);
    static uint64_t allocation_size(const TonConnectConnectPrompt &);
};

struct FfiConverterTypeTonConnectDerivedRequest {
    static TonConnectDerivedRequest lift(RustBuffer);
    static RustBuffer lower(const TonConnectDerivedRequest &);
    static TonConnectDerivedRequest read(RustStream &);
    static void write(RustStream &, const TonConnectDerivedRequest &);
    static uint64_t allocation_size(const TonConnectDerivedRequest &);
};

struct FfiConverterTypeTonConnectDerivedSessionRequest {
    static TonConnectDerivedSessionRequest lift(RustBuffer);
    static RustBuffer lower(const TonConnectDerivedSessionRequest &);
    static TonConnectDerivedSessionRequest read(RustStream &);
    static void write(RustStream &, const TonConnectDerivedSessionRequest &);
    static uint64_t allocation_size(const TonConnectDerivedSessionRequest &);
};

struct FfiConverterTypeTonConnectDevice {
    static TonConnectDevice lift(RustBuffer);
    static RustBuffer lower(const TonConnectDevice &);
    static TonConnectDevice read(RustStream &);
    static void write(RustStream &, const TonConnectDevice &);
    static uint64_t allocation_size(const TonConnectDevice &);
};

struct FfiConverterTypeTonConnectManifest {
    static TonConnectManifest lift(RustBuffer);
    static RustBuffer lower(const TonConnectManifest &);
    static TonConnectManifest read(RustStream &);
    static void write(RustStream &, const TonConnectManifest &);
    static uint64_t allocation_size(const TonConnectManifest &);
};

struct FfiConverterTypeTonConnectPreparedPost {
    static TonConnectPreparedPost lift(RustBuffer);
    static RustBuffer lower(const TonConnectPreparedPost &);
    static TonConnectPreparedPost read(RustStream &);
    static void write(RustStream &, const TonConnectPreparedPost &);
    static uint64_t allocation_size(const TonConnectPreparedPost &);
};

struct FfiConverterTypeTonConnectProofReply {
    static TonConnectProofReply lift(RustBuffer);
    static RustBuffer lower(const TonConnectProofReply &);
    static TonConnectProofReply read(RustStream &);
    static void write(RustStream &, const TonConnectProofReply &);
    static uint64_t allocation_size(const TonConnectProofReply &);
};

struct FfiConverterTypeTonConnectProofSignRequest {
    static TonConnectProofSignRequest lift(RustBuffer);
    static RustBuffer lower(const TonConnectProofSignRequest &);
    static TonConnectProofSignRequest read(RustStream &);
    static void write(RustStream &, const TonConnectProofSignRequest &);
    static uint64_t allocation_size(const TonConnectProofSignRequest &);
};

struct FfiConverterTypeTonConnectProofSignature {
    static TonConnectProofSignature lift(RustBuffer);
    static RustBuffer lower(const TonConnectProofSignature &);
    static TonConnectProofSignature read(RustStream &);
    static void write(RustStream &, const TonConnectProofSignature &);
    static uint64_t allocation_size(const TonConnectProofSignature &);
};

struct FfiConverterTypeTonConnectSessionConfig {
    static TonConnectSessionConfig lift(RustBuffer);
    static RustBuffer lower(const TonConnectSessionConfig &);
    static TonConnectSessionConfig read(RustStream &);
    static void write(RustStream &, const TonConnectSessionConfig &);
    static uint64_t allocation_size(const TonConnectSessionConfig &);
};

struct FfiConverterTypeTonConnectSignDataRequest {
    static TonConnectSignDataRequest lift(RustBuffer);
    static RustBuffer lower(const TonConnectSignDataRequest &);
    static TonConnectSignDataRequest read(RustStream &);
    static void write(RustStream &, const TonConnectSignDataRequest &);
    static uint64_t allocation_size(const TonConnectSignDataRequest &);
};

struct FfiConverterTypeTonConnectSignDataSignRequest {
    static TonConnectSignDataSignRequest lift(RustBuffer);
    static RustBuffer lower(const TonConnectSignDataSignRequest &);
    static TonConnectSignDataSignRequest read(RustStream &);
    static void write(RustStream &, const TonConnectSignDataSignRequest &);
    static uint64_t allocation_size(const TonConnectSignDataSignRequest &);
};

struct FfiConverterTypeTonConnectSignedData {
    static TonConnectSignedData lift(RustBuffer);
    static RustBuffer lower(const TonConnectSignedData &);
    static TonConnectSignedData read(RustStream &);
    static void write(RustStream &, const TonConnectSignedData &);
    static uint64_t allocation_size(const TonConnectSignedData &);
};

struct FfiConverterTypeWalletClientConfig {
    static WalletClientConfig lift(RustBuffer);
    static RustBuffer lower(const WalletClientConfig &);
    static WalletClientConfig read(RustStream &);
    static void write(RustStream &, const WalletClientConfig &);
    static uint64_t allocation_size(const WalletClientConfig &);
};

struct FfiConverterTypeWalletDescriptor {
    static WalletDescriptor lift(RustBuffer);
    static RustBuffer lower(const WalletDescriptor &);
    static WalletDescriptor read(RustStream &);
    static void write(RustStream &, const WalletDescriptor &);
    static uint64_t allocation_size(const WalletDescriptor &);
};

struct FfiConverterTypeWalletSnapshot {
    static WalletSnapshot lift(RustBuffer);
    static RustBuffer lower(const WalletSnapshot &);
    static WalletSnapshot read(RustStream &);
    static void write(RustStream &, const WalletSnapshot &);
    static uint64_t allocation_size(const WalletSnapshot &);
};

struct FfiConverterTypeWalletUpdate {
    static WalletUpdate lift(RustBuffer);
    static RustBuffer lower(const WalletUpdate &);
    static WalletUpdate read(RustStream &);
    static void write(RustStream &, const WalletUpdate &);
    static uint64_t allocation_size(const WalletUpdate &);
};
struct FfiConverterAccountStatus {
    static AccountStatus lift(RustBuffer);
    static RustBuffer lower(const AccountStatus &);
    static AccountStatus read(RustStream &);
    static void write(RustStream &, const AccountStatus &);
    static uint64_t allocation_size(const AccountStatus &);
};
struct FfiConverterActivityDirection {
    static ActivityDirection lift(RustBuffer);
    static RustBuffer lower(const ActivityDirection &);
    static ActivityDirection read(RustStream &);
    static void write(RustStream &, const ActivityDirection &);
    static uint64_t allocation_size(const ActivityDirection &);
};
struct FfiConverterActivityStatus {
    static ActivityStatus lift(RustBuffer);
    static RustBuffer lower(const ActivityStatus &);
    static ActivityStatus read(RustStream &);
    static void write(RustStream &, const ActivityStatus &);
    static uint64_t allocation_size(const ActivityStatus &);
};
struct FfiConverterErrorCategory {
    static ErrorCategory lift(RustBuffer);
    static RustBuffer lower(const ErrorCategory &);
    static ErrorCategory read(RustStream &);
    static void write(RustStream &, const ErrorCategory &);
    static uint64_t allocation_size(const ErrorCategory &);
};
struct FfiConverterErrorCode {
    static ErrorCode lift(RustBuffer);
    static RustBuffer lower(const ErrorCode &);
    static ErrorCode read(RustStream &);
    static void write(RustStream &, const ErrorCode &);
    static uint64_t allocation_size(const ErrorCode &);
};

struct FfiConverterHttpHostError {
    static std::shared_ptr<HttpHostError> lift(RustBuffer buf);
    static RustBuffer lower(const HttpHostError &);
    static std::shared_ptr<HttpHostError> read(RustStream &stream);
    static void write(RustStream &stream, const HttpHostError &);
    static uint64_t allocation_size(const HttpHostError &);
};
struct FfiConverterHttpHostErrorKind {
    static HttpHostErrorKind lift(RustBuffer);
    static RustBuffer lower(const HttpHostErrorKind &);
    static HttpHostErrorKind read(RustStream &);
    static void write(RustStream &, const HttpHostErrorKind &);
    static uint64_t allocation_size(const HttpHostErrorKind &);
};
struct FfiConverterHttpMethod {
    static HttpMethod lift(RustBuffer);
    static RustBuffer lower(const HttpMethod &);
    static HttpMethod read(RustStream &);
    static void write(RustStream &, const HttpMethod &);
    static uint64_t allocation_size(const HttpMethod &);
};

struct FfiConverterJournalHostError {
    static std::shared_ptr<JournalHostError> lift(RustBuffer buf);
    static RustBuffer lower(const JournalHostError &);
    static std::shared_ptr<JournalHostError> read(RustStream &stream);
    static void write(RustStream &stream, const JournalHostError &);
    static uint64_t allocation_size(const JournalHostError &);
};
struct FfiConverterJournalHostErrorKind {
    static JournalHostErrorKind lift(RustBuffer);
    static RustBuffer lower(const JournalHostErrorKind &);
    static JournalHostErrorKind read(RustStream &);
    static void write(RustStream &, const JournalHostErrorKind &);
    static uint64_t allocation_size(const JournalHostErrorKind &);
};
struct FfiConverterKeyRotationMessageKind {
    static KeyRotationMessageKind lift(RustBuffer);
    static RustBuffer lower(const KeyRotationMessageKind &);
    static KeyRotationMessageKind read(RustStream &);
    static void write(RustStream &, const KeyRotationMessageKind &);
    static uint64_t allocation_size(const KeyRotationMessageKind &);
};
struct FfiConverterMnemonicScheme {
    static MnemonicScheme lift(RustBuffer);
    static RustBuffer lower(const MnemonicScheme &);
    static MnemonicScheme read(RustStream &);
    static void write(RustStream &, const MnemonicScheme &);
    static uint64_t allocation_size(const MnemonicScheme &);
};
struct FfiConverterNetwork {
    static Network lift(RustBuffer);
    static RustBuffer lower(const Network &);
    static Network read(RustStream &);
    static void write(RustStream &, const Network &);
    static uint64_t allocation_size(const Network &);
};
struct FfiConverterNftTransferFunding {
    static NftTransferFunding lift(RustBuffer);
    static RustBuffer lower(const NftTransferFunding &);
    static NftTransferFunding read(RustStream &);
    static void write(RustStream &, const NftTransferFunding &);
    static uint64_t allocation_size(const NftTransferFunding &);
};
struct FfiConverterNftTransferPayload {
    static NftTransferPayload lift(RustBuffer);
    static RustBuffer lower(const NftTransferPayload &);
    static NftTransferPayload read(RustStream &);
    static void write(RustStream &, const NftTransferPayload &);
    static uint64_t allocation_size(const NftTransferPayload &);
};
struct FfiConverterPendingReason {
    static PendingReason lift(RustBuffer);
    static RustBuffer lower(const PendingReason &);
    static PendingReason read(RustStream &);
    static void write(RustStream &, const PendingReason &);
    static uint64_t allocation_size(const PendingReason &);
};

struct FfiConverterProtectedSecretHostError {
    static std::shared_ptr<ProtectedSecretHostError> lift(RustBuffer buf);
    static RustBuffer lower(const ProtectedSecretHostError &);
    static std::shared_ptr<ProtectedSecretHostError> read(RustStream &stream);
    static void write(RustStream &stream, const ProtectedSecretHostError &);
    static uint64_t allocation_size(const ProtectedSecretHostError &);
};
struct FfiConverterProtectedSecretHostErrorKind {
    static ProtectedSecretHostErrorKind lift(RustBuffer);
    static RustBuffer lower(const ProtectedSecretHostErrorKind &);
    static ProtectedSecretHostErrorKind read(RustStream &);
    static void write(RustStream &, const ProtectedSecretHostErrorKind &);
    static uint64_t allocation_size(const ProtectedSecretHostErrorKind &);
};
struct FfiConverterResourcePhase {
    static ResourcePhase lift(RustBuffer);
    static RustBuffer lower(const ResourcePhase &);
    static ResourcePhase read(RustStream &);
    static void write(RustStream &, const ResourcePhase &);
    static uint64_t allocation_size(const ResourcePhase &);
};
struct FfiConverterRetryAdvice {
    static RetryAdvice lift(RustBuffer);
    static RustBuffer lower(const RetryAdvice &);
    static RetryAdvice read(RustStream &);
    static void write(RustStream &, const RetryAdvice &);
    static uint64_t allocation_size(const RetryAdvice &);
};
struct FfiConverterSecretAccessReason {
    static SecretAccessReason lift(RustBuffer);
    static RustBuffer lower(const SecretAccessReason &);
    static SecretAccessReason read(RustStream &);
    static void write(RustStream &, const SecretAccessReason &);
    static uint64_t allocation_size(const SecretAccessReason &);
};
struct FfiConverterSendAmount {
    static SendAmount lift(RustBuffer);
    static RustBuffer lower(const SendAmount &);
    static SendAmount read(RustStream &);
    static void write(RustStream &, const SendAmount &);
    static uint64_t allocation_size(const SendAmount &);
};
struct FfiConverterSendExpiration {
    static SendExpiration lift(RustBuffer);
    static RustBuffer lower(const SendExpiration &);
    static SendExpiration read(RustStream &);
    static void write(RustStream &, const SendExpiration &);
    static uint64_t allocation_size(const SendExpiration &);
};
struct FfiConverterSendMessageBody {
    static SendMessageBody lift(RustBuffer);
    static RustBuffer lower(const SendMessageBody &);
    static SendMessageBody read(RustStream &);
    static void write(RustStream &, const SendMessageBody &);
    static uint64_t allocation_size(const SendMessageBody &);
};
struct FfiConverterSendPhase {
    static SendPhase lift(RustBuffer);
    static RustBuffer lower(const SendPhase &);
    static SendPhase read(RustStream &);
    static void write(RustStream &, const SendPhase &);
    static uint64_t allocation_size(const SendPhase &);
};

struct FfiConverterStatuslessHostError {
    static std::shared_ptr<StatuslessHostError> lift(RustBuffer buf);
    static RustBuffer lower(const StatuslessHostError &);
    static std::shared_ptr<StatuslessHostError> read(RustStream &stream);
    static void write(RustStream &stream, const StatuslessHostError &);
    static uint64_t allocation_size(const StatuslessHostError &);
};
struct FfiConverterStatuslessHostErrorKind {
    static StatuslessHostErrorKind lift(RustBuffer);
    static RustBuffer lower(const StatuslessHostErrorKind &);
    static StatuslessHostErrorKind read(RustStream &);
    static void write(RustStream &, const StatuslessHostErrorKind &);
    static uint64_t allocation_size(const StatuslessHostErrorKind &);
};

struct FfiConverterTonAddressError {
    static std::shared_ptr<TonAddressError> lift(RustBuffer buf);
    static RustBuffer lower(const TonAddressError &);
    static std::shared_ptr<TonAddressError> read(RustStream &stream);
    static void write(RustStream &stream, const TonAddressError &);
    static uint64_t allocation_size(const TonAddressError &);
};
struct FfiConverterTonAddressFormat {
    static TonAddressFormat lift(RustBuffer);
    static RustBuffer lower(const TonAddressFormat &);
    static TonAddressFormat read(RustStream &);
    static void write(RustStream &, const TonAddressFormat &);
    static uint64_t allocation_size(const TonAddressFormat &);
};
struct FfiConverterTonConnectConnectErrorCode {
    static TonConnectConnectErrorCode lift(RustBuffer);
    static RustBuffer lower(const TonConnectConnectErrorCode &);
    static TonConnectConnectErrorCode read(RustStream &);
    static void write(RustStream &, const TonConnectConnectErrorCode &);
    static uint64_t allocation_size(const TonConnectConnectErrorCode &);
};
struct FfiConverterTonConnectDevicePlatform {
    static TonConnectDevicePlatform lift(RustBuffer);
    static RustBuffer lower(const TonConnectDevicePlatform &);
    static TonConnectDevicePlatform read(RustStream &);
    static void write(RustStream &, const TonConnectDevicePlatform &);
    static uint64_t allocation_size(const TonConnectDevicePlatform &);
};
struct FfiConverterTonConnectIncomingRequest {
    static TonConnectIncomingRequest lift(RustBuffer);
    static RustBuffer lower(const TonConnectIncomingRequest &);
    static TonConnectIncomingRequest read(RustStream &);
    static void write(RustStream &, const TonConnectIncomingRequest &);
    static uint64_t allocation_size(const TonConnectIncomingRequest &);
};
struct FfiConverterTonConnectRpcErrorCode {
    static TonConnectRpcErrorCode lift(RustBuffer);
    static RustBuffer lower(const TonConnectRpcErrorCode &);
    static TonConnectRpcErrorCode read(RustStream &);
    static void write(RustStream &, const TonConnectRpcErrorCode &);
    static uint64_t allocation_size(const TonConnectRpcErrorCode &);
};

struct FfiConverterTonConnectSessionError {
    static std::shared_ptr<TonConnectSessionError> lift(RustBuffer buf);
    static RustBuffer lower(const TonConnectSessionError &);
    static std::shared_ptr<TonConnectSessionError> read(RustStream &stream);
    static void write(RustStream &stream, const TonConnectSessionError &);
    static uint64_t allocation_size(const TonConnectSessionError &);
};
struct FfiConverterTonConnectSessionPhase {
    static TonConnectSessionPhase lift(RustBuffer);
    static RustBuffer lower(const TonConnectSessionPhase &);
    static TonConnectSessionPhase read(RustStream &);
    static void write(RustStream &, const TonConnectSessionPhase &);
    static uint64_t allocation_size(const TonConnectSessionPhase &);
};
struct FfiConverterTonConnectSignDataPayload {
    static TonConnectSignDataPayload lift(RustBuffer);
    static RustBuffer lower(const TonConnectSignDataPayload &);
    static TonConnectSignDataPayload read(RustStream &);
    static void write(RustStream &, const TonConnectSignDataPayload &);
    static uint64_t allocation_size(const TonConnectSignDataPayload &);
};
struct FfiConverterTonTransferAsset {
    static TonTransferAsset lift(RustBuffer);
    static RustBuffer lower(const TonTransferAsset &);
    static TonTransferAsset read(RustStream &);
    static void write(RustStream &, const TonTransferAsset &);
    static uint64_t allocation_size(const TonTransferAsset &);
};

struct FfiConverterTonTransferLinkError {
    static std::shared_ptr<TonTransferLinkError> lift(RustBuffer buf);
    static RustBuffer lower(const TonTransferLinkError &);
    static std::shared_ptr<TonTransferLinkError> read(RustStream &stream);
    static void write(RustStream &stream, const TonTransferLinkError &);
    static uint64_t allocation_size(const TonTransferLinkError &);
};
struct FfiConverterTonTransferPayload {
    static TonTransferPayload lift(RustBuffer);
    static RustBuffer lower(const TonTransferPayload &);
    static TonTransferPayload read(RustStream &);
    static void write(RustStream &, const TonTransferPayload &);
    static uint64_t allocation_size(const TonTransferPayload &);
};

struct FfiConverterWalletClientError {
    static std::shared_ptr<WalletClientError> lift(RustBuffer buf);
    static RustBuffer lower(const WalletClientError &);
    static std::shared_ptr<WalletClientError> read(RustStream &stream);
    static void write(RustStream &stream, const WalletClientError &);
    static uint64_t allocation_size(const WalletClientError &);
};

struct FfiConverterWalletLifecycleError {
    static std::shared_ptr<WalletLifecycleError> lift(RustBuffer buf);
    static RustBuffer lower(const WalletLifecycleError &);
    static std::shared_ptr<WalletLifecycleError> read(RustStream &stream);
    static void write(RustStream &stream, const WalletLifecycleError &);
    static uint64_t allocation_size(const WalletLifecycleError &);
};
struct FfiConverterWalletOperationOutcome {
    static WalletOperationOutcome lift(RustBuffer);
    static RustBuffer lower(const WalletOperationOutcome &);
    static WalletOperationOutcome read(RustStream &);
    static void write(RustStream &, const WalletOperationOutcome &);
    static uint64_t allocation_size(const WalletOperationOutcome &);
};
struct FfiConverterOptionalUInt16 {
    static std::optional<uint16_t> lift(RustBuffer buf);
    static RustBuffer lower(const std::optional<uint16_t>& val);
    static std::optional<uint16_t> read(RustStream &stream);
    static void write(RustStream &stream, const std::optional<uint16_t>& value);
    static uint64_t allocation_size(const std::optional<uint16_t> &val);
};
struct FfiConverterOptionalInt32 {
    static std::optional<int32_t> lift(RustBuffer buf);
    static RustBuffer lower(const std::optional<int32_t>& val);
    static std::optional<int32_t> read(RustStream &stream);
    static void write(RustStream &stream, const std::optional<int32_t>& value);
    static uint64_t allocation_size(const std::optional<int32_t> &val);
};
struct FfiConverterOptionalUInt64 {
    static std::optional<uint64_t> lift(RustBuffer buf);
    static RustBuffer lower(const std::optional<uint64_t>& val);
    static std::optional<uint64_t> read(RustStream &stream);
    static void write(RustStream &stream, const std::optional<uint64_t>& value);
    static uint64_t allocation_size(const std::optional<uint64_t> &val);
};
struct FfiConverterOptionalInt64 {
    static std::optional<int64_t> lift(RustBuffer buf);
    static RustBuffer lower(const std::optional<int64_t>& val);
    static std::optional<int64_t> read(RustStream &stream);
    static void write(RustStream &stream, const std::optional<int64_t>& value);
    static uint64_t allocation_size(const std::optional<int64_t> &val);
};
struct FfiConverterOptionalBool {
    static std::optional<bool> lift(RustBuffer buf);
    static RustBuffer lower(const std::optional<bool>& val);
    static std::optional<bool> read(RustStream &stream);
    static void write(RustStream &stream, const std::optional<bool>& value);
    static uint64_t allocation_size(const std::optional<bool> &val);
};
struct FfiConverterOptionalString {
    static std::optional<std::string> lift(RustBuffer buf);
    static RustBuffer lower(const std::optional<std::string>& val);
    static std::optional<std::string> read(RustStream &stream);
    static void write(RustStream &stream, const std::optional<std::string>& value);
    static uint64_t allocation_size(const std::optional<std::string> &val);
};
struct FfiConverterOptionalBytes {
    static std::optional<std::vector<uint8_t>> lift(RustBuffer buf);
    static RustBuffer lower(const std::optional<std::vector<uint8_t>>& val);
    static std::optional<std::vector<uint8_t>> read(RustStream &stream);
    static void write(RustStream &stream, const std::optional<std::vector<uint8_t>>& value);
    static uint64_t allocation_size(const std::optional<std::vector<uint8_t>> &val);
};
struct FfiConverterOptionalTypeAccountSnapshot {
    static std::optional<AccountSnapshot> lift(RustBuffer buf);
    static RustBuffer lower(const std::optional<AccountSnapshot>& val);
    static std::optional<AccountSnapshot> read(RustStream &stream);
    static void write(RustStream &stream, const std::optional<AccountSnapshot>& value);
    static uint64_t allocation_size(const std::optional<AccountSnapshot> &val);
};
struct FfiConverterOptionalTypeActivityCursor {
    static std::optional<ActivityCursor> lift(RustBuffer buf);
    static RustBuffer lower(const std::optional<ActivityCursor>& val);
    static std::optional<ActivityCursor> read(RustStream &stream);
    static void write(RustStream &stream, const std::optional<ActivityCursor>& value);
    static uint64_t allocation_size(const std::optional<ActivityCursor> &val);
};
struct FfiConverterOptionalTypeDomainError {
    static std::optional<DomainError> lift(RustBuffer buf);
    static RustBuffer lower(const std::optional<DomainError>& val);
    static std::optional<DomainError> read(RustStream &stream);
    static void write(RustStream &stream, const std::optional<DomainError>& value);
    static uint64_t allocation_size(const std::optional<DomainError> &val);
};
struct FfiConverterOptionalTypeJournalRecord {
    static std::optional<JournalRecord> lift(RustBuffer buf);
    static RustBuffer lower(const std::optional<JournalRecord>& val);
    static std::optional<JournalRecord> read(RustStream &stream);
    static void write(RustStream &stream, const std::optional<JournalRecord>& value);
    static uint64_t allocation_size(const std::optional<JournalRecord> &val);
};
struct FfiConverterOptionalTypeNftCollectionDescriptor {
    static std::optional<NftCollectionDescriptor> lift(RustBuffer buf);
    static RustBuffer lower(const std::optional<NftCollectionDescriptor>& val);
    static std::optional<NftCollectionDescriptor> read(RustStream &stream);
    static void write(RustStream &stream, const std::optional<NftCollectionDescriptor>& value);
    static uint64_t allocation_size(const std::optional<NftCollectionDescriptor> &val);
};
struct FfiConverterOptionalTypeProtectedSecretRef {
    static std::optional<ProtectedSecretRef> lift(RustBuffer buf);
    static RustBuffer lower(const std::optional<ProtectedSecretRef>& val);
    static std::optional<ProtectedSecretRef> read(RustStream &stream);
    static void write(RustStream &stream, const std::optional<ProtectedSecretRef>& value);
    static uint64_t allocation_size(const std::optional<ProtectedSecretRef> &val);
};
struct FfiConverterOptionalTypeResolutionInfo {
    static std::optional<ResolutionInfo> lift(RustBuffer buf);
    static RustBuffer lower(const std::optional<ResolutionInfo>& val);
    static std::optional<ResolutionInfo> read(RustStream &stream);
    static void write(RustStream &stream, const std::optional<ResolutionInfo>& value);
    static uint64_t allocation_size(const std::optional<ResolutionInfo> &val);
};
struct FfiConverterOptionalTypeTonConnectConnectPrompt {
    static std::optional<TonConnectConnectPrompt> lift(RustBuffer buf);
    static RustBuffer lower(const std::optional<TonConnectConnectPrompt>& val);
    static std::optional<TonConnectConnectPrompt> read(RustStream &stream);
    static void write(RustStream &stream, const std::optional<TonConnectConnectPrompt>& value);
    static uint64_t allocation_size(const std::optional<TonConnectConnectPrompt> &val);
};
struct FfiConverterOptionalTypeTonConnectPreparedPost {
    static std::optional<TonConnectPreparedPost> lift(RustBuffer buf);
    static RustBuffer lower(const std::optional<TonConnectPreparedPost>& val);
    static std::optional<TonConnectPreparedPost> read(RustStream &stream);
    static void write(RustStream &stream, const std::optional<TonConnectPreparedPost>& value);
    static uint64_t allocation_size(const std::optional<TonConnectPreparedPost> &val);
};
struct FfiConverterOptionalTypeTonConnectProofReply {
    static std::optional<TonConnectProofReply> lift(RustBuffer buf);
    static RustBuffer lower(const std::optional<TonConnectProofReply>& val);
    static std::optional<TonConnectProofReply> read(RustStream &stream);
    static void write(RustStream &stream, const std::optional<TonConnectProofReply>& value);
    static uint64_t allocation_size(const std::optional<TonConnectProofReply> &val);
};
struct FfiConverterOptionalHttpHostErrorKind {
    static std::optional<HttpHostErrorKind> lift(RustBuffer buf);
    static RustBuffer lower(const std::optional<HttpHostErrorKind>& val);
    static std::optional<HttpHostErrorKind> read(RustStream &stream);
    static void write(RustStream &stream, const std::optional<HttpHostErrorKind>& value);
    static uint64_t allocation_size(const std::optional<HttpHostErrorKind> &val);
};
struct FfiConverterOptionalPendingReason {
    static std::optional<PendingReason> lift(RustBuffer buf);
    static RustBuffer lower(const std::optional<PendingReason>& val);
    static std::optional<PendingReason> read(RustStream &stream);
    static void write(RustStream &stream, const std::optional<PendingReason>& value);
    static uint64_t allocation_size(const std::optional<PendingReason> &val);
};
struct FfiConverterOptionalTypeBase64Hash {
    static std::optional<Base64Hash> lift(RustBuffer buf);
    static RustBuffer lower(const std::optional<Base64Hash>& val);
    static std::optional<Base64Hash> read(RustStream &stream);
    static void write(RustStream &stream, const std::optional<Base64Hash>& value);
    static uint64_t allocation_size(const std::optional<Base64Hash> &val);
};
struct FfiConverterOptionalTypeBoc {
    static std::optional<Boc> lift(RustBuffer buf);
    static RustBuffer lower(const std::optional<Boc>& val);
    static std::optional<Boc> read(RustStream &stream);
    static void write(RustStream &stream, const std::optional<Boc>& value);
    static uint64_t allocation_size(const std::optional<Boc> &val);
};
struct FfiConverterOptionalTypeNonEmptyString {
    static std::optional<NonEmptyString> lift(RustBuffer buf);
    static RustBuffer lower(const std::optional<NonEmptyString>& val);
    static std::optional<NonEmptyString> read(RustStream &stream);
    static void write(RustStream &stream, const std::optional<NonEmptyString>& value);
    static uint64_t allocation_size(const std::optional<NonEmptyString> &val);
};
struct FfiConverterOptionalTypeTonAddressString {
    static std::optional<TonAddressString> lift(RustBuffer buf);
    static RustBuffer lower(const std::optional<TonAddressString>& val);
    static std::optional<TonAddressString> read(RustStream &stream);
    static void write(RustStream &stream, const std::optional<TonAddressString>& value);
    static uint64_t allocation_size(const std::optional<TonAddressString> &val);
};
struct FfiConverterOptionalTypeUnsignedDecimalString {
    static std::optional<UnsignedDecimalString> lift(RustBuffer buf);
    static RustBuffer lower(const std::optional<UnsignedDecimalString>& val);
    static std::optional<UnsignedDecimalString> read(RustStream &stream);
    static void write(RustStream &stream, const std::optional<UnsignedDecimalString>& value);
    static uint64_t allocation_size(const std::optional<UnsignedDecimalString> &val);
};

struct FfiConverterSequenceString {
    static std::vector<std::string> lift(RustBuffer);
    static RustBuffer lower(const std::vector<std::string> &);
    static std::vector<std::string> read(RustStream &);
    static void write(RustStream &, const std::vector<std::string> &);
    static uint64_t allocation_size(const std::vector<std::string> &);
};

struct FfiConverterSequenceTypeActivityItem {
    static std::vector<ActivityItem> lift(RustBuffer);
    static RustBuffer lower(const std::vector<ActivityItem> &);
    static std::vector<ActivityItem> read(RustStream &);
    static void write(RustStream &, const std::vector<ActivityItem> &);
    static uint64_t allocation_size(const std::vector<ActivityItem> &);
};

struct FfiConverterSequenceTypeHttpHeader {
    static std::vector<HttpHeader> lift(RustBuffer);
    static RustBuffer lower(const std::vector<HttpHeader> &);
    static std::vector<HttpHeader> read(RustStream &);
    static void write(RustStream &, const std::vector<HttpHeader> &);
    static uint64_t allocation_size(const std::vector<HttpHeader> &);
};

struct FfiConverterSequenceTypeNftItem {
    static std::vector<NftItem> lift(RustBuffer);
    static RustBuffer lower(const std::vector<NftItem> &);
    static std::vector<NftItem> read(RustStream &);
    static void write(RustStream &, const std::vector<NftItem> &);
    static uint64_t allocation_size(const std::vector<NftItem> &);
};

struct FfiConverterSequenceTypeSendEmulationAction {
    static std::vector<SendEmulationAction> lift(RustBuffer);
    static RustBuffer lower(const std::vector<SendEmulationAction> &);
    static std::vector<SendEmulationAction> read(RustStream &);
    static void write(RustStream &, const std::vector<SendEmulationAction> &);
    static uint64_t allocation_size(const std::vector<SendEmulationAction> &);
};

struct FfiConverterSequenceTypeSendEmulationTransaction {
    static std::vector<SendEmulationTransaction> lift(RustBuffer);
    static RustBuffer lower(const std::vector<SendEmulationTransaction> &);
    static std::vector<SendEmulationTransaction> read(RustStream &);
    static void write(RustStream &, const std::vector<SendEmulationTransaction> &);
    static uint64_t allocation_size(const std::vector<SendEmulationTransaction> &);
};

struct FfiConverterSequenceTypeSendMessage {
    static std::vector<SendMessage> lift(RustBuffer);
    static RustBuffer lower(const std::vector<SendMessage> &);
    static std::vector<SendMessage> read(RustStream &);
    static void write(RustStream &, const std::vector<SendMessage> &);
    static uint64_t allocation_size(const std::vector<SendMessage> &);
};

struct FfiConverterSequenceMnemonicScheme {
    static std::vector<MnemonicScheme> lift(RustBuffer);
    static RustBuffer lower(const std::vector<MnemonicScheme> &);
    static std::vector<MnemonicScheme> read(RustStream &);
    static void write(RustStream &, const std::vector<MnemonicScheme> &);
    static uint64_t allocation_size(const std::vector<MnemonicScheme> &);
};

struct FfiConverterSequenceTonConnectIncomingRequest {
    static std::vector<TonConnectIncomingRequest> lift(RustBuffer);
    static RustBuffer lower(const std::vector<TonConnectIncomingRequest> &);
    static std::vector<TonConnectIncomingRequest> read(RustStream &);
    static void write(RustStream &, const std::vector<TonConnectIncomingRequest> &);
    static uint64_t allocation_size(const std::vector<TonConnectIncomingRequest> &);
};

struct FfiConverterSequenceTypeBase64Hash {
    static std::vector<Base64Hash> lift(RustBuffer);
    static RustBuffer lower(const std::vector<Base64Hash> &);
    static std::vector<Base64Hash> read(RustStream &);
    static void write(RustStream &, const std::vector<Base64Hash> &);
    static uint64_t allocation_size(const std::vector<Base64Hash> &);
};

struct FfiConverterSequenceTypeTonAddressString {
    static std::vector<TonAddressString> lift(RustBuffer);
    static RustBuffer lower(const std::vector<TonAddressString> &);
    static std::vector<TonAddressString> read(RustStream &);
    static void write(RustStream &, const std::vector<TonAddressString> &);
    static uint64_t allocation_size(const std::vector<TonAddressString> &);
};

struct FfiConverterMapStringString {
    static std::unordered_map<std::string, std::string> lift(RustBuffer);
    static RustBuffer lower(const std::unordered_map<std::string, std::string> &);
    static std::unordered_map<std::string, std::string> read(RustStream &);
    static void write(RustStream &, const std::unordered_map<std::string, std::string> &);
    static uint64_t allocation_size(const std::unordered_map<std::string, std::string> &);
};
typedef struct FfiConverterString FfiConverterTypeBase64Hash;
typedef struct FfiConverterString FfiConverterTypeBoc;
typedef struct FfiConverterString FfiConverterTypeNonEmptyString;
typedef struct FfiConverterString FfiConverterTypeTonAddressString;
typedef struct FfiConverterString FfiConverterTypeUnsignedDecimalString;
}

ParsedTonTransferLink parse_ton_transfer_link(const std::string &value);
TonConnectManifest parse_ton_connect_manifest(const std::string &json);
std::shared_ptr<TonConnectSession> ton_connect_session_from_link(const std::string &link, const TonConnectSessionConfig &config);
std::shared_ptr<TonConnectSession> ton_connect_session_restore(const std::string &persisted, const TonConnectSessionConfig &config);
std::string convert_ton_address(const std::string &value, TonAddressFormat format);
bool is_valid_ton_address(const std::string &value);
TonAddressInfo parse_ton_address(const std::string &value);
std::vector<std::string> mnemonic_wordlist();
std::vector<MnemonicScheme> detect_mnemonic_schemes(const std::vector<std::string> &words);
std::vector<uint8_t> rotation_mnemonic_public_key(const std::string &phrase);
}