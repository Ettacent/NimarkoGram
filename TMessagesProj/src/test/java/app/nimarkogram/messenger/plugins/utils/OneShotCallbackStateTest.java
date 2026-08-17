package app.nimarkogram.messenger.plugins.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public final class OneShotCallbackStateTest {

    public static void main(String[] args) throws Exception {
        initialInvocationConsumesCallbackOnce();
        deferredInvocationHasOneRetryOwner();
        concurrentInitialInvocationsHaveOneWinner();
        dropIsTerminalAndClearsCallback();
    }

    private static void initialInvocationConsumesCallbackOnce() {
        Object callback = new Object();
        OneShotCallbackState<Object> state =
                new OneShotCallbackState<>(callback);

        require(state.beginInitialInvocation(), "initial call must win");
        require(!state.beginInitialInvocation(), "second initial call must lose");
        require(state.takeForExecution() == callback, "callback identity changed");
        require(state.takeForExecution() == null, "callback executed twice");
        state.complete();
        require(state.getStateForTests() == OneShotCallbackState.State.DONE,
                "completed callback is not terminal");
    }

    private static void deferredInvocationHasOneRetryOwner() {
        Object callback = new Object();
        OneShotCallbackState<Object> state =
                new OneShotCallbackState<>(callback);

        require(state.beginInitialInvocation(), "initial call must win");
        require(state.deferInitialInvocation(), "initial call must defer");
        require(!state.beginInitialInvocation(),
                "external call stole deferred callback");
        require(state.beginOnlyRetry(), "scheduled retry must win");
        require(!state.beginOnlyRetry(), "second retry must lose");
        require(state.takeForExecution() == callback,
                "retry did not retain exact callback");
        state.complete();
    }

    private static void concurrentInitialInvocationsHaveOneWinner()
            throws Exception {
        OneShotCallbackState<Object> state =
                new OneShotCallbackState<>(new Object());
        int workers = 32;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        List<Thread> threads = new ArrayList<>(workers);

        for (int i = 0; i < workers; i++) {
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
                if (state.beginInitialInvocation()) {
                    winners.incrementAndGet();
                }
            }, "callback-state-test-" + i);
            threads.add(thread);
            thread.start();
        }

        ready.await();
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
        require(winners.get() == 1,
                "concurrent invocation had " + winners.get() + " winners");
        require(state.takeForExecution() != null,
                "winning invocation lost callback");
        require(state.takeForExecution() == null,
                "callback was consumed more than once");
        state.complete();
    }

    private static void dropIsTerminalAndClearsCallback() {
        OneShotCallbackState<Object> state =
                new OneShotCallbackState<>(new Object());
        state.drop();
        require(state.getStateForTests() == OneShotCallbackState.State.DROPPED,
                "drop is not terminal");
        require(!state.beginInitialInvocation(),
                "dropped callback became runnable");
        require(state.takeForExecution() == null,
                "dropped callback reference was retained");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private OneShotCallbackStateTest() {
    }
}
