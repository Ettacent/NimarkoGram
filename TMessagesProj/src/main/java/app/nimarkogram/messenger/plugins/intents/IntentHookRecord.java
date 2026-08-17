package app.nimarkogram.messenger.plugins.intents;

import android.text.TextUtils;

import com.chaquo.python.PyObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.nimarkogram.messenger.plugins.utils.PyObjectUtils;
import app.nimarkogram.messenger.plugins.PluginsController;

public class IntentHookRecord {
    public final String pluginId;
    public final String handlerId;
    public final String name;
    public final int priority;
    public final PluginsController.PluginRuntimeToken runtimeToken;

    private final String scheme;
    private final String host;
    private final String action;
    private final String mime;
    private final String pathExact;       
    private final Pattern pathPattern;    
    private final List<String> pathArgNames;
    private final Set<String> categories;
    private final int whitelistFlags;
    private final int blacklistFlags;
    private final List<String> requiredPathArgs;
    private final List<String> requiredQueryArgs;

    public IntentHookRecord(PyObject f) {
        this.runtimeToken = PluginsController.getInstance().captureCurrentPluginRuntime();
        this.pluginId = nullIfEmpty(PyObjectUtils.getString(f, "plugin_id", null, true));
        String hid = PyObjectUtils.getString(f, "handler_id", null, true);
        this.handlerId = TextUtils.isEmpty(hid) ? UUID.randomUUID().toString() : hid;
        this.name = PyObjectUtils.getString(f, "name", null, true);
        this.priority = PyObjectUtils.getInt(f, "priority", 0, true);

        this.scheme = nullIfEmpty(PyObjectUtils.getString(f, "scheme", null, true));
        this.host = nullIfEmpty(PyObjectUtils.getString(f, "host", null, true));
        this.action = nullIfEmpty(PyObjectUtils.getString(f, "action", null, true));
        this.mime = nullIfEmpty(PyObjectUtils.getString(f, "type", null, true));
        this.whitelistFlags = PyObjectUtils.getInt(f, "whitelist_flags", 0, true);
        this.blacklistFlags = PyObjectUtils.getInt(f, "blacklist_flags", 0, true);
        this.categories = new HashSet<>(Arrays.asList(
                PyObjectUtils.getStringArray(f, "categories", new String[0])));
        this.requiredPathArgs = Arrays.asList(
                PyObjectUtils.getStringArray(f, "required_path_args_names", new String[0]));
        this.requiredQueryArgs = Arrays.asList(
                PyObjectUtils.getStringArray(f, "required_query_args", new String[0]));

        String pathRegex = nullIfEmpty(PyObjectUtils.getString(f, "path_regex", null, true));
        String path = nullIfEmpty(PyObjectUtils.getString(f, "path", null, true));
        List<String> argNames = new ArrayList<>();
        Pattern pat = null;
        String exact = null;
        try {
            if (pathRegex != null) {
                pat = Pattern.compile(pathRegex);
                argNames = extractGroupNames(pathRegex);
            } else if (path != null && path.indexOf('{') >= 0) {
                pat = buildTemplatePattern(path, argNames);
            } else {
                exact = path;
            }
        } catch (Exception e) {
            
            exact = path;
            pat = null;
        }
        this.pathPattern = pat;
        this.pathArgNames = argNames;
        this.pathExact = exact;
    }

    public Map<String, String> match(String action, String scheme, String host, String path,
                                     Map<String, String> queryArgs, Set<String> cats,
                                     int flags, String mime) {
        if (this.action != null && !this.action.equals(action)) return null;
        if (this.scheme != null && !this.scheme.equals(scheme)) return null;
        if (this.host != null && !this.host.equals(host)) return null;
        if (this.mime != null && !this.mime.equals(mime)) return null;

        Map<String, String> pathArgs = new HashMap<>();
        if (pathPattern != null) {
            if (path == null) return null;
            Matcher m = pathPattern.matcher(path);
            if (!m.matches()) return null;
            for (String gn : pathArgNames) {
                try {
                    String v = m.group(gn);
                    if (v != null) pathArgs.put(gn, v);
                } catch (Exception ignored) {}
            }
        } else if (pathExact != null) {
            if (!pathExact.equals(path)) return null;
        }

        for (String rp : requiredPathArgs) {
            if (!pathArgs.containsKey(rp)) return null;
        }
        for (String rq : requiredQueryArgs) {
            if (queryArgs == null || !queryArgs.containsKey(rq)) return null;
        }
        if (!categories.isEmpty() && (cats == null || !cats.containsAll(categories))) return null;
        if (whitelistFlags != 0 && (flags & whitelistFlags) != whitelistFlags) return null;
        if (blacklistFlags != 0 && (flags & blacklistFlags) != 0) return null;
        return pathArgs;
    }

    private static String nullIfEmpty(String s) {
        return TextUtils.isEmpty(s) ? null : s;
    }

    private static Pattern buildTemplatePattern(String path, List<String> outArgNames) {
        StringBuilder sb = new StringBuilder("^");
        Matcher m = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)}").matcher(path);
        int last = 0;
        while (m.find()) {
            sb.append(Pattern.quote(path.substring(last, m.start())));
            String gn = m.group(1);
            outArgNames.add(gn);
            sb.append("(?<").append(gn).append(">[^/]+)");
            last = m.end();
        }
        sb.append(Pattern.quote(path.substring(last)));
        sb.append("$");
        return Pattern.compile(sb.toString());
    }

    private static List<String> extractGroupNames(String regex) {
        List<String> names = new ArrayList<>();
        Matcher m = Pattern.compile("\\(\\?<([A-Za-z][A-Za-z0-9]*)>").matcher(regex);
        while (m.find()) names.add(m.group(1));
        return names;
    }
}
