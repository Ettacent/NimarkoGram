/* Modifications Copyright (C) 2026 Ettacent */

/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui;

import org.telegram.messenger.FileLog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.PhoneFormat.PhoneFormat;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeBind;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ManageChatTextCell;
import org.telegram.ui.Cells.ManageChatUserCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.EmptyTextProgressView;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ListContentReveal;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

import java.util.HashSet;
import app.nimarkogram.messenger.utils.UiAnimationClock;
public class PrivacyUsersActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private ListAdapter listViewAdapter;
    private EmptyTextProgressView emptyView;

    private final long presentationUserId = getUserConfig().getClientUserId();
    private boolean blockedPresentationSettled, listPresentationReady, pendingNativeAppearance;
    private final int presentationLoginTime = getUserConfig().loginTime;
    private final Rect presentationVisibleRect = new Rect();
    private static final int RETRY_BLOCKED = 1;
    private ActionBarMenuItem retryItem;

    private boolean publishBlockedPresentation(boolean wasVisible) {
        if (currentType != TYPE_BLOCKED || blockedPresentationSettled || getMessagesController().totalBlockedCount < 0) return false;
        blockedPresentationSettled = true;
        return wasVisible && getMessagesController().blockePeers.size() > 0;
    }

    private void wakeListPresentation() {
        if (listView instanceof MemberListView) ((MemberListView) listView).wakePresentation();
    }

    private boolean canAnimateListPresentation() {
        if (!(listView instanceof MemberListView) || ((MemberListView) listView).captureDepth != 0
                || isFinished || presentationUserId == 0 || presentationUserId != getUserConfig().getClientUserId()
                || presentationLoginTime != getUserConfig().loginTime || !listPresentationReady
                || isPaused || inTransitionAnimation || !isLastFragment()
                || parentLayout != null && (parentLayout.isSwipeInProgress() || parentLayout.isTransitionAnimationInProgress())
                || UiAnimationClock.isPaused() || !ListContentReveal.isMotionEnabled()) return false;
        View source = rowCount == 0 ? fragmentView : listView;
        if (source == null || !source.isAttachedToWindow() || !source.isShown() || !source.hasWindowFocus()
                || source.getWindowVisibility() != View.VISIBLE || source.getWidth() <= 0 || source.getHeight() <= 0
                || !source.getLocalVisibleRect(presentationVisibleRect)) return false;
        for (View view = source; view != null; view = view.getParent() instanceof View ? (View) view.getParent() : null) {
            if (view.getVisibility() != View.VISIBLE || view.getAlpha() < 1f) return false;
        }
        return true;
    }

    private void configureListAnimator(final RecyclerListView owner) {
        DefaultItemAnimator animator = new DefaultItemAnimator() {
            private final long normalAdd = getAddDuration(), normalRemove = getRemoveDuration(),
                    normalMove = getMoveDuration(), normalChange = getChangeDuration();
            @Override public void runPendingAnimations() {
                boolean appearance = owner == listView && pendingNativeAppearance;
                if (owner == listView) pendingNativeAppearance = false;
                setAddDuration(appearance ? 180 : normalAdd);
                setRemoveDuration(appearance ? 180 : normalRemove);
                setMoveDuration(appearance ? 180 : normalMove);
                setChangeDuration(appearance ? 180 : normalChange);
                setDelayAnimations(!appearance);
                if (owner != listView || !canAnimateListPresentation()) {
                    endAnimations();
                    return;
                }
                super.runPendingAnimations();
            }
            @Override public void endAnimations() {
                super.endAnimations();
                if (owner == listView) {
                    pendingNativeAppearance = false;
                    owner.invalidate();
                }
            }
            @Override protected void onAllAnimationsDone() {
                super.onAllAnimationsDone();
                if (owner == listView) owner.invalidate();
            }
            @Override protected void onAddAnimationUpdate(RecyclerView.ViewHolder holder) {
                super.onAddAnimationUpdate(holder);
                if (owner == listView) owner.invalidate();
            }
            @Override protected void onRemoveAnimationUpdate(RecyclerView.ViewHolder holder) {
                super.onRemoveAnimationUpdate(holder);
                if (owner == listView) owner.invalidate();
            }
            @Override protected void onMoveAnimationUpdate(RecyclerView.ViewHolder holder) {
                super.onMoveAnimationUpdate(holder);
                if (owner == listView) owner.invalidate();
            }
            @Override protected void onChangeAnimationUpdate(RecyclerView.ViewHolder holder) {
                super.onChangeAnimationUpdate(holder);
                if (owner == listView) owner.invalidate();
            }
        };

        owner.setItemAnimator(animator);
    }

    private class MemberListView extends RecyclerListView {
        private int captureDepth;
        private final Runnable presentationWake = this::wakePresentation;

        MemberListView(Context context) { super(context); }

        private void wakePresentation() {
            if (currentType != TYPE_BLOCKED) return;
            if (this != listView || captureDepth != 0) return;
            if (!canAnimateListPresentation() && getItemAnimator() != null) getItemAnimator().endAnimations();
            invalidate();
        }

        @Override protected void dispatchDraw(Canvas canvas) {
            if (currentType != TYPE_BLOCKED) { super.dispatchDraw(canvas); return; }
            if (captureDepth == 0 && (!canvas.isHardwareAccelerated() || !canAnimateListPresentation())
                    && getItemAnimator() != null && getItemAnimator().isRunning()) getItemAnimator().endAnimations();
            super.dispatchDraw(canvas);
        }

        @Override public void capture(Canvas canvas, RectF position) {
            captureDepth++;
            try { super.capture(canvas, position); } finally { captureDepth--; }
        }

        @Override protected void captureChildren(Canvas canvas, RectF position, long drawingTime) {
            captureDepth++;
            try { super.captureChildren(canvas, position, drawingTime); } finally { captureDepth--; }
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            UiAnimationClock.setResumeCallback(presentationWake, true);
            wakePresentation();
        }

        @Override protected void onDetachedFromWindow() {
            UiAnimationClock.setResumeCallback(presentationWake, false);
            if (currentType == TYPE_BLOCKED && this == listView && getItemAnimator() != null) getItemAnimator().endAnimations();
            super.onDetachedFromWindow();
        }

        @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); wakePresentation(); }
        @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); wakePresentation(); }
        @Override protected void onVisibilityChanged(View changedView, int visibility) { super.onVisibilityChanged(changedView, visibility); wakePresentation(); }
    }
    private int rowCount;
    private int blockUserRow;
    private int blockUserDetailRow;
    private int usersHeaderRow;
    private int usersStartRow;
    private int usersEndRow;
    private int usersDetailRow;
    private int deleteAllRow;

    private boolean blockedUsersActivity;

    private boolean isGroup;
    private ArrayList<Long> uidArray;
    private boolean isAlwaysShare;
    public int rulesType;

    private PrivacyActivityDelegate delegate;

    private int currentType;

    public static final int TYPE_PRIVACY = 0;
    public static final int TYPE_BLOCKED = 1;
    public static final int TYPE_FILTER = 2;

    public interface PrivacyActivityDelegate {
        void didUpdateUserList(ArrayList<Long> ids, boolean added);
    }

    public PrivacyUsersActivity() {
        super();
        currentType = TYPE_BLOCKED;
        blockedUsersActivity = true;
    }

    public PrivacyUsersActivity loadBlocked() {
        getMessagesController().getBlockedPeers(true);
        return this;
    }

    public PrivacyUsersActivity(int type, ArrayList<Long> users, boolean group, boolean always) {
        super();
        uidArray = users;
        isAlwaysShare = always;
        isGroup = group;
        blockedUsersActivity = false;
        currentType = type;
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        NotificationCenter.getInstance(currentAccount).addWeakObserver(this, NotificationCenter.updateInterfaces);
        if (currentType == TYPE_BLOCKED) {
            NotificationCenter.getInstance(currentAccount).addWeakObserver(this, NotificationCenter.blockedUsersDidLoad);
        }
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        pendingNativeAppearance = false;
        if (listView != null && listView.getItemAnimator() != null) listView.getItemAnimator().endAnimations();
        if (listView != null) listView.invalidate();
        super.onFragmentDestroy();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.updateInterfaces);
        if (currentType == TYPE_BLOCKED) {
            NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.blockedUsersDidLoad);
        }
    }

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        simpleActionBar.setBackButtonImage(R.drawable.ic_ab_back);
        if (currentType == TYPE_BLOCKED) {
            simpleActionBar.setTitle(LocaleController.getString(R.string.BlockedUsers));
        } else if (currentType == TYPE_FILTER) {
            if (isAlwaysShare) {
                simpleActionBar.setTitle(LocaleController.getString(R.string.FilterAlwaysShow));
            } else {
                simpleActionBar.setTitle(LocaleController.getString(R.string.FilterNeverShow));
            }
        } else {
            if (isGroup) {
                if (isAlwaysShare) {
                    simpleActionBar.setTitle(LocaleController.getString(R.string.AlwaysAllow));
                } else {
                    simpleActionBar.setTitle(LocaleController.getString(R.string.NeverAllow));
                }
            } else {
                if (isAlwaysShare) {
                    simpleActionBar.setTitle(LocaleController.getString(R.string.AlwaysShareWithTitle));
                } else {
                    simpleActionBar.setTitle(LocaleController.getString(R.string.NeverShareWithTitle));
                }
            }
        }
        simpleActionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == RETRY_BLOCKED && currentType == TYPE_BLOCKED && !isFinished
                        && presentationUserId == getUserConfig().getClientUserId()
                        && presentationLoginTime == getUserConfig().loginTime
                        && getMessagesController().totalBlockedCount < 0 && !getMessagesController().loadingBlockedPeers) {
                    getMessagesController().getBlockedPeers(true);
                    updateBlockedLoadState();
                }
            }
        });

        if (currentType == TYPE_BLOCKED) {
            retryItem = simpleActionBar.createMenu().addItem(RETRY_BLOCKED, LocaleController.getString(R.string.Retry));
            retryItem.setVisibility(View.GONE);
        }
        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;

        emptyView = currentType == TYPE_BLOCKED
                ? new EmptyTextProgressView(context, new View(context)) : new EmptyTextProgressView(context);
        if (currentType == TYPE_BLOCKED) {
            emptyView.setText(LocaleController.getString(R.string.NoBlocked));
        } else {
            emptyView.setText(LocaleController.getString(R.string.NoContacts));
        }

        listView = new MemberListView(context);
        listView.setSections();
        listView.setItemSelectorColorProvider(position -> {
            if (position == deleteAllRow) {
                return Theme.multAlpha(Theme.getColor(Theme.key_text_RedRegular), .12f);
            }
            return null;
        });
        listView.setSectionsDrawBackground(true);
        listView.setEmptyView(emptyView);
        listView.setLayoutManager(layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(listViewAdapter = new ListAdapter(context));
        if (currentType == TYPE_BLOCKED) configureListAnimator(listView);
        listView.setVerticalScrollbarPosition(LocaleController.isRTL ? RecyclerListView.SCROLLBAR_POSITION_LEFT : RecyclerListView.SCROLLBAR_POSITION_RIGHT);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setOnItemClickListener((view, position) -> {
            if (position == deleteAllRow) {
                AlertDialog alert = AlertsCreator.createSimpleAlert(getContext(),
                        LocaleController.getString(R.string.NotificationsDeleteAllExceptionTitle), LocaleController.getString(R.string.NotificationsDeleteAllExceptionAlert), LocaleController.getString(R.string.Delete), () -> {
                            uidArray.clear();
                            updateRows();
                            finishFragment();
                            if (delegate != null) {
                                delegate.didUpdateUserList(uidArray, true);
                            }
                        }, null).create();
                alert.show();
                alert.redPositive();
            } else if (position == blockUserRow) {
                if (currentType == TYPE_BLOCKED) {
                    presentFragment(new DialogOrContactPickerActivity());
                } else {
                    Bundle args = new Bundle();
                    args.putBoolean(isAlwaysShare ? "isAlwaysShare" : "isNeverShare", true);
                    if (isGroup) {
                        args.putInt("chatAddType", 1);
                    } else if (currentType == TYPE_FILTER) {
                        args.putInt("chatAddType", 2);
                    }
                    if (isAlwaysShare && rulesType == PrivacyControlActivity.PRIVACY_RULES_TYPE_INVITE) {
                        args.putBoolean("allowPremium", true);
                    } else if (rulesType == PrivacyControlActivity.PRIVACY_RULES_TYPE_GIFTS) {
                        args.putBoolean("allowMiniapps", true);
                    }
                    GroupCreateActivity fragment = new GroupCreateActivity(args);
                    fragment.setDelegate((premium, miniapps, ids) -> {
                        for (Long id1 : ids) {
                            if (uidArray.contains(id1)) {
                                continue;
                            }
                            uidArray.add(id1);
                        }
                        updateRows();
                        if (delegate != null) {
                            delegate.didUpdateUserList(uidArray, true);
                        }
                    });
                    presentFragment(fragment);
                }
            } else if (position >= usersStartRow && position < usersEndRow) {
                if (currentType == TYPE_BLOCKED) {
                    Bundle args = new Bundle();
                    args.putLong("user_id", getMessagesController().blockePeers.keyAt(position - usersStartRow));
                    presentFragment(new ProfileActivity(args));
                } else {
                    Bundle args = new Bundle();
                    long uid = uidArray.get(position - usersStartRow);
                    if (DialogObject.isUserDialog(uid)) {
                        args.putLong("user_id", uid);
                    } else {
                        args.putLong("chat_id", -uid);
                    }
                    presentFragment(new ProfileActivity(args));
                }
            }
        });

        listView.setOnItemLongClickListener((view, position) -> {
            if (position >= usersStartRow && position < usersEndRow) {
                if (currentType == TYPE_BLOCKED) {
                    showUnblockAlert(getMessagesController().blockePeers.keyAt(position - usersStartRow), view);
                } else {
                    showUnblockAlert(uidArray.get(position - usersStartRow), view);
                }
                return true;
            }
            return false;
        });

        if (currentType == TYPE_BLOCKED) {
            listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                    if (getMessagesController().blockedEndReached) {
                        return;
                    }
                    int firstVisibleItem = layoutManager.findFirstVisibleItemPosition();
                    int visibleItemCount = Math.abs(layoutManager.findLastVisibleItemPosition() - firstVisibleItem) + 1;
                    int totalItemCount = recyclerView.getAdapter().getItemCount();
                    if (visibleItemCount > 0) {
                        if (layoutManager.findLastVisibleItemPosition() >= totalItemCount - 10) {
                            getMessagesController().getBlockedPeers(false);
                        }
                    }
                }
            });

            updateBlockedLoadState();
        }

        updateRows();
        return fragmentView;
    }

    public void setDelegate(PrivacyActivityDelegate privacyActivityDelegate) {
        delegate = privacyActivityDelegate;
    }

    private void showUnblockAlert(Long uid, View view) {
        if (getParentActivity() == null) {
            return;
        }
        ItemOptions.makeOptions(this, view)
            .setScrimViewBackground(new ColorDrawable(Theme.getColor(Theme.key_windowBackgroundWhite)))
            .addIf(currentType == TYPE_BLOCKED, 0, LocaleController.getString(R.string.Unblock), () -> getMessagesController().unblockPeer(uid))
            .addIf(currentType != TYPE_BLOCKED, currentType == TYPE_PRIVACY ? R.drawable.msg_user_remove : 0, LocaleController.getString(R.string.Remove), true, () -> {
                uidArray.remove(uid);
                updateRows();
                if (delegate != null) {
                    delegate.didUpdateUserList(uidArray, false);
                }
                if (uidArray.isEmpty()) {
                    finishFragment();
                }
            })
            .setMinWidth(190)
            .show();
    }

    private void updateBlockedLoadState() {
        if (currentType != TYPE_BLOCKED || emptyView == null || listView == null) return;
        boolean unknown = getMessagesController().totalBlockedCount < 0;
        boolean failed = unknown && !getMessagesController().loadingBlockedPeers;
        if (retryItem != null) retryItem.setVisibility(failed ? View.VISIBLE : View.GONE);
        if (unknown && !failed) {
            listView.setEmptyView(null);
            emptyView.setVisibility(View.GONE);
        } else {
            emptyView.setText(failed ? LocaleController.getString(R.string.ErrorOccurred) + System.lineSeparator() + LocaleController.getString(R.string.TryAgain)
                    : LocaleController.getString(R.string.NoBlocked));
            emptyView.showTextView();
            listView.setEmptyView(emptyView);
        }
    }
    private void updateRows() {
        final int previousRows = rowCount;
        final boolean wasVisible = currentType == TYPE_BLOCKED && canAnimateListPresentation();
        rowCount = 0;
        usersStartRow = usersEndRow = usersDetailRow = -1;
        blockUserRow = -1;
        usersHeaderRow = -1;
        blockUserDetailRow = -1;
        deleteAllRow = -1;
        if (!blockedUsersActivity || getMessagesController().totalBlockedCount >= 0) {
            blockUserRow = rowCount++;
            if (currentType == TYPE_BLOCKED) {
                blockUserDetailRow = rowCount++;
            }

            int count;
            if (currentType == TYPE_BLOCKED) {
                count = getMessagesController().blockePeers.size();
            } else {
                count = uidArray.size();
            }
            if (count != 0) {
                if (currentType == TYPE_BLOCKED) {
                    usersHeaderRow = rowCount++;
                }
                usersStartRow = rowCount;
                rowCount += count;
                usersEndRow = rowCount;
                usersDetailRow = rowCount++;
                if (currentType != TYPE_BLOCKED) {
                    deleteAllRow = rowCount++;
                }
            } else {
                usersHeaderRow = -1;
                usersStartRow = -1;
                usersEndRow = -1;
                usersDetailRow = -1;
                deleteAllRow = -1;
            }


        }
        if (listViewAdapter != null) {
            boolean appearance = publishBlockedPresentation(wasVisible) && previousRows == 0;
            updateBlockedLoadState();
            if (appearance) {
                pendingNativeAppearance = true;
                listViewAdapter.notifyItemRangeInserted(0, rowCount);
            } else {
                if (currentType == TYPE_BLOCKED && listView.getItemAnimator() != null) listView.getItemAnimator().endAnimations();
            listViewAdapter.notifyDataSetChanged();
            }
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.updateInterfaces) {
            int mask = (Integer) args[0];
            if ((mask & MessagesController.UPDATE_MASK_AVATAR) != 0 || (mask & MessagesController.UPDATE_MASK_NAME) != 0) {
                updateVisibleRows(mask);
            }
        } else if (id == NotificationCenter.blockedUsersDidLoad && currentType == TYPE_BLOCKED) {
            updateRows();
        }
    }

    private void updateVisibleRows(int mask) {
        if (listView == null) {
            return;
        }
        int count = listView.getChildCount();
        for (int a = 0; a < count; a++) {
            View child = listView.getChildAt(a);
            if (child instanceof ManageChatUserCell) {
                ((ManageChatUserCell) child).update(mask);
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        wakeListPresentation();
        if (listViewAdapter != null) {
            listViewAdapter.notifyDataSetChanged();
        }
    }

    @Override public void onPause() {
        if (listView != null && listView.getItemAnimator() != null && currentType == TYPE_BLOCKED) listView.getItemAnimator().endAnimations();
        super.onPause();
    }

    @Override
    public void prepareFragmentToSlide(boolean topFragment, boolean beginSlide) {
        super.prepareFragmentToSlide(topFragment, beginSlide);
        if (listView != null && currentType == TYPE_BLOCKED && listView.getItemAnimator() != null) listView.getItemAnimator().endAnimations();
        if (topFragment && !beginSlide) wakeListPresentation();
    }

    @Override public void onTransitionAnimationStart(boolean isOpen, boolean backward) {
        listPresentationReady = false;
        if (listView != null && currentType == TYPE_BLOCKED && listView.getItemAnimator() != null) listView.getItemAnimator().endAnimations();
        super.onTransitionAnimationStart(isOpen, backward);
    }

    @Override public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        super.onTransitionAnimationEnd(isOpen, backward);
        listPresentationReady = isOpen;
        wakeListPresentation();
    }

    @Override public void onBecomeFullyVisible() {
        super.onBecomeFullyVisible();
        listPresentationReady = true;
        wakeListPresentation();
    }

    @Override public void onBecomeFullyHidden() {
        listPresentationReady = false;
        if (listView != null && currentType == TYPE_BLOCKED && listView.getItemAnimator() != null) listView.getItemAnimator().endAnimations();
        super.onBecomeFullyHidden();
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int viewType = holder.getItemViewType();
            return viewType == 0 || viewType == 2 || viewType == 4;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 0:
                    view = new ManageChatUserCell(mContext, 7, 6, true);
                    ((ManageChatUserCell) view).setDelegate((cell, click) -> {
                        if (click) {
                            showUnblockAlert((Long) cell.getTag(), cell);
                        }
                        return true;
                    });
                    break;
                case 1:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                case 2:
                    view = new ManageChatTextCell(mContext);
                    break;
                case 3:
                default:
                    HeaderCell headerCell = new HeaderCell(mContext, Theme.key_windowBackgroundWhiteBlueHeader, 21, 11, false);
                    headerCell.setHeight(43);
                    view = headerCell;
                    break;
                case 4:
                    TextCell textCell = new TextCell(parent.getContext());
                    textCell.setText(LocaleController.getString(R.string.NotificationsDeleteAllException), false);
                    textCell.setColors(-1, Theme.key_text_RedRegular);
                    view = textCell;
                    break;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case 0:
                    ManageChatUserCell userCell = (ManageChatUserCell) holder.itemView;
                    long uid;
                    if (currentType == TYPE_BLOCKED) {
                        uid = getMessagesController().blockePeers.keyAt(position - usersStartRow);
                    } else {
                        uid = uidArray.get(position - usersStartRow);
                    }
                    userCell.setTag(uid);
                    if (uid > 0) {
                        TLRPC.User user = getMessagesController().getUser(uid);
                        if (user != null) {
                            String number;
                            if (user.bot) {
                                number = LocaleController.getString(R.string.Bot).substring(0, 1).toUpperCase() + LocaleController.getString(R.string.Bot).substring(1);
                            } else if (user.phone != null && user.phone.length() != 0) {
                                number = PhoneFormat.getInstance().format("+" + user.phone);
                            } else {
                                number = LocaleController.getString(R.string.NumberUnknown);
                            }
                            userCell.setData(user, null, number, position != usersEndRow - 1);
                        }
                    } else {
                        TLRPC.Chat chat = getMessagesController().getChat(-uid);
                        if (chat != null) {
                            String subtitle;
                            if (chat.participants_count != 0) {
                                subtitle = LocaleController.formatPluralString("Members", chat.participants_count);
                            } else if (chat.has_geo) {
                                subtitle = LocaleController.getString(R.string.MegaLocation);
                            } else if (!ChatObject.isPublic(chat)) {
                                subtitle = LocaleController.getString(R.string.MegaPrivate);
                            } else {
                                subtitle = LocaleController.getString(R.string.MegaPublic);
                            }
                            userCell.setData(chat, null, subtitle, position != usersEndRow - 1);
                        }
                    }
                    break;
                case 1:
                    TextInfoPrivacyCell privacyCell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == blockUserDetailRow) {
                        if (currentType == TYPE_BLOCKED) {
                            privacyCell.setFixedSize(0);
                            privacyCell.setText(LocaleController.getString(R.string.BlockedUsersInfo));
                        } else {
                            privacyCell.setFixedSize(8);
                            privacyCell.setText(null);
                        }
                    } else if (position == usersDetailRow) {
                        privacyCell.setFixedSize(12);
                        privacyCell.setText("");
                    }
                    break;
                case 2:
                    ManageChatTextCell actionCell = (ManageChatTextCell) holder.itemView;
                    actionCell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueButton);
                    if (currentType == TYPE_BLOCKED) {
                        actionCell.setText(LocaleController.getString(R.string.BlockUser), null, R.drawable.msg_contact_add, false);
                    } else {
                        actionCell.setText(LocaleController.getString(R.string.PrivacyAddAnException), null, R.drawable.msg_contact_add, uidArray.size() > 0);
                    }
                    break;
                case 3:
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    if (position == usersHeaderRow) {
                        if (currentType == TYPE_BLOCKED) {
                            headerCell.setText(LocaleController.formatPluralString("BlockedUsersCount", getMessagesController().totalBlockedCount));
                        } else {
                            headerCell.setText(LocaleController.getString(R.string.PrivacyExceptions));
                        }
                    }
                    break;
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == deleteAllRow) {
                return 4;
            } else if (position == usersHeaderRow) {
                return 3;
            } else if (position == blockUserRow) {
                return 2;
            } else if (position == blockUserDetailRow || position == usersDetailRow) {
                return 1;
            } else {
                return 0;
            }
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();

        ThemeDescription.ThemeDescriptionDelegate cellDelegate = this::cellDelegateThemeColors;

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{ManageChatUserCell.class, ManageChatTextCell.class, HeaderCell.class}, null, null, null, Theme.key_windowBackgroundWhite));

