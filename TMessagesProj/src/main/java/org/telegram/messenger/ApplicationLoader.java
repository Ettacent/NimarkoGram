/* Modifications Copyright (C) 2026 Ettacent */

/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import app.nimarkogram.messenger.utils.AppMotionBlur;
import app.nimarkogram.messenger.utils.UiAnimationClock;
import app.nimarkogram.messenger.utils.UiAnimationLifecycle;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;

import org.json.JSONObject;
import org.telegram.messenger.utils.Choreographer60FpsContent;
import org.telegram.messenger.voip.VideoCapturerDevice;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.ForegroundDetector;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.IUpdateLayout;
import org.telegram.ui.LauncherIconController;

import java.io.File;
import java.util.Locale;

import java.util.concurrent.atomic.AtomicBoolean;
public class ApplicationLoader extends Application {

    public static ApplicationLoader applicationLoaderInstance;

    @SuppressLint("StaticFieldLeak")
    public static volatile Context applicationContext;
    public static volatile NetworkInfo currentNetworkInfo;
    public static volatile Handler applicationHandler;

    private static ConnectivityManager connectivityManager;
    private static volatile boolean applicationInited = false;
    private static volatile  ConnectivityManager.NetworkCallback networkCallback;
    private static final AtomicBoolean networkCallbackRegistering = new AtomicBoolean();
    private static volatile long nextNetworkCallbackAttempt;
    private static long lastNetworkCheckTypeTime;
    private static volatile int lastKnownNetworkType = -1;

    public static long startTime;

    public static volatile boolean isScreenOn = false;
    public static volatile boolean mainInterfacePaused = true;
    public static volatile boolean mainInterfaceStopped = true;
    public static volatile boolean externalInterfacePaused = true;
    public static volatile boolean mainInterfacePausedStageQueue = true;
    public static boolean canDrawOverlays;
    public static volatile long mainInterfacePausedStageQueueTime;

    private static PushListenerController.IPushListenerServiceProvider pushProvider;
    private static IMapsProvider mapsProvider;
    private static ILocationServiceProvider locationServiceProvider;

    private boolean documentRendererProcess;
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        documentRendererProcess = isRendererProcess(base);
        if (documentRendererProcess) return;
        warmStartupPreferences(base);
        try {
            ru.noties.jlatexmath.JLatexMathAndroid.init(base);
        } catch (Throwable ignored) {
        }

        try {
            app.nimarkogram.messenger.CrashHandler.install(base);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isRendererProcess(Context base) {
        if (app.nimarkogram.messenger.documents.DocumentProcess.isIsolated(base)) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            String name = Application.getProcessName();
            if (name != null && !name.isEmpty()) return name.contains(":widgets");
        }
        return org.telegram.utils.twidget.WidgetEngineService.isEngineProcess();
    }

    private static void warmStartupPreferences(Context storage) {
        try {
            new Thread(() -> {
                try {
                    storage.getSharedPreferences("userconfing", Context.MODE_PRIVATE);
                } catch (RuntimeException ignored) {
                }
            }, "ng-startup-prefs").start();
        } catch (RuntimeException ignored) {
        }
    }

    public static ILocationServiceProvider getLocationServiceProvider() {
        if (locationServiceProvider == null) {
            locationServiceProvider = applicationLoaderInstance.onCreateLocationServiceProvider();
            locationServiceProvider.init(applicationContext);
        }
        return locationServiceProvider;
    }

    protected ILocationServiceProvider onCreateLocationServiceProvider() {
        return new GoogleLocationProvider();
    }

    public static IMapsProvider getMapsProvider() {
        if (mapsProvider == null) {
            mapsProvider = applicationLoaderInstance.onCreateMapsProvider();
        }
        return mapsProvider;
    }

    protected IMapsProvider onCreateMapsProvider() {
        return new GoogleMapsProvider();
    }

    public static PushListenerController.IPushListenerServiceProvider getPushProvider() {
        if (pushProvider == null) {
            pushProvider = applicationLoaderInstance.onCreatePushProvider();
        }
        return pushProvider;
    }

    protected PushListenerController.IPushListenerServiceProvider onCreatePushProvider() {
        return PushListenerController.GooglePushListenerServiceProvider.INSTANCE;
    }

    public static String getApplicationId() {
        return applicationLoaderInstance.onGetApplicationId();
    }

