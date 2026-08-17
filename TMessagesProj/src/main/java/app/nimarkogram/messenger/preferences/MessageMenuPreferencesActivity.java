/**
 * This file is part of NimarkoGram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * NimarkoGram modifications:
 * Copyright Ettacent, 2026.
 *
 * Portions derived from Cherrygram:
 * Copyright github.com/arsLan4k1390, 2022-2026.
 */

package app.nimarkogram.messenger.preferences;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import app.nimarkogram.messenger.NimarkoConfig;

public class MessageMenuPreferencesActivity extends BaseFragment {

    private int rowCount;
    private ListAdapter listAdapter;
    private RecyclerListView listView;

    private int miscellaneousHeaderRow;
    private int messageMenuItemsRow;
    private int messageMenuOrderRow;
    private int hapticRow;
    private int telegramPlusMessageMenuRow;
    private int messageMenuItemsCompactView;
    private int miscellaneousEndDivisor;

    protected Theme.ResourcesProvider resourcesProvider;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        updateRowsId(true);
        return true;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
        
        org.telegram.ui.Components.Bulletin.addDelegate(this, new org.telegram.ui.Components.Bulletin.Delegate() {
            @Override
            public int getBottomOffset(int tag) { return 0; }
            @Override
            public int getTopOffset(int tag) { return AndroidUtilities.statusBarHeight; }
        });
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));

        actionBar.setTitle(getString(R.string.CP_MessageMenu));
        actionBar.setAllowOverlayTitle(false);

        actionBar.setOccupyStatusBar(!AndroidUtilities.isTablet());
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        listAdapter = new ListAdapter(context);

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        FrameLayout frameLayout = (FrameLayout) fragmentView;

        listView = new RecyclerListView(context);
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(listAdapter);
        if (listView.getItemAnimator() != null) {
            ((DefaultItemAnimator) listView.getItemAnimator()).setDelayAnimations(false);
        }
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position, x, y) -> {
            var holder = listView.findViewHolderForAdapterPosition(position);
            if (holder == null || !listAdapter.isEnabled(holder)) {
                return;
            }
            if (position == messageMenuItemsRow) {
                presentFragment(new MessageMenuItemsPreferencesActivity());
            } else if (position == messageMenuOrderRow) {
                presentFragment(new MessageMenuOrderPreferencesActivity());
            } else if (position == hapticRow) {
                NimarkoConfig.toggleMessageMenuHaptic();
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(NimarkoConfig.messageMenuHaptic);
                }
            } else if (position == telegramPlusMessageMenuRow) {
                NimarkoConfig.toggleTelegramPlusMessageMenu();
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(NimarkoConfig.telegramPlusMessageMenu);
                }
            } else if (position == messageMenuItemsCompactView) {
                NimarkoConfig.toggleMsgMenuItemsCompactView();
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(app.nimarkogram.messenger.NimarkoConfig.msgMenuItemsCompactView);
                }
            }
        });

        listView.setSections(true);
        actionBar.setAdaptiveBackground(listView);

        return fragmentView;
    }

    public void showRestartBulletin() {
        org.telegram.ui.Components.BulletinFactory.of(this).createSimpleBulletin(
                org.telegram.messenger.R.raw.info,
                org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.NM_RestartRequired),
                org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.NM_Restart),
                () -> {
                    android.content.Context ctx = getParentActivity() != null ? getParentActivity() : getContext();
                    app.nimarkogram.messenger.utils.AppRestartHelper.triggerRebirth(ctx);
                }
        ).show();
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context mContext;

        private final int VIEW_TYPE_SHADOW = 0;
        private final int VIEW_TYPE_HEADER = 1;
        private final int VIEW_TYPE_TEXT_CELL = 2;
        private final int VIEW_TYPE_TEXT_CHECK = 3;

        ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case VIEW_TYPE_SHADOW:
                    ShadowSectionCell shadowSectionCell = (ShadowSectionCell) holder.itemView;
                    shadowSectionCell.setEnabled(false);
                    break;
                case VIEW_TYPE_HEADER:
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    headerCell.setEnabled(false);

                    if (position == miscellaneousHeaderRow) {
                        headerCell.setText(getString(R.string.LocalMiscellaneousCache));
                    }
                    break;
                case VIEW_TYPE_TEXT_CELL:
                    TextCell textCell = (TextCell) holder.itemView;

                    if (position == messageMenuItemsRow) {
                        textCell.setEnabled(true);
                        textCell.setTextAndIcon(getString(R.string.CP_MessageMenuItems), R.drawable.msg_list, true);
                    } else if (position == messageMenuOrderRow) {
                        textCell.setEnabled(true);
                        textCell.setTextAndIcon(getString(R.string.NM_Menu_Reorder), R.drawable.msg_reorder, true);
                    }
                    break;
                case VIEW_TYPE_TEXT_CHECK:
                    TextCheckCell textCheckCell = (TextCheckCell) holder.itemView;

                    if (position == telegramPlusMessageMenuRow) {
                        textCheckCell.setEnabled(true, null);
                        textCheckCell.setTextAndValueAndCheck(
                                getString(R.string.NM_Menu_TelegramPlus),
                                getString(R.string.NM_Menu_TelegramPlus_Desc),
                                app.nimarkogram.messenger.NimarkoConfig.telegramPlusMessageMenu,
                                true,
                                false
                        );
                    } else if (position == messageMenuItemsCompactView) {
                        textCheckCell.setEnabled(true, null);
                        textCheckCell.setTextAndValueAndCheck(
                                getString(R.string.CP_MessageMenuCompactLayout),
                                getString(R.string.CP_MessageMenuCompactLayout_Desc) + "\n\n" + getString(R.string.CP_MessageMenuCompactLayout_Dot),
                                app.nimarkogram.messenger.NimarkoConfig.msgMenuItemsCompactView,
                                true,
                                false
                        );
                    } else if (position == hapticRow) {
                        textCheckCell.setEnabled(true, null);
                        textCheckCell.setTextAndValueAndCheck(
                                getString(R.string.NM_Menu_Haptic),
                                getString(R.string.NM_Menu_Haptic_Desc),
                                app.nimarkogram.messenger.NimarkoConfig.messageMenuHaptic,
                                true,
                                true
                        );
                    }
                    break;
            }
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.itemView.isEnabled();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case VIEW_TYPE_SHADOW:
                    
                    view = new ShadowSectionCell(mContext);
                    break;
                case VIEW_TYPE_HEADER:
                    
                    view = new HeaderCell(mContext);
                    break;
                case VIEW_TYPE_TEXT_CELL:
                    view = new TextCell(mContext);
                    break;
                case VIEW_TYPE_TEXT_CHECK:
                    view = new TextCheckCell(mContext);
                    break;
                default:
                    throw new IllegalStateException("Unexpected value: " + viewType);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public int getItemViewType(int position) {
            if (position == miscellaneousEndDivisor) {
                return VIEW_TYPE_SHADOW;
            } else if (position == miscellaneousHeaderRow) {
                return VIEW_TYPE_HEADER;
            } else if (position == telegramPlusMessageMenuRow
                    || position == messageMenuItemsCompactView
                    || position == hapticRow) {
                return VIEW_TYPE_TEXT_CHECK;
            } else if (position == messageMenuItemsRow || position == messageMenuOrderRow) {
                return VIEW_TYPE_TEXT_CELL;
            }
            return VIEW_TYPE_SHADOW;
        }
    }

    private void updateRowsId(boolean notify) {
        rowCount = 0;

        miscellaneousHeaderRow = rowCount++;
        messageMenuItemsRow = rowCount++;
        messageMenuOrderRow = rowCount++;
        
        if (app.nimarkogram.messenger.utils.VibrateUtils.hasVibrator()) {
            hapticRow = rowCount++;
        } else {
            hapticRow = -1;
        }
        telegramPlusMessageMenuRow = rowCount++;
        messageMenuItemsCompactView = rowCount++;
        miscellaneousEndDivisor = rowCount++;

        if (listAdapter != null && notify) {
            listAdapter.notifyDataSetChanged();
        }
    }

}
