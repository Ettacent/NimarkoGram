 
package app.nimarkogram.messenger.ui;

import android.view.HapticFeedbackConstants;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.nimarkogram.messenger.NimarkoConfig;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;

public final class MessageMenuTweaks {

    private MessageMenuTweaks() {}

    public static void filterMenuItems(ArrayList<CharSequence> items,
                                       ArrayList<Integer> options,
                                       ArrayList<Integer> icons) {
        if (items == null || options == null || icons == null) return;
        
        for (int i = options.size() - 1; i >= 0; i--) {
            Integer optBoxed = options.get(i);
            if (optBoxed == null) continue;
            boolean drop = false;
            switch (optBoxed) {
                case ChatActivity.OPTION_REPLY:
                    drop = !NimarkoConfig.showReply;
                    break;
                case ChatActivity.OPTION_SAVE_TO_GALLERY:
                case ChatActivity.OPTION_SAVE_TO_GALLERY2:
                    drop = !NimarkoConfig.showSaveToGallery;
                    break;
                case ChatActivity.OPTION_SAVE_TO_DOWNLOADS_OR_MUSIC:
                    drop = !NimarkoConfig.showSaveToDownloads;
                    break;
                case ChatActivity.OPTION_SHARE:
                    drop = !NimarkoConfig.showShare;
                    break;
                case ChatActivity.OPTION_FORWARD:
                    drop = !NimarkoConfig.showForward;
                    break;
                default:
                    
                    break;
            }
            if (drop) {
                options.remove(i);
                if (i < items.size()) items.remove(i);
                if (i < icons.size()) icons.remove(i);
            }
        }

        reorderMenuItems(items, options, icons);
    }

    public static void reorderMenuItems(ArrayList<CharSequence> items,
                                        ArrayList<Integer> options,
                                        ArrayList<Integer> icons) {
        List<Integer> order = NimarkoConfig.messageMenuOrder;
        if (order == null || order.isEmpty()) return;
        if (items == null || options == null || icons == null) return;
        if (options.size() != items.size() || options.size() != icons.size()) return;
        int n = options.size();
        if (n <= 1) return;

        Map<Integer, Integer> byOpt = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) byOpt.put(normalizeOption(options.get(i)), i);

        boolean[] taken = new boolean[n];
        ArrayList<CharSequence> outItems = new ArrayList<>(n);
        ArrayList<Integer> outOptions = new ArrayList<>(n);
        ArrayList<Integer> outIcons = new ArrayList<>(n);

        for (Integer requestedOpt : order) {
            Integer idx = byOpt.get(normalizeOption(requestedOpt));
            if (idx == null || taken[idx]) continue;
            taken[idx] = true;
            outItems.add(items.get(idx));
            outOptions.add(options.get(idx));
            outIcons.add(icons.get(idx));
        }
        
        for (int i = 0; i < n; i++) {
            if (taken[i]) continue;
            outItems.add(items.get(i));
            outOptions.add(options.get(i));
            outIcons.add(icons.get(i));
        }
        items.clear(); items.addAll(outItems);
        options.clear(); options.addAll(outOptions);
        icons.clear(); icons.addAll(outIcons);
    }

    private static Integer normalizeOption(Integer opt) {
        if (opt != null && opt == ChatActivity.OPTION_SAVE_TO_GALLERY2) {
            return ChatActivity.OPTION_SAVE_TO_GALLERY;
        }
        return opt;
    }

    public static void playHaptic(View view) {
        if (view == null) return;
        if (!NimarkoConfig.messageMenuHaptic) return;
        try {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        } catch (Throwable ignored) {}
    }

    public static boolean applyAfterRowsAdded(
            ActionBarPopupWindow.ActionBarPopupWindowLayout layout,
            long dialogId,
            ActionBarMenuSubItem[] rows,
            List<Integer> options,
            Theme.ResourcesProvider resourcesProvider
    ) {
        try {
            return MessageMenuCompactView.install(
                    layout,
                    dialogId,
                    rows,
                    options,
                    resourcesProvider
            );
        } catch (Throwable ignored) {
            
            return false;
        }
    }
}
