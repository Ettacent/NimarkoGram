/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.documents;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Handler;
import android.os.Build;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;
import androidx.webkit.WebViewStartUpConfig;
import androidx.webkit.WebViewRenderProcess;
import androidx.webkit.WebViewRenderProcessClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.Components.blur3.capture.IBlur3Hash;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

public final class OfficeDocumentView extends FrameLayout {
    public static final int ERROR_FILE = 1, ERROR_TOO_LARGE = 2, ERROR_WEBVIEW = 3,
            ERROR_PASSWORD = 4, ERROR_TIMEOUT = 5;
    private static final int MAX_COUNT = 100000, MAX_MESSAGE = 262144, MAX_QUERY = 4096;
    private static final boolean DOM_DOCX_READER_ENABLED = true;
    private static final boolean DOM_XLSX_READER_ENABLED = true;
    private static final boolean DOM_PPTX_READER_ENABLED = true;
    private static final boolean DOM_PDF_READER_ENABLED = true;

    public static boolean usesWebPdf() { return DOM_PDF_READER_ENABLED; }

    private static boolean domReaderEnabled(String format) {
        return "docx".equals(format) && DOM_DOCX_READER_ENABLED
                || "xlsx".equals(format) && DOM_XLSX_READER_ENABLED
                || "pptx".equals(format) && DOM_PPTX_READER_ENABLED
                || "pdf".equals(format) && DOM_PDF_READER_ENABLED;
    }
    private static final String CSP = "default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; "
            + "worker-src 'self' blob:; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; "
            + "font-src 'self' data: blob:; connect-src 'self'; frame-src 'none'; object-src 'none'; "
            + "base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    public interface Listener {
        void onReady(int count);
        void onPosition(int index, int count, String label);
        default void onSearch(int index, int count, boolean truncated) {}
        default void onSearch(int index, int count, boolean truncated, boolean pending) {
            if (!pending) onSearch(index, count, truncated);
        }
        void onFailure(int code);
        default void onNativePdfRequired() { onFailure(ERROR_WEBVIEW); }
        default boolean isResumeFrameStateSettled() { return true; }
    }

    private static final class Session {
        final long generation;
        final File file;
        final String format;
        final boolean domReader;
        final boolean nativeGestures;
        final String assetRoot;
        String password;
        boolean cancelled;
        byte[] document;
        Set<String> assets = Collections.emptySet();
        Session(long generation, File file, String format, boolean domReader) {
            this.generation = generation;
            this.file = file;
            this.format = format;
            this.domReader = domReader;
            nativeGestures = domReader && !"xlsx".equals(format);
            assetRoot = domReader ? "office/gander-" + format : "office";
        }
    }

    private static final class WebViewStartup {
        private static final Handler main = new Handler(Looper.getMainLooper());
        private static final Map<Session, IntConsumer> waiting = new HashMap<>();
        private static boolean started, complete;
        private static long attemptGeneration;
        private static ThreadPoolExecutor executor;
        private static Runnable watchdog;

        @android.annotation.SuppressLint("UnsafeOptInUsageError")
        static void prepare(Context context, Session target, IntConsumer callback) {
            requireMain();
            if (complete) {
                callback.accept(0);
                return;
            }
            waiting.put(target, callback);
            if (started) return;
            started = true;
            final long attempt = ++attemptGeneration;
            watchdog = () -> finish(attempt, ERROR_TIMEOUT);
            main.postDelayed(watchdog, 30000);
            try {
                if (executor == null) {
                    executor = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
                            new LinkedBlockingQueue<>(64), r -> {
                        Thread thread = new Thread(r, "NimarkoWebViewStartup");
                        thread.setDaemon(true);
                        return thread;
                    });
                    executor.allowCoreThreadTimeOut(true);
                }
                WebViewStartUpConfig config = new WebViewStartUpConfig.Builder(task -> executor.execute(() -> {
                    try {
                        task.run();
                    } catch (RuntimeException | LinkageError error) {
                        main.post(() -> finish(attempt, ERROR_WEBVIEW));
                    } catch (OutOfMemoryError error) {
                        main.post(() -> finish(attempt, ERROR_TOO_LARGE));
                    }
                })).setShouldRunUiThreadStartUpTasks(false).build();
                WebViewCompat.startUpWebView(context.getApplicationContext(), config,
                        startupResult -> main.post(() -> finish(attempt, 0)));
            } catch (RuntimeException | LinkageError error) {
                main.post(() -> finish(attempt, ERROR_WEBVIEW));
            } catch (OutOfMemoryError error) {
                main.post(() -> finish(attempt, ERROR_TOO_LARGE));
            }
        }

        static void cancel(Session target) {
            requireMain();
            waiting.remove(target);
        }

        private static void finish(long attempt, int code) {
            requireMain();
            if (!started || attempt != attemptGeneration) return;
            if (watchdog != null) main.removeCallbacks(watchdog);
            watchdog = null;
            started = false;
            complete = code == 0;
            IntConsumer[] callbacks = waiting.values().toArray(new IntConsumer[0]);
            waiting.clear();
            for (IntConsumer callback : callbacks) callback.accept(code);
        }
    }

    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String origin = "https://" + UUID.randomUUID().toString() + ".office.invalid";
    private Session session;
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                || event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) cancelNativeZoom();
        return super.dispatchTouchEvent(event);
    }
    private long generation;
    private WebView webView;
    private ActionMode copyActionMode;
    private static class NativeZoomWebView extends WebView {
        final NativeZoomFlight zoomFlight = new NativeZoomFlight(this);
        NativeZoomWebView(Context context) { super(context); }
    }
    private static final boolean USE_SHARED_COMPOSITION_LAYER = true;
    private Object contentRenderNode;
    private WebView recordedWebView;
    private boolean recordingContentNode;
    private boolean glassPending;
    private JSONObject pendingGlassFrame;
    private long glassEpoch;
    private String positionLabel;
    private int queryRevision;
    private ExecutorService worker;
    private Future<?> loading;
    private boolean bridgeReady, ready, allowCopy, paused;
    private int currentIndex, count, background = 0xffffffff, foreground = 0xff202020, accent = 0xff3390ec;
    private double scale = 1;
    private int bottomInset;
    private int topInset;
    private double sentBottomInset = -1;
    private double sentTopInset = -1;
    private final PdfReadingColors readingColors = new PdfReadingColors();
    private boolean preserveReadingImages = true;
    private String[] names = new String[0];
    private String query = "";
    private Runnable timeout;
    private Runnable pendingStartup;
    private Session preparingSession;
    private boolean webViewPrepared;
    private boolean readyDelivered, revealing, colorScheduled, colorInFlight, colorsDirty;
    private static final long RESUME_VISUAL_TIMEOUT_MS = 1000;
    private boolean resumeVisualPending, resumeVisualInFlight, resumePaletteUnconfirmed, interruptedReveal;
    private long resumeGeneration, resumeVisualRevision;
    private Runnable resumeVisualTimeout;
    private final Runnable confirmResumeVisual = this::confirmResumeVisual;
    private long openingDeadline, remainingOpeningTime;
    private long revealGeneration, colorGeneration;
    private int paletteReplays;
    private float nativeFitScale;
    private ValueAnimator nativeZoomAnimator;
    private final NativeZoomProgress nativeZoomProgress = new NativeZoomProgress();
    private boolean nativeZoomObserving, nativeZoomHasPending, nativeZoomPendingReset;
    private boolean nativeZoomGateWaiting, nativeZoomGateHeld;
    private long nativeZoomGateRevision;
    private long nativeZoomGateDeadline;
    private Runnable nativeZoomGateTimeout;
    private boolean nativeZoomIgnoreInHint, nativeZoomIgnoreOutHint;
    private double nativeZoomPendingFactor = 1;
    private float nativeZoomPendingLast = 1;
    private final Runnable flushColors = this::flushColors;
    private long nativeZoomProbeDeadline;
    private Runnable nativeZoomNextSample;
    private final Runnable nativeZoomTimeout = this::timeoutNativeZoomProbe;

    public OfficeDocumentView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        if (Build.VERSION.SDK_INT < 29 || !canvas.isHardwareAccelerated()) {
            super.dispatchDraw(canvas);
            return;
        }
        WebView view = webView;
        Session target = session;
        if (view == null || !active(target) || getWidth() <= 0 || getHeight() <= 0) {
            releaseContentRenderNode();
            return;
        }
        if (recordingContentNode) return;
        if (contentRenderNode == null) {
            RenderNode created = new RenderNode("OfficeDocumentContent");
            created.setUseCompositingLayer(USE_SHARED_COMPOSITION_LAYER, null);
            contentRenderNode = created;
        }
        RenderNode node = (RenderNode) contentRenderNode;
        node.setPosition(0, 0, getWidth(), getHeight());
        recordedWebView = null;
        Canvas recording = node.beginRecording();
        recordingContentNode = true;
        boolean complete = false;
        try {
            super.dispatchDraw(recording);
            complete = true;
        } finally {
            node.endRecording();
            recordingContentNode = false;
            if (!complete || contentRenderNode != node || view != webView || !active(target)) {
                node.setUseCompositingLayer(false, null);
                node.discardDisplayList();
                if (contentRenderNode == node) contentRenderNode = null;
            }
        }
        if (contentRenderNode == node && view == webView && active(target)) {
            recordedWebView = view;
            canvas.drawRenderNode(node);
        }
    }

    void captureForBlur(Canvas canvas) {
        if (Build.VERSION.SDK_INT < 29 || !canvas.isHardwareAccelerated()) {
            draw(canvas);
            return;
        }
        if (getBackground() != null) getBackground().draw(canvas);
        else canvas.drawColor(background);
        if (recordingContentNode || contentRenderNode == null || recordedWebView != webView
                || webView == null || !active(session)) return;
        RenderNode node = (RenderNode) contentRenderNode;
        if (node.hasDisplayList() && node.getWidth() == getWidth() && node.getHeight() == getHeight()) {
            canvas.drawRenderNode(node);
        }
    }

    void captureBlurHash(IBlur3Hash hash) {
        hash.add(getBackground() instanceof ColorDrawable ? ((ColorDrawable) getBackground()).getColor() : background);
        boolean ready = Build.VERSION.SDK_INT >= 29 && !recordingContentNode && contentRenderNode != null
                && recordedWebView == webView && webView != null && active(session);
        RenderNode node = ready ? (RenderNode) contentRenderNode : null;
        hash.add(node != null && node.hasDisplayList() && node.getWidth() == getWidth()
                && node.getHeight() == getHeight() ? node.getUniqueId() : 0L);
    }

    private void releaseContentRenderNode() {
        Object node = contentRenderNode;
        contentRenderNode = null;
        recordedWebView = null;
        if (Build.VERSION.SDK_INT >= 29 && node != null && !recordingContentNode) {
            RenderNode released = (RenderNode) node;
            released.setUseCompositingLayer(false, null);
            released.discardDisplayList();
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        cancelNativeZoom();
        releaseContentRenderNode();
        super.onDetachedFromWindow();
    }

    public void open(File file, String format, int initialIndex) {
        open(file, format, initialIndex, true, null);
    }

    public void openPdf(File file, int initialIndex, String password) {
        open(file, "pdf", initialIndex, true, password);
    }

    private void open(File file, String format, int initialIndex, boolean allowDomReader, String password) {
        requireMain();
        close();
        String kind = format == null ? "" : format.toLowerCase(Locale.ROOT);
        Session target = new Session(++generation, file, kind,
                allowDomReader && domReaderEnabled(kind));
        target.password = password;
        session = target;
        if (file == null) {
            post(target, () -> fail(target, ERROR_FILE));
            return;
        }
        if (password != null && password.length() > 4096) {
            post(target, () -> fail(target, ERROR_PASSWORD));
            return;
        }
        if (kind.equals("pdf") && !target.domReader) {
            requireNativePdf(target);
            return;
        }
        if (file == null || !(kind.equals("docx") || kind.equals("xlsx") || kind.equals("pptx") || kind.equals("pdf"))) {
            post(target, () -> fail(target, ERROR_FILE));
            return;
        }
        timeout = () -> fail(target, ERROR_TIMEOUT);
        remainingOpeningTime = 60000;
        openingDeadline = SystemClock.uptimeMillis() + remainingOpeningTime;
        if (!paused) handler.postAtTime(timeout, target, openingDeadline);
        try {
            worker = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "NimarkoOfficeInput");
                thread.setDaemon(true);
                return thread;
            });
            loading = worker.submit(() -> {
                try {
                    long deadline = System.nanoTime() + 30000000000L;
                    byte[] bytes = readRegular(file, kind, deadline, target);
                    Set<String> assets = new HashSet<>();
                    try {
                        if (target.domReader) {
                            collectAssets("", assets, 0, target);
                        } else {
                            assets.add("/index.html");
                            assets.add("/viewer.js");
                            assets.add("/quality.js");
                            assets.add("/office-tile-cache.js");
                            collectAssets("engine", assets, 0, target);
                        }
                    } catch (IOException e) {
                        throw new OfficeInput.InputException(ERROR_WEBVIEW);
                    }
                    synchronized (target) {
                        if (target.cancelled) return;
                        target.document = bytes;
                        target.assets = Collections.unmodifiableSet(assets);
                        post(target, () -> createWebView(target, kind, Math.max(0, initialIndex)));
                    }
                } catch (CancellationException ignored) {
                } catch (PdfWebInput.NativeFallback e) {
                    post(target, () -> requireNativePdf(target));
                } catch (PdfWebInput.Deadline e) {
                    post(target, () -> fail(target, ERROR_TIMEOUT));
                } catch (OfficeInput.InputException e) {
                    post(target, () -> fail(target, e.code));
                } catch (OutOfMemoryError e) {
                    post(target, () -> fail(target, ERROR_TOO_LARGE));
                } catch (Exception e) {
                    post(target, () -> fail(target, ERROR_FILE));
                }
            });
        } catch (RuntimeException | LinkageError e) {
            post(target, () -> fail(target, ERROR_WEBVIEW));
        }
    }

    private byte[] readRegular(File file, String format, long deadline, Session target) throws Exception {
        FileDescriptor fd = Os.open(file.getAbsolutePath(), OsConstants.O_RDONLY | OsConstants.O_NONBLOCK
                | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
        try {
            StructStat stat = Os.fstat(fd);
            if (!OsConstants.S_ISREG(stat.st_mode)) throw new OfficeInput.InputException(ERROR_FILE);
            if (!"pdf".equals(format) && stat.st_size > OfficeInput.MAX_COMPRESSED) throw new OfficeInput.InputException(ERROR_TOO_LARGE);
            FileInputStream input = new FileInputStream(fd);
            fd = null;
            try (FileInputStream owned = input) {
                if ("pdf".equals(format)) {
                    return PdfWebInput.read(owned, stat.st_size, Runtime.getRuntime().maxMemory(), deadline, () -> {
                        synchronized (target) { return target.cancelled; }
                    });
                }
                return OfficeInput.read(owned, format, deadline, () -> {
                    synchronized (target) { return target.cancelled; }
                });
            }
        } finally {
            if (fd != null) Os.close(fd);
        }
    }

    private void collectAssets(String path, Set<String> assets, int depth, Session target) throws IOException {
        synchronized (target) { if (target.cancelled) throw new CancellationException(); }
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
        if (depth > 16 || assets.size() > 4096) throw new IOException();
        String[] children = getContext().getAssets().list(target.assetRoot + (path.isEmpty() ? "" : "/" + path));
        if (children == null) throw new IOException();
        if (children.length == 0) {
            if (!path.isEmpty() && !path.equals("engine")) assets.add("/" + path);
            return;
        }
        for (String child : children) {
            if (!child.matches("[A-Za-z0-9_@.-]+") || child.equals(".") || child.equals("..")) throw new IOException();
            collectAssets(path.isEmpty() ? child : path + "/" + child, assets, depth + 1, target);
        }
    }

    @SuppressWarnings("deprecation")
    private void createWebView(Session target, String format, int initialIndex) {
        requireMain();
        if (!active(target)) return;
        if (paused) {
            pendingStartup = () -> createWebView(target, format, initialIndex);
            return;
        }
        pendingStartup = null;
        if (webView != null) return;
        if (!webViewPrepared) {
            if (preparingSession == target) return;
            preparingSession = target;
            WebViewStartup.prepare(getContext(), target, code -> {
                if (!active(target)) return;
                preparingSession = null;
                if (code != 0) {
                    fail(target, code);
                    return;
                }
                webViewPrepared = true;
                createWebView(target, format, initialIndex);
            });
            return;
        }
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                fail(target, ERROR_WEBVIEW);
                return;
            }
            WebView view = new NativeZoomWebView(getContext()) {
                @Override public ActionMode startActionMode(ActionMode.Callback callback) {
                    return startActionMode(callback, ActionMode.TYPE_PRIMARY);
                }
                @Override public ActionMode startActionMode(ActionMode.Callback callback, int type) {
                    return canCopy(this, target)
                            ? super.startActionMode(copyActionModeCallback(this, target, callback), type) : null;
                }
                @Override public boolean showContextMenu() {
                    return canCopy(this, target) && super.showContextMenu();
                }
                @Override public boolean showContextMenu(float x, float y) {
                    return canCopy(this, target) && super.showContextMenu(x, y);
                }
            };
            webView = view;
            view.setAlpha(0f);
            view.setVerticalScrollBarEnabled(false);
            view.setHorizontalScrollBarEnabled(false);
            WebSettings settings = view.getSettings();
            if (Build.VERSION.SDK_INT >= 29) view.setForceDarkAllowed(false);
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false);
            }
            settings.setJavaScriptEnabled(true);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setAllowFileAccessFromFileURLs(false);
            settings.setAllowUniversalAccessFromFileURLs(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            settings.setDomStorageEnabled(false);
            settings.setDatabaseEnabled(false);
            settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
            settings.setBlockNetworkLoads(true);
            settings.setJavaScriptCanOpenWindowsAutomatically(false);
            settings.setSupportMultipleWindows(true);
            settings.setGeolocationEnabled(false);
            settings.setSaveFormData(false);
            settings.setMediaPlaybackRequiresUserGesture(true);
            settings.setSupportZoom(target.nativeGestures);
            settings.setBuiltInZoomControls(target.nativeGestures);
            settings.setDisplayZoomControls(false);
            if (target.domReader) {
                settings.setUseWideViewPort(true);
                settings.setLoadWithOverviewMode(true);
                settings.setTextZoom(100);
            }
            view.setOnLongClickListener(v -> !canCopy(view, target));
            view.setLongClickable(allowCopy);
            view.setHapticFeedbackEnabled(false);
            if (Build.VERSION.SDK_INT >= 26) view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
            view.setDownloadListener((url, agent, disposition, mime, length) -> {});
            view.setWebChromeClient(new WebChromeClient() {
                @Override public boolean onCreateWindow(WebView v, boolean dialog, boolean gesture, Message result) { return false; }
                @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
                @Override public void onGeolocationPermissionsShowPrompt(String url, GeolocationPermissions.Callback callback) {
                    callback.invoke(url, false, false);
                }
                @Override public boolean onShowFileChooser(WebView v, android.webkit.ValueCallback<Uri[]> callback,
                                                         FileChooserParams params) { callback.onReceiveValue(null); return true; }
            });
            view.setWebViewClient(new WebViewClient() {
                @Override public void onScaleChanged(WebView v, float oldScale, float newScale) {
                    if (target.nativeGestures && active(target) && v == webView && newScale > 0
                            && !Float.isNaN(newScale) && !Float.isInfinite(newScale)) {
                        scale = nativeFitScale > 0 ? newScale / nativeFitScale : 1;
                    }
                }
                @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) { return true; }
                @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return true; }
                @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                    return response(target, request.getUrl(), request.getMethod());
                }
                @Override public WebResourceResponse shouldInterceptRequest(WebView v, String url) {
                    return response(target, Uri.parse(url), "GET");
                }
                @Override public void onReceivedSslError(WebView v, SslErrorHandler h, SslError error) { h.cancel(); }
                @Override public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
                    if (request.isForMainFrame() || isEssential(target, request.getUrl())) {
                        post(target, () -> fail(target, ERROR_WEBVIEW));
                    }
                }
                @Override public void onReceivedError(WebView v, int code, String description, String url) {
                    if (url != null && isEssential(target, Uri.parse(url))) {
                        post(target, () -> fail(target, ERROR_WEBVIEW));
                    }
                }
                @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                    if (v == webView && active(target)) fail(target, ERROR_WEBVIEW);
                    return true;
                }
            });
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_CLIENT_BASIC_USAGE)) {
                WebViewCompat.setWebViewRenderProcessClient(view, new WebViewRenderProcessClient() {
                    @Override public void onRenderProcessUnresponsive(WebView v, WebViewRenderProcess renderer) {
                        post(target, () -> {
                            if (v == webView) fail(target, ERROR_TIMEOUT);
                        });
                    }
                    @Override public void onRenderProcessResponsive(WebView v, WebViewRenderProcess renderer) {}
                });
            }
            WebViewCompat.addWebMessageListener(view, "OfficeHost", Collections.singleton(origin),
                    (v, message, sourceOrigin, mainFrame, reply) -> {
                        if (!mainFrame || !origin.equals(sourceOrigin.toString()) || v != webView) return;
                        try {
                            String data = message.getData();
                            if (data == null || data.length() > MAX_MESSAGE) return;
                            post(target, () -> receive(target, data, format, initialIndex));
                        } catch (RuntimeException ignored) {}
                    });
            addView(view, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            view.setBackgroundColor(background);
            if (paused) view.onPause();
            view.loadUrl(origin + "/index.html");
        } catch (RuntimeException | LinkageError e) {
            fail(target, ERROR_WEBVIEW);
        } catch (OutOfMemoryError e) {
            fail(target, ERROR_TOO_LARGE);
        }
    }

    private boolean isEssential(Session target, Uri uri) {
        if (!"https".equals(uri.getScheme()) || !origin.substring(8).equals(uri.getEncodedAuthority())
                || uri.getEncodedQuery() != null || uri.getEncodedFragment() != null) return false;
        String path = uri.getEncodedPath();
        if ("/index.html".equals(path) || "/viewer.js".equals(path) || "/document".equals(path)) return true;
        synchronized (target) {
            return path != null && target.assets.contains(path) && path.startsWith("/engine/")
                    && (path.endsWith(".js") || path.endsWith(".mjs") || path.endsWith(".wasm") || path.endsWith(".json")
                    || target.domReader && path.endsWith(".css"));
        }
    }

    private WebResourceResponse response(Session target, Uri uri, String method) {
        try {
            if (!"GET".equals(method) || !"https".equals(uri.getScheme())
                    || !origin.substring(8).equals(uri.getEncodedAuthority()) || uri.getEncodedQuery() != null
                    || uri.getEncodedFragment() != null) return denied();
            String path = uri.getEncodedPath();
            if (path == null || path.indexOf('%') >= 0 || path.indexOf('\\') >= 0) return denied();
            InputStream input;
            synchronized (target) {
                if (target.cancelled) return denied();
                if ("/document".equals(path)) {
                    if (target.document == null) return denied();
                    input = new DocumentStream(target);
                } else {
                    if (!target.assets.contains(path)) return denied();
                    input = null;
                }
            }
            if (input == null) {
                input = getContext().getAssets().open(target.assetRoot + path);
                boolean cancelled;
                synchronized (target) { cancelled = target.cancelled; }
                if (cancelled) {
                    input.close();
                    return denied();
                }
            }
            return new WebResourceResponse(mime(path), null, 200, "OK", headers(), input);
        } catch (Exception e) {
            post(target, () -> fail(target, ERROR_WEBVIEW));
            return denied();
        }
    }

    private static Map<String, String> headers() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Security-Policy", CSP);
        headers.put("Cache-Control", "no-store");
        headers.put("X-Content-Type-Options", "nosniff");
        headers.put("Referrer-Policy", "no-referrer");
        headers.put("Permissions-Policy", "camera=(), microphone=(), geolocation=(), clipboard-read=(), clipboard-write=()");
        return headers;
    }

    private static WebResourceResponse denied() {
        return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", headers(), new ByteArrayInputStream(new byte[0]));
    }

    private static String mime(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".js") || path.endsWith(".mjs")) return "application/javascript";
        if (path.endsWith(".wasm")) return "application/wasm";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".woff")) return "font/woff";
        if (path.endsWith(".ttf")) return "font/ttf";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private static final class DocumentStream extends InputStream {
        private final Session target;
        private int position;
        private boolean closed;
        DocumentStream(Session target) { this.target = target; }
        @Override public int read() {
            synchronized (target) {
                if (closed || target.cancelled || target.document == null || position == target.document.length) return -1;
                return target.document[position++] & 255;
            }
        }
        @Override public int read(byte[] b, int off, int len) {
            if (b == null) throw new NullPointerException();
            if (off < 0 || len < 0 || off > b.length - len) throw new IndexOutOfBoundsException();
            if (len == 0) return 0;
            synchronized (target) {
                if (closed || target.cancelled || target.document == null || position == target.document.length) return -1;
                int n = Math.min(16384, Math.min(len, target.document.length - position));
                System.arraycopy(target.document, position, b, off, n);
                position += n;
                return n;
            }
        }
        @Override public void close() { synchronized (target) { closed = true; } }
    }

    private void receive(Session target, String data, String format, int initialIndex) {
        if (!active(target)) return;
        try {
            JSONObject json = new JSONObject(data);
            String type = string(json, "type", 32);
            if ("bridgeReady".equals(type)) {
                if (bridgeReady) return;
                if (target.domReader && !target.nativeGestures
                        && !"xlsx-local-v1".equals(json.opt("gestureMode"))) {
                    String pendingQuery = query;
                    open(target.file, target.format, initialIndex, false, null);
                    setQuery(pendingQuery);
                    return;
                }
                bridgeReady = true;
                JSONObject open = colors(command("open"));
                open.put("format", format).put("index", initialIndex).put("allowCopy", allowCopy)
                        .put("motionEnabled", motionEnabled()).put("nativeGestures", target.nativeGestures);
                if (target.domReader && !target.nativeGestures) open.put("gestureMode", "xlsx-local-v1");
                if ("pdf".equals(format) && target.password != null) open.put("password", target.password);
                target.password = null;
                putInsets(open);
                colorsDirty = false;
                send(open);
                if (active(target) && paused) send(command("pause"));
            } else if (bridgeReady && !ready && "pdf".equals(format) && "password".equals(type)) {
                integer(json, "requestId", 1, Integer.MAX_VALUE);
                String reason = string(json, "reason", 16);
                if ("required".equals(reason) || "incorrect".equals(reason)) {
                    fail(target, ERROR_PASSWORD);
                }
            } else if ("error".equals(type)) {
                if (target.domReader && !ready && Boolean.TRUE.equals(json.opt("fallbackRequired"))) {
                    if ("pdf".equals(target.format)) {
                        requireNativePdf(target);
                        return;
                    }
                    String pendingQuery = query;
                    open(target.file, target.format, initialIndex, false, null);
                    setQuery(pendingQuery);
                    return;
                }
                fail(target, integer(json, "code", ERROR_FILE, ERROR_TIMEOUT));
            } else if (bridgeReady && "ready".equals(type) && !ready) {
                int nextCount = integer(json, "count", 1, MAX_COUNT);
                int nextIndex = integer(json, "index", 0, nextCount - 1);
                JSONArray array = json.getJSONArray("names");
                if (array.length() > 4096 || array.length() != 0 && array.length() != nextCount) return;
                String[] nextNames = new String[array.length()];
                int chars = 0;
                for (int i = 0; i < nextNames.length; i++) {
                    Object name = array.get(i);
                    if (!(name instanceof String) || ((String) name).length() > 256) return;
                    nextNames[i] = (String) name;
                    chars += nextNames[i].length();
                    if (chars > 65536) return;
                }
                count = nextCount;
                currentIndex = nextIndex;
                names = nextNames;
                ready = true;
                sendInsets();
                if (!active(target)) return;
                send(command("query").put("query", query).put("revision", queryRevision));
                if (active(target) && paused) send(command("pause"));
                reveal(target);
            } else if (ready && "nativeZoomActivityAck".equals(type)) {
                if (target.nativeGestures && "xlsx".equals(target.format) && nativeZoomGateWaiting
                        && json.opt("revision") instanceof Number
                        && json.optLong("revision", -1) == nativeZoomGateRevision
                        && json.optDouble("revision", -1) == nativeZoomGateRevision
                        && Boolean.TRUE.equals(json.opt("active")) && !paused && webView != null) {
                    if (SystemClock.uptimeMillis() > nativeZoomGateDeadline) { cancelNativeZoom(); return; }
                    nativeZoomGateWaiting = false;
                    nativeZoomGateHeld = true;
                    clearNativeZoomGateTimeout();
                    observeNativeZoom(false);
                }
            } else if (ready && "position".equals(type)) {
                int nextCount = integer(json, "count", 1, MAX_COUNT);
                int nextIndex = integer(json, "index", 0, nextCount - 1);
                String label = string(json, "label", 1024);
                if (nextCount != count) return;
                if (currentIndex == nextIndex && label.equals(positionLabel)) return;
                currentIndex = nextIndex;
                positionLabel = label;
                if (listener != null) listener.onPosition(currentIndex, count, label);
            } else if (ready && "frame".equals(type)) {
                int frameGeneration = integer(json, "generation", 0, Integer.MAX_VALUE);
                int frameId = integer(json, "frameId", 1, Integer.MAX_VALUE);
                if (json.has("coordinateRevision")) integer(json, "coordinateRevision", 0, Integer.MAX_VALUE);
                double x = json.getDouble("x"), y = json.getDouble("y"), width = json.getDouble("width");
                double frameScale = json.has("scale") ? json.getDouble("scale") : scale;
                if (Double.isNaN(x) || Double.isInfinite(x) || Double.isNaN(y) || Double.isInfinite(y)
                        || Double.isNaN(width) || Double.isInfinite(width) || width <= 0
                        || Double.isNaN(frameScale) || frameScale <= 0 || frameScale > 10
                        || Math.abs(x) > 1e9 || Math.abs(y) > 1e9 || paused || webView == null) return;
                if (glassPending) {
                    pendingGlassFrame = json;
                    return;
                }
                WebView view = webView;
                long epoch = glassEpoch;
                glassPending = true;
                try {
                    view.postVisualStateCallback(frameId, new WebView.VisualStateCallback() {
                        @Override public void onComplete(long requestId) {
                            if (!active(target) || view != webView || paused || epoch != glassEpoch) return;
                            glassPending = false;
                            invalidate();
                            ViewParent parent = OfficeDocumentView.this.getParent();
                            while (parent instanceof View && !(parent instanceof DocumentChromeLayout)) parent = parent.getParent();
                            if (parent instanceof DocumentChromeLayout) {
                                ((DocumentChromeLayout) parent).invalidateContentForGlass(OfficeDocumentView.this);
                            }
                            try {
                                send(command("frameAck").put("generation", frameGeneration).put("frameId", frameId));
                            }
                            catch (JSONException ignored) {}
                            JSONObject pending = pendingGlassFrame;
                            pendingGlassFrame = null;
                            if (pending != null) receive(target, pending.toString(), format, initialIndex);
                        }
                    });
                } catch (RuntimeException | LinkageError error) {
                    glassPending = false;
                    fail(target, ERROR_WEBVIEW);
                }
            } else if (ready && "search".equals(type)) {
                if (integer(json, "revision", 0, Integer.MAX_VALUE) != queryRevision) return;
                int hits = integer(json, "count", 0, MAX_COUNT);
                int index = integer(json, "index", -1, hits - 1);
                Object truncated = json.get("truncated");
                Object pending = json.get("pending");
                if (!(truncated instanceof Boolean) || !(pending instanceof Boolean)) return;
                if (listener != null) listener.onSearch(index, hits, (Boolean) truncated, (Boolean) pending);
            } else if (ready && target.nativeGestures && "nativeZoom".equals(type)) {
                String operation = string(json, "operation", 16);
                if ("in".equals(operation)) zoomIn();
                else if ("out".equals(operation)) zoomOut();
                else if ("reset".equals(operation)) resetZoom();
            } else if (bridgeReady && "scale".equals(type)) {
                Object value = json.get("scale");
                if (!(value instanceof Number)) return;
                double nextScale = ((Number) value).doubleValue();
                if (!Double.isNaN(nextScale) && nextScale >= 0.1 && nextScale <= 10) scale = nextScale;
            }
        } catch (JSONException | IllegalArgumentException ignored) {}
    }

    private static int integer(JSONObject json, String key, int min, int max) throws JSONException {
        Object value = json.get(key);
        if (!(value instanceof Number)) throw new JSONException(key);
        double number = ((Number) value).doubleValue();
        if (Double.isNaN(number) || number < min || number > max || number != Math.rint(number)) throw new JSONException(key);
        return (int) number;
    }

    private static String string(JSONObject json, String key, int max) throws JSONException {
        Object value = json.get(key);
        if (!(value instanceof String) || ((String) value).length() > max) throw new JSONException(key);
        return (String) value;
    }

    private static JSONObject command(String type) {
        JSONObject json = new JSONObject();
        try { json.put("type", type); } catch (JSONException e) { throw new IllegalArgumentException(e); }
        return json;
    }

    private JSONObject colors(JSONObject json) throws JSONException {
        json.put("background", background).put("foreground", foreground).put("accent", accent)
                .put("readingDark", readingColors.dark).put("readingPaper", readingColors.page)
                .put("preserveImages", preserveReadingImages);
        if (readingColors.dark) {
            JSONArray matrix = new JSONArray();
            for (int i = 0; i < readingColors.matrix.length; i++) {
                matrix.put(readingColors.matrix[i] / (i % 5 == 4 ? 255.0 : 1.0));
            }
            json.put("readingMatrix", matrix);
        }
        return json;
    }

    private void send(JSONObject json) {
        requireMain();
        if (webView == null || !bridgeReady || session == null) {
            return;
        }
        try {
            String encoded = json.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
            WebView view = webView;
            view.evaluateJavascript("window.OfficeViewer.command(" + encoded + ");", null);
        } catch (RuntimeException | LinkageError e) { fail(session, ERROR_WEBVIEW); }
    }

    private void queueColors() {
        if (!colorsDirty || colorScheduled || colorInFlight || revealing || !bridgeReady || webView == null || paused) return;
        colorScheduled = true;
        postOnAnimation(flushColors);
    }

    private void flushColors() {
        colorScheduled = false;
        Session target = session;
        WebView view = webView;
        if (!active(target) || view == null || !bridgeReady || paused || colorInFlight || revealing) return;
        if (ready && !readyDelivered) { reveal(target); return; }
        if (!colorsDirty) { reveal(target); queueResumeVisual(); return; }
        colorsDirty = false;
        colorInFlight = true;
        long transaction = ++colorGeneration;
        setBackgroundColor(background);
        view.setBackgroundColor(background);
        try {
            view.evaluateJavascript("window.OfficeViewer.command(" + colors(command("colors")) + ");", result -> {
                if (!active(target) || view != webView || transaction != colorGeneration) return;
                colorInFlight = false;
                if (ready && !readyDelivered) reveal(target); else queueColors();
                queueResumeVisual();
            });
        } catch (JSONException | RuntimeException | LinkageError error) {
            colorInFlight = false;
            fail(target, ERROR_WEBVIEW);
        }
    }

    public boolean isReadyForResumeSnapshot() {
        requireMain();
        return !resumeVisualPending || resumePaletteUnconfirmed;
    }

    private void beginResumeVisual() {
        cancelResumeVisual();
        Session target = session;
        WebView view = webView;
        if (!active(target) || view == null || paused || !readyDelivered) return;
        resumeVisualPending = true;
        resumePaletteUnconfirmed = target.domReader && "pdf".equals(target.format)
                && (colorsDirty || colorInFlight);
        long transaction = resumeGeneration;
        resumeVisualTimeout = () -> finishResumeVisual(target, view, transaction, true);
        handler.postAtTime(resumeVisualTimeout, target, SystemClock.uptimeMillis() + RESUME_VISUAL_TIMEOUT_MS);
        queueResumeVisual();
    }

    private void cancelResumeVisual() {
        ++resumeGeneration;
        resumeVisualPending = resumeVisualInFlight = resumePaletteUnconfirmed = false;
        removeCallbacks(confirmResumeVisual);
        if (resumeVisualTimeout != null) handler.removeCallbacks(resumeVisualTimeout);
        resumeVisualTimeout = null;
    }

    private void invalidateResumeVisual() {
        if (!resumeVisualPending) return;
        ++resumeVisualRevision;
        resumeVisualInFlight = false;
        if (session != null && session.domReader && "pdf".equals(session.format) && (colorsDirty || colorInFlight)) {
            resumePaletteUnconfirmed = true;
        }
        queueResumeVisual();
    }

    private void queueResumeVisual() {
        if (!resumeVisualPending || paused || resumeVisualInFlight) return;
        removeCallbacks(confirmResumeVisual);
        postOnAnimation(confirmResumeVisual);
    }

    private void confirmResumeVisual() {
        Session target = session;
        WebView view = webView;
        if (!resumeVisualPending || resumeVisualInFlight || paused || !active(target) || view == null) return;
        if (listener != null && !listener.isResumeFrameStateSettled()) {
            queueResumeVisual();
            return;
        }
        if (colorsDirty || colorScheduled || colorInFlight || revealing) {
            queueColors();
            return;
        }
        long transaction = resumeGeneration, revision = resumeVisualRevision, palette = colorGeneration;
        resumeVisualInFlight = true;
        try {
            view.evaluateJavascript("void 0;", result -> {
                if (!resumeVisualCurrent(target, view, transaction, revision, palette)) return;
                if (listener != null && !listener.isResumeFrameStateSettled()) {
                    invalidateResumeVisual();
                    return;
                }
                try {
                    view.postVisualStateCallback(transaction, new WebView.VisualStateCallback() {
                        @Override public void onComplete(long requestId) {
                            if (resumeVisualCurrent(target, view, transaction, revision, palette)) {
                                if (listener != null && !listener.isResumeFrameStateSettled()) {
                                    invalidateResumeVisual();
                                    return;
                                }
                                finishResumeVisual(target, view, transaction, false);
                            }
                        }
                    });
                } catch (RuntimeException | LinkageError error) {
                    finishResumeVisual(target, view, transaction, true);
                }
            });
        } catch (RuntimeException | LinkageError error) {
            finishResumeVisual(target, view, transaction, true);
        }
    }

    private boolean resumeVisualCurrent(Session target, WebView view, long transaction, long revision, long palette) {
        return resumeVisualPending && active(target) && view == webView && !paused
                && transaction == resumeGeneration && revision == resumeVisualRevision && palette == colorGeneration
                && !colorsDirty && !colorScheduled && !colorInFlight;
    }

    private void finishResumeVisual(Session target, WebView view, long transaction, boolean fallback) {
        if (!resumeVisualPending || !active(target) || view != webView || paused || transaction != resumeGeneration) return;
        cancelResumeVisual();
        if (fallback && colorInFlight) {
            ++colorGeneration;
            colorsDirty = true;
            colorInFlight = false;
            queueColors();
        }
        if (interruptedReveal) {
            interruptedReveal = false;
            view.animate().cancel();
            float alpha = view.getAlpha();
            if (alpha < 1f && motionEnabled()) {
                view.animate().alpha(1f).setDuration(Math.max(1L, Math.round(200 * (1f - alpha))))
                        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            } else if (alpha < 1f) view.setAlpha(1f);
        }
        invalidate();
    }

    private void reveal(Session target) {
        if (!active(target) || !ready || readyDelivered || revealing || colorInFlight || paused || webView == null) return;
        WebView view = webView;
        long transaction = ++revealGeneration;
        long palette = ++colorGeneration;
        removeCallbacks(flushColors);
        colorScheduled = false;
        revealing = true;
        colorsDirty = false;
        colorInFlight = true;
        setBackgroundColor(background);
        view.setBackgroundColor(background);
        try {
            view.evaluateJavascript("window.OfficeViewer.command(" + colors(command("colors")) + ");", result -> {
                if (!active(target) || view != webView || transaction != revealGeneration || palette != colorGeneration) return;
                colorInFlight = false;
                if (paused) return;
                try {
                    view.postVisualStateCallback(transaction, new WebView.VisualStateCallback() {
                        @Override public void onComplete(long requestId) {
                            if (!active(target) || view != webView || transaction != revealGeneration || paused) return;
                            revealing = false;
                            if (colorsDirty && paletteReplays < 4) {
                                paletteReplays++;
                                reveal(target);
                                return;
                            }
                            readyDelivered = true;
                            if (target.nativeGestures) {
                                nativeFitScale = view.getScale();
                                scale = 1;
                                try { send(command("nativeScale").put("scale", 1)); }
                                catch (JSONException ignored) {}
                                if (!active(target) || view != webView) return;
                            }
                            if (timeout != null) handler.removeCallbacks(timeout);
                            view.animate().cancel();
                            if (motionEnabled()) view.animate().alpha(1f).setDuration(200).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                            else view.setAlpha(1f);
                            if (listener != null) listener.onReady(count);
                            if (active(target) && view == webView) queueColors();
                        }
                    });
                } catch (RuntimeException | LinkageError error) {
                    fail(target, ERROR_WEBVIEW);
                }
            });
        } catch (JSONException | RuntimeException | LinkageError error) {
            fail(target, ERROR_WEBVIEW);
        }
    }

    public void goToIndex(int index) {
        requireMain();
        if (!ready || index < 0 || index >= count) return;
        cancelNativeZoom();
        try { send(command("goto").put("index", index)); } catch (JSONException ignored) {}
    }
    public void previous() { requireMain(); cancelNativeZoom(); send(command("previous")); }
    public void next() { requireMain(); cancelNativeZoom(); send(command("next")); }
    public void zoomIn() { if (!zoomNative(1.25f)) send(command("zoomIn")); }
    public void zoomOut() { if (!zoomNative(.8f)) send(command("zoomOut")); }
    private boolean zoomNative(float factor) {
        requireMain();
        if (session == null || !session.nativeGestures) return false;
        if (readyDelivered && !paused && webView != null) animateNativeZoom(factor, false);
        return true;
    }
    public void resetZoom() {
        requireMain();
        if (session != null && session.nativeGestures) {
            if (readyDelivered && !paused && webView != null) animateNativeZoom(1f, true);
        } else send(command("reset"));
    }

    private void animateNativeZoom(float factor, boolean reset) {
        if (webView == null || !active(session) || !session.nativeGestures || !readyDelivered || paused) return;
        boolean fresh = nativeZoomAnimator == null && !nativeZoomObserving;
        int direction = nativeZoomProgress.direction();
        if (direction == 0) direction = nativeZoomProgress.issuedDirection();
        stopNativeZoomAnimator();
        if (fresh) {
            nativeZoomProgress.reset();
            nativeZoomIgnoreInHint = nativeZoomIgnoreOutHint = false;
        }
        if (reset) {
            nativeZoomPendingReset = true;
            nativeZoomPendingFactor = 1;
            nativeZoomPendingLast = 1;
        } else {
            nativeZoomPendingFactor = Math.max(.000001, Math.min(1000000, nativeZoomPendingFactor * factor));
            nativeZoomPendingLast = factor;
        }
        nativeZoomHasPending = true;
        if (session.domReader && "xlsx".equals(session.format) && !nativeZoomGateHeld) {
            if (!nativeZoomGateWaiting && nativeZoomFlightFor(webView).busy()) {
                cancelNativeZoom();
                return;
            }
            acquireNativeZoomGate();
            return;
        }
        if (nativeZoomObserving) return;
        if (fresh || reset || direction * (factor > 1 ? 1 : -1) < 0) {
            observeNativeZoom(false);
            return;
        }
        consumeNativeZoomCommands();
        startNativeZoomAnimator();
    }

    private void consumeNativeZoomCommands() {
        if (nativeZoomPendingReset) nativeZoomProgress.resetToScale(1f);
        nativeZoomProgress.steps(nativeZoomPendingFactor, nativeZoomPendingLast);
        nativeZoomPendingReset = nativeZoomHasPending = false;
        nativeZoomPendingFactor = nativeZoomPendingLast = 1;
    }

    private void startNativeZoomAnimator() {
        final WebView view = webView;
        final Session target = session;
        if (view == null || !active(target) || paused || !nativeZoomProgress.ready()) return;
        final float destination = nativeZoomProgress.target();
        if (!motionEnabled() || !isAttachedToWindow() || !isShown()) {
            if (applyNativeZoom(view, target, destination)) observeNativeZoom(true);
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(nativeZoomProgress.submitted(), destination);
        nativeZoomAnimator = animator;
        animator.setDuration(220);
        animator.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
        animator.addUpdateListener(animation -> {
            if (nativeZoomAnimator != animation) return;
            if (view != webView || !active(target) || paused || !isAttachedToWindow() || !isShown()) {
                cancelNativeZoom();
                return;
            }
            if (!motionEnabled()) {
                stopNativeZoomAnimator();
                if (applyNativeZoom(view, target, destination)) observeNativeZoom(true);
                return;
            }
            applyNativeZoom(view, target, (float) animation.getAnimatedValue());
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (nativeZoomAnimator != animation) return;
                nativeZoomAnimator = null;
                observeNativeZoom(true);
            }
        });
        animator.start();
    }

    private boolean applyNativeZoom(WebView view, Session target, float next) {
        if (view != webView || !active(target) || paused || nativeZoomObserving) return false;
        float delta = nativeZoomProgress.advance(next);
        if (delta == 1f) return true;
        try {
            float remaining = delta;
            while (remaining < .01f) { view.zoomBy(.01f); remaining /= .01f; }
            while (remaining > 100f) { view.zoomBy(100f); remaining /= 100f; }
            view.zoomBy(remaining);
            boolean canIn = view.canZoomIn(), canOut = view.canZoomOut();
            if (canIn) nativeZoomIgnoreInHint = false;
            if (canOut) nativeZoomIgnoreOutHint = false;
            if (delta > 1 && !canIn && !nativeZoomIgnoreInHint
                    || delta < 1 && !canOut && !nativeZoomIgnoreOutHint) {
                stopNativeZoomAnimator();
                observeNativeZoom(false);
                return false;
            }
            return true;
        } catch (RuntimeException | LinkageError error) {
            cancelNativeZoom();
            fail(target, ERROR_WEBVIEW);
            return false;
        }
    }

    private void observeNativeZoom(boolean finishing) {
        if (nativeZoomObserving || webView == null || !active(session) || paused) return;
        stopNativeZoomAnimator();
        final WebView view = webView;
        final Session target = session;
        if (nativeZoomFlightFor(view).busy()) {
            cancelNativeZoom();
            return;
        }
        nativeZoomObserving = true;
        long token = nativeZoomProgress.newObservation();
        long deadline = SystemClock.uptimeMillis() + 750;
        nativeZoomProbeDeadline = deadline;
        nativeZoomNextSample = () -> pollNativeZoom(view, target, token, deadline, finishing);
        removeCallbacks(nativeZoomTimeout);
        postDelayed(nativeZoomTimeout, 750);
        pollNativeZoom(view, target, token, deadline, finishing);
    }

    private NativeZoomFlight nativeZoomFlightFor(WebView view) {
        return ((NativeZoomWebView) view).zoomFlight;
    }

    private void timeoutNativeZoomProbe() {
        if (!nativeZoomObserving) return;
        long remaining = nativeZoomProbeDeadline - SystemClock.uptimeMillis();
        if (remaining > 0) {
            removeCallbacks(nativeZoomTimeout);
            postDelayed(nativeZoomTimeout, remaining);
            return;
        }
        cancelNativeZoom();
    }

    private void clearNativeZoomTimers() {
        removeCallbacks(nativeZoomTimeout);
        if (nativeZoomNextSample != null) removeCallbacks(nativeZoomNextSample);
        nativeZoomNextSample = null;
        nativeZoomProbeDeadline = 0;
    }

    private boolean ownsNativeZoomProbe(WebView view, Session target, long token) {
        return nativeZoomObserving && nativeZoomProgress.currentObservation(token)
                && view == webView && active(target) && !paused;
    }

    private void pollNativeZoom(WebView view, Session target, long token, long deadline, boolean finishing) {
        if (!ownsNativeZoomProbe(view, target, token)) return;
        if (SystemClock.uptimeMillis() > deadline) {
            cancelNativeZoom();
            return;
        }
        final NativeZoomFlight flight = nativeZoomFlightFor(view);
        final NativeZoomFlight.Slot visualSlot = flight.acquire();
        if (visualSlot == null) {
            failNativeZoomProbe(view, target, token);
            return;
        }
        try {
            view.postVisualStateCallback(token, new WebView.VisualStateCallback() {
                @Override public void onComplete(long requestId) {
                    if (!flight.retire(visualSlot)) return;
                    if (!ownsNativeZoomProbe(view, target, token)) return;
                    if (SystemClock.uptimeMillis() > deadline) {
                        failNativeZoomProbe(view, target, token);
                        return;
                    }
                    final NativeZoomFlight.Slot jsSlot = flight.acquire();
                    if (jsSlot == null) {
                        failNativeZoomProbe(view, target, token);
                        return;
                    }
                    try {
                        view.evaluateJavascript("window.OfficeViewer.getNativeZoomState()", value -> {
                            if (!flight.retire(jsSlot)) return;
                            if (!ownsNativeZoomProbe(view, target, token)) return;
                            if (SystemClock.uptimeMillis() > deadline) {
                                failNativeZoomProbe(view, target, token);
                                return;
                            }
                            try {
                                float observed = 0;
                                float rendererPhysicalScale = 0;
                                try {
                                    JSONArray state = new JSONArray(value);
                                    if (state.length() == 3) {
                                        observed = (float) state.getDouble(0);
                                        rendererPhysicalScale = (float) (state.getDouble(1) * state.getDouble(2));
                                    }
                                } catch (JSONException | RuntimeException ignored) {}
                                float uiPhysicalScale = view.getScale();
                                boolean canIn = view.canZoomIn(), canOut = view.canZoomOut();
                                if (!nativeZoomProgress.observeSnapshot(token, observed, rendererPhysicalScale,
                                        uiPhysicalScale, canIn, canOut)) {
                                    if (nativeZoomNextSample != null) {
                                        removeCallbacks(nativeZoomNextSample);
                                        postOnAnimation(nativeZoomNextSample);
                                    }
                                    return;
                                }
                                nativeZoomObserving = false;
                                clearNativeZoomTimers();
                                nativeZoomIgnoreInHint = !canIn;
                                nativeZoomIgnoreOutHint = !canOut;
                                if (finishing) nativeZoomProgress.finish();
                                if (nativeZoomHasPending) {
                                    consumeNativeZoomCommands();
                                    startNativeZoomAnimator();
                                } else if (!finishing) startNativeZoomAnimator();
                                else releaseNativeZoomGate();
                            } catch (RuntimeException | LinkageError error) {
                                failNativeZoomProbe(view, target, token);
                            }
                        });
                    } catch (RuntimeException | LinkageError error) {
                        failNativeZoomProbe(view, target, token);
                    }
                }
            });
        } catch (RuntimeException | LinkageError error) {
            failNativeZoomProbe(view, target, token);
        }
    }

    private void failNativeZoomProbe(WebView view, Session target, long token) {
        if (!ownsNativeZoomProbe(view, target, token)) return;
        cancelNativeZoom();
    }

    private void stopNativeZoomAnimator() {
        ValueAnimator previous = nativeZoomAnimator;
        nativeZoomAnimator = null;
        if (previous != null) {
            previous.removeAllUpdateListeners();
            previous.removeAllListeners();
            previous.cancel();
        }
    }

    private void cancelNativeZoom() {
        stopNativeZoomAnimator();
        clearNativeZoomTimers();
        nativeZoomObserving = nativeZoomHasPending = nativeZoomPendingReset = false;
        nativeZoomPendingFactor = nativeZoomPendingLast = 1;
        nativeZoomIgnoreInHint = nativeZoomIgnoreOutHint = false;
        nativeZoomProgress.reset();
        releaseNativeZoomGate();
    }

    private long nextNativeZoomGateRevision() {
        return ++nativeZoomGateRevision;
    }

    private void clearNativeZoomGateTimeout() {
        if (nativeZoomGateTimeout != null) handler.removeCallbacks(nativeZoomGateTimeout);
        nativeZoomGateTimeout = null;
        nativeZoomGateDeadline = 0;
    }

    private void acquireNativeZoomGate() {
        if (nativeZoomGateWaiting || nativeZoomGateHeld || webView == null || !active(session)) return;
        final Session target = session;
        final WebView view = webView;
        final long revision = nextNativeZoomGateRevision();
        nativeZoomGateDeadline = SystemClock.uptimeMillis() + 750;
        nativeZoomGateWaiting = true;
        nativeZoomGateTimeout = () -> {
            if (active(target) && view == webView && nativeZoomGateWaiting && nativeZoomGateRevision == revision) cancelNativeZoom();
        };
        handler.postAtTime(nativeZoomGateTimeout, target, nativeZoomGateDeadline);
        try { send(command("nativeZoomActivity").put("revision", revision).put("active", true)); }
        catch (JSONException ignored) { cancelNativeZoom(); }
    }

    private void releaseNativeZoomGate() {
        clearNativeZoomGateTimeout();
        if (!nativeZoomGateWaiting && !nativeZoomGateHeld) return;
        nativeZoomGateWaiting = nativeZoomGateHeld = false;
        long revision = nextNativeZoomGateRevision();
        try { send(command("nativeZoomActivity").put("revision", revision).put("active", false)); }
        catch (JSONException ignored) {}
    }
    public boolean isZoomed() { requireMain(); return Math.abs(scale - 1) > 0.001; }
    public int getCurrentIndex() { requireMain(); return currentIndex; }
    public int getCount() { requireMain(); return count; }
    public String[] getSheetNames() { requireMain(); return names.clone(); }
    public String getCurrentSheetName() { requireMain(); return currentIndex >= 0 && currentIndex < names.length ? names[currentIndex] : ""; }

    public void setBottomInset(int pixels) {
        requireMain();
        if (bottomInset != Math.max(0, pixels)) cancelNativeZoom();
        bottomInset = Math.max(0, pixels);
        sendInsets();
    }

    public void setTopInset(int pixels) {
        requireMain();
        if (topInset != Math.max(0, pixels)) cancelNativeZoom();
        topInset = Math.max(0, pixels);
        sendInsets();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw || h != oldh) cancelNativeZoom();
        if (w <= 0 || h <= 0) releaseContentRenderNode();
        sendInsets();
        if (w != oldw || h != oldh) invalidateResumeVisual();
    }

    private void sendInsets() {
        if (!ready || !bridgeReady || webView == null || session == null || paused) return;
        float density = getResources().getDisplayMetrics().density;
        if (!(density > 0) || Float.isInfinite(density)) density = 1;
        double bottom = bottomInset / (double) density;
        double top = topInset / (double) density;
        if (bottom == sentBottomInset && top == sentTopInset) return;
        try {
            JSONObject insets = putInsets(command("insets"));
            send(insets);
            invalidateResumeVisual();
        } catch (JSONException ignored) {}
    }

    private JSONObject putInsets(JSONObject json) throws JSONException {
        float density = getResources().getDisplayMetrics().density;
        if (!(density > 0) || Float.isInfinite(density)) density = 1;
        sentTopInset = topInset / (double) density;
        sentBottomInset = bottomInset / (double) density;
        return json.put("top", sentTopInset).put("bottom", sentBottomInset);
    }

    public void setQuery(String value) {
        requireMain();
        int end = value == null ? 0 : Math.min(value.length(), MAX_QUERY);
        if (value != null && end < value.length() && end > 0
                && Character.isHighSurrogate(value.charAt(end - 1)) && Character.isLowSurrogate(value.charAt(end))) end--;
        String next = value == null ? "" : value.substring(0, end);
        if (query.equals(next)) return;
        query = next;
        queryRevision = (queryRevision + 1) & Integer.MAX_VALUE;
        try { if (ready) send(command("query").put("query", query).put("revision", queryRevision)); } catch (JSONException ignored) {}
    }
    public void findNext(boolean forward) {
        requireMain();
        try { if (ready) send(command("find").put("forward", forward).put("revision", queryRevision)); } catch (JSONException ignored) {}
    }
    public void setColors(int background, int foreground, int accent) {
        requireMain();
        boolean changed = this.background != background || this.foreground != foreground || this.accent != accent;
        this.background = background;
        this.foreground = foreground;
        this.accent = accent;
        if (!revealing) {
            setBackgroundColor(background);
            if (webView != null) webView.setBackgroundColor(background);
        }
        if (changed) {
            colorsDirty = true;
            invalidateResumeVisual();
            queueColors();
        }
    }
    public void setReadingColors(int paper, int ink, boolean dark) {
        setReadingColors(paper, ink, dark, true);
    }
    public void setReadingColors(int paper, int ink, boolean dark, boolean preserveImages) {
        requireMain();
        boolean changed = readingColors.update(paper, ink, dark);
        if (preserveReadingImages != preserveImages) {
            preserveReadingImages = preserveImages;
            changed = true;
        }
        if (changed) {
            colorsDirty = true;
            invalidateResumeVisual();
            queueColors();
        }
    }
    private boolean canCopy(WebView owner, Session target) {
        return allowCopy && owner == webView && active(target);
    }

    private ActionMode.Callback copyActionModeCallback(WebView owner, Session target, ActionMode.Callback callback) {
        return new ActionMode.Callback2() {
            @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                if (!canCopy(owner, target) || !callback.onCreateActionMode(mode, menu)) return false;
                if (!canCopy(owner, target)) return false;
                copyActionMode = mode;
                return true;
            }
            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return canCopy(owner, target) && callback.onPrepareActionMode(mode, menu);
            }
            @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (!canCopy(owner, target)) {
                    mode.finish();
                    return true;
                }
                return callback.onActionItemClicked(mode, item);
            }
            @Override public void onDestroyActionMode(ActionMode mode) {
                if (copyActionMode == mode) copyActionMode = null;
                callback.onDestroyActionMode(mode);
            }
            @Override public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
                if (callback instanceof ActionMode.Callback2) {
                    ((ActionMode.Callback2) callback).onGetContentRect(mode, view, outRect);
                } else {
                    super.onGetContentRect(mode, view, outRect);
                }
            }
        };
    }

    private void finishCopyActionMode() {
        ActionMode mode = copyActionMode;
        copyActionMode = null;
        if (mode != null) mode.finish();
    }

    public void setAllowCopy(boolean value) {
        requireMain();
        allowCopy = value;
        if (webView != null) webView.setLongClickable(value);
        if (!value) finishCopyActionMode();
        try { send(command("protection").put("allowCopy", value)); } catch (JSONException ignored) {}
    }
    public void stopScrolling() {
        requireMain();
        cancelNativeZoom();
        WebView view = webView;
        if (view == null) return;
        long now = SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        try {
            dispatchTouchEvent(cancel);
            view.onTouchEvent(cancel);
            view.flingScroll(0, 0);
            view.evaluateJavascript("window.OfficeViewer && window.OfficeViewer.stopScrolling && window.OfficeViewer.stopScrolling();", null);
        } catch (RuntimeException | LinkageError e) {
            fail(session, ERROR_WEBVIEW);
        } finally {
            cancel.recycle();
        }
    }

    public void pause() {
        requireMain();
        if (paused) return;
        cancelNativeZoom();
        paused = true;
        cancelResumeVisual();
        ++glassEpoch;
        glassPending = false;
        pendingGlassFrame = null;
        ++revealGeneration;
        revealing = false;
        paletteReplays = 0;
        ++colorGeneration;
        colorsDirty |= colorInFlight;
        colorInFlight = false;
        removeCallbacks(flushColors);
        colorScheduled = false;
        if (webView != null) {
            webView.animate().cancel();
            interruptedReveal = readyDelivered && webView.getAlpha() < 1f;
            if (!readyDelivered) webView.setAlpha(0f);
        }
        if (!readyDelivered && timeout != null) {
            remainingOpeningTime = Math.max(1, openingDeadline - SystemClock.uptimeMillis());
            handler.removeCallbacks(timeout);
        }
        send(command("pause"));
        try { if (webView != null) webView.onPause(); } catch (RuntimeException | LinkageError e) { fail(session, ERROR_WEBVIEW); }
    }
    public void resume() {
        requireMain();
        if (!paused) return;
        paused = false;
        try { if (webView != null) webView.onResume(); } catch (RuntimeException | LinkageError e) { fail(session, ERROR_WEBVIEW); }
        try { send(command("resume").put("motionEnabled", motionEnabled())); } catch (JSONException ignored) {}
        if (!readyDelivered && timeout != null && session != null) {
            openingDeadline = SystemClock.uptimeMillis() + remainingOpeningTime;
            handler.postAtTime(timeout, session, openingDeadline);
        }
        sendInsets();
        queueColors();
        reveal(session);
        Runnable startup = pendingStartup;
        pendingStartup = null;
        if (startup != null) startup.run();
        beginResumeVisual();
    }

    private boolean active(Session target) { return target != null && session == target && target.generation == generation; }
    private void post(Session target, Runnable runnable) {
        synchronized (target) {
            if (!target.cancelled) handler.postAtTime(() -> { if (active(target)) runnable.run(); }, target, SystemClock.uptimeMillis());
        }
    }
    private void fail(Session target, int code) {
        if (!active(target)) return;
        close();
        if (listener != null) listener.onFailure(code);
    }

    private void requireNativePdf(Session target) {
        if (!active(target) || !"pdf".equals(target.format)) return;
        close();
        if (listener != null) listener.onNativePdfRequired();
    }

    public void close() {
        requireMain();
        finishCopyActionMode();
        clearNativeZoomGateTimeout();
        nativeZoomGateWaiting = nativeZoomGateHeld = false;
        cancelNativeZoom();
        cancelResumeVisual();
        interruptedReveal = false;
        releaseContentRenderNode();
        ++glassEpoch;
        glassPending = false;
        pendingGlassFrame = null;
        positionLabel = null;
        ++generation;
        ++revealGeneration;
        ++colorGeneration;
        Session old = session;
        session = null;
        if (old != null) {
            WebViewStartup.cancel(old);
            synchronized (old) {
                old.cancelled = true;
                old.password = null;
                old.document = null;
                old.assets = Collections.emptySet();
                handler.removeCallbacksAndMessages(old);
            }
        }
        if (loading != null) loading.cancel(true);
        loading = null;
        if (worker != null) worker.shutdownNow();
        worker = null;
        timeout = null;
        pendingStartup = null;
        preparingSession = null;
        webViewPrepared = false;
        removeCallbacks(flushColors);
        colorScheduled = colorInFlight = colorsDirty = revealing = readyDelivered = false;
        WebView owned = webView;
        webView = null;
        if (owned != null) {
            owned.animate().cancel();
            removeView(owned);
            try { WebViewCompat.removeWebMessageListener(owned, "OfficeHost"); } catch (RuntimeException | LinkageError ignored) {}
            try { owned.stopLoading(); } catch (RuntimeException | LinkageError ignored) {}
            try { owned.destroy(); } catch (RuntimeException | LinkageError ignored) {}
        }
        ready = bridgeReady = false;
        paletteReplays = 0;
        sentBottomInset = -1;
        sentTopInset = -1;
        count = currentIndex = 0;
        names = new String[0];
        scale = 1;
        nativeFitScale = 0;
        query = "";
    }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Main thread required");
    }

    private static boolean motionEnabled() {
        return SharedConfig.animationsEnabled() && (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled());
    }
}
