/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.ActionBar;

import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.RadioButton;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.RecyclerListView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;

public final class ThemeBind {

    private ThemeBind() {}

    private static final int TEXT = 0;
    private static final int HINT = 1;
    private static final int LINK = 2;
    private static final int HIGHLIGHT = 3;
    private static final int BACKGROUND = 4;
    private static final int COLOR_FILTER = 5;
    private static final int SLOTS = 6;

    private static final int OP_NONE = 0;
    private static final int OP_ALPHA = 1;
    private static final int OP_MULT_ALPHA = 2;
    private static final int OP_BLEND = 3;
    private static final int OP_BLEND_OVER = 4;
    private static final int OP_ADAPTIVE_RIPPLE = 5;

    public static final class Spec {
        final int key, key2, op;
        final float value;

        private Spec(int key, int key2, int op, float value) {
            this.key = key;
            this.key2 = key2;
            this.op = op;
            this.value = value;
        }
    }

    public static Spec alpha(int key, int alpha) {
        return new Spec(key, -1, OP_ALPHA, alpha);
    }

    public static Spec multAlpha(int key, float multiplier) {
        return new Spec(key, -1, OP_MULT_ALPHA, multiplier);
    }

    public static Spec adaptiveRipple(int key) {
        return new Spec(key, -1, OP_ADAPTIVE_RIPPLE, 0);
    }

    public static Spec of(int key) {
        return new Spec(key, -1, OP_NONE, 0);
    }

    public static Spec blendOver(int key1, int key2) {
        return new Spec(key1, key2, OP_BLEND_OVER, 0);
    }

    public static Spec blend(int key1, int key2, float ratio) {
        return new Spec(key1, key2, OP_BLEND, ratio);
    }

    private static final class Binding {
        int key, key2 = -1, op;
        float value;
        WeakReference<Theme.ResourcesProvider> providerRef;
        Theme.ResourcesProvider providerStrong;
        int applied;
        PorterDuff.Mode mode;
        Object appliedObject;
        WeakReference<Drawable> drawable;
        boolean untracked;
        int slot;
        ColorGetter getter;
        ColorSetter setter;
        boolean builtin;
        ViewColorGetter<View> viewGetter;
        ViewColorSetter<View> viewSetter;

        void set(int key, int key2, int op, float value, Theme.ResourcesProvider resourcesProvider) {
            this.key = key;
            this.key2 = key2;
            this.op = op;
            this.value = value;
            if (resourcesProvider == null) {
                providerRef = null;
                providerStrong = null;
            } else if (isLambda(resourcesProvider)) {
                providerRef = null;
                providerStrong = resourcesProvider;
            } else {
                providerRef = new WeakReference<>(resourcesProvider);
                providerStrong = null;
            }
        }

        boolean providerGone() {
            return providerRef != null && providerRef.get() == null;
        }

        int resolve() {
            return ThemeBind.resolve(key, key2, op, value, providerStrong != null ? providerStrong : providerRef != null ? providerRef.get() : null);
        }
    }

    private static boolean isLambda(Object o) {
        final Class<?> c = o.getClass();
        return c.isSynthetic() || c.getName().contains("$$Lambda") || c.getName().contains("$$ExternalSynthetic");
    }

    private static int resolve(int key, int key2, int op, float value, Theme.ResourcesProvider resourcesProvider) {
        final int color = Theme.getColor(key, resourcesProvider);
        switch (op) {
            case OP_ALPHA: return ColorUtils.setAlphaComponent(color, (int) value);
            case OP_MULT_ALPHA: return Theme.multAlpha(color, value);
            case OP_BLEND: return ColorUtils.blendARGB(color, Theme.getColor(key2, resourcesProvider), value);
            case OP_BLEND_OVER: return Theme.blendOver(color, Theme.getColor(key2, resourcesProvider));
            case OP_ADAPTIVE_RIPPLE: return Theme.AdaptiveRipple.calcRippleColor(color);
        }
        return color;
    }

    private static final class Bindings {
        final Binding[] slots = new Binding[SLOTS];
        ArrayList<Binding> drawables;
        ArrayList<Binding> custom;
        Drawable background;
        Binding backgroundContent, backgroundRipple;
    }

    public interface ColorGetter {
        int get();
    }

    public interface ColorSetter {
        void set(int color);
    }

    public interface ViewColorGetter<V extends View> {
        int get(V view);
    }

    public interface ViewColorSetter<V extends View> {
        void set(V view, int color);
    }

    public static void textColor(TextView view, int key) {
        textColor(view, key, null);
    }

    public static void textColor(TextView view, int key, Theme.ResourcesProvider resourcesProvider) {
        bindText(view, key, -1, OP_NONE, 0, resourcesProvider);
    }

    public static void textColor(TextView view, Spec spec) {
        textColor(view, spec, null);
    }

    public static void textColor(TextView view, Spec spec, Theme.ResourcesProvider resourcesProvider) {
        bindText(view, spec.key, spec.key2, spec.op, spec.value, resourcesProvider);
    }

    public static void textColor(SimpleTextView view, int key) {
        textColor(view, key, null);
    }

    public static void textColor(SimpleTextView view, int key, Theme.ResourcesProvider resourcesProvider) {
        bindText(view, key, -1, OP_NONE, 0, resourcesProvider);
    }

    public static void textColor(SimpleTextView view, Spec spec) {
        textColor(view, spec, null);
    }

    public static void textColor(SimpleTextView view, Spec spec, Theme.ResourcesProvider resourcesProvider) {
        bindText(view, spec.key, spec.key2, spec.op, spec.value, resourcesProvider);
    }

    public static void textColor(AnimatedTextView view, int key) {
        textColor(view, key, null);
    }

