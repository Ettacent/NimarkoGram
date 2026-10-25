/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons;

import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;

import app.nimarkogram.messenger.AppConfig;

public final class ActivityIconResources {
    private Resources baseResources;
    private AssetManager assets;
    private Configuration configuration;
    private DisplayMetrics metrics;
    private IconResources resources;
    private boolean updating;
    private boolean hasPresentedSelection;
    private int presentedSelection;

    public synchronized boolean onResume(Resources installed) {
        if (!(installed instanceof IconResources)) {
            return false;
        }
        int selection = ((IconResources) installed).getAppliedSelection();
        boolean changed = hasPresentedSelection && presentedSelection != selection;
        presentedSelection = selection;
        hasPresentedSelection = true;
        return changed;
    }

    public synchronized Resources getResources(Resources base) {
        if (base == null || updating) {
            return base;
        }
        updating = true;
        try {
            if (base instanceof IconResources) {
                IconResources wrapped = (IconResources) base;
                if (wrapped.getAppliedSelection() != AppConfig.iconReplacement) {
                    wrapped.reloadReplacements();
                }
                return wrapped;
            }
            AssetManager currentAssets = base.getAssets();
            Configuration currentConfiguration = base.getConfiguration();
            DisplayMetrics currentMetrics = base.getDisplayMetrics();
            if (resources == null || baseResources != base || assets != currentAssets
                    || !configuration.equals(currentConfiguration) || !metrics.equals(currentMetrics)) {
                Configuration nextConfiguration = new Configuration(currentConfiguration);
                DisplayMetrics nextMetrics = new DisplayMetrics();
                nextMetrics.setTo(currentMetrics);
                IconResources nextResources = new IconResources(base);
                baseResources = base;
                assets = currentAssets;
                configuration = nextConfiguration;
                metrics = nextMetrics;
                resources = nextResources;
            }
            if (resources.getAppliedSelection() != AppConfig.iconReplacement) {
                resources.reloadReplacements();
            }
            return resources;
        } finally {
            updating = false;
        }
    }
}
