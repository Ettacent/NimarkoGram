/**
 * This is the source code of NimarkoGram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * Verbatim port of Cherrygram's editor preview cell (p000.KO from the
 * CG-10.7.0 deobfuscated source, originally
 * uz.unnarsx.cherrygram.preferences.tabs.MainTabsPreviewCell).
 *
 * CG-parity behaviour:
 *   - ViewGroup that lays out one tab tile per entry in the source list
 *   - Long-press on a tile starts an Android DnD drag with the standard
 *     View.DragShadowBuilder and ClipData carrying the source index
 *   - onDragEvent:
 *       ACTION_DRAG_STARTED -> hide the source tile (visibility = INVISIBLE)
 *       ACTION_DROP         -> resolve target via linear sweep
 *                              (childRight > x) and reorder the list
 *       ACTION_DRAG_ENDED   -> restore source visibility
 *   - SEARCH tab is pinned to the right: its long-press returns false so it
 *     cannot be dragged, and the drop resolver clamps the target index so
 *     SEARCH never loses the last slot
 *   - Single click on a tile toggles `enabled` for that tab and persists via
 *     MainTabsManager.saveTabs(list); SEARCH is a no-op
 *   - No animation on reorder: removeAllViews() + rebuild
 *
 * Deliberately NOT ported (CG does not ship these either):
 *   - per-tab quick-edit dialog (rename, icon picker, toggle row)
 *   - custom titles persistence
 *   - translucent ghost drag shadow (CG uses the default builder)
 *   - cross-fade / slide reorder animation
 */
package app.nimarkogram.messenger.preferences.tabs;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.ClipData;
import android.content.Context;
import android.os.Build;
import android.view.DragEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.glass.GlassTabView;
import org.telegram.ui.DialogsActivity;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.utils.ui.MainTabsManager;

public class MainTabsPreviewCell extends ViewGroup {

    public GlassTabView[] tabs;

    private Context boundContext;
    private Theme.ResourcesProvider boundResourceProvider;
    private int boundCurrentAccount = -1;
    private boolean boundFromSettings;
    private boolean boundShowSearch;

    @Nullable
    private List<MainTabsManager.Tab> sourceTabs;

    private MainTabsManager.TabType[] tabTypes;

    @Nullable private Runnable onReorderCommitted;
    public void setOnReorderCommitted(@Nullable Runnable r) { this.onReorderCommitted = r; }

    @Nullable
    private View draggedView;
    private int contentGeneration;
    private int dragGeneration = -1;

    private boolean editMode;

    public MainTabsPreviewCell(Context context) {
        super(context);
        setWillNotDraw(false);
        setClipChildren(false);
    }

    public void setEditMode(boolean editMode) {
        this.editMode = editMode;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        final int height = dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS);

