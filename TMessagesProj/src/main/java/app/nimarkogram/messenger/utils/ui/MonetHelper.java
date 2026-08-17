package app.nimarkogram.messenger.utils.ui;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.os.PatternMatcher;
import android.util.SparseIntArray;

import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.Theme;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RequiresApi(api = Build.VERSION_CODES.S)
public class MonetHelper {
    
    private static final double SMALL_TEXT_CONTRAST = 7.0;
    private static final Pattern PALETTE_TOKEN = Pattern.compile(
            "^([an])([1-3])_(0|10|50|[1-9]00|1000)(?:_([0-9]{1,3}))?$"
    );
    private static final Pattern ROLE_TOKEN = Pattern.compile(
            "^monet(Light|Dark)(Primary|OnPrimary|PrimaryContainer|OnPrimaryContainer|PrimaryPressed|PrimaryContainerPressed)(?:_([0-9]{1,3}))?$"
    );
    private static final int SUCCESS_LIGHT = 0xff188038;
    private static final int SUCCESS_DARK = 0xff81c995;

    @SuppressLint("NewApi")
    private static final SparseIntArray IDS = new SparseIntArray() {{
        put(1_1_0000, android.R.color.system_accent1_0);
        put(1_1_0010, android.R.color.system_accent1_10);
        put(1_1_0050, android.R.color.system_accent1_50);
        put(1_1_0100, android.R.color.system_accent1_100);
        put(1_1_0200, android.R.color.system_accent1_200);
        put(1_1_0300, android.R.color.system_accent1_300);
        put(1_1_0400, android.R.color.system_accent1_400);
        put(1_1_0500, android.R.color.system_accent1_500);
        put(1_1_0600, android.R.color.system_accent1_600);
        put(1_1_0700, android.R.color.system_accent1_700);
        put(1_1_0800, android.R.color.system_accent1_800);
        put(1_1_0900, android.R.color.system_accent1_900);
        put(1_1_1000, android.R.color.system_accent1_1000);
        put(1_2_0000, android.R.color.system_accent2_0);
        put(1_2_0010, android.R.color.system_accent2_10);
        put(1_2_0050, android.R.color.system_accent2_50);
        put(1_2_0100, android.R.color.system_accent2_100);
        put(1_2_0200, android.R.color.system_accent2_200);
        put(1_2_0300, android.R.color.system_accent2_300);
        put(1_2_0400, android.R.color.system_accent2_400);
        put(1_2_0500, android.R.color.system_accent2_500);
        put(1_2_0600, android.R.color.system_accent2_600);
        put(1_2_0700, android.R.color.system_accent2_700);
        put(1_2_0800, android.R.color.system_accent2_800);
        put(1_2_0900, android.R.color.system_accent2_900);
        put(1_2_1000, android.R.color.system_accent2_1000);
        put(1_3_0000, android.R.color.system_accent3_0);
        put(1_3_0010, android.R.color.system_accent3_10);
        put(1_3_0050, android.R.color.system_accent3_50);
        put(1_3_0100, android.R.color.system_accent3_100);
        put(1_3_0200, android.R.color.system_accent3_200);
        put(1_3_0300, android.R.color.system_accent3_300);
        put(1_3_0400, android.R.color.system_accent3_400);
        put(1_3_0500, android.R.color.system_accent3_500);
        put(1_3_0600, android.R.color.system_accent3_600);
        put(1_3_0700, android.R.color.system_accent3_700);
        put(1_3_0800, android.R.color.system_accent3_800);
        put(1_3_0900, android.R.color.system_accent3_900);
        put(1_3_1000, android.R.color.system_accent3_1000);
        put(2_1_0000, android.R.color.system_neutral1_0);
        put(2_1_0010, android.R.color.system_neutral1_10);
        put(2_1_0050, android.R.color.system_neutral1_50);
        put(2_1_0100, android.R.color.system_neutral1_100);
        put(2_1_0200, android.R.color.system_neutral1_200);
        put(2_1_0300, android.R.color.system_neutral1_300);
        put(2_1_0400, android.R.color.system_neutral1_400);
        put(2_1_0500, android.R.color.system_neutral1_500);
        put(2_1_0600, android.R.color.system_neutral1_600);
        put(2_1_0700, android.R.color.system_neutral1_700);
        put(2_1_0800, android.R.color.system_neutral1_800);
        put(2_1_0900, android.R.color.system_neutral1_900);
        put(2_1_1000, android.R.color.system_neutral1_1000);
        put(2_2_0000, android.R.color.system_neutral2_0);
        put(2_2_0010, android.R.color.system_neutral2_10);
        put(2_2_0050, android.R.color.system_neutral2_50);
        put(2_2_0100, android.R.color.system_neutral2_100);
        put(2_2_0200, android.R.color.system_neutral2_200);
        put(2_2_0300, android.R.color.system_neutral2_300);
        put(2_2_0400, android.R.color.system_neutral2_400);
        put(2_2_0500, android.R.color.system_neutral2_500);
        put(2_2_0600, android.R.color.system_neutral2_600);
        put(2_2_0700, android.R.color.system_neutral2_700);
        put(2_2_0800, android.R.color.system_neutral2_800);
        put(2_2_0900, android.R.color.system_neutral2_900);
        put(2_2_1000, android.R.color.system_neutral2_1000);
    }};
    private static final String ACTION_OVERLAY_CHANGED = "android.intent.action.OVERLAY_CHANGED";
    private static final OverlayChangeReceiver overlayChangeReceiver = new OverlayChangeReceiver();

