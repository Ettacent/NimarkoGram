package app.nimarkogram.messenger.plugins.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.net.Uri;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.plugins.Plugin;
import app.nimarkogram.messenger.plugins.PluginsController;
import app.nimarkogram.messenger.plugins.ui.components.EmptyPluginsView;
import app.nimarkogram.messenger.plugins.ui.components.PluginCell;
import app.nimarkogram.messenger.plugins.ui.components.PluginCellDelegate;
import app.nimarkogram.messenger.plugins.utils.PluginsWatchdog;
import app.nimarkogram.messenger.preferences.BasePreferencesActivity;
import app.nimarkogram.messenger.utils.text.LocaleUtils;

public class PluginsActivity extends BasePreferencesActivity implements NotificationCenter.NotificationCenterDelegate {
    private static final int PLUGIN_SETTINGS = 3;
    
    private static final int REQ_PICK_PLUGIN = 4711;
    private static final long MAX_PICKER_PLUGIN_BYTES = 4L * 1024L * 1024L;
    private static final String PICKER_IMPORT_DIRECTORY = "imported_plugins";
    private static final Pattern PICKER_IMPORT_FILE_PATTERN = Pattern.compile(
            "^\\.plugin-picker-[0-9a-f]{32}\\.plugin$");
    private static final Object PICKER_IMPORT_LOCK = new Object();
    private static final HashMap<String, PickerImportState> ACTIVE_PICKER_IMPORTS =
            new HashMap<>();

    private enum PickerImportState {
        READY,
        CLAIMED
    }

    private boolean isSwitchingEngineState = false;
    private final HashMap<String, Long> pluginUiOperationEpochs = new HashMap<>();
    private volatile long lifecycleEpoch;
    private volatile long pickerIoOperationEpoch;
    private volatile long sweepIoOperationEpoch;
    private long nextUiOperationEpoch;
    private volatile boolean uiLifecycleActive;
    private String query;
    private ActionBarMenuItem searchItem;
    private ActionBarMenuItem engineSettingsItem;
    private boolean searching;
    private EmptyPluginsView emptyView;
    private FrameLayout addPluginButton;
    private int bottomInset;

