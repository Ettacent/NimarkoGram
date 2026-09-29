package app.nimarkogram.messenger.preferences;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.CubicBezierInterpolator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.nimarkogram.messenger.NimarkoConfig;

public class MessageMenuOrderPreferencesActivity extends BaseFragment {

    private static final int MENU_RESET = 1;
    private static final IconBackgroundColors[] ROW_COLORS = {
            IconBackgroundColors.BLUE, IconBackgroundColors.PURPLE,
            IconBackgroundColors.GREEN, IconBackgroundColors.ORANGE,
            IconBackgroundColors.CYAN
    };

    private RecyclerListView listView;
    private ListAdapter adapter;
    private ItemTouchHelper itemTouchHelper;

    private final ArrayList<Integer> workingOrder = new ArrayList<>();

    private static final int[] CATALOGUE = new int[] {
            ChatActivity.OPTION_REPLY,
            ChatActivity.OPTION_COPY,
            ChatActivity.OPTION_FORWARD,
            ChatActivity.OPTION_DELETE,
            ChatActivity.OPTION_PIN,
            ChatActivity.OPTION_SAVE_TO_GALLERY,
            ChatActivity.OPTION_SAVE_TO_DOWNLOADS_OR_MUSIC,
            ChatActivity.OPTION_SHARE,
            ChatActivity.OPTION_TRANSLATE,
            ChatActivity.OPTION_EDIT,
    };

    private static Map<Integer, Integer> labelByOption() {
        Map<Integer, Integer> m = new HashMap<>();
        m.put(ChatActivity.OPTION_REPLY, R.string.Reply);
        m.put(ChatActivity.OPTION_COPY, R.string.Copy);
        m.put(ChatActivity.OPTION_FORWARD, R.string.Forward);
        m.put(ChatActivity.OPTION_EDIT, R.string.Edit);
        m.put(ChatActivity.OPTION_PIN, R.string.PinMessage);
        m.put(ChatActivity.OPTION_SAVE_TO_GALLERY, R.string.SaveToGallery);
        m.put(ChatActivity.OPTION_SAVE_TO_DOWNLOADS_OR_MUSIC, R.string.SaveToDownloads);
        m.put(ChatActivity.OPTION_SHARE, R.string.ShareFile);
        m.put(ChatActivity.OPTION_TRANSLATE, R.string.TranslateMessage);
        m.put(ChatActivity.OPTION_DELETE, R.string.Delete);
        return m;
    }

    private static Map<Integer, Integer> iconByOption() {
        Map<Integer, Integer> m = new HashMap<>();
        m.put(ChatActivity.OPTION_REPLY, R.drawable.menu_reply);
        m.put(ChatActivity.OPTION_COPY, R.drawable.msg_copy);
        m.put(ChatActivity.OPTION_FORWARD, R.drawable.msg_forward);
        m.put(ChatActivity.OPTION_EDIT, R.drawable.msg_edit);
        m.put(ChatActivity.OPTION_PIN, R.drawable.msg_pin);
        m.put(ChatActivity.OPTION_SAVE_TO_GALLERY, R.drawable.msg_gallery);
        m.put(ChatActivity.OPTION_SAVE_TO_DOWNLOADS_OR_MUSIC, R.drawable.msg_download);
        m.put(ChatActivity.OPTION_SHARE, R.drawable.msg_share);
        m.put(ChatActivity.OPTION_TRANSLATE, R.drawable.msg_translate);
        m.put(ChatActivity.OPTION_DELETE, R.drawable.msg_delete);
        return m;
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        buildWorkingOrder();
        return true;
    }