    public static int getColor(String color) {
        return getColor(color, false);
    }

    public static boolean isMonetColorToken(String rawColor) {
        if (rawColor == null) {
            return false;
        }
        rawColor = rawColor.trim();
        Matcher matcher = PALETTE_TOKEN.matcher(rawColor);
        if (matcher.matches()) {
            if ("n".equals(matcher.group(1)) && "3".equals(matcher.group(2))) {
                return false;
            }
            if (matcher.group(4) != null) {
                try {
                    return Integer.parseInt(matcher.group(4)) <= 255;
                } catch (NumberFormatException ignore) {
                    return false;
                }
            }
            return true;
        }
        matcher = ROLE_TOKEN.matcher(rawColor);
        if (matcher.matches()) {
            if (matcher.group(3) != null) {
                try {
                    return Integer.parseInt(matcher.group(3)) <= 255;
                } catch (NumberFormatException ignore) {
                    return false;
                }
            }
            return true;
        }
        return "monetRed".equals(rawColor)
                || "monetRedLight".equals(rawColor)
                || "monetRedDark".equals(rawColor)
                || "monetRedCall".equals(rawColor)
                || "monetGreen".equals(rawColor)
                || "monetGreenCall".equals(rawColor);
    }

    public static int getColor(String rawColor, boolean amoled) {
        if (rawColor == null) {
            return 0;
        }
        rawColor = rawColor.trim();
        if (!isMonetColorToken(rawColor)) {
            return 0;
        }
        var context = ApplicationLoader.applicationContext;
        if (context == null) {
            return 0;
        }
        Matcher roleMatcher = ROLE_TOKEN.matcher(rawColor);
        if (roleMatcher.matches()) {
            boolean dark = "Dark".equals(roleMatcher.group(1));
            String role = roleMatcher.group(2);
            int alpha = -1;
            if (roleMatcher.group(3) != null) {
                try {
                    alpha = Integer.parseInt(roleMatcher.group(3));
                } catch (NumberFormatException ignore) {
                    return 0;
                }
            }
            try {
                int color;
                if ("PrimaryPressed".equals(role) || "PrimaryContainerPressed".equals(role)) {
                    boolean container = "PrimaryContainerPressed".equals(role);
                    int primary = context.getColor(getDynamicRoleResource(
                            dark, container ? "PrimaryContainer" : "Primary"));
                    int onPrimary = context.getColor(getDynamicRoleResource(
                            dark, container ? "OnPrimaryContainer" : "OnPrimary"));
                    color = ColorUtils.compositeColors(
                            ColorUtils.setAlphaComponent(onPrimary, 31), primary);
                } else {
                    color = context.getColor(getDynamicRoleResource(dark, role));
                }
                return alpha >= 0 ? ColorUtils.setAlphaComponent(color, alpha) : color;
            } catch (Throwable t) {
                FileLog.e(t);
                String fallback;
                if ("OnPrimary".equals(role)) {
                    fallback = dark ? "a1_800" : "a1_0";
                } else if ("PrimaryContainer".equals(role)) {
                    fallback = dark ? "a1_700" : "a1_100";
                } else if ("OnPrimaryContainer".equals(role)) {
                    fallback = dark ? "a1_100" : "a1_900";
                } else if ("PrimaryPressed".equals(role) || "PrimaryContainerPressed".equals(role)) {
                    boolean container = "PrimaryContainerPressed".equals(role);
                    int fallbackBase = getColor(dark
                            ? (container ? "a1_700" : "a1_200")
                            : (container ? "a1_100" : "a1_600"), amoled);
                    int fallbackOnBase = getColor(dark
                            ? (container ? "a1_100" : "a1_800")
                            : (container ? "a1_900" : "a1_0"), amoled);
                    int color = ColorUtils.compositeColors(
                            ColorUtils.setAlphaComponent(fallbackOnBase, 31), fallbackBase);
                    return alpha >= 0 ? ColorUtils.setAlphaComponent(color, alpha) : color;
                } else {
                    fallback = dark ? "a1_200" : "a1_600";
                }
                int color = getColor(fallback, amoled);
                return alpha >= 0 ? ColorUtils.setAlphaComponent(color, alpha) : color;
            }
        }
        boolean monetRed = "monetRed".equals(rawColor)
                || "monetRedLight".equals(rawColor)
                || "monetRedDark".equals(rawColor)
                || "monetRedCall".equals(rawColor);
        boolean monetGreen = "monetGreen".equals(rawColor)
                || "monetGreenCall".equals(rawColor);
        if (monetRed || monetGreen) {
            try {
                if (monetRed) {
                    boolean dark = "monetRedDark".equals(rawColor) || "monetRedCall".equals(rawColor);
                    int id = dark
                            ? com.google.android.material.R.color.m3_sys_color_dynamic_dark_error
                            : com.google.android.material.R.color.m3_sys_color_dynamic_light_error;
                    return context.getColor(id);
                }
                return "monetGreenCall".equals(rawColor) ? SUCCESS_DARK : SUCCESS_LIGHT;
            } catch (Throwable t) {
                FileLog.e(t);
                return 0;
            }
        }
        
        Matcher matcher = PALETTE_TOKEN.matcher(rawColor);
        matcher.matches();
        int group = "n".equals(matcher.group(1)) ? 2 : 1;
        int palette;
        int shade;
        int alpha = -1;
        try {
            palette = Integer.parseInt(matcher.group(2));
            shade = Integer.parseInt(matcher.group(3));
            if (matcher.group(4) != null) alpha = Integer.parseInt(matcher.group(4));
        } catch (NumberFormatException ignore) {
            return 0;
        }
        if ((group == 2 && palette > 2) || alpha > 255) {
            return 0;
        }
        if (amoled && group == 2 && palette == 1 && shade == 900) {
            shade = 1000;
        }
        var key = group * 1_0_0000 + palette * 1_0000 + shade;
        var id = IDS.get(key);
        if (id == 0) {
            return 0;
        }
        try {
            var color = context.getColor(id);
            if (alpha != -1) {
                color = ColorUtils.setAlphaComponent(color, alpha);
            }
            return color;
        } catch (Throwable t) {
            FileLog.e(t);
            return 0;
        }
    }

