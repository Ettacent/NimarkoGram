/* Modifications Copyright (C) 2026 Ettacent */
package app.nimarkogram.messenger.documents;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadioButton;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SearchField;
import org.telegram.ui.Components.SeekBarView;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.ArrayList;

final class DocumentNavigationSheet {
    interface Listener { void onSelected(DocumentNavigationSheet sheet, int index); }
    private final Theme.ResourcesProvider colors;
    private final Listener listener;
    private final int count;
    private final DocumentSheetIndex sheets;
    private final LinearLayout body;
    private BottomSheet dialog;
    private EditTextBoldCursor editor;
    private GradientDrawable pageBackground;
    private TextView counter, empty;
    private ImageView previous, next;
    private SeekBarView seek;
    private ButtonWithCounterView confirm;
    private SearchField search;
    private RecyclerListView list;
    private RecyclerListView.SelectionAdapter adapter;
    private final ArrayList<ThemeDescription> searchColors = new ArrayList<>();
    private int selected;
    private boolean changing;

    DocumentNavigationSheet(Context context, Theme.ResourcesProvider colors, int count, int current, String[] names, Listener listener) {
        this.colors = colors;
        this.listener = listener;
        this.count = Math.max(1, count);
        selected = Math.max(0, Math.min(this.count - 1, current));
        sheets = names == null ? null : new DocumentSheetIndex(names);
        body = new LinearLayout(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                if (sheets == null && editor != null) {
                    editor.getLayoutParams().height = Math.max(AndroidUtilities.dp(56),
                            (int) Math.ceil(editor.getPaint().getFontSpacing()) + AndroidUtilities.dp(16));
                }
                if (list != null) {
                    int available = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED
                            ? AndroidUtilities.displaySize.y * 2 / 3 : MeasureSpec.getSize(heightSpec);
                    int rows = Math.max(AndroidUtilities.dp(144), Math.min(AndroidUtilities.dp(380), sheets.size() * AndroidUtilities.dp(56)));
                    ((FrameLayout.LayoutParams) list.getLayoutParams()).height = Math.max(1,
                            Math.min(rows, available - AndroidUtilities.dp(76)));
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, 0, AndroidUtilities.dp(16));
        body.setFocusableInTouchMode(true);
        if (sheets == null) createPages(context); else createSheets(context);
        body.requestFocus();
        String title = LocaleController.getString(sheets == null ? R.string.NM_DocGoToPage : R.string.NM_DocChooseSheet);
        if (sheets != null) title += " · " + count;
        View custom = body;
        if (sheets == null) {
            ScrollView scroll = new ScrollView(context);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
            custom = scroll;
        }
        dialog = new BottomSheet.Builder(context, true, colors).setTitle(title, true).setNativeCustomView(custom).create();
        dialog.smoothKeyboardAnimationEnabled = true;
        dialog.setOnShowListener(ignored -> { updateColors(); body.requestFocus(); });
        updateColors();
    }

    BottomSheet getDialog() { return dialog; }
    EditTextBoldCursor getEditor() { return editor; }

    void setProtected(boolean value) {
        if (value && dialog != null && dialog.getWindow() != null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    private void createPages(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        previous = arrow(context, false);
        next = arrow(context, true);
        row.addView(previous, new LinearLayout.LayoutParams(AndroidUtilities.dp(52), AndroidUtilities.dp(52)));
        editor = new EditTextBoldCursor(context);
        editor.setInputType(InputType.TYPE_CLASS_NUMBER);
        editor.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        editor.setSingleLine(true);
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        editor.setGravity(Gravity.CENTER);
        pageBackground = new GradientDrawable();
        pageBackground.setCornerRadius(AndroidUtilities.dp(12));
        editor.setBackground(pageBackground);
        editor.setSelectAllOnFocus(true);
        editor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(Integer.toString(count).length())});
        editor.setContentDescription(LocaleController.getString(R.string.NM_DocGoToPage));
        row.addView(editor, new LinearLayout.LayoutParams(0, AndroidUtilities.dp(56), 1f));
        row.addView(next, new LinearLayout.LayoutParams(AndroidUtilities.dp(52), AndroidUtilities.dp(52)));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.setMargins(AndroidUtilities.dp(20), 0, AndroidUtilities.dp(20), 0);
        body.addView(row, rowParams);
        counter = new TextView(context);
        counter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        counter.setGravity(Gravity.CENTER);
        counter.setMinHeight(AndroidUtilities.dp(32));
        counter.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(counter, new LinearLayout.LayoutParams(-1, -2));
        seek = new SeekBarView(context, false, colors);
        seek.setReportChanges(true);
        seek.setDelegate(new SeekBarView.SeekBarViewDelegate() {
            @Override public void onSeekBarDrag(boolean stop, float progress) { select(Math.round(progress * (count - 1))); }
            @Override public int getStepsCount() { return Math.max(1, count - 1); }
            @Override public CharSequence getContentDescription() { return counter.getText(); }
        });
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(52));
        seekParams.setMargins(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), AndroidUtilities.dp(12));
        body.addView(seek, seekParams);
        seek.setEnabled(count > 1);
        confirm = new ButtonWithCounterView(context, colors);
        confirm.setText(LocaleController.getString(R.string.NM_DocGoToPage), false);
        confirm.setOnClickListener(v -> commit());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(48));
        buttonParams.setMargins(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0);
        body.addView(confirm, buttonParams);
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int n, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int n) {}
            @Override public void afterTextChanged(Editable value) {
                if (changing) return;
                try {
                    int page = Integer.parseInt(value.toString());
                    if (page < 1 || page > count) throw new NumberFormatException();
                    selected = page - 1;
                    syncPageControls();
                    confirm.setEnabled(true);
                } catch (NumberFormatException invalid) {
                    confirm.setEnabled(false);
                    counter.setText("1 – " + count);
                }
            }
        });
        editor.setOnEditorActionListener((v, id, event) -> {
            if (id != EditorInfo.IME_ACTION_DONE) return false;
            commit(); return true;
        });
        select(selected);
    }

    private ImageView arrow(Context context, boolean forward) {
        ImageView view = new ImageView(context);
        view.setImageResource(R.drawable.msg_arrowright);
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(14), AndroidUtilities.dp(14), AndroidUtilities.dp(14));
        if (!forward) view.setScaleX(-1f);
        view.setContentDescription(LocaleController.getString(forward ? R.string.NM_DocNext : R.string.NM_DocPrevious));
        view.setFocusable(true);
        view.setOnClickListener(v -> select(selected + (forward ? 1 : -1)));
        return view;
    }

    private void select(int value) {
        selected = Math.max(0, Math.min(count - 1, value));
        changing = true;
        editor.setText(Integer.toString(selected + 1));
        editor.setSelection(editor.length());
        changing = false;
        syncPageControls();
        confirm.setEnabled(true);
    }

    private void syncPageControls() {
        seek.setProgress(count < 2 ? 0 : selected / (float) (count - 1));
        counter.setText(LocaleController.formatString(R.string.NM_DocPageOf, selected + 1, count));
        previous.setEnabled(selected > 0); previous.setAlpha(selected > 0 ? 1 : .35f);
        next.setEnabled(selected + 1 < count); next.setAlpha(selected + 1 < count ? 1 : .35f);
    }

    private void commit() {
        if (confirm != null && confirm.isEnabled() && dialog != null && dialog.isShowing()) listener.onSelected(this, selected);
    }

    private void createSheets(Context context) {
        search = new SearchField(context, true, colors) {
            @Override public void onTextChange(String text) {
                sheets.filter(text);
                if (adapter != null) adapter.notifyDataSetChanged();
                if (empty != null) empty.setVisibility(sheets.size() == 0 ? View.VISIBLE : View.GONE);
            }
        };
        search.setHint(LocaleController.getString(R.string.Search));
        search.getThemeDescriptions(searchColors);
        editor = search.getSearchEditText();
        editor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(256)});
        body.addView(search, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(58)));
        FrameLayout frame = new FrameLayout(context);
        list = new RecyclerListView(context, colors);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setItemAnimator(null);
        list.setVerticalScrollBarEnabled(false);
        list.setOnItemClickListener((v, position) -> {
            if (position < 0 || position >= sheets.size()) return;
            int index = sheets.indexAt(position);
            if (v.getTag() instanceof Integer && (Integer) v.getTag() == index) listener.onSelected(this, index);
        });
        adapter = new RecyclerListView.SelectionAdapter() {
            @Override public boolean isEnabled(RecyclerView.ViewHolder holder) { return true; }
            @Override public int getItemCount() { return sheets.size(); }
            @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                return new RecyclerListView.Holder(new SheetRow(parent.getContext()));
            }
            @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                SheetRow row = (SheetRow) holder.itemView;
                int index = sheets.indexAt(position);
                row.setTag(index);
                row.text.setText((index + 1) + ". " + sheets.nameAt(position));
                row.radio.setChecked(index == selected, false);
                row.selected = index == selected;
                row.updateColors();
            }
        };
        list.setAdapter(adapter);
        frame.addView(list, LayoutHelper.createFrame(-1, 380));
        empty = new TextView(context);
        empty.setText(LocaleController.getString(R.string.NoResult));
        empty.setGravity(Gravity.CENTER);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        empty.setVisibility(View.GONE);
        frame.addView(empty, LayoutHelper.createFrame(-1, -1));
        body.addView(frame, new LinearLayout.LayoutParams(-1, -2));
        ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(selected, 0);
    }

    void updateColors() {
        int background = color(Theme.key_dialogBackground);
        int foreground = color(Theme.key_dialogTextBlack);
        int surface = ColorUtils.setAlphaComponent(background, 255);
        if (dialog != null) { dialog.setBackgroundColor(background); dialog.setTitleColor(foreground); }
        if (editor != null) {
            if (sheets == null) {
                int fill = color(Theme.key_dialogSearchBackground);
                int visibleFill = ColorUtils.compositeColors(fill, surface);
                int onSurface = ColorUtils.setAlphaComponent(foreground, 255);
                double minimumSeparation = ColorUtils.calculateLuminance(visibleFill)
                        < ColorUtils.calculateLuminance(surface) ? 1.3 : 1.15;
                if ((ColorUtils.calculateLuminance(onSurface) > ColorUtils.calculateLuminance(surface)
                        && ColorUtils.calculateContrast(visibleFill, surface) < minimumSeparation)
                        || ColorUtils.calculateContrast(foreground, visibleFill) < 4.5) {
                    fill = visibleFill = ColorUtils.blendARGB(surface, onSurface, .08f);
                    if (ColorUtils.calculateContrast(foreground, visibleFill) < 4.5) fill = visibleFill = surface;
                }
                pageBackground.setColor(fill);
                pageBackground.setStroke(AndroidUtilities.dp(1), contrastColor(
                        color(Theme.key_dialogInputField), visibleFill, surface, 3));
            }
            editor.setTextColor(foreground);
            editor.setHintTextColor(color(Theme.key_dialogTextHint));
            editor.setCursorColor(foreground);
            editor.setHandlesColor(color(Theme.key_chat_TextSelectionCursor));
            editor.setHighlightColor(color(Theme.key_chat_inTextSelectionHighlight));
        }
        if (counter != null) counter.setTextColor(contrastColor(color(Theme.key_dialogTextGray2), surface, surface, 4.5));
        if (empty != null) empty.setTextColor(color(Theme.key_dialogTextGray2));
        if (seek != null) seek.setColors(color(Theme.key_switchTrack), color(Theme.key_dialogTextBlue));
        if (confirm != null) confirm.updateColors();
        for (ImageView view : new ImageView[]{previous, next}) if (view != null) {
            view.setColorFilter(new PorterDuffColorFilter(foreground, PorterDuff.Mode.SRC_IN));
            if (view.getBackground() == null) view.setBackground(Theme.createSelectorDrawable(color(Theme.key_listSelector)));
            else Theme.setSelectorDrawableColor(view.getBackground(), color(Theme.key_listSelector), true);
        }
        for (ThemeDescription description : searchColors) description.setColor(color(description.getCurrentKey()), false, false);
        if (list != null) for (int i = 0; i < list.getChildCount(); i++) if (list.getChildAt(i) instanceof SheetRow) ((SheetRow) list.getChildAt(i)).updateColors();
    }

    private int color(int key) { return Theme.getColor(key, colors); }

    private int contrastColor(int candidate, int background, int surrounding, double minimum) {
        int foreground = ColorUtils.setAlphaComponent(color(Theme.key_dialogTextBlack), 255);
        for (int step = 0; step <= 20; step++) {
            int blended = ColorUtils.blendARGB(candidate, foreground, step / 20f);
            if (ColorUtils.calculateContrast(blended, background) >= minimum
                    && ColorUtils.calculateContrast(blended, surrounding) >= minimum) return blended;
        }
        return foreground;
    }

    private final class SheetRow extends FrameLayout {
        final TextView text;
        final RadioButton radio;
        boolean selected;
        SheetRow(Context context) {
            super(context);
            text = new TextView(context);
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            text.setSingleLine(true);
            text.setEllipsize(TextUtils.TruncateAt.END);
            text.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            addView(text, LayoutHelper.createFrame(-1, -1, Gravity.FILL, LocaleController.isRTL ? 54 : 20, 0, LocaleController.isRTL ? 20 : 54, 0));
            radio = new RadioButton(context);
            radio.setSize(AndroidUtilities.dp(20));
            radio.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(radio, LayoutHelper.createFrame(22, 22, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL,
                    LocaleController.isRTL ? 20 : 0, 0, LocaleController.isRTL ? 0 : 20, 0));
            setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(Math.max(AndroidUtilities.dp(56),
                    (int) Math.ceil(text.getPaint().getFontSpacing()) + AndroidUtilities.dp(24)), MeasureSpec.EXACTLY));
        }
        void updateColors() {
            text.setTextColor(color(Theme.key_dialogTextBlack));
            radio.setColor(color(Theme.key_dialogRadioBackground), color(Theme.key_dialogRadioBackgroundChecked));
        }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setCheckable(true); info.setChecked(selected); info.setText(text.getText());
        }
    }
}