    private void buildWorkingOrder() {
        workingOrder.clear();
        Set<Integer> catalogue = new HashSet<>();
        for (int opt : CATALOGUE) catalogue.add(opt);

        List<Integer> persisted = NimarkoConfig.messageMenuOrder;
        Set<Integer> seen = new HashSet<>();
        if (persisted != null) {
            for (Integer opt : persisted) {
                if (opt != null && catalogue.contains(opt) && seen.add(opt)) {
                    workingOrder.add(opt);
                }
            }
        }
        for (int opt : CATALOGUE) {
            if (seen.add(opt)) workingOrder.add(opt);
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setTitle(getString(R.string.NM_Menu_Reorder));
        actionBar.setAllowOverlayTitle(false);
        actionBar.setOccupyStatusBar(!AndroidUtilities.isTablet());

        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem reset = menu.addItem(MENU_RESET, R.drawable.msg_reset);
        reset.setContentDescription(getString(R.string.NM_Menu_Reorder_Reset));

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_RESET) {
                    NimarkoConfig.resetMessageMenuOrder();
                    buildWorkingOrder();
                    if (adapter != null) adapter.notifyDataSetChanged();
                }
            }
        });

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        FrameLayout frameLayout = (FrameLayout) fragmentView;

        listView = new RecyclerListView(context);
        listView.setDrawSelection(false);
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        adapter = new ListAdapter(context);
        listView.setAdapter(adapter);
        if (listView.getItemAnimator() != null) {
            ((DefaultItemAnimator) listView.getItemAnimator()).setDelayAnimations(false);
            ((DefaultItemAnimator) listView.getItemAnimator()).setSupportsChangeAnimations(false);
        }
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        TextInfoPrivacyCell hint = new TextInfoPrivacyCell(context);
        hint.setText(getString(R.string.NM_Menu_Reorder_Desc));
        content.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        content.addView(listView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        frameLayout.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setSections(true);
        actionBar.setAdaptiveBackground(listView);
        ViewCompat.setOnApplyWindowInsetsListener(frameLayout, this::onInsetsInternal);
        ViewCompat.requestApplyInsets(frameLayout);

        itemTouchHelper = new ItemTouchHelper(new TouchHelperCallback());
        itemTouchHelper.attachToRecyclerView(listView);

        return fragmentView;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public boolean drawEdgeNavigationBar() {
        return false;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        if (listView != null) {
            listView.setPadding(listView.getPaddingLeft(), listView.getPaddingTop(),
                    listView.getPaddingRight(), bottom);
            listView.setClipToPadding(false);
        }
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        flush();
    }

    private void flush() {
        if (matchesCatalogue(workingOrder)) {
            NimarkoConfig.resetMessageMenuOrder();
        } else {
            NimarkoConfig.setMessageMenuOrder(new ArrayList<>(workingOrder));
        }
    }

    private static boolean matchesCatalogue(List<Integer> order) {
        if (order == null || order.size() != CATALOGUE.length) return false;
        for (int i = 0; i < CATALOGUE.length; i++) {
            Integer a = order.get(i);
            if (a == null || a != CATALOGUE[i]) return false;
        }
        return true;
    }

    private class TouchHelperCallback extends ItemTouchHelper.Callback {
        private MenuOrderCell draggedCell;
        @Override
        public boolean isLongPressDragEnabled() { return true; }

        @Override
        public int getMovementFlags(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
            return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView rv,
                              @NonNull RecyclerView.ViewHolder source,
                              @NonNull RecyclerView.ViewHolder target) {
            int from = source.getAdapterPosition();
            int to = target.getAdapterPosition();
            if (from < 0 || to < 0 || from >= workingOrder.size() || to >= workingOrder.size()) {
                return false;
            }
            Integer moved = workingOrder.remove(from);
            workingOrder.add(to, moved);
            adapter.notifyItemMoved(from, to);
            flush();
            return true;
        }

        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder vh, int actionState) {
            super.onSelectedChanged(vh, actionState);
            MenuOrderCell next = actionState == ItemTouchHelper.ACTION_STATE_DRAG && vh != null
                    ? (MenuOrderCell) vh.itemView : null;
            if (draggedCell != null && draggedCell != next) {
                draggedCell.setDragging(false, true);
            }
            draggedCell = next;
            listView.cancelClickRunnables(true);
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && vh != null) {
                ((MenuOrderCell) vh.itemView).setDragging(true, true);
                try {
                    vh.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                } catch (Throwable ignored) {}
            }
        }

        @Override
        public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
            super.clearView(rv, vh);
            ((MenuOrderCell) vh.itemView).setDragging(false, true);
            if (draggedCell == vh.itemView) draggedCell = null;
            listView.cancelClickRunnables(true);
            adapter.notifyItemRangeChanged(0, workingOrder.size());
            vh.itemView.announceForAccessibility(((TextCell) vh.itemView).getTextView().getText());
        }
        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {}
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context ctx;
        private final Map<Integer, Integer> labels = labelByOption();
        private final Map<Integer, Integer> icons = iconByOption();

        ListAdapter(Context context) { this.ctx = context; setHasStableIds(true); }
        @Override
        public long getItemId(int position) { return workingOrder.get(position); }

        @Override
        public int getItemCount() { return workingOrder.size(); }

        @Override
        public boolean isEnabled(@NonNull RecyclerView.ViewHolder holder) { return true; }
        @Override
        public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
            ((MenuOrderCell) holder.itemView).setDragging(false, false);
            super.onViewRecycled(holder);
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            MenuOrderCell cell = new MenuOrderCell(ctx);
            cell.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            RecyclerListView.Holder holder = new RecyclerListView.Holder(cell);
            cell.handle.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                        && holder.getAdapterPosition() != RecyclerView.NO_POSITION) {
                    itemTouchHelper.startDrag(holder);
                }
                return false;
            });
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            TextCell cell = (TextCell) holder.itemView;
            int opt = workingOrder.get(position);
            Integer labelRes = labels.get(opt);
            Integer iconRes = icons.get(opt);
            String text = labelRes != null ? LocaleController.getString(labelRes) : ("#" + opt);
            int icon = iconRes != null ? iconRes : R.drawable.msg_reorder;
            cell.setTextAndIcon(text, icon, position < workingOrder.size() - 1);
            int catalogueIndex = 0;
            while (catalogueIndex < CATALOGUE.length - 1 && CATALOGUE[catalogueIndex] != opt) catalogueIndex++;
            IconBackgroundColors color = ROW_COLORS[catalogueIndex % ROW_COLORS.length];
            cell.setColorfulIcon(color.top, color.bottom, icon);
            ViewCompat.setStateDescription(cell, (position + 1) + " / " + workingOrder.size());
        }
    }
    private static class MenuOrderCell extends TextCell {
        final ImageView handle;
        private final Paint dragPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float dragHighlight;
        private boolean dragging;
        private ValueAnimator dragAnimator;
        void setDragging(boolean value, boolean animated) {
            if (dragging == value && animated) return;
            dragging = value;
            if (dragAnimator != null) {
                dragAnimator.cancel();
                dragAnimator = null;
            }
            final float target = value ? 1f : 0f;
            if (!animated || !isAttachedToWindow()) {
                dragHighlight = target;
                invalidate();
                return;
            }
            dragAnimator = ValueAnimator.ofFloat(dragHighlight, target);
            dragAnimator.setDuration(180);
            dragAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            dragAnimator.addUpdateListener(animation -> {
                dragHighlight = (float) animation.getAnimatedValue();
                invalidate();
            });
            dragAnimator.start();
        }
        @Override
        protected void dispatchDraw(Canvas canvas) {
            if (dragHighlight > 0f) {
                dragPaint.setColor(androidx.core.graphics.ColorUtils.blendARGB(
                        Theme.getColor(Theme.key_windowBackgroundWhite),
                        Theme.getColor(Theme.key_windowBackgroundWhiteBlueText), 0.12f));
                dragPaint.setAlpha(Math.round(255 * dragHighlight));
                canvas.drawRoundRect(0, 0, getWidth(), getHeight(),
                        AndroidUtilities.dp(12), AndroidUtilities.dp(12), dragPaint);
            }
            super.dispatchDraw(canvas);
        }
        @Override
        protected void onDetachedFromWindow() {
            setDragging(false, false);
            super.onDetachedFromWindow();
        }
        MenuOrderCell(Context context) {
            super(context);
            handle = new ImageView(context);
            handle.setImageResource(R.drawable.msg_reorder);
            handle.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon));
            handle.setScaleType(ImageView.ScaleType.CENTER);
            handle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(handle);
        }
        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            handle.measure(MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(48), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));
            getTextView().measure(MeasureSpec.makeMeasureSpec(Math.max(0,
                    getMeasuredWidth() - AndroidUtilities.dp(120)), MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(20), MeasureSpec.EXACTLY));
        }
        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            int x = LocaleController.isRTL ? 0 : getMeasuredWidth() - handle.getMeasuredWidth();
            handle.layout(x, 0, x + handle.getMeasuredWidth(), getMeasuredHeight());
        }
    }
}
