/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.view.Gravity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Wallet.GramWallet;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public class ProfileBalanceView extends ProfileMusicView {
    private final Drawable gramIcon;
    private final Drawable gramColorIcon;
    private final Text label;
    private final AnimatedTextView.AnimatedTextDrawable amount;
    private boolean balanceInitialized;
    private float amountWidth;
    private final Paint chevronPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path chevron = new Path();
    private final CompactChevronRenderer chevronRenderer = new CompactChevronRenderer();
    private final boolean customGramIcon;
    private float ejectProgress;
    private final DecimalFormat balanceFormat = new DecimalFormat("#,##0.#########", new DecimalFormatSymbols(Locale.US));

    public ProfileBalanceView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider);
        gramIcon = context.getResources().getDrawable(R.drawable.profile_balance_gram).mutate();
        gramColorIcon = context.getResources().getDrawable(R.drawable.profile_balance_gram_color).mutate();
        customGramIcon = context.getResources() instanceof app.nimarkogram.messenger.icons.IconResources
                && ((app.nimarkogram.messenger.icons.IconResources) context.getResources()).hasReplacement(R.drawable.profile_balance_gram);
        label = Text.ui(getString(R.string.StarsBalance) + ":", 11);
        amount = new AnimatedTextView.AnimatedTextDrawable(false, true, true, true, true);
        amount.setCallback(this);
        amount.setTextSize(dp(11));
        amount.setTypeface(AndroidUtilities.bold());
        amount.setGravity(Gravity.LEFT);
        amount.ignoreRTL = true;
        amount.setOverrideFullWidth(dp(4096));
        amount.setFadeOverflow(true);
        amount.setAnimationProperties(0.35f, 0, 320, CubicBezierInterpolator.EASE_OUT_QUINT);
        chevronPaint.setStyle(Paint.Style.STROKE);
        chevronPaint.setStrokeCap(Paint.Cap.ROUND);
        chevronPaint.setStrokeJoin(Paint.Join.ROUND);
        chevronPaint.setStrokeWidth(dpf2(1.16f));
        chevron.moveTo(0, -dpf2(3.33f));
        chevron.lineTo(dpf2(3.16f), 0);
        chevron.lineTo(0, dpf2(3.33f));
        amount.setText("0", false);
    }

    public void setBalance(long nanograms) {
        final String formatted = balanceFormat.format(new BigDecimal(GramWallet.formatGramsBalance(nanograms, false)));
        amount.setText(formatted, balanceInitialized);
        balanceInitialized = true;
        setContentDescription(getString(R.string.StarsBalance) + ": " + formatted);
        invalidateContentWidth();
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return who == amount || super.verifyDrawable(who);
    }

    @Override
    public void invalidateDrawable(Drawable who) {
        if (who == amount) invalidateContentWidth();
        else super.invalidateDrawable(who);
    }

    public void setEjectProgress(float progress) {
        if (ejectProgress == progress) return;
        ejectProgress = progress;
        invalidate();
    }

    @Override
    protected float getContentWidth(float maxWidth) {
        label.ellipsize(-1);
        final float available = Math.max(0, maxWidth - dp(16 + 4 + 13 + 2 + 8));
        label.ellipsize(Math.max(0, available - amount.getCurrentWidth()));
        amountWidth = amount.getCurrentWidth(Math.max(0, available - label.getWidth()));
        return label.getWidth() + dp(4 + 13 + 2 + 8) + amountWidth;
    }

    @Override
    protected void drawContent(Canvas canvas, float alpha) {
        final float cy = getHeight() / 2f;
        canvas.translate(0, -dp(20) * ejectProgress);
        label.draw(canvas, 0, cy, textColor, 0.85f * alpha);
        canvas.translate(label.getWidth() + dp(4), 0);
        final Drawable icon = hasProfileColor || isOnProfilePhoto() ? gramColorIcon : gramIcon;
        if (customGramIcon) icon.setColorFilter(textColor, PorterDuff.Mode.SRC_IN);
        final int size = dp(13);
        icon.setBounds(0, (int) cy - size / 2, size, (int) cy - size / 2 + size);
        icon.setAlpha((int) (255 * alpha));
        icon.draw(canvas);
        canvas.translate(size + dp(2), 0);
        amount.setBounds(0, 0, (int) Math.ceil(amountWidth), getHeight());
        amount.setTextColor(textColor);
        amount.setAlpha(Math.round(255 * alpha));
        amount.draw(canvas);
        canvas.translate(amountWidth + dpf2(4.8f), cy);
        chevronPaint.setColor(Theme.multAlpha(textColor, 0.85f * alpha));
        if (!chevronRenderer.draw(canvas, chevron, chevronPaint, CompactChevronRenderer.RIGHT)) {
            canvas.drawPath(chevron, chevronPaint);
        }
    }
}
