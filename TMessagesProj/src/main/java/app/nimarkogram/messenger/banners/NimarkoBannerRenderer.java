 
package app.nimarkogram.messenger.banners;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.VideoPlayer;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NimarkoBannerRenderer {

    private static final double AV_HIDE_DUR = 1.0;   
                                                     
    private static final double FADE_DUR = 0.85;
    private static final float BASE_VOL = 0.05f;
    private static final int BLUR_DS = 8;
    private static final int BLUR_SR = 10;
    private static final double BLUR_INT = 4.0;
    private static final int FREEZE_FADE = 1000; 
    private static final int VID_FADE = 1000;
    private static final double BANNER_BLEED = 1.02; 
    private static final int VID_HEIGHT_THRESHOLD = 8;
    private static final int BMP_MAX = 2048;
    
    private static final double COLL_SETTLE = 0.55;
    private static final double FX_MIN_INTERVAL = 0.012;
    private static final double VID_ATTACH_INT = 0.15;
    private static final float AUDIO_VOL_UPDATE_THRESHOLD = 0.003f;
    
    private static final float AUDIO_MUTE_EPSILON = 0.02f;
    private static final long MIN_VID = 10_000L;
    private static final double FAIL_CD_S = 30.0; 

    public static volatile boolean suppressActionsColor = false;

    public static final boolean DBG = false;  
    private String lastDbgKey = null;
    static void dbg(String s) { if (DBG) android.util.Log.d("NimarkoBanner", s); }

    public static final boolean DBG_AV = false;
    private String lastAvKey = null;
    static void av(String s) { if (DBG_AV) android.util.Log.d("NMAV", s); }
    private void avChanged(String key, String s) {
        if (!DBG_AV) return;
        if (!key.equals(lastAvKey)) { lastAvKey = key; android.util.Log.d("NMAV", s); }
    }

    public static volatile boolean suppressGifts = false;

    private static volatile NimarkoBannerRenderer instance;

    public static NimarkoBannerRenderer getInstance() {
        NimarkoBannerRenderer local = instance;
        if (local == null) {
            synchronized (NimarkoBannerRenderer.class) {
                local = instance;
                if (local == null) { local = new NimarkoBannerRenderer(); instance = local; }
            }
        }
        return local;
    }

    public static NimarkoBannerRenderer peek() { return instance; }

    public static final class FrameDecision {
        public boolean suppressBackground;
    }

    private final NimarkoBannerController ctrl = NimarkoBannerController.getInstance();
    private final FrameDecision decision = new FrameDecision();

    private volatile boolean inCall;
    private boolean callObserverRegistered;
    private org.telegram.messenger.NotificationCenter.NotificationCenterDelegate callObserver;
    private float lastAudioExtra;

    private void ensureCallObserver() {
        if (callObserverRegistered) return;
        callObserverRegistered = true;
        try { inCall = org.telegram.messenger.voip.VoIPService.getSharedInstance() != null; } catch (Throwable ignored) {}
        AndroidUtilities.runOnUIThread(() -> {
            callObserver = (id, account, args) -> {
                
                boolean active = id != org.telegram.messenger.NotificationCenter.didEndCall;
                inCall = active;
                if (active) {
                    VideoPlayer p = videoPlayer;
                    if (p != null) { try { p.setVolume(0f); lastVol = 0f; } catch (Throwable ignored) {} }
                } else {
                    applyAudioVolume(lastAudioExtra); 
                }
            };
            org.telegram.messenger.NotificationCenter nc = org.telegram.messenger.NotificationCenter.getGlobalInstance();
            nc.addObserver(callObserver, org.telegram.messenger.NotificationCenter.didStartedCall);
            nc.addObserver(callObserver, org.telegram.messenger.NotificationCenter.voipServiceCreated);
            nc.addObserver(callObserver, org.telegram.messenger.NotificationCenter.didEndCall);
        });
    }

    private final ExecutorService executor = Executors.newFixedThreadPool(3, new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "nimarko-banner-fx");
            t.setDaemon(true);
            return t;
        }
    });

    private View avatarImage, avatarContainer, avatarsViewPager, storyView, giftsView, avatarGooey;
    private long avViewsKey;
    private float avLastAlpha = -1f, avLastGifts = -1f;
    private int avSvVis = -1;
    
    private float lastFadeA = -1f, lastFadeGifts = -1f;

    private final Map<Long, Float> avAlpha = new HashMap<>();
    private final Map<Long, Double> avTimes = new HashMap<>();
    private final Map<Long, Float> avBase = new HashMap<>();
    private final Set<Long> avAnim = new HashSet<>();
    
    private final Set<Long> avShow = new HashSet<>();

    private ViewGroup currentTopView;
    private long viewedProfileId;
    
    private volatile boolean isProfileOpen, appPaused, overlayOpen, videoPausedByTab;
    private static final Object LEGACY_OVERLAY_OWNER = new Object();
    private final Object overlayOwnersLock = new Object();
    private final Set<Object> overlayOwners = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean openAnimDone, showingPh;
    
    private volatile boolean collapseSettling;
    
    private volatile boolean pagerOwnedByProfile;
    
    private volatile double animDoneTime;
    private double frameTime;
    
    private double firstCommitTime;
    private float headerExtraHint;
    private float frameLastExtra = -999f;
    private double setupVideoAfter;
    private String curBf;
    private boolean curIv, curLoading;
    private boolean suppressBg;

    private Paint pBmp, pBlur, pDark, pGrad;
    private Matrix matrix;
    
    private int matKeyW = -1, matKeyY1 = -1, matKeyBw = -1, matKeyBh = -1;
    private LinearGradient grad;
    private int gradKeyY1q = -1;
    private float maxEh;
    
    private final java.util.HashMap<Long, String> photoFadeKey = new java.util.HashMap<>();
    private final java.util.HashMap<Long, Double> photoFadeStart = new java.util.HashMap<>();
    
    private final java.util.HashMap<Long, String> frameBfByEid = new java.util.HashMap<>();
    private final java.util.HashMap<Long, Boolean> frameIvByEid = new java.util.HashMap<>();
    private Bitmap xfadeBmp;
    private double xfadeStart;
    private Matrix xfadeMatrix;
    private String xfadeMatKey;
    private final BitmapLru bitmaps = new BitmapLru(32);
    private final BitmapLru blurBmps = new BitmapLru(16);
    private final Set<String> preloading = ConcurrentSet();
    private final Set<String> blurReq = ConcurrentSet();
    
    private final Set<Long> avLoading = ConcurrentSet();
    
    private static final int AV_BMP_MAX = 6;
    private final LinkedHashMap<Long, Bitmap> avBmpByEid =
            new LinkedHashMap<Long, Bitmap>(AV_BMP_MAX, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Bitmap> eldest) {
                    if (size() > AV_BMP_MAX) {
                        
                        recycle(eldest.getValue());
                        return true;
                    }
                    return false;
                }
            };

    private volatile VideoPlayer videoPlayer;
    private volatile TextureView videoTexture;
    
    private volatile long videoSessionId;
    
    private long videoHierarchyGeneration;
    private ImageView vidFreeze, vidBlur;
    private View vidContrast, vidDark;
    private volatile Bitmap freezeBmp;
    private volatile Bitmap vidBlurBmp;
    private volatile String frozenPath, curVidPath;
    private String videoPreparing;
    private volatile boolean vidReady, curVidSound, waitFrame;
    private volatile int vidW, vidH;
    private int maxVh, lastLh, lastDa = -1;
    private float lastBa = -1f, lastVol = -1f;
    
    private boolean profileExitActive;
    private long profileExitEid;
    private float profileExitTextureAlpha;
    private float profileExitFreezeAlpha;
    private float profileExitBlurAlpha;
    private float profileExitContrastAlpha;
    private float profileExitDarkAlpha;
    
    private float profileExitAvatarAlpha = 1f;
    private float profileExitAvatarProgress = 1f;
    private long vidTexAttachedTvId;
    private double lastVidAttach;
    
    private volatile int resumeWatchGen;
    private Matrix vidMatrix;
    private final Map<String, Double> failVids = new java.util.concurrent.ConcurrentHashMap<>();
    private int videoViewH;
    
    private boolean reparentCover;
    
    private android.graphics.Bitmap reopenFreeze;
    private String reopenFreezePath;
    
    private double vidFirstFrameTime;
    
    private volatile boolean freshAttachPending;

    private final View[] fxViews = new View[5];
    private double lastFxTime, lastFxExtra = -1, lastFxExpand = -1;
    private double blurFadeStart;
    private volatile Thread blurThread;
    private volatile AtomicBoolean blurStop;
    
    private volatile int blurGen;

    private NimarkoBannerRenderer() {}

    private static <T> Set<T> ConcurrentSet() { return java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>()); }

    private static double t() { return System.nanoTime() / 1e9; }
    private static float clamp01(double v) { return (float) Math.max(0.0, Math.min(1.0, v)); }
    private static boolean okBmp(Bitmap b) { try { return b != null && !b.isRecycled(); } catch (Throwable e) { return false; } }
    private static void recycle(Bitmap b) {
        if (b == null) return;
        synchronized (b) {
            if (okBmp(b)) {
                try { b.recycle(); } catch (Throwable ignored) {}
            }
        }
    }
    private static boolean isVideoPath(String p) { return p != null && p.toLowerCase().endsWith(".mp4"); }

    private boolean isCurrentVideoSession(long sessionId, VideoPlayer player, String path) {
        return sessionId == videoSessionId
                && player != null
                && player == videoPlayer
                && pathEq(path, curVidPath);
    }

    public void invalidateTopView() {
        ViewGroup tv = currentTopView;
        if (tv != null) AndroidUtilities.runOnUIThread(tv::postInvalidateOnAnimation);
    }

    private void postInv() {
        ViewGroup tv = currentTopView;
        if (tv != null) {
            try { tv.postInvalidateOnAnimation(); } catch (Throwable e) { try { tv.invalidate(); } catch (Throwable ignored) {} }
        }
    }

    public void setAvatarViews(View avatarImage, View avatarContainer, View avatarsViewPager,
                               View storyView, View giftsView, View avatarGooey) {
        this.avatarImage = avatarImage;
        this.avatarContainer = avatarContainer;
        this.avatarsViewPager = avatarsViewPager;
        this.storyView = storyView;
        this.giftsView = giftsView;
        this.avatarGooey = avatarGooey;
        avLastAlpha = -1f; avLastGifts = -1f; avSvVis = -1;
    }

    public void setCollapseSettling(boolean settling) {
        if (collapseSettling == settling) return;
        collapseSettling = settling;
        if (!settling) {
            
            try {
                long eid = viewedProfileId;
                if (eid != 0 && avAlpha.containsKey(eid) && ctrl.shouldHideAvatar(eid)) {
                    float ca = getOr(avAlpha, eid, 1f);
                    if (ca > 0.01f && ca < 0.999f) {
                        avAnim.add(eid);
                        anchorFadeStart(eid, ca, t());
                    }
                }
            } catch (Throwable ignored) {}
        }
        postInv();
    }

    public void setPagerOwnedByProfile(boolean owned) {
        pagerOwnedByProfile = owned;
    }

    private void clearSettleState() {
        setPagerOwnedByProfile(false);
        setCollapseSettling(false);
    }

    public void onProfileResumed(ViewGroup topView, long dialogId) {
        
        ViewGroup prevTopView = currentTopView;
        boolean samePeer = viewedProfileId != 0 && viewedProfileId == dialogId;
        boolean topViewChanged = samePeer && prevTopView != topView;
        currentTopView = topView;
        if (viewedProfileId != 0 && viewedProfileId != dialogId) resetState();
        viewedProfileId = dialogId;
        isProfileOpen = true;
        openAnimDone = false;
        if (appPaused && !ApplicationLoader.mainInterfacePaused) {
            appPaused = false;
        }
        
        ctrl.ensureStarted();
        
        boolean texOnThisTv = videoTexture != null
                && vidTexAttachedTvId == System.identityHashCode(topView);
        av("RESUME eid=" + dialogId + " samePeer=" + samePeer + " topViewChanged=" + topViewChanged
                + " texOnThisTv=" + texOnThisTv + " curIv=" + curIv + " vp=" + (videoPlayer != null)
                + " vt=" + (videoTexture != null) + " vidReady=" + vidReady + " firstFrame=" + (vidFirstFrameTime > 0)
                + " willResume=" + (!topViewChanged || texOnThisTv));
        if (!topViewChanged || texOnThisTv) {
            resumePlayerIfReady();
        } else {
        }
        
        if (curIv && videoPlayer == null && videoTexture == null) {
            av("RESUME DEAD-recovery eid=" + dialogId + " — player gone, re-arming firstCommit/setupVideoAfter");
            firstCommitTime = 0;            
            setupVideoAfter = 0;            
        }
        
        for (long d : new long[]{150, 500, 1200, 2200}) {
            AndroidUtilities.runOnUIThread(this::postInv, d);
        }
        postInv();
    }

    public void onProfilePaused() {
        onProfilePaused(null);
    }

    public void onProfilePaused(ViewGroup topView) {
        
        if (topView != null && currentTopView != null && topView != currentTopView) {
            return;
        }
        isProfileOpen = false;
        openAnimDone = false;
        
        setupVideoAfter = 0;
        
        clearSettleState();
        stopBlur();
        if (videoPlayer != null && vidReady) {
            boolean cap = false;
            try { cap = captureFreezeFrame(); } catch (Throwable ignored) {}
        }
        if (videoPlayer != null) { try { videoPlayer.pause(); } catch (Throwable ignored) {} }
    }

    public void onProfileDestroyed(long dialogId) {
        onProfileDestroyed(null, dialogId);
    }

    public void onProfileDestroyed(ViewGroup topView, long dialogId) {
        if (dialogId != 0 && viewedProfileId != 0 && dialogId != viewedProfileId) {
            
            frameBfByEid.remove(dialogId);
            frameIvByEid.remove(dialogId);
            return;
        }
        
        // dialogId-only guard above does NOT return and B's destroy would fall through
        
        if (topView != null && currentTopView != null && topView != currentTopView) {
            return;
        }
        
        if (dialogId != 0) {
            frameBfByEid.remove(dialogId);
            frameIvByEid.remove(dialogId);
        }
        resetState();
        
        avatarImage = null; avatarContainer = null; avatarsViewPager = null;
        storyView = null; avatarGooey = null; giftsView = null; currentTopView = null;
    }

    public void onOverlayOpen() {
        onOverlayOpen(LEGACY_OVERLAY_OWNER);
    }

    public void onOverlayOpen(Object owner) {
        Object key = owner != null ? owner : LEGACY_OVERLAY_OWNER;
        synchronized (overlayOwnersLock) {
            if (!overlayOwners.add(key)) {
                return;
            }
            overlayOpen = true;
        }
        stopBlur();
        VideoPlayer player = videoPlayer;
        if (player != null) {
            try {
                if (vidReady) {
                    captureFreezeFrame();
                }
                player.pause();
            } catch (Throwable ignored) {}
        }
    }

    public void onOverlayClose() {
        onOverlayClose(LEGACY_OVERLAY_OWNER);
    }

    public void onOverlayClose(Object owner) {
        Object key = owner != null ? owner : LEGACY_OVERLAY_OWNER;
        synchronized (overlayOwnersLock) {
            if (!overlayOwners.remove(key)) {
                return;
            }
            overlayOpen = !overlayOwners.isEmpty();
            if (overlayOpen) {
                return;
            }
        }
        boolean playGate = videoPlayer != null && vidReady && isProfileOpen && !appPaused && !videoPausedByTab;
        if (playGate) {
            resumePlayerIfReady();
        }
    }

    public void onTabVisibilityChanged(float visibility) {
        if (visibility < 0.01f) {
            if (videoPlayer != null && vidReady && !videoPausedByTab) {
                videoPausedByTab = true;
                try { videoPlayer.pause(); } catch (Throwable ignored) {}
                stopBlur();
            }
        } else {
            if (videoPausedByTab) {
                videoPausedByTab = false;
                
                boolean showGate = isProfileOpen && videoPlayer != null && vidReady && !appPaused && !overlayOpen;
                if (showGate) {
                    try { videoPlayer.play(); } catch (Throwable ignored) {}
                    startBlur();
                }
                invalidateTopView();
            }
        }
    }

    public void onAppPause() {
        appPaused = true;
        stopBlur();
        if (videoPlayer != null) { try { videoPlayer.pause(); } catch (Throwable ignored) {} }
    }

    public void onAppResume() {
        appPaused = false;
        final long sessionId = videoSessionId;
        final VideoPlayer player = videoPlayer;
        final String path = curVidPath;
        if (player != null && vidReady) {
            AndroidUtilities.runOnUIThread(() -> {
                if (isCurrentVideoSession(sessionId, player, path)) {
                    resumePlayerIfReady();
                }
            });
        }
    }

    private void resumePlayerIfReady() {
        final long sessionId = videoSessionId;
        final VideoPlayer player = videoPlayer;
        final String path = curVidPath;
        if (!isCurrentVideoSession(sessionId, player, path) || !vidReady) {
            return;
        }
        if (appPaused || videoPausedByTab || overlayOpen || !isProfileOpen) {
            return;
        }
        
        boolean skipReattach = freshAttachPending && waitFrame && vidFirstFrameTime == 0;
        if (videoTexture != null && !skipReattach) {
            try { player.setTextureView(null); } catch (Throwable ignored) {}
            try { player.setTextureView(videoTexture); } catch (Throwable ignored) {}
        } else if (skipReattach) {
        } else {
        }
        
        vidFirstFrameTime = 0;
        armResumeCrossfade();
        try { player.play(); } catch (Throwable ignored) {}
        startBlur();
        invalidateTopView();
        
        final int gen = ++resumeWatchGen;
        
        AndroidUtilities.runOnUIThread(
                () -> resumeFrameWatchdog(gen, false, sessionId, player, path),
                300);
    }

    private void resumeFrameWatchdog(int gen, boolean second, long sessionId,
                                     VideoPlayer player, String path) {
        try {
            if (gen != resumeWatchGen) { return; }
            if (!isCurrentVideoSession(sessionId, player, path)) { return; }
            if (!waitFrame) { return; }
            if (!vidReady) { return; }
            if (appPaused || videoPausedByTab || overlayOpen || !isProfileOpen) { return; } 
            if (!second) {
                
                if (freshAttachPending) {
                    
                    AndroidUtilities.runOnUIThread(
                            () -> resumeFrameWatchdog(gen, true, sessionId, player, path),
                            350);
                    return;
                }
                
                if (videoTexture != null) {
                    try { player.setTextureView(null); } catch (Throwable ignored) {}
                    try { player.setTextureView(videoTexture); } catch (Throwable ignored) {}
                }
                try { player.play(); } catch (Throwable ignored) {}
                AndroidUtilities.runOnUIThread(
                        () -> resumeFrameWatchdog(gen, true, sessionId, player, path),
                        350);
                return;
            }
            
            dbg("VID resume watchdog — no frame after re-attach, forcing dismissFreeze");
            dismissFreeze();
        } catch (Throwable ignored) {}
    }

    private void resetState() {
        isProfileOpen = false; openAnimDone = false; animDoneTime = 0; frameTime = 0;
        firstCommitTime = 0;
        frameLastExtra = -999f; setupVideoAfter = 0; videoPausedByTab = false;
        videoHierarchyGeneration++;
        destroyVideo();
        
        avAlpha.clear(); avTimes.clear(); avBase.clear(); avAnim.clear(); avShow.clear();
        clearSettleState(); 
        maxEh = 0; matKeyW = -1; matKeyY1 = -1; matKeyBw = -1; matKeyBh = -1; grad = null; gradKeyY1q = -1;
        lastDa = -1; lastBa = -1f; lastLh = 0; lastVol = -1f;
        viewedProfileId = 0; maxVh = 0;
        
        curBf = null; curIv = false; curLoading = false;
        avViewsKey = 0; avLastAlpha = -1f; avLastGifts = -1f; avSvVis = -1;
        lastFadeA = -1f; lastFadeGifts = -1f; vidFirstFrameTime = 0;
        profileExitActive = false; profileExitEid = 0;
        profileExitAvatarAlpha = 1f; profileExitAvatarProgress = 1f;
        lastFxExtra = -1; lastFxExpand = -1;
        clearXfade();
        clearBmpsKeepLru();
        
        suppressActionsColor = false; suppressGifts = false;
    }

    private void clearBmpsKeepLru() {
        maxEh = 0; matKeyW = -1; matKeyY1 = -1; matKeyBw = -1; matKeyBh = -1; grad = null; gradKeyY1q = -1;
    }

    private void clearBmps() {
        bitmaps.clearAll(); blurBmps.clearAll();
        maxEh = 0; matKeyW = -1; matKeyY1 = -1; matKeyBw = -1; matKeyBh = -1; grad = null; gradKeyY1q = -1;
    }

    private void markFirstCommit(double now) {
        if (firstCommitTime == 0) {
            firstCommitTime = now;
            
            if (headerExtraHint > maxEh) maxEh = headerExtraHint;
        }
    }

    private float collapseEnvelope(float rawColl) {
        double fc = firstCommitTime;
        if (fc <= 0) return rawColl;
        double el = (frameTime != 0 ? frameTime : t()) - fc;
        if (el >= COLL_SETTLE || el < 0) return rawColl;
        
        float p = (float) (el / COLL_SETTLE);
        float w = p * p * (3f - 2f * p);     
        
        return rawColl * w;
    }

    public FrameDecision prepareFrame(ViewGroup topView, long eid, float extra, int w, int y1Hint,
                                      boolean openAnim, boolean transAnim, boolean searchMode,
                                      float expand, int playProfileAnimation, boolean hasMainTabs,
                                      boolean profileClosing,
                                      int headerExtra) {
        try {
            
            if (headerExtra > 0) headerExtraHint = headerExtra;
            int h = topView.getMeasuredHeight();
            if (w <= 0 || h <= 0) { decision.suppressBackground = suppressBg; return decision; }

            boolean committed = (viewedProfileId == 0) || (viewedProfileId == eid);
            if (!committed) {
                return prepareFrameNonCommitted(eid);
            }

            double now = t();
            frameTime = now;

            markFirstCommit(now);

            if (extra == frameLastExtra && avAnim.isEmpty() && blurFadeStart == 0.0
                    && videoPlayer != null && videoTexture != null
                    && curVidPath != null && curVidPath.equals(curBf) && curBf != null
                    && !showingPh && !curLoading
                    && openAnimDone && !(openAnim || transAnim)) {
                
                boolean qpHide = ctrl.shouldHideAvatar(eid);
                suppressGifts = qpHide && (getOr(avAlpha, eid, 1f) <= 0.05f);
                suppressActionsColor = true;
                decision.suppressBackground = suppressBg;
                return decision;
            }
            frameLastExtra = extra;
            
            boolean animNow = (openAnim || transAnim) && !profileClosing;

            if (hasMainTabs) {
                if (!openAnimDone) { openAnimDone = true; animDoneTime = now; }
            } else {
                if (animNow) {
                    if (openAnimDone) {
                        
                        boolean freshOpen = firstCommitTime > 0 && (now - firstCommitTime) < COLL_SETTLE;
                        if (openAnim && !freshOpen) {
                            if (videoPlayer != null || videoTexture != null) scheduleDestroyVideo();
                            decision.suppressBackground = suppressBg;
                            return decision;
                        }
                        if (!openAnim && videoTexture != null && videoPlayer != null && vidReady
                                && (!okBmp(freezeBmp) || !pathEq(frozenPath, curVidPath))) {
                            try { captureFreezeFrame(); } catch (Throwable ignored) {}
                        }
                        if (!freshOpen) {
                            decision.suppressBackground = suppressBg;
                            return decision;
                        }
                        // freshOpen: fall through so resolve()+setupVideo run this
                        
                    }
                } else {
                    if (!openAnimDone) { openAnimDone = true; animDoneTime = now; }
                }
            }

            if (searchMode) {
                if (videoPlayer != null || videoTexture != null) scheduleDestroyVideo();
                suppressBg = false;
                decision.suppressBackground = false;
                suppressActionsColor = false;
                suppressGifts = false;
                return decision;
            }

            if (eid == 0) { decision.suppressBackground = suppressBg; return decision; }

            ctrl.maybeKickLoad(eid);

            NimarkoBannerController.Resolved r = ctrl.resolve(eid);
            String bf = r.path;
            boolean iv = r.isVideo;
            
            frameBfByEid.put(eid, bf);
            frameIvByEid.put(eid, iv);
            
            curBf = bf; curIv = iv; curLoading = r.loading;

            if (DBG) {
                String k = eid + "|" + bf + "|" + iv + "|" + r.loading + "|" + animNow + "|" + hasMainTabs
                        + "|vp=" + (videoPlayer != null) + "|vt=" + (videoTexture != null) + "|rdy=" + vidReady;
                if (!k.equals(lastDbgKey)) {
                    lastDbgKey = k;
                    dbg("RESOLVE eid=" + eid + " bf=" + bf + " isVideo=" + iv + " loading=" + r.loading
                            + " animNow=" + animNow + " hasMainTabs=" + hasMainTabs
                            + " videoPlayer=" + (videoPlayer != null) + " videoTexture=" + (videoTexture != null)
                            + " vidReady=" + vidReady + " firstFrame=" + vidFirstFrameTime);
                }
            }

            boolean wantHide = ctrl.shouldHideAvatar(eid);
            
            boolean hide;
            float avA0 = getOr(avAlpha, eid, 1f);
            boolean hasFreeze = okBmp(freezeBmp);
            boolean coldReveal = !hasFreeze && avA0 > 0.05f;
            if (wantHide && iv && coldReveal) {
                hide = vidReady && vidFirstFrameTime > 0;
            } else {
                hide = wantHide;
            }
            
            avChanged(eid + "|" + wantHide + "|" + iv + "|" + coldReveal + "|" + hasFreeze
                            + "|" + vidReady + "|" + (vidFirstFrameTime > 0) + "|" + hide + "|" + Math.round(avA0 * 100),
                    "HIDE eid=" + eid + " wantHide=" + wantHide + " iv=" + iv
                            + " -> hide=" + hide + " | coldReveal=" + coldReveal + " hasFreeze=" + hasFreeze
                            + " vidReady=" + vidReady + " firstFrame=" + (vidFirstFrameTime > 0)
                            + " avAlpha=" + String.format("%.2f", avA0)
                            + " suppressBg=" + suppressBg + " vp=" + (videoPlayer != null)
                            + " vt=" + (videoTexture != null) + " curBf=" + (curBf == null ? "null" : "set"));
            updateAvFade(eid, hide, now, openAnim, playProfileAnimation, expand);
            suppressGifts = hide && (getOr(avAlpha, eid, 1f) <= 0.05f);
            
            if (!avAnim.isEmpty()) postInv();

            showingPh = false;

            boolean avatarFallback = bf == null && NimarkoBannerConfig.useAvatar && ctrl.hasNoRealBanner(eid);
            if (avatarFallback) {
                
                avatarBitmapFor(eid);
            }

            if (bf != null) {
                if (iv) {
                    
                    boolean freshOpen = firstCommitTime > 0 && (now - firstCommitTime) < COLL_SETTLE;
                    if (!animNow || hasMainTabs || freshOpen) {
                        if (setupVideoAfter == 0.0) setupVideoAfter = now + 0.1;
                        if (now < setupVideoAfter && videoPlayer == null && videoTexture == null) {
                            dbg("VID waiting setupVideoAfter (" + (setupVideoAfter - now) + "s left)");
                            try { topView.postInvalidateOnAnimation(); } catch (Throwable ignored) {}
                        } else {
                            if (pathEq(curVidPath, ctrl.placeholderPath()) && !bf.equals(ctrl.placeholderPath())) {
                                try { captureXfadeBmp(); } catch (Throwable ignored) {}
                            } else if (xfadeBmp != null) {
                                try { clearXfade(); } catch (Throwable ignored) {}
                            }
                            scheduleSetupVideo(topView, bf, w, y1Hint);
                        }
                    } else {
                        dbg("VID setup SKIPPED (open/transition anim in progress, no main tabs)");
                    }
                    
                    boolean texShown = vidReady && vidFirstFrameTime > 0
                            && (now - vidFirstFrameTime) >= (VID_FADE / 1000.0);
                    suppressBg = videoPlayer != null && videoTexture != null && texShown;
                } else {
                    if (videoPlayer != null || videoTexture != null) {
                        try { captureXfadeBmp(); } catch (Throwable ignored) {}
                        scheduleDestroyVideo();
                    }
                    Bitmap cached = bitmaps.get(bf);
                    if (!okBmp(cached)) preloadBmp(bf);
                    
                    double pfs = photoFadeStart.getOrDefault(eid, 0.0);
                    suppressBg = okBmp(cached) && pfs > 0 && ("f" + bf).equals(photoFadeKey.get(eid))
                            && (now - pfs) >= FADE_DUR;
                }
                
                suppressActionsColor = true;
            } else {
                
                boolean avatarShown = avatarFallback && okBmp(avBmpByEid.get(eid));
                double afs = photoFadeStart.getOrDefault(eid, 0.0);
                suppressBg = avatarShown && afs > 0 && ("a" + eid).equals(photoFadeKey.get(eid))
                        && (now - afs) >= FADE_DUR;
                suppressActionsColor = avatarShown;
                if (videoPlayer != null || videoTexture != null) {
                    if (pathEq(curVidPath, ctrl.placeholderPath())) {
                        try { captureXfadeBmp(); } catch (Throwable ignored) {}
                    }
                    scheduleDestroyVideo();
                }
                if (xfadeBmp != null && !pathEq(curVidPath, ctrl.placeholderPath())) {
                    try { clearXfade(); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            
        }
        decision.suppressBackground = suppressBg;
        return decision;
    }

    private FrameDecision prepareFrameNonCommitted(long eid) {
        boolean sb = false;
        try {
            NimarkoBannerController.Resolved r = ctrl.resolve(eid);
            String bf = r.path;
            boolean iv = r.isVideo;
            frameBfByEid.put(eid, bf);
            frameIvByEid.put(eid, iv);
            double now = t();
            if (bf != null && !iv) {
                
                Bitmap cached = bitmaps.get(bf);
                double pfs = photoFadeStart.getOrDefault(eid, 0.0);
                sb = okBmp(cached) && pfs > 0
                        && ("f" + bf).equals(photoFadeKey.get(eid))
                        && (now - pfs) >= FADE_DUR;
            } else if (bf == null && NimarkoBannerConfig.useAvatar && ctrl.hasNoRealBanner(eid)) {
                
                double afs = photoFadeStart.getOrDefault(eid, 0.0);
                sb = okBmp(avatarBitmapFor(eid)) && afs > 0
                        && ("a" + eid).equals(photoFadeKey.get(eid))
                        && (now - afs) >= FADE_DUR;
            }
            
        } catch (Throwable ignored) {}
        decision.suppressBackground = sb;
        return decision;
    }

    private static boolean pathEq(String a, String b) { return a != null && a.equals(b); }
    private static float getOr(Map<Long, Float> m, long k, float def) { Float v = m.get(k); return v == null ? def : v; }

    private void updateAvFade(long eid, boolean hide, double now,
                              boolean openAnim, int playProfileAnimation, float exp) {
        try {
            
            if (profileExitActive && profileExitEid == eid) return;

            if (!hide) {
                if (!avAlpha.containsKey(eid)) return; 
                float ca = getOr(avAlpha, eid, 1f);
                if (ca >= 0.999f) {                    
                    setAvAlpha(1f, 1f);
                    avAlpha.remove(eid); avTimes.remove(eid);
                    avAnim.remove(eid); avBase.remove(eid); avShow.remove(eid);
                    return;
                }
                if (!avShow.contains(eid)) {           
                    avShow.add(eid); avAnim.add(eid); avBase.remove(eid);
                    
                    anchorFadeStart(eid, 1f - ca, now);
                }
                
                double el = now - getOr(avTimes, eid, now);
                double p = Math.max(0.0, el / AV_HIDE_DUR);
                float fa = (float) Math.min(1.0, 1.0 - smoothFade(p));
                if (Math.abs(fa - ca) >= 0.003f || fa >= 0.999f) { setAvAlpha(fa, fa); avAlpha.put(eid, fa); }
                postInv();
                return;
            }

            if (openAnim && playProfileAnimation == 2) return;
            
            boolean wasShowing = avShow.remove(eid);
            float ca = getOr(avAlpha, eid, -1f);
            boolean hasBl = avBase.containsKey(eid);
            boolean isA = avAnim.contains(eid);
            if (ca < 0) {
                if (exp > 0.05f) {
                    float na = clamp01(exp);
                    avAlpha.put(eid, na);
                    setAvAlpha(na, 0f);
                    return;
                }
                if (!isA) {
                    avAnim.add(eid); avAlpha.put(eid, 1f); avTimes.put(eid, now);
                    setAvAlpha(1f, 1f); postInv();
                }
                return;
            }
            
            if (wasShowing) { avAnim.add(eid); anchorFadeStart(eid, ca, now); }
            
            boolean inExp = exp > 0.02f || (hasBl && exp > 0.001f);
            if (inExp) {
                if (isA) { avBase.put(eid, ca); avAnim.remove(eid); }
                else if (!hasBl) avBase.put(eid, ca);
                float bl = getOr(avBase, eid, 0f);
                float na = clamp01(bl + exp * (1f - bl));
                if (Math.abs(na - ca) >= 0.002f) { setAvAlpha(na, bl); avAlpha.put(eid, na); }
                return;
            }
            if (hasBl) {
                float bl = avBase.remove(eid);
                if (bl > 0.01f) {
                    
                    avAnim.add(eid); anchorFadeStart(eid, bl, now); postInv();
                    return;
                }
                avAlpha.put(eid, 0f); setAvAlpha(0f, 0f);
                return;
            }
            if (!isA && !wasShowing) {
                float na = clamp01(exp);
                if (Math.abs(na - ca) >= 0.003f) { setAvAlpha(na, 0f); avAlpha.put(eid, na); }
                return;
            }
            double el = now - getOr(avTimes, eid, now);
            if (el < AV_HIDE_DUR) {
                
                float fa = (float) smoothFade(el / AV_HIDE_DUR);
                if (Math.abs(fa - ca) >= 0.003f) { setAvAlpha(fa, fa); avAlpha.put(eid, fa); }
                postInv();
                return;
            }
            avAnim.remove(eid); avAlpha.put(eid, 0f); setAvAlpha(0f, 0f);
        } catch (Throwable ignored) {}
    }

    private static double getOr(Map<Long, Double> m, long k, double def) { Double v = m.get(k); return v == null ? def : v; }

    private static double smoothFade(double p) {
        if (p <= 0.0) return 1.0;
        if (p >= 1.0) return 0.0;
        return 1.0 - p * p * (3.0 - 2.0 * p);
    }

    private static double fadeProgressForAlpha(float alpha) {
        double a = Math.max(0.0, Math.min(1.0, alpha));
        double p = 0.5 - Math.sin(Math.asin(2.0 * a - 1.0) / 3.0);
        return Math.max(0.0, Math.min(1.0, p));
    }

    private void anchorFadeStart(long eid, float currentAlpha, double now) {
        double p = fadeProgressForAlpha(currentAlpha);
        avTimes.put(eid, now - (p * AV_HIDE_DUR));
    }

    private void setAvAlpha(float a, float giftsA) {
        
        float af = a <= 0.001f ? 0f : (a >= 0.999f ? 1f : clamp01(a));
        float gf = giftsA <= 0.001f ? 0f : (giftsA >= 0.999f ? 1f : clamp01(giftsA));
        lastFadeA = a; lastFadeGifts = giftsA;
        avLastAlpha = af;
        avLastGifts = gf;

        if (avatarContainer != null) {
            setViewAlphaIfNeeded(avatarContainer, af);
        } else {
            
            setViewAlphaIfNeeded(avatarImage, af);
        }
        if (storyView != null) {
            try {
                setViewAlphaIfNeeded(storyView, af);
                
                if (avSvVis != View.VISIBLE) {
                    storyView.setVisibility(View.VISIBLE);
                    avSvVis = View.VISIBLE;
                }
            } catch (Throwable ignored) {}
        }
        setViewAlphaIfNeeded(giftsView, gf);
    }

    private static void setViewAlphaIfNeeded(View view, float alpha) {
        if (view == null) return;
        try {
            if (Math.abs(view.getAlpha() - alpha) >= 0.001f) {
                view.setAlpha(alpha);
            }
        } catch (Throwable ignored) {}
    }

    public void reassertAvatarFade() {
        try {
            long eid = viewedProfileId;
            if (eid == 0 || lastFadeA < 0) return;
            if (profileExitActive && profileExitEid == eid) {
                applyProfileExitAvatarAlpha();
                return;
            }
            if (!ctrl.shouldHideAvatar(eid)) return;
            if (!avAlpha.containsKey(eid)) return;
            setAvAlpha(lastFadeA, lastFadeGifts);
        } catch (Throwable ignored) {}
    }

    public float getForegroundProgress(long eid) {
        try {
            if (eid == 0) return 0f;
            String bf = frameBfByEid.get(eid);
            boolean iv = Boolean.TRUE.equals(frameIvByEid.get(eid));
            boolean committed = viewedProfileId == 0 || viewedProfileId == eid;
            if (bf != null && iv) {
                return committed && pathEq(bf, curVidPath) ? videoVisualProgress() : 0f;
            }

            if (committed && okBmp(xfadeBmp)) {
                return 1f;
            }

            String key;
            Bitmap bmp;
            if (bf != null) {
                key = "f" + bf;
                bmp = bitmaps.get(bf);
            } else if (NimarkoBannerConfig.useAvatar && ctrl.hasNoRealBanner(eid)) {
                key = "a" + eid;
                bmp = avBmpByEid.get(eid);
            } else {
                return 0f;
            }
            if (!okBmp(bmp) || !key.equals(photoFadeKey.get(eid))) return 0f;
            double fs = photoFadeStart.getOrDefault(eid, 0.0);
            if (fs <= 0) return 0f;
            double progress = clamp01(((frameTime != 0 ? frameTime : t()) - fs) / FADE_DUR);
            progress = 1.0 - Math.pow(1.0 - progress, 2);
            if (avAnim.contains(eid) && ctrl.shouldHideAvatar(eid)) {
                progress = Math.max(progress, 1.0 - getOr(avAlpha, eid, 1f));
            }
            return clamp01(progress);
        } catch (Throwable ignored) {
            return 0f;
        }
    }

    private float videoVisualProgress() {
        float progress = 0f;
        try {
            if (videoTexture != null && videoTexture.getVisibility() == View.VISIBLE) {
                progress = Math.max(progress, videoTexture.getAlpha());
            }
            if (vidFreeze != null && vidFreeze.getVisibility() == View.VISIBLE
                    && vidFreeze.getDrawable() != null) {
                progress = Math.max(progress, vidFreeze.getAlpha());
            }
            if (vidFirstFrameTime > 0) {
                float p = clamp01(((frameTime != 0 ? frameTime : t()) - vidFirstFrameTime) / (VID_FADE / 1000.0));
                progress = Math.max(progress, 1f - (1f - p) * (1f - p));
            }
        } catch (Throwable ignored) {}
        return clamp01(progress);
    }

    public void drawImageBanner(Canvas canvas, int w, int y1, float extra, long callerEid) {
        try {
            if (videoTexture != null && videoPlayer != null) return; 
            if (canvas == null || w <= 0) return;
            
            long eid = callerEid;
            if (eid == 0) return;
            double now = frameTime != 0 ? frameTime : t();

            String bf = frameBfByEid.get(eid);
            Boolean ivBoxed = frameIvByEid.get(eid);
            boolean iv = ivBoxed != null && ivBoxed;

            if (bf != null && !iv) {
                String prevKey = photoFadeKey.get(eid);
                if (prevKey != null && prevKey.length() > 1 && prevKey.charAt(0) == 'f') {
                    String prevPath = prevKey.substring(1);
                    if (!prevPath.equals(bf) && !isVideoPath(prevPath)) {
                        armPhotoXfade(prevPath);
                    }
                }
            }

            Bitmap bmp = null;
            if (bf != null && !iv) {
                Bitmap cached = bitmaps.get(bf);
                if (okBmp(cached)) bmp = cached;
                else { drawXfadeOnly(canvas, w, y1); preloadBmp(bf); return; }
            }
            if (!okBmp(bmp) && NimarkoBannerConfig.useAvatar && ctrl.hasNoRealBanner(eid)) {
                Bitmap ab = avatarBitmapFor(eid);
                if (okBmp(ab)) bmp = ab;
            }
            if (!okBmp(bmp)) return;

            String dk = bf != null ? ("f" + bf) : ("a" + eid);
            String curKey = photoFadeKey.get(eid);
            if (!dk.equals(curKey)) { photoFadeKey.put(eid, dk); photoFadeStart.put(eid, now); }
            double fs = photoFadeStart.getOrDefault(eid, 0.0);
            int fa = 255;
            boolean needInv = false;
            if (fs > 0) {
                double pr = Math.min(1.0, (now - fs) / FADE_DUR);
                pr = 1.0 - Math.pow(1.0 - pr, 2);
                
                if (avAnim.contains(eid) && ctrl.shouldHideAvatar(eid)) {
                    double hideComplement = 1.0 - getOr(avAlpha, eid, 1f);
                    if (hideComplement > pr) { pr = hideComplement; needInv = true; }
                }
                fa = (int) (pr * 255);
                if (fa < 1) { postInv(); return; }
                if (pr < 1.0) needInv = true;
            }

            initPaints();
            int bw = bmp.getWidth(), bh = bmp.getHeight();
            if (bw <= 0 || bh <= 0) return;
            if (y1 <= 0) return;

            if (extra > maxEh) maxEh = extra;
            float me = Math.max(maxEh, 400f);
            float coll = collapseEnvelope(clamp01(1.0 - extra / me));
            
            if (firstCommitTime > 0 && (now - firstCommitTime) < COLL_SETTLE) needInv = true;

            if (matKeyW != w || matKeyY1 != y1 || matKeyBw != bw || matKeyBh != bh) {
                float sc = (float) (Math.max(w / (double) bw, y1 / (double) bh) * BANNER_BLEED);
                float dx = (w - bw * sc) * 0.5f, dy = (y1 - bh * sc) * 0.5f;
                matrix.reset(); matrix.postScale(sc, sc); matrix.postTranslate(dx, dy);
                matKeyW = w; matKeyY1 = y1; matKeyBw = bw; matKeyBh = bh;
            }

            boolean lite = NimarkoBannerConfig.liteMode;
            Bitmap bb = null;
            if (!lite) {
                String bk = System.identityHashCode(bmp) + ":" + bw + "x" + bh;
                bb = blurBmps.get(bk);
                if (bb == null && !blurReq.contains(bk)) {
                    blurReq.add(bk);
                    final Bitmap ref = bmp;
                    executor.submit(() -> {
                        Bitmap bl = null;
                        try {
                            synchronized (ref) {
                                if (okBmp(ref)) {
                                    bl = ref.copy(Bitmap.Config.ARGB_8888, true);
                                }
                            }
                            if (okBmp(bl)) {
                                Utilities.stackBlurBitmap(bl, 25);
                            }
                        } catch (Throwable ignored) {}
                        final Bitmap result = bl;
                        AndroidUtilities.runOnUIThread(() -> {
                            try {
                                if (okBmp(result)) {
                                    Bitmap previous = blurBmps.put(bk, result);
                                    if (previous != null && previous != result) {
                                        recycle(previous);
                                    }
                                    invalidateTopView();
                                } else {
                                    recycle(result);
                                }
                            } finally {
                                blurReq.remove(bk);
                            }
                        });
                    });
                }
            }

            int y1q = (y1 + 3) & ~3;
            
            if (grad == null || gradKeyY1q != y1q) {
                int gh = Math.max(1, (int) (y1q * 0.4));
                grad = new LinearGradient(0, y1q - gh, 0, y1q, Color.argb(0, 0, 0, 0), Color.argb(120, 0, 0, 0), Shader.TileMode.CLAMP);
                gradKeyY1q = y1q;
            }

            float ff = fa / 255f;
            int sid = canvas.save();
            try {
                canvas.clipRect(0, 0, w, y1);
                int xa = 0;
                if (okBmp(xfadeBmp) && xfadeStart > 0 && fa < 255) {
                    xa = 255 - fa;
                    int xbw = xfadeBmp.getWidth(), xbh = xfadeBmp.getHeight();
                    if (xbw > 0 && xbh > 0) {
                        String xmk = w + "x" + y1 + "x" + xbw + "x" + xbh;
                        if (xfadeMatrix == null) xfadeMatrix = new Matrix();
                        if (!xmk.equals(xfadeMatKey)) {
                            
                            float xsc = (float) (Math.max(w / (double) xbw, y1 / (double) xbh) * BANNER_BLEED);
                            float xdx = (w - xbw * xsc) * 0.5f, xdy = (y1 - xbh * xsc) * 0.5f;
                            xfadeMatrix.reset(); xfadeMatrix.postScale(xsc, xsc); xfadeMatrix.postTranslate(xdx, xdy);
                            xfadeMatKey = xmk;
                        }
                        try { pBmp.setAlpha(xa); canvas.drawBitmap(xfadeBmp, xfadeMatrix, pBmp); } catch (Throwable ignored) {}
                        needInv = true;
                    }
                }
                pBmp.setAlpha(fa); canvas.drawBitmap(bmp, matrix, pBmp);
                float visFactor = xa > 0 ? 1f : ff;
                if (!lite && okBmp(bb) && coll > 0.05f) {
                    pBlur.setAlpha((int) (coll * 255 * visFactor)); canvas.drawBitmap(bb, matrix, pBlur);
                }
                int da = Math.min(100 + (int) (coll * 100), 220);
                pDark.setARGB((int) (da * visFactor), 0, 0, 0); canvas.drawRect(0, 0, w, y1, pDark);
                if (grad != null) {
                    pGrad.setShader(grad); pGrad.setAlpha((int) (255 * visFactor));
                    int gh = Math.max(1, (int) (y1 * 0.4)); canvas.drawRect(0, y1 - gh, w, y1, pGrad);
                }
                if (xa <= 0 && xfadeBmp != null) { try { clearXfade(); } catch (Throwable ignored) {} }
            } finally {
                try { canvas.restoreToCount(sid); } catch (Throwable e) { try { canvas.restore(); } catch (Throwable ignored) {} }
            }
            if (needInv) postInv();
        } catch (Throwable ignored) {}
    }

    private void drawXfadeOnly(Canvas canvas, int w, int y1) {
        try {
            if (!okBmp(xfadeBmp) || xfadeStart <= 0 || canvas == null || w <= 0 || y1 <= 0) return;
            
            if (t() - xfadeStart > 2.5) { try { clearXfade(); } catch (Throwable ignored) {} return; }
            int xbw = xfadeBmp.getWidth(), xbh = xfadeBmp.getHeight();
            if (xbw <= 0 || xbh <= 0) return;
            initPaints();
            String xmk = w + "x" + y1 + "x" + xbw + "x" + xbh;
            if (xfadeMatrix == null) xfadeMatrix = new Matrix();
            if (!xmk.equals(xfadeMatKey)) {
                float xsc = (float) (Math.max(w / (double) xbw, y1 / (double) xbh) * BANNER_BLEED);
                float xdx = (w - xbw * xsc) * 0.5f, xdy = (y1 - xbh * xsc) * 0.5f;
                xfadeMatrix.reset(); xfadeMatrix.postScale(xsc, xsc); xfadeMatrix.postTranslate(xdx, xdy);
                xfadeMatKey = xmk;
            }
            int sid = canvas.save();
            try {
                canvas.clipRect(0, 0, w, y1);
                pBmp.setAlpha(255); canvas.drawBitmap(xfadeBmp, xfadeMatrix, pBmp);
                pDark.setARGB(100, 0, 0, 0); canvas.drawRect(0, 0, w, y1, pDark);
                if (grad != null) {
                    pGrad.setShader(grad); pGrad.setAlpha(255);
                    int gh = Math.max(1, (int) (y1 * 0.4)); canvas.drawRect(0, y1 - gh, w, y1, pGrad);
                }
            } finally {
                try { canvas.restoreToCount(sid); } catch (Throwable e) { try { canvas.restore(); } catch (Throwable ignored) {} }
            }
            postInv();
        } catch (Throwable ignored) {}
    }

    private void initPaints() {
        if (pBmp == null) {
            pBmp = new Paint(); pBmp.setFilterBitmap(true); pBmp.setDither(true); pBmp.setAntiAlias(true);
            pBlur = new Paint(); pBlur.setFilterBitmap(true); pBlur.setDither(true); pBlur.setAntiAlias(true);
            pDark = new Paint(); pDark.setStyle(Paint.Style.FILL); pDark.setAntiAlias(true);
            pGrad = new Paint(); matrix = new Matrix(); vidMatrix = new Matrix();
        }
    }

    private Bitmap decodeCapped(String path) {
        try {
            if (path == null || !new File(path).exists()) return null;
            BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int w = o.outWidth, h = o.outHeight;
            if (w <= 0 || h <= 0) return null;
            int s = 1;
            while (w / s > BMP_MAX || h / s > BMP_MAX) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s; o2.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(path, o2);
        } catch (Throwable t) {
            try { return BitmapFactory.decodeFile(path); } catch (Throwable e) { return null; }
        }
    }

    private void preloadBmp(String path) {
        if (path == null || isVideoPath(path) || preloading.contains(path)) return;
        if (okBmp(bitmaps.get(path))) return;
        preloading.add(path);
        executor.submit(() -> {
            Bitmap decoded = null;
            try {
                if (new File(path).exists()) {
                    decoded = decodeCapped(path);
                }
            } catch (Throwable ignored) {}
            final Bitmap result = decoded;
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    if (okBmp(result)) {
                        Bitmap previous = bitmaps.put(path, result);
                        if (previous != null && previous != result) {
                            recycle(previous);
                        }
                        invalidateTopView();
                    } else {
                        recycle(result);
                    }
                } finally {
                    preloading.remove(path);
                }
            });
        });
    }

    private Bitmap loadAvatar(long eid) {
        try {
            int account = UserConfig.selectedAccount;
            MessagesController mc = MessagesController.getInstance(account);
            TLRPC.FileLocation fl = null;
            if (eid > 0) {
                TLRPC.User u = mc.getUser(eid);
                if (u != null && u.photo != null) fl = u.photo.photo_big;
            } else {
                TLRPC.Chat c = mc.getChat(-eid);
                if (c != null && c.photo != null) fl = c.photo.photo_big;
            }
            if (fl == null) return null;
            File af = FileLoader.getInstance(account).getPathToAttach(fl, true);
            
            if (af != null && af.exists()) return decodeCapped(af.getAbsolutePath());
        } catch (Throwable ignored) {}
        return null;
    }

    private Bitmap avatarBitmapFor(long eid) {
        Bitmap b = avBmpByEid.get(eid);
        if (okBmp(b)) return b;
        kickAvatarDecode(eid);
        return null;
    }

    private void kickAvatarDecode(long eid) {
        if (!avLoading.add(eid)) return;
        executor.submit(() -> {
            Bitmap ab = null;
            try { ab = loadAvatar(eid); } catch (Throwable ignored) {}
            final Bitmap fab = ab;
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    if (okBmp(fab)) {
                        Bitmap previous = avBmpByEid.put(eid, fab);
                        if (previous != null && previous != fab) recycle(previous);
                    }
                } catch (Throwable ignored) {
                    recycle(fab);
                }
                avLoading.remove(eid);
                if (okBmp(fab)) invalidateTopView();
            });
        });
    }

    public void beginProfileExit(long eid) {
        profileExitActive = true;
        profileExitEid = eid;
        profileExitAvatarProgress = 1f;
        float liveAvatarAlpha = lastFadeA >= 0f
                ? clamp01(lastFadeA)
                : avatarContainer != null ? clamp01(avatarContainer.getAlpha())
                : avatarImage != null ? clamp01(avatarImage.getAlpha()) : 1f;
        profileExitAvatarAlpha = liveAvatarAlpha;
        profileExitTextureAlpha = videoTexture != null ? videoTexture.getAlpha() : 0f;
        profileExitFreezeAlpha = vidFreeze != null ? vidFreeze.getAlpha() : 0f;
        profileExitBlurAlpha = vidBlur != null ? vidBlur.getAlpha() : 0f;
        profileExitContrastAlpha = vidContrast != null ? vidContrast.getAlpha() : 0f;
        profileExitDarkAlpha = vidDark != null ? vidDark.getAlpha() : 0f;
        try { if (videoTexture != null) videoTexture.animate().cancel(); } catch (Throwable ignored) {}
        try { if (vidFreeze != null) vidFreeze.animate().cancel(); } catch (Throwable ignored) {}
        try {
            if (videoPlayer != null) {
                videoPlayer.setVolume(0f);
                lastVol = 0f;
            }
        } catch (Throwable ignored) {}
    }

    public void applyProfileExitAlpha(long eid, float alpha) {
        if (!profileExitActive || profileExitEid != eid) return;
        alpha = clamp01(alpha);
        profileExitAvatarProgress = alpha;
        try { if (videoTexture != null) videoTexture.setAlpha(profileExitTextureAlpha * alpha); } catch (Throwable ignored) {}
        try { if (vidFreeze != null) vidFreeze.setAlpha(profileExitFreezeAlpha * alpha); } catch (Throwable ignored) {}
        try { if (vidBlur != null) vidBlur.setAlpha(profileExitBlurAlpha * alpha); } catch (Throwable ignored) {}
        try { if (vidContrast != null) vidContrast.setAlpha(profileExitContrastAlpha * alpha); } catch (Throwable ignored) {}
        try { if (vidDark != null) vidDark.setAlpha(profileExitDarkAlpha * alpha); } catch (Throwable ignored) {}
        applyProfileExitAvatarAlpha();
    }

    private void applyProfileExitAvatarAlpha() {
        if (!profileExitActive) return;
        float reveal = 1f - clamp01(profileExitAvatarProgress);
        float alpha = profileExitAvatarAlpha
                + (1f - profileExitAvatarAlpha) * reveal;
        if (avatarContainer != null) {
            setViewAlphaIfNeeded(avatarContainer, alpha);
        } else {
            setViewAlphaIfNeeded(avatarImage, alpha);
        }
    }

    public void endProfileExit(long eid) {
        if (!profileExitActive || profileExitEid != eid) return;
        
        try { if (videoTexture != null) videoTexture.setAlpha(profileExitTextureAlpha); } catch (Throwable ignored) {}
        try { if (vidFreeze != null) vidFreeze.setAlpha(profileExitFreezeAlpha); } catch (Throwable ignored) {}
        try { if (vidBlur != null) vidBlur.setAlpha(profileExitBlurAlpha); } catch (Throwable ignored) {}
        try { if (vidContrast != null) vidContrast.setAlpha(profileExitContrastAlpha); } catch (Throwable ignored) {}
        try { if (vidDark != null) vidDark.setAlpha(profileExitDarkAlpha); } catch (Throwable ignored) {}
        if (avatarContainer != null) {
            setViewAlphaIfNeeded(avatarContainer, profileExitAvatarAlpha);
        } else {
            setViewAlphaIfNeeded(avatarImage, profileExitAvatarAlpha);
        }
        profileExitActive = false;
        profileExitEid = 0;
        profileExitAvatarProgress = 1f;
    }

    public void applyVideoFx(float extra, int y1, float expand) {
        if (videoPlayer == null || videoTexture == null) return;
        
        applyAudioVolume(extra);
        
        if (vidContrast != null) {
            try { vidContrast.setAlpha(videoVisualProgress()); } catch (Throwable ignored) {}
        }
        
        syncVideoViewport(y1);
        double now = frameTime != 0 ? frameTime : t();
        if (now - lastFxTime < FX_MIN_INTERVAL) return;
        lastFxTime = now;
        try {
            boolean blurFading = blurFadeStart > 0;
            
            boolean enveloping = firstCommitTime > 0 && (now - firstCommitTime) < COLL_SETTLE;
            if (!blurFading && !enveloping
                    && Math.abs(extra - lastFxExtra) < 0.5 && Math.abs(expand - lastFxExpand) < 0.005) return;
            lastFxExtra = extra; lastFxExpand = expand;
            float baseH = Math.max(maxEh, 400f);
            float er = clamp01(extra / baseH);
            float x = 1f - er;
            float scrollColl = x * x * (3f - 2f * x);
            float coll = collapseEnvelope(scrollColl * (1f - expand));
            
            if (enveloping) postInv();

            int nd = (int) (coll * 140);
            if (nd != lastDa) {
                lastDa = nd;
                if (vidDark != null) try { vidDark.setBackgroundColor(Color.argb(nd, 0, 0, 0)); } catch (Throwable ignored) {}
            }

            if (!NimarkoBannerConfig.liteMode) {
                float blurAlpha = clamp01(coll * Math.max(0f, 1f - er * 3f));
                if (!okBmp(vidBlurBmp)) blurAlpha = 0f;
                else if (blurFadeStart > 0) {
                    double elapsed = now - blurFadeStart;
                    if (elapsed >= 0.5) blurFadeStart = 0;
                    else blurAlpha *= clamp01(elapsed / 0.5);
                }
                if (Math.abs(blurAlpha - lastBa) > 0.002f) {
                    lastBa = blurAlpha;
                    if (vidBlur != null) try { vidBlur.setAlpha(blurAlpha); } catch (Throwable ignored) {}
                }
            } else {
                if (lastBa != 0f) {
                    lastBa = 0f;
                    if (vidBlur != null) try { vidBlur.setAlpha(0f); } catch (Throwable ignored) {}
                }
            }

        } catch (Throwable ignored) {}
    }

    private void syncVideoViewport(int y1) {
        final int vh = Math.max(y1 - 1, 1);
        if (vh > maxVh) {
            maxVh = vh;
        }
        if (vh == lastLh) {
            return;
        }
        lastLh = vh;

        fxViews[0] = videoTexture;
        fxViews[1] = vidFreeze;
        fxViews[2] = vidBlur;
        fxViews[3] = vidContrast;
        fxViews[4] = vidDark;
        for (View view : fxViews) {
            if (view == null) {
                continue;
            }
            try {
                final ViewGroup.LayoutParams params = view.getLayoutParams();
                if (params != null && params.height != vh) {
                    params.height = vh;
                    view.requestLayout();
                }
            } catch (Throwable ignored) {}
        }

        try {
            int width = videoTexture != null ? videoTexture.getWidth() : 0;
            if (width <= 0 && videoTexture != null && videoTexture.getParent() instanceof View) {
                width = ((View) videoTexture.getParent()).getWidth();
            }
            updateVidTransform(width, vh);
        } catch (Throwable ignored) {}
    }

    public void applyAudioVolume(float extra) {
        lastAudioExtra = extra;
        VideoPlayer p = videoPlayer;
        if (p == null) return;
        try {
            if (!curVidSound || showingPh || inCall) {
                if (lastVol != 0f) { lastVol = 0f; p.setVolume(0f); }
                return;
            }
            float baseH = Math.max(maxEh, 400f);
            float er = clamp01(extra / baseH);
            float targetVol;
            if (er <= AUDIO_MUTE_EPSILON) {
                targetVol = 0f;                 
            } else if (er >= 1f - AUDIO_MUTE_EPSILON) {
                targetVol = BASE_VOL;           
            } else {
                targetVol = BASE_VOL * er * er; 
            }
            
            boolean terminal = targetVol == 0f || targetVol == BASE_VOL;
            if (targetVol != lastVol
                    && (terminal || Math.abs(targetVol - lastVol) > AUDIO_VOL_UPDATE_THRESHOLD)) {
                lastVol = targetVol;
                p.setVolume(targetVol);
            }
        } catch (Throwable ignored) {}
    }

    private void scheduleSetupVideo(ViewGroup tv, String path, int w, int y1) {
        if (tv == null || path == null) return;
        final long generation = ++videoHierarchyGeneration;
        final long ownerId = viewedProfileId;
        final String requestedPath = path;
        final Runnable transaction = () -> {
            if (generation != videoHierarchyGeneration
                    || ownerId != viewedProfileId
                    || tv != currentTopView
                    || !isProfileOpen
                    || !curIv
                    || !pathEq(requestedPath, curBf)) {
                return;
            }
            setupVideo(tv, requestedPath, w, y1);
        };
        try {
            tv.post(transaction);
        } catch (Throwable ignored) {
            AndroidUtilities.runOnUIThread(transaction, 1);
        }
    }

    private void scheduleDestroyVideo() {
        final long generation = ++videoHierarchyGeneration;
        final VideoPlayer expectedPlayer = videoPlayer;
        final TextureView expectedTexture = videoTexture;
        final String expectedPath = curVidPath;
        final Runnable transaction = () -> {
            if (generation != videoHierarchyGeneration
                    || videoPlayer != expectedPlayer
                    || videoTexture != expectedTexture
                    || (expectedPath == null
                        ? curVidPath != null
                        : !pathEq(curVidPath, expectedPath))) {
                return;
            }
            destroyVideo();
        };
        ViewGroup host = currentTopView;
        try {
            if (host != null) {
                host.post(transaction);
            } else {
                AndroidUtilities.runOnUIThread(transaction, 1);
            }
        } catch (Throwable ignored) {
            AndroidUtilities.runOnUIThread(transaction, 1);
        }
    }

    private void setupVideo(ViewGroup tv, String path, int w, int y1) {
        ensureCallObserver(); 
        try {
            double now = frameTime != 0 ? frameTime : t();
            av("setupVideo ENTER path=" + (path == null ? "null" : path.substring(Math.max(0, path.length() - 16)))
                    + " curVid=" + (curVidPath == null ? "null" : "set") + " vp=" + (videoPlayer != null)
                    + " vt=" + (videoTexture != null) + " vidReady=" + vidReady + " pausedByTab=" + videoPausedByTab);
            if (path.equals(curVidPath) && videoPlayer != null && videoTexture != null && !videoPausedByTab) {
                if (vidTexAttachedTvId == System.identityHashCode(tv)) { return; } 
                
                if (waitFrame && videoTexture.getParent() != null) { dbg("setupVideo SKIP rebuild — reveal in flight (waitFrame)"); return; }
            }
            if (now - getOrD(failVids, path) < FAIL_CD_S) { return; }
            if (curVidPath != null && !curVidPath.equals(path)) {
                if (videoTexture != null && videoPlayer != null) {
                    try { if (videoTexture.isAvailable()) freezeAndRelease(); else destroyVideo(); }
                    catch (Throwable e) { destroyVideo(); }
                } else destroyVideo();
            }
            videoViewH = Math.max(y1 - 1, 1);
            if (!prepVideo(path)) return;
            
            boolean reparentKeepFreeze = false;
            if (videoTexture != null) {
                android.view.ViewParent par = videoTexture.getParent();
                boolean parIsTv = par == tv;
                if (par == tv) {
                    vidTexAttachedTvId = System.identityHashCode(tv);
                    if (videoPlayer != null) { return; } else { removeVidViews(); }
                } else if (par != null) {
                    
                    reparentKeepFreeze = path.equals(curVidPath) && videoPlayer != null;
                    if (reparentKeepFreeze && (!okBmp(freezeBmp) || !pathEq(frozenPath, curVidPath))) {
                        try { captureFreezeFrame(); } catch (Throwable ignored) {}
                    }
                    reparentKeepFreeze = okBmp(freezeBmp) && pathEq(frozenPath, curVidPath);
                    removeVidViews(reparentKeepFreeze);
                    
                    reparentCover = reparentKeepFreeze;
                }
            }
            if (now - lastVidAttach < VID_ATTACH_INT) { return; }
            lastVidAttach = now;
            removeVidViews(reparentKeepFreeze);
            addVidViews(tv);
        } catch (Throwable ignored) {}
    }

    private boolean prepVideo(String path) {
        try {
            if (t() - getOrD(failVids, path) < FAIL_CD_S) { dbg("prepVideo SKIP (in fail cooldown) " + path); return false; }
            File f = new File(path);
            if (!f.exists() || f.length() < MIN_VID) {
                dbg("prepVideo FAIL file exists=" + f.exists() + " len=" + (f.exists() ? f.length() : -1) + " min=" + MIN_VID + " " + path);
                failVids.put(path, t()); return false;
            }
            if (videoPlayer != null && path.equals(curVidPath)) { dbg("prepVideo already-prepared " + path); return true; }
            if (path.equals(videoPreparing)) { dbg("prepVideo already-preparing " + path); return false; }
            dbg("prepVideo START path=" + path + " size=" + f.length());
            if (videoPlayer != null) destroyVideo();
            if (path.equals(ctrl.placeholderPath())) curVidSound = false;
            else { long eid = ctrl.eidForPath(path); curVidSound = eid != 0 && ctrl.hasSound(eid); }
            videoPreparing = path;
            VideoPlayer player = null;
            long sessionId = ++videoSessionId;
            try {
                player = new VideoPlayer();
                player.setDelegate(new BannerDelegate(sessionId, player, path));
                player.setLooping(true);
                try { player.setVolume(0f); } catch (Throwable ignored) {}
                videoPlayer = player; curVidPath = path; vidReady = false; lastVol = -1f;
                
                readVidSizeAsync(sessionId, player, path);
                if (videoTexture != null) { try { player.setTextureView(videoTexture); } catch (Throwable ignored) {} }
                player.preparePlayer(Uri.fromFile(new File(path)), "other");
                videoPreparing = null;
                dbg("prepVideo preparePlayer OK, waiting STATE_READY " + path);
                invalidateTopView();
                return true;
            } catch (Throwable e) {
                dbg("prepVideo EXCEPTION " + path + " : " + e);
                if (isCurrentVideoSession(sessionId, player, path)) {
                    removeVidViews();
                    releasePlayer();
                } else if (player != null) {
                    try { player.releasePlayer(true); } catch (Throwable ignored) {}
                }
                videoPreparing = null;
                failVids.put(path, t());
                executor.submit(() -> handleVidFail(path));
                return false;
            }
        } catch (Throwable e) {
            videoPlayer = null; curVidPath = null; curVidSound = false; lastVol = -1f;
            failVids.put(path, t());
            executor.submit(() -> handleVidFail(path));
            return false;
        }
    }

    private void addVidViews(ViewGroup tv) {
        try {
            if (videoPlayer == null) { reparentCover = false; dbg("addVidViews SKIP videoPlayer==null"); return; }
            try { if (tv.getWindowToken() == null) { reparentCover = false; dbg("addVidViews SKIP no windowToken"); return; } } catch (Throwable ignored) {}
            dbg("addVidViews adding TextureView+layers vh=" + Math.max(videoViewH, 1));
            int vh = Math.max(videoViewH, 1);
            maxVh = vh; lastLh = vh; lastDa = -1; lastBa = -1f; lastVol = -1f;
            lastFxExtra = -1; lastFxExpand = -1;
            
            vidFirstFrameTime = 0;
            
            if (okBmp(reopenFreeze)) { recycle(reopenFreeze); reopenFreeze = null; reopenFreezePath = null; }
            if (okBmp(freezeBmp)) { Bitmap old = freezeBmp; freezeBmp = null; frozenPath = null; recycle(old); }
            boolean hasFr = false;
            reparentCover = false;
            android.content.Context ctx = ApplicationLoader.applicationContext;

            videoTexture = new TextureView(ctx) {
                @Override
                protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
                    super.onSizeChanged(width, height, oldWidth, oldHeight);
                    
                    if (this == NimarkoBannerRenderer.this.videoTexture && width > 0 && height > 0) {
                        updateVidTransform(width, height);
                    }
                }
            };
            videoTexture.setLayoutParams(lp(vh)); videoTexture.setOpaque(false);
            
            videoTexture.setAlpha(0f); videoTexture.setVisibility(View.INVISIBLE); tv.addView(videoTexture, 0);

            vidFreeze = new ImageView(ctx); vidFreeze.setScaleType(ImageView.ScaleType.CENTER_CROP);
            vidFreeze.setLayoutParams(lp(vh)); vidFreeze.setBackgroundColor(Color.TRANSPARENT);
            
            vidFreeze.setAlpha(0f);
            waitFrame = true; tv.addView(vidFreeze, 1);

            vidBlur = new ImageView(ctx); vidBlur.setScaleType(ImageView.ScaleType.CENTER_CROP);
            vidBlur.setAlpha(0f); vidBlur.setLayoutParams(lp(vh)); tv.addView(vidBlur, 2);

            vidContrast = new View(ctx);
            GradientDrawable contrast = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x70000000, 0x18000000, 0xB0000000});
            vidContrast.setBackground(contrast);
            vidContrast.setAlpha(0f);
            vidContrast.setLayoutParams(lp(vh)); tv.addView(vidContrast, 3);

            vidDark = new View(ctx); vidDark.setBackgroundColor(Color.TRANSPARENT);
            vidDark.setLayoutParams(lp(vh)); tv.addView(vidDark, 4);

            vidTexAttachedTvId = System.identityHashCode(tv);

            VideoPlayer p = videoPlayer;
            if (p != null) {
                try { p.setTextureView(videoTexture); } catch (Throwable ignored) {}
                
                freshAttachPending = true;
                try { updateVidTransform(0, 0); } catch (Throwable ignored) {}
                boolean playGate = vidReady && !appPaused && isProfileOpen && !videoPausedByTab && !overlayOpen;
                if (playGate) {
                    try { p.play(); } catch (Throwable ignored) {}
                }
                startBlur();
            }
        } catch (Throwable ignored) {}
    }

    private FrameLayout.LayoutParams lp(int h) {
        FrameLayout.LayoutParams l = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, h);
        l.gravity = Gravity.TOP;
        return l;
    }

    private void removeVidViews() { removeVidViews(false); }

    private void removeVidViews(boolean keepFreeze) {
        java.util.ArrayList<Bitmap> bmps = new java.util.ArrayList<>();
        try {
            stopBlur();
            try { if (vidXfade != null) { vidXfade.cancel(); vidXfade = null; } } catch (Throwable ignored) {}
            if (videoPlayer != null) { try { videoPlayer.setTextureView(null); } catch (Throwable ignored) {} }
            if (videoTexture != null) try { videoTexture.animate().cancel(); } catch (Throwable ignored) {}
            if (vidFreeze != null) try { vidFreeze.animate().cancel(); } catch (Throwable ignored) {}
            if (vidBlur != null) try { vidBlur.setImageBitmap(null); } catch (Throwable ignored) {}
            
            if (vidFreeze != null && !keepFreeze) try { vidFreeze.setImageBitmap(null); } catch (Throwable ignored) {}
            if (vidBlurBmp != null) { bmps.add(vidBlurBmp); vidBlurBmp = null; }
            if (!keepFreeze) {
                if (freezeBmp != null) { bmps.add(freezeBmp); freezeBmp = null; }
                frozenPath = null;
                
                reparentCover = false;
            }
            waitFrame = false;
            for (View v : new View[]{videoTexture, vidFreeze, vidBlur, vidContrast, vidDark}) {
                if (v != null) {
                    try {
                        android.view.ViewParent par = v.getParent();
                        if (par instanceof ViewGroup) ((ViewGroup) par).removeView(v);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        videoTexture = null; vidFreeze = null; vidBlur = null; vidContrast = null; vidDark = null;
        vidTexAttachedTvId = 0; vidFirstFrameTime = 0; freshAttachPending = false;
        for (Bitmap b : bmps) recycle(b);
    }

    private void releasePlayer() {
        stopBlur();
        VideoPlayer p = videoPlayer; videoPlayer = null;
        videoSessionId++;
        curVidPath = null; vidReady = false; curVidSound = false; lastVol = -1f;
        videoPreparing = null; vidW = 0; vidH = 0;
        if (p != null) { try { p.releasePlayer(true); } catch (Throwable ignored) {} }
    }

    public void destroyVideo() {
        
        if (okBmp(freezeBmp) && frozenPath != null) {
            recycle(reopenFreeze);
            reopenFreeze = freezeBmp; reopenFreezePath = frozenPath;
            freezeBmp = null; frozenPath = null;
        }
        stopBlur(); releasePlayer(); removeVidViews();
        frozenPath = null; maxVh = 0; vidFirstFrameTime = 0;
        lastDa = -1; lastBa = -1f; lastLh = 0; lastVol = -1f;
    }

    private void handleVidFail(String path) {
        try { ctrl.handleVideoFail(path); } catch (Throwable ignored) {}
    }

    private boolean captureFreezeFrame() {
        try {
            if (videoTexture == null || videoPlayer == null || !vidReady) { return false; }
            if (!videoTexture.isAvailable()) { return false; } 
            Bitmap fr = (vidW > 0 && vidH > 0) ? videoTexture.getBitmap(vidW, vidH) : videoTexture.getBitmap();
            if (!okBmp(fr)) { return false; }
            Bitmap old = freezeBmp; freezeBmp = fr; frozenPath = curVidPath;
            if (vidFreeze != null) {
                
                try { vidFreeze.animate().cancel(); vidFreeze.setImageBitmap(fr); vidFreeze.setAlpha(1f); } catch (Throwable ignored) {}
            }
            if (old != null && old != fr) recycle(old);
            return true;
        } catch (Throwable e) { return false; }
    }

    private void armResumeCrossfade() {
        if (waitFrame) { return; }
        if (!okBmp(freezeBmp)) { return; }
        if (!pathEq(frozenPath, curVidPath)) { return; }
        waitFrame = true;
        try {
            try { if (vidXfade != null) vidXfade.cancel(); } catch (Throwable ignored) {}
            if (vidFreeze != null && okBmp(freezeBmp)) {
                
                try {
                    vidFreeze.animate().cancel();
                    vidFreeze.setImageBitmap(freezeBmp);
                    vidFreeze.setAlpha(1f);
                    vidFreeze.setVisibility(View.VISIBLE);
                } catch (Throwable ignored) {}
            }
            
            if (videoTexture != null) { try { videoTexture.animate().cancel(); videoTexture.setAlpha(0f); videoTexture.setVisibility(View.INVISIBLE); } catch (Throwable ignored) {} }
        } catch (Throwable ignored) {}
    }

    private void fadeInFreeze(ImageView fv, Bitmap bmp) {
        if (fv == null) return;
        try {
            fv.animate().cancel();
            fv.setImageBitmap(bmp);
            fv.setAlpha(0f);
            fv.animate().alpha(1f).setDuration(VID_FADE)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        } catch (Throwable e) {
            try { fv.setImageBitmap(bmp); fv.setAlpha(1f); } catch (Throwable ignored) {}
        }
    }

    private android.animation.ValueAnimator vidXfade; 
    
    private void runFreezeCrossfade(final TextureView tex, final ImageView fv, final Bitmap old) {
        float fa = fv != null ? fv.getAlpha() : 1f;
        if (fv != null && fa < 0.98f) {
            long remain = (long) (VID_FADE * (1f - fa)) + 30;
            final long sessionId = videoSessionId;
            final VideoPlayer player = videoPlayer;
            final String path = curVidPath;
            AndroidUtilities.runOnUIThread(() -> {
                if (isCurrentVideoSession(sessionId, player, path)
                        && fv == vidFreeze && tex == videoTexture) {
                    doFreezeSwap(tex, fv, old);
                }
                else recycle(old);
            }, remain);
            return;
        }
        doFreezeSwap(tex, fv, old);
    }

    private void doFreezeSwap(final TextureView tex, final ImageView fv, final Bitmap old) {
        doFreezeSwap(tex, fv, old, VID_FADE);
    }

    private static final long RESUME_FADE = 350;

    private void doFreezeSwap(final TextureView tex, final ImageView fv, final Bitmap old, final long dur) {
        try {
            try { if (vidXfade != null) vidXfade.cancel(); } catch (Throwable ignored) {}
            
            if (tex != null) { try { tex.animate().cancel(); tex.setAlpha(1f); tex.setVisibility(View.VISIBLE); } catch (Throwable ignored) {} } 
            if (fv != null) {
                fv.animate().cancel();
                fv.animate().alpha(0f).setDuration(dur)
                        .setInterpolator(new android.view.animation.LinearInterpolator())
                        .withEndAction(() -> { try { fv.setImageBitmap(null); } catch (Throwable ignored) {} recycle(old); })
                        .start();
            } else {
                recycle(old);
            }
        } catch (Throwable e) {
            try { if (tex != null) { tex.setVisibility(View.VISIBLE); tex.setAlpha(1f); } } catch (Throwable ignored) {}
            try { if (fv != null) { fv.setAlpha(0f); fv.setImageBitmap(null); } } catch (Throwable ignored) {}
            recycle(old);
        }
    }

    private void dismissFreeze() {
        try {
            
            if (vidW <= 0 || vidH <= 0) { av("dismissFreeze DEFERRED — size unknown (vidW=" + vidW + " vidH=" + vidH + ")"); return; }
            try { updateVidTransform(0, 0); } catch (Throwable ignored) {}
            
            if (vidFirstFrameTime != 0) { waitFrame = false; return; }
            waitFrame = false;
            
            int wgOld = resumeWatchGen;
            resumeWatchGen++;
            vidFirstFrameTime = t(); freshAttachPending = false; dbg("VID firstFrame rendered → dismissFreeze (suppressBg in " + (VID_FADE / 1000.0) + "s)");
            av("dismissFreeze REVEAL — firstFrame stamped, texture fading in NOW (avatar may hide from here)");
            TextureView tex = videoTexture; ImageView fv = vidFreeze;
            frozenPath = null;
            
            boolean coverVisible = fv != null && fv.getVisibility() == View.VISIBLE
                    && fv.getAlpha() > 0.5f && fv.getDrawable() != null;
            if (coverVisible) {
                Bitmap old = freezeBmp; freezeBmp = null;
                doFreezeSwap(tex, fv, old, RESUME_FADE);
            } else {
                
                if (fv != null) { try { fv.animate().cancel(); fv.setVisibility(View.GONE); fv.setImageDrawable(null); } catch (Throwable ignored) {} }
                if (tex != null) {
                    try {
                        tex.animate().cancel();
                        tex.setAlpha(0f); tex.setVisibility(View.VISIBLE);
                        tex.animate().alpha(1f).setDuration(VID_FADE)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                                .start();
                    } catch (Throwable e) { try { tex.setVisibility(View.VISIBLE); tex.setAlpha(1f); } catch (Throwable ignored) {} }
                }
                Bitmap old = freezeBmp; freezeBmp = null; recycle(old);
            }
        } catch (Throwable ignored) {}
    }

    private boolean captureXfadeBmp() {
        try {
            if (videoTexture == null || videoPlayer == null) return false;
            if (!videoTexture.isAvailable()) return false;
            Bitmap fr = (vidW > 0 && vidH > 0) ? videoTexture.getBitmap(vidW, vidH) : videoTexture.getBitmap();
            if (!okBmp(fr)) return false;
            Bitmap old = xfadeBmp; xfadeBmp = fr; xfadeStart = t();
            if (old != null && old != fr) recycle(old);
            return true;
        } catch (Throwable e) { return false; }
    }

    private void clearXfade() {
        Bitmap old = xfadeBmp; xfadeBmp = null; xfadeStart = 0;
        xfadeMatKey = null;
        if (old != null) { final Bitmap o = old; executor.submit(() -> recycle(o)); }
    }

    private void armPhotoXfade(String oldPath) {
        try {
            if (oldPath == null || oldPath.isEmpty() || isVideoPath(oldPath)) return;
            Bitmap out = bitmaps.remove(oldPath);
            if (!okBmp(out)) { if (out != null) recycle(out); return; }
            Bitmap prev = xfadeBmp;
            xfadeBmp = out; xfadeStart = t(); xfadeMatKey = null;
            if (prev != null && prev != out) { final Bitmap p = prev; executor.submit(() -> recycle(p)); }
        } catch (Throwable ignored) {}
    }

    private void freezeAndRelease() {
        boolean captured = false;
        try { if (vidXfade != null) vidXfade.cancel(); } catch (Throwable ignored) {}
        try {
            if (videoTexture != null && videoPlayer != null && videoTexture.isAvailable()) {
                Bitmap fr = (vidW > 0 && vidH > 0) ? videoTexture.getBitmap(vidW, vidH) : videoTexture.getBitmap();
                if (okBmp(fr)) {
                    Bitmap old = freezeBmp; freezeBmp = fr; frozenPath = curVidPath;
                    
                    if (vidFreeze != null) { try { vidFreeze.animate().cancel(); } catch (Throwable ignored) {} vidFreeze.setImageBitmap(fr); vidFreeze.setAlpha(1f); }
                    waitFrame = true;
                    if (old != null && old != fr) recycle(old);
                    captured = true;
                }
            }
        } catch (Throwable ignored) {}
        if (!captured) {
            try { if (videoTexture != null) { videoTexture.setAlpha(0f); videoTexture.setVisibility(View.INVISIBLE); } } catch (Throwable ignored) {}
            waitFrame = true;
        }
        releasePlayer();
    }

    private void readVidSizeAsync(long sessionId, VideoPlayer player, String path) {
        final String fp = path;
        executor.submit(() -> {
            int w = 0, h = 0, rot = 0;
            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
            try {
                r.setDataSource(fp);
                w = parseIntSafe(r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                h = parseIntSafe(r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                rot = parseIntSafe(r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            } catch (Throwable ignored) {
            } finally { try { r.release(); } catch (Throwable ignored) {} }
            if (rot == 90 || rot == 270) { int t = w; w = h; h = t; }
            final int fw = w, fh = h;
            if (fw <= 0 || fh <= 0) return;
            AndroidUtilities.runOnUIThread(() -> {
                if (isCurrentVideoSession(sessionId, player, fp)
                        && (vidW != fw || vidH != fh)) {
                    vidW = fw; vidH = fh;
                    dbg("readVidSize " + fw + "x" + fh + " (pre-frame) " + fp);
                    try { updateVidTransform(0, 0); } catch (Throwable ignored) {}
                }
            });
        });
    }

    private static int parseIntSafe(String s) {
        try { return s == null ? 0 : Integer.parseInt(s.trim()); } catch (Throwable t) { return 0; }
    }

    private void updateVidTransform(int tw, int th) {
        try {
            if (videoTexture == null || videoPlayer == null) return;
            int vw = vidW, vh = vidH;
            if (tw == 0) tw = videoTexture.getWidth();
            if (th == 0) th = videoTexture.getHeight();
            if (vw <= 0 || vh <= 0 || tw <= 0 || th <= 0) {
                dbg("updateVidTransform SKIP vw=" + vw + " vh=" + vh + " tw=" + tw + " th=" + th
                        + " (texW=" + videoTexture.getWidth() + " texH=" + videoTexture.getHeight() + ")");
                return;
            }
            float sc = Math.max((float) tw / vw, (float) th / vh);
            float sw = vw * sc, sh = vh * sc;
            if (vidMatrix == null) vidMatrix = new Matrix();
            vidMatrix.reset();
            vidMatrix.setScale(sw / tw, sh / th);
            vidMatrix.postTranslate((tw - sw) / 2f, (th - sh) / 2f);
            videoTexture.setTransform(vidMatrix);
            dbg("updateVidTransform APPLIED vid=" + vw + "x" + vh + " view=" + tw + "x" + th
                    + " sc=" + sc + " scaleX=" + (sw / tw) + " scaleY=" + (sh / th)
                    + " drawn=" + (int) sw + "x" + (int) sh);
        } catch (Throwable ignored) {}
    }

    private void startBlur() {
        if (NimarkoBannerConfig.liteMode) return;
        
        Thread cur = blurThread;
        AtomicBoolean curStop = blurStop;
        if (cur != null && cur.isAlive() && curStop != null && !curStop.get()) return;
        final int gen = ++blurGen;
        AtomicBoolean stop = new AtomicBoolean(false);
        blurStop = stop;
        blurThread = new Thread(() -> blurWork(stop, gen), "nimarko-banner-blur");
        blurThread.setDaemon(true);
        blurThread.start();
    }

    private void stopBlur() {
        
        if (blurStop != null) blurStop.set(true);
        blurThread = null; blurStop = null;
        blurGen++;
    }

    private void blurWork(AtomicBoolean stop, int gen) {
        try {
            boolean gotFirst = false;
            for (double d : new double[]{1.0, 1.0, 1.0, 1.5}) {
                if (sleepStop(stop, d) || gen != blurGen) return;
                if (NimarkoBannerConfig.liteMode) return;
                if (overlayOpen || appPaused || videoPausedByTab) continue;
                if (t() - animDoneTime < 0.5) continue;
                if (videoTexture != null && videoPlayer != null && vidReady) {
                    if (grabBlur(gen)) { gotFirst = true; break; }
                }
            }
            if (!gotFirst) {
                if (sleepStop(stop, 1.5) || gen != blurGen) return;
                if (videoTexture != null && videoPlayer != null && vidReady) grabBlur(gen);
            }
            while (!stop.get() && gen == blurGen) {
                if (sleepStop(stop, BLUR_INT)) break;
                if (NimarkoBannerConfig.liteMode) continue;
                if (overlayOpen || appPaused || videoPausedByTab) continue;
                if (videoTexture != null && videoPlayer != null && vidReady) grabBlur(gen);
            }
        } catch (Throwable ignored) {}
    }

    private boolean sleepStop(AtomicBoolean stop, double secs) {
        long ms = (long) (secs * 1000);
        long waited = 0;
        while (waited < ms) {
            if (stop.get()) return true;
            long step = Math.min(50, ms - waited);
            try { Thread.sleep(step); } catch (InterruptedException e) { return true; }
            waited += step;
        }
        return stop.get();
    }

    private boolean grabBlur(int gen) {
        if (NimarkoBannerConfig.liteMode) return false;
        if (gen != blurGen) return false;
        try {
            if (videoTexture == null || videoPlayer == null || !vidReady) return false;
            final Bitmap[] cap = {null};
            final boolean[] acceptingCapture = {true};
            final Object captureLock = new Object();
            final CountDownLatch latch = new CountDownLatch(1);
            AndroidUtilities.runOnUIThread(() -> {
                Bitmap captured = null;
                try {
                    
                    if (gen == blurGen) {
                        TextureView tex = videoTexture;
                        if (tex != null && tex.isAvailable() && videoPlayer != null && vidReady) {
                            captured = tex.getBitmap();
                        }
                    }
                } catch (Throwable ignored) {}
                Bitmap discard = null;
                synchronized (captureLock) {
                    if (acceptingCapture[0]) {
                        cap[0] = captured;
                    } else {
                        discard = captured;
                    }
                }
                recycle(discard);
                latch.countDown();
            });
            boolean capturedInTime;
            try {
                capturedInTime = latch.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                capturedInTime = false;
            }
            final Bitmap fr;
            synchronized (captureLock) {
                acceptingCapture[0] = false;
                fr = cap[0];
                cap[0] = null;
            }
            
            if (!capturedInTime || gen != blurGen) {
                recycle(fr);
                return false;
            }
            if (!okBmp(fr)) return false;
            int fw = fr.getWidth(), fh = fr.getHeight();
            if (fw <= 0 || fh <= 0) { recycle(fr); return false; }
            Bitmap sm = Bitmap.createScaledBitmap(fr, Math.max(1, fw / BLUR_DS), Math.max(1, fh / BLUR_DS), true);
            recycle(fr);
            if (!okBmp(sm)) return false;
            Utilities.stackBlurBitmap(sm, BLUR_SR);
            final Bitmap fin = sm;
            AndroidUtilities.runOnUIThread(() -> {
                
                ImageView blurView = vidBlur;
                if (gen != blurGen || blurView == null) { recycle(fin); return; }
                Bitmap old = vidBlurBmp;
                boolean isFirst = !okBmp(old);
                try {
                    blurView.animate().cancel();
                    blurView.setImageBitmap(fin);
                } catch (Throwable e) {
                    try { blurView.setImageBitmap(null); } catch (Throwable ignored) {}
                    recycle(fin);
                    return;
                }
                
                vidBlurBmp = fin;
                if (isFirst) {
                    blurFadeStart = t(); lastBa = -2f;
                    try { blurView.setAlpha(0f); } catch (Throwable ignored) {}
                    ViewGroup tv = currentTopView;
                    if (tv != null) {
                        for (long dl : new long[]{50, 180, 340, 520}) {
                            AndroidUtilities.runOnUIThread(tv::invalidate, dl);
                        }
                    }
                }
                if (old != null && old != fin) recycle(old);
            });
            return true;
        } catch (Throwable e) { return false; }
    }

    private static double getOrD(Map<String, Double> m, String k) { Double v = m.get(k); return v == null ? 0 : v; }

    private final class BannerDelegate implements VideoPlayer.VideoPlayerDelegate {
        private final long sessionId;
        private final VideoPlayer player;
        private final String cp;
        BannerDelegate(long sessionId, VideoPlayer player, String cp) {
            this.sessionId = sessionId;
            this.player = player;
            this.cp = cp;
        }

        @Override public void onStateChanged(boolean playWhenReady, int playbackState) {
            try {
                dbg("onStateChanged state=" + playbackState + " playWhenReady=" + playWhenReady + " cpMatch=" + cp.equals(curVidPath));
                if (!isCurrentVideoSession(sessionId, player, cp)) return;
                if (playbackState == 3 && !vidReady) { 
                    dbg("VID STATE_READY → vidReady=true, play() " + cp);
                    av("STATE_READY → vidReady=true | playGate appPaused=" + appPaused + " isProfileOpen=" + isProfileOpen
                            + " pausedByTab=" + videoPausedByTab + " overlayOpen=" + overlayOpen
                            + " willPlay=" + (!appPaused && isProfileOpen && !videoPausedByTab && !overlayOpen));
                    vidReady = true;
                    failVids.remove(cp);
                    lastFxExtra = -1; lastFxExpand = -1;
                    try { player.setVolume(0f); lastVol = 0f; } catch (Throwable ignored) {}
                    dbg("VID play-gate appPaused=" + appPaused + " isProfileOpen=" + isProfileOpen
                            + " videoPausedByTab=" + videoPausedByTab + " overlayOpen=" + overlayOpen);
                    if (!appPaused && isProfileOpen && !videoPausedByTab && !overlayOpen) {
                        dbg("VID play() called at READY " + cp);
                        try { player.play(); } catch (Throwable ignored) {}
                        
                        if (waitFrame && videoTexture != null) {
                            final int g = ++resumeWatchGen;
                            AndroidUtilities.runOnUIThread(
                                    () -> resumeFrameWatchdog(g, false, sessionId, player, cp),
                                    300);
                        }
                    } else {
                        dbg("VID play() SKIPPED at READY (gate false) — will play when state allows " + cp);
                    }
                    try { updateVidTransform(0, 0); } catch (Throwable ignored) {}
                    startBlur();
                    invalidateTopView();
                }
            } catch (Throwable ignored) {}
        }

        @Override public void onError(VideoPlayer player, Exception e) {
            try {
                dbg("VID onError " + cp + " : " + e);
                AndroidUtilities.runOnUIThread(() -> {
                    if (!isCurrentVideoSession(sessionId, this.player, cp)
                            || player != this.player) {
                        return;
                    }
                    failVids.put(cp, t());
                    removeVidViews();
                    releasePlayer();
                    executor.submit(() -> handleVidFail(cp));
                });
            } catch (Throwable ignored) {}
        }

        @Override public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
            try {
                dbg("onVideoSizeChanged " + width + "x" + height + " rot=" + unappliedRotationDegrees
                        + " par=" + pixelWidthHeightRatio + " cpMatch=" + cp.equals(curVidPath));
                if (!isCurrentVideoSession(sessionId, player, cp)) return;
                vidW = width; vidH = height; updateVidTransform(0, 0);
            } catch (Throwable ignored) {}
        }

        @Override public void onRenderedFirstFrame() {
            try {
                dbg("onRenderedFirstFrame vidW=" + vidW + " vidH=" + vidH + " cpMatch=" + cp.equals(curVidPath));
                if (isCurrentVideoSession(sessionId, player, cp)) dismissFreeze();
            } catch (Throwable ignored) {}
        }

        @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surfaceTexture) {
            try {
                if (waitFrame && isCurrentVideoSession(sessionId, player, cp)) dismissFreeze();
            } catch (Throwable ignored) {}
        }

        @Override public boolean onSurfaceDestroyed(android.graphics.SurfaceTexture surfaceTexture) { return false; }
    }

    private static final class BitmapLru extends LinkedHashMap<String, Bitmap> {
        private final int max;
        BitmapLru(int max) { super(16, 0.75f, true); this.max = max; }
        @Override public synchronized Bitmap get(Object k) { return super.get(k); }
        @Override public synchronized Bitmap put(String k, Bitmap v) { return super.put(k, v); }
        @Override public synchronized Bitmap remove(Object k) { return super.remove(k); }
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            if (size() > max) { recycle(eldest.getValue()); return true; }
            return false;
        }
        synchronized void clearAll() { for (Bitmap b : values()) recycle(b); clear(); }
    }
}
