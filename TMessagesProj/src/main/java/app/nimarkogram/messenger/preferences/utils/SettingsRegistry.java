package app.nimarkogram.messenger.preferences.utils;

import android.text.TextUtils;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.UItem;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

import app.nimarkogram.messenger.plugins.PluginsController;
import app.nimarkogram.messenger.preferences.MainPreferencesActivity;

public class SettingsRegistry {

    public static class Entry {
         
        public int guid;
         
        public Class<?> fragmentClass;
         
        public CharSequence title;
         
        public CharSequence subtext;
         
        public String alias;
         
        public int itemId;
         
        boolean explicitAlias;

        public Entry() {}

        public Entry(int guid, String alias, Class<?> fragmentClass, CharSequence title, CharSequence subtext) {
            this.guid = guid;
            this.alias = alias;
            this.fragmentClass = fragmentClass;
            this.title = title;
            this.subtext = subtext;
        }
    }

    private static final class RegistryState {
        final Map<String, Entry> aliases;
        final Map<Integer, Entry> prepared;

        RegistryState(Map<String, Entry> aliases, Map<Integer, Entry> prepared) {
            this.aliases = Collections.unmodifiableMap(new LinkedHashMap<>(aliases));
            this.prepared = Collections.unmodifiableMap(new HashMap<>(prepared));
        }
    }

    private volatile RegistryState registryState =
            new RegistryState(Collections.emptyMap(), Collections.emptyMap());

    private final class AliasesView extends AbstractMap<String, SettingsRegistry.Entry> {
        @Override
        public SettingsRegistry.Entry get(Object key) {
            return registryState.aliases.get(key);
        }

        @Override
        public int size() {
            return registryState.aliases.size();
        }

        @Override
        public Set<Map.Entry<String, SettingsRegistry.Entry>> entrySet() {
            return registryState.aliases.entrySet();
        }
    }

    private final class PreparedView extends AbstractMap<Integer, SettingsRegistry.Entry> {
        @Override
        public SettingsRegistry.Entry get(Object key) {
            return registryState.prepared.get(key);
        }

        @Override
        public int size() {
            return registryState.prepared.size();
        }

        @Override
        public Set<Map.Entry<Integer, SettingsRegistry.Entry>> entrySet() {
            return registryState.prepared.entrySet();
        }
    }

    public final Map<String, Entry> entriesStringAlias = new AliasesView();
     
    public final Map<Integer, Entry> preparedEntries = new PreparedView();
     
    public static final Map<Class<?>, Boolean> ayuCategories = new HashMap<>();

    private volatile boolean entriesCreated = false;

    private SettingsRegistry() {}

    private static volatile SettingsRegistry instance;

    public static SettingsRegistry getInstance() {
        SettingsRegistry local = instance;
        if (local == null) {
            synchronized (SettingsRegistry.class) {
                local = instance;
                if (local == null) {
                    local = new SettingsRegistry();
                    instance = local;
                }
            }
        }
        return local;
    }

    public void createEntriesIfNeeded() {
        if (entriesCreated) {
            return;
        }
        synchronized (this) {
            if (entriesCreated) {
                return;
            }
            addBuiltIn("nimarko_general", MainPreferencesActivity.ID_GENERAL, R.string.NM_Cat_General);
            addBuiltIn("nimarko_appearance", MainPreferencesActivity.ID_APPEARANCE, R.string.NM_Cat_Appearance);
            addBuiltIn("nimarko_chats", MainPreferencesActivity.ID_CHATS, R.string.NM_Cat_Chats);
            addBuiltIn("nimarko_camera", MainPreferencesActivity.ID_CAMERA, R.string.NM_Cat_Camera);
            addBuiltIn("nimarko_privacy", MainPreferencesActivity.ID_PRIVACY, R.string.NM_Cat_Privacy);
            if (PluginsController.isPluginEngineSupported()) {
                addBuiltIn("nimarko_plugins", MainPreferencesActivity.ID_PLUGINS, R.string.Plugins);
            }
            addBuiltIn("nimarko_media", MainPreferencesActivity.ID_NIMARKO_MEDIA, R.string.NM_DownloadMedia);
            addBuiltIn("nimarko_banners", MainPreferencesActivity.ID_BANNERS, R.string.NM_BAN_Title);
            addBuiltIn("nimarko_wsbypass", MainPreferencesActivity.ID_WSBYPASS, R.string.NM_WSB_Title);
            addBuiltIn("nimarko_textanim", MainPreferencesActivity.ID_TEXTANIM, R.string.NM_TA_Title);
            addBuiltIn("nimarko_infocards", MainPreferencesActivity.ID_PILLSTACK, R.string.NM_CARDS_Title);
            addBuiltIn("nimarko_debug", MainPreferencesActivity.ID_DEBUG, R.string.NM_HUB_Debug);
            addBuiltIn("nimarko_restart", MainPreferencesActivity.ID_RESTART, R.string.NM_HUB_Restart);
            addBuiltIn("nimarko_updates", MainPreferencesActivity.ID_UPDATES, R.string.UP_CheckForUpdates);
            entriesCreated = true;
        }
    }

    public static boolean isValidForLinkAliases(UItem item) {
        if (item == null) {
            return false;
        }
        
        return item.id != 0 || !TextUtils.isEmpty(item.text);
    }

    public static boolean isValidForSearch(UItem item) {
        return item != null && !TextUtils.isEmpty(item.text);
    }