    public static void textColor(AnimatedTextView view, int key, Theme.ResourcesProvider resourcesProvider) {
        bindText(view, key, -1, OP_NONE, 0, resourcesProvider);
    }

    public static void textColor(AnimatedTextView view, Spec spec) {
        textColor(view, spec, null);
    }

    public static void textColor(AnimatedTextView view, Spec spec, Theme.ResourcesProvider resourcesProvider) {
        bindText(view, spec.key, spec.key2, spec.op, spec.value, resourcesProvider);
    }

    private static void bindText(View view, int key, int key2, int op, float value, Theme.ResourcesProvider resourcesProvider) {
        final int color = resolve(key, key2, op, value, resourcesProvider);
        if (view instanceof TextView) {
            ((TextView) view).setTextColor(color);
        } else if (view instanceof SimpleTextView) {
            ((SimpleTextView) view).setTextColor(color);
        } else {
            ((AnimatedTextView) view).setTextColor(color);
        }
        put(view, TEXT, key, key2, op, value, resourcesProvider, color, null);
    }

    public static void hintTextColor(TextView view, int key) {
        hintTextColor(view, key, null);
    }

    public static void hintTextColor(TextView view, int key, Theme.ResourcesProvider resourcesProvider) {
        bindTextView(view, HINT, key, -1, OP_NONE, 0, resourcesProvider);
    }

    public static void hintTextColor(TextView view, Spec spec) {
        hintTextColor(view, spec, null);
    }

    public static void hintTextColor(TextView view, Spec spec, Theme.ResourcesProvider resourcesProvider) {
        bindTextView(view, HINT, spec.key, spec.key2, spec.op, spec.value, resourcesProvider);
    }

    public static void linkTextColor(TextView view, int key) {
        linkTextColor(view, key, null);
    }

    public static void linkTextColor(TextView view, int key, Theme.ResourcesProvider resourcesProvider) {
        bindTextView(view, LINK, key, -1, OP_NONE, 0, resourcesProvider);
    }

    public static void linkTextColor(TextView view, Spec spec) {
        linkTextColor(view, spec, null);
    }

    public static void linkTextColor(TextView view, Spec spec, Theme.ResourcesProvider resourcesProvider) {
        bindTextView(view, LINK, spec.key, spec.key2, spec.op, spec.value, resourcesProvider);
    }

    public static void highlightColor(TextView view, int key) {
        highlightColor(view, key, null);
    }

    public static void highlightColor(TextView view, int key, Theme.ResourcesProvider resourcesProvider) {
        bindTextView(view, HIGHLIGHT, key, -1, OP_NONE, 0, resourcesProvider);
    }

    public static void highlightColor(TextView view, Spec spec) {
        highlightColor(view, spec, null);
    }

    public static void highlightColor(TextView view, Spec spec, Theme.ResourcesProvider resourcesProvider) {
        bindTextView(view, HIGHLIGHT, spec.key, spec.key2, spec.op, spec.value, resourcesProvider);
    }

    private static void bindTextView(TextView view, int slot, int key, int key2, int op, float value, Theme.ResourcesProvider resourcesProvider) {
        final int color = resolve(key, key2, op, value, resourcesProvider);
        switch (slot) {
            case HINT: view.setHintTextColor(color); break;
            case LINK: view.setLinkTextColor(color); break;
            case HIGHLIGHT: view.setHighlightColor(color); break;
        }
        put(view, slot, key, key2, op, value, resourcesProvider, color, null);
    }

    public static void backgroundColor(View view, int key) {
        backgroundColor(view, key, null);
    }

    public static void backgroundColor(View view, int key, Theme.ResourcesProvider resourcesProvider) {
        bindBackground(view, key, -1, OP_NONE, 0, resourcesProvider);
    }

    public static void backgroundColor(View view, Spec spec) {
        backgroundColor(view, spec, null);
    }

    public static void backgroundColor(View view, Spec spec, Theme.ResourcesProvider resourcesProvider) {
        bindBackground(view, spec.key, spec.key2, spec.op, spec.value, resourcesProvider);
    }

    private static void bindBackground(View view, int key, int key2, int op, float value, Theme.ResourcesProvider resourcesProvider) {
        final int color = resolve(key, key2, op, value, resourcesProvider);
        view.setBackgroundColor(color);
        put(view, BACKGROUND, key, key2, op, value, resourcesProvider, color, null);
    }

    public static void colorFilter(ImageView view, int key, PorterDuff.Mode mode) {
        colorFilter(view, key, null, mode);
    }

    public static void colorFilter(ImageView view, int key, Theme.ResourcesProvider resourcesProvider, PorterDuff.Mode mode) {
        bindColorFilter(view, key, -1, OP_NONE, 0, resourcesProvider, mode);
    }

    public static void colorFilter(ImageView view, Spec spec, PorterDuff.Mode mode) {
        colorFilter(view, spec, null, mode);
    }

    public static void colorFilter(ImageView view, Spec spec, Theme.ResourcesProvider resourcesProvider, PorterDuff.Mode mode) {
        bindColorFilter(view, spec.key, spec.key2, spec.op, spec.value, resourcesProvider, mode);
    }

    private static void bindColorFilter(ImageView view, int key, int key2, int op, float value, Theme.ResourcesProvider resourcesProvider, PorterDuff.Mode mode) {
        final int color = resolve(key, key2, op, value, resourcesProvider);
        final PorterDuffColorFilter filter = new PorterDuffColorFilter(color, mode);
        view.setColorFilter(filter);
        final Binding b = put(view, COLOR_FILTER, key, key2, op, value, resourcesProvider, color, filter);
        b.mode = mode;
    }