    protected String onGetApplicationId() {
        return null;
    }

    public static boolean isHuaweiStoreBuild() {
        return applicationLoaderInstance.isHuaweiBuild();
    }

    public static boolean isStandaloneBuild() {
        return applicationLoaderInstance.isStandalone();
    }

    public static boolean isBetaBuild() {
        return applicationLoaderInstance.isBeta();
    }

    public static boolean isAndroidTestEnvironment() {
        return applicationLoaderInstance.isAndroidTestEnv();
    }

    protected boolean isHuaweiBuild() {
        return false;
    }

    protected boolean isStandalone() {
        return false;
    }

    protected boolean isBeta() {
        return false;
    }

    protected boolean isAndroidTestEnv() {
        return false;
    }

    public static File getFilesDirFixed() {
        for (int a = 0; a < 10; a++) {
            File path = ApplicationLoader.applicationContext.getFilesDir();
            if (path != null) {
                return path;
            }
        }
        try {
            ApplicationInfo info = applicationContext.getApplicationInfo();
            File path = new File(info.dataDir, "files");
            path.mkdirs();
            return path;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return new File("/data/data/org.telegram.messenger/files");
    }

    public static File getFilesDirFixed(String child) {
        try {
            File path = getFilesDirFixed();
            File dir = new File(path, child);
            dir.mkdirs();

            return dir;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    private static void prewarmChatPreferences() {
        Context context = applicationContext;
        if (context == null) return;
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            try {
                context.getSharedPreferences("bot_drafts" + account, Context.MODE_PRIVATE).contains("");
                context.getSharedPreferences("hashtag_search_history" + account, Context.MODE_PRIVATE).contains("");
                context.getSharedPreferences("saved_reactions_" + account, Context.MODE_PRIVATE).contains("");
            } catch (Throwable ignored) {}
        }
    }

    public static void postInitApplication() {
        if (applicationInited || applicationContext == null) {
            return;
        }
        applicationInited = true;
        NativeLoader.initNativeLibs(ApplicationLoader.applicationContext);

        try {
            LocaleController.getInstance();
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            connectivityManager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            BroadcastReceiver networkStateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        currentNetworkInfo = connectivityManager.getActiveNetworkInfo();
                    } catch (Throwable ignore) {

                    }

                    boolean isSlow = isConnectionSlow() || app.nimarkogram.messenger.AppConfig.slowNetworkMode;
                    for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                        ConnectionsManager.getInstance(a).checkConnection();
                        FileLoader.getInstance(a).onNetworkChanged(isSlow);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
            ApplicationLoader.applicationContext.registerReceiver(networkStateReceiver, filter);
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            final IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            final BroadcastReceiver mReceiver = new ScreenReceiver();
            applicationContext.registerReceiver(mReceiver, filter);
        } catch (Exception e) {
            e.printStackTrace();
        }

        LiteMode.registerBatteryReceiver();
        try {
            PowerManager pm = (PowerManager) ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
            isScreenOn = pm.isScreenOn();
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("screen state = " + isScreenOn);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        SharedConfig.loadConfig();
        SharedPrefsHelper.init(applicationContext);
        try {
            Utilities.globalQueue.postRunnable(ApplicationLoader::prewarmChatPreferences, 1500);
        } catch (Throwable ignored) {}

        try {
            if (app.nimarkogram.messenger.camera.CameraXUtils.isCurrentCameraCameraX()) {
                app.nimarkogram.messenger.camera.CameraXUtils.warmUpAsync(applicationContext);

                app.nimarkogram.messenger.camera.CameraXUtils.loadCameraXSizes();
            }
        } catch (Throwable ignored) {}
        try {
            Utilities.globalQueue.postRunnable(org.telegram.ui.CastSync::preload);
        } catch (Throwable ignored) {}

        app.nimarkogram.messenger.distribution.ClientRelays.start();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig.getInstance(a).loadConfig();
            MessagesController.getInstance(a);
            if (a == 0) {
                SharedConfig.pushStringStatus = "__FIREBASE_GENERATING_SINCE_" + ConnectionsManager.getInstance(a).getCurrentTime() + "__";
            } else {
                ConnectionsManager.getInstance(a);
            }
            TLRPC.User user = UserConfig.getInstance(a).getCurrentUser();
            if (user != null) {
                MessagesController.getInstance(a).putUser(user, true);
                SendMessagesHelper.getInstance(a).checkUnsentMessages();
            }
        }

        try {
            app.nimarkogram.messenger.distribution.ClientRelays.suspendForVpn();
        } catch (Throwable ignored) {}

        ApplicationLoader app = (ApplicationLoader) ApplicationLoader.applicationContext;
        app.initPushServices();
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("app initied");
        }

        MediaController.getInstance();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            ContactsController.getInstance(a).checkAppAccount();
            DownloadController.getInstance(a);
        }
        BillingController.getInstance().startConnection();
        AndroidUtilities.runOnUIThread(() -> Utilities.globalQueue.postRunnable(
                () -> org.telegram.utils.twidget.WidgetSandboxSession.prewarmEngine(applicationContext)), 3000);
    }

    public ApplicationLoader() {
        super();
    }

    private volatile app.nimarkogram.messenger.icons.IconResources nmAppResources;
    private static final ThreadLocal<Boolean> nmWrappingResources = new ThreadLocal<>();
    private final Object nmAppResourcesLock = new Object();
    private volatile boolean nmAppResourcesReady;

    @Override
    public android.content.res.Resources getResources() {
        android.content.res.Resources base = super.getResources();
        try {
            if (base == null || !nmAppResourcesReady || applicationContext == null
                    || app.nimarkogram.messenger.AppConfig.iconReplacement == app.nimarkogram.messenger.AppConfig.ICON_REPLACE_NONE) {
                return base;
            }
            if (Boolean.TRUE.equals(nmWrappingResources.get())) {
                return base;
            }

            app.nimarkogram.messenger.icons.IconResources resources = nmAppResources;
            if (resources == null || resources.getAssets() != base.getAssets()) {
                final app.nimarkogram.messenger.icons.IconResources candidate;
                nmWrappingResources.set(Boolean.TRUE);
                try {
                    candidate = new app.nimarkogram.messenger.icons.IconResources(base, false);
                } finally {
                    nmWrappingResources.remove();
                }
                boolean installed = false;
                synchronized (nmAppResourcesLock) {
                    resources = nmAppResources;
                    if (resources == null || resources.getAssets() != base.getAssets()) {
                        resources = candidate;
                        resources.publishAsInstalled();
                        nmAppResources = resources;
                        installed = true;
                    }
                }
                if (installed) {
                    resources.prepareAsync();
                }
            }
            return resources != null ? resources : base;
        } catch (Throwable t) {
            return base;
        }
    }

    public static void reloadAppIconResources() {
        try {
            ApplicationLoader inst = applicationLoaderInstance;
            if (inst != null && inst.nmAppResources != null) {
                inst.nmAppResources.reloadReplacements(null);
            }
        } catch (Throwable ignore) {}
    }

    android.content.res.Resources nmRawResources() {
        return super.getResources();
    }

    public static android.content.res.Resources rawResources() {
        ApplicationLoader inst = applicationLoaderInstance;
        if (inst != null) {
            try { return inst.nmRawResources(); } catch (Throwable ignore) {}
        }
        return applicationContext != null ? applicationContext.getResources() : null;
    }

    public static final java.util.concurrent.CountDownLatch ngPineReady = PluginRuntimeBootstrap.ngPineReady;
    public static void ensurePineInited() { PluginRuntimeBootstrap.ensurePineInited(); }
    public static boolean isPineAvailable() { return PluginRuntimeBootstrap.isPineAvailable(); }
    public static boolean wasPineInitializationAttempted() { return PluginRuntimeBootstrap.wasPineInitializationAttempted(); }
    public static String getPineUnavailableReason() { return PluginRuntimeBootstrap.getPineUnavailableReason(); }
    public static boolean awaitPineInitializationForHooks() { return PluginRuntimeBootstrap.awaitPineInitializationForHooks(); }
    public static void ngAwaitPine() { ensurePineInited(); }

    @androidx.annotation.Keep
    private static int pineRuntimeStaticProbe(int value) {
        return value + 1;
    }

    @androidx.annotation.Keep
    static final class PineRuntimeProbe {
        int constructorValue;

        @androidx.annotation.Keep
        PineRuntimeProbe() {
        }

        @androidx.annotation.Keep
        PineRuntimeProbe(int value) {
            constructorValue = value;
        }

        @androidx.annotation.Keep
        long mixedProbe(
                Object marker1, int a, long b,
                Object marker2, int c, long d,
                Object marker3, int e, long f,
                Object marker4, int g, long h,
                double tail) {
            if (marker1 != marker2 || marker1 != marker3
                    || marker1 != marker4) {
                return Long.MIN_VALUE;
            }
            return a + b + c + d + e + f + g + h + (long) tail;
        }
    }


    @Override
    public void onCreate() {
        if (documentRendererProcess) {
            super.onCreate();
            return;
        }
        applicationLoaderInstance = this;
        AppMotionBlur.install(this);
        try {
            applicationContext = getApplicationContext();
        } catch (Throwable ignore) {

        }

        app.nimarkogram.messenger.diagnostics.CrashDiagnostics.install(this);
        app.nimarkogram.messenger.diagnostics.CrashDiagnostics.recordEvent("startup", "on_create", "begin");
        try {
            app.nimarkogram.messenger.CrashHandler.install(this);
        } catch (Throwable ignored) {}

        try {
            PluginRuntimeBootstrap.installPineRuntimeGuardIfNeeded();
        } catch (Throwable ignored) {
        }

        try {
            org.telegram.messenger.SharedConfig.loadConfig();
            int ignoredSel = app.nimarkogram.messenger.AppConfig.iconReplacement;
            nmAppResourcesReady = true;
        } catch (Throwable ignored) {}
        try {
            new Thread(() -> {
                try { org.telegram.messenger.FileLog.getInstance().init(); } catch (Throwable ignored) {}
            }, "ng-filelog-init").start();
        } catch (Throwable ignored) {}

        super.onCreate();

        app.nimarkogram.messenger.utils.ui.SystemTextPaint.configure(
                nmRawResources().getConfiguration(), app.nimarkogram.messenger.AppConfig.systemFonts);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                app.nimarkogram.messenger.distribution.ClientPlugins.exitDiagnostics();
            }
        } catch (Throwable t) {
            FileLog.e("nimarko: unable to schedule previous-exit diagnostics", t);
        }

        try {
            app.nimarkogram.messenger.badges.BadgesController.getInstance()
                    .init(applicationContext);
        } catch (Throwable t) {
            FileLog.e("nimarko-badges: init failed", t);
        }

        try {
            app.nimarkogram.messenger.textanim.TextAnim.initIfEnabled();
        } catch (Throwable t) {
            FileLog.e("nimarko-textanim: init failed", t);
        }

        final String helloWorld = AndroidUtilities.getHelloWorld();

        if (BuildVars.LOGS_ENABLED) {
            FileLog.d(helloWorld);
            FileLog.d("app start time = " + (startTime = SystemClock.elapsedRealtime()));
            try {
                final PackageInfo info = ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
                final String abi;
                switch (info.versionCode % 10) {
                    case 1:
                    case 2:
                        abi = "store bundled " + Build.CPU_ABI + " " + Build.CPU_ABI2;
                        break;
                    default:
                    case 9:
                        if (ApplicationLoader.isStandaloneBuild()) {
                            abi = "direct " + Build.CPU_ABI + " " + Build.CPU_ABI2;
                        } else {
                            abi = "universal " + Build.CPU_ABI + " " + Build.CPU_ABI2;
                        }
                        break;
                }
                FileLog.d("buildVersion = " + String.format(Locale.US, "v%s (%d[%d]) %s", info.versionName, info.versionCode / 10, info.versionCode % 10, abi));
            } catch (Exception e) {
                FileLog.e(e);
            }
            FileLog.d("device = manufacturer=" + Build.MANUFACTURER + ", device=" + Build.DEVICE + ", model=" + Build.MODEL + ", product=" + Build.PRODUCT);
        }
        if (applicationContext == null) {
            applicationContext = getApplicationContext();
        }

        app.nimarkogram.messenger.diagnostics.CrashDiagnostics.recordEvent("startup", "native_library", "begin");
        NativeLoader.initNativeLibs(ApplicationLoader.applicationContext);

        app.nimarkogram.messenger.diagnostics.CrashDiagnostics.recordEvent("startup", "native_library", "complete");
        try {
            ConnectionsManager.native_setJava(false);
        } catch (UnsatisfiedLinkError error) {
            Error loadError = NativeLoader.getLoadError();
            throw new RuntimeException("can't load native libraries " +  Build.CPU_ABI + " lookup folder " + NativeLoader.getAbiFolder(), loadError != null ? loadError : error);
        }
        new ForegroundDetector(this) {
            @Override
            public void onActivityStarted(Activity activity) {
                boolean wasInBackground = isBackground();
                super.onActivityStarted(activity);
                if (wasInBackground) {
                    ensureCurrentNetworkGet(true);
                }
            }
        };
        UiAnimationClock.install(ForegroundDetector.getInstance());
        UiAnimationLifecycle.install(this);
        if (BuildConfig.DEBUG_VERSION) {
            new ANRDetector(FileLog::dumpANR);
        }

        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("load libs time = " + (SystemClock.elapsedRealtime() - startTime));
        }

        applicationHandler = new Handler(applicationContext.getMainLooper());

        app.nimarkogram.messenger.CrashContext.install(applicationContext);
        AndroidUtilities.runOnUIThread(ApplicationLoader::startPushService);

        LauncherIconController.tryFixLauncherIconIfNeeded();
        ProxyRotationController.init();

        try {
            app.nimarkogram.messenger.FeatureHooks.setDiscussInsteadOfMute(
                    app.nimarkogram.messenger.AppConfig.discussInsteadOfMute);
        } catch (Throwable ignored) {}

        try {
            if (app.nimarkogram.messenger.distribution.ClientPlugins.supported() && app.nimarkogram.messenger.AppConfig.pluginsEngine) {
                app.nimarkogram.messenger.distribution.ClientPlugins.init();
            } else {
                FileLog.d("NimarkoGram: pluginsEngine disabled, skipping plugin init");
            }
        } catch (Throwable t) {
            FileLog.e("PluginsController.init failed at app start", t);
        }

        app.nimarkogram.messenger.diagnostics.CrashDiagnostics.startupCompleted();
    }

    private final Runnable debugEverySecondChecks = () -> AndroidUtilities.runOnUIThread(() -> {
        NotificationCenter.sanitize();
    });

    public static void startPushService() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Utilities.globalQueue.postRunnable(ApplicationLoader::startPushService);
            return;
        }
        SharedPreferences preferences = applicationContext.getSharedPreferences("Notifications", Context.MODE_PRIVATE);
        boolean enabled;
        if (preferences.contains("pushService")) {
            enabled = preferences.getBoolean("pushService", false);
        } else {
            int account = UserConfig.selectedAccount;
            SharedPreferences mainSettings = applicationContext.getSharedPreferences(
                    account == 0 ? "mainconfig" : "mainconfig" + account, Context.MODE_PRIVATE);
            enabled = mainSettings.getBoolean("keepAliveService", false);
        }

        boolean residentEnabled = app.nimarkogram.messenger.AppConfig.residentNotification
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && Build.VERSION.SDK_INT < 35;
        if (residentEnabled) {
            enabled = true;
        }
        final boolean shouldStart = enabled;
        final boolean shouldRunForeground = residentEnabled;
        AndroidUtilities.runOnUIThread(() -> {
            try {
                Intent svc = new Intent(applicationContext, NotificationsService.class);

                if (shouldStart) {
                    if (shouldRunForeground) {
                        applicationContext.startForegroundService(svc);
                    } else {
                        applicationContext.startService(svc);
                    }
                } else {
                    applicationContext.stopService(svc);
                }
            } catch (Throwable ignore) {

            }
        });
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (documentRendererProcess) return;
        app.nimarkogram.messenger.utils.ui.SystemTextPaint.configure(
                newConfig, app.nimarkogram.messenger.AppConfig.systemFonts);
        try {
            LocaleController.getInstance().onDeviceConfigurationChange(newConfig);
            AndroidUtilities.checkDisplaySize(applicationContext, newConfig);
            VideoCapturerDevice.checkScreenCapturerSize();
            AndroidUtilities.resetTabletFlag();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (documentRendererProcess) return;
        app.nimarkogram.messenger.diagnostics.CrashDiagnostics.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
                || (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
                && level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)) {
            ImageLoader.clearMemoryIfInitialized();
        }
    }

    private void initPushServices() {
        AndroidUtilities.runOnUIThread(() -> {
            if (getPushProvider().hasServices()) {
                getPushProvider().onRequestPushToken();
            } else {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("No valid " + getPushProvider().getLogTitle() + " APK found.");
                }
                SharedConfig.pushStringStatus = "__NO_GOOGLE_PLAY_SERVICES__";
                PushListenerController.sendRegistrationToServer(getPushProvider().getPushType(), null);
            }
        }, 1000);
    }

    private boolean checkPlayServices() {
        try {
            int resultCode = GooglePlayServicesUtil.isGooglePlayServicesAvailable(this);
            return resultCode == ConnectionResult.SUCCESS;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return true;
    }

    private static long lastNetworkCheck = -1;
    private static void ensureCurrentNetworkGet() {
        final long now = System.currentTimeMillis();
        ensureCurrentNetworkGet(now - lastNetworkCheck > 5000);
        lastNetworkCheck = now;
    }

    private static void ensureCurrentNetworkGet(boolean force) {
        if (force || currentNetworkInfo == null) {
            try {
                if (connectivityManager == null) {
                    connectivityManager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
                }
                currentNetworkInfo = connectivityManager.getActiveNetworkInfo();
            } catch (Throwable ignore) {

            }
        }
        ensureNetworkCallback(connectivityManager);
    }

    private static void ensureNetworkCallback(ConnectivityManager manager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || manager == null || networkCallback != null
                || SystemClock.elapsedRealtime() < nextNetworkCallbackAttempt
                || !networkCallbackRegistering.compareAndSet(false, true)) return;
        if (networkCallback != null) {
            networkCallbackRegistering.set(false);
            return;
        }
        try {
            new Thread(() -> {
                try {
                    ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
                            @Override
                            public void onAvailable(@NonNull Network network) {
                                lastKnownNetworkType = -1;
                            }

                            @Override
                        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities capabilities) {
                                lastKnownNetworkType = -1;
                            }
                        @Override
                        public void onLost(@NonNull Network network) {
                            lastKnownNetworkType = -1;
                        }
                        };
                    manager.registerDefaultNetworkCallback(callback);
                    networkCallback = callback;
                } catch (Exception error) {
                    nextNetworkCallbackAttempt = SystemClock.elapsedRealtime() + 5000;
                } finally {
                    networkCallbackRegistering.set(false);
                }
            }, "network-callback-register").start();
        } catch (Throwable error) {
            nextNetworkCallbackAttempt = SystemClock.elapsedRealtime() + 5000;
            networkCallbackRegistering.set(false);
        }
    }

    public static boolean isRoaming() {
        try {
            ensureCurrentNetworkGet(false);
            return currentNetworkInfo != null && currentNetworkInfo.isRoaming();
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public static boolean isConnectedOrConnectingToWiFi() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo != null && (currentNetworkInfo.getType() == ConnectivityManager.TYPE_WIFI || currentNetworkInfo.getType() == ConnectivityManager.TYPE_ETHERNET)) {
                NetworkInfo.State state = currentNetworkInfo.getState();
                if (state == NetworkInfo.State.CONNECTED || state == NetworkInfo.State.CONNECTING || state == NetworkInfo.State.SUSPENDED) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public static boolean isConnectedToWiFi() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo != null && (currentNetworkInfo.getType() == ConnectivityManager.TYPE_WIFI || currentNetworkInfo.getType() == ConnectivityManager.TYPE_ETHERNET) && currentNetworkInfo.getState() == NetworkInfo.State.CONNECTED) {
                return true;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public static boolean isConnectionSlow() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo != null && currentNetworkInfo.getType() == ConnectivityManager.TYPE_MOBILE) {
                switch (currentNetworkInfo.getSubtype()) {
                    case TelephonyManager.NETWORK_TYPE_1xRTT:
                    case TelephonyManager.NETWORK_TYPE_CDMA:
                    case TelephonyManager.NETWORK_TYPE_EDGE:
                    case TelephonyManager.NETWORK_TYPE_GPRS:
                    case TelephonyManager.NETWORK_TYPE_IDEN:
                        return true;
                }
            }
        } catch (Throwable ignore) {

        }
        return false;
    }

    public static int getAutodownloadNetworkType() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo == null) {
                return StatsController.TYPE_MOBILE;
            }
            if (currentNetworkInfo.getType() == ConnectivityManager.TYPE_WIFI || currentNetworkInfo.getType() == ConnectivityManager.TYPE_ETHERNET) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && (lastKnownNetworkType == StatsController.TYPE_MOBILE || lastKnownNetworkType == StatsController.TYPE_WIFI) && System.currentTimeMillis() - lastNetworkCheckTypeTime < 5000) {
                    return lastKnownNetworkType;
                }
                if (connectivityManager.isActiveNetworkMetered()) {
                    lastKnownNetworkType = StatsController.TYPE_MOBILE;
                } else {
                    lastKnownNetworkType = StatsController.TYPE_WIFI;
                }
                lastNetworkCheckTypeTime = System.currentTimeMillis();
                return lastKnownNetworkType;
            }
            if (currentNetworkInfo.isRoaming()) {
                return StatsController.TYPE_ROAMING;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return StatsController.TYPE_MOBILE;
    }

    public static int getCurrentNetworkType() {
        if (isConnectedOrConnectingToWiFi()) {
            return StatsController.TYPE_WIFI;
        } else if (isRoaming()) {
            return StatsController.TYPE_ROAMING;
        } else {
            return StatsController.TYPE_MOBILE;
        }
    }

    public static boolean isNetworkOnlineFast() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo != null
                    && (currentNetworkInfo.isConnectedOrConnecting() || currentNetworkInfo.isAvailable())) {
                return true;
            }

            NetworkInfo netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
            if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                return true;
            } else {
                netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
            return true;
        }
        return false;
    }

    public static boolean isNetworkOnlineRealtime() {
        try {
            ConnectivityManager connectivityManager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo netInfo = connectivityManager.getActiveNetworkInfo();
            if (netInfo != null && (netInfo.isConnectedOrConnecting() || netInfo.isAvailable())) {
                return true;
            }

            netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);

            if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                return true;
            } else {
                netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
            return true;
        }
        return false;
    }

    public static boolean isNetworkOnline() {
        return Looper.myLooper() == Looper.getMainLooper()
                ? isNetworkOnlineFast() : isNetworkOnlineRealtime();
    }

    public static void startAppCenter(Activity context) {
        applicationLoaderInstance.startAppCenterInternal(context);
    }

    public static void checkForUpdates() {
        applicationLoaderInstance.checkForUpdatesInternal();
    }

    public static void appCenterLog(Throwable e) {
        applicationLoaderInstance.appCenterLogInternal(e);
    }

    protected void appCenterLogInternal(Throwable e) {

    }

    protected void checkForUpdatesInternal() {

    }

    protected void startAppCenterInternal(Activity context) {

    }

    public static void logDualCamera(boolean success, boolean vendor) {
        applicationLoaderInstance.logDualCameraInternal(success, vendor);
    }

    protected void logDualCameraInternal(boolean success, boolean vendor) {

    }

    public boolean checkApkInstallPermissions(final Context context) {
        return false;
    }

    public boolean openApkInstall(Activity activity, TLRPC.Document document) {
        return false;
    }

    public boolean showUpdateAppPopup(Context context, TLRPC.TL_help_appUpdate update, int account) {
        return false;
    }

    public boolean showCustomUpdateAppPopup(Context context, BetaUpdate update, int account) {
        return false;
    }

    public IUpdateLayout takeUpdateLayout(Activity activity, ViewGroup sideMenuContainer) {
        return null;
    }

    public TLRPC.Update parseTLUpdate(int constructor) {
        return null;
    }

    public void processUpdate(int currentAccount, TLRPC.Update update) {

    }

    public boolean onSuggestionFill(String suggestion, CharSequence[] output, boolean[] closeable) {
        return false;
    }

    public boolean onSuggestionClick(String suggestion) {
        return false;
    }

    public void addItemOptions(ItemOptions itemOptions) {

    }

    public boolean checkRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        return false;
    }

    public boolean consumePush(int account, JSONObject json) {
        return false;
    }

    public void onResume() {

    }

    public boolean onPause() {
        return false;
    }

    public BaseFragment openSettings(int n) {
        return null;
    }

    public boolean isCustomUpdate() {
        return false;
    }
    public void downloadUpdate() {}
    public void cancelDownloadingUpdate() {}
    public boolean isDownloadingUpdate() {
        return false;
    }
    public float getDownloadingUpdateProgress() {
        return 0.0f;
    }
    public void checkUpdate(boolean force, Runnable whenDone) {}
    public BetaUpdate getUpdate() {
        return null;
    }
    public File getDownloadedUpdateFile() {
        return null;
    }
}
