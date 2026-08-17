 
package app.nimarkogram.messenger.preferences;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
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
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.nimarkogram.messenger.NimarkoConfig;

public class MessageMenuOrderPreferencesActivity extends BaseFragment {

    private static final int MENU_RESET = 1;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private ItemTouchHelper itemTouchHelper;

    private final ArrayList<Integer> workingOrder = new ArrayList<>();

    private static final int[] CATALOGUE = new int[] {
            ChatActivity.OPTION_REPLY,
            ChatActivity.OPTION_COPY,
            ChatActivity.OPTION_FORWARD,
            ChatActivity.OPTION_EDIT,
            ChatActivity.OPTION_PIN,
            ChatActivity.OPTION_SAVE_TO_GALLERY,
            ChatActivity.OPTION_SAVE_TO_DOWNLOADS_OR_MUSIC,
            ChatActivity.OPTION_SHARE,
            ChatActivity.OPTION_TRANSLATE,
            ChatActivity.OPTION_DELETE,
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
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        adapter = new ListAdapter(context);
        listView.setAdapter(adapter);
        if (listView.getItemAnimator() != null) {
            ((DefaultItemAnimator) listView.getItemAnimator()).setDelayAnimations(false);
        }
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        itemTouchHelper = new ItemTouchHelper(new TouchHelperCallback());
        itemTouchHelper.attachToRecyclerView(listView);

        return fragmentView;
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
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && vh != null) {
                try {
                    vh.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                } catch (Throwable ignored) {}
            }
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {}
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context ctx;
        private final Map<Integer, Integer> labels = labelByOption();
        private final Map<Integer, Integer> icons = iconByOption();

        ListAdapter(Context context) { this.ctx = context; }

        @Override
        public int getItemCount() { return workingOrder.size(); }

        @Override
        public boolean isEnabled(@NonNull RecyclerView.ViewHolder holder) { return true; }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextCell cell = new TextCell(ctx);
            cell.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(cell);
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
        }
    }
}
