package app.nimarkogram.messenger.plugins.utils;

import java.util.concurrent.atomic.AtomicReference;

final class OneShotCallbackState<T> {

    enum State {
        PENDING,
        WAITING,
        RUNNING,
        DONE,
        DROPPED
    }

    private final AtomicReference<T> callback;
    private final AtomicReference<State> state =
            new AtomicReference<>(State.PENDING);

    OneShotCallbackState(T callback) {
        if (callback == null) {
            throw new IllegalArgumentException("Callback cannot be null");
        }
        this.callback = new AtomicReference<>(callback);
    }

    boolean beginInitialInvocation() {
        return state.compareAndSet(State.PENDING, State.RUNNING);
    }

    boolean deferInitialInvocation() {
        return state.compareAndSet(State.RUNNING, State.WAITING);
    }

    boolean beginOnlyRetry() {
        return state.compareAndSet(State.WAITING, State.RUNNING);
    }

    T takeForExecution() {
        if (state.get() != State.RUNNING) {
            return null;
        }
        return callback.getAndSet(null);
    }

    void complete() {
        state.compareAndSet(State.RUNNING, State.DONE);
        callback.getAndSet(null);
    }

    void drop() {
        callback.getAndSet(null);
        while (true) {
            State current = state.get();
            if (current == State.DONE || current == State.DROPPED) {
                return;
            }
            if (state.compareAndSet(current, State.DROPPED)) {
                return;
            }
        }
    }

    State getStateForTests() {
        return state.get();
    }
}