    private static int getDynamicRoleResource(boolean dark, String role) {
        switch (role) {
            case "Primary":
                return dark
                        ? com.google.android.material.R.color.m3_sys_color_dynamic_dark_primary
                        : com.google.android.material.R.color.m3_sys_color_dynamic_light_primary;
            case "OnPrimary":
                return dark
                        ? com.google.android.material.R.color.m3_sys_color_dynamic_dark_on_primary
                        : com.google.android.material.R.color.m3_sys_color_dynamic_light_on_primary;
            case "PrimaryContainer":
                return dark
                        ? com.google.android.material.R.color.m3_sys_color_dynamic_dark_primary_container
                        : com.google.android.material.R.color.m3_sys_color_dynamic_light_primary_container;
            case "OnPrimaryContainer":
                return dark
                        ? com.google.android.material.R.color.m3_sys_color_dynamic_dark_on_primary_container
                        : com.google.android.material.R.color.m3_sys_color_dynamic_light_on_primary_container;
            default:
                throw new IllegalArgumentException("Unknown Monet role: " + role);
        }
    }

    public static boolean isActiveMonetTheme() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && Theme.getActiveTheme() != null
                && Theme.getActiveTheme().isMonet();
    }

    private static int getActiveRoleColor(String lightRole, String darkRole, int original) {
        if (!isActiveMonetTheme()) {
            return original;
        }
        int color = getColor(Theme.getActiveTheme().isDark() ? darkRole : lightRole);
        return color != 0 ? color : original;
    }

    public static int getPrimaryColor(int original) {
        return getActiveRoleColor("monetLightPrimary", "monetDarkPrimary", original);
    }

    public static int getOnPrimaryColor(int original) {
        return getActiveRoleColor("monetLightOnPrimary", "monetDarkOnPrimary", original);
    }

    public static int getPrimaryContainerColor(int original) {
        return getActiveRoleColor(
                "monetLightPrimaryContainer", "monetDarkPrimaryContainer", original);
    }

    public static int getOnPrimaryContainerColor(int original) {
        return getActiveRoleColor(
                "monetLightOnPrimaryContainer", "monetDarkOnPrimaryContainer", original);
    }

    public static int ensureReadableForeground(int foreground, int background) {
        if (!isActiveMonetTheme()) {
            return foreground;
        }

        final boolean dark = Theme.getActiveTheme().isDark();
        int surface = dark ? getColor("n1_1000") : getColor("n1_0");
        if (surface == 0) {
            surface = dark ? Color.BLACK : Color.WHITE;
        }
        surface = ColorUtils.setAlphaComponent(surface, 255);

        final int opaqueBackground = Color.alpha(background) == 255
                ? background
                : ColorUtils.compositeColors(background, surface);
        final int visibleForeground = Color.alpha(foreground) == 255
                ? foreground
                : ColorUtils.compositeColors(foreground, opaqueBackground);
        if (ColorUtils.calculateContrast(visibleForeground, opaqueBackground)
                >= SMALL_TEXT_CONTRAST) {
            return foreground;
        }

        int light = getColor("n1_0");
        int darkNeutral = getColor("n1_1000");
        if (light == 0) {
            light = Color.WHITE;
        }
        if (darkNeutral == 0) {
            darkNeutral = Color.BLACK;
        }
        light = ColorUtils.setAlphaComponent(light, 255);
        darkNeutral = ColorUtils.setAlphaComponent(darkNeutral, 255);

        return ColorUtils.calculateContrast(light, opaqueBackground)
                >= ColorUtils.calculateContrast(darkNeutral, opaqueBackground)
                ? light
                : darkNeutral;
    }

    public static int getSemanticButtonForeground(int original) {
        if (!isActiveMonetTheme()) {
            return original;
        }
        int light = getColor("n1_0");
        return light != 0 ? ColorUtils.setAlphaComponent(light, 255) : Color.WHITE;
    }

    public static int ensureSemanticButtonBackground(int background, int foreground) {
        if (!isActiveMonetTheme()) {
            return background;
        }
        background = ColorUtils.setAlphaComponent(background, 255);
        foreground = ColorUtils.setAlphaComponent(foreground, 255);
        if (ColorUtils.calculateContrast(foreground, background) >= 4.5) {
            return background;
        }

        float low = 0f;
        float high = 1f;
        for (int i = 0; i < 10; i++) {
            float amount = (low + high) * 0.5f;
            int candidate = ColorUtils.blendARGB(background, Color.BLACK, amount);
            if (ColorUtils.calculateContrast(foreground, candidate) >= 4.5) {
                high = amount;
            } else {
                low = amount;
            }
        }
        return ColorUtils.blendARGB(background, Color.BLACK, high);
    }

    private static class OverlayChangeReceiver extends BroadcastReceiver {

        public void register(Context context) {
            IntentFilter packageFilter = new IntentFilter(ACTION_OVERLAY_CHANGED);
            packageFilter.addDataScheme("package");
            packageFilter.addDataSchemeSpecificPart("android", PatternMatcher.PATTERN_LITERAL);
            
            ContextCompat.registerReceiver(context, this, packageFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
        }

        public void unregister(Context context) {
            context.unregisterReceiver(this);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_OVERLAY_CHANGED.equals(intent.getAction())) {
                if (Theme.getActiveTheme().isMonet()) {
                    Theme.applyTheme(Theme.getActiveTheme(), Theme.isCurrentThemeNight());
                }
            }
        }
    }

    public static int getSettingsIconBackgroundColor(int original) {
        return getPrimaryContainerColor(original);
    }

    public static int getSettingsIconForegroundColor(int original) {
        return getOnPrimaryContainerColor(original);
    }

    public static void registerReceiver(Context context) {
        overlayChangeReceiver.register(context);
    }

    public static void unregisterReceiver(Context context) {
        try {
            overlayChangeReceiver.unregister(context);
        } catch (IllegalArgumentException e) {
            FileLog.e(e);
        }
    }
}
