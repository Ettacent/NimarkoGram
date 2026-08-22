package app.nimarkogram.messenger.preferences;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;

import app.nimarkogram.messenger.utils.AppRestartHelper;
import android.widget.FrameLayout;
import android.view.HapticFeedbackConstants;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.core.view.ViewCompat;
import app.nimarkogram.messenger.utils.ui.PopupUtils;
import com.google.android.exoplayer2.util.Consumer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.function.ToIntFunction;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.NotificationsCheckCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ShareAlert;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

public abstract class BasePreferencesActivity extends BaseFragment {
    protected LinearLayoutManager layoutManager;
    protected UniversalRecyclerView listView;
    private int initialSearchItemId;

    @Override
    public View createView(Context context) {
        initializeOptionStrings();
        this.actionBar.setBackButtonDrawable(new BackDrawable(false));
        this.actionBar.setAllowOverlayTitle(false);
        this.actionBar.setTitle(getTitle());
        this.actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int i) {
                if (i == -1) {
                    finishFragment();
                }
            }
        });
        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        if (this.actionBar.menu == null) {
            this.actionBar.createMenu();
        }
        final BasePreferencesActivity self = this;
        UniversalRecyclerView universalRecyclerView = new UniversalRecyclerView(this,
                (Utilities.Callback2<ArrayList<UItem>, UniversalAdapter>) self::fillItemsWithDescriptions,
                (UItem item, View v, Integer pos, Float fx, Float fy) -> self.onClick(item, v, pos, fx, fy),
                (UItem item, View v, Integer pos, Float fx, Float fy) -> self.onLongClick(item, v, pos, fx, fy));
        this.listView = universalRecyclerView;
        universalRecyclerView.setSections(true);
        this.listView.adapter.setApplyBackground(false);
        this.actionBar.setAdaptiveBackground(this.listView);
        this.layoutManager = universalRecyclerView.layoutManager;
        frameLayout.addView(this.listView, LayoutHelper.createFrame(-1, -1.0f));
        if (initialSearchItemId != 0) {
            this.listView.post(() -> scrollToItem(initialSearchItemId));
        }
        this.fragmentView = frameLayout;
        ViewCompat.setOnApplyWindowInsetsListener(frameLayout, this::onInsetsInternal);
        ViewCompat.requestApplyInsets(frameLayout);
        return frameLayout;
    }

    public abstract void fillItems(ArrayList<UItem> arrayList, UniversalAdapter universalAdapter);

    private void fillItemsWithDescriptions(ArrayList<UItem> items, UniversalAdapter adapter) {
        fillItems(items, adapter);
        NimarkoSettingsSearchIndex.applyDescriptions(this, items);
    }

    public abstract String getTitle();

    public boolean hasHeaderCell() {
        return false;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public boolean drawEdgeNavigationBar() {
        return false;
    }

    public abstract void onClick(UItem uItem, View view, int i, float f, float f2);

    @Override
    public void onInsets(int i, int i2, int i3, int i4) {
        if (this.listView != null) {
            this.listView.setPadding(0, 0, 0, i4);
            this.listView.setClipToPadding(false);
        }
    }

    public boolean onLongClick(UItem uItem, View view, int i, float f, float f2) {
        String alias = app.nimarkogram.messenger.preferences.utils.SettingsRegistry.getInstance()
                .getFirstSettingLink(getClass(), uItem);
        if (TextUtils.isEmpty(alias)) return false;
        showCopyLinkOptions(view, "tg://settings/" + alias);
        return true;
    }

    @Override
    public void onPause() {
        super.onPause();
        Bulletin.removeDelegate(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (this.listView != null) {
            this.listView.adapter.update(false);
        }
        Bulletin.addDelegate(this, new Bulletin.Delegate() {
            @Override
            public int getBottomOffset(int i) {
                return getBottomInset();
            }

            @Override
            public int getTopOffset(int i) {
                if (hasHeaderCell()) {
                    return AndroidUtilities.statusBarHeight;
                }
                return 0;
            }
        });
    }

    public void scrollToItem(final int i) {
        if (listView == null || listView.adapter == null || layoutManager == null) {
            return;
        }
        int iFindPositionByItemId = listView.findPositionByItemId(i);
        if (iFindPositionByItemId < 0 || iFindPositionByItemId >= listView.adapter.getItemCount()) {
            return;
        }
        layoutManager.scrollToPositionWithOffset(iFindPositionByItemId, AndroidUtilities.dp(80.0f));
        listView.highlightRow(() -> listView.findPositionByItemId(i));
    }

    public BasePreferencesActivity openAtSetting(int itemId) {
        initialSearchItemId = itemId;
        return this;
    }

    public void showCopyLinkOptions(View view, final String str) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, 1);
        ItemOptions.makeOptions(this, view)
                .add(R.drawable.msg_copy, LocaleController.getString(R.string.CopyLink), () -> {
                    if (AndroidUtilities.addToClipboard(str)) {
                        BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.LinkCopied)).show();
                    }
                })
                .add(R.drawable.msg_share, LocaleController.getString(R.string.ShareLink), () -> {
                    showDialog(new ShareAlert(getContext(), null, str, false, str, false, getResourceProvider()));
                })
                .setScrimViewBackground(listView.getClipBackground(view))
                .show();
    }

    public void showListDialog(final UItem uItem, final CharSequence[] charSequenceArr, int[] iArr, String str, final int i, final PopupUtils.OnItemClickListener onItemClickListener, boolean z, final boolean z2) {
        if (getParentActivity() == null) {
            return;
        }
        PopupUtils.showDialog(charSequenceArr, iArr, str, i, getContext(), (int i2) -> {
            if (z2 && i == i2) {
                return;
            }
            onItemClickListener.onClick(i2);
            View viewFindViewByItemId = listView.findViewByItemId(uItem.id);
            if (viewFindViewByItemId instanceof TextCell) {
                ((TextCell) viewFindViewByItemId).setValue(charSequenceArr[i2], true);
            } else if (viewFindViewByItemId instanceof SettingsActivity.SettingCell) {
                ((SettingsActivity.SettingCell) viewFindViewByItemId).setValue(charSequenceArr[i2]);
            }
            listView.adapter.update(true);
        }, getResourceProvider(), z);
    }

    public void showRestartBulletin() {
        BulletinFactory.of(this).createSimpleBulletin(
                R.raw.info,
                LocaleController.getString(R.string.NM_RestartRequired),
                LocaleController.getString(R.string.NM_Restart),
                () -> {
                    Context ctx = getParentActivity() != null ? getParentActivity() : getContext();
                    AppRestartHelper.triggerRebirth(ctx);
                }
        ).show();
    }

    public void toggleBooleanSettingAndRefresh(UItem uItem, Consumer<Boolean> consumer) {
        boolean z = !uItem.checked;
        consumer.accept(Boolean.valueOf(z));
        uItem.setChecked(z);
        View viewFindViewByItemId = this.listView.findViewByItemId(uItem.id);
        if (viewFindViewByItemId instanceof CheckBoxCell) {
            ((CheckBoxCell) viewFindViewByItemId).setChecked(z, true);
        } else if (viewFindViewByItemId instanceof NotificationsCheckCell) {
            ((NotificationsCheckCell) viewFindViewByItemId).setChecked(z);
        } else if (viewFindViewByItemId instanceof TextCheckCell) {
            ((TextCheckCell) viewFindViewByItemId).setChecked(z);
        }
        this.listView.adapter.update(true);
    }

    public static void updateCheckState(View view, boolean isChecked) {
        if (view instanceof NotificationsCheckCell) {
            ((NotificationsCheckCell) view).setChecked(isChecked);
        } else if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(isChecked);
        }
    }

    public int[] unBox(Collection<Integer> collection) {
        return collection.stream().mapToInt((ToIntFunction<Integer>) Integer::intValue).toArray();
    }

    public void initializeOptionStrings() {
    }

    protected UItem asPlainSettingsRow(int id, CharSequence title) {
        return UItem.asButton(id, title);
    }

    protected UItem asPlainSettingsRow(int id, CharSequence title, CharSequence value) {
        return UItem.asButton(id, title, value);
    }

    protected UItem asPlainSettingsRowWithSubtitle(int id, CharSequence title, CharSequence subtitle) {
        return SettingsActivity.SettingCell.Factory.of(id, 0, 0, 0, title, subtitle, null);
    }

    protected UItem asSettingsLink(int id, IconBackgroundColors colors, int icon, CharSequence title) {
        return SettingsActivity.SettingCell.Factory.of(id, colors.top, colors.bottom, icon, title);
    }

    protected UItem asSettingsLink(int id, IconBackgroundColors colors, int icon,
                                   CharSequence title, CharSequence subtitle) {
        return SettingsActivity.SettingCell.Factory.of(
                id, colors.top, colors.bottom, icon, title, subtitle, null);
    }

    protected UItem asSettingsValue(int id, IconBackgroundColors colors, int icon,
                                    CharSequence title, CharSequence value) {
        return SettingsActivity.SettingCell.Factory.of(
                id, colors.top, colors.bottom, icon, title, null, value);
    }

    public void showListDialog(UItem uItem, CharSequence[] charSequenceArr, int[] iArr, String str, int i, PopupUtils.OnItemClickListener onItemClickListener) {
        showListDialog(uItem, charSequenceArr, iArr, str, i, onItemClickListener, iArr == null, true);
    }

    public void showListDialog(UItem uItem, CharSequence[] charSequenceArr, String str, int i, PopupUtils.OnItemClickListener onItemClickListener) {
        showListDialog(uItem, charSequenceArr, null, str, i, onItemClickListener);
    }
}
