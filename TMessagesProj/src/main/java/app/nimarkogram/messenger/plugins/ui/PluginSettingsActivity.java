package app.nimarkogram.messenger.plugins.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.chaquo.python.PyObject;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.NotificationsCheckCell;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import app.nimarkogram.messenger.plugins.Plugin;
import app.nimarkogram.messenger.plugins.PluginsConstants;
import app.nimarkogram.messenger.plugins.PluginsController;
import app.nimarkogram.messenger.plugins.PythonPluginsEngine;
import app.nimarkogram.messenger.plugins.models.DividerSetting;
import app.nimarkogram.messenger.plugins.models.EditTextSetting;
import app.nimarkogram.messenger.plugins.models.HeaderSetting;
import app.nimarkogram.messenger.plugins.models.InputSetting;
import app.nimarkogram.messenger.plugins.models.SelectorSetting;
import app.nimarkogram.messenger.plugins.models.SettingItem;
import app.nimarkogram.messenger.plugins.models.SwitchSetting;
import app.nimarkogram.messenger.plugins.models.TextSetting;
import app.nimarkogram.messenger.plugins.ui.components.PluginEditTextCell;
import android.view.HapticFeedbackConstants;
import app.nimarkogram.messenger.preferences.BasePreferencesActivity;

public class PluginSettingsActivity extends BasePreferencesActivity implements
        NotificationCenter.NotificationCenterDelegate,
        PluginUiRegistry.RuntimeOwnedUi {
    private volatile PyObject createSubFragmentCallback;
    private final String customTitle;
    private final Plugin plugin;
    private final PluginsController.PluginRuntimeToken runtimeToken;
    private ActionBarMenuItem resetItem;
    private List<SettingItem> settingItems;
    private String settingsLinkPrefix;
    private Integer targetSettingItemId;
    private String targetSettingName;
    private boolean runtimeLossFinishScheduled;
    
    private final java.util.HashMap<Object, View[]> customViewCache = new java.util.HashMap<>();

    public PluginSettingsActivity(Plugin plugin) {
        this(plugin, null, null, null, null);
    }

    public PluginSettingsActivity(Plugin plugin, String str) {
        this(plugin, null, null, null, str);
    }

    public PluginSettingsActivity(Plugin plugin, String str, List<SettingItem> list, PyObject pyObject) {
        this(plugin, str, list, pyObject, null);
    }

    public PluginSettingsActivity(Plugin plugin, String str, List<SettingItem> list, PyObject pyObject, String str2) {
        this.plugin = plugin;
        PluginsController.PluginRuntimeToken definitionsToken =
                list != null && !list.isEmpty() ? list.get(0).runtimeToken : null;
        this.runtimeToken = definitionsToken != null ? definitionsToken
                : plugin != null
                        ? PluginsController.getInstance().getCurrentPluginRuntime(plugin.getId())
                        : null;
        this.customTitle = str;
        this.settingItems = list;
        this.createSubFragmentCallback = pyObject;
        this.targetSettingName = str2;
        this.targetSettingItemId = null;
        this.settingsLinkPrefix = null;
    }

    public PluginSettingsActivity setSettingsLinkPrefix(String str) {
        this.settingsLinkPrefix = str;
        return this;
    }

    private interface PluginResultMapper<T> {
        T map(PyObject result) throws Throwable;
    }

    private <T> T callPluginCallback(
            PyObject callback,
            PluginResultMapper<T> resultMapper,
            T staleResult,
            Object... args) {
        if (callback == null || plugin == null) {
            return staleResult;
        }
        PluginsController controller = PluginsController.getInstance();
        
        boolean entered = runtimeToken != null
                && controller.getPluginRuntimeTaskDecision(runtimeToken)
                        == PluginsController.RUNTIME_TASK_RUN
                && controller.enterPluginRuntime(runtimeToken);
        if (!entered) {
            return staleResult;
        }
        controller.getWatchdog().onPluginExecutionStarted(plugin.getId());
        try {
            PyObject result = callback.call(args);
            return resultMapper != null
                    ? resultMapper.map(result)
                    : staleResult;
        } catch (Throwable failure) {
            controller.getWatchdog()
                    .onPluginExecutionFailed(plugin.getId(), failure);
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            if (failure instanceof Error) throw (Error) failure;
            throw new RuntimeException(failure);
        } finally {
            controller.getWatchdog().onPluginExecutionFinished(plugin.getId());
            if (runtimeToken != null) {
                controller.exitPluginRuntime(runtimeToken);
            }
        }
    }

    private void runPluginCallback(PyObject callback, Object... args) {
        callPluginCallback(callback, result -> null, null, args);
    }

    private List<SettingItem> callSettingsCallback(PyObject callback) {
        return callPluginCallback(callback, result -> {
            ArrayList<SettingItem> items = new ArrayList<>();
            if (result == null) {
                return items;
            }
            PluginsController.PluginsEngine engine =
                    PluginsController.engines.get(PluginsConstants.PYTHON);
            if (engine instanceof PythonPluginsEngine) {
                items.addAll(((PythonPluginsEngine) engine)
                        .parsePySettingDefinitions(
                                result.asList(), runtimeToken));
            }
            return items;
        }, null);
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pluginSettingsRegistered);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pluginSettingsUnregistered);
        if (!super.onFragmentCreate()) {
            NotificationCenter.getGlobalInstance().removeObserver(
                    this, NotificationCenter.pluginSettingsRegistered);
            NotificationCenter.getGlobalInstance().removeObserver(
                    this, NotificationCenter.pluginSettingsUnregistered);
            return false;
        }
        if (runtimeToken != null
                && !PluginUiRegistry.registerRuntimeOwnedUi(
                        runtimeToken, this)) {
            clearPluginUiReferences(runtimeToken);
        }
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pluginSettingsRegistered);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pluginSettingsUnregistered);
        PluginUiRegistry.unregisterRuntimeOwnedUi(runtimeToken, this);
        
        createSubFragmentCallback = null;
        settingItems = null;
        customViewCache.clear();
        super.onFragmentDestroy();
    }

    @Override
    public void clearPluginUiReferences(
            PluginsController.PluginRuntimeToken ownerToken) {
        if (runtimeToken == null || !runtimeToken.equals(ownerToken)) {
            return;
        }
        clearRetainedPythonReferences();
        scheduleFinishForRuntimeLoss();
    }

    private void clearRetainedPythonReferences() {
        createSubFragmentCallback = null;
        List<SettingItem> oldItems = settingItems;
        settingItems = new ArrayList<>();
        clearSettingItems(oldItems);
        customViewCache.clear();
    }

    private static void clearSettingItems(List<SettingItem> items) {
        if (items == null) {
            return;
        }
        for (SettingItem item : items) {
            if (item != null) {
                item.clearPythonReferences();
            }
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.pluginSettingsRegistered) {
            String pluginId = (args.length > 0 && args[0] instanceof String) ? (String) args[0] : null;
            if (this.plugin != null && (pluginId == null || this.plugin.getId().equals(pluginId))) {
                PluginsController.PluginRuntimeToken eventRuntime =
                        args.length > 1
                                && args[1] instanceof PluginsController.PluginRuntimeToken
                                ? (PluginsController.PluginRuntimeToken) args[1]
                                : null;
                if ((eventRuntime != null
                        && !eventRuntime.equals(runtimeToken))
                        || !isExactRuntimeCurrent()) {
                    scheduleFinishForRuntimeLoss();
                    return;
                }
                if (this.createSubFragmentCallback != null) {
                    Utilities.pluginsQueue.postRunnable(this::lambda$didReceivedNotification$1);
                    return;
                }
                if (this.listView != null && this.listView.adapter != null) {
                    this.listView.adapter.update(true);
                    if (this.resetItem != null) {
                        AndroidUtilities.updateViewVisibilityAnimated(this.resetItem, PluginsController.getInstance().hasPluginSettingsPreferences(this.plugin.getId()), 0.5f, true);
                    }
                }
            }
        } else if (id == NotificationCenter.pluginSettingsUnregistered) {
            String pluginId = (args.length > 0 && args[0] instanceof String) ? (String) args[0] : null;
            if (this.plugin != null && (pluginId == null || this.plugin.getId().equals(pluginId))) {
                scheduleFinishForRuntimeLoss();
            }
        }
    }

    private boolean isExactRuntimeCurrent() {
        return runtimeToken != null
                && PluginsController.getInstance()
                        .getPluginRuntimeTaskDecision(runtimeToken)
                        == PluginsController.RUNTIME_TASK_RUN;
    }

    private void scheduleFinishForRuntimeLoss() {
        if (runtimeLossFinishScheduled || isFinished) {
            return;
        }
        runtimeLossFinishScheduled = true;
        PluginUiRegistry.runAfterTraversal(() -> {
            runtimeLossFinishScheduled = false;
            if (isFinished || plugin == null) {
                return;
            }
            PluginsController controller =
                    PluginsController.getInstance();
            if (!isExactRuntimeCurrent()
                    || !controller.hasPluginSettings(plugin.getId())) {
                finishFragment();
            }
        });
    }

    private void lambda$didReceivedNotification$1() {
        try {
            final List<SettingItem> arrayList =
                    callSettingsCallback(this.createSubFragmentCallback);
            if (arrayList == null) {
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (!isRuntimeCurrentForUi()) {
                    clearSettingItems(arrayList);
                    return;
                }
                this.settingItems = arrayList;
                customViewCache.clear();
                if (this.listView != null && this.listView.adapter != null) {
                    this.listView.adapter.update(true);
                }
            });
        } catch (Exception unused) {
        }
    }

    @Override
    public String getTitle() {
        return this.customTitle != null ? this.customTitle : (this.plugin != null ? this.plugin.getName() : "");
    }

    @Override
    public View createView(Context context) {
        super.createView(context);
        
        if (this.createSubFragmentCallback == null && this.plugin != null) {
            ActionBarMenuItem actionBarMenuItemAddItem = this.actionBar.createMenu().addItem(0, R.drawable.msg_reset);
            this.resetItem = actionBarMenuItemAddItem;
            actionBarMenuItemAddItem.setContentDescription(LocaleController.getString(R.string.Reset));
            AndroidUtilities.updateViewVisibilityAnimated(this.resetItem, PluginsController.getInstance().hasPluginSettingsPreferences(this.plugin.getId()), 0.5f, false);
            this.resetItem.setTag(null);
            this.resetItem.setOnClickListener(view -> {
                AlertDialog.Builder builder = new AlertDialog.Builder(getContext(), getResourceProvider());
                builder.setTitle(LocaleController.getString(R.string.Reset));
                builder.setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.ResetPluginSettingsInfo, this.plugin.getName())));
                builder.setPositiveButton(LocaleController.getString(R.string.Reset), (alertDialog, i) -> {
                    AndroidUtilities.updateViewVisibilityAnimated(this.resetItem, false, 0.5f, true);
                    PluginsController.getInstance().clearPluginSettingsPreferences(this.plugin.getId(), false);
                    PluginsController.getInstance().loadPluginSettings(this.plugin.getId());
                    AndroidUtilities.runOnUIThread(() -> BulletinFactory.of(this).createSimpleBulletin(R.raw.info, AndroidUtilities.replaceTags(LocaleController.formatString(R.string.ResetPluginSettings, this.plugin.getName()))).show());
                });
                builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
                AlertDialog alertDialogCreate = builder.create();
                showDialog(alertDialogCreate);
                TextView textView = (TextView) alertDialogCreate.getButton(-1);
                if (textView != null) {
                    textView.setTextColor(Theme.getColor(Theme.key_text_RedBold));
                }
            });
        }
        return this.fragmentView;
    }

    public void checkTargetSetting() {
        if (this.targetSettingItemId != null && this.listView != null && this.listView.adapter != null) {
            final int pos = this.listView.findPositionByItemId(this.targetSettingItemId);
            if (pos >= 0 && pos < this.listView.adapter.getItemCount()) {
                this.listView.highlightRow(() -> {
                    this.layoutManager.scrollToPositionWithOffset(pos, AndroidUtilities.dp(60.0f));
                    return pos;
                });
            }
            this.targetSettingItemId = null;
        } 
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (this.plugin == null) return;

        List<SettingItem> currentSettings = this.settingItems;
        if (currentSettings == null) {
            currentSettings = PluginsController.getInstance().getPluginSettingsList(this.plugin.getId());
        }

        if (currentSettings == null && this.createSubFragmentCallback != null) {
            
            for (int i = 0; i < 3; i++) {
                items.add(UItem.asFlicker(0xF11C0000 + i, FlickerLoadingView.CHECKBOX_TYPE));
            }
            return;
        }
        if (currentSettings == null || currentSettings.isEmpty()) return;

        boolean lastWasDivider = false;
        for (int idx = 0; idx < currentSettings.size(); idx++) {
            SettingItem item = currentSettings.get(idx);
            if (item == null) continue;
            if ("divider".equals(item.type)) {
                lastWasDivider = true;
            } else if ("header".equals(item.type)) {
                if (!lastWasDivider) {
                    UItem shadow = UItem.asShadow(null);
                    shadow.id = 0xC0DE0000 + idx;
                    items.add(shadow);
                }
                lastWasDivider = false;
            } else {
                lastWasDivider = false;
            }
            // fall through into the original switch below
            renderSettingItem(item, items);
        }
    }

    private void renderSettingItem(SettingItem item, ArrayList<UItem> items) {
        {

            int iconRes = 0;
            if (!TextUtils.isEmpty(item.icon)) {
                try {
                    
                    Context context = ApplicationLoader.applicationContext;
                    iconRes = context.getResources().getIdentifier(item.icon, "drawable", context.getPackageName());
                } catch (Exception ignored) {}
            }

            UItem uItem = null;
            String type = item.type;

            switch (type) {
                case "divider":
                    DividerSetting ds = (DividerSetting) item;
                    
                    uItem = UItem.asShadow(ds.text != null ? ds.text : "");
                    break;
                case "selector":
                    SelectorSetting ss = (SelectorSetting) item;
                    if (ss.key != null && ss.text != null && ss.items != null && ss.items.length > 0) {
                        int val = PluginsController.getInstance().getPluginSettingInt(this.plugin.getId(), ss.key, ss.defaultValue);
                        if (val < 0 || val >= ss.items.length) {
                            val = Math.max(0, Math.min(ss.defaultValue, ss.items.length - 1));
                            PluginsController.getInstance().setPluginSetting(this.plugin.getId(), ss.key, val);
                        }
                        uItem = UItem.asButton(0, ss.text, ss.items[val]);
                        uItem.texts = ss.items;
                        uItem.intValue = val;
                        uItem.iconResId = iconRes;
                        uItem.object2 = ss.key;
                        uItem.settingItem = ss;
                    }
                    break;
                case "input":
                    InputSetting is = (InputSetting) item;
                    if (is.key != null && is.text != null) {
                        String val = PluginsController.getInstance().getPluginSettingString(this.plugin.getId(), is.key, is.defaultValue);
                        uItem = UItem.asButton(0, is.text, val);
                        uItem.iconResId = iconRes;
                        uItem.object2 = is.key;
                        uItem.settingItem = is;
                    }
                    break;
                case "text":
                    TextSetting ts = (TextSetting) item;
                    uItem = UItem.asButton(0, ts.text);
                    uItem.settingItem = ts;
                    uItem.iconResId = iconRes;
                    uItem.accent = ts.accent;
                    uItem.red = ts.red;
                    break;
                case "switch":
                    SwitchSetting sws = (SwitchSetting) item;
                    if (sws.key != null && sws.text != null) {
                        boolean val = PluginsController.getInstance().getPluginSettingBoolean(this.plugin.getId(), sws.key, sws.defaultValue);
                        if (sws.subtext != null) {
                            uItem = UItem.asButtonCheck(0, sws.text, sws.subtext);
                        } else {
                            uItem = UItem.asCheck(0, sws.text);
                        }
                        if (iconRes != 0) {
                            uItem.iconResId = iconRes;
                        }
                        uItem.setChecked(val);
                        uItem.object2 = sws.key;
                        uItem.settingItem = sws;
                    }
                    break;
                case "header":
                    HeaderSetting hs = (HeaderSetting) item;
                    if (hs.text != null) {
                        uItem = UItem.asHeader(hs.text);
                        uItem.settingItem = hs;
                    }
                    break;
                case "edit_text":
                    EditTextSetting ets = (EditTextSetting) item;
                    if (ets.key != null && ets.hint != null) {
                        uItem = PluginEditTextCell.Factory.as(this.plugin, ets);
                    }
                    break;
                case "custom":
                    app.nimarkogram.messenger.plugins.models.CustomSetting custs =
                            (app.nimarkogram.messenger.plugins.models.CustomSetting) item;
                    com.chaquo.python.PyObject cvCb = custs.getCreateViewCallback();
                    if (cvCb != null) {
                        try {
                            View[] cachedCustom = customViewCache.get(custs);
                            if (cachedCustom != null && cachedCustom[0] != null) {
                                
                                com.chaquo.python.PyObject bvCbR = custs.getBindViewCallback();
                                if (bvCbR != null && cachedCustom[1] != null) {
                                    try {
                                        runPluginCallback(
                                                bvCbR, cachedCustom[1]);
                                    } catch (Throwable ignored) {
                                    }
                                }
                                View cachedHost = cachedCustom[0];
                                View cachedPluginView = cachedCustom[1];
                                if (cachedPluginView == null
                                        || cachedPluginView.getParent()
                                                != cachedHost) {
                                    
                                    customViewCache.remove(custs);
                                    FileLog.e(
                                            "nimarko: plugin custom setting "
                                                    + "re-parented its cached view");
                                    break;
                                }
                                uItem = UItem.asCustom(cachedCustom[0]);
                                uItem.settingItem = custs;
                                break;
                            }
                            
                            Context vctx = getParentActivity();
                            if (vctx == null || !isRuntimeCurrentForUi()) {
                                break;
                            }
                            View v = callPluginCallback(
                                    cvCb,
                                    result -> result != null
                                            ? result.toJava(View.class)
                                            : null,
                                    null,
                                    vctx);
                            if (v != null) {
                                if (v.getParent() != null) {
                                    FileLog.e(
                                            "nimarko: attached plugin custom "
                                                    + "setting view rejected");
                                    break;
                                }
                                com.chaquo.python.PyObject bvCb = custs.getBindViewCallback();
                                if (bvCb != null) {
                                    try {
                                        runPluginCallback(bvCb, v);
                                    } catch (Throwable ignored) {
                                    }
                                }
                                if (v.getParent() != null) {
                                    FileLog.e(
                                            "nimarko: bind_view attached a "
                                                    + "plugin custom setting "
                                                    + "view; row rejected");
                                    break;
                                }
                                
                                final View pluginView = v;
                                View hosted = null;
                                android.widget.FrameLayout host = null;
                                try {
                                    host = new android.widget.FrameLayout(vctx) {
                                        @Override
                                        protected boolean drawChild(android.graphics.Canvas c, View child, long dt) {
                                            try {
                                                return super.drawChild(c, child, dt);
                                            } catch (VirtualMachineError
                                                    | ThreadDeath
                                                    | LinkageError fatal) {
                                                throw fatal;
                                            } catch (Throwable ignored) {
                                                return false;
                                            }
                                        }
                                    };
                                    android.view.ViewGroup.LayoutParams orig = pluginView.getLayoutParams();
                                    host.addView(pluginView, new android.widget.FrameLayout.LayoutParams(-1, orig != null ? orig.height : -2));
                                    hosted = host;
                                } catch (VirtualMachineError
                                        | ThreadDeath
                                        | LinkageError fatal) {
                                    throw fatal;
                                } catch (Throwable failure) {
                                    if (host != null
                                            && pluginView.getParent() == host) {
                                        host.removeView(pluginView);
                                    }
                                    FileLog.e(
                                            "nimarko: unable to host plugin "
                                                    + "custom setting view",
                                            failure);
                                }
                                if (hosted == null) {
                                    break;
                                }
                                customViewCache.put(custs, new View[]{hosted, v});
                                uItem = UItem.asCustom(hosted);
                                uItem.settingItem = custs;
                            }
                        } catch (Throwable t) {
                            org.telegram.messenger.FileLog.e("nimarko: custom setting view failed", t);
                        }
                    }
                    break;
            }

            if (uItem != null) {
                uItem.id = getStableId(item);
                if (uItem.settingItem != null && !TextUtils.isEmpty(uItem.settingItem.linkAlias) && !TextUtils.isEmpty(this.targetSettingName)) {
                    if (uItem.settingItem.linkAlias.equals(this.targetSettingName)) {
                        this.targetSettingItemId = uItem.id;
                        this.targetSettingName = null;
                    }
                }
                items.add(uItem);
            }
        }
    }

    @Override
    public void onClick(final UItem uItem, View view, int i, float f, float f2) {
        if (uItem == null || this.plugin == null) return;

        SettingItem settingItem = uItem.settingItem;
        if (settingItem instanceof TextSetting) {
            final TextSetting textSetting = (TextSetting) settingItem;
            if (textSetting.createSubFragmentCallback != null) {
                Utilities.pluginsQueue.postRunnable(() -> {
                    try {
                        List<SettingItem> items = callSettingsCallback(
                                textSetting.createSubFragmentCallback);
                        if (items == null) {
                            return;
                        }
                        AndroidUtilities.runOnUIThread(() -> {
                            if (isRuntimeCurrentForUi() && !items.isEmpty()
                                    && fragmentView != null && getParentActivity() != null) {
                                String prefix = (this.settingsLinkPrefix == null ? "" : this.settingsLinkPrefix + ":") + uItem.settingItem.linkAlias;
                                PluginSettingsActivity sub = new PluginSettingsActivity(this.plugin, uItem.text.toString(), items, textSetting.createSubFragmentCallback);
                                presentFragment(sub.setSettingsLinkPrefix(prefix));
                            }
                        });
                    } catch (Throwable t) {
                        
                        FileLog.e("nimarko: plugin create_sub_fragment failed", t);
                    }
                });
                return;
            }
            if (textSetting.onClickCallback != null) {
                try {
                    runPluginCallback(textSetting.onClickCallback, view);
                } catch (Exception e) {
                    FileLog.e("nimarko: TextSetting.onClickCallback threw", e);
                }
                return;
            }
        }

        if (uItem.object2 instanceof String) {
            String key = (String) uItem.object2;
            if (view instanceof TextCheckCell) {
                boolean newState = !((TextCheckCell) view).isChecked();
                ((TextCheckCell) view).setChecked(newState);
                uItem.setChecked(newState);
                Utilities.pluginsQueue.postRunnable(() -> {
                    PluginsController.getInstance().setPluginSetting(this.plugin.getId(), key, newState);
                    if (settingItem instanceof SwitchSetting) {
                        triggerOnChange(((SwitchSetting) settingItem).onChangeCallback, key, newState);
                    }
                });
            } else if (view instanceof NotificationsCheckCell) {
                boolean newState = !((NotificationsCheckCell) view).isChecked();
                ((NotificationsCheckCell) view).setChecked(newState);
                uItem.setChecked(newState);
                Utilities.pluginsQueue.postRunnable(() -> {
                    PluginsController.getInstance().setPluginSetting(this.plugin.getId(), key, newState);
                    if (settingItem instanceof SwitchSetting) {
                        triggerOnChange(((SwitchSetting) settingItem).onChangeCallback, key, newState);
                    }
                });
            } else if (view instanceof TextCell) {
                if (settingItem instanceof SelectorSetting) {
                    showSelectorDialog(uItem, view, key);
                } else if (settingItem instanceof InputSetting) {
                    showStringInputDialog(uItem, view, key);
                }
            }
        }
    }

    private boolean isRuntimeCurrentForUi() {
        if (plugin == null || fragmentView == null) return false;
        PluginsController controller = PluginsController.getInstance();
        return runtimeToken != null
                && controller.getPluginRuntimeTaskDecision(runtimeToken)
                        == PluginsController.RUNTIME_TASK_RUN;
    }

    @Override
    public boolean onLongClick(final UItem uItem, View view, int i, float f, float f2) {
        if (uItem != null && this.plugin != null && uItem.settingItem != null) {
            String alias = uItem.settingItem.linkAlias;
            if (!TextUtils.isEmpty(alias)) {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, 1);
                ItemOptions.makeOptions(this, view).add(R.drawable.msg_copy, LocaleController.getString(R.string.CopyLink), () -> {
                    String link = uItem.settingItem.getLink(this.plugin.getId(), this.settingsLinkPrefix);
                    if (AndroidUtilities.addToClipboard(link)) {
                        BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.LinkCopied)).show();
                    }
                }).show();
                return true;
            }
            if (uItem.settingItem.onLongClickCallback != null) {
                try {
                    runPluginCallback(
                            uItem.settingItem.onLongClickCallback, view);
                } catch (Exception ignored) {}
                return true;
            }
        }
        return false;
    }

    private void showStringInputDialog(UItem uItem, final View view, final String key) {
        if (getParentActivity() == null) return;
        
        InputSetting setting = (InputSetting) uItem.settingItem;
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext(), getResourceProvider());
        builder.setTitle(uItem.text);
        
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        
        if (setting.subtext != null) {
            TextView textView = new TextView(getContext());
            textView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
            textView.setTextSize(1, 16.0f);
            textView.setText(setting.subtext);
            layout.addView(textView, LayoutHelper.createLinear(-1, -2, 24.0f, 5.0f, 24.0f, 12.0f));
        }
        
        EditTextBoldCursor editText = new EditTextBoldCursor(getContext());
        editText.lineYFix = true;
        editText.setTextSize(1, 18.0f);
        editText.setText(PluginsController.getInstance().getPluginSettingString(this.plugin.getId(), key, setting.defaultValue));
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
        editText.setHintColor(Theme.getColor(Theme.key_groupcreate_hintText, getResourceProvider()));
        editText.setHintText(LocaleController.getString(R.string.EnterValue));
        editText.setFocusable(true);
        editText.setInputType(147457);
        int activated = Theme.key_windowBackgroundWhiteInputFieldActivated;
        editText.setCursorColor(Theme.getColor(activated, getResourceProvider()));
        editText.setLineColors(
                Theme.getColor(Theme.key_windowBackgroundWhiteInputField, getResourceProvider()),
                Theme.getColor(activated, getResourceProvider()),
                Theme.getColor(Theme.key_text_RedRegular, getResourceProvider()));
        editText.setBackgroundDrawable(null);
        editText.setPadding(0, AndroidUtilities.dp(6.0f), 0, AndroidUtilities.dp(6.0f));
        layout.addView(editText, LayoutHelper.createLinear(-1, -2, 24.0f, 0.0f, 24.0f, 10.0f));
        
        builder.setView(layout);
        builder.setPositiveButton(LocaleController.getString(R.string.Done), (dialog, which) -> {
            String val = editText.getText().toString();
            ((TextCell) view).setValue(val, true);
            Utilities.pluginsQueue.postRunnable(() -> {
                PluginsController.getInstance().setPluginSetting(this.plugin.getId(), key, val);
                triggerOnChange(setting.onChangeCallback, key, val);
            });
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            editText.requestFocus();
            editText.setSelection(editText.length());
            AndroidUtilities.showKeyboard(editText);
        });
        showDialog(dialog);
    }

    private void showSelectorDialog(UItem uItem, final View view, final String key) {
        if (getParentActivity() == null) return;
        
        SelectorSetting setting = (SelectorSetting) uItem.settingItem;
        AtomicReference<AlertDialog> dialogRef = new AtomicReference<>();
        
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        
        for (int i = 0; i < setting.items.length; i++) {
            final int index = i;
            RadioColorCell cell = new RadioColorCell(getParentActivity());
            cell.setPadding(AndroidUtilities.dp(4.0f), 0, AndroidUtilities.dp(4.0f), 0);
            cell.setCheckColor(Theme.getColor(Theme.key_radioBackground), Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
            cell.setTextAndValue(setting.items[i], PluginsController.getInstance().getPluginSettingInt(this.plugin.getId(), key, setting.defaultValue) == i);
            cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
            layout.addView(cell);
            cell.setOnClickListener(v -> {
                if (dialogRef.get() != null) dialogRef.get().dismiss();
                ((TextCell) view).setValue(setting.items[index], true);
                Utilities.pluginsQueue.postRunnable(() -> {
                    PluginsController.getInstance().setPluginSetting(this.plugin.getId(), key, index);
                    triggerOnChange(setting.onChangeCallback, key, index);
                });
            });
        }
        
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity()).setTitle(uItem.text).setView(layout).setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        dialogRef.set(builder.create());
        showDialog(dialogRef.get());
    }

    private int getStableId(SettingItem settingItem) {
        if (settingItem instanceof SwitchSetting) return Objects.hash(PluginsConstants.Settings.TYPE_SWITCH, ((SwitchSetting) settingItem).key);
        if (settingItem instanceof InputSetting) return Objects.hash(PluginsConstants.Settings.TYPE_INPUT, ((InputSetting) settingItem).key);
        if (settingItem instanceof EditTextSetting) return Objects.hash("edit", ((EditTextSetting) settingItem).key);
        if (settingItem instanceof SelectorSetting) return Objects.hash(PluginsConstants.Settings.TYPE_SELECTOR, ((SelectorSetting) settingItem).key);
        if (settingItem instanceof HeaderSetting) return Objects.hash(PluginsConstants.Settings.TYPE_HEADER, ((HeaderSetting) settingItem).text);
        if (settingItem instanceof DividerSetting) return Objects.hash(PluginsConstants.Settings.TYPE_DIVIDER, ((DividerSetting) settingItem).text);
        if (settingItem instanceof TextSetting) return Objects.hash("text", ((TextSetting) settingItem).text);
        return settingItem.hashCode();
    }

    private void triggerOnChange(final PyObject pyObject, final String str, final Object obj) {
        if (pyObject != null) {
            try {
                runPluginCallback(pyObject, obj);
            } catch (Exception e) {
                FileLog.e("Error executing on_change callback for " + this.plugin.getId() + "/" + str, e);
                
                AndroidUtilities.runOnUIThread(() -> {
                    org.telegram.ui.ActionBar.BaseFragment last = org.telegram.ui.LaunchActivity.getSafeLastFragment();
                    if (last != null) {
                        BulletinFactory.of(last).createErrorBulletin(LocaleController.getString(R.string.PluginCallbackError)).show();
                    }
                });
            }
        }
    }
}
