package app.nimarkogram.messenger.plugins.ui.components.templates;

import android.content.Context;
import android.view.View;

import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

import app.nimarkogram.messenger.plugins.PluginsController;
import app.nimarkogram.messenger.plugins.ui.PluginUiRegistry;

public class UniversalFragment extends org.telegram.ui.Components.UniversalFragment
        implements PluginUiRegistry.RuntimeOwnedUi {
    private UniversalFragmentDelegate delegate;
    private PluginsController.PluginRuntimeToken delegateRuntimeToken;

    public UniversalFragment(UniversalFragmentDelegate universalFragmentDelegate) {
        setDelegate(universalFragmentDelegate);
    }

    public UniversalFragmentDelegate getDelegate() {
        PluginRuntimeDelegate.requireMainThread();
        return this.delegate;
    }

    public void setDelegate(UniversalFragmentDelegate universalFragmentDelegate) {
        PluginsController.PluginRuntimeToken runtimeToken =
                PluginRuntimeDelegate.capture(universalFragmentDelegate);
        clearDelegate(null, false);
        if (universalFragmentDelegate == null) {
            return;
        }
        this.delegate = universalFragmentDelegate;
        this.delegateRuntimeToken = runtimeToken;
        if (!PluginUiRegistry.registerRuntimeOwnedUi(runtimeToken, this)) {
            clearDelegate(runtimeToken, true);
        }
    }

    private void clearDelegate(
            PluginsController.PluginRuntimeToken runtimeToken,
            boolean clearMenuListener) {
        PluginsController.PluginRuntimeToken ownedToken =
                this.delegateRuntimeToken;
        if (runtimeToken != null && !runtimeToken.equals(ownedToken)) {
            return;
        }
        boolean hadDelegate = this.delegate != null || ownedToken != null;
        this.delegate = null;
        this.delegateRuntimeToken = null;
        if (ownedToken != null) {
            PluginUiRegistry.unregisterRuntimeOwnedUi(ownedToken, this);
        }
        if (clearMenuListener && this.actionBar != null) {
            
            this.actionBar.setActionBarMenuOnItemClick(
                    new ActionBar.ActionBarMenuOnItemClick() {
                        @Override
                        public void onItemClick(int id) {
                            if (id == -1) {
                                finishFragment();
                            }
                        }
                    });
        }
        if (hadDelegate) {
            onPluginDelegateCleared();
        }
    }

    protected void onPluginDelegateCleared() {
    }

    @Override
    public void clearPluginUiReferences(
            PluginsController.PluginRuntimeToken runtimeToken) {
        PluginRuntimeDelegate.requireMainThread();
        clearDelegate(runtimeToken, true);
    }

    @Override
    public View createView(Context context) {
        PluginRuntimeDelegate.requireMainThread();
        UniversalFragmentDelegate delegate = this.delegate;
        PluginsController.PluginRuntimeToken runtimeToken = this.delegateRuntimeToken;
        View beforeView = delegate != null
                ? PluginRuntimeDelegate.call(
                        runtimeToken, delegate::beforeCreateView, null)
                : null;
        
        if (beforeView != null) {
            return beforeView;
        }

        View view = super.createView(context);
        
        this.actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int i) {
                if (i == -1) {
                    PluginRuntimeDelegate.requireMainThread();
                    UniversalFragmentDelegate delegate =
                            UniversalFragment.this.delegate;
                    PluginsController.PluginRuntimeToken runtimeToken =
                            UniversalFragment.this.delegateRuntimeToken;
                    Boolean shouldClose = delegate != null
                            ? PluginRuntimeDelegate.call(
                                    runtimeToken,
                                    delegate::onBackPressed,
                                    null)
                            : null;
                    if (shouldClose == null || shouldClose) {
                        UniversalFragment.this.finishFragment();
                    }
                } else {
                    PluginRuntimeDelegate.requireMainThread();
                    UniversalFragmentDelegate delegate =
                            UniversalFragment.this.delegate;
                    PluginsController.PluginRuntimeToken runtimeToken =
                            UniversalFragment.this.delegateRuntimeToken;
                    if (delegate != null) {
                        PluginRuntimeDelegate.run(
                                runtimeToken, () -> delegate.onMenuItemClick(i));
                    }
                }
            }
        });

        UniversalFragmentDelegate delegate2 = this.delegate;
        PluginsController.PluginRuntimeToken runtimeToken2 =
                this.delegateRuntimeToken;
        if (delegate2 != null) {
            View afterView = PluginRuntimeDelegate.call(
                    runtimeToken2, () -> delegate2.afterCreateView(view), null);
            if (afterView != null) {
                return afterView;
            }
        }
        return view;
    }

    public ActionBarMenu getActionBarMenu() {
        PluginRuntimeDelegate.requireMainThread();
        return this.actionBar.createMenu();
    }

    @Override
    protected CharSequence getTitle() {
        PluginRuntimeDelegate.requireMainThread();
        UniversalFragmentDelegate delegate = this.delegate;
        if (delegate != null) {
            return PluginRuntimeDelegate.call(
                    this.delegateRuntimeToken, delegate::getTitle, null);
        }
        return null;
    }

    public void setTitle(CharSequence title, boolean animated, long duration) {
        PluginRuntimeDelegate.requireMainThread();
        if (animated) {
            ActionBar actionBar = this.actionBar;
            if (duration <= 0) {
                duration = 300;
            }
            actionBar.setTitleAnimated(title, false, duration);
        } else {
            this.actionBar.setTitle(title);
        }
    }

    @Override
    public void fillItems(ArrayList<UItem> arrayList, UniversalAdapter universalAdapter) {
        PluginRuntimeDelegate.requireMainThread();
        UniversalFragmentDelegate delegate = this.delegate;
        if (delegate != null) {
            PluginRuntimeDelegate.run(
                    this.delegateRuntimeToken,
                    () -> delegate.fillItems(arrayList, universalAdapter));
        }
    }

    @Override
    public void onClick(UItem uItem, View view, int i, float f, float f2) {
        PluginRuntimeDelegate.requireMainThread();
        UniversalFragmentDelegate delegate = this.delegate;
        if (delegate != null) {
            PluginRuntimeDelegate.run(
                    this.delegateRuntimeToken,
                    () -> delegate.onClick(uItem, view, i, f, f2));
        }
    }

    @Override
    protected boolean onLongClick(UItem uItem, View view, int i, float f, float f2) {
        PluginRuntimeDelegate.requireMainThread();
        UniversalFragmentDelegate delegate = this.delegate;
        if (delegate != null) {
            Boolean result = PluginRuntimeDelegate.call(
                    this.delegateRuntimeToken,
                    () -> delegate.onLongClick(uItem, view, i, f, f2),
                    null);
            if (result != null) {
                return result;
            }
        }
        return false;
    }

    @Override
    public boolean onFragmentCreate() {
        PluginRuntimeDelegate.requireMainThread();
        UniversalFragmentDelegate delegate = this.delegate;
        if (delegate != null) {
            PluginRuntimeDelegate.run(
                    this.delegateRuntimeToken, delegate::onFragmentCreate);
        }
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        PluginRuntimeDelegate.requireMainThread();
        UniversalFragmentDelegate delegate = this.delegate;
        PluginsController.PluginRuntimeToken runtimeToken =
                this.delegateRuntimeToken;
        try {
            if (delegate != null) {
                PluginRuntimeDelegate.run(
                        runtimeToken, delegate::onFragmentDestroy);
            }
        } finally {
            clearDelegate(null, true);
            super.onFragmentDestroy();
        }
    }

    public interface UniversalFragmentDelegate {
        default View beforeCreateView() { return null; }
        default View afterCreateView(View view) { return view; }
        
        void fillItems(ArrayList<UItem> arrayList, UniversalAdapter universalAdapter);
        
        default CharSequence getTitle() { return null; }
        default Boolean onBackPressed() { return null; }
        
        default void onClick(UItem uItem, View view, int i, float f, float f2) {}
        default void onFragmentCreate() {}
        default void onFragmentDestroy() {}
        default boolean onLongClick(UItem uItem, View view, int i, float f, float f2) { return false; }
        default void onMenuItemClick(int i) {}
    }
}
