/* Modifications Copyright (C) 2026 Ettacent */

/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.AnimationNotificationsLocker;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.ringtone.RingtoneDataStore;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeBind;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Adapters.FiltersView;
import org.telegram.ui.Cells.GraySectionCell;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.SharedDocumentCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.Premium.LimitReachedBottomSheet;
import org.telegram.ui.FilteredSearchView;
import org.telegram.ui.PhotoPickerActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.StringTokenizer;

public class ChatAttachAlertDocumentLayout extends ChatAttachAlert.AttachAlertLayout {

    public interface DocumentSelectActivityDelegate {
        void didSelectFiles(ArrayList<String> files, String caption, ArrayList<TLRPC.MessageEntity> captionEntities, ArrayList<MessageObject> fmessages, boolean notify, int scheduleDate, int scheduleRepeatPeriod, long effectId, boolean invertMedia, long payStars);
        default void didSelectPhotos(ArrayList<SendMessagesHelper.SendingMediaInfo> photos, boolean notify, int scheduleDate, int scheduleRepeatPeriod, long payStars) {

        }

        default void startDocumentSelectActivity() {

        }

        default void startMusicSelectActivity() {
        }
    }

    public final static int TYPE_DEFAULT = 0;
    public final static int TYPE_MUSIC = 1;
    public final static int TYPE_RINGTONE = 2;

    private int type;

    private final static int ANIMATION_NONE = 0;
    private final static int ANIMATION_FORWARD = 1;
    private final static int ANIMATION_BACKWARD = 2;
    private int currentAnimationType;

    private RecyclerListView listView;
    private RecyclerListView backgroundListView;
    private ListAdapter listAdapter;
    private ListAdapter backgroundListAdapter;
    private LinearLayoutManager backgroundLayoutManager;
    private SearchAdapter searchAdapter;
    private LinearLayoutManager layoutManager;
    private ActionBarMenuItem searchItem;
    private ActionBarMenuItem sortItem;

    private FiltersView filtersView;
    private AnimatorSet filtersViewAnimator;
    private FlickerLoadingView loadingView;

    private boolean sendPressed;

    private StickerEmptyView emptyView;
    private float additionalTranslationY;

    private boolean hasFiles;

    private File currentDir;
    private boolean receiverRegistered = false;
    private DocumentSelectActivityDelegate delegate;
    private HashMap<String, ListItem> selectedFiles = new HashMap<>();
    public ArrayList<String> selectedFilesOrder = new ArrayList<>();
    private HashMap<FilteredSearchView.MessageHashId, MessageObject> selectedMessages = new HashMap<>();
    private boolean scrolling;
    private int maxSelectedFiles = -1;
    private boolean canSelectOnlyImageFiles;
    private boolean allowMusic;

    private boolean searching;

    private final int currentAccount;
    private final long ownerUserId;
    private boolean searchActive = true;
    private boolean destroyed;
    private boolean sortByName;

    private final static int search_button = 0;
    private final static int sort_button = 6;
    public boolean isSoundPicker;

    private static class ListItem {
        public int icon;
        public String title;
        public String subtitle = "";
        public String ext = "";
        public String thumb;
        public File file;
    }

    private static class HistoryEntry {
        int scrollItem, scrollOffset;
        File dir;
        String title;
    }

