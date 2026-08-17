 
package app.nimarkogram.messenger.utils.folders;

import android.content.Context;
import android.graphics.RectF;
import android.os.Build;
import android.view.ViewGroup;

import androidx.annotation.RequiresApi;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.telegram.messenger.MessagesController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.FilterTabsView;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.DownscaleScrollableNoiseSuppressor;
import org.telegram.ui.Components.blur3.capture.IBlur3Capture;
import org.telegram.ui.DialogsActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.nimarkogram.messenger.NimarkoConfig;

public final class NimarkoFoldersHelper {

    private NimarkoFoldersHelper() {
    }

    public static boolean moveFoldersToBottom() {
        return false;
    }

    public static int getFloatingButtonsOffset(FilterTabsView filterTabsView) {
        return 0;
    }

    public static void setupFilterTabs(
            Context context,
            ViewGroup contentView,
            FilterTabsView filterTabsView,
            Theme.ResourcesProvider resourceProvider,
            BlurredBackgroundDrawableViewFactory iBlur3FactoryLiquidGlass,
            BlurredBackgroundDrawableViewFactory iBlur3FactoryFade,
            boolean inForwardMode
    ) {
    }

    @RequiresApi(Build.VERSION_CODES.S)
    public static void blur3_InvalidateBlur(
            DialogsActivity dialogsActivity,
            IBlur3Capture iBlur3Capture,
            RectF iBlur3PositionActionBar,
            RectF iBlur3PositionFolders,
            RectF iBlur3PositionMainTabs,
            ArrayList<RectF> iBlur3Positions,
            DownscaleScrollableNoiseSuppressor scrollableViewNoiseSuppressor
    ) {
    }

    public static void updateFoldersOffset(
            DialogsActivity dialogsActivity,
            boolean inForwardMode
    ) {
    }

    private static final Gson GSON = new Gson();
     
    public static final int COLOR_UNSET = 0;

    public static int getFolderColor(int folderId, int defaultColor) {
        Map<Integer, Integer> colors = NimarkoConfig.folderColors;
        if (colors == null) return defaultColor;
        Integer v = colors.get(folderId);
        return (v == null || v == COLOR_UNSET) ? defaultColor : v;
    }

    public static void setFolderColor(int folderId, int color) {
        if (NimarkoConfig.folderColors == null) {
            NimarkoConfig.folderColors = new HashMap<>();
        }
        if (color == COLOR_UNSET) {
            NimarkoConfig.folderColors.remove(folderId);
        } else {
            NimarkoConfig.folderColors.put(folderId, color);
        }
        NimarkoConfig.saveFolderColors();
    }

    public static boolean hasFolderColor(int folderId) {
        Map<Integer, Integer> colors = NimarkoConfig.folderColors;
        if (colors == null) return false;
        Integer v = colors.get(folderId);
        return v != null && v != COLOR_UNSET;
    }

    public static int getFolderBadgeMode(int folderId) {
        Map<Integer, Integer> modes = NimarkoConfig.folderBadgeMode;
        if (modes == null) return NimarkoConfig.FOLDER_BADGE_NUMBER;
        Integer v = modes.get(folderId);
        if (v == null) return NimarkoConfig.FOLDER_BADGE_NUMBER;
        
        if (v < NimarkoConfig.FOLDER_BADGE_NUMBER || v > NimarkoConfig.FOLDER_BADGE_HIDDEN) {
            return NimarkoConfig.FOLDER_BADGE_NUMBER;
        }
        return v;
    }

    public static void setFolderBadgeMode(int folderId, int mode) {
        if (NimarkoConfig.folderBadgeMode == null) {
            NimarkoConfig.folderBadgeMode = new HashMap<>();
        }
        if (mode == NimarkoConfig.FOLDER_BADGE_NUMBER) {
            
            NimarkoConfig.folderBadgeMode.remove(folderId);
        } else {
            NimarkoConfig.folderBadgeMode.put(folderId, mode);
        }
        NimarkoConfig.saveFolderBadgeMode();
    }

    public static int filterTabCounter(int folderId, int rawCount) {
        if (NimarkoConfig.tabsNoUnread) return 0;
        int mode = getFolderBadgeMode(folderId);
        if (mode == NimarkoConfig.FOLDER_BADGE_HIDDEN) return 0;
        if (mode == NimarkoConfig.FOLDER_BADGE_DOT) return 0; 
        return rawCount;
    }

    public static boolean shouldShowDot(int folderId, int rawCount) {
        if (NimarkoConfig.tabsNoUnread) return false;
        if (rawCount <= 0) return false;
        return getFolderBadgeMode(folderId) == NimarkoConfig.FOLDER_BADGE_DOT;
    }

    public static int nextFolderId(int accountNum, int currentId, int direction) {
        if (!NimarkoConfig.folderSwipeEnabled) return currentId;
        if (direction == 0) return currentId;
        List<MessagesController.DialogFilter> filters =
                MessagesController.getInstance(accountNum).getDialogFilters();
        if (filters == null || filters.isEmpty()) return currentId;
        int n = filters.size();
        
        int idx = -1;
        for (int i = 0; i < n; i++) {
            if (filters.get(i).id == currentId || i == currentId) {
                idx = i; break;
            }
        }
        if (idx < 0) return currentId;
        int target = idx + (direction > 0 ? 1 : -1);
        if (target < 0 || target >= n) return currentId; 
        return target;
    }

    public static Map<String, List<Integer>> getFolderGroups() {
        String json = NimarkoConfig.folderGroupsJson;
        if (json == null || json.isEmpty()) return new LinkedHashMap<>();
        try {
            Map<String, List<Integer>> m = GSON.fromJson(json,
                    new TypeToken<LinkedHashMap<String, List<Integer>>>() {}.getType());
            return m != null ? m : new LinkedHashMap<>();
        } catch (Throwable ignored) {
            return new LinkedHashMap<>();
        }
    }

    public static void setFolderGroups(Map<String, List<Integer>> groups) {
        NimarkoConfig.setFolderGroupsJson(
                GSON.toJson(groups == null ? Collections.emptyMap() : groups));
    }

    public static void addFolderToGroup(String groupName, int folderId) {
        if (groupName == null || groupName.isEmpty()) return;
        Map<String, List<Integer>> groups = getFolderGroups();
        List<Integer> ids = groups.get(groupName);
        if (ids == null) {
            ids = new ArrayList<>();
            groups.put(groupName, ids);
        }
        if (!ids.contains(folderId)) ids.add(folderId);
        setFolderGroups(groups);
    }

    public static void removeFolderFromGroup(String groupName, int folderId) {
        if (groupName == null || groupName.isEmpty()) return;
        Map<String, List<Integer>> groups = getFolderGroups();
        List<Integer> ids = groups.get(groupName);
        if (ids == null) return;
        ids.remove(Integer.valueOf(folderId));
        if (ids.isEmpty()) groups.remove(groupName);
        setFolderGroups(groups);
    }

    public static boolean isInGroup(int folderId) {
        Map<String, List<Integer>> groups = getFolderGroups();
        for (List<Integer> ids : groups.values()) {
            if (ids != null && ids.contains(folderId)) return true;
        }
        return false;
    }
}
