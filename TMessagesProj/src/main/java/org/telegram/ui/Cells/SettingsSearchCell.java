package org.telegram.ui.Cells;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ImageSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.ProfileActivity;

public class SettingsSearchCell extends FrameLayout {

    private TextView textView;
    private TextView valueTextView;
    private ImageView imageView;
    private boolean needDivider;
    private int left;
    private boolean standardLayout;
    private int textInset() {
        return standardLayout ? 64 : 71;
    }
    private void setStandardLayout(boolean standard) {
        standardLayout = standard;
        imageView.setScaleType(standard ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER);
        imageView.setLayoutParams(LayoutHelper.createFrame(standard ? 24 : 48, standard ? 24 : 48,
                LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT,
                standard ? 22 : 10, standard ? 20 : 8, standard ? 22 : 10, 0));
        valueTextView.setEllipsize(standard ? TextUtils.TruncateAt.END : null);
    }
    public static int standardIcon(ProfileActivity.SearchAdapter.SearchResult result) {
        switch (result.guid) {
            case 500: return R.drawable.msg_edit;
            case 501: case 5: case 105: case 122: case 218: return R.drawable.msg_calls;
            case 502: return R.drawable.msg_add;
            case 3: return R.drawable.msg_groups;
            case 4: return R.drawable.msg_channel;
            case 8: return R.drawable.msg_contacts;
            case 101: return R.drawable.msg2_block2;
            case 102: return R.drawable.msg_recent;
            case 103: case 222: case 223: case 224: case 225: return R.drawable.msg_gallery;
            case 110: return R.drawable.settings_devices;
            case 124: return R.drawable.msg2_autodelete;
            case 125: return R.drawable.msg2_email;
            case 220: return R.drawable.pill_proxy;
            case 302: case 303: case 304: return R.drawable.msg_background;
            case 604: return R.drawable.msg2_archived_stickers;
            case 608: case 609: case 610: case 611: return R.drawable.input_smile;
            default: return result.iconResId != 0 ? result.iconResId : R.drawable.msg_settings;
        }
    }

    public static class VerticalImageSpan extends ImageSpan {

        public VerticalImageSpan(Drawable drawable) {
            super(drawable);
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fontMetricsInt) {
            Drawable drawable = getDrawable();
            Rect rect = drawable.getBounds();
            if (fontMetricsInt != null) {
                Paint.FontMetricsInt fmPaint = paint.getFontMetricsInt();
                int fontHeight = fmPaint.descent - fmPaint.ascent;
                int drHeight = rect.bottom - rect.top;
                int centerY = fmPaint.ascent + fontHeight / 2;

                fontMetricsInt.ascent = centerY - drHeight / 2;
                fontMetricsInt.top = fontMetricsInt.ascent;
                fontMetricsInt.bottom = centerY + drHeight / 2;
                fontMetricsInt.descent = fontMetricsInt.bottom;
            }
            return rect.right;
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
            Drawable drawable = getDrawable();
            canvas.save();
            Paint.FontMetricsInt fmPaint = paint.getFontMetricsInt();
            int fontHeight = fmPaint.descent - fmPaint.ascent;
            int centerY = y + fmPaint.descent - fontHeight / 2;
            int transY = centerY - (drawable.getBounds().bottom - drawable.getBounds().top) / 2;
            canvas.translate(x, transY);
            if (LocaleController.isRTL) {
                canvas.scale(-1, 1, drawable.getIntrinsicWidth() / 2, drawable.getIntrinsicHeight() / 2);
            }
            drawable.draw(canvas);
            canvas.restore();
        }
    }

    public SettingsSearchCell(Context context) {
        super(context);

        textView = new TextView(context);
        textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        textView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        textView.setLines(1);
        textView.setMaxLines(1);
        textView.setSingleLine(true);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        addView(textView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT, LocaleController.isRTL ? 16 : 71, 10, LocaleController.isRTL ? 71 : 16, 0));