//        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));

        themeDescriptions.add(new ThemeDescription(emptyView, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_emptyListPlaceholder));
        themeDescriptions.add(new ThemeDescription(emptyView, ThemeDescription.FLAG_PROGRESSBAR, null, null, null, null, Theme.key_progressCircle));

        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{ManageChatUserCell.class}, new String[]{"statusColor"}, null, null, cellDelegate, Theme.key_windowBackgroundWhiteGrayText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{ManageChatUserCell.class}, new String[]{"statusOnlineColor"}, null, null, cellDelegate, Theme.key_windowBackgroundWhiteBlueText));

        return themeDescriptions;
    }

    @Nullable
    @Override
    protected RecyclerListView getListViewForSimpleGlass() {
        return listView;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }
    @Override
    public void onThemeColorsChanged() {
        try {
            if (emptyView != null) {
                emptyView.setProgressBarColor(Theme.getColor(Theme.key_progressCircle));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        try {
            super.onThemeColorsChanged();
            cellDelegateThemeColors();

        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void cellDelegateThemeColors() {
    }

    @Override
    public void addThemeColorKeys(HashSet<Integer> keys) {
        super.addThemeColorKeys(keys);
        keys.add(Theme.key_windowBackgroundWhiteBlueIcon);
        keys.add(Theme.key_windowBackgroundWhiteBlueButton);
        keys.add(Theme.key_windowBackgroundWhiteGrayIcon);
        keys.add(Theme.key_windowBackgroundWhiteBlackText);
        keys.add(Theme.key_windowBackgroundWhiteBlueHeader);
        keys.add(Theme.key_avatar_backgroundPink);
        keys.add(Theme.key_avatar_backgroundBlue);
        keys.add(Theme.key_avatar_backgroundCyan);
        keys.add(Theme.key_avatar_backgroundGreen);
        keys.add(Theme.key_avatar_backgroundViolet);
        keys.add(Theme.key_avatar_backgroundOrange);
        keys.add(Theme.key_avatar_backgroundRed);
        keys.add(Theme.key_avatar_text);
        keys.add(Theme.key_windowBackgroundGrayShadow);
        keys.add(Theme.key_listSelector);
        keys.add(Theme.key_actionBarDefaultSelector);
        keys.add(Theme.key_actionBarDefaultTitle);
        keys.add(Theme.key_actionBarDefaultIcon);
        keys.add(Theme.key_actionBarDefault);
    }
}
