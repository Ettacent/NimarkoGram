/* Modifications Copyright (C) 2026 Ettacent */

#pragma once

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <functional>
#include <future>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <type_traits>

namespace twidget {

class JSThread {
public:
    static constexpr size_t kStackSize = size_t(8) << 20;
    static constexpr size_t kJSStackLimit = size_t(6) << 20;

    explicit JSThread(std::string name);
    ~JSThread();
    JSThread(const JSThread&) = delete;
    JSThread& operator=(const JSThread&) = delete;

    bool isCurrent() const { return std::this_thread::get_id() == id_; }

    void async(std::function<void()> fn);

    template <class F>
    auto sync(F&& fn) -> std::invoke_result_t<F> {
        using R = std::invoke_result_t<F>;
        if (isCurrent()) return fn();
        std::promise<R> promise;
        auto future = promise.get_future();
        bool queued = post([&] {
            if constexpr (std::is_void_v<R>) { fn(); promise.set_value(); }
            else promise.set_value(fn());
        });
        if (!queued) {
            if constexpr (std::is_void_v<R>) return;
            else return R{};
        }
        return future.get();
    }

    class Scheduled {
    public:
        void cancel() { cancelled_.store(true, std::memory_order_release); }
        bool cancelled() const { return cancelled_.load(std::memory_order_acquire); }
    private:
        std::atomic<bool> cancelled_{false};
    };

    std::shared_ptr<Scheduled> after(double seconds, std::function<void()> fn);
    size_t queuedTimers();

    void stop();
    void join();

private:
    struct Timer {
        std::shared_ptr<Scheduled> handle;
        std::function<void()> fn;
    };
    bool post(std::function<void()> fn);
    void run();
    static void* entry(void* self);

    std::string name_;
    std::mutex mutex_;
    std::condition_variable cv_;
    std::deque<std::function<void()>> tasks_;
    std::multimap<std::chrono::steady_clock::time_point, Timer> timers_;
    size_t sweepAt_ = 64;
    bool stopRequested_ = false;
    bool stopping_ = false;
    bool joined_ = false;
    std::atomic<std::thread::id> id_{};
    void* handle_ = nullptr;
};

}
