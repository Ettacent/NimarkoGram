package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BirthdayController;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextDetailCell;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.RLottieDrawable;
import org.telegram.ui.Components.RecyclerListView;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class ProfileBirthdayEffect extends View {

    private final int currentAccount;
    private final long dialogId;
    private final ProfileActivity profileActivity;
    private BirthdayEffectFetcher fetcher;
    private BirthdayEffectFetcher fetcherToSet;

    public static String numbersEmojipack = "FestiveFontEmoji";
    public static String interactionsPack = "EmojiAnimations";
    public static String[] interactions = new String[] {
        "🎉", "🎆", "🎈"
    };


    public PointF sourcePoint = new PointF();
    private final Matrix sourceMatrix = new Matrix();
    private final Matrix overlayInverse = new Matrix();
    private final float[] anchor = new float[2];
    private final Rect visibleText = new Rect();
    private ViewTreeObserver observedTree;
    private final ViewTreeObserver.OnPreDrawListener anchorListener = () -> {
        if (!this.autoplayed) tryAutoplay();
        return true;
    };
    private void tryAutoplay() {
        if (!autoplayed && !profileActivity.profileTransitionInProgress && start()) {
            autoplayed = true;
        }
    }

    public ProfileBirthdayEffect(ProfileActivity profileActivity, BirthdayEffectFetcher fetcher) {
        super(profileActivity.getContext());

        this.currentAccount = profileActivity.getCurrentAccount();
        this.dialogId = profileActivity.getDialogId();
        this.profileActivity = profileActivity;
        this.fetcher = fetcher;
    }

    private boolean autoplayed;
    private boolean attached;
    private float t = 1;

    private long lastTime;

    private final static long duration = 4200L;

    @Override
    protected void onDraw(Canvas canvas) {

        if (!fetcher.loaded) return;

        if (!attached) {
            for (int i = 0; i < fetcher.allAssets.size(); ++i) {
                fetcher.allAssets.get(i).setParentView(this);
            }
            attached = true;

        }
        tryAutoplay();

        if (!isPlaying) {
            return;
        }

        if (!updateSourcePoint()) {
            isPlaying = false;
            t = 1;
            updateFetcher(fetcherToSet);
            fetcherToSet = null;
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        final float delta = Utilities.clamp((now - lastTime), 20, 0) / (float) duration;
        t = Utilities.clamp(t + delta, 1, 0);
        lastTime = now;


        final int iw = EmojiAnimationsOverlay.getFilterWidth();
        fetcher.interactionAsset.setImageCoords((getWidth() - dp(iw)) / 2f, Math.max(0, sourcePoint.y - dp(iw) * .5f), dp(iw), dp(iw));
        canvas.save();
        canvas.scale(-1, 1, getWidth() / 2f, 0);
        fetcher.interactionAsset.setAlpha(Utilities.clamp((1f - t) / .1f, 1f, 0f));
        fetcher.interactionAsset.draw(canvas);
        canvas.restore();

        final int sz = dp(110);
        for (int i = fetcher.digitAssets.size() - 1; i >= 0; --i) {
            ImageReceiverAsset asset = fetcher.digitAssets.get(i);

            final float t = AndroidUtilities.cascade(this.t, i, fetcher.digitAssets.size(), 1.8f);

            final float w = (getWidth() - sz * .88f * (fetcher.digitAssets.size() - 1)) / 2f - sourcePoint.x;
            final float h = sourcePoint.y + sz;

            final float centerX = sourcePoint.x + sz * .88f * i + t * w;
            final float centerY = sourcePoint.y - h * (float) Math.pow(this.t, 2f);
            final float scale = CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(Utilities.clamp(t / .4f, 1, 0));

            asset.setImageCoords(
                centerX - sz / 2f * scale,
                centerY - sz / 2f * scale,
                sz * scale,
                sz * scale
            );
            asset.draw(canvas);
        }

        if (t >= 1) {
            isPlaying = false;
            updateFetcher(fetcherToSet);
            fetcherToSet = null;
        } else {
            invalidate();
        }
    }

    public void updateFetcher(BirthdayEffectFetcher fetcher) {
        if (this.fetcher == fetcher || fetcher == null) return;
        if (isPlaying) {
            fetcherToSet = fetcher;
        } else {
            if (attached) {
                for (int i = 0; i < this.fetcher.allAssets.size(); ++i) {
                    this.fetcher.allAssets.get(i).setParentView(null);
                }
                attached = false;
            }
            this.fetcher.removeView(this);
            this.fetcher = fetcher;
            if (isAttachedToWindow()) this.fetcher.addView(this);
            if (!attached) {
                for (int i = 0; i < fetcher.allAssets.size(); ++i) {
                    fetcher.allAssets.get(i).setParentView(this);
                }
                attached = true;
            }
        }
    }

    private boolean isPlaying = false;
    public boolean start() {
        if (!isAttachedToWindow() || !fetcher.loaded || !updateSourcePoint()) {
            return false;
        }
        if (t < 1) {
            return false;
        }
        if (fetcher.interactionAsset.getLottieAnimation() != null) {
            fetcher.interactionAsset.getLottieAnimation().setCurrentFrame(0, false);
            fetcher.interactionAsset.getLottieAnimation().restart(true);
        }
        isPlaying = true;
        autoplayed = true;
        t = 0;
        lastTime = SystemClock.elapsedRealtime();
        animate().cancel();
        setAlpha(1f);
        invalidate();
        return true;
    }

    public void hide() {
        animate().alpha(0).setDuration(200).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
    }

    private boolean updateSourcePoint() {
        RecyclerListView listView = profileActivity.getListView();
        final int position = profileActivity.birthdayRow;
        if (position < 0 || listView == null || !listView.isShown()) return false;
        for (int i = 0; i < listView.getChildCount(); ++i) {
            View child = listView.getChildAt(i);
            final int childPosition = listView.getChildAdapterPosition(child);
            if (position == childPosition && child instanceof TextDetailCell) {
                TextView textView = ((TextDetailCell) child).textView;
                if (!textView.isShown() || textView.getAlpha() <= 0f || TextUtils.isEmpty(textView.getText())
                        || textView.getWidth() <= 0 || textView.getHeight() <= 0
                        || !textView.getGlobalVisibleRect(visibleText)
                        || textView.getRootView() != getRootView()) return false;
                sourceMatrix.reset();
                ProfileActivity.profileViewToRoot(this, sourceMatrix);
                if (!sourceMatrix.invert(overlayInverse)) return false;
                sourceMatrix.reset();
                ProfileActivity.profileViewToRoot(textView, sourceMatrix);
                overlayInverse.preConcat(sourceMatrix);
                anchor[0] = textView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                        ? textView.getWidth() - dp(12) : dp(12);
                anchor[1] = textView.getHeight() / 2f;
                overlayInverse.mapPoints(anchor);
                if (anchor[0] < 0 || anchor[0] > getWidth() || anchor[1] < 0 || anchor[1] > getHeight()) return false;
                sourcePoint.set(anchor[0], anchor[1]);
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        fetcher.addView(this);
        observedTree = getViewTreeObserver();
        observedTree.addOnPreDrawListener(anchorListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (observedTree != null && observedTree.isAlive()) observedTree.removeOnPreDrawListener(anchorListener);
        observedTree = null;
        isPlaying = false;
        t = 1;
        if (attached) {
            for (int i = 0; i < fetcher.allAssets.size(); ++i) {
                fetcher.allAssets.get(i).setParentView(null);
            }
            attached = false;
        }
        fetcher.removeView(this);
    }

    public static class BirthdayEffectFetcher {
        public static BirthdayEffectFetcher of(int currentAccount, TLRPC.UserFull userInfo) {
            return of(currentAccount, userInfo, null);
        }

        public static BirthdayEffectFetcher of(int currentAccount, TLRPC.UserFull userInfo, BirthdayEffectFetcher old) {
            if (!LiteMode.isEnabled(LiteMode.FLAG_ANIMATED_STICKERS_CHAT) || !BirthdayController.isToday(userInfo)) {
                if (old != null) {
                    old.detach(false);
                }
                return null;
            }
            final int age;
            if (userInfo != null && userInfo.birthday != null && (userInfo.birthday.flags & 1) != 0) {
                age = Period.between(LocalDate.of(userInfo.birthday.year, userInfo.birthday.month, userInfo.birthday.day), LocalDate.now()).getYears();
            } else {
                age = 0;
            }
            if (old != null) {
                if (old.age == age) return old;
                old.detach(false);
            }
            return new BirthdayEffectFetcher(currentAccount, age);
        }


        public final int currentAccount;
        public final int age;

        private boolean loaded;

        public ImageReceiverAsset interactionAsset;
        public ArrayList<ImageReceiverAsset> digitAssets = new ArrayList<>();

        public ArrayList<ImageReceiverAsset> allAssets = new ArrayList<>();
        public ArrayList<ImageReceiverAsset> loadedAssets = new ArrayList<>();

        private final boolean[] setsLoaded = new boolean[2];

        private BirthdayEffectFetcher(int currentAccount, int age) {
            this.currentAccount = currentAccount;
            this.age = age;

            if (age <= 0) {
                setsLoaded[0] = true;
            } else {
                final ArrayList<Integer> order = new ArrayList<>();
                final HashSet<Integer> digits = new HashSet<>();
                final String ageString = "" + age;
                for (int i = 0; i < ageString.length(); ++i) {
                    char c = ageString.charAt(i);
                    int n = c - '0';
                    if (n < 0 || n > 9) continue;
                    order.add(n);
                    digits.add(n);
                }

                TLRPC.TL_inputStickerSetShortName inputStickerSetShortName = new TLRPC.TL_inputStickerSetShortName();
                inputStickerSetShortName.short_name = numbersEmojipack;
                MediaDataController.getInstance(currentAccount).getStickerSet(inputStickerSetShortName, 0, false, set -> {

                    HashMap<Integer, TLRPC.Document> documents = new HashMap<>();

                    for (Integer digit : digits) {
                        TLRPC.Document d = SelectAnimatedEmojiDialog.findSticker(set, digit + "\uFE0F\u20E3");
                        if (d == null) {
                            d = SelectAnimatedEmojiDialog.findSticker(set, digit + "\u20E3");
                        }
                        if (d == null) {
                            FileLog.e("couldn't find " + (digit + "\uFE0F\u20E3") + " emoji in " + numbersEmojipack);
                            return;
                        }
                        documents.put(digit, d);
                    }

                    HashMap<Integer, ImageReceiverAsset> assets = new HashMap<>();
                    for (Map.Entry<Integer, TLRPC.Document> entry : documents.entrySet()) {
                        final int digit = entry.getKey();
                        ImageReceiverAsset asset = new ImageReceiverAsset();
                        allAssets.add(asset);
                        asset.setEmoji(entry.getValue(), "80_80", set, () -> {
                            loadedAssets.add(asset);
                            checkWhenLoaded();
                        });
                        asset.onAttachedToWindow();
                        assets.put(digit, asset);
                    }

                    for (int i = 0; i < order.size(); ++i) {
                        final int digit = order.get(i);
                        digitAssets.add(assets.get(digit));
                    }

                    setsLoaded[0] = true;
                    checkWhenLoaded();
                });
            }

            final String interaction = interactions[Utilities.random.nextInt(interactions.length)];

            TLRPC.TL_inputStickerSetShortName inputStickerSetShortName2 = new TLRPC.TL_inputStickerSetShortName();
            inputStickerSetShortName2.short_name = interactionsPack;
            MediaDataController.getInstance(currentAccount).getStickerSet(inputStickerSetShortName2, 0, false, set -> {
                TLRPC.Document document = SelectAnimatedEmojiDialog.findSticker(set, interaction);

                if (document == null) {
                    FileLog.e("couldn't find " + interaction + " sticker in " + interactionsPack);
                    return;
                }

                interactionAsset = new ImageReceiverAsset();
                allAssets.add(interactionAsset);
                final int w = EmojiAnimationsOverlay.getFilterWidth();
                interactionAsset.setAutoRepeat(0);
                interactionAsset.setEmoji(document, w + "_" + w + "_precache", set, () -> {
                    loadedAssets.add(interactionAsset);
                    checkWhenLoaded();
                });
                interactionAsset.onAttachedToWindow();

                setsLoaded[1] = true;
                checkWhenLoaded();
            });
        }

        private ArrayList<Runnable> callbacks = new ArrayList<>();
        public void checkWhenLoaded() {
            if (loaded || loadedAssets.size() < allAssets.size()) {
                return;
            }
            if (!setsLoaded[0] || !setsLoaded[1]) {
                return;
            }
            loaded = true;
            for (Runnable callback : callbacks)
                callback.run();
            callbacks.clear();
        }

        public boolean isLoaded() {
            return loaded;
        }

        public void subscribe(Runnable callback) {
            if (loaded) callback.run();
            else callbacks.add(callback);
        }

        private boolean detachLater;
        public void detach(boolean force) {
            if (!force && !views.isEmpty()) {
                detachLater = true;
                return;
            }
            callbacks.clear();
            for (int i = 0; i < allAssets.size(); ++i) {
                allAssets.get(i).onDetachedFromWindow();
            }
            allAssets.clear();
        }

        public ArrayList<ProfileBirthdayEffect> views = new ArrayList<>();

        public void addView(ProfileBirthdayEffect effect) {
            views.add(effect);
        }

        public void removeView(ProfileBirthdayEffect effect) {
            views.remove(effect);
            if (views.isEmpty() && detachLater) {
                detach(true);
                detachLater = false;
            }
        }
    }


    private static class ImageReceiverAsset extends ImageReceiver {

        public void setEmoji(TLRPC.Document document, String filter, TLRPC.TL_messages_stickerSet set, Runnable whenDone) {
            final Runnable[] callback = new Runnable[] { whenDone };
            setDelegate(new ImageReceiverDelegate() {
                @Override
                public void didSetImage(ImageReceiver imageReceiver, boolean set, boolean thumb, boolean memCache) {
                    if (imageReceiver.hasBitmapImage() && callback[0] != null) {
                        RLottieDrawable lottieDrawable = imageReceiver.getLottieAnimation();
                        if (lottieDrawable == null) {
                            callback[0].run();
                            callback[0] = null;
                            return;
                        }
                        if (lottieDrawable.isGeneratingCache()) {
                            lottieDrawable.whenCacheDone = () -> {
                                callback[0].run();
                                callback[0] = null;
                            };
                        } else {
                            callback[0].run();
                            callback[0] = null;
                        }
                    }
                }
            });
            setImage(ImageLocation.getForDocument(document), filter, null, null, set, 0);
        }
    }

}
