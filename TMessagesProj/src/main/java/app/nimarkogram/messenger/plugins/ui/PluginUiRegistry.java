package app.nimarkogram.messenger.plugins.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.LaunchActivity;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import app.nimarkogram.messenger.plugins.PluginDebugLog;
import app.nimarkogram.messenger.plugins.PluginsController;

public final class PluginUiRegistry {

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ConcurrentHashMap<PluginsController.PluginRuntimeToken, UiBucket>
            UI_BY_RUNTIME = new ConcurrentHashMap<>();
     
    private static final WeakHashMap<ViewGroup,
            HashMap<String, WeakReference<PluginOverlayHost>>>
            OVERLAY_HOSTS = new WeakHashMap<>();

    private PluginUiRegistry() {
    }

    public interface RuntimeOwnedUi {
        void clearPluginUiReferences(
                PluginsController.PluginRuntimeToken runtimeToken);
    }

    private interface UiEntry {
        boolean isFor(Object owner);
        boolean isAlive();
        void teardown();
    }

    private static final class UiBucket {
        final ArrayList<UiEntry> entries = new ArrayList<>();
    }

    private static final class DialogEntry implements UiEntry {
        final WeakReference<Dialog> dialogReference;
        final boolean cancelOnCleanup;
        final boolean suppressCallbacks;

        DialogEntry(
                Dialog dialog,
                boolean cancelOnCleanup,
                boolean suppressCallbacks) {
            this.dialogReference = new WeakReference<>(dialog);
            this.cancelOnCleanup = cancelOnCleanup;
            this.suppressCallbacks = suppressCallbacks;
        }

        @Override
        public boolean isFor(Object owner) {
            return dialogReference.get() == owner;
        }

        @Override
        public boolean isAlive() {
            return dialogReference.get() != null;
        }

        @Override
        public void teardown() {
            Dialog dialog = dialogReference.get();
            if (dialog != null) {
                teardownDialog(dialog, cancelOnCleanup, suppressCallbacks);
            }
        }
    }

    private static final class BulletinEntry implements UiEntry {
        final WeakReference<Bulletin> bulletinReference;

        BulletinEntry(Bulletin bulletin) {
            this.bulletinReference = new WeakReference<>(bulletin);
        }

        @Override
        public boolean isFor(Object owner) {
            return bulletinReference.get() == owner;
        }

        @Override
        public boolean isAlive() {
            return bulletinReference.get() != null;
        }

        @Override
        public void teardown() {
            Bulletin bulletin = bulletinReference.get();
            if (bulletin == null) {
                return;
            }
            try {
                if (bulletin.isShowing()) {
                    
                    bulletin.hide(false, 0);
                }
            } catch (Throwable t) {
                FileLog.e("Unable to hide plugin bulletin", t);
            }
        }
    }

    private static final class RuntimeOwnedEntry implements UiEntry {
        final WeakReference<RuntimeOwnedUi> ownerReference;
        final PluginsController.PluginRuntimeToken runtimeToken;

        RuntimeOwnedEntry(
                RuntimeOwnedUi owner,
                PluginsController.PluginRuntimeToken runtimeToken) {
            this.ownerReference = new WeakReference<>(owner);
            this.runtimeToken = runtimeToken;
        }

        @Override
        public boolean isFor(Object owner) {
            return ownerReference.get() == owner;
        }

        @Override
        public boolean isAlive() {
            return ownerReference.get() != null;
        }

        @Override
        public void teardown() {
            RuntimeOwnedUi owner = ownerReference.get();
            if (owner == null) {
                return;
            }
            try {
                owner.clearPluginUiReferences(runtimeToken);
            } catch (Throwable t) {
                FileLog.e("Unable to clear plugin UI delegate", t);
            }
        }
    }

    private static final class OverlayViewEntry implements UiEntry {
        final WeakReference<View> viewReference;
        final WeakReference<PluginOverlayHost> hostReference;

        OverlayViewEntry(View view, PluginOverlayHost host) {
            this.viewReference = new WeakReference<>(view);
            this.hostReference = new WeakReference<>(host);
        }

