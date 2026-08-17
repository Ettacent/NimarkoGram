package app.nimarkogram.messenger.utils;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import app.nimarkogram.messenger.NimarkoConfig;

public final class NimarkoConfigBackup {

    private static final String MAGIC = "nimarkoconfig";
    
    private static final int VERSION = 4;
    private static final int MAX_BACKUP_BYTES = 2 * 1024 * 1024;
    private static final int MAX_NAMESPACES = 16;
    private static final int MAX_KEYS_PER_NAMESPACE = 2048;
    private static final int MAX_KEY_LENGTH = 160;
    private static final int MAX_STRING_LENGTH = 256 * 1024;
    private static final int MAX_SET_ITEMS = 4096;
    private static final int MAX_PLUGIN_DEPTH = 8;
    private static final int MAX_PLUGIN_VALUES = 4096;
    private static final Pattern SAFE_KEY = Pattern.compile("^[A-Za-z0-9_.:-]{1," + MAX_KEY_LENGTH + "}$");

    private static final String PREFS_MAIN = "nimarkoconfig";
    private static final String PREFS_BANNERS = "nimarko_banners";
    private static final String PREFS_WSBYPASS = "nimarko_wsbypass";
    private static final String PREFS_UPDATE = "nimarko_update";
    private static final String PREFS_INFO_CARDS = "nm_pillstack";
    private static final String PREFS_PLUGINS = "plugin_settings";
    private static final String PYTHON_PLUGIN_SETTINGS = "pythonPluginSettings";
    private static final Set<String> NON_PORTABLE_PLUGIN_IDS =
            new HashSet<>(Arrays.asList("__legacy_quarantine__"));

    private static final List<String> PORTABLE_NAMESPACES = Arrays.asList(
            PREFS_MAIN, PREFS_BANNERS, PREFS_WSBYPASS, PREFS_UPDATE,
            PREFS_INFO_CARDS, PREFS_PLUGINS);
    private static final Set<String> PORTABLE_NAMESPACE_SET = new HashSet<>(PORTABLE_NAMESPACES);

    private static final Set<String> SENSITIVE_KEYS = new HashSet<>(Arrays.asList(
            "nimarkoMediaAuthToken", "voipRelayAuthToken", "wsRelayAuthToken", "wsInstallId",
            "auth_token", "mtproto_secret", "ws_relay_cred", "voip_relay_cred"));

    private static final Set<String> DEVICE_LOCAL_SECURITY_KEYS = new HashSet<>(Arrays.asList(
            "askBiometricsBeforeDelete",
            "askBiometricsToOpenChat",
            "lockedChatsBiometricTtlSec",
            "allowSystemPasscode",
            "askBiometricsToOpenArchive",
            "askBiometricsToOpenEncrypted",
            "askPasscodeBeforeDelete"));

    private static final Set<String> JSON_STRING_BACKED_MAIN_KEYS = new HashSet<>(Arrays.asList(
            "folderColors",
            "folderBadgeMode",
            "messageMenuOrder",
            "chatCompactOverrideOn",
            "chatCompactOverrideOff",
            "pinnedPlugins"));

    private static final Map<String, Map<String, String>> FIXED_SCHEMA = buildFixedSchema();

    private static Map<String, Map<String, String>> buildFixedSchema() {
        Map<String, Map<String, String>> out = new HashMap<>();
        out.put(PREFS_BANNERS, schema(
                "enabled", "b", "use_avatar", "b", "lite_mode", "b"));
        
        out.put(PREFS_WSBYPASS, schema(
                "configured", "b", "enabled", "b", "suspend_on_vpn", "b"));
        out.put(PREFS_UPDATE, schema("autoOTA", "b"));
        out.put(PREFS_INFO_CARDS, schema(
                "enabled", "b", "activePills", "s", "hiddenPills", "s",
                "layoutCustomized", "b",
                "infiniteScrolling", "b", "autoScroll", "b", "colorMode", "i",
                "lastActivePillId", "i", "weatherUseCurrentLocation", "b",
                "weatherCustomLocation", "s", "weatherCustomAddress", "s"));
        return out;
    }

