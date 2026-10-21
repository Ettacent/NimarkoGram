/* Modifications Copyright (C) 2026 Ettacent */

/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import app.nimarkogram.messenger.utils.ui.SystemTextPaint;
import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.icons.NimarkoIconResources;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

public class TimerDrawable extends Drawable {

    private TextPaint timePaint = new SystemTextPaint(Paint.ANTI_ALIAS_FLAG);
    private Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private StaticLayout timeLayout;
    private float timeWidth = 0;
    private int timeHeight = 0;
    private int time = -1;
    private Drawable currentTtlIcon;
    private int iconColor;
    private boolean iconColorSet;
    private int currentTtlIconId;
    private Resources iconResources;
    private Resources.Theme iconTheme;
    private Configuration iconConfiguration;
    private long iconGeneration;
    private int iconDensityDpi;
    private float iconDensity;
    private float layoutDensity;
    Context context;
    Theme.ResourcesProvider resourcesProvider;
    private boolean overrideColor;
    private Integer foregroundColorOverride;
    private boolean isStaticIcon;
    private boolean isDialog;

    public TimerDrawable(Context context, Theme.ResourcesProvider resourcesProvider) {
        this.context = context;
        this.resourcesProvider = resourcesProvider;
        timePaint.setTypeface(AndroidUtilities.getTypeface("fonts/rcondensedbold.ttf"));

        linePaint.setStrokeWidth(AndroidUtilities.dp(1));
        linePaint.setStyle(Paint.Style.STROKE);
    }

    public void setTime(int value) {
        if (time == -1 && value == -1) {
            return;
        }
        boolean changed = time != value;
        if (changed) {
            time = value;

        }
        refreshIcon(changed);
    }

    private void refreshIcon(boolean timeChanged) {
        Resources resources = context.getResources();
        Resources.Theme theme = context.getTheme();
        NimarkoIconResources packResources = resources instanceof NimarkoIconResources
                ? (NimarkoIconResources) resources : null;
        long generation = packResources == null ? 0 : packResources.getReplacementGeneration();
        int densityDpi = resources.getDisplayMetrics().densityDpi;
        float density = resources.getDisplayMetrics().density;
        int id = isDialog ? R.drawable.msg_autodelete_badge2
                : time == 0 ? R.drawable.msg_mini_autodelete : R.drawable.msg_mini_autodelete_empty;
        boolean environmentChanged = iconResources != resources || iconTheme != theme
                || iconGeneration != generation || iconDensityDpi != densityDpi
                || iconDensity != density || layoutDensity != AndroidUtilities.density
                || iconConfiguration == null || !iconConfiguration.equals(resources.getConfiguration());
        if (environmentChanged || currentTtlIconId != id || currentTtlIcon == null) {
            int selection = packResources == null ? NimarkoConfig.ICON_REPLACE_NONE : packResources.getAppliedSelection();
            currentTtlIcon = id != R.drawable.msg_mini_autodelete
                    ? AutoDeleteTimerDrawable.create(context, selection, isDialog ? AndroidUtilities.dp(22) : AndroidUtilities.dp(21)) : null;
            if (currentTtlIcon == null) {
                currentTtlIcon = ContextCompat.getDrawable(context, id).mutate();
            }
            if (!isDialog) {
                currentTtlIcon.setColorFilter(currentColorFilter);
            }
            iconColorSet = false;
            currentTtlIconId = id;
            iconResources = resources;
            iconTheme = theme;
            iconGeneration = generation;
            iconDensityDpi = densityDpi;
            iconDensity = density;
            layoutDensity = AndroidUtilities.density;
            iconConfiguration = new Configuration(resources.getConfiguration());
            invalidateSelf();
        }
        if (timeChanged || environmentChanged) {
            updateTimeLayout();
        }
    }

    private void updateTimeLayout() {
        int value = time;

            String timeString;
            if (time >= 1 && time < 60) {
                timeString = "" + value;
                if (timeString.length() < 2) {
                    timeString += LocaleController.getString(R.string.SecretChatTimerSeconds);
                }
            } else if (time >= 60 && time < 60 * 60) {
                timeString = "" + value / 60;
                if (timeString.length() < 2) {
                    timeString += LocaleController.getString(R.string.SecretChatTimerMinutes);
                }
            } else if (time >= 60 * 60 && time < 60 * 60 * 24) {
                timeString = "" + value / 60 / 60;
                if (timeString.length() < 2) {
                    timeString += LocaleController.getString(R.string.SecretChatTimerHours);
                }
            } else if (time >= 60 * 60 * 24 && time < 60 * 60 * 24 * 7) {
                timeString = "" + value / 60 / 60 / 24;
                if (timeString.length() < 2) {
                    timeString += LocaleController.getString(R.string.SecretChatTimerDays);
                }
            } else if (time < 60 * 60 * 24 * 31) {
                timeString = "" + value / 60 / 60 / 24 / 7;
                if (timeString.length() < 2) {
                    timeString += LocaleController.getString(R.string.SecretChatTimerWeeks);
                } else if (timeString.length() > 2) {
                    timeString = "c";
                }
            } else if (time < 60 * 60 * 24 * 364){
                timeString = "" + value / 60 / 60 / 24 / 30;
                if (timeString.length() < 2) {
                    timeString += LocaleController.getString(R.string.SecretChatTimerMonths);
                }
            } else {
                timeString = "" + value / 60 / 60 / 24 / 364;
                if (timeString.length() < 2) {
                    timeString += LocaleController.getString(R.string.SecretChatTimerYears);
                }
            }

            timePaint.setTextSize(AndroidUtilities.dp(11));
            timeWidth = timePaint.measureText(timeString);
            if (timeWidth > AndroidUtilities.dp(13)) {
                timePaint.setTextSize(AndroidUtilities.dp(9));
                timeWidth = timePaint.measureText(timeString);
            }
            if (timeWidth > AndroidUtilities.dp(13)) {
                timePaint.setTextSize(AndroidUtilities.dp(6));
                timeWidth = timePaint.measureText(timeString);
            }
            try {
                timeLayout = new StaticLayout(timeString, timePaint, (int) Math.ceil(timeWidth), Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false);
                timeHeight = timeLayout.getHeight();
            } catch (Exception e) {
                timeLayout = null;
                FileLog.e(e);
            }

            invalidateSelf();
        }