    public static void drawableColorFilter(View owner, Drawable drawable, int key, PorterDuff.Mode mode) {
        drawableColorFilter(owner, drawable, key, null, mode);
    }

    public static void drawableColorFilter(View owner, Drawable drawable, int key, Theme.ResourcesProvider resourcesProvider, PorterDuff.Mode mode) {
        bindDrawable(owner, drawable, key, -1, OP_NONE, 0, resourcesProvider, mode);
    }

    public static void drawableColorFilter(View owner, Drawable drawable, Spec spec, PorterDuff.Mode mode) {
        drawableColorFilter(owner, drawable, spec, null, mode);
    }

    public static void drawableColorFilter(View owner, Drawable drawable, Spec spec, Theme.ResourcesProvider resourcesProvider, PorterDuff.Mode mode) {
        bindDrawable(owner, drawable, spec.key, spec.key2, spec.op, spec.value, resourcesProvider, mode);
    }

    private static void bindDrawable(View owner, Drawable drawable, int key, int key2, int op, float value, Theme.ResourcesProvider resourcesProvider, PorterDuff.Mode mode) {
        final int color = resolve(key, key2, op, value, resourcesProvider);
        final PorterDuffColorFilter filter = new PorterDuffColorFilter(color, mode);
        drawable.setColorFilter(filter);
        if (owner == null) {
            return;
        }
        final Bindings bindings = bindings(owner, true);
        if (bindings.drawables == null) {
            bindings.drawables = new ArrayList<>(2);
        }
        Binding b = null;
        for (int i = bindings.drawables.size() - 1; i >= 0; --i) {
            final Binding o = bindings.drawables.get(i);
            final Drawable d = o.drawable.get();
            if (d == drawable) {
                b = o;
            } else if (d == null || !o.untracked && d.getColorFilter() != o.appliedObject) {
                bindings.drawables.remove(i);
            }
        }
        if (b == null) {
            b = new Binding();
            b.drawable = new WeakReference<>(drawable);
            bindings.drawables.add(b);
        }
        b.set(key, key2, op, value, resourcesProvider);
        b.applied = color;
        b.appliedObject = filter;
        b.mode = mode;
        b.untracked = drawable.getColorFilter() != filter;
    }

    public static void custom(View owner, int slot, int key, Theme.ResourcesProvider resourcesProvider, ColorGetter getter, ColorSetter setter) {
        bindCustom(owner, slot, key, -1, OP_NONE, 0, resourcesProvider, getter, setter);
    }

    public static void custom(View owner, int slot, Spec spec, Theme.ResourcesProvider resourcesProvider, ColorGetter getter, ColorSetter setter) {
        bindCustom(owner, slot, spec.key, spec.key2, spec.op, spec.value, resourcesProvider, getter, setter);
    }

    public static <V extends View> void custom(V owner, int slot, int key, Theme.ResourcesProvider resourcesProvider, ViewColorGetter<V> getter, ViewColorSetter<V> setter) {
        bindViewCustom(owner, slot, key, -1, OP_NONE, 0, resourcesProvider, getter, setter);
    }

    public static <V extends View> void custom(V owner, int slot, Spec spec, Theme.ResourcesProvider resourcesProvider, ViewColorGetter<V> getter, ViewColorSetter<V> setter) {
        bindViewCustom(owner, slot, spec.key, spec.key2, spec.op, spec.value, resourcesProvider, getter, setter);
    }

    @SuppressWarnings("unchecked")
    private static <V extends View> void bindViewCustom(V owner, int slot, int key, int key2, int op, float value, Theme.ResourcesProvider resourcesProvider, ViewColorGetter<V> getter, ViewColorSetter<V> setter) {
        final int color = resolve(key, key2, op, value, resourcesProvider);
        setter.set(owner, color);
        final Binding b = customBinding(owner, slot);
        b.set(key, key2, op, value, resourcesProvider);
        b.getter = null;
        b.setter = null;
        b.viewGetter = (ViewColorGetter<View>) getter;
        b.viewSetter = (ViewColorSetter<View>) setter;
        b.builtin = false;
        b.applied = color;
    }

    private static void bindSlot(View owner, int slot, int key, int key2, int op, float value, Theme.ResourcesProvider resourcesProvider) {
        final Binding b = slotBinding(owner, slot, key, key2, op, value, resourcesProvider);
        final int color = b.resolve();
        setSlot(owner, b, color);
        b.applied = color;
    }

    private static Binding slotBinding(View owner, int slot, int key, int key2, int op, float value, Theme.ResourcesProvider resourcesProvider) {
        final Binding b = customBinding(owner, slot);
        b.set(key, key2, op, value, resourcesProvider);
        b.getter = null;
        b.setter = null;
        b.viewGetter = null;
        b.viewSetter = null;
        b.builtin = true;
        return b;
    }