    private BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context arg0, Intent intent) {
            Runnable r = () -> {
                try {
                    if (currentDir == null) {
                        listRoots();
                    } else {
                        listFiles(currentDir);
                    }
                    updateSearchButton();
                } catch (Exception e) {
                    FileLog.e(e);
                }
            };
            if (Intent.ACTION_MEDIA_UNMOUNTED.equals(intent.getAction())) {
                listView.postDelayed(r, 1000);
            } else {
                r.run();
            }
        }
    };

    public ChatAttachAlertDocumentLayout(ChatAttachAlert alert, Context context, int type, Theme.ResourcesProvider resourcesProvider) {
        super(alert, context, resourcesProvider);
        currentAccount = alert.currentAccount;
        ownerUserId = UserConfig.getInstance(currentAccount).getClientUserId();
        listAdapter = new ListAdapter(context);
        allowMusic = type == TYPE_MUSIC;
        isSoundPicker = type == TYPE_RINGTONE;
        sortByName = SharedConfig.sortFilesByName;
        loadRecentFiles();

        searching = false;

        if (!receiverRegistered) {
            receiverRegistered = true;
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
            filter.addAction(Intent.ACTION_MEDIA_CHECKING);
            filter.addAction(Intent.ACTION_MEDIA_EJECT);
            filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
            filter.addAction(Intent.ACTION_MEDIA_NOFS);
            filter.addAction(Intent.ACTION_MEDIA_REMOVED);
            filter.addAction(Intent.ACTION_MEDIA_SHARED);
            filter.addAction(Intent.ACTION_MEDIA_UNMOUNTABLE);
            filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
            filter.addDataScheme("file");
            if (Build.VERSION.SDK_INT >= 33) {
                ApplicationLoader.applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                ApplicationLoader.applicationContext.registerReceiver(receiver, filter);
            }
        }

        ActionBarMenu menu = parentAlert.actionBar.createMenu();
        searchItem = menu.addItem(search_button, R.drawable.outline_header_search).setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override
            public void onSearchExpand() {
                searching = true;
                sortItem.setVisibility(View.GONE);
                parentAlert.makeFocusable(searchItem.getSearchField(), true);
            }

            @Override
            public void onSearchCollapse() {
                searching = false;
                searchAdapter.search(null, true);
                sortItem.setVisibility(View.VISIBLE);
                if (listView.getAdapter() != listAdapter) {
                    listView.setAdapter(listAdapter);
                }
                listAdapter.notifyDataSetChanged();
            }

            @Override
            public void onTextChanged(EditText editText) {
                searchAdapter.search(editText.getText().toString(), false);
            }

            @Override
            public void onSearchFilterCleared(FiltersView.MediaFilterData filterData) {
                searchAdapter.removeSearchFilter(filterData);
                searchAdapter.search(searchItem.getSearchField().getText().toString(), false);
                searchAdapter.updateFiltersView(true, null, null,true);
            }
        });
        searchItem.setSearchFieldHint(LocaleController.getString(R.string.Search));
        searchItem.setContentDescription(LocaleController.getString(R.string.Search));
        EditTextBoldCursor editText = searchItem.getSearchField();
        ThemeBind.textColor(editText, Theme.key_dialogTextBlack, resourcesProvider);
        ThemeBind.cursorColor(editText, Theme.key_dialogTextBlack, resourcesProvider);
        ThemeBind.hintTextColor(editText, Theme.key_chat_messagePanelHint, resourcesProvider);

        sortItem = menu.addItem(sort_button, sortByName ? R.drawable.msg_contacts_time : R.drawable.msg_contacts_name);
        sortItem.setContentDescription(LocaleController.getString(R.string.AccDescrContactSorting));

        addView(loadingView = new FlickerLoadingView(context, resourcesProvider));

        emptyView = new StickerEmptyView(context, loadingView, StickerEmptyView.STICKER_TYPE_SEARCH, resourcesProvider) {
            @Override
            public void setTranslationY(float translationY) {
                super.setTranslationY(translationY + additionalTranslationY);
            }

            @Override
            public float getTranslationY() {
                return super.getTranslationY() - additionalTranslationY;
            }
        };
        addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        emptyView.setVisibility(View.GONE);
        emptyView.setOnTouchListener((v, event) -> true);

        backgroundListView = new RecyclerListView(context, resourcesProvider) {
            Paint paint = new Paint();
            @Override
            protected void dispatchDraw(Canvas canvas) {
                if (currentAnimationType == ANIMATION_BACKWARD && getChildCount() > 0) {
                    float top = Integer.MAX_VALUE;
                    for (int i = 0; i < getChildCount(); i++) {
                        if (getChildAt(i).getY() < top) {
                            top = getChildAt(i).getY();
                        }
                    }
                    paint.setColor(Theme.getColor(Theme.key_dialogBackground));

                }
                super.dispatchDraw(canvas);
            }

            @Override
            public boolean onTouchEvent(MotionEvent e) {
                if (currentAnimationType != ANIMATION_NONE) {
                    return false;
                }
                return super.onTouchEvent(e);
            }
        };
        backgroundListView.setSectionsType(RecyclerListView.SECTIONS_TYPE_DATE);
        backgroundListView.setVerticalScrollBarEnabled(false);
        backgroundListView.setLayoutManager(backgroundLayoutManager = new FillLastLinearLayoutManager(context, LinearLayoutManager.VERTICAL, false, AndroidUtilities.dp(56), backgroundListView));
        backgroundListView.setClipToPadding(false);
        backgroundListView.setAdapter(backgroundListAdapter = new ListAdapter(context));
        addView(backgroundListView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        backgroundListView.setVisibility(View.GONE);

        listView = new RecyclerListView(context, resourcesProvider) {

            Paint paint = new Paint();
            @Override
            protected void dispatchDraw(Canvas canvas) {
                if (currentAnimationType == ANIMATION_FORWARD && getChildCount() > 0) {
                    float top = Integer.MAX_VALUE;
                    for (int i = 0; i < getChildCount(); i++) {
                        if (getChildAt(i).getY() < top) {
                            top = getChildAt(i).getY();
                        }
                    }
                    paint.setColor(Theme.getColor(Theme.key_dialogBackground));

                }
                super.dispatchDraw(canvas);

            }
        };
        listView.setSections();
        setBlur3Capture(listView);
        iBlur3CaptureView = listView;
        occupyStatusBar = true;
        occupyNavigationBar = true;
        listView.setSectionsType(RecyclerListView.SECTIONS_TYPE_DATE);
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutManager(layoutManager = new FillLastLinearLayoutManager(context, LinearLayoutManager.VERTICAL, false, AndroidUtilities.dp(56) + AndroidUtilities.statusBarHeight, listView) {
            @Override
            public void smoothScrollToPosition(RecyclerView recyclerView, RecyclerView.State state, int position) {
                LinearSmoothScroller linearSmoothScroller = new LinearSmoothScroller(recyclerView.getContext()) {
                    @Override
                    public int calculateDyToMakeVisible(View view, int snapPreference) {
                        int dy = super.calculateDyToMakeVisible(view, snapPreference);
                        dy -= (listView.getPaddingTop() - AndroidUtilities.statusBarHeight - AndroidUtilities.dp(56));
                        return dy;
                    }

                    @Override
                    protected int calculateTimeForDeceleration(int dx) {
                        return super.calculateTimeForDeceleration(dx) * 2;
                    }
                };
                linearSmoothScroller.setTargetPosition(position);
                startSmoothScroll(linearSmoothScroller);
            }
        });
        listView.setClipToPadding(false);
        listView.setAdapter(listAdapter);
        addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        searchAdapter = new SearchAdapter(context);

        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                parentAlert.updateLayout(ChatAttachAlertDocumentLayout.this, true, dy);
                updateEmptyViewPosition();

                if (listView.getAdapter() == searchAdapter) {
                    int firstVisibleItem = layoutManager.findFirstVisibleItemPosition();
                    int lastVisibleItem = layoutManager.findLastVisibleItemPosition();
                    int visibleItemCount = Math.abs(lastVisibleItem - firstVisibleItem) + 1;
                    int totalItemCount = recyclerView.getAdapter().getItemCount();
                    if (visibleItemCount > 0 && lastVisibleItem >= totalItemCount - 10) {
                        searchAdapter.loadMore();
                    }
                }
            }

            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    int offset = AndroidUtilities.dp(13);
                    int backgroundPaddingTop = parentAlert.getBackgroundPaddingTop();
                    int top = parentAlert.scrollOffsetY[0] - backgroundPaddingTop - offset;
                    if (top + backgroundPaddingTop < ActionBar.getCurrentActionBarHeight()) {
                        RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(0);
                        if (holder != null) {
                            final int dy = holder.itemView.getTop() - AndroidUtilities.statusBarHeight - AndroidUtilities.dp(56);
                            if (dy > 0) {
                                listView.smoothScrollBy(0, dy);
                            }
                        }
                    }
                }
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING && searching && listView.getAdapter() == searchAdapter) {
                    AndroidUtilities.hideKeyboard(parentAlert.getCurrentFocus());
                }
                scrolling = newState != RecyclerView.SCROLL_STATE_IDLE;
            }
        });

        listView.setOnItemClickListener((view, position) -> {
            Object object;
            if (listView.getAdapter() == listAdapter) {
                object = listAdapter.getItem(position);
            } else {
                object = searchAdapter.getItem(position);
            }
            if (object instanceof ListItem) {
                ListItem item = (ListItem) object;
                File file = item.file;
                boolean isExternalStorageManager = false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    isExternalStorageManager = Environment.isExternalStorageManager();
                }
                if (!BuildVars.NO_SCOPED_STORAGE && (item.icon == R.drawable.files_storage || item.icon == R.drawable.files_internal) && !isExternalStorageManager) {
                    if (!parentAlert.checkEditingCaptionLimit()) {
                        return;
                    }
                    delegate.startDocumentSelectActivity();
                } else if (file == null) {
                    if (item.icon == R.drawable.files_gallery) {
                        HashMap<Object, Object> selectedPhotos = new HashMap<>();
                        ArrayList<Object> selectedPhotosOrder = new ArrayList<>();
                        ChatActivity chatActivity;
                        if (parentAlert.baseFragment instanceof ChatActivity) {
                            chatActivity = (ChatActivity) parentAlert.baseFragment;
                        } else {
                            chatActivity = null;
                        }

                        PhotoPickerActivity fragment = new PhotoPickerActivity(0, MediaController.allMediaAlbumEntry, selectedPhotos, selectedPhotosOrder, 0, chatActivity != null, chatActivity, false);
                        fragment.setDocumentsPicker(true);
                        fragment.setDelegate(new PhotoPickerActivity.PhotoPickerActivityDelegate() {
                            @Override
                            public void selectedPhotosChanged() {

                            }

                            @Override
                            public void actionButtonPressed(boolean canceled, boolean notify, int scheduleDate, int scheduleRepeatPeriod) {
                                if (!canceled) {
                                    sendSelectedPhotos(selectedPhotos, selectedPhotosOrder, notify, scheduleDate);
                                }
                            }

                            @Override
                            public void onCaptionChanged(CharSequence text) {

                            }

                            @Override
                            public void onOpenInPressed() {
                                delegate.startDocumentSelectActivity();
                            }
                        });
                        fragment.setMaxSelectedPhotos(maxSelectedFiles, false);
                        parentAlert.presentFragment(fragment);
                        parentAlert.dismiss(true);
                    } else if (item.icon == R.drawable.files_music) {
                        if (delegate != null) {
                            delegate.startMusicSelectActivity();
                        }
                    } else {
                        int top = getTopForScroll();
                        prepareAnimation();
                        HistoryEntry he = listAdapter.history.remove(listAdapter.history.size() - 1);
                        parentAlert.actionBar.setTitle(he.title);
                        if (he.dir != null) {
                            listFiles(he.dir);
                        } else {
                            listRoots();
                        }
                        updateSearchButton();
                        layoutManager.scrollToPositionWithOffset(0, top);
                        runAnimation(ANIMATION_BACKWARD);
                    }
                } else if (file.isDirectory()) {
                    HistoryEntry he = new HistoryEntry();
                    View child = listView.getChildAt(0);
                    RecyclerView.ViewHolder holder = listView.findContainingViewHolder(child);
                    if (holder != null) {
                        he.scrollItem = holder.getAdapterPosition();
                        he.scrollOffset = child.getTop();
                        he.dir = currentDir;
                        he.title = parentAlert.actionBar.getTitle();

                        prepareAnimation();
                        listAdapter.history.add(he);
                        if (!listFiles(file)) {
                            listAdapter.history.remove(he);
                            return;
                        } else {
                            runAnimation(ANIMATION_FORWARD);
                        }
                        parentAlert.actionBar.setTitle(item.title);
                    }
                } else {
                    onItemClick(view, item);
                }
            } else {
                onItemClick(view, object);
            }
        });

        listView.setOnItemLongClickListener((view, position) -> {
            Object object;
            if (listView.getAdapter() == listAdapter) {
                object = listAdapter.getItem(position);
            } else {
                object = searchAdapter.getItem(position);
            }
            return onItemClick(view, object);
        });

        filtersView = new FiltersView(context, resourcesProvider);
        filtersView.setOnItemClickListener((view, position) -> {
            filtersView.cancelClickRunnables(true);
            searchAdapter.addSearchFilter(filtersView.getFilterAt(position));
        });
        ThemeBind.backgroundColor(filtersView, Theme.key_dialogBackground, resourcesProvider);
        addView(filtersView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 44, Gravity.TOP));
        filtersView.setTranslationY(-AndroidUtilities.dp(44));
        filtersView.setVisibility(INVISIBLE);

        listRoots();
        updateSearchButton();
        updateEmptyView();
    }

    ValueAnimator listAnimation;
    private void runAnimation(int animationType) {
        if (listAnimation != null) {
            listAnimation.cancel();
        }
        currentAnimationType = animationType;
        int listViewChildIndex = 0;
        for (int i = 0; i < getChildCount(); i++) {
            if (getChildAt(i) == listView) {
                listViewChildIndex = i;
                break;
            }
        }
        float xTranslate;
        if (animationType == ANIMATION_FORWARD) {
            xTranslate = AndroidUtilities.dp(150);
            backgroundListView.setAlpha(1f);
            backgroundListView.setScaleX(1f);
            backgroundListView.setScaleY(1f);
            backgroundListView.setTranslationX(0);
            removeView(backgroundListView);
            addView(backgroundListView, listViewChildIndex);
            backgroundListView.setVisibility(View.VISIBLE);
            listView.setTranslationX(xTranslate);
            listView.setAlpha(0f);
            listAnimation = ValueAnimator.ofFloat(1f, 0);
        } else {
            xTranslate = AndroidUtilities.dp(150);
            listView.setAlpha(0f);
            listView.setScaleX(0.95f);
            listView.setScaleY(0.95f);
            backgroundListView.setScaleX(1f);
            backgroundListView.setScaleY(1f);
            backgroundListView.setTranslationX(0f);
            backgroundListView.setAlpha(1f);
            removeView(backgroundListView);
            addView(backgroundListView, listViewChildIndex + 1);
            backgroundListView.setVisibility(View.VISIBLE);
            listAnimation = ValueAnimator.ofFloat(0f, 1f);
        }

        listAnimation.addUpdateListener(animation -> {
            float value = (float) animation.getAnimatedValue();
            if (animationType == ANIMATION_FORWARD) {
                listView.setTranslationX(xTranslate * value);
                listView.setAlpha(1f - value);
                listView.invalidate();

                backgroundListView.setAlpha(value);
                float s = 0.95f + value * 0.05f;
                backgroundListView.setScaleX(s);
                backgroundListView.setScaleY(s);
            } else {
                backgroundListView.setTranslationX(xTranslate * value);
                backgroundListView.setAlpha(Math.max(0, 1f - value));
                backgroundListView.invalidate();

                listView.setAlpha(value);
                float s = 0.95f + value * 0.05f;
                listView.setScaleX(s);
                listView.setScaleY(s);
                backgroundListView.invalidate();
            }
        });
        listAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                backgroundListView.setVisibility(View.GONE);
                currentAnimationType = ANIMATION_NONE;
                listView.setAlpha(1f);
                listView.setScaleX(1f);
                listView.setScaleY(1f);
                listView.setTranslationX(0f);
                listView.invalidate();
            }
        });
        if (animationType == ANIMATION_FORWARD) {
            listAnimation.setDuration(220);
        } else {
            listAnimation.setDuration(200);
        }
        listAnimation.setInterpolator(CubicBezierInterpolator.DEFAULT);
        listAnimation.start();
    }

    private void prepareAnimation() {
        backgroundListAdapter.history.clear();
        backgroundListAdapter.history.addAll(listAdapter.history);
        backgroundListAdapter.items.clear();
        backgroundListAdapter.items.addAll(listAdapter.items);
        backgroundListAdapter.recentItems.clear();
        backgroundListAdapter.recentItems.addAll(listAdapter.recentItems);
        backgroundListAdapter.notifyDataSetChanged();
        backgroundListView.setVisibility(View.VISIBLE);

        backgroundListView.setPadding(listView.getPaddingLeft(), listView.getPaddingTop(), listView.getPaddingRight(), listView.getPaddingBottom());
        int p = layoutManager.findFirstVisibleItemPosition();
        if (p >= 0) {
            View childView = layoutManager.findViewByPosition(p);
            if (childView != null) {
                backgroundLayoutManager.scrollToPositionWithOffset(p, childView.getTop() - backgroundListView.getPaddingTop());
            }
        }
    }

    @Override
    public void checkColors() {
        try {
            if (searchItem != null && searchItem.getSearchField() != null) {
                searchItem.getSearchField().setCursorColor(getThemedColor(Theme.key_dialogTextBlack));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onDestroy() {
        if (destroyed) return;
        destroyed = true;
        searchActive = false;
        searching = false;
        searchAdapter.invalidateSearch();
        if (filtersViewAnimator != null) {
            AnimatorSet animator = filtersViewAnimator;
            filtersViewAnimator = null;
            animator.cancel();
        }
        try {
            if (receiverRegistered) {
                ApplicationLoader.applicationContext.unregisterReceiver(receiver);
                receiverRegistered = false;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        parentAlert.actionBar.closeSearchField();
        ActionBarMenu menu = parentAlert.actionBar.createMenu();
        menu.removeView(sortItem);
        menu.removeView(searchItem);
    }

    @Override
    public void onMenuItemClick(int id) {
        if (id == sort_button) {
            SharedConfig.toggleSortFilesByName();
            sortByName = SharedConfig.sortFilesByName;
            sortRecentItems();
            sortFileItems();
            listAdapter.notifyDataSetChanged();
            sortItem.setIcon(sortByName ? R.drawable.msg_contacts_time : R.drawable.msg_contacts_name);
        }
    }

    @Override
    public int needsActionBar() {
        return 1;
    }

    @Override
    public int getCurrentItemTop() {
        if (listView.getChildCount() <= 0) {
            return Integer.MAX_VALUE;
        }
        View child = listView.getChildAt(0);
        RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findContainingViewHolder(child);
        int top = (int) child.getY() - AndroidUtilities.statusBarHeight - AndroidUtilities.dp(4) - AndroidUtilities.dp(8);
        int newOffset = top > 0 && holder != null && holder.getAdapterPosition() == 0 ? top : 0;
        if (top >= 0 && holder != null && holder.getAdapterPosition() == 0) {
            newOffset = top;
        }
        return newOffset + AndroidUtilities.dp(13);
    }

    @Override
    public void setTranslationY(float translationY) {
        super.setTranslationY(translationY);
        parentAlert.getSheetContainer().invalidate();
    }

    @Override
    public int getListTopPadding() {
        return listView.getPaddingTop();
    }

    @Override
    public int getFirstOffset() {
        return getListTopPadding() + AndroidUtilities.dp(5);
    }

    @Override
    public void onPreMeasure(int availableWidth, int availableHeight) {
        int padding;
        if (parentAlert.actionBar.isSearchFieldVisible() || parentAlert.sizeNotifierFrameLayout.measureKeyboardHeight() > AndroidUtilities.dp(20)) {
            padding = AndroidUtilities.dp(56);
            parentAlert.setAllowNestedScroll(false);
        } else {
            if (!AndroidUtilities.isTablet() && AndroidUtilities.displaySize.x > AndroidUtilities.displaySize.y) {
                padding = (int) (availableHeight / 3.5f);
            } else {
                padding = (availableHeight / 5 * 2);
            }
            padding -= AndroidUtilities.dp(1);
            if (padding < 0) {
                padding = 0;
            }
            parentAlert.setAllowNestedScroll(true);
        }
        padding += AndroidUtilities.statusBarHeight;
        listView.setPaddingWithoutRequestLayout(0, padding, 0, listPaddingBottom);
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) filtersView.getLayoutParams();
        layoutParams.topMargin = ActionBar.getCurrentActionBarHeight();
    }

    @Override
    public int getButtonsHideOffset() {
        return AndroidUtilities.dp(62);
    }

    @Override
    public void scrollToTop() {
        listView.smoothScrollToPosition(0);
    }

    @Override
    public int getSelectedItemsCount() {
        return selectedFiles.size() + selectedMessages.size();
    }

    @Override
    public boolean sendSelectedItems(boolean notify, int scheduleDate, int scheduleRepeatPeriod, long effectId, boolean invertMedia) {
        if (selectedFiles.size() == 0 && selectedMessages.size() == 0 || delegate == null || sendPressed) {
            return false;
        }
        final ArrayList<MessageObject> fmessages = new ArrayList<>();
        final Iterator<FilteredSearchView.MessageHashId> idIterator = selectedMessages.keySet().iterator();
        while (idIterator.hasNext()) {
            FilteredSearchView.MessageHashId hashId = idIterator.next();
            fmessages.add(selectedMessages.get(hashId));
        }
        final ArrayList<String> files = new ArrayList<>(selectedFilesOrder);

        final CharSequence[] message = new CharSequence[]{ parentAlert.getCommentView().getText() };
        final ArrayList<TLRPC.MessageEntity> captionEntities = MediaDataController.getInstance(parentAlert.currentAccount).getEntities(message, true);
        final String caption = message[0].toString();

        return AlertsCreator.ensurePaidMessageConfirmation(parentAlert.currentAccount, parentAlert.getDialogId(), (!TextUtils.isEmpty(caption) ? 1 : 0) + files.size() + parentAlert.getAdditionalMessagesCount(), payStars -> {
            sendPressed = true;
            delegate.didSelectFiles(files, caption, captionEntities, fmessages, notify, scheduleDate, scheduleRepeatPeriod, effectId, invertMedia, payStars);
            parentAlert.dismiss(true);
        });
    }

    private boolean onItemClick(View view, Object object) {
        boolean add;
        if (object instanceof ListItem) {
            ListItem item = (ListItem) object;
            if (item.file == null || item.file.isDirectory()) {
                return false;
            }
            String path = item.file.getAbsolutePath();
            if (selectedFiles.containsKey(path)) {
                selectedFiles.remove(path);
                selectedFilesOrder.remove(path);
                add = false;
            } else {
                if (!item.file.canRead()) {
                    showErrorBox(LocaleController.getString(R.string.AccessError));
                    return false;
                }
                if (canSelectOnlyImageFiles && item.thumb == null) {
                    showErrorBox(LocaleController.formatString("PassportUploadNotImage", R.string.PassportUploadNotImage));
                    return false;
                }
                if ((item.file.length() > FileLoader.DEFAULT_MAX_FILE_SIZE && !UserConfig.getInstance(UserConfig.selectedAccount).isPremium()) || item.file.length() > FileLoader.DEFAULT_MAX_FILE_SIZE_PREMIUM) {
                    LimitReachedBottomSheet limitReachedBottomSheet = new LimitReachedBottomSheet(parentAlert.baseFragment, parentAlert.getContainer().getContext(), LimitReachedBottomSheet.TYPE_LARGE_FILE, UserConfig.selectedAccount, null);
                    limitReachedBottomSheet.setVeryLargeFile(true);
                    limitReachedBottomSheet.show();
                    return false;
                }
                if (maxSelectedFiles >= 0 && selectedFiles.size() >= maxSelectedFiles) {
                    showErrorBox(LocaleController.formatString("PassportUploadMaxReached", R.string.PassportUploadMaxReached, LocaleController.formatPluralString("Files", maxSelectedFiles)));
                    return false;
                }
                if (isSoundPicker && !isRingtone(item.file)) {
                    return false;
                }
                if (item.file.length() == 0) {
                    return false;
                }
                if (parentAlert.storyMediaPicker) {

                }
                selectedFiles.put(path, item);
                selectedFilesOrder.add(path);
                add = true;
            }
            scrolling = false;
        } else if (object instanceof MessageObject) {
            MessageObject message = (MessageObject) object;
            FilteredSearchView.MessageHashId hashId = new FilteredSearchView.MessageHashId(message.getId(), message.getDialogId());
            if (selectedMessages.containsKey(hashId)) {
                selectedMessages.remove(hashId);
                add = false;
            } else {
                if (selectedMessages.size() >= 100) {
                    return false;
                }
                selectedMessages.put(hashId, message);
                add = true;
            }
        } else {
            return false;
        }

        if (parentAlert.isPollAttach) {
            if (selectedFiles.isEmpty() && selectedMessages.isEmpty() || delegate == null || sendPressed) {
                return true;
            }
            final ArrayList<MessageObject> fmessages = new ArrayList<>();
            for (FilteredSearchView.MessageHashId hashId : selectedMessages.keySet()) {
                fmessages.add(selectedMessages.get(hashId));
            }
            final ArrayList<String> files = new ArrayList<>(selectedFilesOrder);
            delegate.didSelectFiles(files, null, null, fmessages, false, 0, 0, 0, false, 0);
            return true;
        }

        if (view instanceof SharedDocumentCell) {
            ((SharedDocumentCell) view).setChecked(add, true);
        }
        parentAlert.updateCountButton(add ? 1 : 2);
        return true;
    }

    public boolean isRingtone(File file) {
        String mimeType = null;
        String extension = FileLoader.getFileExtension(file);
        if (extension != null) {
            mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        }
        if (file.length() == 0 || mimeType == null || !RingtoneDataStore.ringtoneSupportedMimeType.contains(mimeType)) {
            BulletinFactory.of(parentAlert.getContainer(), null).createErrorBulletinSubtitle(LocaleController.formatString("InvalidFormatError", R.string.InvalidFormatError), LocaleController.getString(R.string.ErrorRingtoneInvalidFormat), null).show();
            return false;
        }
        if (file.length() > MessagesController.getInstance(UserConfig.selectedAccount).ringtoneSizeMax) {
            BulletinFactory.of(parentAlert.getContainer(), null).createErrorBulletinSubtitle(LocaleController.formatString("TooLargeError", R.string.TooLargeError), LocaleController.formatString("ErrorRingtoneSizeTooBig", R.string.ErrorRingtoneSizeTooBig, (MessagesController.getInstance(UserConfig.selectedAccount).ringtoneSizeMax / 1024)), null).show();
            return false;
        }

        int millSecond;
        try {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            mmr.setDataSource(ApplicationLoader.applicationContext, Uri.fromFile(file));
            String durationStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            millSecond = Integer.parseInt(durationStr);
        } catch (Exception e) {
            millSecond = Integer.MAX_VALUE;
        }

        if (millSecond > MessagesController.getInstance(UserConfig.selectedAccount).ringtoneDurationMax * 1000) {
            BulletinFactory.of(parentAlert.getContainer(), null).createErrorBulletinSubtitle(LocaleController.formatString("TooLongError", R.string.TooLongError), LocaleController.formatString("ErrorRingtoneDurationTooLong", R.string.ErrorRingtoneDurationTooLong, MessagesController.getInstance(UserConfig.selectedAccount).ringtoneDurationMax), null).show();
            return false;
        }

        return true;
    }

    public void setMaxSelectedFiles(int value) {
        maxSelectedFiles = value;
    }

    public void setCanSelectOnlyImageFiles(boolean value) {
        canSelectOnlyImageFiles = value;
    }

    private void sendSelectedPhotos(HashMap<Object, Object> photos, ArrayList<Object> order, boolean notify, int scheduleDate) {
        if (photos.isEmpty() || delegate == null || sendPressed) {
            return;
        }
        sendPressed = true;
        ArrayList<SendMessagesHelper.SendingMediaInfo> media = new ArrayList<>();
        for (int a = 0; a < order.size(); a++) {
            Object object = photos.get(order.get(a));
            SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
            media.add(info);
            if (object instanceof MediaController.PhotoEntry) {
                MediaController.PhotoEntry photoEntry = (MediaController.PhotoEntry) object;
                if (photoEntry.imagePath != null) {
                    info.path = photoEntry.imagePath;
                } else {
                    info.path = photoEntry.path;
                }
                info.thumbPath = photoEntry.thumbPath;
                info.coverPath = photoEntry.coverPath;
                info.videoEditedInfo = photoEntry.editedInfo;
                info.isVideo = photoEntry.isVideo;
                info.caption = photoEntry.caption != null ? photoEntry.caption.toString() : null;
                info.entities = photoEntry.entities;
                info.masks = photoEntry.stickers;
                info.ttl = photoEntry.ttl;
            }
        }
        AlertsCreator.ensurePaidMessageConfirmation(parentAlert.currentAccount, parentAlert.getDialogId(), media.size() + parentAlert.getAdditionalMessagesCount(), payStars -> {
            delegate.didSelectPhotos(media, notify, scheduleDate, 0, payStars);
        });
    }

    public void loadRecentFiles() {
        try {
            if (isSoundPicker) {
                String[] projection = {
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.DATA,
                        MediaStore.Audio.Media.DURATION,
                        MediaStore.Audio.Media.SIZE,
                        MediaStore.Audio.Media.MIME_TYPE
                };
                try (Cursor cursor = ApplicationLoader.applicationContext.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, MediaStore.Audio.Media.IS_MUSIC + " != 0", null, MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
                    while (cursor.moveToNext()) {
                        File file = new File(cursor.getString(1));
                        long duration = cursor.getLong(2);
                        long fileSize = cursor.getLong(3);
                        String mimeType = cursor.getString(4);

                        if (duration > MessagesController.getInstance(UserConfig.selectedAccount).ringtoneDurationMax * 1000 || fileSize > MessagesController.getInstance(UserConfig.selectedAccount).ringtoneSizeMax || (!TextUtils.isEmpty(mimeType) && !("audio/mpeg".equals(mimeType) || !"audio/mpeg4".equals(mimeType)))) {
                            continue;
                        }

                        ListItem item = new ListItem();
                        item.title = file.getName();
                        item.file = file;
                        String fname = file.getName();
                        String[] sp = fname.split("\\.");
                        item.ext = sp.length > 1 ? sp[sp.length - 1] : "?";
                        item.subtitle = AndroidUtilities.formatFileSize(file.length());
                        fname = fname.toLowerCase();
                        if (fname.endsWith(".jpg") || fname.endsWith(".png") || fname.endsWith(".gif") || fname.endsWith(".jpeg")) {
                            item.thumb = file.getAbsolutePath();
                        }
                        listAdapter.recentItems.add(item);
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
            } else {
                checkDirectory(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
                sortRecentItems();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void checkDirectory(File rootDir) {
        File[] files = rootDir.listFiles();
        File storiesDir = FileLoader.checkDirectory(FileLoader.MEDIA_DIR_STORIES);
        if (files != null) {
            for (int a = 0; a < files.length; a++) {
                File file = files[a];
                if (file.isDirectory() && file.getName().equals("NimarkoGram")) {
                    checkDirectory(file);
                    continue;
                }
                if (file.equals(storiesDir)) {
                    continue;
                }
                ListItem item = new ListItem();
                item.title = file.getName();
                item.file = file;
                String fname = file.getName();
                String[] sp = fname.split("\\.");
                item.ext = sp.length > 1 ? sp[sp.length - 1] : "?";
                item.subtitle = AndroidUtilities.formatFileSize(file.length());
                fname = fname.toLowerCase();
                if (fname.endsWith(".jpg") || fname.endsWith(".png") || fname.endsWith(".gif") || fname.endsWith(".jpeg")) {
                    item.thumb = file.getAbsolutePath();
                }
                listAdapter.recentItems.add(item);
            }
        }
    }

    private void sortRecentItems() {
        Collections.sort(listAdapter.recentItems, (o1, o2) -> {
            if (sortByName) {
                String lm = o1.file.getName();
                String rm = o2.file.getName();
                return lm.compareToIgnoreCase(rm);
            } else {
                long lm = o1.file.lastModified();
                long rm = o2.file.lastModified();
                if (lm == rm) {
                    return 0;
                } else if (lm > rm) {
                    return -1;
                } else {
                    return 1;
                }
            }
        });
    }

    private void sortFileItems() {
        if (currentDir == null) {
            return;
        }
        Collections.sort(listAdapter.items, (lhs, rhs) -> {
            if (lhs.file == null) {
                return -1;
            } else if (rhs.file == null) {
                return 1;
            }
            boolean isDir1 = lhs.file.isDirectory();
            boolean isDir2 = rhs.file.isDirectory();
            if (isDir1 != isDir2) {
                return isDir1 ? -1 : 1;
            } else if (isDir1 || sortByName) {
                return lhs.file.getName().compareToIgnoreCase(rhs.file.getName());
            } else {
                long lm = lhs.file.lastModified();
                long rm = rhs.file.lastModified();
                if (lm == rm) {
                    return 0;
                } else if (lm > rm) {
                    return -1;
                } else {
                    return 1;
                }
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
        if (searchAdapter != null) {
            searchAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public void onShow(ChatAttachAlert.AttachAlertLayout previousLayout) {
        if (destroyed) return;
        searchActive = true;
        searching = false;
        selectedFiles.clear();
        selectedMessages.clear();
        searchAdapter.clear();
        if (listView.getAdapter() != listAdapter) listView.setAdapter(listAdapter);
        selectedFilesOrder.clear();
        listAdapter.history.clear();
        listRoots();
        updateSearchButton();
        updateEmptyView();
        parentAlert.actionBar.setTitle(LocaleController.getString(R.string.SelectFile));
        sortItem.setVisibility(VISIBLE);
        layoutManager.scrollToPositionWithOffset(0, 0);
    }

    @Override
    public void onHide() {
        searchActive = false;
        searching = false;
        searchAdapter.invalidateSearch();
        if (filtersViewAnimator != null) {
            AnimatorSet animator = filtersViewAnimator;
            filtersViewAnimator = null;
            animator.cancel();
        }
        sortItem.setVisibility(GONE);
        searchItem.setVisibility(GONE);
    }

    private void updateEmptyViewPosition() {
        if (emptyView.getVisibility() != VISIBLE) {
            return;
        }
        View child = listView.getChildAt(0);
        if (child == null) {
            return;
        }
        float oldTranslation = emptyView.getTranslationY();
        additionalTranslationY = (emptyView.getMeasuredHeight() - getMeasuredHeight() + child.getTop()) / 2;
        emptyView.setTranslationY(oldTranslation);
    }

    private void updateEmptyView() {
        boolean visible;
        if (listView.getAdapter() == searchAdapter) {
            visible = searchAdapter.searchResult.isEmpty() && searchAdapter.sections.isEmpty();
        } else {
            visible = listAdapter.getItemCount() == 1;
        }
        emptyView.setVisibility(visible ? VISIBLE : GONE);
        updateEmptyViewPosition();
    }

    private void updateSearchButton() {
        if (searchItem == null) {
            return;
        }
        if (!searchItem.isSearchFieldVisible()) {
            searchItem.setVisibility(hasFiles || listAdapter.history.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private int getTopForScroll() {
        View child = listView.getChildAt(0);
        RecyclerView.ViewHolder holder = listView.findContainingViewHolder(child);
        int top = -listView.getPaddingTop();
        if (holder != null && holder.getAdapterPosition() == 0) {
            top += child.getTop();
        }
        return top;
    }

    private boolean canClosePicker() {
        if (listAdapter.history.size() > 0) {
            prepareAnimation();
            HistoryEntry he = listAdapter.history.remove(listAdapter.history.size() - 1);
            parentAlert.actionBar.setTitle(he.title);
            int top = getTopForScroll();
            if (he.dir != null) {
                listFiles(he.dir);
            } else {
                listRoots();
            }
            updateSearchButton();
            layoutManager.scrollToPositionWithOffset(0, top);
            runAnimation(ANIMATION_BACKWARD);
            return false;
        }
        return true;
    }

    @Override
    public boolean onBackPressed() {
        if (!canClosePicker()) {
            return true;
        }
        return super.onBackPressed();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateEmptyViewPosition();
    }

    public void setDelegate(DocumentSelectActivityDelegate documentSelectActivityDelegate) {
        delegate = documentSelectActivityDelegate;
    }

    private boolean listFiles(File dir) {
        hasFiles = false;
        if (!dir.canRead()) {
            if (dir.getAbsolutePath().startsWith(Environment.getExternalStorageDirectory().toString())
                    || dir.getAbsolutePath().startsWith("/sdcard")
                    || dir.getAbsolutePath().startsWith("/mnt/sdcard")) {
                if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)
                        && !Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED_READ_ONLY)) {
                    currentDir = dir;
                    listAdapter.items.clear();
                    String state = Environment.getExternalStorageState();
                    AndroidUtilities.clearDrawableAnimation(listView);
                    scrolling = true;
                    listAdapter.notifyDataSetChanged();
                    return true;
                }
            }
            showErrorBox(LocaleController.getString(R.string.AccessError));
            return false;
        }
        File[] files;
        try {
            files = dir.listFiles();
        } catch (Exception e) {
            showErrorBox(e.getLocalizedMessage());
            return false;
        }
        if (files == null) {
            showErrorBox(LocaleController.getString(R.string.UnknownError));
            return false;
        }
        currentDir = dir;
        listAdapter.items.clear();

        File storiesDir = FileLoader.checkDirectory(FileLoader.MEDIA_DIR_STORIES);
        for (int a = 0; a < files.length; a++) {
            File file = files[a];
            if (file.getName().indexOf('.') == 0) {
                continue;
            }

            if (file.equals(storiesDir)) {
                continue;
            }
            ListItem item = new ListItem();
            item.title = file.getName();
            item.file = file;
            if (file.isDirectory()) {
                item.icon = R.drawable.files_folder;
                item.subtitle = LocaleController.getString(R.string.Folder);
            } else {
                hasFiles = true;
                String fname = file.getName();
                String[] sp = fname.split("\\.");
                item.ext = sp.length > 1 ? sp[sp.length - 1] : "?";
                item.subtitle = AndroidUtilities.formatFileSize(file.length());
                fname = fname.toLowerCase();
                if (fname.endsWith(".jpg") || fname.endsWith(".png") || fname.endsWith(".gif") || fname.endsWith(".jpeg")) {
                    item.thumb = file.getAbsolutePath();
                }
            }
            listAdapter.items.add(item);
        }
        ListItem item = new ListItem();
        item.title = "..";
        if (listAdapter.history.size() > 0) {
            HistoryEntry entry = listAdapter.history.get(listAdapter.history.size() - 1);
            if (entry.dir == null) {
                item.subtitle = LocaleController.getString(R.string.Folder);
            } else {
                item.subtitle = entry.dir.toString();
            }
        } else {
            item.subtitle = LocaleController.getString(R.string.Folder);
        }
        item.icon = R.drawable.files_folder;
        item.file = null;
        listAdapter.items.add(0, item);
        sortFileItems();
        updateSearchButton();
        AndroidUtilities.clearDrawableAnimation(listView);
        scrolling = true;
        int top = getTopForScroll();
        listAdapter.notifyDataSetChanged();
        layoutManager.scrollToPositionWithOffset(0, top);
        return true;
    }

    private void showErrorBox(String error) {
        new AlertDialog.Builder(getContext(), resourcesProvider).setTitle(LocaleController.getString(R.string.AppName)).setMessage(error).setPositiveButton(LocaleController.getString(R.string.OK), null).show();
    }

    @SuppressLint("NewApi")
    private void listRoots() {
        currentDir = null;
        hasFiles = false;
        listAdapter.items.clear();

        HashSet<String> paths = new HashSet<>();
        boolean isExternalStorageManager = false;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            isExternalStorageManager = Environment.isExternalStorageManager();
        }

            String defaultPath = Environment.getExternalStorageDirectory().getPath();
            String defaultPathState = Environment.getExternalStorageState();
            if (defaultPathState.equals(Environment.MEDIA_MOUNTED) || defaultPathState.equals(Environment.MEDIA_MOUNTED_READ_ONLY)) {
                ListItem ext = new ListItem();
                if (Environment.isExternalStorageRemovable()) {
                    ext.title = LocaleController.getString(R.string.SdCard);
                    ext.icon = R.drawable.files_internal;
                    ext.subtitle = LocaleController.getString(R.string.ExternalFolderInfo);
                } else {
                    ext.title = LocaleController.getString(R.string.InternalStorage);
                    ext.icon = R.drawable.files_storage;
                    ext.subtitle = LocaleController.getString(R.string.InternalFolderInfo);
                }
                ext.file = Environment.getExternalStorageDirectory();
                listAdapter.items.add(ext);
                paths.add(defaultPath);
            }

            BufferedReader bufferedReader = null;
            try {
                bufferedReader = new BufferedReader(new FileReader("/proc/mounts"));
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    if (line.contains("vfat") || line.contains("/mnt")) {
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d(line);
                        }
                        StringTokenizer tokens = new StringTokenizer(line, " ");
                        String unused = tokens.nextToken();
                        String path = tokens.nextToken();
                        if (paths.contains(path)) {
                            continue;
                        }
                        if (line.contains("/dev/block/vold")) {
                            if (!line.contains("/mnt/secure") && !line.contains("/mnt/asec") && !line.contains("/mnt/obb") && !line.contains("/dev/mapper") && !line.contains("tmpfs")) {
                                if (!new File(path).isDirectory()) {
                                    int index = path.lastIndexOf('/');
                                    if (index != -1) {
                                        String newPath = "/storage/" + path.substring(index + 1);
                                        if (new File(newPath).isDirectory()) {
                                            path = newPath;
                                        }
                                    }
                                }
                                paths.add(path);
                                try {
                                    ListItem item = new ListItem();
                                    if (path.toLowerCase().contains("sd")) {
                                        item.title = LocaleController.getString(R.string.SdCard);
                                    } else {
                                        item.title = LocaleController.getString(R.string.ExternalStorage);
                                    }
                                    item.subtitle = LocaleController.getString(R.string.ExternalFolderInfo);
                                    item.icon = R.drawable.files_internal;
                                    item.file = new File(path);
                                    listAdapter.items.add(item);
                                } catch (Exception e) {
                                    FileLog.e(e);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (bufferedReader != null) {
                    try {
                        bufferedReader.close();
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
            }

        ListItem fs;
        try {
            File telegramPath = new File(ApplicationLoader.applicationContext.getExternalFilesDir(null), "NimarkoGram");
            if (telegramPath.exists()) {
                fs = new ListItem();
                fs.title = "NimarkoGram";
                fs.subtitle = LocaleController.getString(R.string.AppFolderInfo);
                fs.icon = R.drawable.files_folder;
                fs.file = telegramPath;
                listAdapter.items.add(fs);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }

        if (!isSoundPicker && (parentAlert == null || !parentAlert.isPollAttach)) {
            fs = new ListItem();
            fs.title = LocaleController.getString(R.string.Gallery);
            fs.subtitle = LocaleController.getString(R.string.GalleryInfo);
            fs.icon = R.drawable.files_gallery;
            fs.file = null;
            listAdapter.items.add(fs);
        }

        if (allowMusic) {
            fs = new ListItem();
            fs.title = LocaleController.getString(R.string.AttachMusic);
            fs.subtitle = LocaleController.getString(R.string.MusicInfo);
            fs.icon = R.drawable.files_music;
            fs.file = null;
            listAdapter.items.add(fs);
        }
        if (!listAdapter.recentItems.isEmpty()) {
            hasFiles = true;
        }

        AndroidUtilities.clearDrawableAnimation(listView);
        scrolling = true;
        listAdapter.notifyDataSetChanged();
    }

    private String getRootSubtitle(String path) {
        try {
            StatFs stat = new StatFs(path);
            long total = (long) stat.getBlockCount() * (long) stat.getBlockSize();
            long free = (long) stat.getAvailableBlocks() * (long) stat.getBlockSize();
            if (total == 0) {
                return "";
            }
            return LocaleController.formatString("FreeOfTotal", R.string.FreeOfTotal, AndroidUtilities.formatFileSize(free), AndroidUtilities.formatFileSize(total));
        } catch (Exception e) {
            FileLog.e(e);
        }
        return path;
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private ArrayList<ListItem> items = new ArrayList<>();
        private ArrayList<HistoryEntry> history = new ArrayList<>();
        private ArrayList<ListItem> recentItems = new ArrayList<>();

        private Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == 1;
        }

        @Override
        public int getItemCount() {
            int count = items.size();
            if (history.isEmpty() && !recentItems.isEmpty()) {
                count += recentItems.size() + 2;
            }
            return count + 1;
        }

        public ListItem getItem(int position) {
            int itemsSize = items.size();
            if (position < itemsSize) {
                return items.get(position);
            } else if (history.isEmpty() && !recentItems.isEmpty() && position != itemsSize && position != itemsSize + 1) {
                position -= items.size() + 2;
                if (position < recentItems.size()) {
                    return recentItems.get(position);
                }
            }
            return null;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == getItemCount() - 1) {
                return 3;
            } else {
                int itemsSize = items.size();
                if (position == itemsSize) {
                    return 2;
                } else if (position == itemsSize + 1) {
                    return 0;
                }
            }
            return 1;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 0:
                    view = new HeaderCell(mContext, resourcesProvider);
                    break;
                case 1:
                    view = new SharedDocumentCell(mContext, SharedDocumentCell.VIEW_TYPE_PICKER, resourcesProvider);
                    break;
                case 2:
                    view = new ShadowSectionCell(mContext);

                    break;
                case 3:
                default:
                    view = new View(mContext);
                    view.setTag(RecyclerListView.TAG_NOT_SECTION);
                    break;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case 0:
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    if (sortByName) {
                        headerCell.setText(LocaleController.getString(R.string.RecentFilesAZ));
                    } else {
                        headerCell.setText(LocaleController.getString(R.string.RecentFiles));
                    }
                    break;
                case 1:
                    ListItem item = getItem(position);
                    SharedDocumentCell documentCell = (SharedDocumentCell) holder.itemView;
                    if (item.icon != 0) {
                        documentCell.setTextAndValueAndTypeAndThumb(item.title, item.subtitle, null, null, item.icon, position != items.size() - 1);
                    } else {
                        String type = item.ext.toUpperCase().substring(0, Math.min(item.ext.length(), 4));
                        documentCell.setTextAndValueAndTypeAndThumb(item.title, item.subtitle, type, item.thumb, 0, false);
                    }
                    if (item.file != null) {
                        documentCell.setChecked(selectedFiles.containsKey(item.file.toString()), !scrolling);
                    } else {
                        documentCell.setChecked(false, !scrolling);
                    }
                    break;
            }
        }

        @Override
        public void notifyDataSetChanged() {
            super.notifyDataSetChanged();
            updateEmptyView();
        }
    }

    public class SearchAdapter extends RecyclerListView.SectionsAdapter  {

        private Context mContext;

        private ArrayList<ListItem> searchResult = new ArrayList<>();
        private Runnable searchRunnable;
        private Runnable localSearchRunnable;

        private long currentSearchDialogId;
        private FiltersView.MediaFilterData currentSearchFilter;
        private long currentSearchMinDate;
        private long currentSearchMaxDate;

        private volatile long searchIndex;
        private int nextSearchRate;

        private final FilteredSearchView.MessageHashId messageHashIdTmp = new FilteredSearchView.MessageHashId(0, 0);

        private String lastMessagesSearchString;
        private String currentDataQuery;

        private String currentDataFilterQueryString;
        private String activeSearchQuery;
        private String activeTipsQueryKey;
        private volatile TipsTask tipsTask;
        private TipsOwner tipsOwner;

        private final class TipsTask {
            final String key, query;
            final boolean searchChats;
            final Runnable work;
            ArrayList<Object> chats;
            ArrayList<FiltersView.DateData> dates;
            boolean completed;

            TipsTask(String key, String query, boolean searchChats) {
                this.key = key;
                this.query = query;
                this.searchChats = searchChats;
                work = () -> loadTips(this);
            }
        }

        private final class TipsOwner {
            final TipsTask task;
            final long generation;

            TipsOwner(TipsTask task, long generation) {
                this.task = task;
                this.generation = generation;
            }
        }
        private ArrayList<Object> localTipChats = new ArrayList<>();
        private ArrayList<FiltersView.DateData> localTipDates = new ArrayList<>();

        public ArrayList<MessageObject> messages = new ArrayList<>();
        public SparseArray<MessageObject> messagesById = new SparseArray<>();
        public ArrayList<String> sections = new ArrayList<>();
        public HashMap<String, ArrayList<MessageObject>> sectionArrays = new HashMap<>();

        private ArrayList<FiltersView.MediaFilterData> currentSearchFilters = new ArrayList<>();

        private boolean isLoading;
        private volatile long requestIndex;
        private int currentRequestId;
        private boolean requestPending;
        private boolean firstLoading = true;
        private AnimationNotificationsLocker notificationsLocker = new AnimationNotificationsLocker();
        private boolean endReached;

        private ViewTreeObserver resultsObserver;
        private ViewTreeObserver.OnPreDrawListener resultsPreDrawListener;
        private AnimatorSet resultsAnimator;
        private boolean resultsNotificationsLocked;
        private Animator progressAnimator;
        private View progressAnimationView;
        private RecyclerView.LayoutManager progressAnimationLayoutManager;
        private boolean progressViewIgnored;
        private final ArrayList<View> animatedResultViews = new ArrayList<>();

        public SearchAdapter(Context context) {
            mContext = context;
        }

        private boolean isCurrentSearch(long generation) {
            return generation == searchIndex && !destroyed && searchActive
                    && parentAlert.currentAccount == currentAccount
                    && ownerUserId == UserConfig.getInstance(currentAccount).getClientUserId();
        }

        private boolean isCurrentRequest(long generation, long request) {
            return isCurrentSearch(generation) && request == requestIndex;
        }

        private long invalidateSearch() {
            return invalidateSearch(false);
        }

        private long invalidateSearch(boolean preserveTips) {
            final long generation = ++searchIndex;
            activeSearchQuery = null;
            activeTipsQueryKey = null;
            final TipsTask oldTips = preserveTips ? null : tipsTask;
            if (!preserveTips) tipsTask = null;
            tipsOwner = null;
            ++requestIndex;
            final Runnable local = localSearchRunnable;
            localSearchRunnable = null;
            final Runnable global = searchRunnable;
            searchRunnable = null;
            final int networkRequest = currentRequestId;
            currentRequestId = 0;
            requestPending = false;
            isLoading = false;
            cancelResultsAnimation();
            if (local != null) AndroidUtilities.cancelRunOnUIThread(local);
            if (global != null) AndroidUtilities.cancelRunOnUIThread(global);
            if (networkRequest != 0) AccountInstance.getInstance(currentAccount).getConnectionsManager().cancelRequest(networkRequest, true);
            if (oldTips != null) AccountInstance.getInstance(currentAccount).getMessagesStorage().getStorageQueue().cancelRunnable(oldTips.work);
            return generation;
        }

        private long invalidateGlobalSearch() {
            final long request = ++requestIndex;
            final Runnable global = searchRunnable;
            searchRunnable = null;
            final int networkRequest = currentRequestId;
            currentRequestId = 0;
            requestPending = false;
            isLoading = false;
            if (global != null) AndroidUtilities.cancelRunOnUIThread(global);
            if (networkRequest != 0) AccountInstance.getInstance(currentAccount).getConnectionsManager().cancelRequest(networkRequest, true);
            return request;
        }

        private void clearGlobalResults() {
                    messages.clear();
            messagesById.clear();
                    sections.clear();
                    sectionArrays.clear();
            currentDataQuery = null;
            currentDataFilterQueryString = null;
            nextSearchRate = 0;
            endReached = false;
        }

        private void cancelResultsAnimation() {
            final ViewTreeObserver observer = resultsObserver;
            final ViewTreeObserver.OnPreDrawListener listener = resultsPreDrawListener;
            resultsObserver = null;
            resultsPreDrawListener = null;
            final AnimatorSet results = resultsAnimator;
            resultsAnimator = null;
            final Animator progress = progressAnimator;
            progressAnimator = null;
            final View progressView = progressAnimationView;
            final RecyclerView.LayoutManager progressLayoutManager = progressAnimationLayoutManager;
            final boolean ignored = progressViewIgnored;
            progressAnimationView = null;
            progressAnimationLayoutManager = null;
            progressViewIgnored = false;
            final boolean unlock = resultsNotificationsLocked;
            resultsNotificationsLocked = false;
            final ArrayList<View> views = new ArrayList<>(animatedResultViews);
            animatedResultViews.clear();
            if (observer != null && observer.isAlive() && listener != null) observer.removeOnPreDrawListener(listener);
            if (results != null) {
                results.removeAllListeners();
                results.cancel();
            }
            if (progress != null) {
                progress.removeAllListeners();
                progress.cancel();
            }
            for (View view : views) view.setAlpha(1f);
            clearProgressView(progressView, progressLayoutManager, ignored);
            if (unlock) notificationsLocker.unlock();
        }

        private void clearProgressView(View view, RecyclerView.LayoutManager manager, boolean ignored) {
            if (view == null) return;
            view.setAlpha(1f);
            if (view.getParent() == listView) {
                if (ignored && manager == listView.getLayoutManager()) manager.stopIgnoringView(view);
                listView.removeView(view);
            }
        }

        public void search(final String query, boolean reset) {
            final TipsTask currentTips = tipsTask;
            final boolean preserveTips = !canSelectOnlyImageFiles && listAdapter.history.isEmpty()
                    && !TextUtils.isEmpty(query) && TextUtils.equals(query, activeSearchQuery)
                    && currentTips != null && currentTips.key.equals(activeTipsQueryKey);
            final long generation = invalidateSearch(preserveTips);
            if (!isCurrentSearch(generation)) return;
            activeSearchQuery = query;
            if (TextUtils.isEmpty(query)) {
                    searchResult.clear();
                clearGlobalResults();
                lastMessagesSearchString = null;
                currentSearchFilter = null;
                firstLoading = true;
                localTipDates.clear();
                localTipChats.clear();
                if (listView.getAdapter() != listAdapter) {
                    listView.setAdapter(listAdapter);
                    if (!isCurrentSearch(generation)) return;
                }
                emptyView.showProgress(false);
                if (!isCurrentSearch(generation)) return;
                updateFiltersView(false, null, null, true);
                if (!isCurrentSearch(generation)) return;
                notifyDataSetChanged();
                return;
            } else {
                AndroidUtilities.runOnUIThread(localSearchRunnable = () -> {
                    if (!isCurrentSearch(generation)) return;
                    localSearchRunnable = null;
                    final ArrayList<ListItem> copy = new ArrayList<>(listAdapter.items);
                    if (listAdapter.history.isEmpty()) {
                        copy.addAll(0, listAdapter.recentItems);
                    }
                    boolean hasFilters = !currentSearchFilters.isEmpty();
                    if (!Utilities.searchQueue.postRunnable(() -> {
                        if (!isCurrentSearch(generation)) return;
                        String search1 = query.trim().toLowerCase();
                        if (search1.length() == 0) {
                            updateSearchResults(new ArrayList<>(), generation);
                            return;
                        }
                        String search2 = LocaleController.getInstance().getTranslitString(search1);
                        if (search1.equals(search2) || search2.length() == 0) {
                            search2 = null;
                        }
                        String[] search = new String[1 + (search2 != null ? 1 : 0)];
                        search[0] = search1;
                        if (search2 != null) {
                            search[1] = search2;
                        }

                        ArrayList<ListItem> resultArray = new ArrayList<>();

                        if (!hasFilters) {
                            for (int a = 0; a < copy.size(); a++) {
                                if (!isCurrentSearch(generation)) return;
                                ListItem entry = copy.get(a);
                                if (entry.file == null || entry.file.isDirectory()) {
                                    continue;
                                }
                                for (int b = 0; b < search.length; b++) {
                                    String q = search[b];

                                    boolean ok = false;
                                    if (entry.title != null) {
                                        ok = entry.title.toLowerCase().contains(q);
                                    }
                                    if (ok) {
                                        resultArray.add(entry);
                                        break;
                                    }
                                }
                            }
                        }

                        updateSearchResults(resultArray, generation);
                    })) {
                        updateSearchResults(new ArrayList<>(), generation);
                    }
                }, 300);
            }

            if (!canSelectOnlyImageFiles && listAdapter.history.isEmpty()) {
                long dialogId = 0;
                long minDate = 0;
                long maxDate = 0;
                for (int i = 0; i < currentSearchFilters.size(); i++) {
                    FiltersView.MediaFilterData data = currentSearchFilters.get(i);
                    if (data.filterType == FiltersView.FILTER_TYPE_CHAT) {
                        if (data.chat instanceof TLRPC.User) {
                            dialogId = ((TLRPC.User) data.chat).id;
                        } else if (data.chat instanceof TLRPC.Chat) {
                            dialogId = -((TLRPC.Chat) data.chat).id;
                        }
                    } else if (data.filterType == FiltersView.FILTER_TYPE_DATE) {
                        minDate = data.dateData.minDate;
                        maxDate = data.dateData.maxDate;
                    }
                }

                searchGlobal(dialogId, minDate, maxDate, FiltersView.filters[2], query, reset, generation);
            }
        }

        public void loadMore() {
            if (!isCurrentSearch(searchIndex) || TextUtils.isEmpty(activeSearchQuery)
                    || searchAdapter.isLoading || searchAdapter.endReached || currentSearchFilter == null) {
                return;
            }
            searchGlobal(currentSearchDialogId, currentSearchMinDate, currentSearchMaxDate, currentSearchFilter, lastMessagesSearchString, false, searchIndex);
        }

        public void removeSearchFilter(FiltersView.MediaFilterData filterData) {
            final long generation = invalidateSearch();
            if (!isCurrentSearch(generation)) return;
            currentSearchFilters.remove(filterData);
        }

        public void clear() {
            final long generation = invalidateSearch();
            if (generation != searchIndex) return;
            currentSearchFilters.clear();
            searchResult.clear();
            clearGlobalResults();
            lastMessagesSearchString = null;
            currentSearchFilter = null;
            localTipChats.clear();
            localTipDates.clear();
            firstLoading = true;
            if (isCurrentSearch(generation)) updateFiltersView(false, null, null, false);
        }

        private void addSearchFilter(FiltersView.MediaFilterData filter) {
            if (!currentSearchFilters.isEmpty()) {
                for (int i = 0; i < currentSearchFilters.size(); i++) {
                    if (filter.isSameType(currentSearchFilters.get(i))) {
                        return;
                    }
                }
            }
            final long generation = invalidateSearch();
            if (!isCurrentSearch(generation)) return;
            currentSearchFilters.add(filter);
            parentAlert.actionBar.setSearchFilter(filter);
            if (!isCurrentSearch(generation)) return;
            parentAlert.actionBar.setSearchFieldText("");
            if (!isCurrentSearch(searchIndex) || !currentSearchFilters.contains(filter)
                    || !TextUtils.isEmpty(activeSearchQuery)) return;
            updateFiltersView(true, null, null, true);
        }

        private void updateFiltersView(boolean showMediaFilters, ArrayList<Object> users, ArrayList<FiltersView.DateData> dates, boolean animated) {
            if (!isCurrentSearch(searchIndex)) return;
            boolean hasMediaFilter = false;
            boolean hasUserFilter = false;
            boolean hasDataFilter = false;

            for (int i = 0; i < currentSearchFilters.size(); i++) {
                if (currentSearchFilters.get(i).isMedia()) {
                    hasMediaFilter = true;
                } else if (currentSearchFilters.get(i).filterType == FiltersView.FILTER_TYPE_CHAT) {
                    hasUserFilter = true;
                } else if (currentSearchFilters.get(i).filterType == FiltersView.FILTER_TYPE_DATE) {
                    hasDataFilter = true;
                }
            }

            boolean visible = false;
            boolean hasUsersOrDates = (users != null && !users.isEmpty()) || (dates != null && !dates.isEmpty());
            if (!hasMediaFilter && !hasUsersOrDates && showMediaFilters) {
            } else if (hasUsersOrDates) {
                ArrayList<Object> finalUsers = (users != null && !users.isEmpty() && !hasUserFilter) ? users : null;
                ArrayList<FiltersView.DateData> finalDates = (dates != null && !dates.isEmpty() && !hasDataFilter) ? dates : null;
                if (finalUsers != null || finalDates != null) {
                    visible = true;
                    filtersView.setUsersAndDates(finalUsers, finalDates, false, currentAccount);
                }
            }
            if (!visible) {
                filtersView.setUsersAndDates(null, null, false, currentAccount);
            }
            filtersView.setEnabled(visible);
            if (animated && (visible && filtersView.getTag() != null || !visible && filtersView.getTag() == null)) {
                return;
            }
            filtersView.setTag(visible ? 1 : null);
            if (filtersViewAnimator != null) {
                AnimatorSet animator = filtersViewAnimator;
                filtersViewAnimator = null;
                animator.cancel();
            }
            if (animated) {
                if (visible) {
                    filtersView.setVisibility(VISIBLE);
                }
                filtersViewAnimator = new AnimatorSet();
                filtersViewAnimator.playTogether(
                        ObjectAnimator.ofFloat(listView, View.TRANSLATION_Y, visible ? AndroidUtilities.dp(44) : 0),
                        ObjectAnimator.ofFloat(filtersView, View.TRANSLATION_Y, visible ? 0 : -AndroidUtilities.dp(44)),
                        ObjectAnimator.ofFloat(loadingView, View.TRANSLATION_Y, visible ? AndroidUtilities.dp(44) : 0),
                        ObjectAnimator.ofFloat(emptyView, View.TRANSLATION_Y, visible ? AndroidUtilities.dp(44) : 0));
                filtersViewAnimator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (filtersViewAnimator != animation || destroyed || !searchActive) return;
                        if (filtersView.getTag() == null) {
                            filtersView.setVisibility(INVISIBLE);
                        }
                        filtersViewAnimator = null;
                    }
                });
                filtersViewAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT);
                filtersViewAnimator.setDuration(180);
                filtersViewAnimator.start();
            } else {
                filtersView.getAdapter().notifyDataSetChanged();
                listView.setTranslationY(visible ? AndroidUtilities.dp(44) : 0);
                filtersView.setTranslationY(visible ? 0 : -AndroidUtilities.dp(44));
                loadingView.setTranslationY(visible ? AndroidUtilities.dp(44) : 0);
                emptyView.setTranslationY(visible ? AndroidUtilities.dp(44) : 0);
                filtersView.setVisibility(visible ? VISIBLE : INVISIBLE);
            }
        }

        private boolean isCurrentTipsTask(TipsTask task) {
            return task == tipsTask && !destroyed && searchActive
                    && parentAlert.currentAccount == currentAccount
                    && ownerUserId == UserConfig.getInstance(currentAccount).getClientUserId();
        }

        private boolean isCurrentTipsOwner(TipsOwner owner) {
            return owner != null && owner == tipsOwner && owner.task == tipsTask
                    && isCurrentSearch(owner.generation) && owner.task.key.equals(activeTipsQueryKey);
        }

        private void searchTips(String key, String query, boolean searchChats, long generation) {
            if (!isCurrentSearch(generation) || !key.equals(activeTipsQueryKey)) return;
            TipsTask task = tipsTask;
            if (task != null && task.key.equals(key)) {
                if (tipsOwner != null && tipsOwner.task == task && tipsOwner.generation == generation
                        && isCurrentTipsOwner(tipsOwner)) return;
                TipsOwner owner = new TipsOwner(task, generation);
                tipsOwner = owner;
                if (task.completed) publishTips(owner);
                return;
            }
            final TipsTask previous = task;
            task = new TipsTask(key, query, searchChats);
            tipsTask = task;
            final TipsOwner owner = new TipsOwner(task, generation);
            tipsOwner = owner;
            final MessagesStorage storage = AccountInstance.getInstance(currentAccount).getMessagesStorage();
            if (previous != null) storage.getStorageQueue().cancelRunnable(previous.work);
            if (!isCurrentTipsOwner(owner)) return;
            if (!storage.getStorageQueue().postRunnable(task.work)) {
                if (!isCurrentTipsTask(task)) return;
                task.chats = new ArrayList<>();
                task.dates = new ArrayList<>();
                task.completed = true;
                final TipsOwner currentOwner = tipsOwner;
                if (currentOwner != null && currentOwner.task == task) publishTips(currentOwner);
            }
        }

        private void loadTips(TipsTask task) {
            if (!isCurrentTipsTask(task)) return;
            final ArrayList<Object> chats = new ArrayList<>();
            final ArrayList<FiltersView.DateData> dates = new ArrayList<>();
            try {
                if (task.searchChats) {
                    AccountInstance.getInstance(currentAccount).getMessagesStorage().localSearch(0, task.query,
                            chats, new ArrayList<>(), new ArrayList<>(), null, -1);
                }
                if (!isCurrentTipsTask(task)) return;
                FiltersView.fillTipDates(task.query, dates);
            } catch (Exception e) {
                FileLog.e(e);
            }
            if (!isCurrentTipsTask(task)) return;
            AndroidUtilities.runOnUIThread(() -> {
                final TipsOwner owner = tipsOwner;
                if (owner == null || owner.task != task || !isCurrentTipsOwner(owner)) return;
                task.chats = chats;
                task.dates = dates;
                task.completed = true;
                publishTips(owner);
            });
        }

        private void publishTips(TipsOwner owner) {
            if (!isCurrentTipsOwner(owner) || !owner.task.completed) return;
            final TipsTask task = owner.task;
            MessagesController.getInstance(currentAccount).cacheLocalSearchPeers(task.chats);
            if (!isCurrentTipsOwner(owner)) return;
            localTipChats.clear();
            localTipChats.addAll(task.chats);
            TLRPC.User self = UserConfig.getInstance(currentAccount).getCurrentUser();
            if (self != null && task.query.length() >= 3 && (LocaleController.getString(R.string.SavedMessages).toLowerCase().startsWith(task.query) || "saved messages".startsWith(task.query))) {
                boolean found = false;
                for (Object chat : localTipChats) {
                    if (chat instanceof TLRPC.User && self.id == ((TLRPC.User) chat).id) {
                        found = true;
                        break;
                    }
                }
                if (!found) localTipChats.add(0, self);
            }
            localTipDates.clear();
            localTipDates.addAll(task.dates);
            updateFiltersView(false, localTipChats, localTipDates, true);
        }

        private void searchGlobal(long dialogId, long minDate, long maxDate, FiltersView.MediaFilterData searchFilter, String query, boolean clearOldResults, long generation) {
            if (!isCurrentSearch(generation)) return;
            String currentSearchFilterQueryString = String.format(Locale.ENGLISH, "%d:%d:%d:%d:%s", dialogId, minDate, maxDate, searchFilter.filterType, query);
            final boolean dataIsSame = currentSearchFilterQueryString.equals(currentDataFilterQueryString);
            boolean forceClear = !dataIsSame && clearOldResults;
            activeTipsQueryKey = currentSearchFilterQueryString;
            if (!TextUtils.isEmpty(query) && (dataIsSame || tipsTask != null && tipsTask.key.equals(currentSearchFilterQueryString))) {
                searchTips(currentSearchFilterQueryString, query, dialogId == 0, generation);
                if (!isCurrentSearch(generation)) return;
            }
            if (clearOldResults && dataIsSame) return;
            final long requestId = invalidateGlobalSearch();
            if (!isCurrentRequest(generation, requestId)) return;
            currentSearchFilter = searchFilter;
            this.currentSearchDialogId = dialogId;
            this.currentSearchMinDate = minDate;
            this.currentSearchMaxDate = maxDate;

            if (TextUtils.isEmpty(query)) {
                clearGlobalResults();
                localTipDates.clear();
                localTipChats.clear();
                updateFiltersView(false, null, null, true);
                if (isCurrentRequest(generation, requestId)) notifyDataSetChanged();
                return;
            }
            if (forceClear) {
                clearGlobalResults();
                firstLoading = true;
                localTipChats.clear();
                localTipDates.clear();
            }
            isLoading = true;
            requestPending = true;
            if (!dataIsSame && !forceClear) {
                clearGlobalResults();
            }
            if (forceClear) {
                emptyView.setVisibility(View.VISIBLE);
                if (!isCurrentRequest(generation, requestId)) return;
                if (listView.getPinnedHeader() != null) listView.getPinnedHeader().setAlpha(0);
            }
            notifyDataSetChanged();
            if (!isCurrentRequest(generation, requestId)) return;
            if (!dataIsSame) {
                emptyView.showProgress(true, !clearOldResults);
                if (!isCurrentRequest(generation, requestId)) return;
            }

            final AccountInstance accountInstance = AccountInstance.getInstance(currentAccount);

            AndroidUtilities.runOnUIThread(searchRunnable = () -> {
                if (!isCurrentRequest(generation, requestId) || !requestPending) return;
                searchRunnable = null;
                TLObject request;

                if (dialogId != 0) {
                    final TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
                    req.q = query;
                    req.limit = 20;
                    req.filter = searchFilter.filter;
                    req.peer = accountInstance.getMessagesController().getInputPeer(dialogId);
                    if (minDate > 0) {
                        req.min_date = (int) (minDate / 1000);
                    }
                    if (maxDate > 0) {
                        req.max_date = (int) (maxDate / 1000);
                    }
                    if (dataIsSame && query.equals(lastMessagesSearchString) && !messages.isEmpty()) {
                        MessageObject lastMessage = messages.get(messages.size() - 1);
                        req.offset_id = lastMessage.getId();
                    } else {
                        req.offset_id = 0;
                    }
                    request = req;
                } else {

                    final TLRPC.TL_messages_searchGlobal req = new TLRPC.TL_messages_searchGlobal();
                    req.limit = 20;
                    req.q = query;
                    req.filter = searchFilter.filter;
                    if (minDate > 0) {
                        req.min_date = (int) (minDate / 1000);
                    }
                    if (maxDate > 0) {
                        req.max_date = (int) (maxDate / 1000);
                    }
                    if (dataIsSame && query.equals(lastMessagesSearchString) && !messages.isEmpty()) {
                        MessageObject lastMessage = messages.get(messages.size() - 1);
                        req.offset_id = lastMessage.getId();
                        req.offset_rate = nextSearchRate;
                        long id;
                        if (lastMessage.messageOwner.peer_id.channel_id != 0) {
                            id = -lastMessage.messageOwner.peer_id.channel_id;
                        } else if (lastMessage.messageOwner.peer_id.chat_id != 0) {
                            id = -lastMessage.messageOwner.peer_id.chat_id;
                        } else {
                            id = lastMessage.messageOwner.peer_id.user_id;
                        }
                        req.offset_peer = accountInstance.getMessagesController().getInputPeer(id);
                    } else {
                        req.offset_rate = 0;
                        req.offset_id = 0;
                        req.offset_peer = new TLRPC.TL_inputPeerEmpty();
                    }
                    request = req;
                }

                lastMessagesSearchString = query;
                if (!isCurrentRequest(generation, requestId)) return;
                final int networkRequestId = accountInstance.getConnectionsManager().sendRequest(request, (response, error) -> {
                    if (!isCurrentRequest(generation, requestId)) return;
                    ArrayList<MessageObject> messageObjects = new ArrayList<>();
                    if (error == null && response instanceof TLRPC.messages_Messages) {
                        TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                        int n = res.messages.size();
                        for (int i = 0; i < n; i++) {
                            if (!isCurrentRequest(generation, requestId)) return;
                            MessageObject messageObject = new MessageObject(accountInstance.getCurrentAccount(), res.messages.get(i), false, true);
                            messageObject.setQuery(query);
                            messageObjects.add(messageObject);
                        }
                    }

                    AndroidUtilities.runOnUIThread(() -> {
                        if (!isCurrentRequest(generation, requestId) || !requestPending) {
                            return;
                        }
                        currentRequestId = 0;
                        requestPending = false;
                        if (error != null || !(response instanceof TLRPC.messages_Messages)) {
                        isLoading = false;
                            emptyView.title.setText(LocaleController.getString(R.string.SearchEmptyViewTitle2));
                            if (!isCurrentRequest(generation, requestId)) return;
                            emptyView.subtitle.setVisibility(View.VISIBLE);
                            if (!isCurrentRequest(generation, requestId)) return;
                            emptyView.subtitle.setText(LocaleController.getString(R.string.SearchEmptyViewFilteredSubtitle2));
                            if (!isCurrentRequest(generation, requestId)) return;
                            emptyView.showProgress(false, true);
                            if (isCurrentRequest(generation, requestId)) notifyDataSetChanged();
                            return;
                        }


                        TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                        accountInstance.getMessagesStorage().putUsersAndChats(res.users, res.chats, true, true);
                        if (!isCurrentRequest(generation, requestId)) return;
                        accountInstance.getMessagesController().putUsers(res.users, false);
                        if (!isCurrentRequest(generation, requestId)) return;
                        accountInstance.getMessagesController().putChats(res.chats, false);
                        if (!isCurrentRequest(generation, requestId)) return;
                        if (!dataIsSame) clearGlobalResults();
                        nextSearchRate = res.next_rate;
                        int totalCount = res.count;
                        currentDataQuery = query;
                        currentDataFilterQueryString = currentSearchFilterQueryString;
                        int n = messageObjects.size();
                        for (int i = 0; i < n; i++) {
                            MessageObject messageObject = messageObjects.get(i);
                            ArrayList<MessageObject> messageObjectsByDate = sectionArrays.get(messageObject.monthKey);
                            if (messageObjectsByDate == null) {
                                messageObjectsByDate = new ArrayList<>();
                                sectionArrays.put(messageObject.monthKey, messageObjectsByDate);
                                sections.add(messageObject.monthKey);
                            }
                            messageObjectsByDate.add(messageObject);
                            messages.add(messageObject);
                            messagesById.put(messageObject.getId(), messageObject);
                        }
                        if (messages.size() > totalCount) {
                            totalCount = messages.size();
                        }
                        endReached = messages.size() >= totalCount;

                        firstLoading = false;
                        isLoading = false;
                        emptyView.showProgress(false);
                        if (!isCurrentRequest(generation, requestId)) return;
                        if (messages.isEmpty()) {
                            if (TextUtils.isEmpty(currentDataQuery) && dialogId == 0 && minDate == 0) {
                                emptyView.title.setText(LocaleController.getString(R.string.SearchEmptyViewTitle));
                                if (!isCurrentRequest(generation, requestId)) return;
                                emptyView.subtitle.setVisibility(View.VISIBLE);
                                if (!isCurrentRequest(generation, requestId)) return;
                                emptyView.subtitle.setText(LocaleController.getString(R.string.SearchEmptyViewFilteredSubtitleFiles));
                            } else {
                                emptyView.title.setText(LocaleController.getString(R.string.SearchEmptyViewTitle2));
                                if (!isCurrentRequest(generation, requestId)) return;
                                emptyView.subtitle.setVisibility(View.VISIBLE);
                                if (!isCurrentRequest(generation, requestId)) return;
                                emptyView.subtitle.setText(LocaleController.getString(R.string.SearchEmptyViewFilteredSubtitle2));
                            }
                            if (!isCurrentRequest(generation, requestId)) return;
                        }

                        cancelResultsAnimation();
                        if (!isCurrentRequest(generation, requestId)) return;
                        View progressView = null;
                        int progressViewPosition = -1;
                        for (int i = 0; i < n; i++) {
                            View child = listView.getChildAt(i);
                            if (child instanceof FlickerLoadingView) {
                                progressView = child;
                                progressViewPosition = listView.getChildAdapterPosition(child);
                            }
                        }
                        final View finalProgressView = progressView;
                        if (progressView != null) {
                            listView.removeView(progressView);
                        }
                        if (loadingView.getVisibility() == View.VISIBLE && listView.getChildCount() <= 1 || progressView != null) {
                            int finalProgressViewPosition = progressViewPosition;
                            final ViewTreeObserver observer = getViewTreeObserver();
                            resultsObserver = observer;
                            resultsPreDrawListener = new ViewTreeObserver.OnPreDrawListener() {
                                @Override
                                public boolean onPreDraw() {
                                    if (observer.isAlive()) observer.removeOnPreDrawListener(this);
                                    if (resultsPreDrawListener != this) return true;
                                    resultsPreDrawListener = null;
                                    resultsObserver = null;
                                    if (!isCurrentSearch(generation)) return true;
                                    int n = listView.getChildCount();
                                    AnimatorSet animatorSet = new AnimatorSet();
                                    resultsAnimator = animatorSet;
                                    for (int i = 0; i < n; i++) {
                                        View child = listView.getChildAt(i);
                                        if (finalProgressView != null) {
                                            if (listView.getChildAdapterPosition(child) < finalProgressViewPosition) {
                                                continue;
                                            }
                                        }
                                        animatedResultViews.add(child);
                                        child.setAlpha(0);
                                        int s = Math.min(listView.getMeasuredHeight(), Math.max(0, child.getTop()));
                                        int delay = (int) ((s / (float) listView.getMeasuredHeight()) * 100);
                                        ObjectAnimator a = ObjectAnimator.ofFloat(child, View.ALPHA, 0, 1f);
                                        a.setStartDelay(delay);
                                        a.setDuration(200);
                                        animatorSet.playTogether(a);
                                    }
                                    animatorSet.addListener(new AnimatorListenerAdapter() {
                                        @Override
                                        public void onAnimationEnd(Animator animation) {
                                            if (resultsAnimator != animation) return;
                                            resultsAnimator = null;
                                            animatedResultViews.clear();
                                            if (resultsNotificationsLocked) {
                                                resultsNotificationsLocked = false;
                                            notificationsLocker.unlock();
                                        }
                                        }
                                    });
                                    resultsNotificationsLocked = true;
                                    notificationsLocker.lock();
                                    if (!isCurrentSearch(generation) || resultsAnimator != animatorSet) return true;
                                    animatorSet.start();

                                    if (!isCurrentSearch(generation)) return true;
                                    if (finalProgressView != null && finalProgressView.getParent() == null) {
                                        RecyclerView.LayoutManager layoutManager = listView.getLayoutManager();
                                        if (layoutManager != null) {
                                            progressAnimationView = finalProgressView;
                                            progressAnimationLayoutManager = layoutManager;
                                            listView.addView(finalProgressView);
                                            if (!isCurrentSearch(generation) || progressAnimationView != finalProgressView) return true;
                                            layoutManager.ignoreView(finalProgressView);
                                            progressViewIgnored = true;
                                            Animator animator = ObjectAnimator.ofFloat(finalProgressView, ALPHA, finalProgressView.getAlpha(), 0);
                                            progressAnimator = animator;
                                            animator.addListener(new AnimatorListenerAdapter() {
                                                @Override
                                                public void onAnimationEnd(Animator animation) {
                                                    if (progressAnimator != animation) return;
                                                    progressAnimator = null;
                                                    progressAnimationView = null;
                                                    progressAnimationLayoutManager = null;
                                                    progressViewIgnored = false;
                                                    clearProgressView(finalProgressView, layoutManager, true);
                                                }
                                            });
                                            animator.start();
                                        }
                                    }
                                    return true;
                                }
                            };
                            if (observer.isAlive()) observer.addOnPreDrawListener(resultsPreDrawListener);
                        }
                        notifyDataSetChanged();
                    });
                });
                if (!isCurrentRequest(generation, requestId) || !requestPending) {
                    if (networkRequestId != 0) accountInstance.getConnectionsManager().cancelRequest(networkRequestId, true);
                } else {
                    currentRequestId = networkRequestId;
                    if (networkRequestId == 0) {
                        requestPending = false;
                        isLoading = false;
                        emptyView.showProgress(false, true);
                        if (isCurrentRequest(generation, requestId)) notifyDataSetChanged();
                    }
                }
                searchTips(currentSearchFilterQueryString, query, dialogId == 0, generation);
            }, (dataIsSame && !messages.isEmpty()) ? 0 : 350);
            loadingView.setViewType(FlickerLoadingView.FILES_TYPE);
        }

        private void updateSearchResults(final ArrayList<ListItem> result, long generation) {
            if (!isCurrentSearch(generation)) return;
            AndroidUtilities.runOnUIThread(() -> {
                if (!isCurrentSearch(generation) || !searching || TextUtils.isEmpty(activeSearchQuery)) return;
                searchResult = result;
                    if (listView.getAdapter() != searchAdapter) {
                        listView.setAdapter(searchAdapter);
                    if (!isCurrentSearch(generation)) return;
                }
                notifyDataSetChanged();
            });
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder, int section, int row) {
            int type = holder.getItemViewType();
            return type == 1 || type == 4;
        }

        @Override
        public int getSectionCount() {
            int count = 2;
            if (!sections.isEmpty()) {
                count += sections.size() + (endReached ? 0 : 1);
            }
            return count;
        }

        @Override
        public Object getItem(int section, int position) {
            if (section == 0) {
                if (position < searchResult.size()) {
                    return searchResult.get(position);
                }
            } else {
                section--;
                if (section < sections.size()) {
                    ArrayList<MessageObject> arrayList = sectionArrays.get(sections.get(section));
                    if (arrayList != null) {
                        int p = position - (section == 0 && searchResult.isEmpty() ? 0 : 1);
                        if (p >= 0 && p < arrayList.size()) {
                            return arrayList.get(p);
                        }
                    }
                }
            }
            return null;
        }

        @Override
        public int getCountForSection(int section) {
            if (section == 0) {
                return searchResult.size();
            }
            section--;
            if (section < sections.size()) {
                ArrayList<MessageObject> arrayList = sectionArrays.get(sections.get(section));
                if (arrayList != null) {
                    return arrayList.size() + (section == 0 && searchResult.isEmpty() ? 0 : 1);
                } else {
                    return 0;
                }
            }
            return 1;
        }

        @Override
        public View getSectionHeaderView(int section, View view) {
            GraySectionCell sectionCell = (GraySectionCell) view;
            if (sectionCell == null) {
                sectionCell = new GraySectionCell(mContext, resourcesProvider);
                sectionCell.setBackgroundColor(getThemedColor(Theme.key_graySection) & 0xf2ffffff);
            }
            if (section == 0 || section == 1 && searchResult.isEmpty()) {
                sectionCell.setAlpha(0f);
                return sectionCell;
            }
            section--;
            if (section < sections.size()) {
                sectionCell.setAlpha(1.0f);
                String name = sections.get(section);
                ArrayList<MessageObject> messageObjects = sectionArrays.get(name);
                if (messageObjects != null) {
                    MessageObject messageObject = messageObjects.get(0);
                    String str;
                    if (section == 0 && !searchResult.isEmpty()) {
                        str = LocaleController.getString(R.string.GlobalSearch);
                    } else {
                        str = LocaleController.formatSectionDate(messageObject.messageOwner.date);
                    }
                    sectionCell.setText(str);
                }
            }
            return view;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 0:
                    view = new GraySectionCell(mContext, resourcesProvider);
                    break;
                case 1:
                case 4:
                    SharedDocumentCell documentCell = new SharedDocumentCell(mContext, viewType == 1 ? SharedDocumentCell.VIEW_TYPE_PICKER : SharedDocumentCell.VIEW_TYPE_GLOBAL_SEARCH, resourcesProvider);
                    documentCell.setDrawDownloadIcon(false);
                    view = documentCell;
                    break;
                case 2:
                    FlickerLoadingView flickerLoadingView = new FlickerLoadingView(mContext, resourcesProvider);
                    flickerLoadingView.setViewType(FlickerLoadingView.FILES_TYPE);
                    flickerLoadingView.setIsSingleCell(true);
                    view = flickerLoadingView;
                    break;
                case 3:
                default:
                    view = new View(mContext);
                    view.setTag(RecyclerListView.TAG_NOT_SECTION);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(int section, int position, RecyclerView.ViewHolder holder) {
            int viewType = holder.getItemViewType();
            if (viewType == 2 || viewType == 3) {
                return;
            }
            switch (viewType) {
                case 0: {
                    section--;
                    String name = sections.get(section);
                    ArrayList<MessageObject> messageObjects = sectionArrays.get(name);
                    if (messageObjects == null) {
                        return;
                    }
                    MessageObject messageObject = messageObjects.get(0);
                    String str;
                    if (section == 0 && !searchResult.isEmpty()) {
                        str = LocaleController.getString(R.string.GlobalSearch);
                    } else {
                        str = LocaleController.formatSectionDate(messageObject.messageOwner.date);
                    }
                    ((GraySectionCell) holder.itemView).setText(str);
                    break;
                }
                case 1:
                case 4: {
                    SharedDocumentCell sharedDocumentCell = (SharedDocumentCell) holder.itemView;
                    if (section == 0) {
                        ListItem item = (ListItem) getItem(position);
                        SharedDocumentCell documentCell = (SharedDocumentCell) holder.itemView;
                        if (item.icon != 0) {
                            documentCell.setTextAndValueAndTypeAndThumb(item.title, item.subtitle, null, null, item.icon, false);
                        } else {
                            String type = item.ext.toUpperCase().substring(0, Math.min(item.ext.length(), 4));
                            documentCell.setTextAndValueAndTypeAndThumb(item.title, item.subtitle, type, item.thumb, 0, false);
                        }
                        if (item.file != null) {
                            documentCell.setChecked(selectedFiles.containsKey(item.file.toString()), !scrolling);
                        } else {
                            documentCell.setChecked(false, !scrolling);
                        }
                    } else {
                        section--;
                        if (section != 0 || !searchResult.isEmpty()) {
                            position--;
                        }
                        String name = sections.get(section);
                        ArrayList<MessageObject> messageObjects = sectionArrays.get(name);
                        if (messageObjects == null) {
                            return;
                        }
                        MessageObject messageObject = messageObjects.get(position);
                        boolean animated = sharedDocumentCell.getMessage() != null && sharedDocumentCell.getMessage().getId() == messageObject.getId();
                        sharedDocumentCell.setDocument(messageObject, position != messageObjects.size() - 1 || section == sections.size() - 1 && isLoading);
                        sharedDocumentCell.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                            @Override
                            public boolean onPreDraw() {
                                sharedDocumentCell.getViewTreeObserver().removeOnPreDrawListener(this);
                                if (parentAlert.actionBar.isActionModeShowed()) {
                                    messageHashIdTmp.set(messageObject.getId(), messageObject.getDialogId());
                                    sharedDocumentCell.setChecked(selectedMessages.containsKey(messageHashIdTmp), animated);
                                } else {
                                    sharedDocumentCell.setChecked(false, animated);
                                }
                                return true;
                            }
                        });
                    }
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int section, int position) {
            if (section == 0) {
                return 1;
            } else if (section == getSectionCount() - 1) {
                return 3;
            }
            section--;
            if (section < sections.size()) {
                if ((section != 0 || !searchResult.isEmpty()) && position == 0) {
                    return 0;
                } else {
                    return 4;
                }
            }
            return 2;
        }

        @Override
        public void notifyDataSetChanged() {
            super.notifyDataSetChanged();
            updateEmptyView();
        }

        @Override
        public String getLetter(int position) {
            return null;
        }

        @Override
        public void getPositionForScrollProgress(RecyclerListView listView, float progress, int[] position) {
            position[0] = 0;
            position[1] = 0;
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();
        themeDescriptions.add(new ThemeDescription(searchItem.getSearchField(), ThemeDescription.FLAG_CURSORCOLOR, null, null, null, null, Theme.key_dialogTextBlack));

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_dialogScrollGlow));

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUNDFILTER, new Class[]{ShadowSectionCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUNDFILTER | ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{ShadowSectionCell.class}, null, null, null, Theme.key_windowBackgroundGray));

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider));

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR, new Class[]{SharedDocumentCell.class}, new String[]{"nameTextView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR, new Class[]{SharedDocumentCell.class}, new String[]{"dateTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText3));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CHECKBOX, new Class[]{SharedDocumentCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_checkbox));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CHECKBOXCHECK, new Class[]{SharedDocumentCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_checkboxCheck));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_IMAGECOLOR, new Class[]{SharedDocumentCell.class}, new String[]{"thumbImageView"}, null, null, null, Theme.key_files_folderIcon));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_IMAGECOLOR | ThemeDescription.FLAG_BACKGROUNDFILTER, new Class[]{SharedDocumentCell.class}, new String[]{"thumbImageView"}, null, null, null, Theme.key_files_folderIconBackground));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR, new Class[]{SharedDocumentCell.class}, new String[]{"extTextView"}, null, null, null, Theme.key_files_iconText));
        return themeDescriptions;
    }
}