    public static TimerDrawable getTtlIcon(int ttl) {
        TimerDrawable timerDrawable = new TimerDrawable(ApplicationLoader.applicationContext, null);
        timerDrawable.setTime(ttl);
        timerDrawable.isStaticIcon = true;
        return timerDrawable;
    }


    public static TimerDrawable getTtlIconForDialogs(int ttl) {
        TimerDrawable timerDrawable = new TimerDrawable(ApplicationLoader.applicationContext, null);
        timerDrawable.isDialog = true;
        timerDrawable.setTime(ttl);
        return timerDrawable;
    }
    @Override
    public void draw(@NonNull Canvas canvas) {
        if (time != -1) {
            refreshIcon(false);
        }
        int width = getIntrinsicWidth();
        int height = getIntrinsicHeight();

        if (isDialog) {
            timePaint.setColor(Color.WHITE);
        } else if (!isStaticIcon) {
            if (!overrideColor) {
                paint.setColor(Theme.getColor(Theme.key_actionBarDefault, resourcesProvider));
            }
            timePaint.setColor(getForegroundColor());
        } else {
            timePaint.setColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuItemIcon, resourcesProvider));
        }

        if (currentTtlIcon != null) {
            if (!isStaticIcon && !isDialog) {
                canvas.drawCircle(getBounds().centerX(), getBounds().centerY(), getBounds().width() / 2f, paint);
                int iconColor = getForegroundColor();
                if (!iconColorSet || this.iconColor != iconColor) {
                    this.iconColor = iconColor;
                    iconColorSet = true;
                    currentTtlIcon.setColorFilter(new PorterDuffColorFilter(iconColor, PorterDuff.Mode.MULTIPLY));
                }
            } else if (isStaticIcon && currentTtlIcon instanceof AutoDeleteTimerDrawable) {
                int color = timePaint.getColor();
                if (!iconColorSet || iconColor != color) {
                    iconColor = color;
                    iconColorSet = true;
                    currentTtlIcon.setColorFilter(currentColorFilter != null ? currentColorFilter
                            : new PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY));
                }
            }
            if (isDialog) {
                currentTtlIcon.setBounds(getBounds().left, getBounds().top, getBounds().left + currentTtlIcon.getIntrinsicWidth(),  getBounds().top + currentTtlIcon.getIntrinsicHeight());
                currentTtlIcon.draw(canvas);
            } else {
                AndroidUtilities.rectTmp2.set((int) (getBounds().centerX() - AndroidUtilities.dp(10.5f)), (int) (getBounds().centerY() - AndroidUtilities.dp(10.5f)),
                        (int) (getBounds().centerX() - AndroidUtilities.dp(10.5f)) + currentTtlIcon.getIntrinsicWidth(),
                        (int) (getBounds().centerY() - AndroidUtilities.dp(10.5f)) + currentTtlIcon.getIntrinsicHeight());
                currentTtlIcon.setBounds(AndroidUtilities.rectTmp2);
                currentTtlIcon.draw(canvas);
            }
        }
        if (time != 0) {
            if (timeLayout != null) {
                int xOffxet = 0;
                if (AndroidUtilities.density == 3) {
                    xOffxet = -1;
                }
                canvas.save();
                if (isDialog) {
                    canvas.translate((float) ((getBounds().width() / 2 - Math.ceil(timeWidth / 2)) + xOffxet), (getBounds().height() - timeHeight) / 2f);
                    timeLayout.draw(canvas);
                } else {
                    canvas.translate((int) (width / 2 - Math.ceil(timeWidth / 2)) + xOffxet, (height - timeHeight) / 2f);
                    timeLayout.draw(canvas);
                }
                canvas.restore();

            }
        }
    }

    @Override
    public void setAlpha(int alpha) {

    }

    ColorFilter currentColorFilter;
    @Override
    public void setColorFilter(ColorFilter cf) {
        currentColorFilter = cf;
        if (isStaticIcon && currentTtlIcon != null) {
            currentTtlIcon.setColorFilter(cf);
            iconColorSet = false;
            if (currentTtlIcon instanceof AutoDeleteTimerDrawable) {
                invalidateSelf();
            }
        }
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSPARENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return AndroidUtilities.dp(23);
    }

    @Override
    public int getIntrinsicHeight() {
        return AndroidUtilities.dp(23);
    }

    public void setBackgroundColor(int currentActionBarColor) {
        overrideColor = true;
        paint.setColor(currentActionBarColor);
        invalidateSelf();
    }
    public int getBackgroundColor() {
        return overrideColor ? paint.getColor()
                : Theme.getColor(Theme.key_actionBarDefault, resourcesProvider);
    }
    public int getForegroundColor() {
        return foregroundColorOverride != null ? foregroundColorOverride
                : Theme.getColor(Theme.key_actionBarDefaultTitle, resourcesProvider);
    }
    public void setForegroundColor(int color) {
        foregroundColorOverride = color;
        invalidateSelf();
    }

    public int getTime() {
        return time;
    }
}
