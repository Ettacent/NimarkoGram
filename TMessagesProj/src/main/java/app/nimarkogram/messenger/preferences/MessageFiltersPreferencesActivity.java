/**
 * This is the source code of Cherrygram for Android, ported to NimarkoGram.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 * Please, be respectful and credit the original author if you use this code.
 *
 * Copyright github.com/arsLan4k1390, 2022-2026.
 */

package app.nimarkogram.messenger.preferences;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.GraySectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.OutlineEditText;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.FiltersSetupActivity;
import org.telegram.ui.UsersSelectActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.chats.filters.MessagesFilterHelper;

public class MessageFiltersPreferencesActivity extends BaseFragment {

    private int rowCount;
    private ListAdapter listAdapter;
    private RecyclerListView listView;

    private int filtersHeaderRow;
    private int enableFilterRow;
    private int filterWordsRow;
    private int filteredWordsAdviceRow;
    private int detectTranslitRow;
    private int exactWordMatchRow;
    private int exclusionsRow;
    private int filtersEndDivisor;

    private int miscellaneousHeaderRow;
    private int detectEntitiesRow;
    private int hideAllRow;
    private int collapseAutomaticallyRow;
    private int makeTransparentRow;
    private int miscellaneousEndDivisor;

    private int advancedHeaderRow;
    private int useRegexRow;
    private int regexPatternsRow;
    private int regexPatternsAdviceRow;
    private int logicModeRow;
    private int chatWhitelistRow;
    private int chatBlacklistRow;
    private int advancedEndDivisor;

    private OutlineEditText outlineEditText;
    private OutlineEditText regexEditText;

    private TextWatcher filterWordsWatcher;
    private TextWatcher regexPatternsWatcher;