    @Override
    public View createView(Context context) {
        View viewCreateView = super.createView(context);
        ActionBarMenuItem actionBarMenuItemSearchListener = this.actionBar.menu.addItem(0, R.drawable.outline_header_search).setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override
            public void onSearchExpand() {
                PluginsActivity.this.searching = true;
                PluginsActivity.this.listView.adapter.update(true);
                PluginsActivity.this.listView.scrollToPosition(0);
                if (PluginsActivity.this.engineSettingsItem != null) {
                    PluginsActivity.this.engineSettingsItem.setVisibility(View.GONE);
                }
                updateAddButtonVisibility(true);
            }

            @Override
            public void onSearchCollapse() {
                PluginsActivity.this.searching = false;
                PluginsActivity.this.query = null;
                PluginsActivity.this.listView.adapter.update(true);
                PluginsActivity.this.listView.scrollToPosition(0);
                if (PluginsActivity.this.engineSettingsItem != null) {
                    PluginsActivity.this.engineSettingsItem.setVisibility(View.VISIBLE);
                }
                updateAddButtonVisibility(true);
            }

            @Override
            public void onTextChanged(EditText editText) {
                PluginsActivity.this.query = editText.getText().toString();
                PluginsActivity.this.listView.adapter.update(true);
                PluginsActivity.this.listView.scrollToPosition(0);
            }
        });
        this.searchItem = actionBarMenuItemSearchListener;
        actionBarMenuItemSearchListener.setSearchFieldHint(LocaleController.getString(R.string.Search));
        actionBarMenuItemSearchListener.setContentDescription(LocaleController.getString(R.string.Search));
        AndroidUtilities.updateViewVisibilityAnimated(this.searchItem, NimarkoConfig.pluginsEngine && !PluginsController.getInstance().plugins.isEmpty(), 0.5f, false);

        this.engineSettingsItem = this.actionBar.menu.addItem(
                PLUGIN_SETTINGS, R.drawable.msg_settings);
        this.engineSettingsItem.setContentDescription(LocaleController.getString(R.string.Settings));
        this.engineSettingsItem.setOnClickListener(view -> presentFragment(new PluginsInfoActivity()));
        
        this.listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int i) {
                if (i == 1) {
                    
                    android.app.Activity parent = PluginsActivity.this.getParentActivity();
                    if (parent != null) {
                        AndroidUtilities.hideKeyboard(parent.getCurrentFocus());
                    }
                }
            }
        });

        if (viewCreateView instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) viewCreateView;
            FrameLayout fab = new FrameLayout(context);
            this.addPluginButton = fab;
            fab.setBackground(Theme.createSimpleSelectorCircleDrawable(
                    AndroidUtilities.dp(56),
                    Theme.getColor(Theme.key_chats_actionBackground),
                    Theme.getColor(Theme.key_chats_actionPressedBackground)));
            AppCompatImageView fabIcon = new AppCompatImageView(context);
            fabIcon.setScaleType(ImageView.ScaleType.CENTER);
            fabIcon.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.msg_filled_plus).mutate());
            fabIcon.setColorFilter(new PorterDuffColorFilter(
                    Theme.getColor(Theme.key_chats_actionIcon), PorterDuff.Mode.MULTIPLY));
            fab.addView(fabIcon, LayoutHelper.createFrame(24, 24, Gravity.CENTER));
            fab.setOnClickListener(v -> onFabClicked());
            fab.setContentDescription(LocaleController.getString(R.string.NM_AddPlugin));
            fab.setElevation(AndroidUtilities.dp(6));
            FrameLayout.LayoutParams fabLp = LayoutHelper.createFrame(56, 56,
                    Gravity.BOTTOM | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT),
                    LocaleController.isRTL ? 16 : 0, 0,
                    LocaleController.isRTL ? 0 : 16, 16);
            if (bottomInset > 0) {
                fabLp.bottomMargin = bottomInset + AndroidUtilities.dp(16);
            }
            parent.addView(fab, fabLp);
            
            updateAddButtonVisibility(false);
        }

        this.fragmentView = viewCreateView;
        return viewCreateView;
    }

    private void updateAddButtonVisibility(boolean animated) {
        if (addPluginButton == null) return;
        boolean visible = NimarkoConfig.pluginsEngine && !searching;
        AndroidUtilities.updateViewVisibilityAnimated(addPluginButton, visible, 0.5f, animated);
    }

    private long beginPluginUiOperation(String pluginId) {
        long operationEpoch = ++nextUiOperationEpoch;
        pluginUiOperationEpochs.put(pluginId, operationEpoch);
        return operationEpoch;
    }

    private long getCurrentPluginUiOperationEpoch(String pluginId) {
        Long operationEpoch = pluginUiOperationEpochs.get(pluginId);
        return uiLifecycleActive && operationEpoch != null
                ? operationEpoch
                : PluginCell.NO_UI_OPERATION_EPOCH;
    }

    private boolean isPluginToggleLoading(String pluginId) {
        if (!uiLifecycleActive || TextUtils.isEmpty(pluginId)) {
            return false;
        }
        PluginsController controller = PluginsController.getInstance();
        return getCurrentPluginUiOperationEpoch(pluginId)
                        != PluginCell.NO_UI_OPERATION_EPOCH
                || controller.isTogglingInProgress(pluginId)
                || controller.isEnablingInProgress(pluginId);
    }

    private boolean isUiLifecycleCurrent(long expectedLifecycleEpoch) {
        return uiLifecycleActive
                && lifecycleEpoch == expectedLifecycleEpoch
                && PluginUiRegistry.isFragmentUiActive(this);
    }

    private boolean isPluginUiOperationEpochCurrent(
            long expectedLifecycleEpoch,
            String pluginId,
            long expectedOperationEpoch) {
        Long currentOperationEpoch = pluginUiOperationEpochs.get(pluginId);
        return currentOperationEpoch != null
                && currentOperationEpoch.longValue() == expectedOperationEpoch
                && lifecycleEpoch == expectedLifecycleEpoch;
    }

    private void finishPluginUiOperation(
            String pluginId, long expectedOperationEpoch) {
        Long currentOperationEpoch = pluginUiOperationEpochs.get(pluginId);
        if (currentOperationEpoch != null
                && currentOperationEpoch.longValue() == expectedOperationEpoch) {
            pluginUiOperationEpochs.remove(pluginId);
        }
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        bottomInset = bottom;
        if (listView != null) {
            
            listView.setPadding(0, 0, 0, bottom + AndroidUtilities.dp(80));
            listView.setClipToPadding(false);
        }
        if (addPluginButton != null && addPluginButton.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) addPluginButton.getLayoutParams();
            int wanted = bottom + AndroidUtilities.dp(16);
            if (lp.bottomMargin != wanted) {
                lp.bottomMargin = wanted;
                addPluginButton.setLayoutParams(lp);
            }
        }
    }

    private void onFabClicked() {
        if (!NimarkoConfig.pluginsEngine) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info,
                    LocaleController.getString(R.string.EnablePluginsEngine)).show();
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(intent,
                    LocaleController.getString(R.string.NM_AddPlugin)), REQ_PICK_PLUGIN);
        } catch (Throwable t) {
            org.telegram.messenger.FileLog.e("nimarko: failed to launch plugin picker", t);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_PLUGIN || resultCode != android.app.Activity.RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }

        final long callbackLifecycleEpoch = lifecycleEpoch;
        if (!isUiLifecycleCurrent(callbackLifecycleEpoch)) {
            return;
        }
        final long operationEpoch = ++pickerIoOperationEpoch;
        boolean accepted = PluginUiDiskExecutor.execute(
                "copy picker plugin",
                () -> {
                    
                    if (!isPickerIoOperationCurrent(
                            callbackLifecycleEpoch, operationEpoch)) {
                        return;
                    }
                    final String path = copyUriToCache(uri);
                    if (!isPickerIoOperationCurrent(
                            callbackLifecycleEpoch, operationEpoch)) {
                        releaseUnclaimedPickerImportSource(path);
                        return;
                    }
                    AndroidUtilities.runOnUIThread(() ->
                            publishPickerImportResult(
                                    path,
                                    callbackLifecycleEpoch,
                                    operationEpoch));
                });
        if (!accepted) {
            AndroidUtilities.runOnUIThread(() -> {
                if (isPickerUiOperationCurrent(
                        callbackLifecycleEpoch, operationEpoch)) {
                    BulletinFactory.of(this).createSimpleBulletin(
                            R.raw.error,
                            LocaleController.getString(
                                    R.string.NM_AddPluginFailed)).show();
                }
            });
        }
    }

    private void publishPickerImportResult(
            String path,
            long callbackLifecycleEpoch,
            long operationEpoch) {
        if (!isPickerUiOperationCurrent(
                callbackLifecycleEpoch, operationEpoch)) {
            releaseUnclaimedPickerImportSource(path);
            return;
        }
        if (TextUtils.isEmpty(path)) {
            BulletinFactory.of(this).createSimpleBulletin(
                    R.raw.error,
                    LocaleController.getString(
                            R.string.NM_AddPluginFailed)).show();
            return;
        }
        try {
            PluginsController.getInstance().showInstallDialog(
                    this, path, false);
        } catch (Throwable failure) {
            FileLog.e(
                    "nimarko: failed to present picked plugin",
                    failure);
            if (isPickerUiOperationCurrent(
                    callbackLifecycleEpoch, operationEpoch)) {
                BulletinFactory.of(this).createSimpleBulletin(
                        R.raw.error,
                        LocaleController.getString(R.string.NM_AddPluginFailed)).show();
            }
        } finally {
            
            releaseUnclaimedPickerImportSource(path);
        }
    }

    private boolean isPickerIoOperationCurrent(
            long expectedLifecycleEpoch,
            long expectedOperationEpoch) {
        return uiLifecycleActive
                && lifecycleEpoch == expectedLifecycleEpoch
                && pickerIoOperationEpoch == expectedOperationEpoch;
    }

    private boolean isPickerUiOperationCurrent(
            long expectedLifecycleEpoch,
            long expectedOperationEpoch) {
        return isPickerIoOperationCurrent(
                expectedLifecycleEpoch, expectedOperationEpoch)
                && isUiLifecycleCurrent(expectedLifecycleEpoch);
    }

    private boolean isSweepIoOperationCurrent(
            long expectedLifecycleEpoch,
            long expectedOperationEpoch) {
        return uiLifecycleActive
                && lifecycleEpoch == expectedLifecycleEpoch
                && sweepIoOperationEpoch == expectedOperationEpoch;
    }

    private String copyUriToCache(Uri uri) {
        File outDir = getPickerImportDirectory(true);
        if (outDir == null) {
            return null;
        }

        File out = null;
        FileDescriptor descriptor = null;
        boolean streamOwnsDescriptor = false;
        boolean completed = false;
        try {
            for (int attempt = 0; attempt < 128; attempt++) {
                out = new File(
                        outDir,
                        ".plugin-picker-"
                                + UUID.randomUUID().toString().replace("-", "")
                                + ".plugin");
                try {
                    descriptor = Os.open(
                            out.getAbsolutePath(),
                            OsConstants.O_WRONLY
                                    | OsConstants.O_CREAT
                                    | OsConstants.O_EXCL
                                    | OsConstants.O_CLOEXEC
                                    | OsConstants.O_NOFOLLOW,
                            OsConstants.S_IRUSR | OsConstants.S_IWUSR);
                    break;
                } catch (ErrnoException collision) {
                    if (collision.errno != OsConstants.EEXIST) {
                        throw collision;
                    }
                    out = null;
                }
            }
            if (out == null || descriptor == null) {
                throw new IOException(
                        "Could not allocate an exclusive plugin picker stage");
            }

            InputStream rawInput = ApplicationLoader.applicationContext
                    .getContentResolver().openInputStream(uri);
            if (rawInput == null) {
                throw new IOException("Picker returned an unreadable URI");
            }
            try (InputStream ownedInput = rawInput;
                 BufferedInputStream input =
                        new BufferedInputStream(ownedInput)) {
                FileOutputStream fileOutput =
                        new FileOutputStream(descriptor);
                streamOwnsDescriptor = true;
                try (FileOutputStream ownedOutput = fileOutput;
                     BufferedOutputStream output =
                            new BufferedOutputStream(ownedOutput)) {
                    byte[] buffer = new byte[16 * 1024];
                    long total = 0;
                    while (true) {
                        int count = input.read(buffer);
                        if (count < 0) {
                            break;
                        }
                        if (count == 0) {
                            continue;
                        }
                        if (count > MAX_PICKER_PLUGIN_BYTES - total) {
                            throw new IOException(
                                    "Picked plugin exceeds 4 MiB");
                        }
                        output.write(buffer, 0, count);
                        total += count;
                    }
                    if (total == 0) {
                        throw new IOException("Picked plugin is empty");
                    }
                    output.flush();
                    ownedOutput.getFD().sync();
                }
            }
            syncPickerDirectory(outDir);
            registerPickerImportSource(out);
            completed = true;
            return out.getAbsolutePath();
        } catch (Throwable failure) {
            FileLog.e(
                    "nimarko: failed to copy picked plugin URI to cache",
                    failure);
            return null;
        } finally {
            if (!streamOwnsDescriptor && descriptor != null) {
                try {
                    Os.close(descriptor);
                } catch (Throwable ignored) {
                }
            }
            if (!completed && out != null) {
                deleteManagedPickerImport(out);
            }
        }
    }

    private static File getPickerImportDirectory(boolean create) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null || context.getCacheDir() == null) {
            return null;
        }
        try {
            File cacheDir = context.getCacheDir().getCanonicalFile();
            File outDir = new File(cacheDir, PICKER_IMPORT_DIRECTORY);
            boolean created = false;
            if (create && !outDir.exists()) {
                if (!outDir.mkdirs()) {
                    return null;
                }
                created = true;
            }
            if (!outDir.exists()
                    || !outDir.isDirectory()
                    || !outDir.getCanonicalFile().equals(
                            outDir.getAbsoluteFile())) {
                return null;
            }
            android.system.StructStat stat =
                    Os.lstat(outDir.getAbsolutePath());
            if ((stat.st_mode & OsConstants.S_IFMT)
                    != OsConstants.S_IFDIR) {
                return null;
            }
            if (created) {
                syncPickerDirectory(cacheDir);
            }
            return outDir;
        } catch (Throwable failure) {
            FileLog.e("nimarko: picker import directory is unsafe", failure);
            return null;
        }
    }

    private static String normalizeManagedPickerImport(String path) {
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        Context context = ApplicationLoader.applicationContext;
        if (context == null || context.getCacheDir() == null) {
            return null;
        }
        try {
            File directory = new File(
                    context.getCacheDir(),
                    PICKER_IMPORT_DIRECTORY).getAbsoluteFile();
            File file = new File(path).getAbsoluteFile();
            File parent = file.getParentFile();
            if (parent == null
                    || !parent.equals(directory)
                    || !PICKER_IMPORT_FILE_PATTERN.matcher(
                            file.getName()).matches()) {
                return null;
            }
            return new File(directory, file.getName()).getAbsolutePath();
        } catch (Throwable failure) {
            return null;
        }
    }

    private static void registerPickerImportSource(File file)
            throws IOException {
        String path = normalizeManagedPickerImport(
                file != null ? file.getAbsolutePath() : null);
        if (path == null) {
            throw new IOException("Picker stage escaped its managed directory");
        }
        synchronized (PICKER_IMPORT_LOCK) {
            ACTIVE_PICKER_IMPORTS.put(path, PickerImportState.READY);
        }
    }

    public static boolean claimPickerImportSource(String path) {
        String normalized = normalizeManagedPickerImport(path);
        if (normalized == null) {
            return false;
        }
        synchronized (PICKER_IMPORT_LOCK) {
            if (ACTIVE_PICKER_IMPORTS.get(normalized)
                    != PickerImportState.READY) {
                return false;
            }
            ACTIVE_PICKER_IMPORTS.put(
                    normalized, PickerImportState.CLAIMED);
            return true;
        }
    }

    public static void releasePickerImportSource(String path) {
        String normalized = normalizeManagedPickerImport(path);
        if (normalized == null) {
            return;
        }
        synchronized (PICKER_IMPORT_LOCK) {
            ACTIVE_PICKER_IMPORTS.remove(normalized);
        }
        enqueuePickerImportDelete(new File(normalized));
    }

    private static void releaseUnclaimedPickerImportSource(String path) {
        String normalized = normalizeManagedPickerImport(path);
        if (normalized == null) {
            return;
        }
        boolean release = false;
        synchronized (PICKER_IMPORT_LOCK) {
            if (ACTIVE_PICKER_IMPORTS.get(normalized)
                    == PickerImportState.READY) {
                ACTIVE_PICKER_IMPORTS.remove(normalized);
                release = true;
            }
        }
        if (release) {
            enqueuePickerImportDelete(new File(normalized));
        }
    }

    private static void enqueuePickerImportDelete(File file) {
        PluginUiDiskExecutor.execute(
                "delete picker plugin",
                () -> deleteManagedPickerImport(file));
    }

    private static void sweepOrphanedPickerImports() {
        File directory = getPickerImportDirectory(false);
        if (directory == null) {
            return;
        }
        Set<String> active;
        synchronized (PICKER_IMPORT_LOCK) {
            active = new HashSet<>(ACTIVE_PICKER_IMPORTS.keySet());
        }
        File[] candidates = directory.listFiles((dir, name) ->
                PICKER_IMPORT_FILE_PATTERN.matcher(name).matches());
        if (candidates == null) {
            return;
        }
        for (File candidate : candidates) {
            if (candidate != null
                    && !active.contains(candidate.getAbsolutePath())) {
                deleteManagedPickerImport(candidate);
            }
        }
    }

    private static void deleteManagedPickerImport(File file) {
        String normalized = normalizeManagedPickerImport(
                file != null ? file.getAbsolutePath() : null);
        if (normalized == null) {
            return;
        }
        File directory = getPickerImportDirectory(false);
        if (directory == null) {
            return;
        }
        File candidate = new File(normalized);
        try {
            File parent = candidate.getParentFile();
            if (parent == null
                    || !parent.getCanonicalFile().equals(
                            directory.getCanonicalFile())) {
                return;
            }
            android.system.StructStat stat = Os.lstat(
                    candidate.getAbsolutePath());
            int type = stat.st_mode & OsConstants.S_IFMT;
            if (type != OsConstants.S_IFREG
                    && type != OsConstants.S_IFLNK) {
                return;
            }
        } catch (ErrnoException missing) {
            if (missing.errno == OsConstants.ENOENT) {
                return;
            }
            FileLog.e("nimarko: could not inspect picker stage", missing);
            return;
        } catch (IOException unsafePath) {
            FileLog.e(
                    "nimarko: could not canonicalize picker stage",
                    unsafePath);
            return;
        }
        if (!candidate.delete() && candidate.exists()) {
            FileLog.w(
                    "nimarko: could not delete picker stage "
                            + candidate.getAbsolutePath());
            return;
        }
        syncPickerDirectory(candidate.getParentFile());
    }

    private static void syncPickerDirectory(File directory) {
        if (directory == null) {
            return;
        }
        FileDescriptor descriptor = null;
        try {
            descriptor = Os.open(
                    directory.getAbsolutePath(),
                    OsConstants.O_RDONLY | OsConstants.O_CLOEXEC,
                    0);
            Os.fsync(descriptor);
        } catch (Throwable failure) {
            FileLog.e("nimarko: could not fsync picker directory", failure);
        } finally {
            if (descriptor != null) {
                try {
                    Os.close(descriptor);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.Plugins);
    }

    @Override
    public void fillItems(ArrayList<UItem> arrayList, UniversalAdapter universalAdapter) {
        Plugin plugin;
        if (!this.searching) {
            arrayList.add(UItem.asCheck(2, LocaleController.getString(R.string.PluginsEngine))
                    .setChecked(NimarkoConfig.pluginsEngine));
            arrayList.add(UItem.asShadow(null));
        }
        if (NimarkoConfig.pluginsEngine) {
            HashMap<String, Plugin> map = new HashMap<>(PluginsController.getInstance().plugins);
            UItem uItemAsSpace = UItem.asSpace(AndroidUtilities.dp(8.0f));
            uItemAsSpace.transparent = true;
            arrayList.add(uItemAsSpace);
            
            if (this.searching && !TextUtils.isEmpty(this.query)) {
                
                map.values().removeIf(p -> !p.getName().toLowerCase().contains(this.query.toLowerCase()));
            }
            if (map.isEmpty()) {
                
                if (this.emptyView == null) {
                    this.emptyView = new EmptyPluginsView(getContext(), getResourceProvider());
                }
                EmptyPluginsView emptyPluginsView = this.emptyView;
                if (this.searching) {
                    if (emptyPluginsView.getTag() == null || ((Integer) emptyPluginsView.getTag()).intValue() != 1) {
                        MediaDataController.getInstance(UserConfig.selectedAccount).setPlaceholderImage(emptyPluginsView.getBackupImageView(), "AnimatedEmojies", "🔎", "100_100");
                        emptyPluginsView.setText(LocaleController.getString(R.string.PluginsNotFound));
                        emptyPluginsView.setTag(1);
                    }
                } else if (emptyPluginsView.getTag() == null || ((Integer) emptyPluginsView.getTag()).intValue() != 2) {
                    MediaDataController.getInstance(UserConfig.selectedAccount).setPlaceholderImage(emptyPluginsView.getBackupImageView(), "AnimatedEmojies", "📂", "100_100");
                    
                    emptyPluginsView.setText(LocaleUtils.formatWithUsernames(
                            LocaleController.getString(R.string.NM_PluginsEmpty) + "\n"
                                    + LocaleController.getString(R.string.NM_PluginsEmptyHint)));
                    emptyPluginsView.setTag(2);
                }
                arrayList.add(UItem.asFullscreenCustom(emptyPluginsView, AndroidUtilities.dp((this.searching ? 2 : 1) * 74), true).setTransparent(true));
            } else {
                List<String> pinnedPluginIds = NimarkoConfig.getPinnedPluginsSnapshot();
                if (!pinnedPluginIds.isEmpty()) {
                    for (String str : pinnedPluginIds) {
                        if (map.containsKey(str) && (plugin = map.get(str)) != null) {
                            arrayList.add(createPluginItem(plugin));
                        }
                    }
                }
                List<Plugin> arrayList2 = new ArrayList<>(map.values());
                Collections.sort(arrayList2, Comparator.comparing(Plugin::getName));

                for (Plugin plugin2 : arrayList2) {
                    if (!pinnedPluginIds.contains(plugin2.getId())) {
                        arrayList.add(createPluginItem(plugin2));
                    }
                }
            }
            UItem uItemAsSpace2 = UItem.asSpace(AndroidUtilities.dp(4.0f));
            uItemAsSpace2.transparent = true;
            arrayList.add(uItemAsSpace2);
        }
    }

    private UItem createPluginItem(Plugin plugin) {
        return PluginCell.Factory.as(plugin, new PluginCellDelegate() {
            @Override
            public void sharePlugin() {
                PluginsController.PluginsEngine pluginEngine = PluginsController.getInstance().getPluginEngine(plugin.getId());
                if (pluginEngine != null) {
                    pluginEngine.sharePlugin(plugin.getId());
                }
            }

            @Override
            public void openInExternalApp() {
                PluginsController.PluginsEngine pluginEngine = PluginsController.getInstance().getPluginEngine(plugin.getId());
                if (pluginEngine != null) {
                    pluginEngine.openInExternalApp(plugin.getId());
                }
            }

            @Override
            public void deletePlugin() {
                final long dialogLifecycleEpoch = lifecycleEpoch;
                if (!isUiLifecycleCurrent(dialogLifecycleEpoch)) {
                    return;
                }
                if (plugin.isNotResponding()) {
                    PluginsWatchdog.showNotRespondingAlert(plugin);
                    return;
                }
                final String pluginId = plugin.getId();
                AlertDialog.Builder message = new AlertDialog.Builder(PluginsActivity.this.getParentActivity(), PluginsActivity.this.getResourceProvider()).setTitle(LocaleController.getString(R.string.PluginDelete)).setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.PluginDeleteInfo, plugin.getName())));
                String string = LocaleController.getString(R.string.Delete);
                AlertDialog alertDialogCreate = message.setPositiveButton(string, (alertDialog, i) -> {
                    if (!isUiLifecycleCurrent(dialogLifecycleEpoch)) {
                        return;
                    }
                    final long callbackLifecycleEpoch = dialogLifecycleEpoch;
                    final long operationEpoch = beginPluginUiOperation(pluginId);
                    PluginsController.getInstance().deletePlugin(pluginId, str ->
                        AndroidUtilities.runOnUIThread(() -> {
                            if (!isPluginUiOperationEpochCurrent(
                                    callbackLifecycleEpoch, pluginId, operationEpoch)) {
                                return;
                            }
                            finishPluginUiOperation(pluginId, operationEpoch);
                            if (!isUiLifecycleCurrent(callbackLifecycleEpoch)) {
                                return;
                            }
                            if (PluginsActivity.this.listView != null
                                    && PluginsActivity.this.listView.adapter != null) {
                                PluginsActivity.this.listView.adapter.update(true);
                            }
                            if (str != null) {
                                BulletinFactory.of(PluginsActivity.this)
                                        .createSimpleBulletin(R.raw.error, str)
                                        .show();
                            }
                        })
                    );
                }
                ).setNegativeButton(LocaleController.getString(R.string.Cancel), null).create();
                alertDialogCreate.show();
                TextView textView = (TextView) alertDialogCreate.getButton(-1);
                if (textView != null) {
                    textView.setTextColor(Theme.getColor(Theme.key_text_RedBold));
                }
            }

            @Override
            public void togglePlugin(View view) {
                final long callbackLifecycleEpoch = lifecycleEpoch;
                if (!isUiLifecycleCurrent(callbackLifecycleEpoch)) {
                    return;
                }
                if (plugin.isNotResponding()) {
                    PluginsWatchdog.showNotRespondingAlert(plugin);
                    return;
                }
                final PluginCell pluginCell = (PluginCell) view;
                final String pluginId = plugin.getId();
                final PluginsController controller = PluginsController.getInstance();
                final boolean z = !controller.getRequestedPluginEnabled(pluginId);
                final long operationEpoch = beginPluginUiOperation(pluginId);
                final long cellBindingEpoch = pluginCell.getBindingEpoch();
                
                pluginCell.setChecked(z, true);
                
                pluginCell.setLoading(isPluginToggleLoading(pluginId));
                controller.setPluginEnabled(pluginId, z, str ->
                    AndroidUtilities.runOnUIThread(() -> {
                        if (!isPluginUiOperationEpochCurrent(
                                callbackLifecycleEpoch, pluginId, operationEpoch)) {
                            return;
                        }
                        finishPluginUiOperation(pluginId, operationEpoch);
                        if (!isUiLifecycleCurrent(callbackLifecycleEpoch)) {
                            return;
                        }
                        boolean stillBound = pluginCell.isBoundTo(
                                pluginId, cellBindingEpoch);
                        if (stillBound && pluginCell.isAttachedToWindow()) {
                            pluginCell.setLoading(isPluginToggleLoading(pluginId));
                            pluginCell.setChecked(
                                    controller.getRequestedPluginEnabled(pluginId), true);
                        }
                        
                        if (PluginsActivity.this.listView != null
                                && PluginsActivity.this.listView.adapter != null) {
                            PluginsActivity.this.listView.adapter.update(true);
                        }
                        if (str != null) {
                            BulletinFactory.of(PluginsActivity.this).createSimpleBulletin(R.raw.error, AndroidUtilities.replaceTags(LocaleController.formatString(z ? R.string.PluginEnableError : R.string.PluginDisableError, plugin.getName())), LocaleUtils.createCopySpan(PluginsActivity.this), () -> {
                                if (isUiLifecycleCurrent(callbackLifecycleEpoch)
                                        && AndroidUtilities.addToClipboard(str)) {
                                    BulletinFactory.of(PluginsActivity.this).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
                                }
                            }).show();
                        }
                    })
                );
            }

            @Override
            public void openPluginSettings() {
                PluginsController.PluginsEngine pluginEngine;
                if (!PluginsController.getInstance().hasPluginSettings(plugin.getId()) || (pluginEngine = PluginsController.getInstance().getPluginEngine(plugin.getId())) == null) {
                    return;
                }
                pluginEngine.openPluginSettings(plugin, PluginsActivity.this);
            }

            @Override
            public void pinPlugin(View view) {
                
                boolean zIsPluginPinned = PluginsController.isPluginPinned(plugin.getId());
                PluginsController.setPluginPinned(plugin.getId(), !zIsPluginPinned);
                PluginCell cell = null;
                View walker = view;
                while (walker != null) {
                    if (walker instanceof PluginCell) {
                        cell = (PluginCell) walker;
                        break;
                    }
                    if (walker.getParent() instanceof View) {
                        walker = (View) walker.getParent();
                    } else {
                        walker = null;
                    }
                }
                if (cell != null) {
                    cell.setPinned(!zIsPluginPinned);
                }
                PluginsActivity.this.listView.adapter.update(true);
                PluginsActivity.this.listView.smoothScrollToPosition(0);
            }

            @Override
            public boolean canOpenInExternalApp() {
                PluginsController.PluginsEngine pluginEngine = PluginsController.getInstance().getPluginEngine(plugin.getId());
                return pluginEngine != null && pluginEngine.canOpenInExternalApp();
            }

            @Override
            public void showKebabMenu(View anchor) {
                
                final boolean pinned = PluginsController.isPluginPinned(plugin.getId());
                final boolean hasSettings = plugin.isEnabled()
                        && PluginsController.getInstance().hasPluginSettings(plugin.getId());
                ItemOptions opts = ItemOptions.makeOptions(PluginsActivity.this, anchor);
                if (hasSettings) {
                    opts.add(R.drawable.msg_settings,
                            LocaleController.getString(R.string.Settings),
                            this::openPluginSettings);
                }
                opts.add(pinned ? R.drawable.msg_unpin : R.drawable.msg_pin,
                        LocaleController.getString(pinned ? R.string.DialogUnpin : R.string.DialogPin),
                        () -> pinPlugin(anchor));
                opts.add(R.drawable.msg_share,
                        LocaleController.getString(R.string.ShareFile),
                        this::sharePlugin);
                opts.addIf(canOpenInExternalApp(),
                        R.drawable.msg_openin,
                        LocaleController.getString(R.string.OpenInExternalApp),
                        this::openInExternalApp);
                opts.add(R.drawable.msg_delete,
                        LocaleController.getString(R.string.Delete),
                        true,
                        this::deletePlugin);
                opts.show();
            }
        }, getCurrentPluginUiOperationEpoch(plugin.getId()));
    }

    @Override
    public void onClick(UItem uItem, View view, int i, float f, float f2) {
        if (uItem.id == 2) {
            togglePluginsEngine(view, uItem);
            return;
        }
        
        if (uItem.plugin != null && view instanceof PluginCell) {
            
            if (((PluginCell) view).isPointOnInteractive(f, f2)) {
                return;
            }
            Plugin plugin = uItem.plugin;
            if (plugin.isNotResponding()) {
                PluginsWatchdog.showNotRespondingAlert(plugin);
                return;
            }
            
            PluginCell cellRef = (PluginCell) view;
            if (cellRef.isLoading()
                    || PluginsController.getInstance().isEnablingInProgress(plugin.getId())) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info,
                        AndroidUtilities.replaceTags(
                                LocaleController.formatString(R.string.NM_PluginEnabling, plugin.getName()))).show();
                return;
            }
            
            if (!plugin.isEnabled()) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info,
                        AndroidUtilities.replaceTags(
                                LocaleController.formatString(R.string.NM_PluginIsDisabled, plugin.getName()))).show();
                return;
            }
            if (!PluginsController.getInstance().hasPluginSettings(plugin.getId())) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info,
                        AndroidUtilities.replaceTags(
                                LocaleController.formatString(R.string.PluginHasNoSettings, plugin.getName()))).show();
                return;
            }
            PluginsController.PluginsEngine engine =
                    PluginsController.getInstance().getPluginEngine(plugin.getId());
            if (engine != null) {
                engine.openPluginSettings(plugin, PluginsActivity.this);
            }
        }
    }

    private void togglePluginsEngine(View view, UItem uItem) {
        if (this.isSwitchingEngineState) {
            return;
        }
        this.isSwitchingEngineState = true;

        NimarkoConfig.togglePluginsEngine();
        boolean z = NimarkoConfig.pluginsEngine;

        TextCheckCell textCheckCell = (TextCheckCell) view;
        uItem.checked = z;
        textCheckCell.setChecked(z);

        BulletinFactory.of(this).createSimpleBulletin(R.drawable.msg_retry,
                LocaleController.getString(R.string.NM_PluginsRestarting)).show();

        AndroidUtilities.runOnUIThread(() -> {
            try {
                android.content.Context ctx = getParentActivity();
                if (ctx == null) {
                    ctx = org.telegram.messenger.ApplicationLoader.applicationContext;
                }
                app.nimarkogram.messenger.utils.AppRestartHelper.triggerRebirth(ctx);
            } catch (Throwable t) {
                org.telegram.messenger.FileLog.e("nimarko: AppRestartHelper.triggerRebirth failed", t);
                this.isSwitchingEngineState = false;
            }
        }, 250L);
    }

    @Override
    public int getNavigationBarColor() {
        return Theme.getColor(Theme.key_windowBackgroundGray);
    }

    @Override
    public boolean onFragmentCreate() {
        if (!super.onFragmentCreate()) {
            return false;
        }
        lifecycleEpoch++;
        uiLifecycleActive = true;
        final long callbackLifecycleEpoch = lifecycleEpoch;
        final long operationEpoch = ++sweepIoOperationEpoch;
        PluginUiDiskExecutor.execute(
                "sweep picker plugins",
                () -> {
                    if (!isSweepIoOperationCurrent(
                            callbackLifecycleEpoch, operationEpoch)) {
                        return;
                    }
                    sweepOrphanedPickerImports();
                    if (!isSweepIoOperationCurrent(
                            callbackLifecycleEpoch, operationEpoch)) {
                        return;
                    }
                });
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pluginsUpdated);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.reloadInterface);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pluginIsNotResponding);
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        uiLifecycleActive = false;
        lifecycleEpoch++;
        pickerIoOperationEpoch++;
        sweepIoOperationEpoch++;
        pluginUiOperationEpochs.clear();
        isSwitchingEngineState = false;
        query = null;
        searching = false;
        searchItem = null;
        engineSettingsItem = null;
        emptyView = null;
        addPluginButton = null;
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pluginsUpdated);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.reloadInterface);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pluginIsNotResponding);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int i, int i2, Object... objArr) {
        if (!uiLifecycleActive || !PluginUiRegistry.isFragmentUiActive(this)) {
            return;
        }
        if (i == NotificationCenter.pluginsUpdated) {
            if (this.searchItem != null) {
                AndroidUtilities.updateViewVisibilityAnimated(this.searchItem,
                        NimarkoConfig.pluginsEngine && !PluginsController.getInstance().plugins.isEmpty(),
                        0.5f, true);
            }
            updateAddButtonVisibility(true);
            
            if (this.listView != null && this.listView.adapter != null) {
                this.listView.adapter.update(true);
            }
        } else if (i == NotificationCenter.reloadInterface) {
            
            if (this.listView != null) {
                if (this.listView.adapter != null) {
                    this.listView.adapter.update(true);
                } else {
                    this.listView.invalidateViews();
                }
            }
        } else if (i == NotificationCenter.pluginIsNotResponding) {
            if (this.listView != null && this.listView.adapter != null) {
                this.listView.adapter.update(true);
            }
        }
    }
}