        valueTextView = new TextView(context);
        valueTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        valueTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        valueTextView.setLines(1);
        valueTextView.setMaxLines(1);
        valueTextView.setSingleLine(true);
        valueTextView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        addView(valueTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT, LocaleController.isRTL ? 16 : 71, 33, LocaleController.isRTL ? 71 : 16, 0));

        imageView = new ImageView(context);
        imageView.setScaleType(ImageView.ScaleType.CENTER);
        imageView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
        addView(imageView, LayoutHelper.createFrame(48, 48, LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT, 10, 8, 10, 0));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(64) + (needDivider ? 1 : 0), MeasureSpec.EXACTLY));
    }

    public void setTextAndValueAndIcon(CharSequence text, String[] value, int icon, boolean divider) {
        textView.setText(text);
        LayoutParams layoutParams = (LayoutParams) textView.getLayoutParams();
        layoutParams.leftMargin = AndroidUtilities.dp(LocaleController.isRTL ? 16 : textInset());
        layoutParams.rightMargin = AndroidUtilities.dp(LocaleController.isRTL ? textInset() : 16);
        if (value != null && value.length > 0) {
            SpannableStringBuilder builder = new SpannableStringBuilder();
            for (int a = 0; a < value.length; a++) {
                if (a != 0) {
                    builder.append(" > ");
                    Drawable drawable = getContext().getResources().getDrawable(R.drawable.settings_arrow).mutate();
                    drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
                    drawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2), PorterDuff.Mode.MULTIPLY));
                    builder.setSpan(new VerticalImageSpan(drawable), builder.length() - 2, builder.length() - 1, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                builder.append(value[a]);
            }
            valueTextView.setText(builder);
            valueTextView.setVisibility(VISIBLE);
            layoutParams.topMargin = AndroidUtilities.dp(10);

            layoutParams = (LayoutParams) valueTextView.getLayoutParams();
            layoutParams.leftMargin = AndroidUtilities.dp(LocaleController.isRTL ? 16 : textInset());
            layoutParams.rightMargin = AndroidUtilities.dp(LocaleController.isRTL ? textInset() : 16);
        } else {
            layoutParams.topMargin = AndroidUtilities.dp(21);
            valueTextView.setVisibility(GONE);
        }
        if (icon != 0) {
            imageView.setImageResource(icon);
            imageView.setVisibility(VISIBLE);
        } else {
            imageView.setVisibility(GONE);
        }
        left = standardLayout ? textInset() : 69;
        needDivider = divider;
        setWillNotDraw(!needDivider);
        requestLayout();
    }

    public void setTextAndValue(CharSequence text, String[] value, boolean faq, boolean divider) {
        LayoutParams layoutParams = (LayoutParams) textView.getLayoutParams();
        if (faq) {
            valueTextView.setText(text);
            SpannableStringBuilder builder = new SpannableStringBuilder();
            for (int a = 0; a < value.length; a++) {
                if (a != 0) {
                    builder.append(" > ");
                    Drawable drawable = getContext().getResources().getDrawable(R.drawable.settings_arrow).mutate();
                    drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
                    drawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.MULTIPLY));
                    builder.setSpan(new VerticalImageSpan(drawable), builder.length() - 2, builder.length() - 1, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                builder.append(value[a]);
            }
            textView.setText(builder);
            valueTextView.setVisibility(VISIBLE);
            layoutParams.topMargin = AndroidUtilities.dp(10);
        } else {
            textView.setText(text);
            if (value != null) {
                SpannableStringBuilder builder = new SpannableStringBuilder();
                for (int a = 0; a < value.length; a++) {
                    if (a != 0) {
                        builder.append(" > ");
                        Drawable drawable = getContext().getResources().getDrawable(R.drawable.settings_arrow).mutate();
                        drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
                        drawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2), PorterDuff.Mode.MULTIPLY));
                        builder.setSpan(new VerticalImageSpan(drawable), builder.length() - 2, builder.length() - 1, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    builder.append(value[a]);
                }
                valueTextView.setText(builder);
                valueTextView.setVisibility(VISIBLE);
                layoutParams.topMargin = AndroidUtilities.dp(10);
            } else {
                layoutParams.topMargin = AndroidUtilities.dp(21);
                valueTextView.setVisibility(GONE);
            }
        }

        layoutParams.leftMargin = layoutParams.rightMargin = AndroidUtilities.dp(16);

        layoutParams = (LayoutParams) valueTextView.getLayoutParams();
        layoutParams.leftMargin = layoutParams.rightMargin = AndroidUtilities.dp(16);

        imageView.setVisibility(GONE);
        needDivider = divider;
        setWillNotDraw(!needDivider);
        left = 16;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (needDivider) {
            canvas.drawLine(LocaleController.isRTL ? 0 : AndroidUtilities.dp(left), getMeasuredHeight() - 1, getMeasuredWidth() - (LocaleController.isRTL ? AndroidUtilities.dp(left) : 0), getMeasuredHeight() - 1, Theme.dividerPaint);
        }
    }

    public static class Factory extends UItem.UItemFactory<SettingsSearchCell> {
        static { setup(new Factory()); }

        @Override
        public SettingsSearchCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new SettingsSearchCell(context);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            SettingsSearchCell cell = (SettingsSearchCell) view;
            boolean standard = item.intValue == 1;
            cell.setStandardLayout(standard);
            if (item.object instanceof ProfileActivity.SearchAdapter.SearchResult) {
                final ProfileActivity.SearchAdapter.SearchResult r = (ProfileActivity.SearchAdapter.SearchResult) item.object;
                cell.setTextAndValueAndIcon(item.text, r.path, standard ? standardIcon(r) : r.iconResId, divider);
            } else if (item.object instanceof MessagesController.FaqSearchResult) {
                final MessagesController.FaqSearchResult r = (MessagesController.FaqSearchResult) item.object;
                if (standard) {
                    cell.setTextAndValueAndIcon(item.text, r.path, R.drawable.msg2_help, divider);
                } else {
                    cell.setTextAndValue(item.text, r.path, true, divider);
                }
            }
        }
        public static UItem ofStandard(CharSequence text, Object result) {
            UItem item = of(text, result);
            item.intValue = 1;
            return item;
        }

        public static UItem of(CharSequence text, Object obj) {
            UItem item = UItem.ofFactory(Factory.class);
            item.text = text;
            item.object = obj;
            return item;
        }

        public static UItem of(CharSequence text, ProfileActivity.SearchAdapter.SearchResult r) {
            UItem item = UItem.ofFactory(Factory.class);
            item.text = text;
            item.object = r;
            return item;
        }

        public static UItem of(CharSequence text, MessagesController.FaqSearchResult faq) {
            UItem item = UItem.ofFactory(Factory.class);
            item.text = text;
            item.object = faq;
            return item;
        }
    }

}
