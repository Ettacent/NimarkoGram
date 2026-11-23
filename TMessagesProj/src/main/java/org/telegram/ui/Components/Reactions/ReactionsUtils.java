/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components.Reactions;

import static org.telegram.ui.Components.Premium.LimitReachedBottomSheet.TYPE_BOOSTS_FOR_REACTIONS;

import android.graphics.Paint;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stories;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.EditTextCaption;
import org.telegram.ui.Components.Premium.LimitReachedBottomSheet;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.SelectAnimatedEmojiDialog;
import org.telegram.ui.StatisticActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class ReactionsUtils {

    public static final String APPEAR_ANIMATION_FILTER = "30_30_nolimit";
    public static final String SELECT_ANIMATION_FILTER = "60_60_pcache";
    public static final String ACTIVATE_ANIMATION_FILTER = "30_30_pcache";

    public static boolean hasReactionMedia(TLRPC.Document document) {
        return document != null && !(document instanceof TLRPC.TL_documentEmpty)
                && document.id != 0 && document.dc_id > 0 && document.size > 0;
    }

    public static boolean bindStandardReactionIcon(ImageReceiver receiver, int account, String emoji, boolean preview) {
        TLRPC.TL_availableReaction reaction = MediaDataController.getInstance(account).getReactionsMap().get(emoji);
        TLRPC.Document document = null;
        String filter = "40_40_lastreactframe";
        String extension = "webp";
        if (reaction != null) {
            if (preview && hasReactionMedia(reaction.activate_animation)) {
                document = reaction.activate_animation;
                filter = "140_140";
                extension = null;
            } else if (hasReactionMedia(reaction.center_icon)) {
                document = reaction.center_icon;
            } else if (hasReactionMedia(reaction.static_icon)) {
                document = reaction.static_icon;
                filter = "40_40";
            }
        }
        Drawable fallback = standardEmojiFallback(emoji);
        if (document != null) {
            receiver.setImage(ImageLocation.getForDocument(document), filter, fallback, extension, reaction, 1);
        } else {
            receiver.setImageBitmap(fallback);
        }
        return document != null || fallback != null;
    }

    public static Drawable standardEmojiFallback(String emoji) {
        if (TextUtils.isEmpty(emoji) || emoji.length() > 32) return null;
        final String glyph = Emoji.fixEmoji(emoji);
        final String lookup = glyph.replace("\uFE0F", "");
        if (!Emoji.isValidEmoji(glyph) && !Emoji.isValidEmoji(lookup)) return null;
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Rect ink = new Rect();
            private float x, y;

            @Override
            protected void onBoundsChange(Rect bounds) {
                float size = Math.max(1f, Math.min(bounds.width(), bounds.height()) * .87f);
                paint.setTextSize(size);
                paint.getTextBounds(glyph, 0, glyph.length(), ink);
                float maxInk = Math.max(ink.width(), ink.height());
                if (maxInk > size) {
                    paint.setTextSize(size * size / maxInk);
                    paint.getTextBounds(glyph, 0, glyph.length(), ink);
                }
                x = bounds.exactCenterX() - ink.exactCenterX();
                y = bounds.exactCenterY() - ink.exactCenterY();
            }

            @Override public void draw(Canvas canvas) { canvas.drawText(glyph, x, y, paint); }
            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
            @Override public void setColorFilter(ColorFilter filter) {}
            @Override public int getIntrinsicWidth() { return AndroidUtilities.dp(20); }
            @Override public int getIntrinsicHeight() { return getIntrinsicWidth(); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }
    public static boolean compare(TLRPC.Reaction reaction, ReactionsLayoutInBubble.VisibleReaction visibleReaction) {
        if (reaction instanceof TLRPC.TL_reactionEmoji && visibleReaction.documentId == 0 && TextUtils.equals(((TLRPC.TL_reactionEmoji) reaction).emoticon, visibleReaction.emojicon)) {
            return true;
        }
        if (reaction instanceof TLRPC.TL_reactionCustomEmoji && visibleReaction.documentId != 0 && ((TLRPC.TL_reactionCustomEmoji) reaction).document_id == visibleReaction.documentId) {
            return true;
        }
        return false;
    }

    public static boolean compare(TLRPC.Reaction reaction, TLRPC.Reaction reaction2) {
        if (reaction instanceof TLRPC.TL_reactionEmoji && reaction2 instanceof TLRPC.TL_reactionEmoji && TextUtils.equals(((TLRPC.TL_reactionEmoji) reaction).emoticon, ((TLRPC.TL_reactionEmoji) reaction2).emoticon)) {
            return true;
        }
        if (reaction instanceof TLRPC.TL_reactionCustomEmoji && reaction2 instanceof TLRPC.TL_reactionCustomEmoji && ((TLRPC.TL_reactionCustomEmoji) reaction).document_id == ((TLRPC.TL_reactionCustomEmoji) reaction2).document_id) {
            return true;
        }
        return false;
    }

    public static TLRPC.Reaction toTLReaction(ReactionsLayoutInBubble.VisibleReaction visibleReaction) {
        if (visibleReaction.emojicon != null) {
            TLRPC.TL_reactionEmoji emoji = new TLRPC.TL_reactionEmoji();
            emoji.emoticon = visibleReaction.emojicon;
            return emoji;
        } else {
            TLRPC.TL_reactionCustomEmoji emoji = new TLRPC.TL_reactionCustomEmoji();
            emoji.document_id = visibleReaction.documentId;
            return emoji;
        }

    }

    public static CharSequence reactionToCharSequence(TLRPC.Reaction reaction) {
        if (reaction instanceof TLRPC.TL_reactionEmoji) {
            return ((TLRPC.TL_reactionEmoji) reaction).emoticon;
        }
        if (reaction instanceof TLRPC.TL_reactionCustomEmoji) {
            SpannableStringBuilder spannableStringBuilder = new SpannableStringBuilder("d");
            spannableStringBuilder.setSpan(new AnimatedEmojiSpan(((TLRPC.TL_reactionCustomEmoji) reaction).document_id, null), 0, 1, 0);
            return spannableStringBuilder;
        }
        return "";
    }

    public static void applyForStoryViews(TLRPC.Reaction oldReaction, TLRPC.Reaction newReaction, TL_stories.StoryViews views) {
        boolean found = false;
        if (views == null) {
            return;
        }
        for (int i = 0; i < views.reactions.size(); i++) {
            TLRPC.ReactionCount reactionCount = views.reactions.get(i);
            if (oldReaction != null) {
                if (compare(reactionCount.reaction, oldReaction)) {
                    reactionCount.count--;
                    if (reactionCount.count <= 0) {
                        views.reactions.remove(i);
                        i--;
                        continue;
                    }
                }
            }
            if (newReaction != null) {
                if (compare(reactionCount.reaction, newReaction)) {
                    reactionCount.count++;
                    found = true;
                }
            }
        }
        if (!found) {
            TLRPC.ReactionCount reactionCount = new TLRPC.TL_reactionCount();
            reactionCount.count = 1;
            reactionCount.reaction = newReaction;
            views.reactions.add(reactionCount);
        }
    }

    public static void showLimitReachedDialogForReactions(long dialogId, int lvl, TL_stories.TL_premium_boostsStatus boostsStatus) {
        BaseFragment fragment = LaunchActivity.getLastFragment();
        if (fragment == null || boostsStatus == null) {
            return;
        }
        LimitReachedBottomSheet limitReachedBottomSheet = new LimitReachedBottomSheet(fragment, fragment.getContext(), TYPE_BOOSTS_FOR_REACTIONS, UserConfig.selectedAccount, fragment.getResourceProvider());
        limitReachedBottomSheet.setRequiredLvl(lvl);
        limitReachedBottomSheet.setBoostsStats(boostsStatus, true);
        limitReachedBottomSheet.setDialogId(dialogId);
        limitReachedBottomSheet.showStatisticButtonInLink(() -> {
            TLRPC.Chat chat = fragment.getMessagesController().getChat(-dialogId);
            fragment.presentFragment(StatisticActivity.create(chat));
        });
        limitReachedBottomSheet.show();
    }

    public static SpannableString createSpannableText(AnimatedEmojiSpan span, String key) {
        SpannableString spannable = new SpannableString(key);
        spannable.setSpan(span, 0, spannable.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return spannable;
    }

    public static AnimatedEmojiSpan createAnimatedEmojiSpan(TLRPC.Document document, Long documentId, Paint.FontMetricsInt fontMetricsInt) {
        AnimatedEmojiSpan span;
        if (document != null) {
            span = new AnimatedEmojiSpan(document, 1.0f, fontMetricsInt);
        } else {
            span = new AnimatedEmojiSpan(documentId, 1.0f, fontMetricsInt);
        }
        span.cacheType = AnimatedEmojiDrawable.getCacheTypeForEnterView();
        return span;
    }

    public static void addReactionToEditText(TLRPC.TL_availableReaction availableReaction, HashMap<Long, AnimatedEmojiSpan> selectedEmojis, List<Long> selectedEmojisIds, Editable editText, SelectAnimatedEmojiDialog selectAnimatedEmojiDialog, Paint.FontMetricsInt fontMetricsInt) {
        long id = availableReaction.activate_animation.id;
        AnimatedEmojiSpan span = createAnimatedEmojiSpan(availableReaction.activate_animation, id, fontMetricsInt);
        selectedEmojis.put(id, span);
        selectedEmojisIds.add(id);
        editText.append(createSpannableText(span, availableReaction.reaction));
        if (selectAnimatedEmojiDialog != null) {
            selectAnimatedEmojiDialog.setMultiSelected(id, false);
        }
    }

    public static void addReactionToEditText(TLRPC.TL_reactionCustomEmoji customEmoji, HashMap<Long, AnimatedEmojiSpan> selectedEmojis, List<Long> selectedEmojisIds, Editable editText, SelectAnimatedEmojiDialog selectAnimatedEmojiDialog, Paint.FontMetricsInt fontMetricsInt) {
        AnimatedEmojiSpan span = createAnimatedEmojiSpan(null, customEmoji.document_id, fontMetricsInt);
        selectedEmojis.put(customEmoji.document_id, span);
        selectedEmojisIds.add(customEmoji.document_id);
        editText.append(createSpannableText(span, "e"));
        if (selectAnimatedEmojiDialog != null) {
            selectAnimatedEmojiDialog.setMultiSelected(customEmoji.document_id, false);
        }
    }

    public static List<AnimatedEmojiDrawable> startPreloadReactions(TLRPC.Chat currentChat, TLRPC.ChatFull chatFull) {
        List<AnimatedEmojiDrawable> result = new ArrayList<>();
        if (chatFull == null || !ChatObject.isChannelAndNotMegaGroup(currentChat)) {
            return result;
        }
        if (chatFull.available_reactions instanceof TLRPC.TL_chatReactionsSome) {
            TLRPC.TL_chatReactionsSome reactionsSome = (TLRPC.TL_chatReactionsSome) chatFull.available_reactions;
            for (TLRPC.Reaction reaction : reactionsSome.reactions) {
                AnimatedEmojiDrawable animatedEmojiDrawable = null;
                if (reaction instanceof TLRPC.TL_reactionEmoji) {
                    TLRPC.TL_reactionEmoji reactionEmoji = ((TLRPC.TL_reactionEmoji) reaction);
                    TLRPC.TL_availableReaction availableReaction = MediaDataController.getInstance(UserConfig.selectedAccount).getReactionsMap().get(reactionEmoji.emoticon);
                    if (availableReaction == null) {
                        continue;
                    }
                    TLRPC.Document document = availableReaction.activate_animation;
                    animatedEmojiDrawable = AnimatedEmojiDrawable.make(UserConfig.selectedAccount, AnimatedEmojiDrawable.getCacheTypeForEnterView(), document);
                } else if (reaction instanceof TLRPC.TL_reactionCustomEmoji) {
                    TLRPC.TL_reactionCustomEmoji customEmoji = (TLRPC.TL_reactionCustomEmoji) reaction;
                    animatedEmojiDrawable = AnimatedEmojiDrawable.make(UserConfig.selectedAccount, AnimatedEmojiDrawable.getCacheTypeForEnterView(), customEmoji.document_id);
                }
                if (animatedEmojiDrawable != null) {
                    result.add(animatedEmojiDrawable);
                    animatedEmojiDrawable.addView((AnimatedEmojiSpan.InvalidateHolder) null);
                }
            }
        } else if (chatFull.available_reactions instanceof TLRPC.TL_chatReactionsAll) {
            for (TLRPC.TL_availableReaction availableReaction : MediaDataController.getInstance(UserConfig.selectedAccount).getEnabledReactionsList()) {
                if (availableReaction == null) {
                    continue;
                }
                TLRPC.Document document = availableReaction.activate_animation;
                AnimatedEmojiDrawable animatedEmojiDrawable = AnimatedEmojiDrawable.make(UserConfig.selectedAccount, AnimatedEmojiDrawable.getCacheTypeForEnterView(), document);
                result.add(animatedEmojiDrawable);
                animatedEmojiDrawable.addView((AnimatedEmojiSpan.InvalidateHolder) null);
            }
        }
        return result;
    }

    public static void stopPreloadReactions(List<AnimatedEmojiDrawable> list) {
        for (AnimatedEmojiDrawable animatedEmojiDrawable : list) {
            animatedEmojiDrawable.removeView((AnimatedEmojiSpan.InvalidateHolder) null);
        }
    }
}