    private static Map<String, String> schema(String... pairs) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) out.put(pairs[i], pairs[i + 1]);
        return out;
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null || SENSITIVE_KEYS.contains(key)) return true;
        String normalized = key.toLowerCase(Locale.ROOT);
        
        if (normalized.contains("token") || normalized.contains("secret")
                || normalized.contains("password") || normalized.contains("cookie")
                || normalized.contains("credential") || normalized.endsWith("apikey")
                || normalized.endsWith("api_key")) return true;
        int relayAt = normalized.indexOf("relay");
        if (relayAt >= 0) {
            String tail = normalized.substring(relayAt + "relay".length());
            if (tail.startsWith("authtoken") || tail.startsWith("_auth_token")
                    || tail.startsWith("token") || tail.startsWith("_token")
                    || tail.startsWith("cred") || tail.startsWith("_cred")
                    || tail.startsWith("credential") || tail.startsWith("_credential")) return true;
        }
        return normalized.startsWith("tgws_proxy_snap_") || normalized.startsWith("wsinstallid")
                || normalized.endsWith("installid") || normalized.endsWith("install_id")
                || normalized.endsWith("install-id");
    }

    private static SharedPreferences prefs(String name) {
        return ApplicationLoader.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    private static String expectedType(String namespace, String key) {
        if (key == null || !SAFE_KEY.matcher(key).matches() || isSensitiveKey(key)) return null;
        if (PREFS_MAIN.equals(namespace)) {
            if (isDeviceLocalSecurityKey(key)) return null;
            
            if (key.startsWith("notificationReaction_") || key.startsWith("notificationReactionEmoji_")) {
                return "s";
            }
            if (JSON_STRING_BACKED_MAIN_KEYS.contains(key)) return "s";
            if (isCustomSavedMessagesDialogKey(key)) return "l";
            try {
                Field field = NimarkoConfig.class.getDeclaredField(key);
                int mods = field.getModifiers();
                if (!Modifier.isStatic(mods) || Modifier.isFinal(mods)) return null;
                Class<?> type = field.getType();
                if (type == boolean.class || type == Boolean.class) return "b";
                if (type == int.class || type == Integer.class) return "i";
                if (type == long.class || type == Long.class) return "l";
                if (type == float.class || type == Float.class) return "f";
                if (type == String.class || CharSequence.class.isAssignableFrom(type)) return "s";
                if (Set.class.isAssignableFrom(type)) return "ss";
            } catch (Throwable ignore) {
            }
            return null;
        }
        if (PREFS_INFO_CARDS.equals(namespace) && key.matches("ccy_[0-9]{1,9}")) return "s";
        if (PREFS_PLUGINS.equals(namespace)) {
            
            if (isPluginRuntimeMetadataKey(key)) return null;
            if (key.startsWith("plugin_enabled_") && key.length() > "plugin_enabled_".length()) return "b";
            if (key.startsWith("plugin_setting_") && key.length() > "plugin_setting_".length()) {
                return null; 
            }
        }
        Map<String, String> schema = FIXED_SCHEMA.get(namespace);
        return schema == null ? null : schema.get(key);
    }

    private static boolean isDeviceLocalSecurityKey(String key) {
        if (DEVICE_LOCAL_SECURITY_KEYS.contains(key)) return true;
        
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("biometric") || normalized.contains("passcode");
    }

    private static boolean isCustomSavedMessagesDialogKey(String key) {
        final String prefix = "customSavedMessagesDialogId_a";
        if (key == null || !key.startsWith(prefix)) return false;
        String suffix = key.substring(prefix.length());
        if (suffix.isEmpty() || suffix.length() > 2 || suffix.charAt(0) == '0' && suffix.length() > 1) {
            return false;
        }
        int account = 0;
        for (int i = 0; i < suffix.length(); i++) {
            char c = suffix.charAt(i);
            if (c < '0' || c > '9') return false;
            account = account * 10 + c - '0';
        }
        return account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT;
    }

    private static String expectedType(String namespace, String key, Object value) {
        String fixed = expectedType(namespace, key);
        if (fixed != null) return fixed;
        if (PREFS_PLUGINS.equals(namespace) && key != null && key.startsWith("plugin_setting_")
                && SAFE_KEY.matcher(key).matches() && !isSensitiveKey(key)) {
            return typeOfValue(value);
        }
        return null;
    }

    private static String typeOfValue(Object value) {
        if (value instanceof Boolean) return "b";
        if (value instanceof Integer) return "i";
        if (value instanceof Long) return "l";
        if (value instanceof Float) return "f";
        if (value instanceof String) return "s";
        if (value instanceof Set) return "ss";
        return null;
    }

    private static JSONObject dumpPrefs(String namespace, SharedPreferences preferences) throws Exception {
        JSONObject data = new JSONObject();
        Map<String, ?> snapshot = preferences.getAll();
        for (Map.Entry<String, ?> entry : snapshot.entrySet()) {
            Object portableValue = entry.getValue();
            if (PREFS_PLUGINS.equals(namespace) && entry.getKey().startsWith("plugin_enabled_")
                    && !isPluginRuntimeMetadataKey(entry.getKey()) && portableValue instanceof Boolean) {
                String pluginId = entry.getKey().substring("plugin_enabled_".length());
                String beforeQuarantine = "plugin_enabled_before_quarantine_" + pluginId;
                Object originalChoice = snapshot.get(beforeQuarantine);
                if (originalChoice instanceof Boolean) portableValue = originalChoice;
            }
            String type = typeOfValue(portableValue);
            if (type == null || !type.equals(expectedType(namespace, entry.getKey(), portableValue))) continue;
            JSONObject tagged = new JSONObject().put("t", type);
            if ("ss".equals(type)) tagged.put("v", new JSONArray((Set<?>) portableValue));
            else tagged.put("v", portableValue);
            data.put(entry.getKey(), tagged);
        }
        return data;
    }

    public static String exportJson() throws Exception {
        return app.nimarkogram.messenger.plugins.PluginsController
                .withPortablePluginSettingsTransaction(() ->
                        NimarkoConfig.withSettingsTransaction(NimarkoConfigBackup::exportJsonLocked));
    }

    private static String exportJsonLocked() throws Exception {
        JSONObject namespaces = new JSONObject();
        for (String name : PORTABLE_NAMESPACES) namespaces.put(name, dumpPrefs(name, prefs(name)));
        JSONObject root = new JSONObject()
                .put("magic", MAGIC)
                .put("version", VERSION)
                .put("namespaces", namespaces);
        root.put(PYTHON_PLUGIN_SETTINGS, readPortablePythonPluginSettings());
        String result = root.toString(2);
        if (!withinSizeLimit(result)) throw new IllegalStateException("backup too large");
        return result;
    }

    private static final class PendingNamespace {
        final String name;
        final Map<String, Object> values = new LinkedHashMap<>();
        PendingNamespace(String name) { this.name = name; }
    }

    private static Object decodeValue(String type, Object raw) throws Exception {
        if ("b".equals(type)) {
            if (!(raw instanceof Boolean)) throw new IllegalArgumentException("bad boolean");
            return raw;
        } else if ("i".equals(type)) {
            if (!(raw instanceof Integer)) throw new IllegalArgumentException("bad int");
            return raw;
        } else if ("l".equals(type)) {
            if (!(raw instanceof Long) && !(raw instanceof Integer)) throw new IllegalArgumentException("bad long");
            return ((Number) raw).longValue();
        } else if ("f".equals(type)) {
            if (!(raw instanceof Number)) throw new IllegalArgumentException("bad float");
            double d = ((Number) raw).doubleValue();
            if (!Double.isFinite(d) || Math.abs(d) > Float.MAX_VALUE) throw new IllegalArgumentException("bad float");
            return (float) d;
        } else if ("s".equals(type)) {
            if (!(raw instanceof String) || ((String) raw).length() > MAX_STRING_LENGTH) {
                throw new IllegalArgumentException("bad string");
            }
            return raw;
        } else if ("ss".equals(type)) {
            if (!(raw instanceof JSONArray)) throw new IllegalArgumentException("bad string set");
            JSONArray array = (JSONArray) raw;
            if (array.length() > MAX_SET_ITEMS) throw new IllegalArgumentException("set too large");
            Set<String> set = new HashSet<>();
            for (int i = 0; i < array.length(); i++) {
                Object item = array.get(i);
                if (!(item instanceof String) || ((String) item).length() > MAX_STRING_LENGTH) {
                    throw new IllegalArgumentException("bad set item");
                }
                set.add((String) item);
            }
            return set;
        }
        throw new IllegalArgumentException("unknown type");
    }

    private static PendingNamespace validateNamespace(String name, JSONObject data) throws Exception {
        if (!PORTABLE_NAMESPACE_SET.contains(name) || data == null || data.length() > MAX_KEYS_PER_NAMESPACE) {
            throw new IllegalArgumentException("invalid namespace");
        }
        PendingNamespace pending = new PendingNamespace(name);
        Iterator<String> keys = data.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object node = data.get(key);
            if (!(node instanceof JSONObject)) throw new IllegalArgumentException("bad tagged value");
            JSONObject tagged = (JSONObject) node;
            if (tagged.length() != 2 || !tagged.has("t") || !tagged.has("v")) {
                throw new IllegalArgumentException("bad tagged schema");
            }
            Object typeObject = tagged.get("t");
            if (!(typeObject instanceof String)) throw new IllegalArgumentException("bad type");
            String expected = expectedType(name, key);
            if (expected == null && PREFS_PLUGINS.equals(name) && key.startsWith("plugin_setting_")
                    && SAFE_KEY.matcher(key).matches() && !isSensitiveKey(key)) {
                expected = (String) typeObject;
            }
            if (expected == null) continue; 
            if (!expected.equals(typeObject)) {
                throw new IllegalArgumentException("type mismatch");
            }
            pending.values.put(key, decodeValue(expected, tagged.get("v")));
        }
        return pending;
    }

    private static void put(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
        else if (value instanceof String) editor.putString(key, (String) value);
        else if (value instanceof Set) //noinspection unchecked
            editor.putStringSet(key, new HashSet<>((Set<String>) value));
    }

    private static Map<String, Object> portableSnapshot(String namespace) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, ?> entry : prefs(namespace).getAll().entrySet()) {
            if (expectedType(namespace, entry.getKey(), entry.getValue()) != null) {
                Object value = entry.getValue();
                if (value instanceof Set) value = new HashSet<>((Set<?>) value);
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    private static boolean replaceNamespace(PendingNamespace pending) {
        SharedPreferences preferences = prefs(pending.name);
        SharedPreferences.Editor editor = preferences.edit();
        Map<String, ?> current = preferences.getAll();
        for (Map.Entry<String, ?> entry : current.entrySet()) {
            if (expectedType(pending.name, entry.getKey(), entry.getValue()) != null) {
                editor.remove(entry.getKey());
            }
        }
        for (Map.Entry<String, Object> entry : pending.values.entrySet()) put(editor, entry.getKey(), entry.getValue());
        return editor.commit() && pending.values.equals(portableSnapshot(pending.name));
    }

    private static boolean restoreSnapshot(String namespace, Map<String, Object> snapshot) {
        SharedPreferences preferences = prefs(namespace);
        SharedPreferences.Editor editor = preferences.edit();
        Map<String, ?> current = preferences.getAll();
        for (Map.Entry<String, ?> entry : current.entrySet()) {
            if (expectedType(namespace, entry.getKey(), entry.getValue()) != null) {
                editor.remove(entry.getKey());
            }
        }
        for (Map.Entry<String, Object> entry : snapshot.entrySet()) put(editor, entry.getKey(), entry.getValue());
        return editor.commit() && snapshot.equals(portableSnapshot(namespace));
    }

    private static boolean restoreAllSnapshots(List<String> names,
                                               Map<String, Map<String, Object>> snapshots) {
        boolean restored = true;
        HashSet<String> seen = new HashSet<>();
        for (int i = names.size() - 1; i >= 0; i--) {
            String name = names.get(i);
            if (seen.add(name)) {
                Map<String, Object> snapshot = snapshots == null ? null : snapshots.get(name);
                if (snapshot == null) {
                    restored = false;
                    FileLog.e("Nimarko config rollback snapshot missing for " + name);
                    continue;
                }
                try {
                    if (!restoreSnapshot(name, snapshot)) {
                        restored = false;
                        FileLog.e("Nimarko config rollback verification failed for " + name);
                    }
                } catch (Throwable t) {
                    restored = false;
                    FileLog.e("Nimarko config rollback threw for " + name, t);
                }
            }
        }
        return restored;
    }

    private static boolean rollbackImport(Throwable failure, List<String> touched,
                                          Map<String, Map<String, Object>> snapshots,
                                          PreparedPythonReplacement preparedPython,
                                          boolean pythonCommitAttempted,
                                          boolean reloadPythonSettings) {
        boolean preferencesRestored = restoreAllSnapshots(touched, snapshots);
        boolean pythonRestored = true;
        if (preparedPython != null) {
            pythonRestored = pythonCommitAttempted
                    ? preparedPython.rollback()
                    : preparedPython.abort();
        }
        boolean pythonReloaded = true;
        if (reloadPythonSettings) {
            try {
                pythonReloaded = app.nimarkogram.messenger.plugins.PluginsController.reloadPortablePluginSettings();
            } catch (Throwable t) {
                pythonReloaded = false;
                FileLog.e("Nimarko config rollback could not reload restored plugin settings", t);
            }
        }
        if (!preferencesRestored || !pythonRestored || !pythonReloaded) {
            FileLog.e("Nimarko config import ROLLBACK FAILED: preferences=" + preferencesRestored
                    + ", python=" + pythonRestored + ", reload=" + pythonReloaded, failure);
            return false;
        }
        FileLog.e("Nimarko config import failed; verified rollback completed", failure);
        return true;
    }

    public static boolean importJson(String json) {
        try {
            return app.nimarkogram.messenger.plugins.PluginsController
                    .withPortablePluginSettingsTransaction(() ->
                            NimarkoConfig.withSettingsTransaction(() -> importJsonLocked(json)));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            FileLog.e("Nimarko config import settings transaction failed", e);
            return false;
        }
    }

    private static boolean importJsonLocked(String json) {
        if (json == null || json.length() == 0 || !withinSizeLimit(json)) return false;
        Map<String, Map<String, Object>> snapshots = null;
        PreparedPythonReplacement preparedPython = null;
        ArrayList<String> touched = new ArrayList<>();
        boolean mutationStarted = false;
        boolean pythonCommitAttempted = false;
        boolean pythonSettingsReloaded = false;
        int version = 0;
        try {
            JSONObject root = new JSONObject(json.trim());
            if (!MAGIC.equals(root.optString("magic", null))) return false;
            Object versionObject = root.opt("version");
            if (!(versionObject instanceof Integer)) return false;
            version = (Integer) versionObject;
            if (version < 1 || version > VERSION) return false;

            JSONObject pendingPortablePythonSettings = null;
            if (version >= 4) {
                Object pythonNode = root.opt(PYTHON_PLUGIN_SETTINGS);
                if (!(pythonNode instanceof JSONObject)) return false;
                int[] pluginValueCount = {0};
                Object sanitized = sanitizePluginValue(pythonNode, null, 0, pluginValueCount);
                if (!(sanitized instanceof JSONObject)) return false;
                pendingPortablePythonSettings = (JSONObject) sanitized;
            }

            ArrayList<PendingNamespace> pending = new ArrayList<>();
            if (version == 1) {
                JSONObject data = root.optJSONObject("data");
                if (data == null) return false;
                pending.add(validateNamespace(PREFS_MAIN, data));
            } else {
                JSONObject namespaces = root.optJSONObject("namespaces");
                if (namespaces == null || namespaces.length() > MAX_NAMESPACES) return false;
                Iterator<String> names = namespaces.keys();
                while (names.hasNext()) {
                    String name = names.next();
                    if (!PORTABLE_NAMESPACE_SET.contains(name)) return false;
                    pending.add(validateNamespace(name, namespaces.optJSONObject(name)));
                }
                
                if (version >= 3) {
                    List<String> fullSnapshotNamespaces = version >= 4
                            ? PORTABLE_NAMESPACES
                            : PORTABLE_NAMESPACES.subList(0, PORTABLE_NAMESPACES.size() - 1);
                    for (String name : fullSnapshotNamespaces) {
                        boolean found = false;
                        for (PendingNamespace p : pending) if (name.equals(p.name)) { found = true; break; }
                        if (!found) pending.add(new PendingNamespace(name));
                    }
                }
            }

            snapshots = new HashMap<>();
            for (PendingNamespace p : pending) snapshots.put(p.name, portableSnapshot(p.name));
            JSONObject mergedPythonSettings = version >= 4
                    ? mergePortablePythonPluginSettings(pendingPortablePythonSettings)
                    : null;
            preparedPython = mergedPythonSettings == null
                    ? null
                    : PreparedPythonReplacement.prepare(
                            mergedPythonSettings.toString().getBytes(StandardCharsets.UTF_8));
            if (version >= 4 && preparedPython == null) return false;
            for (PendingNamespace p : pending) {
                
                touched.add(p.name);
                mutationStarted = true;
                if (!replaceNamespace(p)) {
                    throw new IllegalStateException("preferences commit verification failed for " + p.name);
                }
            }
            if (preparedPython != null) {
                pythonCommitAttempted = true;
                if (!preparedPython.commit()) {
                    throw new IllegalStateException("Python settings commit verification failed");
                }
            }
            if (version >= 4) {
                pythonSettingsReloaded = true;
                if (!app.nimarkogram.messenger.plugins.PluginsController.reloadPortablePluginSettings()) {
                    throw new IllegalStateException("Python plugin settings reload failed");
                }
            }
            if (preparedPython != null && !preparedPython.finish()) {
                throw new IllegalStateException("Python settings recovery cleanup failed");
            }
        } catch (Throwable failure) {
            if (mutationStarted) {
                boolean rollbackSucceeded = rollbackImport(failure, touched, snapshots, preparedPython,
                        pythonCommitAttempted, version >= 4 && pythonSettingsReloaded);
                if (!rollbackSucceeded) {
                    
                    throw new IllegalStateException("Nimarko config import rollback failed", failure);
                }
            } else if (preparedPython != null && !preparedPython.abort()) {
                FileLog.e("Nimarko config import staging cleanup failed", failure);
            } else {
                FileLog.e("Nimarko config import rejected before mutation", failure);
            }
            return false;
        }
        try {
            AppRestartHelper.triggerRebirth(null);
        } catch (Throwable t) {
            
            FileLog.e("Nimarko config import committed but restart failed", t);
        }
        return true;
    }

    public static boolean looksLikeBackup(String value) {
        if (value == null || value.length() == 0 || !withinSizeLimit(value)) return false;
        try {
            JSONObject root = new JSONObject(value.trim());
            return MAGIC.equals(root.optString("magic", null));
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static boolean withinSizeLimit(String value) {
        
        return value != null && value.length() <= MAX_BACKUP_BYTES
                && value.getBytes(StandardCharsets.UTF_8).length <= MAX_BACKUP_BYTES;
    }

    private static File pythonSettingsFile() {
        return new File(new File(ApplicationLoader.applicationContext.getFilesDir(), "plugins"), "plugin_settings.json");
    }

    private static JSONObject readPythonPluginSettings() throws Exception {
        byte[] bytes = readPythonSettingsBytes();
        return bytes == null || bytes.length == 0
                ? new JSONObject() : new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    private static JSONObject readPortablePythonPluginSettings() throws Exception {
        JSONObject source = readPythonPluginSettings();
        int[] count = {0};
        Object portable = sanitizePluginValue(source, null, 0, count);
        if (!(portable instanceof JSONObject)) throw new IllegalArgumentException("invalid plugin settings");
        return (JSONObject) portable;
    }

    private static JSONObject mergePortablePythonPluginSettings(JSONObject importedPortable) throws Exception {
        if (importedPortable == null) throw new IllegalArgumentException("missing plugin settings");
        JSONObject current = readPythonPluginSettings();
        if (current.length() > MAX_KEYS_PER_NAMESPACE
                || importedPortable.length() > MAX_KEYS_PER_NAMESPACE) {
            throw new IllegalArgumentException("too many plugin buckets");
        }

        JSONObject merged = new JSONObject();
        Iterator<String> importedIds = importedPortable.keys();
        while (importedIds.hasNext()) {
            String pluginId = importedIds.next();
            Object bucket = importedPortable.get(pluginId);
            if (!(bucket instanceof JSONObject)) {
                throw new IllegalArgumentException("invalid imported plugin bucket");
            }
            merged.put(pluginId, bucket);
        }

        int[] localValueCount = {0};
        Iterator<String> currentIds = current.keys();
        while (currentIds.hasNext()) {
            String pluginId = currentIds.next();
            Object rawBucket = current.get(pluginId);
            if (!(rawBucket instanceof JSONObject)) {
                throw new IllegalArgumentException("invalid local plugin bucket");
            }
            JSONObject currentBucket = (JSONObject) rawBucket;
            if (currentBucket.length() > MAX_KEYS_PER_NAMESPACE) {
                throw new IllegalArgumentException("plugin object too large");
            }

            if (!SAFE_KEY.matcher(pluginId).matches() || isNonPortablePluginId(pluginId)) {
                validateAndDetectNonPortablePluginValue(
                        currentBucket, pluginId, 1, localValueCount);
                merged.put(pluginId, currentBucket);
                continue;
            }

            JSONObject target = merged.optJSONObject(pluginId);
            boolean importedBucketExists = target != null;
            if (target == null) target = new JSONObject();
            Iterator<String> settingKeys = currentBucket.keys();
            while (settingKeys.hasNext()) {
                String settingKey = settingKeys.next();
                Object localValue = currentBucket.get(settingKey);
                boolean importedValueExists = target.has(settingKey);
                Object importedValue = importedValueExists
                        ? target.get(settingKey) : null;
                PluginMergeValue result = mergePluginValue(
                        localValue, importedValue, importedValueExists,
                        settingKey, 2, localValueCount);
                if (result.hasLocalNonPortable) {
                    target.put(settingKey, result.value);
                }
            }
            if (target.length() > 0 || importedBucketExists) {
                merged.put(pluginId, target);
            }
        }
        if (merged.length() > MAX_KEYS_PER_NAMESPACE) {
            throw new IllegalArgumentException("too many merged plugin buckets");
        }
        int[] mergedValueCount = {0};
        validateAndDetectNonPortablePluginValue(
                merged, null, 0, mergedValueCount);
        return merged;
    }

    private static final class PluginMergeValue {
        final Object value;
        final boolean hasLocalNonPortable;

        PluginMergeValue(Object value, boolean hasLocalNonPortable) {
            this.value = value;
            this.hasLocalNonPortable = hasLocalNonPortable;
        }
    }

    private static PluginMergeValue mergePluginValue(
            Object localValue, Object importedValue, boolean importedValueExists,
            String key, int depth, int[] count) throws Exception {
        if (depth > MAX_PLUGIN_DEPTH || ++count[0] > MAX_PLUGIN_VALUES) {
            throw new IllegalArgumentException("plugin settings too complex");
        }
        boolean keyNonPortable = key != null && (!SAFE_KEY.matcher(key).matches()
                || depth >= 2 && (isSensitiveKey(key) || isPluginRuntimeMetadataKey(key)));
        if (keyNonPortable) {
            validatePluginDescendants(localValue, depth, count);
            return new PluginMergeValue(localValue, true);
        }

        if (localValue == JSONObject.NULL) {
            return new PluginMergeValue(importedValue, false);
        }
        if (localValue instanceof JSONObject) {
            JSONObject localObject = (JSONObject) localValue;
            if (localObject.length() > MAX_KEYS_PER_NAMESPACE) {
                throw new IllegalArgumentException("plugin object too large");
            }
            JSONObject importedObject = importedValueExists && importedValue instanceof JSONObject
                    ? (JSONObject) importedValue : null;
            JSONObject target = importedObject != null ? importedObject : new JSONObject();
            boolean hasNonPortable = false;
            Iterator<String> keys = localObject.keys();
            while (keys.hasNext()) {
                String childKey = keys.next();
                boolean childImported = importedObject != null && importedObject.has(childKey);
                PluginMergeValue child = mergePluginValue(
                        localObject.get(childKey),
                        childImported ? importedObject.get(childKey) : null,
                        childImported, childKey, depth + 1, count);
                if (child.hasLocalNonPortable) {
                    if (importedValueExists && importedObject == null) {
                        throw new IllegalArgumentException(
                                "plugin setting type conflicts with local protected data");
                    }
                    target.put(childKey, child.value);
                    hasNonPortable = true;
                }
            }
            return hasNonPortable
                    ? new PluginMergeValue(target, true)
                    : new PluginMergeValue(importedValue, false);
        }
        if (localValue instanceof JSONArray) {
            JSONArray localArray = (JSONArray) localValue;
            if (localArray.length() > MAX_SET_ITEMS) {
                throw new IllegalArgumentException("plugin array too large");
            }
            JSONArray importedArray = importedValueExists && importedValue instanceof JSONArray
                    ? (JSONArray) importedValue : null;
            JSONArray target = importedArray;
            boolean hasNonPortable = false;
            for (int i = 0; i < localArray.length(); i++) {
                boolean childImported = importedArray != null && i < importedArray.length();
                PluginMergeValue child = mergePluginValue(
                        localArray.get(i),
                        childImported ? importedArray.get(i) : null,
                        childImported, null, depth + 1, count);
                if (!child.hasLocalNonPortable) continue;
                if (importedArray == null || i > importedArray.length()) {
                    throw new IllegalArgumentException(
                            "plugin array shape conflicts with local protected data");
                }
                if (i == importedArray.length()) importedArray.put(child.value);
                else importedArray.put(i, child.value);
                hasNonPortable = true;
            }
            if (hasNonPortable) {
                if (importedValueExists && !(importedValue instanceof JSONArray)) {
                    throw new IllegalArgumentException(
                            "plugin setting type conflicts with local protected data");
                }
                return new PluginMergeValue(target, true);
            }
            return new PluginMergeValue(importedValue, false);
        }
        validatePluginPrimitive(localValue);
        return new PluginMergeValue(importedValue, false);
    }

    private static void validatePluginDescendants(
            Object value, int depth, int[] count) throws Exception {
        if (value == JSONObject.NULL) return;
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if (object.length() > MAX_KEYS_PER_NAMESPACE) {
                throw new IllegalArgumentException("plugin object too large");
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String childKey = keys.next();
                validateAndDetectNonPortablePluginValue(
                        object.get(childKey), childKey, depth + 1, count);
            }
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            if (array.length() > MAX_SET_ITEMS) {
                throw new IllegalArgumentException("plugin array too large");
            }
            for (int i = 0; i < array.length(); i++) {
                validateAndDetectNonPortablePluginValue(
                        array.get(i), null, depth + 1, count);
            }
            return;
        }
        validatePluginPrimitive(value);
    }

    private static void validatePluginPrimitive(Object value) {
        if (value == JSONObject.NULL
                || value instanceof Boolean
                || value instanceof Integer
                || value instanceof Long) {
            return;
        }
        if (value instanceof String) {
            if (((String) value).length() > MAX_STRING_LENGTH) {
                throw new IllegalArgumentException("plugin string too large");
            }
            return;
        }
        if (value instanceof Number) {
            if (!Double.isFinite(((Number) value).doubleValue())) {
                throw new IllegalArgumentException("invalid plugin number");
            }
            return;
        }
        throw new IllegalArgumentException("invalid plugin value");
    }

    private static Object sanitizePluginValue(Object value, String key, int depth, int[] count) throws Exception {
        if (depth > MAX_PLUGIN_DEPTH || ++count[0] > MAX_PLUGIN_VALUES) {
            throw new IllegalArgumentException("plugin settings too complex");
        }
        if (key != null && !SAFE_KEY.matcher(key).matches()) return null;
        if (key != null && depth == 1 && isNonPortablePluginId(key)) return null;
        
        if (key != null && depth >= 2 && (isSensitiveKey(key) || isPluginRuntimeMetadataKey(key))) return null;
        if (value == JSONObject.NULL) return JSONObject.NULL;
        if (value instanceof JSONObject) {
            JSONObject source = (JSONObject) value;
            if (source.length() > MAX_KEYS_PER_NAMESPACE) throw new IllegalArgumentException("plugin object too large");
            JSONObject out = new JSONObject();
            Iterator<String> keys = source.keys();
            while (keys.hasNext()) {
                String childKey = keys.next();
                Object sourceChild = source.get(childKey);
                if (depth == 0 && !(sourceChild instanceof JSONObject)) {
                    throw new IllegalArgumentException("invalid plugin bucket");
                }
                Object child = sanitizePluginValue(sourceChild, childKey, depth + 1, count);
                if (child != null) out.put(childKey, child);
            }
            return out;
        }
        if (value instanceof JSONArray) {
            JSONArray source = (JSONArray) value;
            if (source.length() > MAX_SET_ITEMS) throw new IllegalArgumentException("plugin array too large");
            JSONArray out = new JSONArray();
            for (int i = 0; i < source.length(); i++) {
                Object child = sanitizePluginValue(source.get(i), null, depth + 1, count);
                if (child == null) throw new IllegalArgumentException("invalid plugin array value");
                out.put(child);
            }
            return out;
        }
        if (value instanceof String) {
            if (((String) value).length() > MAX_STRING_LENGTH) throw new IllegalArgumentException("plugin string too large");
            return value;
        }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long) return value;
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) throw new IllegalArgumentException("invalid plugin number");
            return value;
        }
        throw new IllegalArgumentException("invalid plugin value");
    }

    private static boolean validateAndDetectNonPortablePluginValue(
            Object value, String key, int depth, int[] count) throws Exception {
        if (depth > MAX_PLUGIN_DEPTH || ++count[0] > MAX_PLUGIN_VALUES) {
            throw new IllegalArgumentException("plugin settings too complex");
        }
        boolean nonPortable = key != null && (!SAFE_KEY.matcher(key).matches()
                || depth >= 2 && (isSensitiveKey(key) || isPluginRuntimeMetadataKey(key)));
        if (value == JSONObject.NULL) return nonPortable;
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if (object.length() > MAX_KEYS_PER_NAMESPACE) {
                throw new IllegalArgumentException("plugin object too large");
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String childKey = keys.next();
                nonPortable |= validateAndDetectNonPortablePluginValue(
                        object.get(childKey), childKey, depth + 1, count);
            }
            return nonPortable;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            if (array.length() > MAX_SET_ITEMS) {
                throw new IllegalArgumentException("plugin array too large");
            }
            for (int i = 0; i < array.length(); i++) {
                nonPortable |= validateAndDetectNonPortablePluginValue(
                        array.get(i), null, depth + 1, count);
            }
            return nonPortable;
        }
        if (value instanceof String) {
            if (((String) value).length() > MAX_STRING_LENGTH) {
                throw new IllegalArgumentException("plugin string too large");
            }
            return nonPortable;
        }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            return nonPortable;
        }
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException("invalid plugin number");
            }
            return nonPortable;
        }
        throw new IllegalArgumentException("invalid plugin value");
    }

    private static boolean isNonPortablePluginId(String pluginId) {
        return pluginId != null && NON_PORTABLE_PLUGIN_IDS.contains(pluginId);
    }

    private static boolean isPluginRuntimeMetadataKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.equals("runtime") || normalized.startsWith("runtime_")
                || normalized.endsWith("_runtime") || normalized.contains("quarantine")
                || normalized.equals("crash") || normalized.startsWith("crash_")
                || normalized.endsWith("_crash") || normalized.startsWith("last_crash");
    }

    private static byte[] readPythonSettingsBytes() throws Exception {
        File file = pythonSettingsFile();
        if (!file.exists()) return null;
        long length = file.length();
        if (length < 0 || length > MAX_BACKUP_BYTES) throw new IllegalArgumentException("plugin settings too large");
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(length, 8192))) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BACKUP_BYTES) throw new IllegalArgumentException("plugin settings too large");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static final class PreparedPythonReplacement {
        final File target;
        final File staged;
        final File rollback;
        final byte[] wanted;
        final byte[] original;
        boolean targetMoved;
        boolean replacementMoved;
        boolean replacementInstalled;
        boolean mutationPossible;

        private PreparedPythonReplacement(File target, File staged, File rollback,
                                          byte[] wanted, byte[] original) {
            this.target = target;
            this.staged = staged;
            this.rollback = rollback;
            this.wanted = wanted;
            this.original = original;
        }

        static PreparedPythonReplacement prepare(byte[] bytes) {
            if (bytes == null || bytes.length > MAX_BACKUP_BYTES) return null;
            File target = pythonSettingsFile();
            File parent = target.getParentFile();
            if (parent == null || !parent.exists() && !parent.mkdirs() || !parent.isDirectory()) return null;
            File staged = null;
            File rollback = null;
            try {
                byte[] original = readPythonSettingsBytes();
                staged = writeSyncedTemp(parent, ".plugin_settings.import.", bytes);
                if (!fileEquals(staged, bytes)) throw new IllegalStateException("staging verification failed");
                if (original != null) {
                    rollback = writeSyncedTemp(parent, ".plugin_settings.rollback.", original);
                    if (!fileEquals(rollback, original)) throw new IllegalStateException("rollback verification failed");
                }
                return new PreparedPythonReplacement(target, staged, rollback, bytes, original);
            } catch (Throwable ignore) {
                deleteAndVerify(staged);
                deleteAndVerify(rollback);
                return null;
            }
        }

        boolean commit() {
            try {
                if (original != null) {
                    if (!fileEquals(target, original)) return false;
                    mutationPossible = true;
                    android.system.Os.rename(target.getAbsolutePath(), rollback.getAbsolutePath());
                    targetMoved = !target.exists() && fileEquals(rollback, original);
                    if (!targetMoved) return false;
                } else if (target.exists()) {
                    
                    return false;
                }
                mutationPossible = true;
                android.system.Os.rename(staged.getAbsolutePath(), target.getAbsolutePath());
                replacementMoved = !staged.exists();
                replacementInstalled = replacementMoved && fileEquals(target, wanted);
                return replacementInstalled;
            } catch (Throwable ignore) {
                return false;
            }
        }

        boolean rollback() {
            if (!mutationPossible) {
                return abort();
            }
            boolean ok = true;
            if (original != null && rollback != null && rollback.exists()) {
                try {
                    
                    android.system.Os.rename(rollback.getAbsolutePath(), target.getAbsolutePath());
                    ok &= fileEquals(target, original) && !rollback.exists();
                } catch (Throwable ignore) {
                    ok = false;
                }
            } else if (original != null) {
                ok = false;
            } else if (target.exists()) {
                if (replacementMoved || replacementInstalled || fileEquals(target, wanted)) {
                    ok &= deleteAndVerify(target);
                } else {
                    
                    ok = false;
                }
            }
            ok &= deleteAndVerify(staged);
            ok &= original != null ? fileEquals(target, original) : !target.exists();
            return ok;
        }

        boolean finish() {
            return !staged.exists() && fileEquals(target, wanted) && deleteAndVerify(rollback);
        }

        boolean abort() {
            boolean stagedDeleted = deleteAndVerify(staged);
            boolean rollbackDeleted = deleteAndVerify(rollback);
            return stagedDeleted && rollbackDeleted;
        }
    }

    private static File writeSyncedTemp(File parent, String prefix, byte[] bytes) throws Exception {
        File temp = File.createTempFile(prefix, ".tmp", parent);
        boolean complete = false;
        try (FileOutputStream output = new FileOutputStream(temp)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
            complete = true;
        } finally {
            if (!complete) deleteAndVerify(temp);
        }
        return temp;
    }

    private static boolean fileEquals(File file, byte[] expected) {
        if (file == null || expected == null || !file.isFile() || file.length() != expected.length) return false;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int offset = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (offset + read > expected.length) return false;
                for (int i = 0; i < read; i++) if (buffer[i] != expected[offset + i]) return false;
                offset += read;
            }
            return offset == expected.length;
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static boolean deleteAndVerify(File file) {
        return file == null || !file.exists() || file.delete() && !file.exists();
    }

    private NimarkoConfigBackup() {}
}