    private static final int done_button = 1;
    private ActionBarMenuItem doneButton;

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
            public int getTopOffset(int tag) { return org.telegram.messenger.AndroidUtilities.statusBarHeight; }
        });
    }

    @Override
    public void onFragmentDestroy() {
        checkDone(true);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));

        actionBar.setTitle(getString(R.string.NM_MF_Title));
        actionBar.setAllowOverlayTitle(false);

        actionBar.setOccupyStatusBar(!AndroidUtilities.isTablet());
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == done_button) {
                    checkDone(true);
                }
            }
        });
        doneButton = actionBar.createMenu().addItemWithWidth(done_button, R.drawable.ic_ab_done, dp(56), getString(R.string.Done));

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
            
            boolean requireDonate = false;

            RecyclerView.ViewHolder holder = listView.findViewHolderForAdapterPosition(position);
            if (holder == null || !listAdapter.isEnabled(holder)) {
                return;
            }
            if (requireDonate) {
                
                return;
            }
            if (position == enableFilterRow) {
                NimarkoConfig.putBoolean("enableMsgFilters", !NimarkoConfig.isEnableMsgFilters());
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(NimarkoConfig.isEnableMsgFilters());
                }

                if (!NimarkoConfig.isEnableMsgFilters()) {
                    AndroidUtilities.runOnUIThread(() -> AndroidUtilities.hideKeyboard(listView), 50);
                }

                notifyRowsChanged(filterWordsRow, detectTranslitRow, exactWordMatchRow,
                        exclusionsRow, miscellaneousHeaderRow, detectEntitiesRow, hideAllRow,
                        collapseAutomaticallyRow, makeTransparentRow, advancedHeaderRow,
                        useRegexRow, regexPatternsRow, logicModeRow, chatWhitelistRow,
                        chatBlacklistRow);
            } else if (position == detectTranslitRow) {
                NimarkoConfig.putBoolean("msgFiltersDetectTranslit", !NimarkoConfig.isMsgFiltersDetectTranslit());
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(NimarkoConfig.isMsgFiltersDetectTranslit());

                    if (NimarkoConfig.isMsgFiltersDetectTranslit() && !NimarkoConfig.isEnableMsgFilters()) {
                        NimarkoConfig.putBoolean("enableMsgFilters", true);
                        notifyRowChanged(enableFilterRow);
                    }
                }
            } else if (position == exactWordMatchRow) {
                NimarkoConfig.putBoolean("msgFiltersMatchExactWord", !NimarkoConfig.isMsgFiltersMatchExactWord());
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(NimarkoConfig.isMsgFiltersMatchExactWord());

                    if (NimarkoConfig.isMsgFiltersMatchExactWord() && !NimarkoConfig.isEnableMsgFilters()) {
                        NimarkoConfig.putBoolean("enableMsgFilters", true);
                        notifyRowChanged(enableFilterRow);
                    }
                }
            } else if (position == exclusionsRow) {
                final int account = currentAccount;
                final long ownerUid = UserConfig.getInstance(account).getClientUserId();
                AndroidUtilities.runOnUIThread(() -> {
                    UsersSelectActivity activity = getUsersSelectActivity(account);
                    activity.setDelegate((ids, unused) -> {
                        MessagesFilterHelper messagesFilterHelper = MessagesFilterHelper.INSTANCE;

                        Set<Long> chatIds = new HashSet<>(ids);
                        Set<String> excludedChats = new HashSet<>(messagesFilterHelper.getArrayList(
                                account, messagesFilterHelper.getExcludedList()));

                        excludedChats.clear();

                        if (!chatIds.isEmpty()) {
                            for (Long id : chatIds) {
                                if (DialogObject.isUserDialog(id) || DialogObject.isChatDialog(id)) {
                                    excludedChats.add(String.valueOf(id));
                                }
                            }
                        }

                        messagesFilterHelper.saveArrayList(account, ownerUid,
                                new ArrayList<>(excludedChats), messagesFilterHelper.getExcludedList());

                        notifyRowChanged(exclusionsRow);
                    });
                    presentFragment(activity);
                }, 300);
            } else if (position == detectEntitiesRow) {
                NimarkoConfig.putBoolean("msgFiltersDetectEntities", !NimarkoConfig.isMsgFiltersDetectEntities());
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(NimarkoConfig.isMsgFiltersDetectEntities());

                    if (NimarkoConfig.isMsgFiltersDetectEntities() && !NimarkoConfig.isEnableMsgFilters()) {
                        NimarkoConfig.putBoolean("enableMsgFilters", true);
                        notifyRowChanged(enableFilterRow);
                    }
                }
            } else if (position == hideAllRow) {
                NimarkoConfig.putBoolean("msgFiltersHideAll", !NimarkoConfig.isMsgFiltersHideAll());
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(NimarkoConfig.isMsgFiltersHideAll());

                    if (NimarkoConfig.isMsgFiltersHideAll() && !NimarkoConfig.isEnableMsgFilters()) {
                        NimarkoConfig.putBoolean("enableMsgFilters", true);
                        notifyRowChanged(enableFilterRow);
                    }
                }
            } else if (position == collapseAutomaticallyRow) {
                NimarkoConfig.toggleMsgFiltersCollapseAutomatically();
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(NimarkoConfig.isMsgFiltersCollapseAutomatically());
                }
                if (NimarkoConfig.isMsgFiltersCollapseAutomatically() && !NimarkoConfig.isEnableMsgFilters()) {
                    NimarkoConfig.putBoolean("enableMsgFilters", true);
                    notifyRowChanged(enableFilterRow);
                }
            } else if (position == makeTransparentRow) {
                NimarkoConfig.putBoolean("msgFilterTransparentMsg", !NimarkoConfig.isMsgFilterTransparentMsg());
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(NimarkoConfig.isMsgFilterTransparentMsg());

                    if (NimarkoConfig.isMsgFilterTransparentMsg() && !NimarkoConfig.isEnableMsgFilters()) {
                        NimarkoConfig.putBoolean("enableMsgFilters", true);
                        notifyRowChanged(enableFilterRow);
                    }
                }
            } else if (position == useRegexRow) {
                NimarkoConfig.setMsgFiltersUseRegex(!NimarkoConfig.isMsgFiltersUseRegex());
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(NimarkoConfig.isMsgFiltersUseRegex());
                    if (NimarkoConfig.isMsgFiltersUseRegex() && !NimarkoConfig.isEnableMsgFilters()) {
                        NimarkoConfig.putBoolean("enableMsgFilters", true);
                        notifyRowChanged(enableFilterRow);
                    }
                    
                    notifyRowChanged(regexPatternsRow);
                }
            } else if (position == logicModeRow) {
                int next = NimarkoConfig.getMsgFiltersLogic() == NimarkoConfig.MSG_FILTERS_LOGIC_OR
                        ? NimarkoConfig.MSG_FILTERS_LOGIC_AND
                        : NimarkoConfig.MSG_FILTERS_LOGIC_OR;
                NimarkoConfig.setMsgFiltersLogic(next);
                notifyRowChanged(logicModeRow);
            } else if (position == chatWhitelistRow) {
                presentChatPicker(true);
            } else if (position == chatBlacklistRow) {
                presentChatPicker(false);
            }
        });

        listView.setSections(
            view -> !(view instanceof ShadowSectionCell
                    || view instanceof FiltersSetupActivity.HintInnerCell
                    || view instanceof GraySectionCell)
                    && !java.util.Objects.equals(view.getTag(), RecyclerListView.TAG_NOT_SECTION),
            dp(12),
            dp(16),
            listView::drawBackgroundRect,
            true
        );
        actionBar.setAdaptiveBackground(listView);

        return fragmentView;
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context mContext;

        private static final int VIEW_TYPE_SHADOW = 0;
        private static final int VIEW_TYPE_HEADER = 1;
        private static final int VIEW_TYPE_TEXT_CELL = 2;
        private static final int VIEW_TYPE_TEXT_CHECK = 3;
        private static final int VIEW_TYPE_TEXT_INFO_PRIVACY = 5;
        private static final int VIEW_TYPE_EDIT_TEXT = 7;

        ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            
            boolean requireDonate = false;
            switch (holder.getItemViewType()) {
                case VIEW_TYPE_SHADOW:
                    holder.itemView.setEnabled(false);
                    
                    break;
                case VIEW_TYPE_HEADER:
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    headerCell.setEnabled(false);

                    if (position == filtersHeaderRow) {
                        headerCell.setEnabled(true, null);
                        headerCell.setText(getString(R.string.General));
                    } else if (position == miscellaneousHeaderRow) {
                        headerCell.setEnabled(NimarkoConfig.isEnableMsgFilters(), null);
                        headerCell.setText(getString(R.string.NM_MF_Miscellaneous));
                    } else if (position == advancedHeaderRow) {
                        headerCell.setEnabled(NimarkoConfig.isEnableMsgFilters(), null);
                        headerCell.setText(getString(R.string.NM_MF_Advanced));
                    }
                    break;
                case VIEW_TYPE_TEXT_CELL:
                    TextCell textCell = (TextCell) holder.itemView;
                    textCell.setEnabled(false);

                    if (position == exclusionsRow) {
                        textCell.setEnabled(NimarkoConfig.isEnableMsgFilters());
                        textCell.setTextAndValueAndIcon(
                                getString(R.string.NM_MF_Exclusions),
                                String.valueOf(MessagesFilterHelper.INSTANCE.getExcludedChatsCount(currentAccount)),
                                R.drawable._menu_stream_comments_off_24,
                                false
                        );
                    } else if (position == logicModeRow) {
                        textCell.setEnabled(NimarkoConfig.isEnableMsgFilters());
                        int logic = NimarkoConfig.getMsgFiltersLogic();
                        textCell.setTextAndValue(
                                getString(R.string.NM_MF_LogicMode),
                                getString(logic == NimarkoConfig.MSG_FILTERS_LOGIC_AND
                                        ? R.string.NM_MF_LogicAnd
                                        : R.string.NM_MF_LogicOr),
                                true
                        );
                    } else if (position == chatWhitelistRow) {
                        textCell.setEnabled(NimarkoConfig.isEnableMsgFilters());
                        textCell.setTextAndValue(
                                getString(R.string.NM_MF_ChatWhitelist),
                                String.valueOf(countCsvIds(NimarkoConfig.getMsgFiltersChatWhitelist(currentAccount))),
                                true
                        );
                    } else if (position == chatBlacklistRow) {
                        textCell.setEnabled(NimarkoConfig.isEnableMsgFilters());
                        textCell.setTextAndValue(
                                getString(R.string.NM_MF_ChatBlacklist),
                                String.valueOf(countCsvIds(NimarkoConfig.getMsgFiltersChatBlacklist(currentAccount))),
                                false
                        );
                    }
                    break;
                case VIEW_TYPE_TEXT_CHECK:
                    TextCheckCell textCheckCell = (TextCheckCell) holder.itemView;
                    textCheckCell.setEnabled(NimarkoConfig.isEnableMsgFilters(), null);

                    if (position == enableFilterRow) {
                        textCheckCell.setEnabled(true, null);
                        textCheckCell.setTextAndValueAndCheck(
                                getString(R.string.NM_MF_Filter),
                                getString(R.string.NM_MF_Filter_Desc),
                                NimarkoConfig.isEnableMsgFilters(),
                                true,
                                true
                        );
                    } else if (position == detectTranslitRow) {
                        textCheckCell.setEnabled(NimarkoConfig.isEnableMsgFilters(), null);
                        textCheckCell.setTextAndValueAndCheck(
                                getString(R.string.NM_MF_Translit),
                                getString(R.string.NM_MF_Translit_Desc),
                                NimarkoConfig.isMsgFiltersDetectTranslit(),
                                true,
                                true
                        );
                    } else if (position == exactWordMatchRow) {
                        textCheckCell.setEnabled(NimarkoConfig.isEnableMsgFilters(), null);
                        textCheckCell.setTextAndValueAndCheck(
                                getString(R.string.NM_MF_Exact_Words),
                                getString(R.string.NM_MF_Exact_Words_Desc),
                                NimarkoConfig.isMsgFiltersMatchExactWord(),
                                true,
                                true
                        );
                    } else if (position == detectEntitiesRow) {
                        textCheckCell.setEnabled(NimarkoConfig.isEnableMsgFilters(), null);
                        textCheckCell.setTextAndValueAndCheck(
                                getString(R.string.NM_MF_Entities),
                                getString(R.string.NM_MF_EntitiesDesc),
                                NimarkoConfig.isMsgFiltersDetectEntities(),
                                true,
                                true
                        );
                    } else if (position == hideAllRow) {
                        textCheckCell.setEnabled(NimarkoConfig.isEnableMsgFilters(), null);
                        textCheckCell.setTextAndValueAndCheck(
                                getString(R.string.NM_MF_HideAll),
                                getString(R.string.NM_MF_HideAllDesc),
                                NimarkoConfig.isMsgFiltersHideAll(),
                                true,
                                true
                        );
                    } else if (position == collapseAutomaticallyRow) {
                        textCheckCell.setEnabled(NimarkoConfig.isEnableMsgFilters(), null);
                        textCheckCell.setTextAndValueAndCheck(
                                getString(R.string.CP_Message_Filtering_Collapse),
                                getString(R.string.CP_Message_Filtering_Collapse_Desc),
                                NimarkoConfig.isMsgFiltersCollapseAutomatically(),
                                true,
                                true
                        );
                    } else if (position == makeTransparentRow) {
                        textCheckCell.setEnabled(NimarkoConfig.isEnableMsgFilters(), null);
                        textCheckCell.setTextAndValueAndCheck(
                                getString(R.string.NM_MF_Transparent),
                                getString(R.string.NM_MF_Transparent_Desc),
                                NimarkoConfig.isMsgFilterTransparentMsg(),
                                true,
                                true
                        );
                    } else if (position == useRegexRow) {
                        textCheckCell.setEnabled(NimarkoConfig.isEnableMsgFilters(), null);
                        textCheckCell.setTextAndValueAndCheck(
                                getString(R.string.NM_MF_UseRegex),
                                getString(R.string.NM_MF_UseRegex_Desc),
                                NimarkoConfig.isMsgFiltersUseRegex(),
                                true,
                                true
                        );
                    }
                    break;
                case VIEW_TYPE_TEXT_INFO_PRIVACY:
                    TextInfoPrivacyCell textInfoPrivacyCell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == filteredWordsAdviceRow) {
                        textInfoPrivacyCell.setText(getString(R.string.NM_MF_Field_Desc));
                        
                    } else if (position == regexPatternsAdviceRow) {
                        textInfoPrivacyCell.setText(getString(R.string.NM_MF_Regex_Desc));
                    }
                    break;
                case VIEW_TYPE_EDIT_TEXT:
                    OutlineEditText editView = (OutlineEditText) holder.itemView;
                    editView.setPadding(dp(16), dp(12), dp(16), dp(12));
                    if (position == filterWordsRow) {
                        outlineEditText = editView;
                        outlineEditText.setEnabled(NimarkoConfig.isEnableMsgFilters());
                        outlineEditText.getEditText().setEnabled(NimarkoConfig.isEnableMsgFilters());
                        if (filterWordsWatcher != null) {
                            outlineEditText.getEditText().removeTextChangedListener(filterWordsWatcher);
                        }
                        filterWordsWatcher = new TextWatcher() {
                            @Override
                            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

                            @Override
                            public void onTextChanged(CharSequence s, int start, int before, int count) {}

                            @Override
                            public void afterTextChanged(Editable s) {
                                checkDone(false);
                            }
                        };
                        outlineEditText.getEditText().addTextChangedListener(filterWordsWatcher);
                        outlineEditText.getEditText().setSingleLine(false);
                        outlineEditText.setHint(getString(R.string.NM_MF_Field));
                        outlineEditText.getEditText().setText(NimarkoConfig.getMsgFiltersElements());
                        outlineEditText.setMinimumHeight(200);
                        outlineEditText.getEditText().setPadding(dp(16), dp(12), dp(16), dp(12));
                    } else if (position == regexPatternsRow) {
                        regexEditText = editView;
                        boolean enabled = NimarkoConfig.isEnableMsgFilters() && NimarkoConfig.isMsgFiltersUseRegex();
                        regexEditText.setEnabled(enabled);
                        regexEditText.getEditText().setEnabled(enabled);
                        if (regexPatternsWatcher != null) {
                            regexEditText.getEditText().removeTextChangedListener(regexPatternsWatcher);
                        }
                        regexPatternsWatcher = new TextWatcher() {
                            @Override
                            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                            @Override
                            public void onTextChanged(CharSequence s, int start, int before, int count) { }
                            @Override
                            public void afterTextChanged(Editable s) { checkDone(false); }
                        };
                        regexEditText.getEditText().addTextChangedListener(regexPatternsWatcher);
                        regexEditText.getEditText().setSingleLine(false);
                        regexEditText.setHint(getString(R.string.NM_MF_RegexPatterns));
                        regexEditText.getEditText().setText(NimarkoConfig.getMsgFiltersRegexPatterns());
                        regexEditText.setMinimumHeight(200);
                        regexEditText.getEditText().setPadding(dp(16), dp(12), dp(16), dp(12));
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
                case VIEW_TYPE_TEXT_INFO_PRIVACY:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                case VIEW_TYPE_EDIT_TEXT:
                    
                    view = new OutlineEditText(mContext);
                    break;
                default:
                    throw new IllegalStateException("Unexpected value: " + viewType);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public int getItemViewType(int position) {
            if (position == filtersEndDivisor || position == miscellaneousEndDivisor || position == advancedEndDivisor) {
                return VIEW_TYPE_SHADOW;
            } else if (position == filtersHeaderRow || position == miscellaneousHeaderRow || position == advancedHeaderRow) {
                return VIEW_TYPE_HEADER;
            } else if (position == exclusionsRow || position == logicModeRow || position == chatWhitelistRow || position == chatBlacklistRow) {
                return VIEW_TYPE_TEXT_CELL;
            } else if (position == enableFilterRow || position == detectTranslitRow || position == exactWordMatchRow || position == detectEntitiesRow || position == hideAllRow || position == collapseAutomaticallyRow || position == makeTransparentRow || position == useRegexRow) {
                return VIEW_TYPE_TEXT_CHECK;
            } else if (position == filteredWordsAdviceRow || position == regexPatternsAdviceRow) {
                return VIEW_TYPE_TEXT_INFO_PRIVACY;
            } else if (position == filterWordsRow || position == regexPatternsRow) {
                return VIEW_TYPE_EDIT_TEXT;
            }
            return VIEW_TYPE_SHADOW;
        }
    }

    private void updateRowsId(boolean notify) {
        rowCount = 0;

        filtersHeaderRow = rowCount++;
        enableFilterRow = rowCount++;
        filterWordsRow = rowCount++;
        filteredWordsAdviceRow = rowCount++;
        detectTranslitRow = rowCount++;
        exactWordMatchRow = rowCount++;
        exclusionsRow = rowCount++;
        filtersEndDivisor = rowCount++;

        miscellaneousHeaderRow = rowCount++;
        detectEntitiesRow = rowCount++;
        hideAllRow = rowCount++;
        collapseAutomaticallyRow = rowCount++;
        makeTransparentRow = rowCount++;
        miscellaneousEndDivisor = rowCount++;

        advancedHeaderRow = rowCount++;
        useRegexRow = rowCount++;
        regexPatternsRow = rowCount++;
        regexPatternsAdviceRow = rowCount++;
        logicModeRow = rowCount++;
        chatWhitelistRow = rowCount++;
        chatBlacklistRow = rowCount++;
        advancedEndDivisor = rowCount++;

        if (listAdapter != null && notify) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private boolean hasChanges() {
        boolean keywordsChanged = outlineEditText != null
                && !TextUtils.equals(NimarkoConfig.getMsgFiltersElements(), outlineEditText.getEditText().getText().toString());
        boolean regexChanged = regexEditText != null
                && !TextUtils.equals(NimarkoConfig.getMsgFiltersRegexPatterns(), regexEditText.getEditText().getText().toString());
        return keywordsChanged || regexChanged;
    }

    private static int countCsvIds(String csv) {
        if (csv == null || csv.isEmpty()) return 0;
        int n = 0;
        for (String tok : csv.split(",")) {
            if (!tok.trim().isEmpty()) n++;
        }
        return n;
    }

    private void presentChatPicker(boolean whitelist) {
        final int account = currentAccount;
        final long ownerUid = UserConfig.getInstance(account).getClientUserId();
        if (ownerUid <= 0) return;
        String currentCsv = whitelist
                ? NimarkoConfig.getMsgFiltersChatWhitelist(account)
                : NimarkoConfig.getMsgFiltersChatBlacklist(account);
        ArrayList<Long> initial = new ArrayList<>();
        if (currentCsv != null && !currentCsv.isEmpty()) {
            for (String tok : currentCsv.split(",")) {
                String t = tok.trim();
                if (t.isEmpty()) continue;
                try { initial.add(Long.parseLong(t)); } catch (NumberFormatException ignored) {}
            }
        }
        UsersSelectActivity picker = new UsersSelectActivity(true, initial, 0);
        picker.setDelegate((ids, unused) -> {
            StringBuilder sb = new StringBuilder();
            if (ids != null) {
                for (int i = 0; i < ids.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append(ids.get(i));
                }
            }
            if (whitelist) {
                if (NimarkoConfig.setMsgFiltersChatWhitelist(account, ownerUid, sb.toString())) {
                    notifyRowChanged(chatWhitelistRow);
                }
            } else {
                if (NimarkoConfig.setMsgFiltersChatBlacklist(account, ownerUid, sb.toString())) {
                    notifyRowChanged(chatBlacklistRow);
                }
            }
        });
        presentFragment(picker);
    }

    private void checkDone(boolean finish) {
        if (doneButton == null || outlineEditText == null) return;

        if (finish && hasChanges()) {
            doOnDone(this);
        }

        boolean changed = hasChanges();

        doneButton.setEnabled(changed);

        doneButton.animate()
                .alpha(changed ? 1.0f : 0.0f)
                .scaleX(changed ? 1.0f : 0.0f)
                .scaleY(changed ? 1.0f : 0.0f)
                .setDuration(180)
                .start();
    }

    private void notifyRowsChanged(int... rows) {
        if (listAdapter == null) return;
        int count = listAdapter.getItemCount();
        for (int row : rows) {
            if (row >= 0 && row < count) {
                listAdapter.notifyItemChanged(row, false);
            }
        }
    }

    private void notifyRowChanged(int row) {
        notifyRowsChanged(row);
    }

    private void doOnDone(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }

        if (outlineEditText != null) {
            NimarkoConfig.putString(
                    "msgFiltersElements",
                    outlineEditText.getEditText().getText().toString()
            );
            outlineEditText.getEditText().clearFocus();
        }
        if (regexEditText != null) {
            NimarkoConfig.setMsgFiltersRegexPatterns(regexEditText.getEditText().getText().toString());
            regexEditText.getEditText().clearFocus();
        }

        AndroidUtilities.runOnUIThread(() -> AndroidUtilities.hideKeyboard(listView), 50);

        if (NimarkoConfig.isMsgFiltersHideFromBlocked()) {
            getMessagesController().getBlockedPeers(false);
        }
    }

    private UsersSelectActivity getUsersSelectActivity(int account) {
        MessagesFilterHelper messagesFilterHelper = MessagesFilterHelper.INSTANCE;

        ArrayList<Long> chatsList = new ArrayList<>();
        ArrayList<String> savedChats = messagesFilterHelper.getArrayList(
                account, messagesFilterHelper.getExcludedList());

        for (String chatIdStr : savedChats) {
            long chatId;
            try {
                chatId = Long.parseLong(chatIdStr);
            } catch (NumberFormatException e) {
                continue;
            }

            TLRPC.User user = getMessagesController().getUser(chatId);
            TLRPC.Chat chat = getMessagesController().getChat(-chatId);

            if (user != null) {
                chatsList.add(user.id);
            } else if (chat != null) {
                chatsList.add(-chat.id);
            }
        }

        UsersSelectActivity activity = new UsersSelectActivity(true, chatsList, 0);
        
        return activity;
    }

}