        @Override
        public boolean isFor(Object owner) {
            return viewReference.get() == owner;
        }

        @Override
        public boolean isAlive() {
            return viewReference.get() != null;
        }

        @Override
        public void teardown() {
            View view = viewReference.get();
            PluginOverlayHost host = hostReference.get();
            if (view != null && host != null) {
                host.removeViewIfOwned(view);
            }
        }
    }

    public static final class DecorChildrenSnapshot {
        private final WeakReference<ViewGroup> decorReference;
        private final IdentityHashMap<View, Boolean> children;

        private DecorChildrenSnapshot(ViewGroup decor) {
            decorReference = new WeakReference<>(decor);
            children = new IdentityHashMap<>();
            for (int i = 0; i < decor.getChildCount(); i++) {
                View child = decor.getChildAt(i);
                if (child != null) {
                    children.put(child, Boolean.TRUE);
                }
            }
        }
    }

    public static boolean isMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    public static DecorChildrenSnapshot captureDecorChildren() {
        if (!isMainThread()) {
            return null;
        }
        ViewGroup decor = getLaunchDecorGroup();
        return decor != null ? new DecorChildrenSnapshot(decor) : null;
    }

    public static DecorChildrenSnapshot captureDecorChildrenBlocking(
            long timeoutMs) {
        if (isMainThread()) {
            return captureDecorChildren();
        }
        AtomicReference<DecorChildrenSnapshot> result =
                new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        if (!MAIN_HANDLER.post(() -> {
            try {
                result.set(captureDecorChildren());
            } finally {
                ready.countDown();
            }
        })) {
            return null;
        }
        try {
            if (!ready.await(Math.max(1L, timeoutMs),
                    TimeUnit.MILLISECONDS)) {
                PluginDebugLog.log(
                        "PLUGIN_UI decor baseline timed out");
                return null;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
        return result.get();
    }

    public static void adoptNewDecorChildrenDeferred(
            PluginsController.PluginRuntimeToken runtimeToken,
            DecorChildrenSnapshot snapshot) {
        if (runtimeToken == null || snapshot == null) {
            return;
        }
        runOnMain(() -> {
            adoptNewDecorChildren(runtimeToken, snapshot);
            try {
                Choreographer.getInstance().postFrameCallback(
                        frameTimeNanos -> MAIN_HANDLER.post(() ->
                                adoptNewDecorChildren(
                                        runtimeToken, snapshot)));
            } catch (Throwable ignored) {
                MAIN_HANDLER.post(() -> adoptNewDecorChildren(
                        runtimeToken, snapshot));
            }
        });
    }

    public static void adoptNewDecorChildren(
            PluginsController.PluginRuntimeToken runtimeToken,
            DecorChildrenSnapshot snapshot) {
        if (!isMainThread() || runtimeToken == null || snapshot == null) {
            return;
        }
        ViewGroup decor = snapshot.decorReference.get();
        if (decor == null || decor != getLaunchDecorGroup()) {
            return;
        }

        ArrayList<View> additions = new ArrayList<>();
        for (int i = 0; i < decor.getChildCount(); i++) {
            View child = decor.getChildAt(i);
            if (child != null
                    && !(child instanceof PluginOverlayHost)
                    && !snapshot.children.containsKey(child)) {
                additions.add(child);
            }
        }
        if (additions.isEmpty()) {
            return;
        }

        boolean runtimeCurrent = isRuntimeCurrent(runtimeToken);
        PluginOverlayHost host = runtimeCurrent
                ? getOrCreateOverlayHost(decor, runtimeToken.getPluginId())
                : null;
        int adopted = 0;
        for (View child : additions) {
            if (child.getParent() != decor) {
                continue;
            }
            ViewGroup.LayoutParams original = child.getLayoutParams();
            decor.removeView(child);
            if (host == null || !isRuntimeCurrent(runtimeToken)) {
                continue;
            }
            FrameLayout.LayoutParams overlayParams =
                    toOverlayLayoutParams(original);
            host.addView(child, overlayParams);
            if (child.getParent() == host) {
                put(runtimeToken, child,
                        new OverlayViewEntry(child, host));
                adopted++;
            }
        }
        PluginDebugLog.log("PLUGIN_UI decor overlay isolation runtime="
                + runtimeToken + " discovered=" + additions.size()
                + " adopted=" + adopted
                + " current=" + isRuntimeCurrent(runtimeToken));
    }

    public static boolean isRuntimeCurrent(
            PluginsController.PluginRuntimeToken runtimeToken) {
        return runtimeToken != null
                && PluginsController.getInstance().isPluginRuntimeCurrent(runtimeToken);
    }

    public static boolean canCreateDialog(
            PluginsController.PluginRuntimeToken runtimeToken, Context context) {
        if (!isMainThread() || !isRuntimeCurrent(runtimeToken)) {
            return false;
        }
        Activity activity = AndroidUtilities.findActivity(context);
        return isActivityUsable(activity);
    }

    public static boolean canAttachPluginView(
            PluginsController.PluginRuntimeToken runtimeToken, View view) {
        return isMainThread()
                && isRuntimeCurrent(runtimeToken)
                && view != null
                && view.getParent() == null;
    }

    public static boolean isFragmentUsable(
            PluginsController.PluginRuntimeToken runtimeToken, BaseFragment fragment) {
        if (!isMainThread() || !isRuntimeCurrent(runtimeToken) || fragment == null
                || fragment.isFinished || fragment.getParentLayout() == null) {
            return false;
        }
        Activity activity = fragment.getParentActivity();
        View fragmentView = fragment.getFragmentView();
        return isActivityUsable(activity)
                && fragmentView != null
                && fragment.getBulletinLayoutContainer() != null
                && fragmentView.isAttachedToWindow();
    }

    public static boolean isFragmentUiActive(BaseFragment fragment) {
        if (!isMainThread() || fragment == null || fragment.isFinished
                || fragment.isPaused() || fragment.getParentLayout() == null) {
            return false;
        }
        Activity activity = fragment.getParentActivity();
        View fragmentView = fragment.getFragmentView();
        return isActivityUsable(activity)
                && fragmentView != null
                && fragment.getBulletinLayoutContainer() != null
                && fragmentView.isAttachedToWindow();
    }

    public static boolean registerDialog(
            PluginsController.PluginRuntimeToken runtimeToken,
            Dialog dialog,
            boolean cancelOnCleanup) {
        if (!isMainThread() || dialog == null
                || !canCreateDialog(runtimeToken, dialog.getContext())) {
            return false;
        }
        put(runtimeToken, dialog, new DialogEntry(dialog, cancelOnCleanup, true));
        return true;
    }

    public static boolean registerRuntimeOwnedUi(
            PluginsController.PluginRuntimeToken runtimeToken,
            RuntimeOwnedUi owner) {
        if (!isMainThread() || owner == null || !isRuntimeCurrent(runtimeToken)) {
            return false;
        }
        put(runtimeToken, owner, new RuntimeOwnedEntry(owner, runtimeToken));
        if (!isRuntimeCurrent(runtimeToken)) {
            remove(runtimeToken, owner);
            scheduleAfterTraversal(
                    () -> owner.clearPluginUiReferences(runtimeToken));
            return false;
        }
        return true;
    }

    public static void trackRuntimeOwnedUi(
            PluginsController.PluginRuntimeToken runtimeToken,
            RuntimeOwnedUi owner) {
        if (runtimeToken == null || owner == null) {
            return;
        }
        runOnMain(() -> {
            if (!registerRuntimeOwnedUi(runtimeToken, owner)) {
                try {
                    owner.clearPluginUiReferences(runtimeToken);
                } catch (Throwable t) {
                    FileLog.e("Unable to reject stale plugin UI owner", t);
                }
            }
        });
    }

    public static void unregisterRuntimeOwnedUi(
            PluginsController.PluginRuntimeToken runtimeToken,
            RuntimeOwnedUi owner) {
        if (runtimeToken == null || owner == null) {
            return;
        }
        if (!isMainThread()) {
            MAIN_HANDLER.post(
                    () -> unregisterRuntimeOwnedUi(runtimeToken, owner));
            return;
        }
        remove(runtimeToken, owner);
    }

    public static void unregisterDialog(
            PluginsController.PluginRuntimeToken runtimeToken, Dialog dialog) {
        if (!isMainThread() || runtimeToken == null || dialog == null) {
            return;
        }
        remove(runtimeToken, dialog);
    }

    public static boolean showDialog(
            PluginsController.PluginRuntimeToken runtimeToken,
            Dialog dialog,
            boolean cancelOnCleanup) {
        if (!registerDialog(runtimeToken, dialog, cancelOnCleanup)) {
            return false;
        }
        scheduleAfterTraversal(() -> {
            if (!isRuntimeCurrent(runtimeToken)
                    || !isRegistered(runtimeToken, dialog)
                    || !canCreateDialog(runtimeToken,
                            dialog.getContext())) {
                remove(runtimeToken, dialog);
                teardownDialog(
                        dialog, cancelOnCleanup, true);
                return;
            }
            try {
                if (!dialog.isShowing()) {
                    dialog.show();
                }
            } catch (VirtualMachineError
                    | ThreadDeath
                    | LinkageError fatal) {
                throw fatal;
            } catch (Throwable t) {
                remove(runtimeToken, dialog);
                teardownDialog(
                        dialog, cancelOnCleanup, true);
                FileLog.e(
                        "Unable to show plugin dialog for "
                                + runtimeToken,
                        t);
                return;
            }
            if (!isRuntimeCurrent(runtimeToken)
                    || !isRegistered(runtimeToken, dialog)) {
                remove(runtimeToken, dialog);
                teardownDialog(
                        dialog, cancelOnCleanup, true);
            }
        });
        return true;
    }

    public static boolean showBulletin(
            PluginsController.PluginRuntimeToken runtimeToken, Bulletin bulletin) {
        if (!isMainThread() || bulletin == null || !isRuntimeCurrent(runtimeToken)) {
            return false;
        }
        bulletin.setOnHideListener(() -> remove(runtimeToken, bulletin));
        put(runtimeToken, bulletin, new BulletinEntry(bulletin));
        try {
            bulletin.show();
        } catch (Throwable t) {
            remove(runtimeToken, bulletin);
            FileLog.e("Unable to show plugin bulletin for " + runtimeToken, t);
            return false;
        }
        if (!isRuntimeCurrent(runtimeToken)) {
            remove(runtimeToken, bulletin);
            scheduleAfterTraversal(() -> new BulletinEntry(bulletin).teardown());
            return false;
        }
        return true;
    }

    public static void dismissDialog(Dialog dialog) {
        if (dialog == null) {
            return;
        }
        runOnMain(() -> {
            removeFromAllBuckets(dialog);
            scheduleAfterTraversal(
                    () -> teardownDialog(dialog, false, false));
        });
    }

    public static void cancelDialog(Dialog dialog) {
        if (dialog == null) {
            return;
        }
        runOnMain(() -> {
            removeFromAllBuckets(dialog);
            scheduleAfterTraversal(
                    () -> teardownDialog(dialog, true, false));
        });
    }

    public static void cleanup(PluginsController.PluginRuntimeToken runtimeToken) {
        if (runtimeToken == null) {
            return;
        }
        PluginDebugLog.log("PLUGIN_UI cleanup request runtime="
                + runtimeToken + " main=" + isMainThread());
        runOnMain(() -> cleanupOnMain(runtimeToken));
    }

    public static void cleanupPlugin(String pluginId) {
        if (pluginId == null || pluginId.isEmpty()) {
            return;
        }
        PluginDebugLog.log("PLUGIN_UI broad cleanup request plugin="
                + pluginId + " main=" + isMainThread());
        runOnMain(() -> cleanupPluginOnMain(pluginId));
    }

    public static void runAfterTraversal(Runnable runnable) {
        if (runnable == null) {
            return;
        }
        scheduleAfterTraversal(runnable);
    }

    private static void cleanupOnMain(
            PluginsController.PluginRuntimeToken runtimeToken) {
        UiBucket bucket = UI_BY_RUNTIME.remove(runtimeToken);
        if (bucket == null || bucket.entries.isEmpty()) {
            PluginDebugLog.log("PLUGIN_UI cleanup empty runtime="
                    + runtimeToken);
            return;
        }
        ArrayList<UiEntry> entries = new ArrayList<>(bucket.entries);
        bucket.entries.clear();
        PluginDebugLog.log("PLUGIN_UI cleanup detached runtime="
                + runtimeToken + " entries=" + entries.size());
        scheduleTeardown(entries);
    }

    private static void cleanupPluginOnMain(String pluginId) {
        ArrayList<UiEntry> entries = new ArrayList<>();
        for (Map.Entry<PluginsController.PluginRuntimeToken, UiBucket> item
                : UI_BY_RUNTIME.entrySet()) {
            PluginsController.PluginRuntimeToken runtimeToken = item.getKey();
            UiBucket bucket = item.getValue();
            if (!pluginId.equals(runtimeToken.getPluginId())
                    
                    || isRuntimeCurrent(runtimeToken)
                    || !UI_BY_RUNTIME.remove(runtimeToken, bucket)) {
                continue;
            }
            entries.addAll(bucket.entries);
            bucket.entries.clear();
        }
        PluginDebugLog.log("PLUGIN_UI broad cleanup detached plugin="
                + pluginId + " entries=" + entries.size());
        scheduleTeardown(entries);
    }

    private static void scheduleTeardown(ArrayList<UiEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        PluginDebugLog.log("PLUGIN_UI teardown scheduled entries="
                + entries.size());
        scheduleAfterTraversal(() -> {
            PluginDebugLog.log("PLUGIN_UI teardown begin entries="
                    + entries.size());
            for (UiEntry entry : entries) {
                entry.teardown();
            }
            PluginDebugLog.log("PLUGIN_UI teardown end entries="
                    + entries.size());
        });
    }

    private static boolean isActivityUsable(Activity activity) {
        if (activity == null || activity.isFinishing()
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                && activity.isDestroyed())) {
            return false;
        }
        Window window = activity.getWindow();
        View decorView = window != null ? window.getDecorView() : null;
        return decorView != null && decorView.isAttachedToWindow();
    }

    private static ViewGroup getLaunchDecorGroup() {
        LaunchActivity activity = LaunchActivity.instance;
        if (!isActivityUsable(activity)) {
            return null;
        }
        Window window = activity.getWindow();
        View decor = window != null ? window.getDecorView() : null;
        return decor instanceof ViewGroup ? (ViewGroup) decor : null;
    }

    private static PluginOverlayHost getOrCreateOverlayHost(
            ViewGroup decor, String pluginId) {
        HashMap<String, WeakReference<PluginOverlayHost>> byPlugin =
                OVERLAY_HOSTS.computeIfAbsent(
                        decor, ignored -> new HashMap<>());
        WeakReference<PluginOverlayHost> reference = byPlugin.get(pluginId);
        PluginOverlayHost host =
                reference != null ? reference.get() : null;
        if (host != null && host.getParent() == decor) {
            return host;
        }
        host = new PluginOverlayHost(decor.getContext(), pluginId);
        decor.addView(host, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        byPlugin.put(pluginId, new WeakReference<>(host));
        return host;
    }

    private static FrameLayout.LayoutParams toOverlayLayoutParams(
            ViewGroup.LayoutParams original) {
        if (original instanceof FrameLayout.LayoutParams) {
            return new FrameLayout.LayoutParams(
                    (FrameLayout.LayoutParams) original);
        }
        if (original instanceof ViewGroup.MarginLayoutParams) {
            return new FrameLayout.LayoutParams(
                    (ViewGroup.MarginLayoutParams) original);
        }
        if (original != null) {
            return new FrameLayout.LayoutParams(original);
        }
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static void put(
            PluginsController.PluginRuntimeToken runtimeToken,
            Object key,
            UiEntry entry) {
        UiBucket bucket = UI_BY_RUNTIME.computeIfAbsent(
                runtimeToken, ignored -> new UiBucket());
        for (int i = bucket.entries.size() - 1; i >= 0; i--) {
            UiEntry existing = bucket.entries.get(i);
            if (!existing.isAlive()) {
                bucket.entries.remove(i);
            } else if (existing.isFor(key)) {
                bucket.entries.set(i, entry);
                return;
            }
        }
        bucket.entries.add(entry);
    }

    private static void remove(
            PluginsController.PluginRuntimeToken runtimeToken, Object key) {
        UiBucket bucket = UI_BY_RUNTIME.get(runtimeToken);
        if (bucket == null) {
            return;
        }
        for (int i = bucket.entries.size() - 1; i >= 0; i--) {
            UiEntry entry = bucket.entries.get(i);
            if (!entry.isAlive() || entry.isFor(key)) {
                bucket.entries.remove(i);
            }
        }
        if (bucket.entries.isEmpty()) {
            UI_BY_RUNTIME.remove(runtimeToken, bucket);
        }
    }

    private static boolean isRegistered(
            PluginsController.PluginRuntimeToken runtimeToken, Object key) {
        UiBucket bucket = UI_BY_RUNTIME.get(runtimeToken);
        if (bucket == null) {
            return false;
        }
        for (int i = bucket.entries.size() - 1; i >= 0; i--) {
            UiEntry entry = bucket.entries.get(i);
            if (!entry.isAlive()) {
                bucket.entries.remove(i);
            } else if (entry.isFor(key)) {
                return true;
            }
        }
        if (bucket.entries.isEmpty()) {
            UI_BY_RUNTIME.remove(runtimeToken, bucket);
        }
        return false;
    }

    private static void removeFromAllBuckets(Object key) {
        for (Map.Entry<PluginsController.PluginRuntimeToken, UiBucket> item
                : UI_BY_RUNTIME.entrySet()) {
            UiBucket bucket = item.getValue();
            for (int i = bucket.entries.size() - 1; i >= 0; i--) {
                UiEntry entry = bucket.entries.get(i);
                if (!entry.isAlive() || entry.isFor(key)) {
                    bucket.entries.remove(i);
                }
            }
            if (bucket.entries.isEmpty()) {
                UI_BY_RUNTIME.remove(item.getKey(), bucket);
            }
        }
    }

    private static void teardownDialog(
            Dialog dialog,
            boolean cancelOnCleanup,
            boolean suppressCallbacks) {
        try {
            if (suppressCallbacks) {
                
                dialog.setOnCancelListener(null);
                dialog.setOnDismissListener(null);
                dialog.setOnShowListener(null);
            }
            if (!dialog.isShowing()) {
                return;
            }
            if (cancelOnCleanup) {
                dialog.cancel();
            } else {
                dialog.dismiss();
            }
        } catch (Throwable firstFailure) {
            try {
                dialog.dismiss();
            } catch (Throwable secondFailure) {
                FileLog.e("Unable to close plugin dialog", secondFailure);
            }
        }
    }

    private static void runOnMain(Runnable runnable) {
        if (isMainThread()) {
            runnable.run();
        } else {
            MAIN_HANDLER.post(runnable);
        }
    }

    private static void scheduleAfterTraversal(Runnable runnable) {
        if (!isMainThread()) {
            MAIN_HANDLER.post(() -> scheduleAfterTraversal(runnable));
            return;
        }
        try {
            Choreographer.getInstance().postFrameCallback(
                    frameTimeNanos -> MAIN_HANDLER.post(runnable));
        } catch (Throwable t) {
            MAIN_HANDLER.post(runnable);
        }
    }
}
