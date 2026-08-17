package app.nimarkogram.messenger.camera;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.lifecycle.ProcessCameraProvider;

import org.telegram.messenger.FileLog;

import app.nimarkogram.messenger.NimarkoCameraLog;

final class CameraXProviderCoordinator {

    interface Owner {
        void onCameraXOwnershipInvalidated(long generation);
    }

    interface Operation<T> {
        T run() throws Exception;
    }

    private static long generation;
    @Nullable private static ProcessCameraProvider activeProvider;
    @Nullable private static Owner primaryOwner;
    @Nullable private static Owner secondaryOwner;
    private static boolean notifyingInvalidation;

    private CameraXProviderCoordinator() {
    }

    static synchronized <T> T withSingleOwner(
            @NonNull ProcessCameraProvider provider,
            @NonNull Owner owner,
            @NonNull Operation<T> operation) throws Exception {
        if (notifyingInvalidation) {
            throw new IllegalStateException(
                    "CameraX ownership cannot change from an invalidation callback");
        }
        if (activeProvider != provider || primaryOwner != owner || secondaryOwner != null) {
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXOwner single acquire owner=" + ownerName(owner)
                    + " previousPrimary=" + ownerName(primaryOwner)
                    + " previousSecondary=" + ownerName(secondaryOwner)
                    + " generation=" + generation);
            resetActiveGraphLocked();
            activeProvider = provider;
            primaryOwner = owner;
            secondaryOwner = null;
            ++generation;
        }
        return operation.run();
    }

    static synchronized boolean withConcurrentOwners(
            @NonNull ProcessCameraProvider provider,
            @NonNull Owner first,
            @NonNull Owner second,
            @NonNull Operation<Boolean> operation) throws Exception {
        if (notifyingInvalidation) {
            throw new IllegalStateException(
                    "CameraX ownership cannot change from an invalidation callback");
        }
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXOwner concurrent acquire first=" + ownerName(first)
                + " second=" + ownerName(second)
                + " previousPrimary=" + ownerName(primaryOwner)
                + " previousSecondary=" + ownerName(secondaryOwner)
                + " generation=" + generation);
        
        resetActiveGraphLocked();
        activeProvider = provider;
        primaryOwner = first;
        secondaryOwner = second;
        ++generation;
        try {
            boolean bound = Boolean.TRUE.equals(operation.run());
            if (!bound) {
                resetActiveGraphLocked();
            }
            return bound;
        } catch (Exception error) {
            resetActiveGraphLocked();
            throw error;
        } catch (Throwable error) {
            resetActiveGraphLocked();
            throw new RuntimeException(error);
        }
    }

    static synchronized boolean runIfSingleOwner(
            @Nullable ProcessCameraProvider provider,
            @NonNull Owner owner,
            @NonNull Operation<Boolean> operation) {
        if (provider == null || activeProvider != provider
                || primaryOwner != owner || secondaryOwner != null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(operation.run());
        } catch (Throwable error) {
            FileLog.e("CameraX owned operation failed", error);
            return false;
        }
    }

    static synchronized void release(
            @Nullable ProcessCameraProvider provider,
            @NonNull Owner owner,
            @NonNull Operation<Boolean> unbindSingleOperation) {
        if (provider == null || activeProvider != provider
                || primaryOwner != owner && secondaryOwner != owner) {
            return;
        }
        if (secondaryOwner != null) {
            resetActiveGraphLocked();
            return;
        }

        activeProvider = null;
        primaryOwner = null;
        ++generation;
        try {
            unbindSingleOperation.run();
        } catch (Throwable error) {
            FileLog.e("CameraX single-owner release failed", error);
        }
    }

    static synchronized boolean isOwner(@NonNull Owner owner) {
        return primaryOwner == owner || secondaryOwner == owner;
    }

    static synchronized boolean isConcurrentPair(
            @NonNull Owner first, @NonNull Owner second) {
        return primaryOwner == first && secondaryOwner == second
                || primaryOwner == second && secondaryOwner == first;
    }

    private static void resetActiveGraphLocked() {
        ProcessCameraProvider provider = activeProvider;
        Owner first = primaryOwner;
        Owner second = secondaryOwner;

        activeProvider = null;
        primaryOwner = null;
        secondaryOwner = null;
        long invalidationGeneration = ++generation;

        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXOwner reset generation=" + invalidationGeneration
                + " provider=" + (provider != null)
                + " first=" + ownerName(first)
                + " second=" + ownerName(second));

        if (provider != null) {
            try {
                provider.unbindAll();
                if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXOwner unbindAll dispatched generation="
                        + invalidationGeneration);
            } catch (Throwable error) {
                FileLog.e("CameraX provider reset failed", error);
                if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXOwner unbindAll FAILED generation="
                        + invalidationGeneration, error);
            }
        }
        
        notifyingInvalidation = true;
        try {
            if (first != null) {
                first.onCameraXOwnershipInvalidated(invalidationGeneration);
            }
            if (second != null && second != first) {
                second.onCameraXOwnershipInvalidated(invalidationGeneration);
            }
        } finally {
            notifyingInvalidation = false;
        }
    }

    private static String ownerName(@Nullable Owner owner) {
        return owner == null ? "null"
                : owner.getClass().getSimpleName() + '@'
                + Integer.toHexString(System.identityHashCode(owner));
    }
}
