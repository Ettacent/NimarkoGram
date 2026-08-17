package app.nimarkogram.messenger.plugins.ui.components;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;

import com.chaquo.python.PyObject;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import app.nimarkogram.messenger.plugins.Plugin;
import app.nimarkogram.messenger.plugins.PluginsController;
import app.nimarkogram.messenger.plugins.models.EditTextSetting;
import app.nimarkogram.messenger.plugins.models.SettingItem;
import app.nimarkogram.messenger.plugins.ui.PluginUiRegistry;

@SuppressLint({"ViewConstructor"})
public class PluginEditTextCell extends EditTextCell
        implements PluginUiRegistry.RuntimeOwnedUi {
    private static final int SAVE_DEBOUNCE_MS = 750;
    private EditTextSetting currentSetting;
    private PluginsController.PluginRuntimeToken ownedRuntimeToken;
    private Runnable pendingSaveRunnable;
    private String pluginId;
    private final TextWatcher saveTextWatcher;
    private String valueToSave;

    public PluginEditTextCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, null, false, false, -1, resourcesProvider);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));
        TextWatcher textWatcher = new TextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
            }

            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i2, int i3) {
            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i2, int i3) {
                PluginEditTextCell.this.valueToSave = charSequence.toString();
                PluginEditTextCell.this.scheduleSave();
            }
        };
        this.saveTextWatcher = textWatcher;
        this.editText.addTextChangedListener(textWatcher);
    }

    private void scheduleSave() {
        Runnable runnable = this.pendingSaveRunnable;
        if (runnable != null) {
            AndroidUtilities.cancelRunOnUIThread(runnable);
        }
        final EditTextSetting editTextSetting = this.currentSetting;
        final String key = editTextSetting != null ? editTextSetting.key : null;
        final String value = this.valueToSave;
        final String pId = this.pluginId;
        
        if (key == null || pId == null) {
            return;
        }
        
        Runnable runnable2 = () -> onSaveDebounce(editTextSetting, value, pId, key);
        this.pendingSaveRunnable = runnable2;
        AndroidUtilities.runOnUIThread(runnable2, SAVE_DEBOUNCE_MS);
    }

    private void onSaveDebounce(final EditTextSetting editTextSetting, final String value, final String pId, final String key) {
        this.pendingSaveRunnable = null;
        Utilities.pluginsQueue.postRunnable(() -> performSave(editTextSetting, value, pId, key));
    }

    private void performSave(EditTextSetting editTextSetting, String value, String pId, String key) {
        PluginsController controller = PluginsController.getInstance();
        PluginsController.PluginRuntimeToken token = editTextSetting.runtimeToken;
        
        boolean entered = token != null
                && controller.getPluginRuntimeTaskDecision(token)
                        == PluginsController.RUNTIME_TASK_RUN
                && controller.enterPluginRuntime(token);
        if (!entered) {
            return;
        }
        controller.getWatchdog().onPluginExecutionStarted(pId);
        try {
            if (editTextSetting.maxLength > 0 && value.length() > editTextSetting.maxLength) {
                controller.setPluginSetting(pId, key,
                        value.substring(0, editTextSetting.maxLength));
            } else {
                controller.setPluginSetting(pId, key, value);
            }
            PyObject callback = editTextSetting.onChangeCallback;
            if (callback != null) {
                callback.call(value);
            }
        } catch (Throwable failure) {
            controller.getWatchdog().onPluginExecutionFailed(
                    pId, failure);
            FileLog.e("Error executing on_change callback for " + pId + "/" + key,
                    failure);
            if (failure instanceof VirtualMachineError) {
                throw (VirtualMachineError) failure;
            }
            if (failure instanceof ThreadDeath) {
                throw (ThreadDeath) failure;
            }
            if (failure instanceof LinkageError) {
                throw (LinkageError) failure;
            }
        } finally {
            controller.getWatchdog().onPluginExecutionFinished(pId);
            if (token != null) {
                controller.exitPluginRuntime(token);
            }
        }
    }

    @Override
    protected void onFocusChanged(boolean z) {
        super.onFocusChanged(z);
        if (z) {
            return;
        }
        flushPendingSave();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        flushPendingSave();
    }

    private void flushPendingSave() {
        Runnable runnable = this.pendingSaveRunnable;
        if (runnable != null) {
            AndroidUtilities.cancelRunOnUIThread(runnable);
            this.pendingSaveRunnable.run();
            this.pendingSaveRunnable = null;
        }
    }

    private void dropPendingSave() {
        Runnable runnable = pendingSaveRunnable;
        pendingSaveRunnable = null;
        if (runnable != null) {
            AndroidUtilities.cancelRunOnUIThread(runnable);
        }
    }

    @Override
    public void clearPluginUiReferences(
            PluginsController.PluginRuntimeToken runtimeToken) {
        if (ownedRuntimeToken == null
                || !ownedRuntimeToken.equals(runtimeToken)) {
            return;
        }
        dropPendingSave();
        currentSetting = null;
        pluginId = null;
        valueToSave = null;
        ownedRuntimeToken = null;
    }

    public void bind(String pId, EditTextSetting editTextSetting) {
        PluginsController.PluginRuntimeToken newToken =
                editTextSetting != null ? editTextSetting.runtimeToken : null;
        if (!Objects.equals(ownedRuntimeToken, newToken)) {
            PluginsController.PluginRuntimeToken oldToken =
                    ownedRuntimeToken;
            if (oldToken != null) {
                PluginUiRegistry.unregisterRuntimeOwnedUi(
                        oldToken, this);
            }
            dropPendingSave();
            currentSetting = null;
            pluginId = null;
            ownedRuntimeToken = newToken;
            if (newToken == null
                    || !PluginUiRegistry.registerRuntimeOwnedUi(
                            newToken, this)) {
                clearPluginUiReferences(newToken);
                return;
            }
        }
        EditTextSetting oldSetting = this.currentSetting;
        boolean sameSetting = oldSetting != null && TextUtils.equals(oldSetting.key, editTextSetting.key);
        if (!sameSetting) {
            flushPendingSave();
        }
        this.pluginId = pId;
        this.currentSetting = editTextSetting;
        setWatchersEnabled(false);
        setMultiline(editTextSetting.multiline);
        int inputType = (editTextSetting.multiline ? 131072 : 0) | 573441;
        if (this.editText.getInputType() != inputType) {
            this.editText.setInputType(inputType);
        }
        int maxLen = editTextSetting.maxLength;
        if (maxLen > 0) {
            setShowLimitWhenNear(maxLen / Math.min(maxLen, 4));
        } else {
            setShowLimitWhenNear(-1);
        }
        ArrayList<InputFilter> arrayList = new ArrayList<>();
        InputFilter inputFilterCreateInputFilter = createInputFilter(editTextSetting.mask);
        if (inputFilterCreateInputFilter != null) {
            arrayList.add(inputFilterCreateInputFilter);
        }
        if (editTextSetting.maxLength > 0) {
            arrayList.add(new InputFilter.LengthFilter(editTextSetting.maxLength));
        }
        if (!arrayList.isEmpty()) {
            this.editText.setFilters(arrayList.toArray(new InputFilter[0]));
        } else {
            this.editText.setFilters(new InputFilter[0]);
        }
        
        String pluginSettingString = PluginsController.getInstance().getPluginSettingString(pId, editTextSetting.key, editTextSetting.defaultValue);
        if (!TextUtils.equals(pluginSettingString, this.editText.getText().toString())) {
            if (this.editText.hasFocus() && sameSetting) {
                setWatchersEnabled(true);
                return;
            }
            setText(pluginSettingString);
        }
        this.valueToSave = pluginSettingString;
        String hint = editTextSetting.hint;
        if (hint != null) {
            this.editText.setHint(hint);
        }
        setWatchersEnabled(true);
    }

    private void setWatchersEnabled(boolean z) {
        if (z) {
            this.editText.removeTextChangedListener(this.saveTextWatcher);
            this.editText.addTextChangedListener(this.saveTextWatcher);
        } else {
            this.editText.removeTextChangedListener(this.saveTextWatcher);
        }
    }

    public void setMultiline(boolean z) {
        if (z) {
            this.editText.setMaxLines(5);
            this.editText.setSingleLine(false);
        } else {
            this.editText.setMaxLines(1);
            this.editText.setSingleLine(true);
        }
    }

    public static class Factory extends UItem.UItemFactory {
        static {
            UItem.UItemFactory.setup(new Factory());
        }

        @Override
        public boolean isClickable() {
            return false;
        }

        @Override
        public PluginEditTextCell createView(Context context, org.telegram.ui.Components.RecyclerListView listView, int i, int i2, Theme.ResourcesProvider resourcesProvider) {
            return new PluginEditTextCell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem uItem, boolean z, UniversalAdapter universalAdapter, UniversalRecyclerView universalRecyclerView) {
            if (view instanceof PluginEditTextCell) {
                PluginEditTextCell pluginEditTextCell = (PluginEditTextCell) view;
                SettingItem settingItem = uItem.settingItem;
                if (settingItem instanceof EditTextSetting) {
                    EditTextSetting editTextSetting = (EditTextSetting) settingItem;
                    Plugin plugin = uItem.plugin;
                    if (plugin != null) {
                        pluginEditTextCell.bind(plugin.getId(), editTextSetting);
                        pluginEditTextCell.setDivider(z);
                    }
                }
            }
        }

        public static UItem as(Plugin plugin, EditTextSetting editTextSetting) {
            UItem uItemOfFactory = UItem.ofFactory(Factory.class);
            uItemOfFactory.plugin = plugin;
            uItemOfFactory.settingItem = editTextSetting;
            return uItemOfFactory;
        }
    }

    private InputFilter createInputFilter(String mask) {
        if (TextUtils.isEmpty(mask)) {
            return null;
        }
        try {
            final Pattern pattern = Pattern.compile(mask);
            return (charSequence, i, i2, spanned, i3, i4) -> checkPattern(pattern, charSequence, i, i2);
        } catch (PatternSyntaxException e) {
            FileLog.e("Invalid mask for EditText: " + mask, e);
            return null;
        }
    }

    private static CharSequence checkPattern(Pattern pattern, CharSequence charSequence, int i, int i2) {
        StringBuilder sb = new StringBuilder(i2 - i);
        boolean mismatch = true;
        while (i < i2) {
            char cCharAt = charSequence.charAt(i);
            if (pattern.matcher(String.valueOf(cCharAt)).matches()) {
                sb.append(cCharAt);
            } else {
                mismatch = false;
            }
            i++;
        }
        if (mismatch) {
            return null;
        }
        return sb.toString();
    }
}
