/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons;

import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;

import app.nimarkogram.messenger.NimarkoConfig;

public final class NimarkoActivityIconResources {
    private Resources baseResources;
    private AssetManager assets;
    private Configuration configuration;
    private DisplayMetrics metrics;
    private NimarkoIconResources resources;
    private boolean updating;
    private boolean hasPresentedSelection;
    private int presentedSelection;

    public synchronized boolean onResume(Resources installed) {
        if (!(installed instanceof NimarkoIconResources)) {
            return false;
        }
        int selection = ((NimarkoIconResources) installed).getAppliedSelection();
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
            if (base instanceof NimarkoIconResources) {
                NimarkoIconResources wrapped = (NimarkoIconResources) base;
                if (wrapped.getAppliedSelection() != NimarkoConfig.iconReplacement) {
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
                NimarkoIconResources nextResources = new NimarkoIconResources(base);
                baseResources = base;
                assets = currentAssets;
                configuration = nextConfiguration;
                metrics = nextMetrics;
                resources = nextResources;
            }
            if (resources.getAppliedSelection() != NimarkoConfig.iconReplacement) {
                resources.reloadReplacements();
            }
            return resources;
        } finally {
            updating = false;
        }
    }
}
