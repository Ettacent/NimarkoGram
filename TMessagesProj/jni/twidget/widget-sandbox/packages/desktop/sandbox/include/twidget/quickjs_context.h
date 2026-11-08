/* Modifications Copyright (C) 2026 Ettacent */

#pragma once

#include <cstdint>
#include <functional>
#include <limits>
#include <optional>
#include <stdexcept>
#include <string>
#include <vector>

struct JSRuntime;
struct JSContext;

namespace twidget {

class JSError : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

struct HostReturn {
    enum class Kind { Undefined, Null, String, Number, ArrayBuffer };
    Kind kind = Kind::Undefined;
    std::string str;
    double num = 0;
    std::vector<uint8_t> bytes;

    static HostReturn undefined() { return {}; }
    static HostReturn null() { HostReturn r; r.kind = Kind::Null; return r; }
    static HostReturn string(std::string s) { HostReturn r; r.kind = Kind::String; r.str = std::move(s); return r; }
    static HostReturn number(double d) { HostReturn r; r.kind = Kind::Number; r.num = d; return r; }
    static HostReturn arrayBuffer(std::vector<uint8_t> b) { HostReturn r; r.kind = Kind::ArrayBuffer; r.bytes = std::move(b); return r; }
};

struct JSResult {
    std::optional<std::string> string;
    std::optional<double> number;
};

class QuickJSContext {
public:
    using HostFunction = std::function<HostReturn(const std::vector<std::string>&)>;

    explicit QuickJSContext(size_t memoryLimitBytes, size_t maxStackBytes = size_t(384) << 10);
    ~QuickJSContext();
    QuickJSContext(const QuickJSContext&) = delete;
    QuickJSContext& operator=(const QuickJSContext&) = delete;

    double deadline = std::numeric_limits<double>::infinity();
    bool interrupted = false;

    double cpuDeadline = std::numeric_limits<double>::infinity();

    static double now();
    static double threadCpuTime();

    size_t memoryUsed() const;

    void registerFunction(const std::string& name, HostFunction fn);

    JSResult evaluate(const std::string& code, const std::string& filename, bool asModule = false);
    JSResult evaluateBytecode(const std::vector<uint8_t>& blob, const std::string& source);
    std::vector<uint8_t> compileBytecode(const std::string& code, const std::string& filename);
    JSResult call(const std::string& name, const std::vector<std::string>& args = {});

    bool captureGlobalFunction(const std::string& name);

    bool hasPendingJob() const;
    void executePendingJobs(const std::function<void(const JSError&)>& onError);

private:
    void enter();
    JSError takeException();

    JSRuntime* runtime_;
    JSContext* context_;
    std::vector<HostFunction> functions_;
    struct Captured;
    std::vector<Captured*> captured_;
    friend struct QuickJSTrampoline;
};

}
