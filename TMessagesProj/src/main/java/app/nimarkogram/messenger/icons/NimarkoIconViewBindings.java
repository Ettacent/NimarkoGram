/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

public final class NimarkoIconViewBindings {
    private final WeakHashMap<ImageView, Integer> images = new WeakHashMap<>();
    private final WeakHashMap<TextView, Integer> leftIcons = new WeakHashMap<>();
    private Resources lastResources;
    private long lastGeneration;
    private int lastDensity;
    private Configuration lastConfiguration;

    public void bind(ImageView view, int resourceId) {
        images.put(view, resourceId);
        view.setImageDrawable(view.getResources().getDrawable(resourceId).mutate());
    }

    public void bindLeft(TextView view, int resourceId) {
        leftIcons.put(view, resourceId);
        setLeft(view, view.getResources(), resourceId);
    }

    private static void setLeft(TextView view, Resources resources, int resourceId) {
        Drawable[] current = view.getCompoundDrawables();
        Drawable icon = resources.getDrawable(resourceId).mutate();
        icon.setBounds(0, 0, Math.max(0, icon.getIntrinsicWidth()), Math.max(0, icon.getIntrinsicHeight()));
        view.setCompoundDrawables(icon, current[1], current[2], current[3]);
    }

    public boolean refresh(Resources resources) {
        long generation = resources instanceof NimarkoIconResources
                ? ((NimarkoIconResources) resources).getReplacementGeneration() : -1L;
        int density = resources.getDisplayMetrics().densityDpi;
        Configuration configuration = resources.getConfiguration();
        if (lastResources == resources && lastGeneration == generation && lastDensity == density
                && configuration.equals(lastConfiguration)) {
            return false;
        }
        for (Map.Entry<ImageView, Integer> entry : new ArrayList<>(images.entrySet())) {
            ImageView view = entry.getKey();
            if (view != null) {
                view.setImageDrawable(resources.getDrawable(entry.getValue()).mutate());
            }
        }
        for (Map.Entry<TextView, Integer> entry : new ArrayList<>(leftIcons.entrySet())) {
            TextView view = entry.getKey();
            if (view != null) {
                setLeft(view, resources, entry.getValue());
            }
        }
        lastResources = resources;
        lastGeneration = generation;
        lastDensity = density;
        lastConfiguration = new Configuration(configuration);
        return true;
    }

    public void clear() {
        images.clear();
        leftIcons.clear();
        lastResources = null;
        lastConfiguration = null;
    }
}
