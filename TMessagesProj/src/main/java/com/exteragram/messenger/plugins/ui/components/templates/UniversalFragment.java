package com.exteragram.messenger.plugins.ui.components.templates;

import android.view.View;

import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

import app.nimarkogram.messenger.plugins.PluginsController;
import app.nimarkogram.messenger.plugins.ui.components.templates.PluginRuntimeDelegate;

public class UniversalFragment extends app.nimarkogram.messenger.plugins.ui.components.templates.UniversalFragment {
    private GuardedDelegate bridgeDelegate;

    public UniversalFragment(UniversalFragmentDelegate delegate) {
        super(adapt(delegate));
        app.nimarkogram.messenger.plugins.ui.components.templates
                .UniversalFragment.UniversalFragmentDelegate guardedDelegate =
                super.getDelegate();
        if (guardedDelegate instanceof GuardedDelegate) {
            this.bridgeDelegate = (GuardedDelegate) guardedDelegate;
        }
    }

    public interface UniversalFragmentDelegate {
        default View beforeCreateView() { return null; }
        default View afterCreateView(View view) { return view; }
        void fillItems(ArrayList<UItem> items, UniversalAdapter adapter);
        default CharSequence getTitle() { return null; }
        default Boolean onBackPressed() { return null; }
        default void onClick(UItem item, View view, int position, float x, float y) {}
        default void onFragmentCreate() {}
        default void onFragmentDestroy() {}
        default boolean onLongClick(UItem item, View view, int position, float x, float y) { return false; }
        default void onMenuItemClick(int id) {}
    }

    private static app.nimarkogram.messenger.plugins.ui.components.templates.UniversalFragment.UniversalFragmentDelegate
            adapt(final UniversalFragmentDelegate d) {
        if (d == null) return null;
        return new GuardedDelegate(
                d, PluginRuntimeDelegate.capture(d));
    }

    @Override
    protected void onPluginDelegateCleared() {
        GuardedDelegate delegate = this.bridgeDelegate;
        this.bridgeDelegate = null;
        if (delegate != null) {
            delegate.clear();
        }
    }

    private static final class GuardedDelegate implements
            UniversalFragmentDelegate,
            app.nimarkogram.messenger.plugins.ui.components.templates
                    .UniversalFragment.UniversalFragmentDelegate {
        private UniversalFragmentDelegate delegate;
        private final PluginsController.PluginRuntimeToken runtimeToken;

        GuardedDelegate(
                UniversalFragmentDelegate delegate,
                PluginsController.PluginRuntimeToken runtimeToken) {
            this.delegate = delegate;
            this.runtimeToken = runtimeToken;
        }

        void clear() {
            this.delegate = null;
        }

        @Override
        public View beforeCreateView() {
            UniversalFragmentDelegate delegate = this.delegate;
            return delegate != null
                    ? PluginRuntimeDelegate.call(
                            runtimeToken, delegate::beforeCreateView, null)
                    : null;
        }

        @Override
        public View afterCreateView(View view) {
            UniversalFragmentDelegate delegate = this.delegate;
            return delegate != null
                    ? PluginRuntimeDelegate.call(
                            runtimeToken,
                            () -> delegate.afterCreateView(view),
                            null)
                    : null;
        }

        @Override
        public void fillItems(
                ArrayList<UItem> items, UniversalAdapter adapter) {
            UniversalFragmentDelegate delegate = this.delegate;
            if (delegate != null) {
                PluginRuntimeDelegate.run(
                        runtimeToken,
                        () -> delegate.fillItems(items, adapter));
            }
        }

        @Override
        public CharSequence getTitle() {
            UniversalFragmentDelegate delegate = this.delegate;
            return delegate != null
                    ? PluginRuntimeDelegate.call(
                            runtimeToken, delegate::getTitle, null)
                    : null;
        }

        @Override
        public Boolean onBackPressed() {
            UniversalFragmentDelegate delegate = this.delegate;
            return delegate != null
                    ? PluginRuntimeDelegate.call(
                            runtimeToken, delegate::onBackPressed, null)
                    : null;
        }

        @Override
        public void onClick(
                UItem item,
                View view,
                int position,
                float x,
                float y) {
            UniversalFragmentDelegate delegate = this.delegate;
            if (delegate != null) {
                PluginRuntimeDelegate.run(
                        runtimeToken,
                        () -> delegate.onClick(
                                item, view, position, x, y));
            }
        }

        @Override
        public void onFragmentCreate() {
            UniversalFragmentDelegate delegate = this.delegate;
            if (delegate != null) {
                PluginRuntimeDelegate.run(
                        runtimeToken, delegate::onFragmentCreate);
            }
        }

        @Override
        public void onFragmentDestroy() {
            UniversalFragmentDelegate delegate = this.delegate;
            if (delegate != null) {
                PluginRuntimeDelegate.run(
                        runtimeToken, delegate::onFragmentDestroy);
            }
        }

        @Override
        public boolean onLongClick(
                UItem item,
                View view,
                int position,
                float x,
                float y) {
            UniversalFragmentDelegate delegate = this.delegate;
            return delegate != null && PluginRuntimeDelegate.call(
                    runtimeToken,
                    () -> delegate.onLongClick(
                            item, view, position, x, y),
                    false);
        }

        @Override
        public void onMenuItemClick(int id) {
            UniversalFragmentDelegate delegate = this.delegate;
            if (delegate != null) {
                PluginRuntimeDelegate.run(
                        runtimeToken, () -> delegate.onMenuItemClick(id));
            }
        }
    }
}
