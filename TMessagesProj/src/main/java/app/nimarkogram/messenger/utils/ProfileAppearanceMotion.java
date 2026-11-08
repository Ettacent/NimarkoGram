/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.utils;

import android.animation.ValueAnimator;
import android.os.Build;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.SharedConfig;

public final class ProfileAppearanceMotion {
    private ProfileAppearanceMotion() {
    }

    public static boolean isEnabled() {
        return SharedConfig.animationsEnabled()
                && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? ValueAnimator.areAnimatorsEnabled() : AndroidUtilities.getAnimatorDurationScale() > 0f);
    }
}
