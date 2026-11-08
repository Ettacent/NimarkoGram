/* Modifications Copyright (C) 2026 Ettacent */
package app.nimarkogram.messenger.plugins.utils;

import org.telegram.tgnet.TLObject;
import app.nimarkogram.messenger.plugins.PluginsController;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class ManagedResponseLease {
    private static final class Shared implements Runnable {
        final AtomicInteger holds = new AtomicInteger(1);
        final AtomicReference<TLObject> response;

        Shared(TLObject response) {
            this.response = new AtomicReference<>(response);
        }

        @Override
        public void run() {
            TLObject original = response.getAndSet(null);
            if (original != null) original.freeResources();
        }
    }

    private final Shared shared;
    private final AtomicBoolean released = new AtomicBoolean();

    public ManagedResponseLease(TLObject response) {
        shared = new Shared(response);
    }

    private ManagedResponseLease(Shared shared) {
        this.shared = shared;
    }

    public ManagedResponseLease retain() {
        ManagedResponseLease handle = new ManagedResponseLease(shared);
        int count;
        do {
            count = shared.holds.get();
            if (released.get() || count <= 0) {
                throw new IllegalStateException("Response lease already released");
            }
        } while (!shared.holds.compareAndSet(count, count + 1));
        return handle;
    }

    public void release() {
        release(null);
    }

    public void release(Throwable bodyFailure) {
        if (!released.compareAndSet(false, true)) return;
        if (shared.holds.decrementAndGet() == 0) {
            PluginsController.getInstance().releaseRuntimeResource(shared, bodyFailure);
        }
    }
}