    public synchronized void addLinkAliasForOption(String alias, BaseFragment activity, UItem item) {
        try {
            if (TextUtils.isEmpty(alias) || item == null) {
                return;
            }
            Class<?> fragmentClass = activity != null ? activity.getClass() : null;
            RegistryState current = registryState;
            LinkedHashMap<String, Entry> aliases = new LinkedHashMap<>(current.aliases);
            HashMap<Integer, Entry> prepared = new HashMap<>(current.prepared);
            
            java.util.Iterator<Map.Entry<String, Entry>> iterator = aliases.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Entry> candidate = iterator.next();
                Entry value = candidate.getValue();
                if (!candidate.getKey().equals(alias) && value != null && !value.explicitAlias
                        && sameRow(value, fragmentClass, item)) {
                    prepared.remove(value.guid);
                    iterator.remove();
                }
            }
            Entry previous = aliases.get(alias);
            Entry entry = new Entry();
            entry.alias = alias;
            entry.guid = previous == null ? nextGuid() : previous.guid;
            entry.fragmentClass = fragmentClass;
            entry.title = item.text;
            entry.subtext = item.subtext;
            entry.itemId = item.id;
            entry.explicitAlias = true;
            aliases.put(alias, entry);
            prepared.put(entry.guid, entry);
            publish(aliases, prepared);
        } catch (Throwable t) {
            FileLog.e("nimarko: SettingsRegistry.addLinkAliasForOption failed", t);
        }
    }

    public synchronized void addSearchEntry(BaseFragment activity, UItem item) {
        try {
            if (item == null || TextUtils.isEmpty(item.text)) {
                return;
            }
            String explicitAlias = item.getLinkAlias();
            if (!TextUtils.isEmpty(explicitAlias)) {
                addLinkAliasForOption(explicitAlias, activity, item);
                return;
            }
            Class<?> fragmentClass = activity != null ? activity.getClass() : null;
            RegistryState current = registryState;
            for (Entry existing : current.aliases.values()) {
                if (existing != null && existing.explicitAlias && sameRow(existing, fragmentClass, item)) {
                    return;
                }
            }
            
            String alias = deriveSearchKey(item);
            if (TextUtils.isEmpty(alias)) {
                return;
            }
            Entry entry = current.aliases.get(alias);
            if (entry == null) {
                entry = new Entry(nextGuid(), alias, fragmentClass, item.text, item.subtext);
                entry.itemId = item.id;
                entry.explicitAlias = false;
                LinkedHashMap<String, Entry> aliases = new LinkedHashMap<>(current.aliases);
                HashMap<Integer, Entry> prepared = new HashMap<>(current.prepared);
                aliases.put(alias, entry);
                prepared.put(entry.guid, entry);
                publish(aliases, prepared);
            }
        } catch (Throwable t) {
            FileLog.e("nimarko: SettingsRegistry.addSearchEntry failed", t);
        }
    }

    public String getFirstSettingLink(Class<?> fragmentClass, UItem item) {
        try {
            if (item == null) {
                return null;
            }
            RegistryState snapshot = registryState;
            for (Map.Entry<String, Entry> e : snapshot.aliases.entrySet()) {
                Entry entry = e.getValue();
                if (entry == null) {
                    continue;
                }
                boolean sameFragment = fragmentClass == null
                        || entry.fragmentClass == null
                        || fragmentClass.equals(entry.fragmentClass);
                boolean sameText = entry.title != null && item.text != null
                        && entry.title.toString().contentEquals(item.text);
                if (sameFragment && sameText) {
                    return e.getKey();
                }
            }
        } catch (Throwable t) {
            FileLog.e("nimarko: SettingsRegistry.getFirstSettingLink failed", t);
        }
        return null;
    }

    public void onSettingNotFound(BaseFragment activity) {
        FileLog.d("nimarko: SettingsRegistry.onSettingNotFound (no matching setting)");
    }

    private int guidCounter = 0;

    private synchronized int nextGuid() {
        return ++guidCounter;
    }

    private void addBuiltIn(String alias, int itemId, int titleRes) {
        RegistryState current = registryState;
        LinkedHashMap<String, Entry> aliases = new LinkedHashMap<>(current.aliases);
        HashMap<Integer, Entry> prepared = new HashMap<>(current.prepared);
        Entry previous = aliases.get(alias);
        Entry entry = new Entry(previous == null ? nextGuid() : previous.guid, alias,
                MainPreferencesActivity.class, LocaleController.getString(titleRes),
                LocaleController.getString(R.string.NimarkoGramSettings));
        entry.itemId = itemId;
        entry.explicitAlias = true;
        aliases.put(alias, entry);
        prepared.put(entry.guid, entry);
        publish(aliases, prepared);
    }

    private void publish(Map<String, Entry> aliases, Map<Integer, Entry> prepared) {
        registryState = new RegistryState(aliases, prepared);
    }

    private static String deriveSearchKey(UItem item) {
        if (item == null || item.text == null) {
            return null;
        }
        String base = item.text.toString().trim().toLowerCase(Locale.ROOT);
        if (base.isEmpty()) {
            return null;
        }
        return base.replaceAll("\\s+", "_");
    }

    private static boolean sameRow(Entry entry, Class<?> fragmentClass, UItem item) {
        if (entry.fragmentClass != fragmentClass) return false;
        if (entry.itemId != 0 && item.id != 0) return entry.itemId == item.id;
        return TextUtils.equals(entry.title, item.text);
    }
}