    private static int getSlot(View view, Binding b) {
        switch (b.slot) {
            case SLOT_AB_ITEMS: return view instanceof ActionBar ? ((ActionBar) view).itemsColor : ~b.applied;
            case SLOT_AB_ITEMS_AM: return view instanceof ActionBar ? ((ActionBar) view).itemsActionModeColor : ~b.applied;
            case SLOT_AB_SELECTOR: return view instanceof ActionBar ? ((ActionBar) view).itemsBackgroundColor : ~b.applied;
            case SLOT_AB_SELECTOR_AM: return view instanceof ActionBar ? ((ActionBar) view).itemsActionModeBackgroundColor : ~b.applied;
            case SLOT_AB_TITLE: return view instanceof ActionBar ? ((ActionBar) view).getTitleColorToSet() : ~b.applied;
            case SLOT_AB_SUBTITLE: return view instanceof ActionBar ? ((ActionBar) view).getSubtitleColorInternal() : ~b.applied;
            case SLOT_LIST_SELECTOR: return view instanceof RecyclerListView ? ((RecyclerListView) view).getSelectorDrawableColor() : ~b.applied;
            case SLOT_LIST_GLOW: return view instanceof RecyclerView ? ((RecyclerView) view).getGlowColor() : ~b.applied;
            case SLOT_RADIO: return view instanceof RadioButton ? ((RadioButton) view).getColor() : ~b.applied;
            case SLOT_RADIO_CHECKED: return view instanceof RadioButton ? ((RadioButton) view).getCheckedColor() : ~b.applied;
            case SLOT_THEMED_BACKGROUND: {
                final Drawable drawable = b.drawable != null ? b.drawable.get() : null;
                return drawable != null && view.getBackground() == drawable ? b.applied : ~b.applied;
            }
        }
        if (view instanceof EditTextBoldCursor) {
            final EditTextBoldCursor editText = (EditTextBoldCursor) view;
            switch (b.slot) {
                case SLOT_CURSOR: return editText.getCursorColor();
                case SLOT_LINE: return editText.getLineColor();
                case SLOT_LINE_ACTIVE: return editText.getActiveLineColor();
                case SLOT_LINE_ERROR: return editText.getErrorLineColor();
                case SLOT_EDIT_HINT: return editText.getHintColor();
                case SLOT_EDIT_HEADER_HINT: return editText.getHeaderHintColor();
                case SLOT_EDIT_HANDLES: return editText.getHandlesColor();
            }
        }
        return ~b.applied;
    }