        if (tabs != null && tabs.length > 0) {
            final int hPad = dp(DialogsActivity.MAIN_TABS_MARGIN + 4) * 2;
            final int vPad = dp(DialogsActivity.MAIN_TABS_MARGIN + 4) * 2;
            final int available = Math.max(0, width - hPad);
            final int tabWidth = available / tabs.length;
            final int tabHeight = Math.max(0, height - vPad);
            final int childWSpec = MeasureSpec.makeMeasureSpec(tabWidth, MeasureSpec.EXACTLY);
            final int childHSpec = MeasureSpec.makeMeasureSpec(tabHeight, MeasureSpec.EXACTLY);
            for (GlassTabView tab : tabs) {
                if (tab != null) {
                    tab.measure(childWSpec, childHSpec);
                }
            }
        }

        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (tabs == null || tabs.length == 0) return;
        final int hPad = dp(DialogsActivity.MAIN_TABS_MARGIN + 4);
        final int vPad = dp(DialogsActivity.MAIN_TABS_MARGIN + 4);
        final int available = Math.max(0, (r - l) - hPad * 2);
        final int tabWidth = available / tabs.length;
        for (int i = 0; i < tabs.length; i++) {
            GlassTabView tab = tabs[i];
            int slot = LocaleController.isRTL ? tabs.length - 1 - i : i;
            int x = hPad + slot * tabWidth;
            if (tab != null) {
                tab.layout(x, vPad, x + tabWidth, vPad + tab.getMeasuredHeight());
            }
        }
    }

    public void setTabs(
            Context context,
            Theme.ResourcesProvider resourceProvider,
            int currentAccount,
            boolean fromSettings,
            boolean showSearch
    ) {
        invalidateActiveDrag();
        contentGeneration++;
        this.boundContext = context;
        this.boundResourceProvider = resourceProvider;
        this.boundCurrentAccount = currentAccount < 0 ? UserConfig.selectedAccount : currentAccount;
        this.boundFromSettings = fromSettings;
        this.boundShowSearch = showSearch;
        this.sourceTabs = null;

        rebuildFromFixedLayout();
        applyBackground();
        requestLayout();
        invalidate();
    }

    public void setTabs(Context context, Theme.ResourcesProvider resourceProvider) {
        setTabs(context, resourceProvider, UserConfig.selectedAccount, true, false);
    }

    public void setTabs(
            Context context,
            Theme.ResourcesProvider resourceProvider,
            List<MainTabsManager.Tab> source
    ) {
        invalidateActiveDrag();
        contentGeneration++;
        this.boundContext = context;
        this.boundResourceProvider = resourceProvider;
        this.boundCurrentAccount = UserConfig.selectedAccount;
        this.boundFromSettings = true;
        this.boundShowSearch = NimarkoConfig.showSearchInTabs;
        this.sourceTabs = source;

        rebuildFromSource();
        applyBackground();
        requestLayout();
        invalidate();
    }

    public void refresh() {
        invalidateActiveDrag();
        contentGeneration++;
        
        this.boundShowSearch = app.nimarkogram.messenger.NimarkoConfig.showSearchInTabs;
        if (sourceTabs != null) {
            rebuildFromSource();
        } else {
            rebuildFromFixedLayout();
        }
        applyBackground();
        requestLayout();
        invalidate();
    }

    private void rebuildFromFixedLayout() {
        removeAllViews();
        tabs = new GlassTabView[3];
        tabTypes = new MainTabsManager.TabType[]{
                MainTabsManager.TabType.PROFILE,
                MainTabsManager.TabType.CHATS,
                MainTabsManager.TabType.SETTINGS,
        };
        tabs[0] = GlassTabView.createAvatar(boundContext, boundResourceProvider, boundCurrentAccount, R.string.MainTabsProfile);
        tabs[1] = GlassTabView.createMainTab(boundContext, boundResourceProvider, GlassTabView.TabAnimation.CHATS, R.string.MainTabsChats);
        tabs[2] = GlassTabView.createMainTab(boundContext, boundResourceProvider, GlassTabView.TabAnimation.SETTINGS, R.string.Settings);

        final boolean titleVisible = NimarkoConfig.showMainTabsTitle;
        for (int i = 0; i < tabs.length; i++) {
            GlassTabView tab = tabs[i];
            tab.setTitleVisible(titleVisible);
            tab.setClickable(false);
            tab.setFocusable(false);
            addView(tab, new ViewGroup.LayoutParams(0, 0));
        }
    }

    private void rebuildFromSource() {
        removeAllViews();
        final List<MainTabsManager.Tab> src = sourceTabs;
        if (src == null || src.isEmpty()) {
            rebuildFromFixedLayout();
            return;
        }
        
        final ArrayList<MainTabsManager.Tab> snapshot = new ArrayList<>();
        boolean hasSearch = false;
        for (MainTabsManager.Tab t : src) {
            if (t.getType() == MainTabsManager.TabType.SEARCH && !NimarkoConfig.showSearchInTabs) continue;
            if (t.getType() == MainTabsManager.TabType.SEARCH) hasSearch = true;
            snapshot.add(t);
        }
        
        if (boundShowSearch && !hasSearch) {
            snapshot.add(new MainTabsManager.Tab(MainTabsManager.TabType.SEARCH, true));
        }
        tabs = new GlassTabView[snapshot.size()];
        tabTypes = new MainTabsManager.TabType[snapshot.size()];
        final boolean titleVisible = NimarkoConfig.showMainTabsTitle;
        for (int i = 0; i < snapshot.size(); i++) {
            final int index = i;
            final MainTabsManager.Tab entry = snapshot.get(i);
            final MainTabsManager.TabType type = entry.getType();
            final GlassTabView tab = MainTabsManager.INSTANCE.createTabView(
                    boundContext, boundResourceProvider, boundCurrentAccount,
                    type, boundFromSettings, boundShowSearch);
            tab.setTitleVisible(titleVisible);
            tab.setEnabledVisual(entry.enabled);
            tab.setClickable(true);
            tab.setLongClickable(true);
            tab.setFocusable(true);
            updateAccessibility(tab, type, entry.enabled);

            tab.setOnClickListener(v -> onTabClicked(index, type, tab));
            tab.setOnLongClickListener(v -> onTabLongClicked(index, type));
            tab.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN || !(event.isCtrlPressed() || event.isAltPressed())) {
                    return false;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return moveFromKeyboard(index, -1);
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return moveFromKeyboard(index, 1);
                return false;
            });
            tab.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    if (canMoveVisual(index, -1)) {
                        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                                R.id.acc_action_move_left, LocaleController.getString(R.string.NM_BT_MoveLeft)));
                    }
                    if (canMoveVisual(index, 1)) {
                        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                                R.id.acc_action_move_right, LocaleController.getString(R.string.NM_BT_MoveRight)));
                    }
                }

                @Override
                public boolean performAccessibilityAction(View host, int action, android.os.Bundle args) {
                    if (action == R.id.acc_action_move_left) return moveVisual(index, -1);
                    if (action == R.id.acc_action_move_right) return moveVisual(index, 1);
                    return super.performAccessibilityAction(host, action, args);
                }
            });
            tab.setOnTouchListener((v, ev) -> {
                if (ev.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                    ViewParent p = getParent();
                    while (p != null) {
                        p.requestDisallowInterceptTouchEvent(true);
                        p = p.getParent();
                    }
                }
                return false;
            });

            tabs[i] = tab;
            tabTypes[i] = type;
            addView(tab, new ViewGroup.LayoutParams(0, 0));
        }
    }

    private void applyBackground() {
        setBackground(Theme.createRoundRectDrawable(
                dp(DialogsActivity.MAIN_TABS_HEIGHT / 2f),
                Theme.getColor(Theme.key_chat_messagePanelBackground, boundResourceProvider)));
    }

    private void onTabClicked(int index, MainTabsManager.TabType type, GlassTabView tab) {
        if (sourceTabs == null) return;
        if (type == MainTabsManager.TabType.SEARCH || type == MainTabsManager.TabType.CHATS) return;
        
        MainTabsManager.Tab entry = null;
        for (MainTabsManager.Tab t : sourceTabs) {
            if (t.getType() == type) { entry = t; break; }
        }
        if (entry == null) return;
        entry.enabled = !entry.enabled;
        tab.setEnabledVisual(entry.enabled);
        updateAccessibility(tab, type, entry.enabled);
        MainTabsManager.INSTANCE.saveTabs(sourceTabs);
        if (onReorderCommitted != null) onReorderCommitted.run();
    }

    private boolean onTabLongClicked(int index, MainTabsManager.TabType type) {
        
        if (type == MainTabsManager.TabType.SEARCH) return false;
        startDrag(index);
        return true;
    }

    private void startDrag(int index) {
        if (index < 0 || tabs == null || index >= tabs.length) return;
        View child = tabs[index];
        if (child == null) return;
        draggedView = child;
        dragGeneration = contentGeneration;
        ClipData clip = ClipData.newPlainText("index", String.valueOf(index));
        View.DragShadowBuilder shadow = new View.DragShadowBuilder(child);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                child.startDragAndDrop(clip, shadow, null, 0);
            } else {
                child.startDrag(clip, shadow, null, 0);
            }
        } catch (Throwable ignore) {
            draggedView = null;
            dragGeneration = -1;
        }
    }

    @Override
    public boolean onDragEvent(DragEvent event) {
        final int action = event.getAction();
        if (action == DragEvent.ACTION_DRAG_STARTED) {
            if (draggedView != null && dragGeneration == contentGeneration) {
                draggedView.setVisibility(INVISIBLE);
                return true;
            }
            return false;
        }
        if (action == DragEvent.ACTION_DROP) {
            if (dragGeneration != contentGeneration) {
                return true;
            }
            try {
                int from = Integer.parseInt(event.getClipData().getItemAt(0).getText().toString());
                int to = resolveTargetIndex(event.getX());
                if (from != to) {
                    reorder(from, to);
                }
            } catch (Throwable ignore) {
                
            }
            return true;
        }
        if (action == DragEvent.ACTION_DRAG_ENDED) {
            invalidateActiveDrag();
            return true;
        }
        return true;
    }

    private void invalidateActiveDrag() {
        if (draggedView != null) {
            draggedView.setVisibility(VISIBLE);
            draggedView = null;
        }
        dragGeneration = -1;
    }

    private int resolveTargetIndex(float x) {
        int childCount = getChildCount();
        int nearest = 0;
        float distance = Float.MAX_VALUE;
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child == null) continue;
            float d = Math.abs(x - (child.getLeft() + child.getRight()) / 2f);
            if (d < distance) { distance = d; nearest = i; }
        }
        return nearest;
    }

    private void updateAccessibility(GlassTabView tab, MainTabsManager.TabType type, boolean enabled) {
        int title;
        switch (type) {
            case PROFILE: title = R.string.MainTabsProfile; break;
            case SETTINGS: title = R.string.Settings; break;
            case SEARCH: title = R.string.Search; break;
            default: title = R.string.MainTabsChats; break;
        }
        boolean fixed = type == MainTabsManager.TabType.CHATS || type == MainTabsManager.TabType.SEARCH;
        tab.setSelected(enabled || fixed);
        tab.setContentDescription(LocaleController.getString(title) + ", "
                + LocaleController.getString(enabled || fixed ? R.string.NM_BT_TabEnabled : R.string.NM_BT_TabDisabled));
    }

    private boolean moveFromKeyboard(int index, int physicalDirection) {
        return moveVisual(index, physicalDirection);
    }

    private boolean canMoveVisual(int index, int physicalDirection) {
        if (tabTypes == null || index < 0 || index >= tabTypes.length
                || tabTypes[index] == MainTabsManager.TabType.SEARCH) return false;
        int logicalDirection = LocaleController.isRTL ? -physicalDirection : physicalDirection;
        int target = index + logicalDirection;
        return target >= 0 && target < tabTypes.length
                && tabTypes[target] != MainTabsManager.TabType.SEARCH;
    }

    private boolean moveVisual(int index, int physicalDirection) {
        if (!canMoveVisual(index, physicalDirection)) return false;
        int logicalDirection = LocaleController.isRTL ? -physicalDirection : physicalDirection;
        reorder(index, index + logicalDirection);
        return true;
    }

    private void reorder(int from, int to) {
        if (sourceTabs == null || tabTypes == null) return;
        if (from < 0 || from >= tabTypes.length) return;
        if (to >= tabTypes.length) {
            to = tabTypes.length - 1;
        }
        if (to < 0) to = 0;
        
        if (to < tabTypes.length && tabTypes[to] == MainTabsManager.TabType.SEARCH) {
            to = Math.max(0, to - 1);
        }
        if (from == to) return;
        
        final MainTabsManager.TabType fromType = tabTypes[from];
        final MainTabsManager.TabType toType = tabTypes[to];
        int srcFrom = -1, srcTo = -1;
        for (int i = 0; i < sourceTabs.size(); i++) {
            MainTabsManager.TabType t = sourceTabs.get(i).getType();
            if (srcFrom < 0 && t == fromType) srcFrom = i;
            if (srcTo < 0 && t == toType) srcTo = i;
        }
        if (srcFrom < 0 || srcTo < 0 || srcFrom == srcTo) return;
        MainTabsManager.Tab moved = sourceTabs.remove(srcFrom);
        sourceTabs.add(srcTo, moved);
        MainTabsManager.INSTANCE.saveTabs(sourceTabs);
        rebuildFromSource();
        requestLayout();
        invalidate();
        if (onReorderCommitted != null) {
            onReorderCommitted.run();
        }
    }
}