    private static void setSlot(View view, Binding b, int color) {
        switch (b.slot) {
            case SLOT_AB_ITEMS: case SLOT_AB_ITEMS_AM:
                if (view instanceof ActionBar) ((ActionBar) view).setItemsColor(color, b.slot == SLOT_AB_ITEMS_AM);
                return;
            case SLOT_AB_SELECTOR: case SLOT_AB_SELECTOR_AM:
                if (view instanceof ActionBar) ((ActionBar) view).setItemsBackgroundColor(color, b.slot == SLOT_AB_SELECTOR_AM);
                return;
            case SLOT_AB_TITLE:
                if (view instanceof ActionBar) ((ActionBar) view).setTitleColor(color);
                return;
            case SLOT_AB_SUBTITLE:
                if (view instanceof ActionBar) ((ActionBar) view).setSubtitleColor(color);
                return;
            case SLOT_LIST_SELECTOR:
                if (view instanceof RecyclerListView) ((RecyclerListView) view).setSelectorDrawableColor(color);
                return;
            case SLOT_LIST_GLOW:
                if (view instanceof RecyclerView) ((RecyclerView) view).setGlowColor(color);
                return;
            case SLOT_RADIO:
                if (view instanceof RadioButton) ((RadioButton) view).setBackgroundColor(color);
                return;
            case SLOT_RADIO_CHECKED:
                if (view instanceof RadioButton) ((RadioButton) view).setCheckedColor(color);
                return;
            case SLOT_THEMED_BACKGROUND: {
                final Drawable drawable = b.drawable != null ? b.drawable.get() : null;
                if (drawable != null && color != b.applied) {
                    drawable.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY));
                    view.invalidate();
                }
                return;
            }
        }
        if (view instanceof EditTextBoldCursor) {
            final EditTextBoldCursor editText = (EditTextBoldCursor) view;
            switch (b.slot) {
                case SLOT_CURSOR: editText.setCursorColor(color); return;
                case SLOT_LINE: editText.setLineColor(color); return;
                case SLOT_LINE_ACTIVE: editText.setActiveLineColor(color); return;
                case SLOT_LINE_ERROR: editText.setErrorLineColor(color); return;
                case SLOT_EDIT_HINT: editText.setHintColor(color); return;
                case SLOT_EDIT_HEADER_HINT: editText.setHeaderHintColor(color); return;
                case SLOT_EDIT_HANDLES: editText.setHandlesColor(color); return;
            }
        }
    }

    private static Binding customBinding(View owner, int slot) {
        final Bindings bindings = bindings(owner, true);
        if (bindings.custom == null) {
            bindings.custom = new ArrayList<>(2);
        }
        for (int i = 0; i < bindings.custom.size(); ++i) {
            if (bindings.custom.get(i).slot == slot) {
                return bindings.custom.get(i);
            }
        }
        final Binding b = new Binding();
        b.slot = slot;
        bindings.custom.add(b);
        return b;
    }

    private static void bindCustom(View owner, int slot, int key, int key2, int op, float value, Theme.ResourcesProvider resourcesProvider, ColorGetter getter, ColorSetter setter) {
        final int color = resolve(key, key2, op, value, resourcesProvider);
        setter.set(color);
        final Bindings bindings = bindings(owner, true);
        if (bindings.custom == null) {
            bindings.custom = new ArrayList<>(2);
        }
        Binding b = null;
        for (int i = 0; i < bindings.custom.size(); ++i) {
            if (bindings.custom.get(i).slot == slot) {
                b = bindings.custom.get(i);
                break;
            }
        }
        if (b == null) {
            b = new Binding();
            b.slot = slot;
            bindings.custom.add(b);
        }
        b.set(key, key2, op, value, resourcesProvider);
        b.getter = getter;
        b.setter = setter;
        b.viewGetter = null;
        b.viewSetter = null;
        b.builtin = false;
        b.applied = color;
    }

    private static final int SLOT_AB_ITEMS = 1, SLOT_AB_ITEMS_AM = 2, SLOT_AB_SELECTOR = 3, SLOT_AB_SELECTOR_AM = 4,
        SLOT_AB_TITLE = 5, SLOT_AB_SUBTITLE = 6;

    public static void actionBarItemsColor(ActionBar actionBar, int key, boolean isActionMode) {
        actionBarItemsColor(actionBar, key, null, isActionMode);
    }

    public static void actionBarItemsColor(ActionBar actionBar, int key, Theme.ResourcesProvider resourcesProvider, boolean isActionMode) {
        bindSlot(actionBar, isActionMode ? SLOT_AB_ITEMS_AM : SLOT_AB_ITEMS, key, -1, OP_NONE, 0, resourcesProvider);
    }

    public static void actionBarItemsBackgroundColor(ActionBar actionBar, int key, boolean isActionMode) {
        actionBarItemsBackgroundColor(actionBar, key, null, isActionMode);
    }

    public static void actionBarItemsBackgroundColor(ActionBar actionBar, int key, Theme.ResourcesProvider resourcesProvider, boolean isActionMode) {
        bindSlot(actionBar, isActionMode ? SLOT_AB_SELECTOR_AM : SLOT_AB_SELECTOR, key, -1, OP_NONE, 0, resourcesProvider);
    }

    public static void actionBarTitleColor(ActionBar actionBar, int key) {
        actionBarTitleColor(actionBar, key, null);
    }

    public static void actionBarTitleColor(ActionBar actionBar, int key, Theme.ResourcesProvider resourcesProvider) {
        bindSlot(actionBar, SLOT_AB_TITLE, key, -1, OP_NONE, 0, resourcesProvider);
    }

    public static void actionBarSubtitleColor(ActionBar actionBar, int key) {
        actionBarSubtitleColor(actionBar, key, null);
    }

    public static void actionBarSubtitleColor(ActionBar actionBar, int key, Theme.ResourcesProvider resourcesProvider) {
        bindSlot(actionBar, SLOT_AB_SUBTITLE, key, -1, OP_NONE, 0, resourcesProvider);
    }

    private static final int SLOT_CURSOR = 30, SLOT_LINE = 31, SLOT_LINE_ACTIVE = 32, SLOT_LINE_ERROR = 33,
        SLOT_EDIT_HINT = 34, SLOT_EDIT_HEADER_HINT = 35, SLOT_EDIT_HANDLES = 36;

    public static void editHintColor(EditTextBoldCursor editText, int key) {
        editHintColor(editText, key, null);
    }

    public static void editHintColor(EditTextBoldCursor editText, int key, Theme.ResourcesProvider resourcesProvider) {
        bindSlot(editText, SLOT_EDIT_HINT, key, -1, OP_NONE, 0, resourcesProvider);
    }

    public static void editHeaderHintColor(EditTextBoldCursor editText, int key) {
        editHeaderHintColor(editText, key, null);
    }

    public static void editHeaderHintColor(EditTextBoldCursor editText, int key, Theme.ResourcesProvider resourcesProvider) {
        bindSlot(editText, SLOT_EDIT_HEADER_HINT, key, -1, OP_NONE, 0, resourcesProvider);
    }

    public static void editHandlesColor(EditTextBoldCursor editText, int key) {
        editHandlesColor(editText, key, null);
    }

    public static void editHandlesColor(EditTextBoldCursor editText, int key, Theme.ResourcesProvider resourcesProvider) {
        bindSlot(editText, SLOT_EDIT_HANDLES, key, -1, OP_NONE, 0, resourcesProvider);
    }

    public static void cursorColor(EditTextBoldCursor editText, int key) {
        cursorColor(editText, key, null);
    }

    public static void cursorColor(EditTextBoldCursor editText, int key, Theme.ResourcesProvider resourcesProvider) {
        bindSlot(editText, SLOT_CURSOR, key, -1, OP_NONE, 0, resourcesProvider);
    }

    public static void lineColors(EditTextBoldCursor editText, int key, int activeKey, int errorKey) {
        lineColors(editText, key, activeKey, errorKey, null);
    }

    public static void lineColors(EditTextBoldCursor editText, int key, int activeKey, int errorKey, Theme.ResourcesProvider resourcesProvider) {
        editText.setLineColors(Theme.getColor(key, resourcesProvider), Theme.getColor(activeKey, resourcesProvider), Theme.getColor(errorKey, resourcesProvider));
        slotBinding(editText, SLOT_LINE, key, -1, OP_NONE, 0, resourcesProvider).applied = editText.getLineColor();
        slotBinding(editText, SLOT_LINE_ACTIVE, activeKey, -1, OP_NONE, 0, resourcesProvider).applied = editText.getActiveLineColor();
        slotBinding(editText, SLOT_LINE_ERROR, errorKey, -1, OP_NONE, 0, resourcesProvider).applied = editText.getErrorLineColor();
    }

    private static final int SLOT_LIST_SELECTOR = 20, SLOT_LIST_GLOW = 21, SLOT_THEMED_BACKGROUND = 22,
        SLOT_RADIO = 23, SLOT_RADIO_CHECKED = 24;

    public static void radioColors(RadioButton radioButton, int key, int checkedKey) {
        radioColors(radioButton, key, checkedKey, null);
    }

    public static void radioColors(RadioButton radioButton, int key, int checkedKey, Theme.ResourcesProvider resourcesProvider) {
        radioButton.setColor(Theme.getColor(key, resourcesProvider), Theme.getColor(checkedKey, resourcesProvider));
        bindSlot(radioButton, SLOT_RADIO, key, -1, OP_NONE, 0, resourcesProvider);
        bindSlot(radioButton, SLOT_RADIO_CHECKED, checkedKey, -1, OP_NONE, 0, resourcesProvider);
    }

    public static void themedBackground(View view, android.content.Context context, int resId, int key) {
        themedBackground(view, context, resId, key, null);
    }

    public static void themedBackground(View view, android.content.Context context, int resId, int key, Theme.ResourcesProvider resourcesProvider) {
        final Drawable drawable = Theme.getThemedDrawableByKey(context, resId, key, resourcesProvider);
        view.setBackground(drawable);
        if (drawable == null) {
            return;
        }
        final Binding b = slotBinding(view, SLOT_THEMED_BACKGROUND, key, -1, OP_NONE, 0, resourcesProvider);
        b.drawable = new WeakReference<>(drawable);
        b.applied = Theme.getColor(key, resourcesProvider);
    }

    public static void listSelectorColor(RecyclerListView listView, int key) {
        listSelectorColor(listView, key, null);
    }

    public static void listSelectorColor(RecyclerListView listView, int key, Theme.ResourcesProvider resourcesProvider) {
        bindSlot(listView, SLOT_LIST_SELECTOR, key, -1, OP_NONE, 0, resourcesProvider);
    }

    public static void listGlowColor(RecyclerView listView, int key) {
        listGlowColor(listView, key, null);
    }

    public static void listGlowColor(RecyclerView listView, int key, Theme.ResourcesProvider resourcesProvider) {
        bindSlot(listView, SLOT_LIST_GLOW, key, -1, OP_NONE, 0, resourcesProvider);
    }

    public static void selectorBackground(View view, Drawable drawable, Spec content, Spec ripple, Theme.ResourcesProvider resourcesProvider) {
        view.setBackground(drawable);
        bindBackground(view, drawable, content, ripple, resourcesProvider);
    }

    public static void selectorBackground(View view, Drawable drawable, Spec content, Spec ripple) {
        selectorBackground(view, drawable, content, ripple, null);
    }

    public static void fillBackground(View view, Drawable drawable, Spec fill, Theme.ResourcesProvider resourcesProvider) {
        view.setBackground(drawable);
        bindBackground(view, drawable, fill, null, resourcesProvider);
    }

    public static void fillBackground(View view, Drawable drawable, Spec fill) {
        fillBackground(view, drawable, fill, null);
    }

    private static void bindBackground(View view, Drawable drawable, Spec content, Spec ripple, Theme.ResourcesProvider resourcesProvider) {
        final Bindings bindings = bindings(view, true);
        bindings.background = drawable;
        bindings.backgroundContent = null;
        bindings.backgroundRipple = null;
        if (drawable == null) {
            return;
        }
        if (content != null) {
            final Binding b = bindings.backgroundContent = new Binding();
            b.set(content.key, content.key2, content.op, content.value, resourcesProvider);
            b.applied = contentColor(drawable);
        }
        if (ripple != null) {
            final Binding b = bindings.backgroundRipple = new Binding();
            b.set(ripple.key, ripple.key2, ripple.op, ripple.value, resourcesProvider);
            b.applied = b.resolve();
        }
    }

    private static int contentColor(Drawable drawable) {
        Drawable d = drawable;
        if (d instanceof android.graphics.drawable.InsetDrawable) {
            d = ((android.graphics.drawable.InsetDrawable) d).getDrawable();
        }
        if (d instanceof android.graphics.drawable.RippleDrawable) {
            final android.graphics.drawable.RippleDrawable r = (android.graphics.drawable.RippleDrawable) d;
            d = r.getNumberOfLayers() > 0 && r.getId(0) != android.R.id.mask ? r.getDrawable(0) : null;
        }
        if (d instanceof android.graphics.drawable.ShapeDrawable) {
            return ((android.graphics.drawable.ShapeDrawable) d).getPaint().getColor();
        }
        if (d instanceof ColorDrawable) {
            return ((ColorDrawable) d).getColor();
        }
        return 0;
    }

    private static void setContentColor(Drawable drawable, int color) {
        Drawable d = drawable;
        if (d instanceof android.graphics.drawable.InsetDrawable) {
            d = ((android.graphics.drawable.InsetDrawable) d).getDrawable();
        }
        if (d instanceof android.graphics.drawable.RippleDrawable) {
            final android.graphics.drawable.RippleDrawable r = (android.graphics.drawable.RippleDrawable) d;
            d = r.getNumberOfLayers() > 0 && r.getId(0) != android.R.id.mask ? r.getDrawable(0) : null;
        }
        if (d instanceof android.graphics.drawable.ShapeDrawable) {
            ((android.graphics.drawable.ShapeDrawable) d).getPaint().setColor(color);
            drawable.invalidateSelf();
        } else if (d instanceof ColorDrawable) {
            ((ColorDrawable) d).setColor(color);
        }
    }

    private static void applyBackground(View view, Bindings bindings) {
        if (bindings.background == null || view.getBackground() != bindings.background) {
            return;
        }
        final Binding content = bindings.backgroundContent;
        if (content != null && !content.providerGone()) {
            final int color = content.resolve();
            final int current = contentColor(bindings.background);
            if (current == content.applied || current == color) {
                if (current != color) {
                    setContentColor(bindings.background, color);
                }
                content.applied = color;
            }
        }
        final Binding ripple = bindings.backgroundRipple;
        if (ripple != null && !ripple.providerGone() && bindings.background instanceof android.graphics.drawable.RippleDrawable) {
            final int color = ripple.resolve();
            if (color != ripple.applied) {
                ((android.graphics.drawable.RippleDrawable) bindings.background).setColor(android.content.res.ColorStateList.valueOf(color));
                ripple.applied = color;
            }
        }
    }

    public static void clear(View view) {
        if (view != null) {
            view.setTag(R.id.theme_bind, null);
        }
    }

    private static Bindings bindings(View view, boolean create) {
        final Object tag = view.getTag(R.id.theme_bind);
        if (tag instanceof Bindings) {
            return (Bindings) tag;
        }
        if (!create) {
            return null;
        }
        final Bindings bindings = new Bindings();
        view.setTag(R.id.theme_bind, bindings);
        return bindings;
    }

    private static Binding put(View view, int slot, int key, int key2, int op, float value, Theme.ResourcesProvider resourcesProvider, int color, Object appliedObject) {
        final Bindings bindings = bindings(view, true);
        Binding b = bindings.slots[slot];
        if (b == null) {
            b = bindings.slots[slot] = new Binding();
        }
        b.set(key, key2, op, value, resourcesProvider);
        b.applied = color;
        b.appliedObject = appliedObject;
        return b;
    }

    public static void update(View view) {
        final Bindings bindings = bindings(view, false);
        if (bindings == null) {
            return;
        }
        for (int slot = 0; slot < SLOTS; ++slot) {
            final Binding b = bindings.slots[slot];
            if (b != null) {
                apply(view, slot, b);
            }
        }
        if (bindings.drawables != null) {
            boolean changed = false;
            for (int i = 0; i < bindings.drawables.size(); ++i) {
                changed |= applyDrawable(bindings.drawables.get(i));
            }
            if (changed) {
                view.invalidate();
            }
        }
        applyBackground(view, bindings);
        if (bindings.custom != null) {
            for (int i = 0; i < bindings.custom.size(); ++i) {
                final Binding b = bindings.custom.get(i);
                if (b.providerGone()) continue;
                final int color = b.resolve();
                final int current = b.builtin ? getSlot(view, b) : b.viewGetter != null ? b.viewGetter.get(view) : b.getter != null ? b.getter.get() : ~b.applied;
                if (current != b.applied && current != color) continue;
                if (current != color) {
                    if (b.builtin) {
                        setSlot(view, b, color);
                    } else if (b.viewSetter != null) {
                        b.viewSetter.set(view, color);
                    } else if (b.setter != null) {
                        b.setter.set(color);
                    }
                }
                b.applied = color;
            }
        }
    }

    private static void apply(View view, int slot, Binding b) {
        if (b.providerGone()) return;
        final int color = b.resolve();
        switch (slot) {
            case TEXT: {
                if (view instanceof TextView) {
                    final TextView tv = (TextView) view;
                    if (tv.getTextColors().isStateful()) return;
                    final int current = tv.getCurrentTextColor();
                    if (current != b.applied && current != color) return;
                    if (current != color) tv.setTextColor(color);
                } else if (view instanceof SimpleTextView || view instanceof AnimatedTextView) {
                    final int current = view instanceof SimpleTextView ? ((SimpleTextView) view).getTextColor() : ((AnimatedTextView) view).getTextColor();
                    if (!sameRgb(current, b.applied) && !sameRgb(current, color)) return;
                    if (current != color) {
                        if (view instanceof SimpleTextView) {
                            ((SimpleTextView) view).setTextColor(color);
                        } else {
                            ((AnimatedTextView) view).setTextColor(color);
                        }
                    }
                } else {
                    return;
                }
                break;
            }
            case HINT: {
                final TextView tv = (TextView) view;
                if (tv.getHintTextColors() == null || tv.getHintTextColors().isStateful()) return;
                final int current = tv.getCurrentHintTextColor();
                if (current != b.applied && current != color) return;
                if (current != color) tv.setHintTextColor(color);
                break;
            }
            case LINK: {
                final TextView tv = (TextView) view;
                if (tv.getLinkTextColors() == null || tv.getLinkTextColors().isStateful()) return;
                final int current = tv.getLinkTextColors().getDefaultColor();
                if (current != b.applied && current != color) return;
                if (current != color) tv.setLinkTextColor(color);
                break;
            }
            case HIGHLIGHT: {
                final TextView tv = (TextView) view;
                final int current = tv.getHighlightColor();
                if (current != b.applied && current != color) return;
                if (current != color) tv.setHighlightColor(color);
                break;
            }
            case BACKGROUND: {
                final int current;
                if (view instanceof ActionBar && ((ActionBar) view).blurredBackground) {
                    current = ((ActionBar) view).getBackgroundColor();
                } else if (view instanceof ActionBarMenu && ((ActionBarMenu) view).parentActionBar != null
                        && ((ActionBarMenu) view).parentActionBar.blurredBackground
                        && ((ActionBarMenu) view).parentActionBar.isActionModeView(view)) {
                    current = ((ActionBarMenu) view).parentActionBar.getActionModeColorFor(view);
                } else {
                    final Drawable bg = view.getBackground();
                    if (!(bg instanceof ColorDrawable)) return;
                    current = ((ColorDrawable) bg).getColor();
                }
                if (current != b.applied && current != color) return;
                if (current != color) view.setBackgroundColor(color);
                break;
            }
            case COLOR_FILTER: {
                final ImageView iv = (ImageView) view;
                if (iv.getColorFilter() != b.appliedObject) return;
                if (color != b.applied) {
                    final PorterDuffColorFilter filter = new PorterDuffColorFilter(color, b.mode);
                    iv.setColorFilter(filter);
                    b.appliedObject = filter;
                }
                break;
            }
        }
        b.applied = color;
    }

    private static boolean sameRgb(int a, int b) {
        return (a & 0xFFFFFF) == (b & 0xFFFFFF);
    }

    private static boolean applyDrawable(Binding b) {
        final Drawable drawable = b.drawable.get();
        if (drawable == null || b.providerGone() || !b.untracked && drawable.getColorFilter() != b.appliedObject) return false;
        final int color = b.resolve();
        if (color == b.applied) return false;
        final PorterDuffColorFilter filter = new PorterDuffColorFilter(color, b.mode);
        drawable.setColorFilter(filter);
        b.appliedObject = filter;
        b.applied = color;
        return true;
    }

    private static final int VISIT_UPDATE = 0, VISIT_KEYS = 1;

    private static final class Walker implements androidx.core.util.Consumer<View> {
        final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        boolean withPool;
        int mode;
        HashSet<Integer> keys;
        boolean busy;

        void begin(boolean withPool, int mode, HashSet<Integer> keys) {
            this.busy = true;
            this.withPool = withPool;
            this.mode = mode;
            this.keys = keys;
            seen.clear();
        }

        void end() {
            seen.clear();
            keys = null;
            busy = false;
        }

        void walk(View view) {
            if (view == null || seen.put(view, Boolean.TRUE) != null) {
                return;
            }
            if (mode == VISIT_UPDATE) {
                visitUpdate(view);
            } else {
                addKeys(view, keys);
            }
            if (view instanceof RecyclerView) {
                ((RecyclerView) view).forEveryChild(withPool, this);
            } else if (view instanceof ViewGroup) {
                final ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); ++i) {
                    walk(group.getChildAt(i));
                }
            }
        }

        @Override
        public void accept(View child) {
            walk(child);
        }
    }

    private static final Walker sharedWalker = new Walker();

    private static Walker obtainWalker(boolean withPool, int mode, HashSet<Integer> keys) {
        final Walker walker = sharedWalker.busy ? new Walker() : sharedWalker;
        walker.begin(withPool, mode, keys);
        return walker;
    }

    private static List<View> roots() {
        final List<View> roots = AndroidUtilities.allGlobalViews();
        return roots != null ? roots : new ArrayList<>();
    }

    private static void visitUpdate(View view) {
        if (view instanceof Theme.Colorable) {
            try {
                ((Theme.Colorable) view).updateColors();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        try {
            update(view);
        } catch (Exception e) {
            FileLog.e(e);
        }
        updateDrawable(view.getBackground());
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            updateDrawable(view.getForeground());
        }
        view.invalidate();
    }

    private static void updateDrawable(Drawable drawable) {
        if (drawable instanceof Theme.Colorable) {
            try {
                ((Theme.Colorable) drawable).updateColors();
            } catch (Exception e) {
                FileLog.e(e);
            }
            drawable.invalidateSelf();
        }
    }

    public static void updateTree(View root, boolean withPool) {
        final Walker walker = obtainWalker(withPool, VISIT_UPDATE, null);
        try {
            walker.walk(root);
        } finally {
            walker.end();
        }
    }

    public static void updateAll(boolean withPool, View extraRoot) {
        generation++;
        final Walker walker = obtainWalker(withPool, VISIT_UPDATE, null);
        try {
            final List<View> roots = roots();
            for (int i = 0; i < roots.size(); ++i) {
                walker.walk(roots.get(i));
            }
            walker.walk(extraRoot);
        } finally {
            walker.end();
        }
    }

    public static void updateAll() {
        updateAll(true, null);
    }

    private static int generation = 1;

    public static int generation() {
        return generation;
    }

    private static long lastFrameTime = -1;
    private static float lastFrameProgress;

    public static void updateAllForFrame(float progress, View extraRoot) {
        final long now = AnimationUtils.currentAnimationTimeMillis();
        if (progress < 1f && now == lastFrameTime && progress == lastFrameProgress) {
            if (extraRoot != null && extraRoot.getWindowToken() == null) {
                updateTree(extraRoot, false);
            }
            return;
        }
        lastFrameTime = now;
        lastFrameProgress = progress;
        updateAll(progress >= 1f, extraRoot);
    }

    public static void collectColorKeys(View root, HashSet<Integer> out) {
        final Walker walker = obtainWalker(true, VISIT_KEYS, out);
        try {
            walker.walk(root);
        } finally {
            walker.end();
        }
    }

    public static HashSet<Integer> collectColorKeys() {
        final HashSet<Integer> out = new HashSet<>();
        final Walker walker = obtainWalker(true, VISIT_KEYS, out);
        try {
            final List<View> roots = roots();
            for (int i = 0; i < roots.size(); ++i) {
                walker.walk(roots.get(i));
            }
        } finally {
            walker.end();
        }
        return out;
    }

    private static void addKeys(View view, HashSet<Integer> out) {
        if (view instanceof Theme.Colorable) {
            ((Theme.Colorable) view).addColorKeys(out);
        }
        final Bindings bindings = bindings(view, false);
        if (bindings == null) {
            return;
        }
        for (int slot = 0; slot < SLOTS; ++slot) {
            if (bindings.slots[slot] != null) {
                addKeys(bindings.slots[slot], out);
            }
        }
        if (bindings.drawables != null) {
            for (int i = 0; i < bindings.drawables.size(); ++i) {
                addKeys(bindings.drawables.get(i), out);
            }
        }
        if (bindings.custom != null) {
            for (int i = 0; i < bindings.custom.size(); ++i) {
                addKeys(bindings.custom.get(i), out);
            }
        }
        if (bindings.backgroundContent != null) {
            addKeys(bindings.backgroundContent, out);
        }
        if (bindings.backgroundRipple != null) {
            addKeys(bindings.backgroundRipple, out);
        }
    }

    private static void addKeys(Binding b, HashSet<Integer> out) {
        out.add(b.key);
        if (b.key2 >= 0) {
            out.add(b.key2);
        }
    }
}
