package app.nimarkogram.messenger.plugins.pip;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.nimarkogram.messenger.plugins.PluginsController;
import app.nimarkogram.messenger.plugins.PythonPluginsEngine;
import app.nimarkogram.messenger.plugins.utils.FileUtils;
import org.telegram.messenger.FileLog;

public final class PipController {

    private static final PipController INSTANCE = new PipController();
    public static PipController getInstance() { return INSTANCE; }
    public static final PipController INSTANCE_PROP = INSTANCE; 

    private static final String PYPI_BASE = "https://pypi.org/pypi";
    private static final String USER_AGENT = "NimarkoGram/3.3 (Chaquopy pip-resolver)";
    private static final String DEFAULT_PYTHON_VERSION = "3.11";
    private static final String DEFAULT_PYTHON_FULL_VERSION = "3.11.0";
    private static final String DEFAULT_PLATFORM_MACHINE = "unknown";

    private final Gson gson = new Gson();
    private final ConcurrentHashMap<String, Object> installLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Set<String>>> registry = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> preinstalledVersions =
            new ConcurrentHashMap<>();
    private volatile String pythonVersion = DEFAULT_PYTHON_VERSION;
    private volatile String pythonFullVersion =
            DEFAULT_PYTHON_FULL_VERSION;
    private volatile String platformMachine =
            DEFAULT_PLATFORM_MACHINE;
    private static final AtomicBoolean PIP_RESTART_REQUIRED =
            new AtomicBoolean(false);
    private static final ThreadLocal<Integer> PIP_MUTATION_DEPTH =
            new ThreadLocal<>();

    private static com.chaquo.python.Python getStartedPython() {
        if (!isPythonRuntimeUsable()) {
            throw new IllegalStateException("Python plugin engine is not ready");
        }
        return com.chaquo.python.Python.getInstance();
    }

    private static boolean isPythonRuntimeUsable() {
        return com.chaquo.python.Python.isStarted()
                && !PythonPluginsEngine
                        .isProcessPythonRuntimeAbandoned();
    }

    private static final Pattern REGEX_NORMALIZE = Pattern.compile("[-_.]+");
    private static final Pattern REGEX_REQ_PARSE = Pattern.compile(
            "^\\s*([A-Za-z0-9][A-Za-z0-9._-]*)\\s*(?:\\[([^\\]]*)\\])?\\s*([^;]*)?(?:;\\s*(.+))?$");
    private static final Pattern REGEX_REQ_SPECS = Pattern.compile(
            "(===|==|!=|<=|>=|~=|<|>)\\s*([A-Za-z0-9.+!_*-]+)");
    private static final Pattern ARTIFACT_BACKUP_PATTERN = Pattern.compile(
            "^\\.(.+)\\.([0-9a-f]+)\\.backup$");
    private static final Pattern ARTIFACT_STAGE_PATTERN = Pattern.compile(
            "^\\.(.+)\\.([0-9a-f]+)\\.(wheel|extract)\\.stage$");
    private static final Pattern ARTIFACT_DOWNLOAD_PART_PATTERN =
            Pattern.compile(
                    "^\\.([A-Za-z0-9][A-Za-z0-9._+!-]{0,511}"
                            + "\\.whl)\\.([0-9a-f]{8,40})"
                            + "\\.wheel\\.stage\\.part$");
    private static final Pattern RESOLVER_WHEEL_PATTERN =
            Pattern.compile(
                    "^\\.resolve-([0-9a-f]{64})\\.whl(?:\\.part)?$");
    private static final Pattern REGISTRY_STAGE_PATTERN =
            Pattern.compile(
                    "^\\.registry\\.[0-9a-f]+-[01]\\.stage$");
    private static final Pattern ARTIFACT_JOURNAL_STAGE_PATTERN =
            Pattern.compile(
                    "^\\.plugin-update-"
                            + "([a-zA-Z][a-zA-Z0-9_-]{1,31})"
                            + "\\.artifacts\\.json\\.new\\.[0-9a-f]+$");
    private static final Pattern DEPENDENCY_SNAPSHOT_STAGE_PATTERN =
            Pattern.compile(
                    "^\\.([a-zA-Z][a-zA-Z0-9_-]{1,31})"
                            + "\\.py\\.update\\.deps\\.new\\.[0-9a-f]+$");
    private static final Pattern PLUGIN_ID_PATTERN = Pattern.compile(
            "^[a-zA-Z][a-zA-Z0-9_-]{1,31}$");
    private static final Pattern TRANSACTION_ID_PATTERN = Pattern.compile(
            "^[0-9a-f]{8,40}$");
    private static final Pattern DEFERRED_ARTIFACT_JOURNAL_PATTERN =
            Pattern.compile(
                    "^\\.plugin-update-"
                            + "([a-zA-Z][a-zA-Z0-9_-]{1,31})"
                            + "\\.artifacts\\.json$");
    private static final int LEGACY_TRANSACTION_SCHEMA = 1;
    private static final int TRANSACTION_SCHEMA = 2;
    private static final int REGISTRY_SCHEMA = 2;
    private static final int DEPENDENCY_SNAPSHOT_SCHEMA = 3;
    private static final int MAX_REGISTRY_BYTES = 4 * 1024 * 1024;
    private static final int MAX_TRANSACTION_BYTES = 1024 * 1024;
    private static final int MAX_DISTRIBUTION_METADATA_BYTES =
            2 * 1024 * 1024;
    private static final int MAX_DISTRIBUTION_RECORD_BYTES =
            16 * 1024 * 1024;
    private static final int MAX_WHEEL_METADATA_BYTES =
            2 * 1024 * 1024;
    private static final int MAX_PYPI_JSON_BYTES =
            8 * 1024 * 1024;
    private static final long MAX_WHEEL_DOWNLOAD_BYTES =
            128L * 1024L * 1024L;
    private static final String ARTIFACT_STATE_PREPARED = "PREPARED";
    private static final String ARTIFACT_STATE_REGISTRY_COMMITTED =
            "REGISTRY_COMMITTED";
    private static final Pattern IMPORT_ROOT_PATTERN =
            Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern NATIVE_WHEEL_ENTRY_PATTERN =
            Pattern.compile(
                    "(?i)(?:^|/)[^/]+(?:\\.so(?:\\.[^/]*)?|\\.pyd|\\.dll|\\.dylib)$");
    private static final String PYTHON_RUNTIME_TRANSITION_HELPER =
            "import _imp as _pip_imp\n"
            + "def transition(payload):\n"
            + " _pip_imp.acquire_lock()\n"
            + " before_path = None\n"
            + " module_snapshot = []\n"
            + " validated = []\n"
            + " parent_attrs = []\n"
            + " namespace_snapshot = []\n"
            + " changed = False\n"
            + " try:\n"
            + "  import importlib, json, os, sys\n"
            + "  data = json.loads(payload)\n"
            + "  root = os.path.realpath(data['managed_root'])\n"
            + "  prefix = root + os.sep\n"
            + "  expected = data['expected_paths']\n"
            + "  desired = data['desired_paths']\n"
            + "  removals = data['modules']\n"
            + "  eviction_roots = data['eviction_roots']\n"
            + "  namespace_patches = data['namespace_patches']\n"
            + "  desired_roots = data['desired_roots']\n"
            + "  sentinel = object()\n"
            + "  before_path = list(sys.path)\n"
            + "  def canonical(value):\n"
            + "   return os.path.realpath(value) if isinstance(value, str) else None\n"
            + "  def managed(value):\n"
            + "   candidate = canonical(value)\n"
            + "   return candidate is not None and candidate.startswith(prefix)\n"
            + "  root_metadata = {}\n"
            + "  for item in eviction_roots:\n"
            + "   path = item['path']\n"
            + "   import_roots = set(item['import_roots'])\n"
            + "   if canonical(path) != path or not path.startswith(prefix) or path in root_metadata or not import_roots:\n"
            + "    raise RuntimeError('invalid managed eviction root: ' + str(path))\n"
            + "   root_metadata[path] = import_roots\n"
            + "  desired_metadata = {}\n"
            + "  for item in desired_roots:\n"
            + "   path = item['path']\n"
            + "   import_roots = set(item['import_roots'])\n"
            + "   if canonical(path) != path or path not in desired or path in desired_metadata or not import_roots:\n"
            + "    raise RuntimeError('invalid desired dependency root: ' + str(path))\n"
            + "   desired_metadata[path] = import_roots\n"
            + "  if set(desired_metadata) != set(desired):\n"
            + "   raise RuntimeError('desired dependency metadata mismatch')\n"
            + "  def matching(value):\n"
            + "   candidate = canonical(value)\n"
            + "   if candidate is None:\n"
            + "    return set()\n"
            + "   return {path for path in root_metadata if candidate == path or candidate.startswith(path + os.sep)}\n"
            + "  observed = [canonical(p) for p in sys.path if managed(p)]\n"
            + "  if observed != expected:\n"
            + "   raise RuntimeError('managed sys.path identity changed')\n"
            + "  if len(desired) != len(set(desired)):\n"
            + "   raise RuntimeError('duplicate desired managed path')\n"
            + "  for path in desired:\n"
            + "   if canonical(path) != path or not path.startswith(prefix) or not os.path.isdir(path):\n"
            + "    raise RuntimeError('desired managed path is unavailable: ' + str(path))\n"
            + "  planned = {}\n"
            + "  for removal in removals:\n"
            + "   name = removal['name']\n"
            + "   module = sys.modules.get(name, sentinel)\n"
            + "   if name in planned or module is sentinel or id(module) != removal['identity']:\n"
            + "    raise RuntimeError('module identity changed: ' + str(name))\n"
            + "   planned[name] = module\n"
            + "   validated.append((name, module))\n"
            + "   parent_name, separator, child = name.rpartition('.')\n"
            + "   if separator:\n"
            + "    parent = sys.modules.get(parent_name, sentinel)\n"
            + "    if parent is not sentinel:\n"
            + "     parent_dict = vars(parent)\n"
            + "     old = parent_dict.get(child, sentinel)\n"
            + "     parent_attrs.append((parent_dict, child, old))\n"
            + "  planned_namespace = {}\n"
            + "  for patch in namespace_patches:\n"
            + "   name = patch['name']\n"
            + "   module = sys.modules.get(name, sentinel)\n"
            + "   if name in planned or name in planned_namespace or module is sentinel or id(module) != patch['identity']:\n"
            + "    raise RuntimeError('namespace identity changed: ' + str(name))\n"
            + "   module_dict = vars(module)\n"
            + "   old_path = module_dict.get('__path__', sentinel)\n"
            + "   if old_path is sentinel:\n"
            + "    raise RuntimeError('namespace path disappeared: ' + name)\n"
            + "   observed_paths = [canonical(value) for value in old_path]\n"
            + "   if None in observed_paths or observed_paths != patch['expected_paths']:\n"
            + "    raise RuntimeError('namespace path identity changed: ' + name)\n"
            + "   top_level = name.partition('.')[0]\n"
            + "   replacement = []\n"
            + "   for path in desired:\n"
            + "    if top_level not in desired_metadata[path]:\n"
            + "     continue\n"
            + "    candidate = canonical(os.path.join(path, *name.split('.')))\n"
            + "    if candidate is not None and os.path.isdir(candidate) and candidate not in replacement:\n"
            + "     replacement.append(candidate)\n"
            + "   for value in observed_paths:\n"
            + "    if not matching(value) and value not in replacement:\n"
            + "     replacement.append(value)\n"
            + "   if not replacement:\n"
            + "    raise RuntimeError('namespace would lose every path: ' + name)\n"
            + "   spec = module_dict.get('__spec__')\n"
            + "   old_spec_path = getattr(spec, 'submodule_search_locations', sentinel) if spec is not None else sentinel\n"
            + "   namespace_snapshot.append((module_dict, old_path, spec, old_spec_path))\n"
            + "   planned_namespace[name] = (module, replacement)\n"
            + "  module_snapshot = list(sys.modules.items())\n"
            + "  selected = {}\n"
            + "  selected_namespace = {}\n"
            + "  for name, module in module_snapshot:\n"
            + "   if not isinstance(name, str) or module is None:\n"
            + "    continue\n"
            + "   try:\n"
            + "    module_dict = vars(module)\n"
            + "   except TypeError:\n"
            + "    if name in planned or name in planned_namespace:\n"
            + "     raise RuntimeError('planned module has no identity metadata: ' + name)\n"
            + "    continue\n"
            + "   spec = module_dict.get('__spec__')\n"
            + "   origin = getattr(spec, 'origin', None) if spec is not None else None\n"
            + "   direct = matching(module_dict.get('__file__')) | matching(origin)\n"
            + "   namespace = set()\n"
            + "   namespace_outside = False\n"
            + "   module_path = module_dict.get('__path__')\n"
            + "   if module_path is not None:\n"
            + "    for value in module_path:\n"
            + "     matches = matching(value)\n"
            + "     if matches:\n"
            + "      namespace.update(matches)\n"
            + "     else:\n"
            + "      namespace_outside = True\n"
            + "   matches = direct | namespace\n"
            + "   if not matches:\n"
            + "    continue\n"
            + "   top_level = name.partition('.')[0]\n"
            + "   if not any(top_level in root_metadata[path] for path in matches):\n"
            + "    raise RuntimeError('unproved managed module: ' + name)\n"
            + "   should_remove = bool(direct) or bool(namespace and not namespace_outside)\n"
            + "   if should_remove:\n"
            + "    if planned.get(name, sentinel) is not module:\n"
            + "     raise RuntimeError('managed eviction set changed: ' + name)\n"
            + "    selected[name] = module\n"
            + "   elif namespace and namespace_outside:\n"
            + "    if planned_namespace.get(name, (sentinel,))[0] is not module:\n"
            + "     raise RuntimeError('mixed namespace set changed: ' + name)\n"
            + "    selected_namespace[name] = module\n"
            + "  if set(selected) != set(planned):\n"
            + "   raise RuntimeError('managed eviction provenance changed')\n"
            + "  if set(selected_namespace) != set(planned_namespace):\n"
            + "   raise RuntimeError('namespace patch provenance changed')\n"
            + "  if len(sys.modules) != len(module_snapshot) or any(sys.modules.get(name, sentinel) is not module for name, module in module_snapshot):\n"
            + "   raise RuntimeError('sys.modules changed during identity validation')\n"
            + "  for name, module in validated:\n"
            + "   if sys.modules.get(name, sentinel) is not module:\n"
            + "    raise RuntimeError('module identity changed before transition: ' + name)\n"
            + "  for name, (module, replacement) in planned_namespace.items():\n"
            + "   if sys.modules.get(name, sentinel) is not module:\n"
            + "    raise RuntimeError('namespace identity changed before transition: ' + name)\n"
            + "  changed = True\n"
            + "  sys.path[:] = desired + [p for p in sys.path if not managed(p)]\n"
            + "  for name, (module, replacement) in planned_namespace.items():\n"
            + "   module_dict = vars(module)\n"
            + "   module_dict['__path__'] = list(replacement)\n"
            + "   spec = module_dict.get('__spec__')\n"
            + "   if spec is not None:\n"
            + "    spec.submodule_search_locations = module_dict['__path__']\n"
            + "  for name, module in validated:\n"
            + "   current = sys.modules.get(name, sentinel)\n"
            + "   if current is sentinel or current is not module:\n"
            + "    raise RuntimeError('module identity changed during eviction: ' + name)\n"
            + "   del sys.modules[name]\n"
            + "   parent_name, separator, child = name.rpartition('.')\n"
            + "   if separator:\n"
            + "    parent = sys.modules.get(parent_name, sentinel)\n"
            + "    if parent is not sentinel and vars(parent).get(child, sentinel) is module:\n"
            + "     del vars(parent)[child]\n"
            + "  importlib.invalidate_caches()\n"
            + " except BaseException:\n"
            + "  if changed:\n"
            + "   sys.path[:] = before_path\n"
            + "   sys.modules.clear()\n"
            + "   sys.modules.update(module_snapshot)\n"
            + "   for module_dict, old_path, spec, old_spec_path in namespace_snapshot:\n"
            + "    module_dict['__path__'] = old_path\n"
            + "    if spec is not None and old_spec_path is not sentinel:\n"
            + "     spec.submodule_search_locations = old_spec_path\n"
            + "   for parent_dict, child, old in parent_attrs:\n"
            + "    if old is sentinel:\n"
            + "     parent_dict.pop(child, None)\n"
            + "    else:\n"
            + "     parent_dict[child] = old\n"
            + "   importlib.invalidate_caches()\n"
            + "  raise\n"
            + " finally:\n"
            + "  _pip_imp.release_lock()\n";

    private static final Set<String> PREINSTALLED_PACKAGES;
    static {
        Set<String> s = new HashSet<>();
        Collections.addAll(s,
                "pip", "setuptools", "wheel",
                "chaquopy", "java", "android",
                
                "certifi", "charset-normalizer", "idna", "urllib3", "requests",
                
                "pillow", "pil", "mpmath", "tinydb", "packaging", "humanize", "typing-extensions",
                
                "lxml", "numpy", "cryptography", "pycryptodome", "crypto", "regex",
                "pyyaml", "yaml", "beautifulsoup4", "bs4", "soupsieve", "markupsafe", "mutagen",
                
                "nimarko-blocker");
        PREINSTALLED_PACKAGES = Collections.unmodifiableSet(s);
    }

    public interface InstallerDelegate {
        boolean isCancelled();
        void onProgress(String text);
    }

    public static final class DependencySnapshot {
        private final boolean present;
        private final Map<String, Set<String>> ownership;
        private final List<String> managedImportPaths;
        private final List<RegistryRootDisk> managedRoots;

        private DependencySnapshot(
                boolean present, Map<String, Set<String>> ownership,
                List<String> managedImportPaths,
                List<RegistryRootDisk> managedRoots) {
            this.present = present;
            this.ownership = ownership;
            this.managedImportPaths = managedImportPaths;
            this.managedRoots = managedRoots;
        }
    }

    private static final class RegistryDisk {
        int schema;
        Map<String, Map<String, Set<String>>> ownership;
        List<RegistryRootDisk> roots;
        String checksum;
    }

    private static final class RegistryRootDisk {
        String distribution;
        String version;
        String root;
        String wheel;
        String sha256;
        Set<String> importRoots;
    }

    private static final class DependencySnapshotDisk {
        int schema;
        String pluginId;
        String transactionId;
        boolean present;
        Map<String, Set<String>> ownership;
        List<String> managedImportPaths;
        List<RegistryRootDisk> managedRoots;
        String checksum;
    }

    private static final class DeferredArtifactEntry {
        String target;
        String staged;
        String backup;
        boolean hadTarget;
    }

    private static final class DeferredArtifactJournal {
        int schema;
        String pluginId;
        String transactionId;
        String state;
        boolean outerSourceTransaction;
        boolean previousOwnershipPresent;
        Map<String, Set<String>> previousOwnership;
        List<RegistryRootDisk> previousRuntimeRoots;
        List<DeferredArtifactEntry> entries = new ArrayList<>();
        String checksum;
    }

    private final Set<String> activeDeferredArtifactTransactions =
            ConcurrentHashMap.newKeySet();
    private final List<RegistryRootDisk> registryRuntimeRoots =
            new ArrayList<>();
    private boolean registryLoaded;
     
    private boolean cleanupRequired = true;
    private boolean recoveringLocalArtifactTransactions;
    private boolean bootstrappingManagedRuntime;
    private boolean startupTemporaryFilesSwept;

    public static final class InstallCancelledRuntimeException extends RuntimeException {
        public InstallCancelledRuntimeException() { super("Dependency installation cancelled"); }
    }

    public static final class RestartRequiredRuntimeException
            extends RuntimeException {
        public RestartRequiredRuntimeException(String message) {
            super(message);
        }

        public RestartRequiredRuntimeException(
                String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class ManagedDistributionRoot {
        final String distribution;
        final String version;
        final String canonicalPath;
        final Set<String> importRoots;

        ManagedDistributionRoot(
                String distribution, String version,
                String canonicalPath, Set<String> importRoots) {
            this.distribution = distribution;
            this.version = version;
            this.canonicalPath = canonicalPath;
            this.importRoots = Collections.unmodifiableSet(
                    new LinkedHashSet<>(importRoots));
        }
    }

    private static final class PureWheelInfo {
        final String distribution;
        final String version;
        final Set<String> tags;
        final List<String> requiresDist;
        final Set<String> importRoots;

        PureWheelInfo(
                String distribution, String version,
                Set<String> tags, List<String> requiresDist,
                Set<String> importRoots) {
            this.distribution = distribution;
            this.version = version;
            this.tags = Collections.unmodifiableSet(
                    new LinkedHashSet<>(tags));
            this.requiresDist = Collections.unmodifiableList(
                    new ArrayList<>(requiresDist));
            this.importRoots = Collections.unmodifiableSet(
                    new LinkedHashSet<>(importRoots));
        }
    }

    private static final class ManagedRuntimeSnapshot {
        final List<String> orderedImportPaths;
        final List<String> orderedCanonicalPaths;
        final Map<String, ManagedDistributionRoot> rootsByPath;
        final Map<String, List<ManagedDistributionRoot>>
                rootsByDistribution;

        ManagedRuntimeSnapshot(
                List<String> orderedImportPaths,
                List<String> orderedCanonicalPaths,
                Map<String, ManagedDistributionRoot> rootsByPath,
                Map<String, List<ManagedDistributionRoot>>
                        rootsByDistribution) {
            this.orderedImportPaths =
                    Collections.unmodifiableList(
                            new ArrayList<>(orderedImportPaths));
            this.orderedCanonicalPaths =
                    Collections.unmodifiableList(
                            new ArrayList<>(orderedCanonicalPaths));
            this.rootsByPath =
                    Collections.unmodifiableMap(
                            new LinkedHashMap<>(rootsByPath));
            LinkedHashMap<String, List<ManagedDistributionRoot>>
                    immutable = new LinkedHashMap<>();
            for (Map.Entry<String, List<ManagedDistributionRoot>> entry
                    : rootsByDistribution.entrySet()) {
                immutable.put(
                        entry.getKey(),
                        Collections.unmodifiableList(
                                new ArrayList<>(entry.getValue())));
            }
            this.rootsByDistribution =
                    Collections.unmodifiableMap(immutable);
        }

        ManagedDistributionRoot activeRoot(String distribution) {
            List<ManagedDistributionRoot> roots =
                    rootsByDistribution.get(distribution);
            if (roots == null) return null;
            for (String path : orderedCanonicalPaths) {
                for (ManagedDistributionRoot root : roots) {
                    if (root.canonicalPath.equals(path)) return root;
                }
            }
            return null;
        }
    }

    private static final class ManagedModuleEvictionPlan {
        final Map<String, Set<String>> importRootsByPath =
                new LinkedHashMap<>();

        void add(ManagedDistributionRoot root) {
            if (root == null
                    || PREINSTALLED_PACKAGES.contains(
                            root.distribution)) {
                return;
            }
            importRootsByPath.computeIfAbsent(
                    root.canonicalPath,
                    ignored -> new LinkedHashSet<>())
                    .addAll(root.importRoots);
        }

        void addAll(ManagedModuleEvictionPlan other) {
            if (other == null) return;
            for (Map.Entry<String, Set<String>> entry
                    : other.importRootsByPath.entrySet()) {
                importRootsByPath.computeIfAbsent(
                        entry.getKey(),
                        ignored -> new LinkedHashSet<>())
                        .addAll(entry.getValue());
            }
        }

        boolean isEmpty() {
            return importRootsByPath.isEmpty();
        }
    }

    private static final class PreparedModuleRemoval {
        final String name;
        final long identity;

        PreparedModuleRemoval(String name, long identity) {
            this.name = name;
            this.identity = identity;
        }
    }

    private static final class PreparedNamespacePatch {
        final String name;
        final long identity;
        final List<String> expectedPaths;

        PreparedNamespacePatch(
                String name, long identity,
                List<String> expectedPaths) {
            this.name = name;
            this.identity = identity;
            this.expectedPaths = Collections.unmodifiableList(
                    new ArrayList<>(expectedPaths));
        }
    }

    private static final class PreparedModuleEviction {
        final List<PreparedModuleRemoval> removals;
        final List<PreparedNamespacePatch> namespacePatches;
        final Map<String, Set<String>> evictionRoots;

        PreparedModuleEviction(
                List<PreparedModuleRemoval> removals,
                ManagedModuleEvictionPlan plan) {
            this(removals, Collections.emptyList(), plan);
        }

        PreparedModuleEviction(
                List<PreparedModuleRemoval> removals,
                List<PreparedNamespacePatch> namespacePatches,
                ManagedModuleEvictionPlan plan) {
            this.removals = removals;
            this.namespacePatches = namespacePatches;
            LinkedHashMap<String, Set<String>> roots =
                    new LinkedHashMap<>();
            if (plan != null) {
                for (Map.Entry<String, Set<String>> entry
                        : plan.importRootsByPath.entrySet()) {
                    roots.put(
                            entry.getKey(),
                            new LinkedHashSet<>(entry.getValue()));
                }
            }
            this.evictionRoots =
                    Collections.unmodifiableMap(roots);
        }
    }

    private static final class ManagedTransition {
        final ManagedModuleEvictionPlan eviction =
                new ManagedModuleEvictionPlan();
        final Set<String> obsoleteImportPaths =
                new LinkedHashSet<>();
    }

    private final ConcurrentHashMap<String, ManagedModuleEvictionPlan>
            pendingRollbackModuleEvictions =
                    new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String>
            pendingRollbackModuleEvictionFailures =
                    new ConcurrentHashMap<>();

    public String getPythonVersion() { return pythonVersion; }

    public String getPythonFullVersion() {
        return pythonFullVersion;
    }

    public String getPlatformMachine() {
        return platformMachine;
    }

    public void setPythonVersion(String v) {
        String normalized = normalizePythonVersion(v);
        this.pythonFullVersion = normalized;
        Matcher matcher = Pattern.compile("^(\\d+)\\.(\\d+)")
                .matcher(normalized);
        if (!matcher.find()) {
            throw new IllegalArgumentException(
                    "Invalid Python version " + v);
        }
        this.pythonVersion =
                matcher.group(1) + "." + matcher.group(2);
    }

    public boolean requiresProcessRestart() {
        return PIP_RESTART_REQUIRED.get();
    }

    private static String normalizePythonVersion(String value) {
        Matcher matcher = Pattern.compile(
                "^(\\d+)\\.(\\d+)(?:\\.(\\d+))?")
                .matcher(value != null ? value.trim() : "");
        if (!matcher.find()) {
            throw new IllegalArgumentException(
                    "Invalid Python version " + value);
        }
        return matcher.group(1) + "." + matcher.group(2)
                + "." + (matcher.group(3) != null
                        ? matcher.group(3) : "0");
    }

    private void refreshRuntimeMarkerEnvironment() {
        if (!isPythonRuntimeUsable()) {
            if (DEFAULT_PLATFORM_MACHINE.equals(platformMachine)) {
                platformMachine = androidMachineFallback();
            }
            return;
        }
        try {
            com.chaquo.python.Python python = getStartedPython();
            com.chaquo.python.PyObject versionInfo =
                    python.getModule("sys").get("version_info");
            if (versionInfo == null) {
                throw new IllegalStateException(
                        "Python version_info is unavailable");
            }
            String full = versionInfo.get("major").toString()
                    + "." + versionInfo.get("minor").toString()
                    + "." + versionInfo.get("micro").toString();
            setPythonVersion(full);
            com.chaquo.python.PyObject machine =
                    python.getModule("platform")
                            .callAttr("machine");
            String detected = machine != null
                    ? machine.toString().trim() : "";
            platformMachine = !detected.isEmpty()
                    ? normalizeMachine(detected)
                    : androidMachineFallback();
        } catch (Throwable failure) {
            FileLog.e("PipController could not read the exact "
                    + "Python marker environment", failure);
            if (DEFAULT_PLATFORM_MACHINE.equals(platformMachine)) {
                platformMachine = androidMachineFallback();
            }
        }
    }

    private static String normalizeMachine(String machine) {
        if (machine == null) return DEFAULT_PLATFORM_MACHINE;
        String value = machine.trim().toLowerCase(Locale.ROOT);
        if ("arm64-v8a".equals(value)
                || "arm64".equals(value)) {
            return "aarch64";
        }
        if ("armeabi-v7a".equals(value)
                || "armv7".equals(value)
                || "arm".equals(value)) {
            return "armv7l";
        }
        return value.isEmpty()
                ? DEFAULT_PLATFORM_MACHINE : value;
    }

    private static String androidMachineFallback() {
        try {
            String[] supported = android.os.Build.SUPPORTED_ABIS;
            if (supported != null && supported.length > 0) {
                return normalizeMachine(supported[0]);
            }
        } catch (Throwable ignored) {
        }
        return DEFAULT_PLATFORM_MACHINE;
    }

    private static void enterPipMutation() {
        Integer existing = PIP_MUTATION_DEPTH.get();
        int depth = existing != null ? existing : 0;
        if (depth == 0 && PIP_RESTART_REQUIRED.get()) {
            throw new RestartRequiredRuntimeException(
                    "Restart required: a previous PIP transition "
                            + "left this process unsafe");
        }
        PIP_MUTATION_DEPTH.set(depth + 1);
    }

    private static void exitPipMutation() {
        Integer existing = PIP_MUTATION_DEPTH.get();
        int depth = existing != null ? existing : 0;
        if (depth <= 1) {
            PIP_MUTATION_DEPTH.remove();
        } else {
            PIP_MUTATION_DEPTH.set(depth - 1);
        }
    }

    private static void requirePipMutationAllowed() {
        Integer existing = PIP_MUTATION_DEPTH.get();
        if ((existing == null || existing == 0)
                && PIP_RESTART_REQUIRED.get()) {
            throw new RestartRequiredRuntimeException(
                    "Restart required: PIP mutations are blocked "
                            + "for the remainder of this process");
        }
    }

    public File getLibsDir() {
        File dir = new File(PluginsController.getInstance().getPluginsDir().getParentFile(), "pip-libs");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public File getWheelsDir() {
        File dir = new File(getLibsDir(), "wheels");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public File getRegistryFile() { return new File(getLibsDir(), "registry.json"); }

    public synchronized void loadRegistry() {
        enterPipMutation();
        try {
            loadRegistryStrict();
        } catch (RestartRequiredRuntimeException failure) {
            throw failure;
        } catch (Throwable t) {
            FileLog.e("PipController.loadRegistry failed", t);
        } finally {
            exitPipMutation();
        }
    }

    private void loadRegistryStrict() throws IOException {
        loadRegistryStrict(true);
    }

    private void loadRegistryStrict(boolean bootstrapRuntime)
            throws IOException {
        requirePipMutationAllowed();
        sweepStartupTemporaryFilesStrict();
        recoverInterruptedArtifactTransactions();
        File source = getRegistryFile();
        LinkedHashMap<String, ConcurrentHashMap<String, Set<String>>>
                validated = new LinkedHashMap<>();
        List<RegistryRootDisk> validatedRoots = new ArrayList<>();
        boolean legacyRegistry = false;
        if (source.exists()) {
            byte[] bytes = readBoundedFile(
                    source, MAX_REGISTRY_BYTES, "pip registry");
            final JsonElement document;
            try {
                document = JsonParser.parseString(
                        new String(bytes, StandardCharsets.UTF_8));
            } catch (Throwable failure) {
                throw new IOException("pip registry is corrupt", failure);
            }
            if (document == null || !document.isJsonObject()) {
                throw new IOException("pip registry root is null");
            }
            JsonObject object = document.getAsJsonObject();
            if (object.has("schema")
                    && object.get("schema").isJsonPrimitive()
                    && object.getAsJsonPrimitive("schema")
                            .isNumber()) {
                final RegistryDisk disk;
                try {
                    disk = gson.fromJson(document, RegistryDisk.class);
                } catch (Throwable failure) {
                    throw new IOException(
                            "versioned pip registry is corrupt", failure);
                }
                if (disk == null
                        || disk.schema != REGISTRY_SCHEMA
                        || disk.ownership == null
                        || disk.roots == null
                        || disk.checksum == null) {
                    throw new IOException(
                            "pip registry schema mismatch");
                }
                String expectedChecksum = disk.checksum;
                disk.checksum = null;
                String actualChecksum =
                        registryChecksum(disk);
                disk.checksum = expectedChecksum;
                if (!expectedChecksum.equals(actualChecksum)) {
                    throw new IOException(
                            "pip registry checksum mismatch");
                }
                validated.putAll(
                        validateRegistryOwnership(disk.ownership));
                validatedRoots =
                        validateRegistryRootRecords(
                                disk.roots, false);
            } else {
                final Map<String, Map<String, Set<String>>> parsed;
                try {
                    Type type = new TypeToken<
                            Map<String, Map<String, Set<String>>>>() {
                    }.getType();
                    parsed = gson.fromJson(document, type);
                } catch (Throwable failure) {
                    throw new IOException(
                            "legacy pip registry is corrupt", failure);
                }
                validated.putAll(
                        validateRegistryOwnership(parsed));
                validatedRoots =
                        migrateLegacyRegistryRoots(validated);
                legacyRegistry = true;
            }
        }
        validateRegistryCoverage(validated, validatedRoots);

        if (legacyRegistry) {
            
            writeRegistryStateStrict(validated, validatedRoots);
        }

        registry.clear();
        registry.putAll(validated);
        registryRuntimeRoots.clear();
        registryRuntimeRoots.addAll(
                copyRegistryRoots(validatedRoots));
        registryLoaded = true;
        recoverLocalArtifactTransactions();
        if (bootstrapRuntime) {
            List<RegistryRootDisk> artifactValidated =
                    validateRegistryRootRecords(
                            registryRuntimeRoots, true);
            registryRuntimeRoots.clear();
            registryRuntimeRoots.addAll(artifactValidated);
            bootstrapManagedRuntimeStrict();
        }
    }

    private void loadRegistryOrThrow() {
        
        if (registryLoaded) {
            return;
        }
        try {
            loadRegistryStrict();
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Cannot safely read the pip registry", failure);
        }
    }

    public synchronized void bootstrapRuntimeForPluginStartup() {
        enterPipMutation();
        try {
            loadRegistryStrict();
        } catch (RestartRequiredRuntimeException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Cannot safely bootstrap plugin dependencies",
                    failure);
        } finally {
            exitPipMutation();
        }
    }

    public synchronized void saveRegistry() {
        enterPipMutation();
        try {
            saveRegistryStrict();
        } catch (RestartRequiredRuntimeException failure) {
            throw failure;
        } catch (Throwable t) {
            FileLog.e("PipController.saveRegistry failed", t);
        } finally {
            exitPipMutation();
        }
    }

    private void saveRegistryStrict() throws IOException {
        writeRegistryStateStrict(
                registry, registryRuntimeRoots);
    }

    private void writeRegistryStateStrict(
            Map<String, ? extends Map<String, Set<String>>> ownership,
            List<RegistryRootDisk> roots) throws IOException {
        List<RegistryRootDisk> validatedRoots =
                validateRegistryRootRecords(
                        roots != null
                                ? roots : Collections.emptyList(),
                        false);
        validateRegistryCoverage(
                ownership, validatedRoots);
        RegistryDisk disk = new RegistryDisk();
        disk.schema = REGISTRY_SCHEMA;
        disk.ownership = ownershipForDisk(ownership);
        disk.roots = copyRegistryRoots(validatedRoots);
        disk.checksum = null;
        disk.checksum = registryChecksum(disk);
        byte[] payload = gson.toJson(disk)
                .getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_REGISTRY_BYTES) {
            throw new IOException("pip registry is too large");
        }
        writeRegistryPayloadStrict(payload);
    }

    private static void validateRegistryCoverage(
            Map<String, ? extends Map<String, Set<String>>> ownership,
            List<RegistryRootDisk> roots) throws IOException {
        LinkedHashSet<String> referenced =
                new LinkedHashSet<>();
        if (ownership != null) {
            for (Map<String, Set<String>> packages
                    : ownership.values()) {
                if (packages != null) {
                    referenced.addAll(packages.keySet());
                }
            }
        }
        referenced.removeAll(PREINSTALLED_PACKAGES);
        LinkedHashSet<String> active = new LinkedHashSet<>();
        if (roots != null) {
            for (RegistryRootDisk root : roots) {
                if (root != null
                        && root.distribution != null) {
                    active.add(root.distribution);
                }
            }
        }
        if (!referenced.equals(active)) {
            throw new IOException(
                    "pip registry ownership/runtime coverage "
                            + "mismatch: referenced=" + referenced
                            + " active=" + active);
        }
    }

    private void writeRegistryPayloadStrict(byte[] payload)
            throws IOException {
        File target = getRegistryFile();
        File parent = target.getParentFile();
        if (parent == null
                || (!parent.exists() && !parent.mkdirs()
                        && !parent.exists())) {
            throw new IOException("cannot create registry directory " + parent);
        }

        IOException firstFsyncFailure = null;
        
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = Long.toHexString(System.nanoTime())
                    + "-" + attempt;
            File staged = new File(
                    parent, ".registry." + token + ".stage");
            try {
                try (FileOutputStream output =
                        new FileOutputStream(staged)) {
                    output.write(payload);
                    output.flush();
                    output.getFD().sync();
                }
                try {
                    android.system.Os.rename(
                            staged.getAbsolutePath(),
                            target.getAbsolutePath());
                } catch (android.system.ErrnoException failure) {
                    throw new IOException(
                            "cannot atomically commit registry "
                                    + target,
                            failure);
                }
                try {
                    syncDirectoryStrict(parent);
                    return;
                } catch (IOException fsyncFailure) {
                    if (!fileContentsEqual(target, payload)) {
                        throw new IOException(
                                "registry rename/fsync result is "
                                        + "not the intended generation",
                                fsyncFailure);
                    }
                    if (firstFsyncFailure == null) {
                        firstFsyncFailure = fsyncFailure;
                    } else {
                        firstFsyncFailure.addSuppressed(
                                fsyncFailure);
                    }
                    if (attempt == 0) {
                        continue;
                    }
                }
            } finally {
                if (staged.exists() && !staged.delete()) {
                    FileLog.w("PipController could not remove stale "
                            + "registry stage " + staged);
                }
            }
        }
        try {
            
            syncDirectoryStrict(parent);
            return;
        } catch (IOException finalFailure) {
            if (firstFsyncFailure != null) {
                finalFailure.addSuppressed(firstFsyncFailure);
            }
            throw new IOException(
                    "registry publication remains durability-ambiguous "
                            + "after compensating atomic write",
                    finalFailure);
        }
    }

    private static boolean fileContentsEqual(
            File source, byte[] expected) {
        try {
            return Arrays.equals(
                    readBoundedFile(
                            source, MAX_REGISTRY_BYTES,
                            "published pip registry"),
                    expected);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static LinkedHashMap<String,
            ConcurrentHashMap<String, Set<String>>>
            validateRegistryOwnership(
                    Map<String, ? extends Map<String, Set<String>>> parsed)
                    throws IOException {
        if (parsed == null) {
            throw new IOException("pip registry ownership is null");
        }
        LinkedHashMap<String, ConcurrentHashMap<String, Set<String>>>
                validated = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends Map<String, Set<String>>> owner
                : parsed.entrySet()) {
            String pluginId = owner.getKey();
            if (pluginId == null || pluginId.length() > 64
                    || pluginId.contains("/")
                    || pluginId.contains("\\")) {
                throw new IOException(
                        "pip registry has an invalid plugin id");
            }
            if (owner.getValue() == null) {
                throw new IOException(
                        "pip registry owner is null for " + pluginId);
            }
            ConcurrentHashMap<String, Set<String>> packages =
                    new ConcurrentHashMap<>();
            for (Map.Entry<String, Set<String>> dependency
                    : owner.getValue().entrySet()) {
                String packageName = dependency.getKey();
                if (packageName == null || packageName.isEmpty()
                        || packageName.length() > 256
                        || packageName.contains("/")
                        || packageName.contains("\\")
                        || !packageName.equals(
                                normalizePackageName(packageName))) {
                    throw new IOException(
                            "pip registry has an invalid package name");
                }
                if (dependency.getValue() == null) {
                    throw new IOException(
                            "pip registry requirements are null for "
                                    + packageName);
                }
                LinkedHashSet<String> requirements =
                        new LinkedHashSet<>();
                for (String requirement : dependency.getValue()) {
                    if (requirement == null
                            || requirement.length() > 4096) {
                        throw new IOException(
                                "pip registry has an invalid requirement");
                    }
                    requirements.add(requirement);
                }
                packages.put(packageName, requirements);
            }
            validated.put(pluginId, packages);
        }
        return validated;
    }

    private static Map<String, Map<String, Set<String>>>
            ownershipForDisk(
                    Map<String, ? extends Map<String, Set<String>>>
                            ownership) {
        LinkedHashMap<String, Map<String, Set<String>>> result =
                new LinkedHashMap<>();
        ArrayList<String> owners =
                new ArrayList<>(ownership.keySet());
        Collections.sort(owners);
        for (String owner : owners) {
            Map<String, Set<String>> source = ownership.get(owner);
            LinkedHashMap<String, Set<String>> packages =
                    new LinkedHashMap<>();
            if (source != null) {
                ArrayList<String> names =
                        new ArrayList<>(source.keySet());
                Collections.sort(names);
                for (String name : names) {
                    ArrayList<String> sorted = new ArrayList<>(
                            source.get(name) != null
                                    ? source.get(name)
                                    : Collections.emptySet());
                    Collections.sort(sorted);
                    packages.put(
                            name, new LinkedHashSet<>(sorted));
                }
            }
            result.put(owner, packages);
        }
        return result;
    }

    private static List<RegistryRootDisk> copyRegistryRoots(
            List<RegistryRootDisk> source) {
        ArrayList<RegistryRootDisk> copy = new ArrayList<>();
        if (source == null) return copy;
        for (RegistryRootDisk root : source) {
            if (root == null) {
                copy.add(null);
                continue;
            }
            RegistryRootDisk item = new RegistryRootDisk();
            item.distribution = root.distribution;
            item.version = root.version;
            item.root = root.root;
            item.wheel = root.wheel;
            item.sha256 = root.sha256;
            item.importRoots = root.importRoots != null
                    ? new LinkedHashSet<>(root.importRoots)
                    : null;
            copy.add(item);
        }
        return copy;
    }

    private List<RegistryRootDisk> validateRegistryRootRecords(
            List<RegistryRootDisk> source,
            boolean requireArtifacts) throws IOException {
        if (source == null) {
            throw new IOException(
                    "pip registry runtime roots are null");
        }
        ArrayList<RegistryRootDisk> validated = new ArrayList<>();
        LinkedHashSet<String> distributions =
                new LinkedHashSet<>();
        LinkedHashSet<String> physicalRoots =
                new LinkedHashSet<>();
        LinkedHashMap<String, String> importRootOwners =
                new LinkedHashMap<>();
        File site = new File(getLibsDir(), "site")
                .getCanonicalFile();
        File wheels = getWheelsDir().getCanonicalFile();
        for (RegistryRootDisk raw : source) {
            if (raw == null
                    || raw.distribution == null
                    || raw.version == null
                    || raw.root == null
                    || raw.wheel == null
                    || raw.sha256 == null
                    || raw.importRoots == null
                    || raw.importRoots.isEmpty()) {
                throw new IOException(
                        "pip registry has an incomplete runtime root");
            }
            String distribution =
                    normalizePackageName(raw.distribution);
            if (!distribution.equals(raw.distribution)
                    || distribution.isEmpty()
                    || raw.version.isEmpty()
                    || raw.version.length() > 256
                    || raw.version.contains("/")
                    || raw.version.contains("\\")
                    || !raw.sha256.matches(
                            "(?i)[0-9a-f]{64}")) {
                throw new IOException(
                        "pip registry has an invalid runtime identity");
            }
            File root = resolveArtifactPath(raw.root)
                    .getCanonicalFile();
            File wheel = resolveArtifactPath(raw.wheel)
                    .getCanonicalFile();
            if (!site.equals(root.getParentFile())
                    || !wheels.equals(wheel.getParentFile())
                    || !wheel.getName().endsWith(".whl")
                    || !root.getName().equals(
                            wheel.getName().substring(
                                    0,
                                    wheel.getName().length() - 4))
                    || !distributions.add(distribution)
                    || !physicalRoots.add(root.getPath())) {
                throw new IOException(
                        "pip registry runtime root/path mismatch");
            }
            LinkedHashSet<String> importRoots =
                    new LinkedHashSet<>();
            for (String importRoot : raw.importRoots) {
                if (importRoot == null
                        || !IMPORT_ROOT_PATTERN.matcher(
                                importRoot).matches()) {
                    throw new IOException(
                            "pip registry has an invalid import root");
                }
                importRoots.add(importRoot);
            }
            registerImportRootOwnership(
                    importRootOwners, distribution, importRoots);

            if (requireArtifacts) {
                if (!root.isDirectory()
                        || !extractionDigestMatches(
                                new File(root, ".extracted"),
                                root, raw.sha256, source)
                        || !wheel.isFile()
                        || !verifySha256(wheel, raw.sha256)) {
                    throw new IOException(
                            "active dependency artifact is missing or "
                                    + "does not match its registry digest");
                }
                ManagedDistributionRoot metadata =
                        readManagedDistributionRoot(root, root);
                if (!distribution.equals(metadata.distribution)
                        || !raw.version.equals(
                                metadata.version)
                        || !importRoots.equals(
                                metadata.importRoots)) {
                    throw new IOException(
                            "active dependency root does not match "
                                    + "the registry identity");
                }
                PureWheelInfo wheelInfo =
                        inspectPureWheel(wheel);
                if (!distribution.equals(
                                wheelInfo.distribution)
                        || !raw.version.equals(
                                wheelInfo.version)) {
                    throw new IOException(
                            "active wheel metadata does not match "
                                    + "the registry identity");
                }
            }

            RegistryRootDisk item = new RegistryRootDisk();
            item.distribution = distribution;
            item.version = raw.version;
            item.root = relativeArtifactPath(root);
            item.wheel = relativeArtifactPath(wheel);
            item.sha256 =
                    raw.sha256.toLowerCase(Locale.ROOT);
            item.importRoots = importRoots;
            validated.add(item);
        }
        return validated;
    }

    private static void registerImportRootOwnership(
            Map<String, String> owners, String distribution,
            Set<String> importRoots) throws IOException {
        if (owners == null || distribution == null
                || importRoots == null || importRoots.isEmpty()) {
            throw new IOException(
                    "Cannot prove dependency import-root ownership");
        }
        for (String importRoot : importRoots) {
            String previous = owners.putIfAbsent(
                    importRoot, distribution);
            if (previous != null
                    && !previous.equals(distribution)) {
                throw new IOException(
                        "Import root " + importRoot
                                + " is provided by both "
                                + previous + " and " + distribution
                                + "; cross-distribution namespaces "
                                + "are not supported safely");
            }
        }
    }

    private List<RegistryRootDisk> migrateLegacyRegistryRoots(
            Map<String, ? extends Map<String, Set<String>>>
                    ownership) throws IOException {
        LinkedHashSet<String> referenced =
                new LinkedHashSet<>();
        for (Map<String, Set<String>> packages
                : ownership.values()) {
            if (packages != null) {
                referenced.addAll(packages.keySet());
            }
        }
        referenced.removeAll(PREINSTALLED_PACKAGES);
        if (referenced.isEmpty()) {
            return Collections.emptyList();
        }

        LinkedHashMap<String, List<ManagedDistributionRoot>>
                candidates = new LinkedHashMap<>();
        File site = new File(getLibsDir(), "site");
        File[] children = site.listFiles();
        if (children != null) {
            Arrays.sort(
                    children, Comparator.comparing(File::getName));
            for (File child : children) {
                if (!child.isDirectory()
                        || child.getName().startsWith(".")
                        || !new File(
                                child, ".extracted").isFile()) {
                    continue;
                }
                ManagedDistributionRoot root;
                try {
                    root = readManagedDistributionRoot(
                            child, child);
                } catch (Throwable invalidInactiveRoot) {
                    
                    continue;
                }
                if (referenced.contains(root.distribution)) {
                    candidates.computeIfAbsent(
                            root.distribution,
                            ignored -> new ArrayList<>())
                            .add(root);
                }
            }
        }

        ArrayList<String> orderedDistributions =
                new ArrayList<>(referenced);
        Collections.sort(orderedDistributions);
        ArrayList<RegistryRootDisk> migrated =
                new ArrayList<>();
        for (String distribution : orderedDistributions) {
            List<String[]> constraints = new ArrayList<>();
            String rejectedReason = null;
            for (Map<String, Set<String>> packages
                    : ownership.values()) {
                if (packages == null) continue;
                Set<String> requirements =
                        packages.get(distribution);
                if (requirements == null) continue;
                for (String raw : requirements) {
                    ParsedRequirement parsed =
                            parseRequirement(raw);
                    if (parsed == null
                            || !distribution.equals(
                                    normalizePackageName(
                                            parsed.name))) {
                        rejectedReason =
                                "unparseable legacy requirement";
                        break;
                    }
                    for (String[] spec : parsed.specs) {
                        constraints.add(
                                new String[]{spec[0], spec[1]});
                    }
                }
                if (rejectedReason != null) break;
            }
            if (rejectedReason != null) {
                discardUnprovenLegacyDistribution(
                        ownership, distribution, rejectedReason);
                continue;
            }
            ArrayList<ManagedDistributionRoot> compatible =
                    new ArrayList<>();
            List<ManagedDistributionRoot> roots =
                    candidates.get(distribution);
            if (roots != null) {
                for (ManagedDistributionRoot root : roots) {
                    if (satisfies(root.version, constraints)) {
                        compatible.add(root);
                    }
                }
            }
            compatible.sort((left, right) -> {
                boolean leftPre =
                        isPreRelease(left.version);
                boolean rightPre =
                        isPreRelease(right.version);
                if (leftPre != rightPre) {
                    return leftPre ? 1 : -1;
                }
                int versionOrder =
                        VersionComparator.INSTANCE.compare(
                                right.version, left.version);
                return versionOrder != 0
                        ? versionOrder
                        : left.canonicalPath.compareTo(
                                right.canonicalPath);
            });
            if (compatible.isEmpty()) {
                discardUnprovenLegacyDistribution(
                        ownership, distribution,
                        "no installed runtime root satisfies its requirements");
                continue;
            }
            ManagedDistributionRoot selected =
                    compatible.get(0);
            File wheel = new File(
                    getWheelsDir(),
                    new File(selected.canonicalPath).getName()
                            + ".whl");
            if (!wheel.isFile()) {
                discardUnprovenLegacyDistribution(
                        ownership, distribution,
                        "the matching wheel is missing");
                continue;
            }
            try {
                PureWheelInfo info = inspectPureWheel(wheel);
                if (!distribution.equals(info.distribution)
                        || !selected.version.equals(
                                info.version)) {
                    discardUnprovenLegacyDistribution(
                            ownership, distribution,
                            "the wheel identity does not match the runtime root");
                    continue;
                }
                migrated.add(
                        registryRootForManaged(
                                selected,
                                calculateSha256(wheel)));
            } catch (IOException invalidArtifact) {
                FileLog.e("PipController could not validate legacy dependency "
                        + distribution, invalidArtifact);
                discardUnprovenLegacyDistribution(
                        ownership, distribution,
                        "its stored artifact cannot be validated");
            }
        }
        if (isPythonRuntimeUsable()) {
            LinkedHashMap<String, RegistryRootDisk> byPath =
                    new LinkedHashMap<>();
            for (RegistryRootDisk root : migrated) {
                byPath.put(
                        resolveArtifactPath(root.root)
                                .getCanonicalPath(),
                        root);
            }
            ArrayList<RegistryRootDisk> ordered =
                    new ArrayList<>();
            for (String current
                    : currentManagedImportPathsStrict()) {
                RegistryRootDisk root = byPath.remove(current);
                if (root == null) {
                    
                    cleanupRequired = true;
                    FileLog.e("PipController ignored stale legacy sys.path "
                            + "entry " + current);
                    continue;
                }
                ordered.add(root);
            }
            ordered.addAll(byPath.values());
            migrated = ordered;
        }
        return migrated;
    }

    private void discardUnprovenLegacyDistribution(
            Map<String, ? extends Map<String, Set<String>>> ownership,
            String distribution, String reason) {
        boolean removed = false;
        for (Map<String, Set<String>> packages : ownership.values()) {
            if (packages != null
                    && packages.remove(distribution) != null) {
                removed = true;
            }
        }
        if (removed) {
            
            cleanupRequired = true;
            FileLog.e("PipController discarded unverifiable legacy dependency "
                    + distribution + " (" + reason
                    + "); it will be resolved again when required");
        }
    }

    private RegistryRootDisk registryRootForManaged(
            ManagedDistributionRoot root, String sha256)
            throws IOException {
        if (root == null || sha256 == null
                || !sha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IOException(
                    "Cannot persist an incomplete runtime root");
        }
        File extraction =
                new File(root.canonicalPath).getCanonicalFile();
        File wheel = new File(
                getWheelsDir(),
                extraction.getName() + ".whl");
        RegistryRootDisk record = new RegistryRootDisk();
        record.distribution = root.distribution;
        record.version = root.version;
        record.root = relativeArtifactPath(extraction);
        record.wheel = relativeArtifactPath(wheel);
        record.sha256 =
                sha256.toLowerCase(Locale.ROOT);
        record.importRoots =
                new LinkedHashSet<>(root.importRoots);
        return record;
    }

    private void bootstrapManagedRuntimeStrict()
            throws IOException {
        if (!isPythonRuntimeUsable()
                || bootstrappingManagedRuntime) {
            return;
        }
        bootstrappingManagedRuntime = true;
        try {
            List<RegistryRootDisk> roots =
                    validateRegistryRootRecords(
                            registryRuntimeRoots, true);
            ArrayList<String> desired =
                    registryImportPathsStrict(roots);
            ArrayList<String> current =
                    currentManagedImportPathsStrict();
            ManagedModuleEvictionPlan eviction =
                    new ManagedModuleEvictionPlan();
            LinkedHashSet<String> desiredSet =
                    new LinkedHashSet<>(desired);
            for (String path : current) {
                if (desiredSet.contains(path)) continue;
                File retired = new File(path);
                if (!retired.isDirectory()) {
                    throw restartRequired(
                            "cannot identify a stale bootstrap root "
                                    + path);
                }
                eviction.add(readManagedDistributionRoot(
                        retired, retired));
            }
            PreparedModuleEviction prepared =
                    prepareManagedModuleEviction(eviction);
            executeManagedRuntimeTransition(
                    current, desired, prepared, roots);
        } catch (RestartRequiredRuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw restartRequired(
                    "cannot bootstrap the exact dependency "
                            + "generation from the registry",
                    failure);
        } finally {
            bootstrappingManagedRuntime = false;
        }
    }

    private ArrayList<String> registryImportPathsStrict(
            List<RegistryRootDisk> roots) throws IOException {
        ArrayList<String> result = new ArrayList<>();
        for (RegistryRootDisk root : roots) {
            String canonical =
                    resolveArtifactPath(root.root)
                            .getCanonicalPath();
            if (!isManagedImportPath(canonical)
                    || result.contains(canonical)) {
                throw new IOException(
                        "pip registry contains a duplicate or "
                                + "unmanaged runtime path");
            }
            result.add(canonical);
        }
        return result;
    }

    private static void syncDirectory(File directory) {
        if (directory == null) return;
        java.io.FileDescriptor descriptor = null;
        try {
            descriptor = android.system.Os.open(
                    directory.getAbsolutePath(),
                    android.system.OsConstants.O_RDONLY, 0);
            android.system.Os.fsync(descriptor);
        } catch (Exception failure) {
            FileLog.e("PipController could not fsync registry directory",
                    failure);
        } finally {
            if (descriptor != null) {
                try {
                    android.system.Os.close(descriptor);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void syncDirectoryStrict(File directory)
            throws IOException {
        if (directory == null) {
            throw new IOException("Directory to fsync is missing");
        }
        java.io.FileDescriptor descriptor = null;
        try {
            descriptor = android.system.Os.open(
                    directory.getAbsolutePath(),
                    android.system.OsConstants.O_RDONLY, 0);
            android.system.Os.fsync(descriptor);
        } catch (Throwable failure) {
            throw new IOException(
                    "Could not fsync directory " + directory,
                    failure);
        } finally {
            if (descriptor != null) {
                try {
                    android.system.Os.close(descriptor);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void syncTreeStrict(File artifact)
            throws IOException {
        if (artifact == null || !artifact.exists()) {
            throw new IOException(
                    "Dependency stage is missing");
        }
        if (artifact.isDirectory()) {
            File[] children = artifact.listFiles();
            if (children == null) {
                throw new IOException(
                        "Could not enumerate dependency stage "
                                + artifact);
            }
            for (File child : children) {
                syncTreeStrict(child);
            }
            syncDirectoryStrict(artifact);
            return;
        }
        java.io.FileDescriptor descriptor = null;
        try {
            descriptor = android.system.Os.open(
                    artifact.getAbsolutePath(),
                    android.system.OsConstants.O_RDONLY, 0);
            android.system.Os.fsync(descriptor);
        } catch (Throwable failure) {
            throw new IOException(
                    "Could not fsync dependency stage "
                            + artifact, failure);
        } finally {
            if (descriptor != null) {
                try {
                    android.system.Os.close(descriptor);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static byte[] readBoundedFile(
            File source, int maxBytes, String label) throws IOException {
        if (source == null || !source.isFile()) {
            throw new IOException(label + " is missing");
        }
        long length = source.length();
        if (length < 0 || length > maxBytes) {
            throw new IOException(label + " has invalid size");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(
                (int) Math.max(32, length));
        try (InputStream input = new FileInputStream(source)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) > 0) {
                if (bytes.size() + count > maxBytes) {
                    throw new IOException(label + " is too large");
                }
                bytes.write(buffer, 0, count);
            }
        }
        return bytes.toByteArray();
    }

    private static void validateTransactionIdentity(
            String pluginId, String transactionId) throws IOException {
        if (pluginId == null
                || !PLUGIN_ID_PATTERN.matcher(pluginId).matches()
                || transactionId == null
                || !TRANSACTION_ID_PATTERN.matcher(
                        transactionId).matches()) {
            throw new IOException(
                    "Invalid plugin dependency transaction identity");
        }
    }

    private static String sha256Hex(byte[] payload) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload);
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                result.append(String.format(
                        Locale.ROOT, "%02x", value & 0xff));
            }
            return result.toString();
        } catch (Exception failure) {
            throw new IOException("SHA-256 is unavailable", failure);
        }
    }

    private static String registryChecksum(
            RegistryDisk disk) throws IOException {
        if (disk == null) {
            throw new IOException(
                    "Cannot checksum a null pip registry");
        }
        String previous = disk.checksum;
        disk.checksum = null;
        try {
            return sha256Hex(
                    new Gson().toJson(disk)
                            .getBytes(StandardCharsets.UTF_8));
        } finally {
            disk.checksum = previous;
        }
    }

    static String registryChecksumForTest(
            String serializedRegistry) throws IOException {
        final RegistryDisk disk;
        try {
            disk = new Gson().fromJson(
                    serializedRegistry, RegistryDisk.class);
        } catch (Throwable failure) {
            throw new IOException(
                    "Test registry is malformed", failure);
        }
        return registryChecksum(disk);
    }

    private void sweepStartupTemporaryFilesStrict()
            throws IOException {
        if (startupTemporaryFilesSwept) return;
        sweepRecognizedTemporaryFiles(
                getLibsDir(),
                REGISTRY_STAGE_PATTERN,
                ARTIFACT_JOURNAL_STAGE_PATTERN);
        sweepRecognizedTemporaryFiles(
                getWheelsDir(),
                RESOLVER_WHEEL_PATTERN,
                ARTIFACT_DOWNLOAD_PART_PATTERN);
        File pluginsDirectory =
                PluginsController.getInstance().getPluginsDir();
        sweepRecognizedTemporaryFiles(
                pluginsDirectory,
                DEPENDENCY_SNAPSHOT_STAGE_PATTERN);
        startupTemporaryFilesSwept = true;
    }

    private static void sweepRecognizedTemporaryFiles(
            File directory, Pattern... acceptedNames)
            throws IOException {
        if (directory == null || !directory.isDirectory()) return;
        File[] children = directory.listFiles();
        if (children == null) {
            throw new IOException(
                    "Cannot enumerate PIP temporary directory "
                            + directory);
        }
        boolean changed = false;
        for (File child : children) {
            boolean accepted = false;
            for (Pattern pattern : acceptedNames) {
                if (pattern.matcher(child.getName()).matches()) {
                    accepted = true;
                    break;
                }
            }
            if (!accepted) continue;
            if (!deleteArtifactAndVerify(child)) {
                throw new IOException(
                        "Cannot remove stale PIP temporary artifact "
                                + child);
            }
            changed = true;
        }
        if (changed) {
            syncDirectoryStrict(directory);
        }
    }

    private void recoverInterruptedArtifactTransactions()
            throws IOException {
        Set<String> protectedPaths =
                collectDeferredArtifactRecoveryPaths();
        recoverInterruptedArtifactTransactionsIn(
                getWheelsDir(), protectedPaths);
        recoverInterruptedArtifactTransactionsIn(
                new File(getLibsDir(), "site"),
                protectedPaths);
    }

    private Set<String> collectDeferredArtifactRecoveryPaths()
            throws IOException {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        File[] journals = getLibsDir().listFiles((dir, name) ->
                DEFERRED_ARTIFACT_JOURNAL_PATTERN.matcher(name)
                        .matches());
        if (journals == null) return result;
        for (File file : journals) {
            Matcher matcher = DEFERRED_ARTIFACT_JOURNAL_PATTERN
                    .matcher(file.getName());
            if (!matcher.matches()) continue;
            DeferredArtifactJournal journal =
                    readDeferredArtifactJournal(
                            matcher.group(1), null);
            if (journal == null) continue;
            for (DeferredArtifactEntry entry : journal.entries) {
                result.add(resolveArtifactPath(entry.target)
                        .getCanonicalPath());
                result.add(resolveArtifactPath(entry.backup)
                        .getCanonicalPath());
                result.add(resolveArtifactPath(entry.staged)
                        .getCanonicalPath());
            }
            if (journal.previousRuntimeRoots != null) {
                for (RegistryRootDisk root
                        : journal.previousRuntimeRoots) {
                    result.add(resolveArtifactPath(root.root)
                            .getCanonicalPath());
                    result.add(resolveArtifactPath(root.wheel)
                            .getCanonicalPath());
                }
            }
        }
        return result;
    }

    private void recoverInterruptedArtifactTransactionsIn(
            File directory, Set<String> protectedPaths)
            throws IOException {
        if (directory == null || !directory.isDirectory()) return;
        File[] files = directory.listFiles();
        if (files == null || files.length == 0) return;
        boolean changed = false;

        for (File backup : files) {
            Matcher matcher =
                    ARTIFACT_BACKUP_PATTERN.matcher(backup.getName());
            if (!matcher.matches()) continue;
            if (protectedPaths.contains(
                    backup.getCanonicalPath())) {
                continue;
            }
            String targetName = matcher.group(1);
            String transactionId = matcher.group(2);
            File target = new File(directory, targetName);
            boolean targetReady = target.exists();
            if (!targetReady) {
                try {
                    android.system.Os.rename(
                            backup.getAbsolutePath(),
                            target.getAbsolutePath());
                    targetReady = target.exists() && !backup.exists();
                    changed |= targetReady;
                } catch (Throwable failure) {
                    throw new IOException(
                            "PipController could not restore interrupted "
                                    + "artifact " + target,
                            failure);
                }
            } else if (deleteArtifactAndVerify(backup)) {
                changed = true;
            } else {
                throw new IOException(
                        "PipController retained stale artifact backup "
                                + backup);
            }
            if (!targetReady) continue;

            File wheelStage = new File(
                    directory,
                    "." + targetName + "." + transactionId
                            + ".wheel.stage");
            File extractionStage = new File(
                    directory,
                    "." + targetName + "." + transactionId
                            + ".extract.stage");
            boolean wheelStageExisted = wheelStage.exists();
            if (wheelStageExisted
                    && deleteArtifactAndVerify(wheelStage)) {
                changed = true;
            } else if (wheelStageExisted) {
                throw new IOException(
                        "PipController retained interrupted wheel stage "
                                + wheelStage);
            }
            boolean extractionStageExisted = extractionStage.exists();
            if (extractionStageExisted
                    && deleteArtifactAndVerify(extractionStage)) {
                changed = true;
            } else if (extractionStageExisted) {
                throw new IOException(
                        "PipController retained interrupted extraction "
                                + "stage " + extractionStage);
            }
        }

        files = directory.listFiles();
        if (files != null) {
            for (File stage : files) {
                Matcher matcher =
                        ARTIFACT_STAGE_PATTERN.matcher(stage.getName());
                if (!matcher.matches()) continue;
                if (protectedPaths.contains(
                        stage.getCanonicalPath())) {
                    continue;
                }
                String targetName = matcher.group(1);
                String transactionId = matcher.group(2);
                File backup = new File(
                        directory,
                        "." + targetName + "." + transactionId
                                + ".backup");
                File target = new File(directory, targetName);
                if (backup.exists() && !target.exists()) {
                    
                    continue;
                }
                boolean existed = stage.exists();
                if (!deleteArtifactAndVerify(stage)) {
                    throw new IOException(
                            "PipController retained unpublished stage "
                                    + stage);
                } else if (existed) {
                    changed = true;
                }
            }
        }
        if (changed) syncDirectory(directory);
    }

    private static boolean deleteArtifactAndVerify(File artifact) {
        if (artifact == null || !artifact.exists()) return true;
        FileUtils.deleteRecursive(artifact, true);
        return !artifact.exists();
    }

    public synchronized void cleanup() {
        if (registryLoaded && !cleanupRequired
                && activeDeferredArtifactTransactions.isEmpty()) {
            return;
        }
        try {
            cleanupAndReport();
        } catch (RestartRequiredRuntimeException failure) {
            
            FileLog.e("PipController cleanup deferred until process restart",
                    failure);
        }
    }

    public synchronized boolean cleanupAndReport() {
        enterPipMutation();
        try {
            loadRegistryStrict();
            requireNoPendingArtifactTransactions();
            cleanupInternal();
            removeOrphanedDirectories();
            saveRegistryStrict();
            cleanupRequired = false;
            return true;
        } catch (RestartRequiredRuntimeException failure) {
            throw failure;
        } catch (Throwable t) {
            FileLog.e("PipController.cleanup failed", t);
            return false;
        } finally {
            exitPipMutation();
        }
    }

    private void cleanupInternal() throws IOException {
        
        Set<String> referenced = new HashSet<>();
        for (ConcurrentHashMap<String, Set<String>> inner : registry.values()) {
            referenced.addAll(inner.keySet());
        }
        File libsDir = getLibsDir();
        File[] subs = libsDir.listFiles();
        if (subs == null) return;
        for (File pkgDir : subs) {
            if (!pkgDir.isDirectory()) continue;
            if ("wheels".equals(pkgDir.getName()) || "site".equals(pkgDir.getName())) continue;
            String name = normalizePackageName(pkgDir.getName());
            if (!referenced.contains(name)) {
                FileUtils.deleteRecursive(pkgDir, true);
                if (pkgDir.exists()) {
                    throw new IOException(
                            "Could not remove orphan dependency directory "
                                    + pkgDir);
                }
            }
        }
    }

    private void removeOrphanedDirectories() throws IOException {
        LinkedHashSet<String> activeRoots =
                new LinkedHashSet<>();
        LinkedHashSet<String> activeWheels =
                new LinkedHashSet<>();
        for (RegistryRootDisk root : registryRuntimeRoots) {
            activeRoots.add(resolveArtifactPath(root.root)
                    .getCanonicalPath());
            activeWheels.add(resolveArtifactPath(root.wheel)
                    .getCanonicalPath());
        }
        Set<String> protectedRecovery =
                collectDeferredArtifactRecoveryPaths();
        LinkedHashSet<String> currentRuntimePaths =
                new LinkedHashSet<>();
        if (isPythonRuntimeUsable()) {
            currentRuntimePaths.addAll(
                    currentManagedImportPathsStrict());
            for (String path : currentRuntimePaths) {
                if (!activeRoots.contains(path)) {
                    throw restartRequired(
                            "inactive dependency root is still "
                                    + "published on sys.path: " + path);
                }
            }
        }

        File libsDir = getLibsDir();
        File[] children = libsDir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!child.isDirectory()) continue;
                
                if ("wheels".equals(child.getName()) || "site".equals(child.getName())) continue;
                String norm = normalizePackageName(child.getName());
                if (!isDistributionReferenced(norm)) {
                    FileUtils.deleteRecursive(child, true);
                    if (child.exists()) {
                        throw new IOException(
                                "Could not remove orphan dependency "
                                        + child);
                    }
                }
            }
        }
        
        File wheelsDir = getWheelsDir();
        File[] wheels = wheelsDir.listFiles();
        if (wheels != null) {
            for (File whl : wheels) {
                if (!whl.isFile()) continue;
                if (!whl.getName().endsWith(".whl")) continue;
                String canonical = whl.getCanonicalPath();
                if (!activeWheels.contains(canonical)
                        && !protectedRecovery.contains(canonical)) {
                    if (!whl.delete() && whl.exists()) {
                        throw new IOException(
                                "Could not remove orphan wheel " + whl);
                    }
                }
            }
        }
        File siteDir = new File(libsDir, "site");
        File[] extracted = siteDir.listFiles();
        if (extracted != null) {
            for (File directory : extracted) {
                if (!directory.isDirectory()
                        || directory.getName().startsWith(".")) {
                    continue;
                }
                String canonical =
                        directory.getCanonicalPath();
                if (!activeRoots.contains(canonical)
                        && !protectedRecovery.contains(canonical)) {
                    if (currentRuntimePaths.contains(canonical)) {
                        throw restartRequired(
                                "refusing to delete a published "
                                        + "inactive dependency root "
                                        + canonical);
                    }
                    FileUtils.deleteRecursive(directory, true);
                    if (directory.exists()) {
                        throw new IOException(
                                "Could not remove orphan extraction "
                                        + directory);
                    }
                }
            }
        }
        syncDirectoryStrict(wheelsDir);
        if (siteDir.exists()) {
            syncDirectoryStrict(siteDir);
        }
    }

    private static boolean isWheelReferenced(
            File artifact, Set<String> referenced) throws IOException {
        String owner = packageNameFromArtifact(artifact);
        if (owner.isEmpty()) {
            throw new IOException(
                    "Cannot determine dependency ownership for "
                            + artifact);
        }
        return referenced.contains(owner);
    }

    static String packageNameFromArtifact(File artifact) {
        if (artifact == null || !artifact.exists()) return "";
        try {
            if (artifact.isFile()) {
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(artifact)) {
                    java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        java.util.zip.ZipEntry entry = entries.nextElement();
                        if (!entry.isDirectory() && entry.getName().endsWith(".dist-info/METADATA")) {
                            try (InputStream in = zip.getInputStream(entry)) {
                                return readMetadataName(in);
                            }
                        }
                    }
                }
            } else if (artifact.isDirectory()) {
                File[] children = artifact.listFiles();
                if (children != null) {
                    for (File child : children) {
                        if (!child.isDirectory() || !child.getName().endsWith(".dist-info")) continue;
                        File metadata = new File(child, "METADATA");
                        if (!metadata.isFile()) continue;
                        try (InputStream in = new FileInputStream(metadata)) {
                            return readMetadataName(in);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            FileLog.w("nimarko: cannot read wheel ownership from " + artifact + ": " + t);
        }
        return "";
    }

    private static String readMetadataName(InputStream input) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.regionMatches(true, 0, "Name:", 0, 5)) {
                    return normalizePackageName(line.substring(5).trim());
                }
                if (line.isEmpty()) break;
            }
        }
        return "";
    }

    public synchronized boolean uninstallDependencies(String pluginId) {
        if (pluginId == null) return false;
        enterPipMutation();
        try {
            loadRegistryStrict();
            requireNoPendingArtifactTransactions();
            cleanupRequired = true;
            ConcurrentHashMap<String, Set<String>> inner =
                    registry.get(pluginId);
            ManagedRuntimeSnapshot before =
                    snapshotManagedRuntimeStrict(
                            Collections.emptySet());
            ManagedTransition transition =
                    buildUninstallTransition(
                            pluginId, inner, before);
            PreparedModuleEviction prepared =
                    prepareManagedModuleEviction(
                            transition.eviction);
            List<RegistryRootDisk> previousRoots =
                    copyRegistryRoots(registryRuntimeRoots);
            if (inner != null) {
                registry.remove(pluginId);
            }
            List<RegistryRootDisk> nextRoots =
                    runtimeRootsStillReferenced(
                            registryRuntimeRoots);
            registryRuntimeRoots.clear();
            registryRuntimeRoots.addAll(
                    copyRegistryRoots(nextRoots));
            ArrayList<String> desiredPaths =
                    registryImportPathsStrict(nextRoots);

            try {
                saveRegistryStrict();
            } catch (Throwable persistenceFailure) {
                if (inner != null) {
                    registry.put(pluginId, inner);
                }
                registryRuntimeRoots.clear();
                registryRuntimeRoots.addAll(previousRoots);
                try {
                    saveRegistryStrict();
                } catch (Throwable compensationFailure) {
                    persistenceFailure.addSuppressed(
                            compensationFailure);
                }
                throw persistenceFailure;
            }
            try {
                executeManagedRuntimeTransition(
                        before.orderedCanonicalPaths,
                        desiredPaths, prepared, nextRoots);
            } catch (Throwable lifecycleFailure) {
                if (inner != null) {
                    registry.put(pluginId, inner);
                }
                registryRuntimeRoots.clear();
                registryRuntimeRoots.addAll(previousRoots);
                try {
                    saveRegistryStrict();
                } catch (Throwable restoreFailure) {
                    lifecycleFailure.addSuppressed(
                            restoreFailure);
                }
                throw lifecycleFailure;
            }
            
            cleanupInternal();
            removeOrphanedDirectories();
            cleanupRequired = false;
            return true;
        } catch (RestartRequiredRuntimeException failure) {
            throw failure;
        } catch (Throwable t) {
            FileLog.e("PipController.uninstallDependencies failed for " + pluginId, t);
            return false;
        } finally {
            exitPipMutation();
        }
    }

    public synchronized DependencySnapshot snapshotState(String pluginId) {
        loadRegistryOrThrow();
        final List<String> registryPaths;
        try {
            registryPaths =
                    registryImportPathsStrict(
                            registryRuntimeRoots);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Cannot snapshot exact dependency roots",
                    failure);
        }
        final List<String> importPaths;
        if (isPythonRuntimeUsable()) {
            importPaths =
                    snapshotManagedRuntimeStrict(
                            Collections.emptySet())
                            .orderedImportPaths;
            if (!importPaths.equals(registryPaths)) {
                throw restartRequired(
                        "dependency snapshot does not match the "
                                + "versioned runtime registry");
            }
        } else {
            importPaths = registryPaths;
        }
        List<RegistryRootDisk> roots =
                copyRegistryRoots(registryRuntimeRoots);
        if (pluginId == null) {
            return new DependencySnapshot(
                    false, Collections.emptyMap(),
                    new ArrayList<>(importPaths), roots);
        }
        ConcurrentHashMap<String, Set<String>> source = registry.get(pluginId);
        if (source == null) {
            return new DependencySnapshot(
                    false, Collections.emptyMap(),
                    new ArrayList<>(importPaths), roots);
        }
        return new DependencySnapshot(
                true, deepCopyOwnership(source),
                new ArrayList<>(importPaths), roots);
    }

    public synchronized void writeDependencySnapshot(
            File target, String pluginId, String transactionId,
            DependencySnapshot snapshot) throws IOException {
        enterPipMutation();
        try {
            validateTransactionIdentity(pluginId, transactionId);
            if (target == null || snapshot == null) {
                throw new IOException(
                        "Dependency snapshot target is missing");
            }
            File parent = target.getParentFile();
            if (parent == null
                    || (!parent.exists() && !parent.mkdirs()
                            && !parent.exists())) {
                throw new IOException(
                        "Cannot create dependency snapshot directory");
            }
            DependencySnapshotDisk disk =
                    new DependencySnapshotDisk();
            disk.schema = DEPENDENCY_SNAPSHOT_SCHEMA;
            disk.pluginId = pluginId;
            disk.transactionId = transactionId;
            disk.present = snapshot.present;
            disk.ownership = deepCopyOwnership(snapshot.ownership);
            disk.managedImportPaths =
                    new ArrayList<>(snapshot.managedImportPaths);
            disk.managedRoots =
                    copyRegistryRoots(snapshot.managedRoots);
            disk.checksum = null;
            disk.checksum = sha256Hex(gson.toJson(disk)
                    .getBytes(StandardCharsets.UTF_8));
            byte[] payload = gson.toJson(disk)
                    .getBytes(StandardCharsets.UTF_8);
            if (payload.length > MAX_TRANSACTION_BYTES) {
                throw new IOException(
                        "Dependency rollback snapshot is too large");
            }

            File staged = new File(
                    parent,
                    target.getName() + ".new."
                            + Long.toHexString(System.nanoTime()));
            try {
                try (FileOutputStream out =
                        new FileOutputStream(staged)) {
                    out.write(payload);
                    out.flush();
                    out.getFD().sync();
                }
                try {
                    android.system.Os.rename(
                            staged.getAbsolutePath(),
                            target.getAbsolutePath());
                } catch (android.system.ErrnoException failure) {
                    throw new IOException(
                            "Cannot publish dependency rollback snapshot",
                            failure);
                }
                syncDirectoryStrict(parent);
            } finally {
                if (staged.exists() && !staged.delete()) {
                    FileLog.w("Could not remove dependency snapshot "
                            + "stage " + staged);
                }
            }
        } finally {
            exitPipMutation();
        }
    }

    public synchronized DependencySnapshot readDependencySnapshot(
            File source, String pluginId, String transactionId)
            throws IOException {
        validateTransactionIdentity(pluginId, transactionId);
        if (!registryLoaded) {
            loadRegistryStrict(false);
        }
        byte[] bytes = readBoundedFile(
                source, MAX_TRANSACTION_BYTES,
                "dependency rollback snapshot");
        final DependencySnapshotDisk disk;
        try {
            disk = gson.fromJson(
                    new String(bytes, StandardCharsets.UTF_8),
                    DependencySnapshotDisk.class);
        } catch (Throwable failure) {
            throw new IOException(
                    "Dependency rollback snapshot is corrupt", failure);
        }
        if (disk == null) {
            throw new IOException(
                    "Dependency rollback snapshot is empty");
        }
        if ((disk.schema != DEPENDENCY_SNAPSHOT_SCHEMA
                    && disk.schema != TRANSACTION_SCHEMA)
                || !pluginId.equals(disk.pluginId)
                || !transactionId.equals(disk.transactionId)
                || disk.checksum == null) {
            throw new IOException(
                    "Dependency rollback snapshot identity mismatch");
        }
        String expectedChecksum = disk.checksum;
        disk.checksum = null;
        String actualChecksum = sha256Hex(gson.toJson(disk)
                .getBytes(StandardCharsets.UTF_8));
        disk.checksum = expectedChecksum;
        if (!expectedChecksum.equals(actualChecksum)) {
            throw new IOException(
                    "Dependency rollback snapshot checksum mismatch");
        }
        if (disk.ownership == null
                || disk.managedImportPaths == null) {
            throw new IOException(
                    "Dependency rollback snapshot is incomplete");
        }
        Map<String, Map<String, Set<String>>> ownerWrapper =
                new LinkedHashMap<>();
        ownerWrapper.put(pluginId, disk.ownership);
        Map<String, Set<String>> ownership =
                deepCopyOwnership(
                        validateRegistryOwnership(ownerWrapper)
                                .get(pluginId));
        if (!disk.present && !ownership.isEmpty()) {
            throw new IOException(
                    "Absent dependency snapshot owns packages");
        }
        ArrayList<String> importPaths = new ArrayList<>();
        LinkedHashSet<String> uniquePaths =
                new LinkedHashSet<>();
        for (String path : disk.managedImportPaths) {
            if (path == null
                    || !new File(path).isAbsolute()
                    || !isManagedImportPath(path)) {
                throw new IOException(
                        "Dependency rollback snapshot has an "
                                + "unmanaged import path");
            }
            String canonical =
                    new File(path).getCanonicalPath();
            if (!uniquePaths.add(canonical)) {
                throw new IOException(
                        "Dependency rollback snapshot has a "
                                + "duplicate import path");
            }
            importPaths.add(canonical);
        }
        List<RegistryRootDisk> roots;
        if (disk.schema == DEPENDENCY_SNAPSHOT_SCHEMA) {
            roots = validateRegistryRootRecords(
                    disk.managedRoots, true);
            if (!importPaths.equals(
                    registryImportPathsStrict(roots))) {
                throw new IOException(
                        "Dependency rollback snapshot root order "
                                + "does not match its paths");
            }
            LinkedHashMap<String, Map<String, Set<String>>>
                    restoredOwnership = new LinkedHashMap<>();
            for (Map.Entry<String,
                    ConcurrentHashMap<String, Set<String>>> owner
                    : registry.entrySet()) {
                if (!pluginId.equals(owner.getKey())) {
                    restoredOwnership.put(
                            owner.getKey(),
                            deepCopyOwnership(owner.getValue()));
                }
            }
            if (disk.present) {
                restoredOwnership.put(
                        pluginId,
                        deepCopyOwnership(ownership));
            }
            validateRegistryCoverage(
                    restoredOwnership, roots);
        } else {
            
            roots = Collections.emptyList();
        }
        return new DependencySnapshot(
                disk.present, ownership, importPaths,
                copyRegistryRoots(roots));
    }

    public synchronized void beginDeferredArtifactTransaction(
            String pluginId, String transactionId) throws IOException {
        enterPipMutation();
        try {
            validateTransactionIdentity(pluginId, transactionId);
            if (!registryLoaded) {
                loadRegistryStrict(false);
            }
            requireNoPendingArtifactTransactions();
            File journal = deferredArtifactJournal(pluginId);
            if (journal.exists()) {
                throw new IOException(
                        "A dependency artifact transaction is already "
                                + "pending for " + pluginId);
            }
            DeferredArtifactJournal data =
                    new DeferredArtifactJournal();
            data.schema = TRANSACTION_SCHEMA;
            data.pluginId = pluginId;
            data.transactionId = transactionId;
            data.state = ARTIFACT_STATE_PREPARED;
            data.outerSourceTransaction = true;
            data.previousOwnership = Collections.emptyMap();
            data.previousRuntimeRoots =
                    copyRegistryRoots(registryRuntimeRoots);
            writeDeferredArtifactJournal(journal, data);
            activeDeferredArtifactTransactions.add(
                    deferredArtifactKey(pluginId, transactionId));
        } finally {
            exitPipMutation();
        }
    }

    public synchronized Set<String>
            getPendingDeferredArtifactPluginIds() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        File[] journals = getLibsDir().listFiles((dir, name) ->
                DEFERRED_ARTIFACT_JOURNAL_PATTERN.matcher(name)
                        .matches());
        if (journals == null) return result;
        Arrays.sort(journals, Comparator.comparing(File::getName));
        for (File file : journals) {
            Matcher matcher = DEFERRED_ARTIFACT_JOURNAL_PATTERN
                    .matcher(file.getName());
            if (!matcher.matches()) continue;
            String pluginId = matcher.group(1);
            result.add(pluginId);
            try {
                readDeferredArtifactJournal(pluginId, null);
            } catch (Throwable failure) {
                FileLog.e("Corrupt dependency artifact journal for "
                        + pluginId + "; plugin remains blocked", failure);
            }
        }
        return result;
    }

    public synchronized boolean hasOuterArtifactTransaction(
            String pluginId, String transactionId) {
        try {
            DeferredArtifactJournal journal =
                    readDeferredArtifactJournal(
                            pluginId, transactionId);
            return journal != null
                    && journal.outerSourceTransaction;
        } catch (Throwable failure) {
            FileLog.e("Could not validate dependency transaction for "
                    + pluginId, failure);
            return false;
        }
    }

    public synchronized boolean
            rollbackPendingDeferredArtifactTransaction(String pluginId) {
        enterPipMutation();
        try {
            loadRegistryStrict();
            DeferredArtifactJournal journal =
                    readDeferredArtifactJournal(pluginId, null);
            if (journal == null) return true;
            if (!journal.outerSourceTransaction
                    && ARTIFACT_STATE_REGISTRY_COMMITTED.equals(
                            journal.state)) {
                return commitDeferredArtifactTransaction(
                        pluginId, journal.transactionId);
            }
            return rollbackDeferredArtifactTransaction(
                    pluginId, journal.transactionId);
        } catch (RestartRequiredRuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            FileLog.e("Could not recover orphan dependency artifact "
                    + "transaction for " + pluginId, failure);
            return false;
        } finally {
            exitPipMutation();
        }
    }

    public synchronized boolean commitDeferredArtifactTransaction(
            String pluginId, String transactionId) {
        enterPipMutation();
        try {
            DeferredArtifactJournal journal =
                    readDeferredArtifactJournal(
                            pluginId, transactionId);
            if (journal == null) {
                activeDeferredArtifactTransactions.remove(
                        deferredArtifactKey(
                                pluginId, transactionId));
                return true;
            }
            if (!journal.outerSourceTransaction
                    && !ARTIFACT_STATE_REGISTRY_COMMITTED.equals(
                            journal.state)) {
                throw new IOException(
                        "Local dependency transaction is not committed");
            }
            for (DeferredArtifactEntry entry : journal.entries) {
                File backup = resolveArtifactPath(entry.backup);
                File staged = resolveArtifactPath(entry.staged);
                if (!deleteArtifactAndVerify(backup)
                        || !deleteArtifactAndVerify(staged)) {
                    throw new IOException(
                            "Could not finalize dependency artifact "
                                    + entry.target);
                }
                syncDirectoryStrict(backup.getParentFile());
            }
            File file = deferredArtifactJournal(pluginId);
            if (!file.delete() && file.exists()) {
                throw new IOException(
                        "Could not remove dependency artifact journal");
            }
            syncDirectoryStrict(file.getParentFile());
            activeDeferredArtifactTransactions.remove(
                    deferredArtifactKey(pluginId, transactionId));
            pendingRollbackModuleEvictions.remove(pluginId);
            pendingRollbackModuleEvictionFailures.remove(pluginId);
            return true;
        } catch (RestartRequiredRuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            FileLog.e("Could not commit dependency artifact transaction for "
                    + pluginId, failure);
            return false;
        } finally {
            exitPipMutation();
        }
    }

    public synchronized boolean rollbackDeferredArtifactTransaction(
            String pluginId, String transactionId) {
        enterPipMutation();
        try {
            DeferredArtifactJournal journal =
                    readDeferredArtifactJournal(
                            pluginId, transactionId);
            if (journal == null) {
                activeDeferredArtifactTransactions.remove(
                        deferredArtifactKey(
                                pluginId, transactionId));
                return true;
            }
            List<RegistryRootDisk> previousRoots =
                    validateRegistryRootRecords(
                            journal.previousRuntimeRoots != null
                                    ? journal.previousRuntimeRoots
                                    : Collections.emptyList(),
                            true);
            ArrayList<String> previousPaths =
                    registryImportPathsStrict(previousRoots);
            ManagedModuleEvictionPlan rollbackEviction =
                    captureRollbackModuleEviction(
                            journal, previousRoots);

            Map<String, Set<String>> candidateOwnership = null;
            List<RegistryRootDisk> candidateRoots = null;
            boolean localRegistryRestored = false;
            if (!journal.outerSourceTransaction) {
                candidateOwnership =
                        registry.containsKey(pluginId)
                                ? deepCopyOwnership(
                                        registry.get(pluginId))
                                : null;
                candidateRoots =
                        copyRegistryRoots(registryRuntimeRoots);
                restoreRegistryOwnership(journal);
                registryRuntimeRoots.clear();
                registryRuntimeRoots.addAll(
                        copyRegistryRoots(previousRoots));
                try {
                    saveRegistryStrict();
                } catch (Throwable persistenceFailure) {
                    if (candidateOwnership != null) {
                        registry.put(
                                pluginId,
                                new ConcurrentHashMap<>(
                                        candidateOwnership));
                    } else {
                        registry.remove(pluginId);
                    }
                    registryRuntimeRoots.clear();
                    registryRuntimeRoots.addAll(candidateRoots);
                    try {
                        
                        saveRegistryStrict();
                    } catch (Throwable compensationFailure) {
                        persistenceFailure.addSuppressed(
                                compensationFailure);
                    }
                    throw persistenceFailure;
                }
                localRegistryRestored = true;
            }

            if (isPythonRuntimeUsable()) {
                ArrayList<String> currentPaths =
                        currentManagedImportPathsStrict();
                PreparedModuleEviction prepared =
                        prepareManagedModuleEviction(
                                rollbackEviction);
                
                try {
                    executeManagedRuntimeTransition(
                            currentPaths, previousPaths, prepared,
                            previousRoots);
                } catch (Throwable lifecycleFailure) {
                    if (localRegistryRestored) {
                        if (candidateOwnership != null) {
                            registry.put(
                                    pluginId,
                                    new ConcurrentHashMap<>(
                                            candidateOwnership));
                        } else {
                            registry.remove(pluginId);
                        }
                        registryRuntimeRoots.clear();
                        registryRuntimeRoots.addAll(
                                copyRegistryRoots(candidateRoots));
                        try {
                            
                            saveRegistryStrict();
                        } catch (Throwable compensationFailure) {
                            lifecycleFailure.addSuppressed(
                                    compensationFailure);
                        }
                    }
                    throw lifecycleFailure;
                }
            }
            for (int index = journal.entries.size() - 1;
                    index >= 0; index--) {
                DeferredArtifactEntry entry =
                        journal.entries.get(index);
                rollbackDeferredArtifactEntry(entry);
            }
            File file = deferredArtifactJournal(pluginId);
            if (!file.delete() && file.exists()) {
                throw new IOException(
                        "Could not remove dependency artifact journal");
            }
            syncDirectoryStrict(file.getParentFile());
            activeDeferredArtifactTransactions.remove(
                    deferredArtifactKey(pluginId, transactionId));
            pendingRollbackModuleEvictions.remove(pluginId);
            pendingRollbackModuleEvictionFailures.remove(pluginId);
            return true;
        } catch (RestartRequiredRuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            FileLog.e("Could not roll back dependency artifact transaction "
                    + "for " + pluginId, failure);
            return false;
        } finally {
            exitPipMutation();
        }
    }

    public synchronized boolean discardDeferredArtifactTransaction(
            String pluginId) {
        enterPipMutation();
        try {
            DeferredArtifactJournal journal =
                    readDeferredArtifactJournal(pluginId, null);
            if (journal == null) return true;
            for (DeferredArtifactEntry entry : journal.entries) {
                if (!deleteArtifactAndVerify(
                                resolveArtifactPath(entry.backup))
                        || !deleteArtifactAndVerify(
                                resolveArtifactPath(entry.staged))) {
                    throw new IOException(
                            "Could not discard dependency recovery artifact");
                }
            }
            File file = deferredArtifactJournal(pluginId);
            if (!file.delete() && file.exists()) {
                throw new IOException(
                        "Could not discard dependency artifact journal");
            }
            syncDirectoryStrict(file.getParentFile());
            activeDeferredArtifactTransactions.removeIf(
                    key -> key.startsWith(pluginId + ":"));
            pendingRollbackModuleEvictions.remove(pluginId);
            pendingRollbackModuleEvictionFailures.remove(pluginId);
            return true;
        } catch (RestartRequiredRuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            FileLog.e("Could not discard dependency artifact transaction "
                    + "for " + pluginId, failure);
            return false;
        } finally {
            exitPipMutation();
        }
    }

    private boolean appendDeferredArtifactEntries(
            String pluginId, List<StagedReplacement> replacements)
            throws IOException {
        String keyPrefix = pluginId + ":";
        String activeKey = null;
        for (String key : activeDeferredArtifactTransactions) {
            if (key.startsWith(keyPrefix)) {
                activeKey = key;
                break;
            }
        }
        if (activeKey == null) return false;
        String transactionId =
                activeKey.substring(keyPrefix.length());
        DeferredArtifactJournal journal =
                readDeferredArtifactJournal(
                        pluginId, transactionId);
        if (journal == null) {
            throw new IOException(
                    "Dependency artifact journal disappeared");
        }
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (DeferredArtifactEntry existing : journal.entries) {
            targets.add(existing.target);
        }
        for (StagedReplacement replacement : replacements) {
            syncTreeStrict(replacement.staged);
            syncDirectoryStrict(
                    replacement.staged.getParentFile());
            DeferredArtifactEntry entry =
                    new DeferredArtifactEntry();
            entry.target = relativeArtifactPath(replacement.target);
            entry.staged = relativeArtifactPath(replacement.staged);
            entry.backup = relativeArtifactPath(replacement.backup);
            entry.hadTarget = replacement.target.exists();
            if (!targets.add(entry.target)) {
                throw new IOException(
                        "Duplicate dependency artifact target "
                                + entry.target);
            }
            journal.entries.add(entry);
        }
        writeDeferredArtifactJournal(
                deferredArtifactJournal(pluginId), journal);
        return true;
    }

    private void beginLocalArtifactTransaction(
            String pluginId, String transactionId,
            Map<String, Set<String>> previousOwnership)
            throws IOException {
        validateTransactionIdentity(pluginId, transactionId);
        requireNoPendingArtifactTransactions();
        DeferredArtifactJournal journal =
                new DeferredArtifactJournal();
        journal.schema = TRANSACTION_SCHEMA;
        journal.pluginId = pluginId;
        journal.transactionId = transactionId;
        journal.state = ARTIFACT_STATE_PREPARED;
        journal.outerSourceTransaction = false;
        journal.previousOwnershipPresent =
                previousOwnership != null;
        journal.previousOwnership =
                previousOwnership != null
                        ? deepCopyOwnership(previousOwnership)
                        : Collections.emptyMap();
        journal.previousRuntimeRoots =
                copyRegistryRoots(registryRuntimeRoots);
        writeDeferredArtifactJournal(
                deferredArtifactJournal(pluginId), journal);
        activeDeferredArtifactTransactions.add(
                deferredArtifactKey(pluginId, transactionId));
    }

    private void markLocalArtifactRegistryCommitted(
            String pluginId, String transactionId)
            throws IOException {
        DeferredArtifactJournal journal =
                readDeferredArtifactJournal(
                        pluginId, transactionId);
        if (journal == null || journal.outerSourceTransaction) {
            throw new IOException(
                    "Local dependency transaction is missing");
        }
        journal.state =
                ARTIFACT_STATE_REGISTRY_COMMITTED;
        writeDeferredArtifactJournal(
                deferredArtifactJournal(pluginId), journal);
    }

    private void restoreRegistryOwnership(
            DeferredArtifactJournal journal) {
        if (journal.previousOwnershipPresent) {
            ConcurrentHashMap<String, Set<String>> restored =
                    new ConcurrentHashMap<>();
            Map<String, Set<String>> ownership =
                    journal.previousOwnership != null
                            ? journal.previousOwnership
                            : Collections.emptyMap();
            for (Map.Entry<String, Set<String>> entry
                    : ownership.entrySet()) {
                restored.put(
                        entry.getKey(),
                        new LinkedHashSet<>(
                                entry.getValue() != null
                                        ? entry.getValue()
                                        : Collections.emptySet()));
            }
            registry.put(journal.pluginId, restored);
        } else {
            registry.remove(journal.pluginId);
        }
    }

    private void recoverLocalArtifactTransactions()
            throws IOException {
        if (recoveringLocalArtifactTransactions) return;
        recoveringLocalArtifactTransactions = true;
        try {
            File[] files = getLibsDir().listFiles((dir, name) ->
                    DEFERRED_ARTIFACT_JOURNAL_PATTERN
                            .matcher(name).matches());
            if (files == null) return;
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (File file : files) {
                Matcher matcher =
                        DEFERRED_ARTIFACT_JOURNAL_PATTERN
                                .matcher(file.getName());
                if (!matcher.matches()) continue;
                String pluginId = matcher.group(1);
                DeferredArtifactJournal journal =
                        readDeferredArtifactJournal(pluginId, null);
                if (journal == null
                        || journal.outerSourceTransaction) {
                    continue;
                }
                boolean recovered =
                        ARTIFACT_STATE_REGISTRY_COMMITTED.equals(
                                journal.state)
                                ? commitDeferredArtifactTransaction(
                                        pluginId,
                                        journal.transactionId)
                                : rollbackDeferredArtifactTransaction(
                                        pluginId,
                                        journal.transactionId);
                if (!recovered) {
                    throw new IOException(
                            "Could not recover local dependency "
                                    + "transaction for " + pluginId);
                }
            }
        } finally {
            recoveringLocalArtifactTransactions = false;
        }
    }

    private void requireNoPendingArtifactTransactions()
            throws IOException {
        File[] journals = getLibsDir().listFiles((dir, name) ->
                DEFERRED_ARTIFACT_JOURNAL_PATTERN
                        .matcher(name).matches());
        if (journals != null && journals.length > 0) {
            throw new IOException(
                    "A dependency transaction is pending recovery");
        }
    }

    private String activeArtifactTransactionId(
            String pluginId) throws IOException {
        String prefix = pluginId + ":";
        String result = null;
        for (String key : activeDeferredArtifactTransactions) {
            if (!key.startsWith(prefix)) continue;
            if (result != null) {
                throw new IOException(
                        "Multiple dependency transactions are active for "
                                + pluginId);
            }
            result = key.substring(prefix.length());
        }
        return result;
    }

    private void requireOnlyActiveArtifactTransaction(
            String pluginId, String transactionId)
            throws IOException {
        Set<String> pending =
                getPendingDeferredArtifactPluginIds();
        if (pending.size() != 1
                || !pending.contains(pluginId)
                || transactionId == null) {
            throw new IOException(
                    "Another dependency transaction is pending recovery");
        }
        DeferredArtifactJournal journal =
                readDeferredArtifactJournal(
                        pluginId, transactionId);
        if (journal == null) {
            throw new IOException(
                    "Active dependency transaction disappeared");
        }
    }

    private void rollbackDeferredArtifactEntry(
            DeferredArtifactEntry entry) throws IOException {
        File target = resolveArtifactPath(entry.target);
        File staged = resolveArtifactPath(entry.staged);
        File backup = resolveArtifactPath(entry.backup);
        if (entry.hadTarget) {
            if (backup.exists()) {
                if (!deleteArtifactAndVerify(target)) {
                    throw new IOException(
                            "Could not park rejected dependency artifact "
                                    + target);
                }
                try {
                    android.system.Os.rename(
                            backup.getAbsolutePath(),
                            target.getAbsolutePath());
                } catch (android.system.ErrnoException failure) {
                    throw new IOException(
                            "Could not restore dependency artifact "
                                    + target,
                            failure);
                }
                syncDirectoryStrict(target.getParentFile());
            } else if (staged.exists()) {
                
            } else if (!target.exists()) {
                throw new IOException(
                        "Old dependency artifact is unrecoverable: "
                                + target);
            }
        } else if (!deleteArtifactAndVerify(target)) {
            throw new IOException(
                    "Could not remove rejected dependency artifact "
                            + target);
        }
        if (!deleteArtifactAndVerify(staged)) {
            throw new IOException(
                    "Could not remove dependency stage " + staged);
        }
        syncDirectoryStrict(target.getParentFile());
    }

    private DeferredArtifactJournal readDeferredArtifactJournal(
            String pluginId, String expectedTransactionId)
            throws IOException {
        File source = deferredArtifactJournal(pluginId);
        if (!source.exists()) return null;
        byte[] bytes = readBoundedFile(
                source, MAX_TRANSACTION_BYTES,
                "dependency artifact journal");
        final DeferredArtifactJournal journal;
        try {
            journal = gson.fromJson(
                    new String(bytes, StandardCharsets.UTF_8),
                    DeferredArtifactJournal.class);
        } catch (Throwable failure) {
            throw new IOException(
                    "Dependency artifact journal is corrupt", failure);
        }
        if (journal == null
                || (journal.schema != TRANSACTION_SCHEMA
                        && journal.schema
                                != LEGACY_TRANSACTION_SCHEMA)
                || !pluginId.equals(journal.pluginId)
                || journal.transactionId == null
                || !TRANSACTION_ID_PATTERN.matcher(
                        journal.transactionId).matches()
                || (expectedTransactionId != null
                        && !expectedTransactionId.equals(
                                journal.transactionId))
                || journal.entries == null) {
            throw new IOException(
                    "Dependency artifact journal identity mismatch");
        }
        if (journal.schema == LEGACY_TRANSACTION_SCHEMA) {
            
            journal.state = ARTIFACT_STATE_PREPARED;
            journal.outerSourceTransaction = true;
            journal.previousRuntimeRoots =
                    Collections.emptyList();
        } else {
            if ((!ARTIFACT_STATE_PREPARED.equals(journal.state)
                    && !ARTIFACT_STATE_REGISTRY_COMMITTED.equals(
                            journal.state))
                    || journal.checksum == null) {
                throw new IOException(
                        "Dependency artifact journal state mismatch");
            }
            String expectedChecksum = journal.checksum;
            journal.checksum = null;
            String actualChecksum =
                    sha256Hex(gson.toJson(journal)
                            .getBytes(StandardCharsets.UTF_8));
            journal.checksum = expectedChecksum;
            if (!expectedChecksum.equals(actualChecksum)) {
                throw new IOException(
                        "Dependency artifact journal checksum mismatch");
            }
            if (journal.previousRuntimeRoots == null) {
                
                journal.previousRuntimeRoots =
                        Collections.emptyList();
            } else {
                journal.previousRuntimeRoots =
                        validateRegistryRootRecords(
                                journal.previousRuntimeRoots,
                                false);
            }
        }
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (DeferredArtifactEntry entry : journal.entries) {
            if (entry == null) {
                throw new IOException(
                        "Dependency artifact journal has a null entry");
            }
            File target = resolveArtifactPath(entry.target);
            File staged = resolveArtifactPath(entry.staged);
            File backup = resolveArtifactPath(entry.backup);
            if (!targets.add(target.getCanonicalPath())
                    || !target.getParentFile().getCanonicalFile()
                            .equals(staged.getParentFile()
                                    .getCanonicalFile())
                    || !target.getParentFile().getCanonicalFile()
                            .equals(backup.getParentFile()
                                    .getCanonicalFile())
                    || !backup.getName().equals(
                            "." + target.getName() + "."
                                    + journal.transactionId
                                    + ".backup")
                    || !staged.getName().startsWith(
                            "." + target.getName() + "."
                                    + journal.transactionId + ".")
                    || !staged.getName().endsWith(".stage")) {
                throw new IOException(
                        "Dependency artifact journal path mismatch");
            }
        }
        return journal;
    }

    private void writeDeferredArtifactJournal(
            File target, DeferredArtifactJournal journal)
            throws IOException {
        if (journal == null
                || journal.schema != TRANSACTION_SCHEMA
                || journal.pluginId == null
                || journal.transactionId == null
                || journal.previousRuntimeRoots == null) {
            throw new IOException(
                    "Invalid dependency artifact journal");
        }
        journal.previousRuntimeRoots =
                validateRegistryRootRecords(
                        journal.previousRuntimeRoots, false);
        journal.checksum = null;
        journal.checksum =
                sha256Hex(gson.toJson(journal)
                        .getBytes(StandardCharsets.UTF_8));
        byte[] payload = gson.toJson(journal)
                .getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_TRANSACTION_BYTES) {
            throw new IOException(
                    "Dependency artifact journal is too large");
        }
        File staged = new File(
                target.getParentFile(),
                target.getName() + ".new."
                        + Long.toHexString(System.nanoTime()));
        try {
            try (FileOutputStream output =
                    new FileOutputStream(staged)) {
                output.write(payload);
                output.flush();
                output.getFD().sync();
            }
            android.system.Os.rename(
                    staged.getAbsolutePath(),
                    target.getAbsolutePath());
            syncDirectoryStrict(target.getParentFile());
        } catch (android.system.ErrnoException failure) {
            throw new IOException(
                    "Could not publish dependency artifact journal",
                    failure);
        } finally {
            if (staged.exists() && !staged.delete()) {
                FileLog.w("Could not remove dependency journal stage "
                        + staged);
            }
        }
    }

    private File deferredArtifactJournal(String pluginId) {
        return new File(
                getLibsDir(),
                ".plugin-update-" + pluginId
                        + ".artifacts.json");
    }

    private static String deferredArtifactKey(
            String pluginId, String transactionId) {
        return pluginId + ":" + transactionId;
    }

    private String relativeArtifactPath(File artifact)
            throws IOException {
        String root = getLibsDir().getCanonicalPath();
        String path = artifact.getCanonicalPath();
        if (!path.startsWith(root + File.separator)) {
            throw new IOException(
                    "Dependency artifact escapes its private directory");
        }
        return path.substring(root.length() + 1);
    }

    private File resolveArtifactPath(String relative)
            throws IOException {
        if (relative == null || relative.isEmpty()
                || relative.startsWith("/")
                || relative.contains("..")
                || relative.contains("\\")) {
            throw new IOException(
                    "Invalid dependency artifact path");
        }
        File result = new File(getLibsDir(), relative);
        
        relativeArtifactPath(result);
        return result;
    }

    public synchronized boolean restoreState(
            String pluginId, DependencySnapshot snapshot) {
        if (pluginId == null || snapshot == null) return false;
        enterPipMutation();
        try {
            
            loadRegistryStrict(false);
            requireNoPendingArtifactTransactions();
            cleanupRequired = true;
            String pendingFailure =
                    pendingRollbackModuleEvictionFailures
                            .get(pluginId);
            if (pendingFailure != null) {
                throw restartRequired(pendingFailure);
            }
            ManagedModuleEvictionPlan pending =
                    pendingRollbackModuleEvictions.get(pluginId);
            LinkedHashSet<String> allowedMissing =
                    new LinkedHashSet<>();
            if (pending != null) {
                allowedMissing.addAll(
                        pending.importRootsByPath.keySet());
            }
            allowedMissing.addAll(
                    registryImportPathsStrict(
                            registryRuntimeRoots));
            ManagedRuntimeSnapshot currentRuntime =
                    snapshotManagedRuntimeStrict(allowedMissing);

            List<RegistryRootDisk> desiredRoots;
            if (snapshot.managedRoots != null
                    && !snapshot.managedRoots.isEmpty()) {
                desiredRoots = validateRegistryRootRecords(
                        snapshot.managedRoots, true);
            } else {
                
                ArrayList<RegistryRootDisk> upgraded =
                        new ArrayList<>();
                for (String path
                        : snapshot.managedImportPaths) {
                    File root = new File(path);
                    ManagedDistributionRoot metadata =
                            readManagedDistributionRoot(
                                    root, root);
                    File wheel = new File(
                            getWheelsDir(),
                            root.getName() + ".whl");
                    if (!wheel.isFile()) {
                        throw restartRequired(
                                "rollback wheel is missing for "
                                        + path);
                    }
                    inspectPureWheel(wheel);
                    upgraded.add(
                            registryRootForManaged(
                                    metadata,
                                    calculateSha256(wheel)));
                }
                desiredRoots = validateRegistryRootRecords(
                        upgraded, true);
            }
            ArrayList<String> desiredPaths =
                    registryImportPathsStrict(desiredRoots);
            ArrayList<String> snapshotPaths =
                    new ArrayList<>(
                            canonicalManagedPaths(
                                    snapshot.managedImportPaths));
            if (!desiredPaths.equals(snapshotPaths)) {
                throw restartRequired(
                        "rollback root order does not match "
                                + "the durable snapshot");
            }

            Map<String, Set<String>> currentOwnership =
                    registry.containsKey(pluginId)
                            ? deepCopyOwnership(
                                    registry.get(pluginId))
                            : Collections.emptyMap();
            ManagedTransition transition =
                    buildRestoreTransition(
                            pluginId, currentOwnership,
                            snapshot, currentRuntime, pending);
            PreparedModuleEviction prepared =
                    prepareManagedModuleEviction(
                            transition.eviction);
            if (snapshot.present) {
                ConcurrentHashMap<String, Set<String>> restored =
                        new ConcurrentHashMap<>();
                for (Map.Entry<String, Set<String>> entry
                        : snapshot.ownership.entrySet()) {
                    restored.put(entry.getKey(), new LinkedHashSet<>(
                            entry.getValue() != null
                                    ? entry.getValue()
                                    : Collections.emptySet()));
                }
                registry.put(pluginId, restored);
            } else {
                registry.remove(pluginId);
            }
            registryRuntimeRoots.clear();
            registryRuntimeRoots.addAll(
                    copyRegistryRoots(desiredRoots));

            saveRegistryStrict();
            if (isPythonRuntimeUsable()) {
                executeManagedRuntimeTransition(
                        currentRuntime.orderedCanonicalPaths,
                        desiredPaths, prepared, desiredRoots);
            }
            pendingRollbackModuleEvictions.remove(pluginId);
            pendingRollbackModuleEvictionFailures.remove(pluginId);

            try {
                cleanupInternal();
                removeOrphanedDirectories();
                cleanupRequired = false;
            } catch (RestartRequiredRuntimeException failure) {
                throw failure;
            } catch (Throwable cleanupFailure) {
                FileLog.e("PipController.restoreState committed for "
                        + pluginId + "; inactive artifact cleanup "
                        + "deferred", cleanupFailure);
            }
            return true;
        } catch (RestartRequiredRuntimeException failure) {
            FileLog.e("PipController.restoreState requires a process "
                    + "restart for " + pluginId, failure);
            throw failure;
        } catch (Throwable failure) {
            FileLog.e("PipController.restoreState failed for "
                    + pluginId, failure);
            if (isPythonRuntimeUsable()) {
                throw restartRequired(
                        "dependency rollback could not publish the "
                                + "restored generation",
                        failure);
            }
            return false;
        } finally {
            exitPipMutation();
        }
    }

    private static Map<String, Set<String>> deepCopyOwnership(
            Map<String, Set<String>> source) {
        LinkedHashMap<String, Set<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : source.entrySet()) {
            copy.put(entry.getKey(), new LinkedHashSet<>(
                    entry.getValue() != null
                            ? entry.getValue()
                            : Collections.emptySet()));
        }
        return copy;
    }

    private ArrayList<String> currentManagedImportPathsStrict()
            throws IOException {
        if (!isPythonRuntimeUsable()) {
            return new ArrayList<>();
        }
        ArrayList<String> paths = new ArrayList<>();
        try {
            com.chaquo.python.PyObject pythonPath =
                    getStartedPython().getModule("sys").get("path");
            if (pythonPath == null) {
                throw new IOException(
                        "Python sys.path is unavailable");
            }
            for (com.chaquo.python.PyObject value : pythonPath.asList()) {
                String path = value != null ? value.toString() : null;
                if (!isManagedImportPath(path)) continue;
                String canonical =
                        new File(path).getCanonicalPath();
                if (paths.contains(canonical)) {
                    throw new IOException(
                            "Python sys.path contains a duplicate "
                                    + "managed dependency root");
                }
                paths.add(canonical);
            }
        } catch (Throwable failure) {
            if (failure instanceof IOException) {
                throw (IOException) failure;
            }
            throw new IOException(
                    "Unable to snapshot plugin import paths",
                    failure);
        }
        return paths;
    }

    private boolean isManagedImportPath(String path) {
        if (path == null || path.isEmpty()) return false;
        try {
            String root = new File(getLibsDir(), "site")
                    .getCanonicalPath();
            String candidate = new File(path).getCanonicalPath();
            return candidate.startsWith(root + File.separator);
        } catch (IOException ignored) {
            return false;
        }
    }

    private ManagedDistributionRoot readManagedDistributionRoot(
            File physicalRoot, File logicalRoot) throws IOException {
        if (physicalRoot == null || logicalRoot == null
                || !physicalRoot.isDirectory()
                || !isManagedImportPath(
                        logicalRoot.getAbsolutePath())) {
            throw new IOException(
                    "Managed dependency extraction root is invalid");
        }
        String physicalCanonical =
                physicalRoot.getCanonicalPath();
        File[] children = physicalRoot.listFiles();
        if (children == null) {
            throw new IOException(
                    "Cannot inspect dependency extraction "
                            + physicalRoot);
        }
        File distInfo = null;
        for (File child : children) {
            if (!child.isDirectory()
                    || !child.getName().endsWith(".dist-info")) {
                continue;
            }
            String childCanonical = child.getCanonicalPath();
            if (!childCanonical.startsWith(
                    physicalCanonical + File.separator)) {
                throw new IOException(
                        "Dependency metadata escapes its extraction root");
            }
            if (distInfo != null) {
                throw new IOException(
                        "Dependency extraction contains multiple "
                                + "dist-info directories");
            }
            distInfo = child;
        }
        if (distInfo == null) {
            throw new IOException(
                    "Dependency extraction has no dist-info metadata");
        }

        File metadata = new File(distInfo, "METADATA");
        byte[] metadataBytes = readBoundedFile(
                metadata, MAX_DISTRIBUTION_METADATA_BYTES,
                "dependency METADATA");
        String distribution = null;
        String version = null;
        String[] metadataLines =
                new String(metadataBytes, StandardCharsets.UTF_8)
                        .split("\\r?\\n");
        for (String line : metadataLines) {
            if (line.isEmpty()) break;
            if (line.regionMatches(true, 0, "Name:", 0, 5)) {
                distribution = normalizePackageName(
                        line.substring(5).trim());
            } else if (line.regionMatches(
                    true, 0, "Version:", 0, 8)) {
                version = line.substring(8).trim();
            }
        }
        if (distribution == null || distribution.isEmpty()
                || version == null || version.isEmpty()) {
            throw new IOException(
                    "Dependency METADATA has no provable name/version");
        }
        validateExtractedPureWheel(
                physicalRoot, distInfo);
        Set<String> importRoots =
                readDistributionImportRoots(distInfo);
        if (importRoots.isEmpty()) {
            throw new IOException(
                    "Dependency metadata has no provable import roots");
        }
        return new ManagedDistributionRoot(
                distribution, version,
                logicalRoot.getCanonicalPath(), importRoots);
    }

    private static Set<String> readDistributionImportRoots(
            File distInfo) throws IOException {
        File record = new File(distInfo, "RECORD");
        byte[] recordBytes = readBoundedFile(
                record, MAX_DISTRIBUTION_RECORD_BYTES,
                "dependency RECORD");
        LinkedHashSet<String> roots =
                parseRecordImportRoots(recordBytes);

        File topLevel = new File(distInfo, "top_level.txt");
        if (topLevel.isFile()) {
            byte[] bytes = readBoundedFile(
                    topLevel, MAX_DISTRIBUTION_METADATA_BYTES,
                    "dependency top_level.txt");
            validateTopLevelHint(bytes, roots);
        }
        return roots;
    }

    private static LinkedHashSet<String> parseRecordImportRoots(
            byte[] bytes) throws IOException {
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        for (String line : new String(
                bytes, StandardCharsets.UTF_8)
                .split("\\r?\\n")) {
            if (line.isEmpty()) continue;
            String path = readRecordPath(line);
            if (path == null || path.isEmpty()
                    || path.indexOf('\0') >= 0) {
                throw new IOException(
                        "Dependency RECORD has an invalid path");
            }
            path = path.replace('\\', '/');
            while (path.startsWith("./")) {
                path = path.substring(2);
            }
            if (path.startsWith("/")
                    || path.equals("..")
                    || path.startsWith("../")
                    || path.contains("/../")) {
                
                continue;
            }
            int firstSlash = path.indexOf('/');
            String firstComponent = firstSlash >= 0
                    ? path.substring(0, firstSlash) : path;
            if (firstComponent.endsWith(".data")) {
                String dataPath = firstSlash >= 0
                        ? path.substring(firstSlash + 1) : "";
                if (dataPath.startsWith("platlib/")) {
                    throw new IOException(
                            "Dependency RECORD contains platlib data");
                }
                if (!dataPath.startsWith("purelib/")) {
                    continue;
                }
                path = dataPath.substring(
                        "purelib/".length());
                if (path.isEmpty()) continue;
            }
            int slash = path.indexOf('/');
            String first = slash >= 0
                    ? path.substring(0, slash) : path;
            if (first.endsWith(".dist-info")
                    || "__pycache__".equals(first)) {
                continue;
            }
            String importRoot = null;
            if (slash < 0) {
                if (first.endsWith(".py")) {
                    importRoot =
                            first.substring(0, first.length() - 3);
                } else if (first.endsWith(".pyi")) {
                    importRoot =
                            first.substring(0, first.length() - 4);
                } else if (first.endsWith(".pyc")) {
                    importRoot =
                            first.substring(0, first.length() - 4);
                }
            } else {
                String remainder = path.substring(slash + 1);
                if (isPythonModuleRecord(remainder)) {
                    importRoot = first;
                }
            }
            if (importRoot != null
                    && IMPORT_ROOT_PATTERN.matcher(
                            importRoot).matches()) {
                roots.add(importRoot);
            }
        }
        if (roots.isEmpty()) {
            throw new IOException(
                    "Dependency RECORD cannot prove import roots");
        }
        return roots;
    }

    private static void validateTopLevelHint(
            byte[] bytes, Set<String> recordRoots)
            throws IOException {
        for (String raw : new String(
                bytes, StandardCharsets.UTF_8)
                .split("\\r?\\n")) {
            String value = raw.trim();
            if (value.isEmpty()) continue;
            if (!IMPORT_ROOT_PATTERN.matcher(value).matches()) {
                throw new IOException(
                        "Dependency top_level.txt is malformed");
            }
            if (!recordRoots.contains(value)) {
                throw new IOException(
                        "Dependency top_level.txt claims import root "
                                + value + " which RECORD cannot prove");
            }
        }
    }

    private static boolean isPythonModuleRecord(String path) {
        if (path == null || path.isEmpty()) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".py")
                || lower.endsWith(".pyi")
                || lower.endsWith(".pyc");
    }

    private static String readRecordPath(String line)
            throws IOException {
        if (line.charAt(0) != '"') {
            int comma = line.indexOf(',');
            return comma >= 0 ? line.substring(0, comma) : line;
        }
        StringBuilder value = new StringBuilder();
        boolean closed = false;
        for (int index = 1; index < line.length(); index++) {
            char current = line.charAt(index);
            if (current != '"') {
                value.append(current);
                continue;
            }
            if (index + 1 < line.length()
                    && line.charAt(index + 1) == '"') {
                value.append('"');
                index++;
                continue;
            }
            if (index + 1 < line.length()
                    && line.charAt(index + 1) != ',') {
                throw new IOException(
                        "Dependency RECORD CSV is malformed");
            }
            closed = true;
            break;
        }
        if (!closed) {
            throw new IOException(
                    "Dependency RECORD CSV is unterminated");
        }
        return value.toString();
    }

    private ManagedRuntimeSnapshot snapshotManagedRuntimeStrict(
            Set<String> allowedMissingPaths) {
        if (!isPythonRuntimeUsable()) {
            return new ManagedRuntimeSnapshot(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyMap(),
                    Collections.emptyMap());
        }
        try {
            com.chaquo.python.PyObject pythonPath =
                    getStartedPython().getModule("sys").get("path");
            if (pythonPath == null) {
                throw new IOException("Python sys.path is unavailable");
            }
            ArrayList<String> ordered =
                    new ArrayList<>();
            for (com.chaquo.python.PyObject value
                    : pythonPath.asList()) {
                String raw = value != null
                        ? value.toString() : null;
                if (!isManagedImportPath(raw)) continue;
                String canonical =
                        new File(raw).getCanonicalPath();
                if (ordered.contains(canonical)) {
                    throw new IOException(
                            "Managed sys.path contains a duplicate "
                                    + canonical);
                }
                ordered.add(canonical);
            }

            LinkedHashMap<String, File> candidates =
                    new LinkedHashMap<>();
            for (String canonical : ordered) {
                File root = new File(canonical);
                if (!root.isDirectory()) {
                    if (allowedMissingPaths != null
                            && allowedMissingPaths.contains(
                                    canonical)) {
                        continue;
                    }
                    throw new IOException(
                            "Managed sys.path root is missing: "
                                    + canonical);
                }
                candidates.put(canonical, root);
            }

            LinkedHashMap<String, ManagedDistributionRoot> byPath =
                    new LinkedHashMap<>();
            LinkedHashMap<String, List<ManagedDistributionRoot>>
                    byDistribution = new LinkedHashMap<>();
            for (Map.Entry<String, File> entry
                    : candidates.entrySet()) {
                ManagedDistributionRoot root =
                        readManagedDistributionRoot(
                                entry.getValue(),
                                new File(entry.getKey()));
                byPath.put(root.canonicalPath, root);
                byDistribution.computeIfAbsent(
                        root.distribution,
                        ignored -> new ArrayList<>()).add(root);
            }
            return new ManagedRuntimeSnapshot(
                    new ArrayList<>(ordered),
                    new ArrayList<>(ordered),
                    byPath, byDistribution);
        } catch (RestartRequiredRuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw restartRequired(
                    "Cannot prove the current dynamic dependency "
                            + "generation", failure);
        }
    }

    private static RestartRequiredRuntimeException restartRequired(
            String message) {
        PIP_RESTART_REQUIRED.set(true);
        return new RestartRequiredRuntimeException(
                "Restart required: " + message);
    }

    private static RestartRequiredRuntimeException restartRequired(
            String message, Throwable cause) {
        PIP_RESTART_REQUIRED.set(true);
        return new RestartRequiredRuntimeException(
                "Restart required: " + message, cause);
    }

    private boolean hasOtherDistributionOwner(
            String distribution, String excludedPluginId) {
        for (Map.Entry<String, ConcurrentHashMap<String, Set<String>>>
                owner : registry.entrySet()) {
            if (owner.getKey().equals(excludedPluginId)
                    || owner.getValue() == null) {
                continue;
            }
            if (owner.getValue().containsKey(distribution)) {
                return true;
            }
        }
        return false;
    }

    private boolean isDistributionReferenced(String distribution) {
        for (ConcurrentHashMap<String, Set<String>> ownership
                : registry.values()) {
            if (ownership != null
                    && ownership.containsKey(distribution)) {
                return true;
            }
        }
        return false;
    }

    private List<RegistryRootDisk> runtimeRootsStillReferenced(
            List<RegistryRootDisk> roots) {
        ArrayList<RegistryRootDisk> result = new ArrayList<>();
        if (roots == null) return result;
        for (RegistryRootDisk root : roots) {
            if (root != null
                    && isDistributionReferenced(
                            root.distribution)) {
                result.addAll(copyRegistryRoots(
                        Collections.singletonList(root)));
            }
        }
        return result;
    }

    private boolean isDistributionReferencedAfterInstall(
            String pluginId,
            Map<String, Set<String>> candidateOwnership,
            String distribution) {
        if (candidateOwnership != null
                && candidateOwnership.containsKey(distribution)) {
            return true;
        }
        for (Map.Entry<String,
                ConcurrentHashMap<String, Set<String>>> owner
                : registry.entrySet()) {
            if (owner.getKey().equals(pluginId)
                    || owner.getValue() == null) {
                continue;
            }
            if (owner.getValue().containsKey(distribution)) {
                return true;
            }
        }
        return false;
    }

    private List<RegistryRootDisk> buildInstallRuntimeRoots(
            String pluginId,
            Map<String, Set<String>> candidateOwnership,
            Map<String, ManagedDistributionRoot> selectedRoots,
            Map<String, String> selectedDigests)
            throws IOException {
        LinkedHashMap<String, RegistryRootDisk> existing =
                new LinkedHashMap<>();
        for (RegistryRootDisk root : registryRuntimeRoots) {
            existing.put(root.distribution, root);
        }
        ArrayList<RegistryRootDisk> result = new ArrayList<>();
        LinkedHashSet<String> added = new LinkedHashSet<>();

        for (Map.Entry<String, ManagedDistributionRoot> selected
                : selectedRoots.entrySet()) {
            String distribution = selected.getKey();
            if (existing.containsKey(distribution)
                    || !isDistributionReferencedAfterInstall(
                            pluginId, candidateOwnership,
                            distribution)) {
                continue;
            }
            String digest = selectedDigests.get(distribution);
            result.add(registryRootForManaged(
                    selected.getValue(), digest));
            added.add(distribution);
        }
        for (RegistryRootDisk current : registryRuntimeRoots) {
            String distribution = current.distribution;
            if (!isDistributionReferencedAfterInstall(
                    pluginId, candidateOwnership,
                    distribution)) {
                continue;
            }
            ManagedDistributionRoot selected =
                    selectedRoots.get(distribution);
            if (selected != null) {
                result.add(registryRootForManaged(
                        selected,
                        selectedDigests.get(distribution)));
            } else {
                result.addAll(copyRegistryRoots(
                        Collections.singletonList(current)));
            }
            added.add(distribution);
        }
        for (Map.Entry<String, ManagedDistributionRoot> selected
                : selectedRoots.entrySet()) {
            String distribution = selected.getKey();
            if (added.contains(distribution)
                    || !isDistributionReferencedAfterInstall(
                            pluginId, candidateOwnership,
                            distribution)) {
                continue;
            }
            result.add(registryRootForManaged(
                    selected.getValue(),
                    selectedDigests.get(distribution)));
            added.add(distribution);
        }

        LinkedHashSet<String> referenced = new LinkedHashSet<>();
        if (candidateOwnership != null) {
            referenced.addAll(candidateOwnership.keySet());
        }
        for (Map.Entry<String,
                ConcurrentHashMap<String, Set<String>>> owner
                : registry.entrySet()) {
            if (!owner.getKey().equals(pluginId)
                    && owner.getValue() != null) {
                referenced.addAll(owner.getValue().keySet());
            }
        }
        referenced.removeAll(PREINSTALLED_PACKAGES);
        if (!added.containsAll(referenced)) {
            referenced.removeAll(added);
            throw new IOException(
                    "No exact runtime root for referenced "
                            + "distributions " + referenced);
        }
        return validateRegistryRootRecords(result, false);
    }

    private void validateSelectedImportRootCollisions(
            String pluginId, ResolutionState solved)
            throws IOException {
        LinkedHashMap<String, String> owners =
                new LinkedHashMap<>();
        LinkedHashSet<String> selectedDistributions =
                new LinkedHashSet<>(solved.states.keySet());
        selectedDistributions.removeAll(PREINSTALLED_PACKAGES);

        for (RegistryRootDisk current : registryRuntimeRoots) {
            if (current == null
                    || selectedDistributions.contains(
                            current.distribution)) {
                continue;
            }
            if (hasOtherDistributionOwner(
                    current.distribution, pluginId)) {
                registerImportRootOwnership(
                        owners, current.distribution,
                        current.importRoots);
            }
        }
        for (String distribution : selectedDistributions) {
            WheelCandidate candidate =
                    solved.selected.get(distribution);
            if (candidate == null
                    || candidate.resolvedInfo == null
                    || candidate.resolvedInfo.importRoots.isEmpty()) {
                throw new IOException(
                        "Selected wheel import roots are unavailable "
                                + "for " + distribution);
            }
            registerImportRootOwnership(
                    owners, distribution,
                    candidate.resolvedInfo.importRoots);
        }
    }

    private static boolean versionsEquivalent(
            String first, String second) {
        if (first == null || second == null) return false;
        try {
            return VersionComparator.INSTANCE.compare(
                    first, second) == 0;
        } catch (Throwable ignored) {
            return first.equals(second);
        }
    }

    private static void requireSelectedDistributionRoot(
            ManagedDistributionRoot root, String distribution,
            String version) {
        if (root == null
                || !distribution.equals(root.distribution)
                || !versionsEquivalent(version, root.version)) {
            throw restartRequired(
                    "managed root for " + distribution + " "
                            + version
                            + " does not match its wheel metadata");
        }
    }

    private void validateSharedDistributionTransitions(
            String pluginId, ResolutionState solved,
            ManagedRuntimeSnapshot before) {
        for (Map.Entry<String, WheelCandidate> entry
                : solved.selected.entrySet()) {
            String distribution = entry.getKey();
            WheelCandidate candidate = entry.getValue();
            if (PREINSTALLED_PACKAGES.contains(distribution)
                    || !hasOtherDistributionOwner(
                            distribution, pluginId)) {
                continue;
            }
            if (candidate == null || !candidate.isPure) {
                throw restartRequired(
                        "shared dependency " + distribution
                                + " cannot be hot-swapped");
            }
            ManagedDistributionRoot active =
                    before.activeRoot(distribution);
            if (active == null) {
                throw restartRequired(
                        "shared dependency " + distribution
                                + " has no provable active root");
            }
            File wheel = new File(
                    getWheelsDir(),
                    distribution + "-" + candidate.version + ".whl");
            String desiredPath;
            try {
                desiredPath =
                        extractionDirForWheel(wheel)
                                .getCanonicalPath();
            } catch (IOException failure) {
                throw restartRequired(
                        "cannot resolve shared dependency "
                                + distribution, failure);
            }
            if (!versionsEquivalent(
                            active.version, candidate.version)
                    || !active.canonicalPath.equals(desiredPath)) {
                throw restartRequired(
                        "shared dependency " + distribution
                                + " is active at " + active.version
                                + " and cannot switch to "
                                + candidate.version
                                + " while another plugin owns it");
            }
        }
    }

    private ManagedTransition buildInstallTransition(
            String pluginId,
            Map<String, Set<String>> previousOwnership,
            Map<String, Set<String>> nextOwnership,
            ManagedRuntimeSnapshot before,
            Map<String, ManagedDistributionRoot> desiredRoots,
            Set<String> replacedRoots) {
        LinkedHashSet<String> affected = new LinkedHashSet<>();
        if (previousOwnership != null) {
            affected.addAll(previousOwnership.keySet());
        }
        if (nextOwnership != null) {
            affected.addAll(nextOwnership.keySet());
        }
        ManagedTransition transition = new ManagedTransition();
        for (String distribution : affected) {
            if (PREINSTALLED_PACKAGES.contains(distribution)
                    || hasOtherDistributionOwner(
                            distribution, pluginId)) {
                continue;
            }
            ManagedDistributionRoot desired =
                    nextOwnership != null
                            && nextOwnership.containsKey(distribution)
                            ? desiredRoots.get(distribution) : null;
            List<ManagedDistributionRoot> previousRoots =
                    before.rootsByDistribution.get(distribution);
            if (previousRoots == null) continue;
            for (ManagedDistributionRoot root : previousRoots) {
                boolean remainsSelected =
                        desired != null
                                && desired.canonicalPath.equals(
                                        root.canonicalPath);
                boolean replaced =
                        replacedRoots.contains(root.canonicalPath);
                if (!remainsSelected || replaced) {
                    transition.eviction.add(root);
                }
                if (!remainsSelected) {
                    transition.obsoleteImportPaths.add(
                            root.canonicalPath);
                }
            }
        }
        return transition;
    }

    private ManagedTransition buildRestoreTransition(
            String pluginId,
            Map<String, Set<String>> currentOwnership,
            DependencySnapshot desired,
            ManagedRuntimeSnapshot current,
            ManagedModuleEvictionPlan pending) {
        LinkedHashSet<String> affected = new LinkedHashSet<>();
        if (currentOwnership != null) {
            affected.addAll(currentOwnership.keySet());
        }
        if (desired.ownership != null) {
            affected.addAll(desired.ownership.keySet());
        }
        LinkedHashSet<String> desiredPaths =
                canonicalManagedPaths(desired.managedImportPaths);
        ManagedTransition transition = new ManagedTransition();
        transition.eviction.addAll(pending);
        for (String distribution : affected) {
            if (PREINSTALLED_PACKAGES.contains(distribution)
                    || hasOtherDistributionOwner(
                            distribution, pluginId)) {
                continue;
            }
            List<ManagedDistributionRoot> roots =
                    current.rootsByDistribution.get(distribution);
            if (roots == null) continue;
            for (ManagedDistributionRoot root : roots) {
                
                transition.eviction.add(root);
                if (!desiredPaths.contains(root.canonicalPath)) {
                    transition.obsoleteImportPaths.add(
                            root.canonicalPath);
                }
            }
        }
        return transition;
    }

    private ManagedTransition buildUninstallTransition(
            String pluginId,
            Map<String, Set<String>> removedOwnership,
            ManagedRuntimeSnapshot before) {
        LinkedHashSet<String> affected = new LinkedHashSet<>();
        if (removedOwnership != null) {
            affected.addAll(removedOwnership.keySet());
        } else {
            for (String distribution
                    : before.rootsByDistribution.keySet()) {
                if (!isDistributionReferenced(distribution)) {
                    affected.add(distribution);
                }
            }
        }
        ManagedTransition transition = new ManagedTransition();
        for (String distribution : affected) {
            if (PREINSTALLED_PACKAGES.contains(distribution)
                    || hasOtherDistributionOwner(
                            distribution, pluginId)) {
                continue;
            }
            List<ManagedDistributionRoot> roots =
                    before.rootsByDistribution.get(distribution);
            if (roots == null) continue;
            for (ManagedDistributionRoot root : roots) {
                transition.eviction.add(root);
                transition.obsoleteImportPaths.add(
                        root.canonicalPath);
            }
        }
        return transition;
    }

    private LinkedHashSet<String> canonicalManagedPaths(
            List<String> paths) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (paths == null) return result;
        for (String path : paths) {
            if (path == null || !isManagedImportPath(path)) {
                throw restartRequired(
                        "dependency path is outside the managed root");
            }
            try {
                String canonical =
                        new File(path).getCanonicalPath();
                if (!result.add(canonical)) {
                    throw restartRequired(
                            "duplicate managed dependency path "
                                    + canonical);
                }
            } catch (IOException failure) {
                throw restartRequired(
                        "cannot canonicalize dependency path "
                                + path, failure);
            }
        }
        return result;
    }

    private PreparedModuleEviction prepareManagedModuleEviction(
            ManagedModuleEvictionPlan plan) {
        if (plan == null || plan.isEmpty()
                || !isPythonRuntimeUsable()) {
            return new PreparedModuleEviction(
                    Collections.emptyList(), plan);
        }
        try {
            com.chaquo.python.Python python =
                    getStartedPython();
            
            python.getModule("_imp");
            python.getModule("importlib");
            python.getModule("json");
            python.getModule("os");
            python.getModule("sys");
            com.chaquo.python.PyObject modules =
                    python.getModule("sys").get("modules");
            com.chaquo.python.PyObject builtins =
                    python.getModule("builtins");
            if (modules == null || builtins == null) {
                throw new IOException(
                        "Python module registry is unavailable");
            }
            ArrayList<Map.Entry<com.chaquo.python.PyObject,
                    com.chaquo.python.PyObject>> entries =
                    new ArrayList<>(modules.asMap().entrySet());
            ArrayList<PreparedModuleRemoval> removals =
                    new ArrayList<>();
            ArrayList<PreparedNamespacePatch> namespacePatches =
                    new ArrayList<>();
            for (Map.Entry<com.chaquo.python.PyObject,
                    com.chaquo.python.PyObject> entry : entries) {
                String name = entry.getKey() != null
                        ? entry.getKey().toString() : null;
                com.chaquo.python.PyObject module =
                        entry.getValue();
                if (name == null || name.isEmpty()
                        || module == null) {
                    continue;
                }
                String topLevel = name;
                int separator = name.indexOf('.');
                if (separator >= 0) {
                    topLevel = name.substring(0, separator);
                }

                String file = pythonPathValue(
                        module.get("__file__"));
                com.chaquo.python.PyObject spec =
                        module.get("__spec__");
                String origin = spec != null
                        ? pythonPathValue(spec.get("origin"))
                        : null;
                if ("built-in".equals(origin)
                        || "frozen".equals(origin)) {
                    continue;
                }
                LinkedHashSet<String> directMatches =
                        matchingEvictionRoots(file, plan);
                directMatches.addAll(
                        matchingEvictionRoots(origin, plan));

                boolean hasManagedNamespacePath = false;
                boolean hasOutsideNamespacePath = false;
                boolean invalidNamespacePath = false;
                LinkedHashSet<String> namespaceMatches =
                        new LinkedHashSet<>();
                ArrayList<String> namespacePathSnapshot =
                        new ArrayList<>();
                com.chaquo.python.PyObject modulePath =
                        module.get("__path__");
                if (modulePath != null) {
                    for (com.chaquo.python.PyObject pathValue
                            : modulePath.asList()) {
                        String path = pythonPathValue(pathValue);
                        String canonical =
                                canonicalPythonOrigin(path);
                        if (canonical == null) {
                            invalidNamespacePath = true;
                            continue;
                        }
                        namespacePathSnapshot.add(canonical);
                        Set<String> matches =
                                matchingEvictionRoots(
                                        canonical, plan);
                        if (matches.isEmpty()) {
                            hasOutsideNamespacePath = true;
                        } else {
                            hasManagedNamespacePath = true;
                            namespaceMatches.addAll(matches);
                        }
                    }
                }

                LinkedHashSet<String> allMatches =
                        new LinkedHashSet<>(directMatches);
                allMatches.addAll(namespaceMatches);
                if (allMatches.isEmpty()) continue;
                boolean importRootProved = false;
                for (String rootPath : allMatches) {
                    Set<String> importRoots =
                            plan.importRootsByPath.get(rootPath);
                    if (importRoots != null
                            && importRoots.contains(topLevel)) {
                        importRootProved = true;
                        break;
                    }
                }
                if (!importRootProved) {
                    throw new IOException(
                            "Module " + name
                                    + " is inside a managed root but "
                                    + "is absent from wheel import metadata");
                }

                boolean remove = !directMatches.isEmpty()
                        || (hasManagedNamespacePath
                                && !hasOutsideNamespacePath);
                if (!remove) {
                    
                    if (invalidNamespacePath
                            || namespacePathSnapshot.isEmpty()) {
                        throw new IOException(
                                "Mixed namespace " + name
                                        + " has an unprovable __path__");
                    }
                    long identity =
                            builtins.callAttr("id", module)
                                    .toLong();
                    namespacePatches.add(
                            new PreparedNamespacePatch(
                                    name, identity,
                                    namespacePathSnapshot));
                    continue;
                }
                long identity =
                        builtins.callAttr("id", module).toLong();
                removals.add(new PreparedModuleRemoval(
                        name, identity));
            }
            removals.sort((left, right) -> {
                int leftDepth = moduleDepth(left.name);
                int rightDepth = moduleDepth(right.name);
                if (leftDepth != rightDepth) {
                    return Integer.compare(
                            rightDepth, leftDepth);
                }
                int length = Integer.compare(
                        right.name.length(),
                        left.name.length());
                return length != 0
                        ? length
                        : right.name.compareTo(left.name);
            });
            return new PreparedModuleEviction(
                    removals, namespacePatches, plan);
        } catch (RestartRequiredRuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw restartRequired(
                    "cannot safely inspect dynamic Python modules",
                    failure);
        }
    }

    private void executeManagedRuntimeTransition(
            List<String> expectedPaths,
            List<String> desiredPaths,
            PreparedModuleEviction prepared,
            List<RegistryRootDisk> desiredRuntimeRoots) {
        if (!isPythonRuntimeUsable()) {
            return;
        }
        try {
            ArrayList<String> expected =
                    new ArrayList<>(
                            canonicalManagedPaths(
                                    expectedPaths != null
                                            ? expectedPaths
                                            : Collections.emptyList()));
            ArrayList<String> desired =
                    new ArrayList<>(
                            canonicalManagedPaths(
                                    desiredPaths != null
                                            ? desiredPaths
                                            : Collections.emptyList()));
            ArrayList<Map<String, Object>> removals =
                    new ArrayList<>();
            if (prepared != null) {
                int previousDepth = Integer.MAX_VALUE;
                for (PreparedModuleRemoval removal
                        : prepared.removals) {
                    int depth = moduleDepth(removal.name);
                    if (depth > previousDepth) {
                        throw new IOException(
                                "Module eviction is not deepest-first");
                    }
                    previousDepth = depth;
                    LinkedHashMap<String, Object> item =
                            new LinkedHashMap<>();
                    item.put("name", removal.name);
                    item.put("identity", removal.identity);
                    removals.add(item);
                }
            }
            ArrayList<Map<String, Object>> evictionRoots =
                    new ArrayList<>();
            if (prepared != null) {
                for (Map.Entry<String, Set<String>> entry
                        : prepared.evictionRoots.entrySet()) {
                    LinkedHashMap<String, Object> item =
                            new LinkedHashMap<>();
                    item.put("path", entry.getKey());
                    ArrayList<String> importRoots =
                            new ArrayList<>(entry.getValue());
                    Collections.sort(importRoots);
                    item.put("import_roots", importRoots);
                    evictionRoots.add(item);
                }
            }
            ArrayList<Map<String, Object>> namespacePatches =
                    new ArrayList<>();
            if (prepared != null) {
                for (PreparedNamespacePatch patch
                        : prepared.namespacePatches) {
                    LinkedHashMap<String, Object> item =
                            new LinkedHashMap<>();
                    item.put("name", patch.name);
                    item.put("identity", patch.identity);
                    item.put(
                            "expected_paths",
                            new ArrayList<>(
                                    patch.expectedPaths));
                    namespacePatches.add(item);
                }
            }
            LinkedHashMap<String, RegistryRootDisk>
                    desiredRootRecords = new LinkedHashMap<>();
            for (RegistryRootDisk root
                    : desiredRuntimeRoots != null
                            ? desiredRuntimeRoots
                            : Collections.<RegistryRootDisk>emptyList()) {
                String canonical = resolveArtifactPath(
                        root.root).getCanonicalPath();
                if (desired.contains(canonical)) {
                    desiredRootRecords.put(canonical, root);
                }
            }
            if (desiredRootRecords.size() != desired.size()) {
                throw new IOException(
                        "Desired dependency roots do not match "
                                + "the versioned registry");
            }
            ArrayList<Map<String, Object>> desiredRoots =
                    new ArrayList<>();
            for (String path : desired) {
                RegistryRootDisk root =
                        desiredRootRecords.get(path);
                LinkedHashMap<String, Object> item =
                        new LinkedHashMap<>();
                item.put("path", path);
                ArrayList<String> importRoots =
                        new ArrayList<>(root.importRoots);
                Collections.sort(importRoots);
                item.put("import_roots", importRoots);
                desiredRoots.add(item);
            }
            LinkedHashMap<String, Object> payload =
                    new LinkedHashMap<>();
            payload.put(
                    "managed_root",
                    new File(getLibsDir(), "site")
                            .getCanonicalPath());
            payload.put("expected_paths", expected);
            payload.put("desired_paths", desired);
            payload.put("modules", removals);
            payload.put("eviction_roots", evictionRoots);
            payload.put(
                    "namespace_patches", namespacePatches);
            payload.put("desired_roots", desiredRoots);

            com.chaquo.python.Python python = getStartedPython();
            com.chaquo.python.PyObject builtins =
                    python.getModule("builtins");
            if (builtins == null) {
                throw new IOException(
                        "Python builtins are unavailable");
            }
            
            com.chaquo.python.PyObject namespace =
                    builtins.callAttr("dict");
            builtins.callAttr(
                    "exec",
                    PYTHON_RUNTIME_TRANSITION_HELPER,
                    namespace,
                    namespace);
            com.chaquo.python.PyObject helper =
                    namespace.callAttr(
                            "__getitem__", "transition");
            if (helper == null
                    || !builtins.callAttr(
                            "callable", helper).toBoolean()) {
                throw new IOException(
                        "Python transition helper is unavailable");
            }
            helper.call(gson.toJson(payload));
        } catch (RestartRequiredRuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw restartRequired(
                    "atomic dependency runtime transition "
                            + "did not complete",
                    failure);
        }
    }

    private static int moduleDepth(String name) {
        int depth = 0;
        for (int index = 0; index < name.length(); index++) {
            if (name.charAt(index) == '.') depth++;
        }
        return depth;
    }

    private static String pythonPathValue(
            com.chaquo.python.PyObject value) {
        if (value == null) return null;
        String result = value.toString();
        if (result == null || result.isEmpty()
                || "None".equals(result)
                || result.startsWith("<")) {
            return null;
        }
        return result;
    }

    private static LinkedHashSet<String> matchingEvictionRoots(
            String path, ManagedModuleEvictionPlan plan) {
        LinkedHashSet<String> matches = new LinkedHashSet<>();
        String canonical = canonicalPythonOrigin(path);
        if (canonical == null) return matches;
        for (String root : plan.importRootsByPath.keySet()) {
            if (canonical.equals(root)
                    || canonical.startsWith(
                            root + File.separator)) {
                matches.add(root);
            }
        }
        return matches;
    }

    private static String canonicalPythonOrigin(String path) {
        if (path == null || path.isEmpty()) return null;
        File file = new File(path);
        if (!file.isAbsolute()) return null;
        try {
            return file.getCanonicalPath();
        } catch (IOException ignored) {
            return null;
        }
    }

    private ManagedModuleEvictionPlan captureRollbackModuleEviction(
            DeferredArtifactJournal journal,
            List<RegistryRootDisk> previousRoots) {
        ManagedModuleEvictionPlan plan =
                new ManagedModuleEvictionPlan();
        if (!isPythonRuntimeUsable()
                || journal == null) {
            return plan;
        }
        try {
            LinkedHashSet<String> previousPaths =
                    new LinkedHashSet<>(
                            registryImportPathsStrict(
                                    previousRoots));
            ManagedRuntimeSnapshot current =
                    snapshotManagedRuntimeStrict(
                            Collections.emptySet());
            for (ManagedDistributionRoot root
                    : current.rootsByPath.values()) {
                if (!previousPaths.contains(
                        root.canonicalPath)) {
                    
                    plan.add(root);
                }
            }
            for (DeferredArtifactEntry entry : journal.entries) {
                File target = resolveArtifactPath(entry.target);
                if (!isManagedImportPath(
                                target.getAbsolutePath())
                        || !target.isDirectory()) {
                    continue;
                }
                File backup = resolveArtifactPath(entry.backup);
                File staged = resolveArtifactPath(entry.staged);
                boolean candidatePublished =
                        backup.exists()
                                || (!entry.hadTarget
                                        && target.exists()
                                        && !staged.exists());
                if (!candidatePublished) continue;
                ManagedDistributionRoot root =
                        readManagedDistributionRoot(
                                target, target);
                plan.add(root);
            }
            return plan;
        } catch (Throwable failure) {
            throw restartRequired(
                    "cannot prove candidate modules before rollback",
                    failure);
        }
    }

    public synchronized List<String> snapshotRequirements(String pluginId) {
        if (pluginId == null) return Collections.emptyList();
        loadRegistryOrThrow();
        ConcurrentHashMap<String, Set<String>> ownership = registry.get(pluginId);
        if (ownership == null || ownership.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> snapshot = new LinkedHashSet<>();
        for (Set<String> rawRequirements : ownership.values()) {
            if (rawRequirements == null) continue;
            for (String requirement : rawRequirements) {
                if (requirement != null && !requirement.trim().isEmpty()) {
                    snapshot.add(requirement.trim());
                }
            }
        }
        return new ArrayList<>(snapshot);
    }

    public synchronized boolean isDependencyInstallNoOp(
            List<String> requirements, String pluginId) {
        if (!registryLoaded || pluginId == null
                || PIP_RESTART_REQUIRED.get()
                || recoveringLocalArtifactTransactions
                || !activeDeferredArtifactTransactions.isEmpty()
                || !pendingRollbackModuleEvictions.isEmpty()
                || !pendingRollbackModuleEvictionFailures.isEmpty()) {
            return false;
        }
        if (requirements != null) {
            for (String requirement : requirements) {
                if (requirement != null
                        && !requirement.trim().isEmpty()) {
                    return false;
                }
            }
        }
        ConcurrentHashMap<String, Set<String>> ownership =
                registry.get(pluginId);
        return ownership == null || ownership.isEmpty();
    }

    public synchronized List<String> installDependencies(List<String> requirements, String pluginId, InstallerDelegate delegate) {
        enterPipMutation();
        boolean resolverTouched = false;
        try {
        if (requirements == null) requirements = Collections.emptyList();
        if (pluginId == null) pluginId = "_unknown";
        loadRegistryOrThrow();
        String activeTransactionId;
        try {
            activeTransactionId =
                    activeArtifactTransactionId(pluginId);
            if (activeTransactionId == null) {
                requireNoPendingArtifactTransactions();
            } else {
                requireOnlyActiveArtifactTransaction(
                        pluginId, activeTransactionId);
            }
        } catch (IOException blocked) {
            throw new IllegalStateException(
                    "Cannot safely start dependency installation",
                    blocked);
        }
        if (activeTransactionId == null
                && isDependencyInstallNoOp(requirements, pluginId)) {
            app.nimarkogram.messenger.plugins.PluginDebugLog.log(
                    "PIP installDependencies no-op pluginId="
                            + pluginId);
            return Collections.emptyList();
        }
        refreshRuntimeMarkerEnvironment();
        resolverTouched = true;
        app.nimarkogram.messenger.plugins.PluginDebugLog.log("PIP installDependencies pluginId=" + pluginId
                + " reqs=" + requirements + " pyVer=" + pythonFullVersion
                + " machine=" + platformMachine);
        
        cleanupRequired = true;
        
        ConcurrentHashMap<String, Set<String>> staged = new ConcurrentHashMap<>();
        List<String> installed = new ArrayList<>();
        ResolutionState initial = new ResolutionState(
                collectSharedVersionConstraints(pluginId));
        for (String requirement : requirements) {
            if (requirement != null) {
                addRequirement(initial, requirement, Collections.emptySet());
            }
        }
        ResolutionState solved;
        try {
            solved = solve(initial, delegate);
        } catch (InstallCancelledException cancelled) {
            FileLog.d("PipController.installDependencies cancelled by delegate");
            throw new InstallCancelledRuntimeException();
        } catch (IOException ioe) {
            throw new RuntimeException("Failed to resolve dependencies: " + ioe.getMessage(), ioe);
        }
        if (solved == null) {
            throw new RuntimeException("Dependency constraints are unsatisfiable");
        }
        try {
            validateSelectedImportRootCollisions(
                    pluginId, solved);
        } catch (IOException collision) {
            throw new RuntimeException(
                    "Unsafe dependency import-root collision: "
                            + collision.getMessage(),
                    collision);
        }

        ManagedRuntimeSnapshot runtimeBefore =
                snapshotManagedRuntimeStrict(
                        Collections.emptySet());
        try {
            if (!runtimeBefore.orderedCanonicalPaths.equals(
                    registryImportPathsStrict(
                            registryRuntimeRoots))) {
                throw restartRequired(
                        "managed runtime does not match the "
                                + "versioned registry");
            }
        } catch (IOException failure) {
            throw restartRequired(
                    "cannot validate the active registry generation",
                    failure);
        }
        validateSharedDistributionTransitions(
                pluginId, solved, runtimeBefore);
        String transactionId =
                activeTransactionId != null
                        ? activeTransactionId
                        : java.util.UUID.randomUUID().toString()
                                .replace("-", "");
        List<StagedReplacement> replacements = new ArrayList<>();
        Map<String, ManagedDistributionRoot> desiredRoots =
                new LinkedHashMap<>();
        Map<String, String> selectedDigests =
                new LinkedHashMap<>();
        Set<String> replacedRoots = new LinkedHashSet<>();
        ConcurrentHashMap<String, Set<String>> previousOwnership =
                registry.get(pluginId);
        Map<String, Set<String>> previousOwnershipSnapshot =
                previousOwnership != null
                        ? deepCopyOwnership(previousOwnership)
                        : null;
        List<RegistryRootDisk> previousRuntimeRoots =
                copyRegistryRoots(registryRuntimeRoots);
        boolean outerArtifactTransaction =
                activeTransactionId != null;
        boolean deferredJournalPublished =
                outerArtifactTransaction;
        boolean localRegistryCommitted = false;
        boolean localRollbackAttempted = false;
        try {
            if (!outerArtifactTransaction) {
                beginLocalArtifactTransaction(
                        pluginId, transactionId,
                        previousOwnershipSnapshot);
                deferredJournalPublished = true;
            }
            for (Map.Entry<String, RequirementState> entry : solved.states.entrySet()) {
                throwIfCancelled(delegate);
                String packageKey = entry.getKey();
                RequirementState current = entry.getValue();
                staged.put(packageKey, new LinkedHashSet<>(current.rawRequirements));
                if (PREINSTALLED_PACKAGES.contains(packageKey)) {
                    installed.add(packageKey);
                    continue;
                }
                WheelCandidate cand = solved.selected.get(packageKey);
                if (cand == null) throw new IOException("Resolver omitted " + current.name);
                if (!cand.isPure) {
                    throw new IOException(
                            "Native/platform wheel rejected for "
                                    + current.name);
                }
                File wheelFile = new File(getWheelsDir(), packageKey + "-" + cand.version + ".whl");
                if (cand.expectedSha256 == null || cand.expectedSha256.length() != 64) {
                    throw new IOException("PyPI omitted a valid SHA-256 digest for " + current.name);
                }
                boolean download = !wheelFile.exists();
                if (!download && !verifySha256(wheelFile, cand.expectedSha256)) {
                    
                    download = true;
                }
                File wheelSource = wheelFile;
                if (download) {
                    
                    if (delegate != null) {
                        try { delegate.onProgress("Downloading " + current.name + " " + cand.version); }
                        catch (Throwable ignored) {}
                    }
                    File stagedWheel = stagedSibling(wheelFile, transactionId, "wheel");
                    downloadToFile(cand.downloadUrl, stagedWheel, null, delegate);
                    if (!verifySha256(stagedWheel, cand.expectedSha256)) {
                        String got = calculateSha256(stagedWheel);
                        throw new IOException("sha256 mismatch for " + wheelFile.getName() + ": expected "
                                + cand.expectedSha256 + " got " + got);
                    }
                    replacements.add(new StagedReplacement(stagedWheel, wheelFile, transactionId));
                    wheelSource = stagedWheel;
                }
                throwIfCancelled(delegate);

                PureWheelInfo wheelInfo =
                        inspectPureWheel(wheelSource);
                if (!packageKey.equals(
                                wheelInfo.distribution)
                        || !versionsEquivalent(
                                cand.version,
                                wheelInfo.version)) {
                    throw new IOException(
                            "Wheel metadata identity mismatch for "
                                    + packageKey);
                }
                File extraction = extractionDirForWheel(wheelFile);
                File marker = new File(extraction, ".extracted");
                ManagedDistributionRoot selectedRoot = null;
                if (extraction.isDirectory()) {
                    try {
                        selectedRoot =
                                readManagedDistributionRoot(
                                        extraction, extraction);
                        requireSelectedDistributionRoot(
                                selectedRoot, packageKey,
                                cand.version);
                    } catch (RestartRequiredRuntimeException failure) {
                        throw failure;
                    } catch (Throwable failure) {
                        throw restartRequired(
                                "cannot prove existing managed root "
                                        + "for " + packageKey,
                                failure);
                    }
                } else if (extraction.exists()) {
                    throw restartRequired(
                            "managed root for " + packageKey
                                    + " is not a directory");
                }
                boolean replaceExtraction =
                        selectedRoot == null
                                || !extractionDigestMatches(
                                        marker,
                                        extraction,
                                        cand.expectedSha256);
                String extractionCanonical =
                        extraction.getCanonicalPath();
                if (replaceExtraction
                        && runtimeBefore.orderedCanonicalPaths
                                .contains(extractionCanonical)) {
                    throw restartRequired(
                            "active dependency root "
                                    + extractionCanonical
                                    + " cannot be replaced in place");
                }
                if (replaceExtraction
                        && hasOtherDistributionOwner(
                                packageKey, pluginId)) {
                    throw restartRequired(
                            "shared dependency " + packageKey
                                    + " requires extraction "
                                    + "replacement");
                }
                if (replaceExtraction) {
                    File stagedExtraction = stagedSibling(
                            extraction, transactionId, "extract");
                    extractPureWheel(
                            wheelSource, stagedExtraction,
                            delegate);
                    throwIfCancelled(delegate);
                    writeExtractionMarker(
                            stagedExtraction,
                            cand.expectedSha256);
                    try {
                        selectedRoot =
                                readManagedDistributionRoot(
                                        stagedExtraction,
                                        extraction);
                        requireSelectedDistributionRoot(
                                selectedRoot, packageKey,
                                cand.version);
                    } catch (RestartRequiredRuntimeException failure) {
                        throw failure;
                    } catch (Throwable failure) {
                        throw restartRequired(
                                "cannot prove staged managed root "
                                        + "for " + packageKey,
                                failure);
                    }
                    replacements.add(new StagedReplacement(
                            stagedExtraction, extraction,
                            transactionId));
                    replacedRoots.add(extractionCanonical);
                }
                if (selectedRoot == null
                        || !wheelInfo.importRoots.equals(
                                selectedRoot.importRoots)) {
                    throw new IOException(
                            "Extracted import roots do not match "
                                    + "the selected wheel RECORD for "
                                    + packageKey);
                }
                desiredRoots.put(packageKey, selectedRoot);
                selectedDigests.put(
                        packageKey,
                        cand.expectedSha256.toLowerCase(
                                Locale.ROOT));
                installed.add(packageKey);
            }

            throwIfCancelled(delegate);
            ManagedTransition transition =
                    buildInstallTransition(
                            pluginId,
                            previousOwnershipSnapshot,
                            staged, runtimeBefore,
                            desiredRoots, replacedRoots);
            PreparedModuleEviction preparedEviction =
                    prepareManagedModuleEviction(
                            transition.eviction);
            List<RegistryRootDisk> nextRuntimeRoots =
                    buildInstallRuntimeRoots(
                            pluginId, staged,
                            desiredRoots, selectedDigests);
            ArrayList<String> nextRuntimePaths =
                    registryImportPathsStrict(
                            nextRuntimeRoots);
            deferredJournalPublished =
                    appendDeferredArtifactEntries(
                            pluginId, replacements);
            for (StagedReplacement replacement : replacements) replacement.commit();
            nextRuntimeRoots =
                    validateRegistryRootRecords(
                            nextRuntimeRoots, true);
            registry.put(pluginId, staged);
            registryRuntimeRoots.clear();
            registryRuntimeRoots.addAll(
                    copyRegistryRoots(nextRuntimeRoots));
            saveRegistryStrict();
            executeManagedRuntimeTransition(
                    runtimeBefore.orderedCanonicalPaths,
                    nextRuntimePaths, preparedEviction,
                    nextRuntimeRoots);
            if (!outerArtifactTransaction) {
                markLocalArtifactRegistryCommitted(
                        pluginId, transactionId);
                localRegistryCommitted = true;
                if (!commitDeferredArtifactTransaction(
                        pluginId, transactionId)) {
                    
                    FileLog.w("Local dependency transaction committed; "
                            + "artifact cleanup deferred for "
                            + pluginId);
                }
            }
        } catch (Throwable t) {
            app.nimarkogram.messenger.plugins.PluginDebugLog.log("PIP install transaction failed", t);
            IOException artifactRollbackFailure = null;
            if (deferredJournalPublished
                    && !outerArtifactTransaction
                    && !localRegistryCommitted) {
                localRollbackAttempted = true;
                if (!rollbackDeferredArtifactTransaction(
                        pluginId, transactionId)) {
                    artifactRollbackFailure =
                            new IOException(
                                    "durable dependency rollback "
                                            + "remains pending for "
                                            + pluginId);
                }
            } else if (!deferredJournalPublished) {
                for (int i = replacements.size() - 1; i >= 0; i--) {
                    StagedReplacement replacement = replacements.get(i);
                    if (!replacement.rollback()) {
                        IOException failure = new IOException(
                                "could not verify artifact rollback for " + replacement.target
                                        + "; recovery artifact retained at "
                                        + replacement.recoveryArtifact());
                        if (artifactRollbackFailure == null) artifactRollbackFailure = failure;
                        else artifactRollbackFailure.addSuppressed(failure);
                    }
                }
            }
            if (!localRegistryCommitted
                    && !localRollbackAttempted) {
                if (previousOwnershipSnapshot == null) {
                    registry.remove(pluginId);
                } else {
                    ConcurrentHashMap<String, Set<String>>
                            restoredOwnership =
                                    new ConcurrentHashMap<>();
                    for (Map.Entry<String, Set<String>> entry
                            : previousOwnershipSnapshot.entrySet()) {
                        restoredOwnership.put(
                                entry.getKey(),
                                new LinkedHashSet<>(
                                        entry.getValue()));
                    }
                    registry.put(pluginId, restoredOwnership);
                }
                registryRuntimeRoots.clear();
                registryRuntimeRoots.addAll(
                        copyRegistryRoots(previousRuntimeRoots));
                try {
                    
                    saveRegistryStrict();
                } catch (Throwable restoreFailure) {
                    IOException failure = new IOException(
                            "could not durably compensate the registry "
                                    + "after dependency rollback",
                            restoreFailure);
                    if (artifactRollbackFailure == null) {
                        artifactRollbackFailure = failure;
                    } else {
                        artifactRollbackFailure.addSuppressed(
                                failure);
                    }
                }
            }
            if (artifactRollbackFailure != null) {
                artifactRollbackFailure.addSuppressed(t);
                throw restartRequired(
                        "dependency artifact rollback remains pending; "
                                + "recovery backup was preserved",
                        artifactRollbackFailure);
            }
            if (t instanceof InstallCancelledException) {
                throw new InstallCancelledRuntimeException();
            }
            if (t instanceof RestartRequiredRuntimeException) {
                throw (RestartRequiredRuntimeException) t;
            }
            throw new RuntimeException("Failed to install dependencies: " + t.getMessage(), t);
        }
        
        return installed;
        } finally {
            if (resolverTouched) {
                try {
                    sweepRecognizedTemporaryFiles(
                            getWheelsDir(),
                            RESOLVER_WHEEL_PATTERN);
                } catch (Throwable cleanupFailure) {
                    FileLog.e("PipController retained resolver "
                            + "temporary wheels", cleanupFailure);
                }
            }
            exitPipMutation();
        }
    }

    private static final class InstallCancelledException extends Exception {}

    private static final class ResolutionState {
        final LinkedHashMap<String, RequirementState> states = new LinkedHashMap<>();
        final LinkedHashMap<String, WheelCandidate> selected = new LinkedHashMap<>();
        final HashMap<String, String> expanded = new HashMap<>();
        final Map<String, List<String[]>> sharedVersionConstraints;

        ResolutionState() {
            this(Collections.emptyMap());
        }

        ResolutionState(
                Map<String, List<String[]>> sharedVersionConstraints) {
            this.sharedVersionConstraints =
                    sharedVersionConstraints != null
                            ? sharedVersionConstraints
                            : Collections.emptyMap();
        }

        ResolutionState copy() {
            ResolutionState copy =
                    new ResolutionState(sharedVersionConstraints);
            for (Map.Entry<String, RequirementState> entry : states.entrySet()) {
                copy.states.put(entry.getKey(), entry.getValue().copy());
            }
            copy.selected.putAll(selected);
            copy.expanded.putAll(expanded);
            return copy;
        }
    }

    private static void addRequirement(ResolutionState state, String raw, Set<String> parentExtras) {
        ParsedRequirement req = parseRequirement(raw);
        if (req == null || req.name == null) {
            throw new IllegalArgumentException("Unsupported or malformed requirement: " + raw);
        }
        if (!markerApplies(req.marker, parentExtras)) return;
        String key = normalizePackageName(req.name);
        RequirementState requirement = state.states.computeIfAbsent(
                key, ignored -> new RequirementState(req.name));
        requirement.merge(req, raw);
        requirement.mergeVersionConstraints(
                state.sharedVersionConstraints.get(key));
    }

    private Map<String, List<String[]>>
            collectSharedVersionConstraints(String excludedPluginId) {
        LinkedHashMap<String, List<String[]>> constraints =
                new LinkedHashMap<>();
        for (Map.Entry<String, ConcurrentHashMap<String, Set<String>>> owner
                : registry.entrySet()) {
            if (owner.getKey().equals(excludedPluginId)
                    || owner.getValue() == null) {
                continue;
            }
            for (Map.Entry<String, Set<String>> packageEntry
                    : owner.getValue().entrySet()) {
                String packageKey =
                        normalizePackageName(packageEntry.getKey());
                if (packageKey.isEmpty()
                        || packageEntry.getValue() == null) {
                    continue;
                }
                List<String[]> packageConstraints =
                        constraints.computeIfAbsent(
                                packageKey,
                                ignored -> new ArrayList<>());
                for (String raw : packageEntry.getValue()) {
                    ParsedRequirement parsed = parseRequirement(raw);
                    if (parsed == null
                            || !packageKey.equals(
                                    normalizePackageName(parsed.name))) {
                        continue;
                    }
                    for (String[] spec : parsed.specs) {
                        packageConstraints.add(
                                new String[]{spec[0], spec[1]});
                    }
                }
                if (!PREINSTALLED_PACKAGES.contains(
                        packageKey)) {
                    RegistryRootDisk active = null;
                    for (RegistryRootDisk root
                            : registryRuntimeRoots) {
                        if (packageKey.equals(
                                root.distribution)) {
                            active = root;
                            break;
                        }
                    }
                    if (active == null) {
                        throw restartRequired(
                                "shared dependency "
                                        + packageKey
                                        + " has no exact active "
                                        + "registry root");
                    }
                    packageConstraints.add(
                            new String[]{
                                    "==", active.version
                            });
                }
            }
        }
        return constraints;
    }

    private ResolutionState solve(ResolutionState state, InstallerDelegate delegate)
            throws IOException, InstallCancelledException {
        if (delegate != null && delegate.isCancelled()) throw new InstallCancelledException();

        for (Map.Entry<String, WheelCandidate> selected : state.selected.entrySet()) {
            RequirementState constraints = state.states.get(selected.getKey());
            if (constraints != null && selected.getValue() != null
                    && !satisfies(selected.getValue().version, constraints.specs)) {
                return null;
            }
        }

        for (Map.Entry<String, RequirementState> entry : state.states.entrySet()) {
            String key = entry.getKey();
            if (state.selected.containsKey(key)) continue;
            RequirementState requirement = entry.getValue();
            if (PREINSTALLED_PACKAGES.contains(key)) {
                if (!requirement.specs.isEmpty()) {
                    String bundledVersion = getPreinstalledVersion(key);
                    if (bundledVersion == null
                            || !satisfies(
                                    bundledVersion,
                                    requirement.specs)) {
                        throw new IOException(
                                "Bundled package " + requirement.name
                                        + " does not satisfy "
                                        + formatSpecs(requirement.specs)
                                        + (bundledVersion != null
                                        ? " (bundled " + bundledVersion + ")"
                                        : " (version unavailable)"));
                    }
                }
                state.selected.put(key, null);
                state.expanded.put(key, requirement.signature());
                return solve(state, delegate);
            }
            if (delegate != null) {
                try { delegate.onProgress("Resolving " + requirement.name + "..."); }
                catch (Throwable ignored) {}
            }
            List<WheelCandidate> candidates = resolveWheelCandidates(requirement.asParsedRequirement(), delegate);
            String activeVersion =
                    getRuntimeDistributionVersion(requirement.name);
            if (activeVersion != null) {
                candidates.sort((left, right) -> {
                    boolean leftActive =
                            activeVersion.equals(left.version);
                    boolean rightActive =
                            activeVersion.equals(right.version);
                    return leftActive == rightActive
                            ? 0 : (leftActive ? -1 : 1);
                });
            }
            for (WheelCandidate candidate : candidates) {
                ResolutionState branch = state.copy();
                branch.selected.put(key, candidate);
                ResolutionState result = solve(branch, delegate);
                if (result != null) return result;
            }
            return null;
        }

        for (Map.Entry<String, RequirementState> entry : state.states.entrySet()) {
            String key = entry.getKey();
            WheelCandidate candidate = state.selected.get(key);
            if (candidate == null) continue;
            RequirementState requirement = entry.getValue();
            String signature = candidate.version + "|" + requirement.signature();
            if (signature.equals(state.expanded.get(key))) continue;
            ResolutionState branch = state.copy();
            branch.expanded.put(key, signature);
            PureWheelInfo selectedWheel =
                    resolveSelectedWheelMetadata(
                            requirement.name, candidate, delegate);
            for (String transitive : selectedWheel.requiresDist) {
                addRequirement(branch, transitive, requirement.extras);
            }
            return solve(branch, delegate);
        }
        return state;
    }

    private String getPreinstalledVersion(String normalizedName) {
        String cached = preinstalledVersions.get(normalizedName);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        String distribution = normalizedName;
        if ("pil".equals(distribution)) distribution = "pillow";
        else if ("bs4".equals(distribution)) distribution = "beautifulsoup4";
        else if ("yaml".equals(distribution)) distribution = "pyyaml";
        else if ("crypto".equals(distribution)) distribution = "pycryptodome";
        try {
            com.chaquo.python.PyObject version = getStartedPython()
                    .getModule("importlib.metadata")
                    .callAttr("version", distribution);
            String value = version != null ? version.toString().trim() : "";
            preinstalledVersions.put(normalizedName, value);
            return value.isEmpty() ? null : value;
        } catch (Throwable failure) {
            preinstalledVersions.put(normalizedName, "");
            return null;
        }
    }

    private String getRuntimeDistributionVersion(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return null;
        try {
            com.chaquo.python.PyObject version = getStartedPython()
                    .getModule("importlib.metadata")
                    .callAttr("version", packageName);
            String value = version != null ? version.toString().trim() : "";
            return value.isEmpty() ? null : value;
        } catch (Throwable failure) {
            return null;
        }
    }

    private static final class RequirementState {
        final String name;
        final List<String[]> specs = new ArrayList<>();
        final Set<String> extras = new LinkedHashSet<>();
        final Set<String> rawRequirements = new LinkedHashSet<>();

        RequirementState(String name) { this.name = name; }

        RequirementState copy() {
            RequirementState copy = new RequirementState(name);
            for (String[] spec : specs) copy.specs.add(new String[]{spec[0], spec[1]});
            copy.extras.addAll(extras);
            copy.rawRequirements.addAll(rawRequirements);
            return copy;
        }

        void merge(ParsedRequirement req, String raw) {
            for (String[] spec : req.specs) {
                mergeVersionConstraint(spec);
            }
            extras.addAll(req.extras);
            rawRequirements.add(raw);
        }

        void mergeVersionConstraints(List<String[]> constraints) {
            if (constraints == null) return;
            for (String[] constraint : constraints) {
                mergeVersionConstraint(constraint);
            }
        }

        private void mergeVersionConstraint(String[] spec) {
            if (spec == null || spec.length < 2) return;
            for (String[] existing : specs) {
                if (existing[0].equals(spec[0])
                        && existing[1].equals(spec[1])) {
                    return;
                }
            }
            specs.add(new String[]{spec[0], spec[1]});
        }

        ParsedRequirement asParsedRequirement() {
            return new ParsedRequirement(name, new ArrayList<>(specs), null,
                    new LinkedHashSet<>(extras));
        }

        String signature() {
            ArrayList<String> values = new ArrayList<>();
            for (String[] spec : specs) values.add(spec[0] + spec[1]);
            Collections.sort(values);
            ArrayList<String> extraValues = new ArrayList<>(extras);
            Collections.sort(extraValues);
            return values.toString() + "|" + extraValues;
        }
    }

    private static boolean markerApplies(String marker, Set<String> parentExtras) {
        if (marker == null || marker.trim().isEmpty()) return true;
        
        if (parentExtras != null && !parentExtras.isEmpty()) {
            for (String extra : parentExtras) {
                if (new MarkerParser(marker, extra).parse()) return true;
            }
            return false;
        }
        return new MarkerParser(marker, "").parse();
    }

    static boolean markerAppliesForTest(String marker, Set<String> parentExtras) {
        return markerApplies(marker, parentExtras);
    }

    private static String formatSpecs(List<String[]> specs) {
        ArrayList<String> out = new ArrayList<>();
        for (String[] spec : specs) out.add(spec[0] + spec[1]);
        return out.toString();
    }

    private void invalidateImportCaches() {
        try {
            getStartedPython().getModule("importlib").callAttr("invalidate_caches");
        } catch (Throwable t) {
            FileLog.e("nimarko: invalidate_caches failed", t);
        }
    }

    public List<String> installDependencies(List<String> requirements, String pluginId) {
        return installDependencies(requirements, pluginId, null);
    }

    private static String httpGet(String url, InstallerDelegate delegate)
            throws IOException, InstallCancelledException {
        throwIfCancelled(delegate);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "application/json");
        try {
            int code = conn.getResponseCode();
            throwIfCancelled(delegate);
            if (code != 200) throw new IOException("HTTP " + code + " from " + url);
            long declared = conn.getContentLengthLong();
            if (declared > MAX_PYPI_JSON_BYTES) {
                throw new IOException(
                        "PyPI JSON response is too large");
            }
            try (InputStream is = conn.getInputStream()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) {
                    throwIfCancelled(delegate);
                    if (baos.size() + n
                            > MAX_PYPI_JSON_BYTES) {
                        throw new IOException(
                                "PyPI JSON response exceeded "
                                        + "the byte limit");
                    }
                    baos.write(buf, 0, n);
                }
                return new String(
                        baos.toByteArray(),
                        StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }
    }

    public interface DownloadProgress { void onBytes(long done, long total); }

    private static void downloadToFile(String url, File target) throws IOException {
        try {
            downloadToFile(url, target, null, null);
        } catch (InstallCancelledException impossible) {
            throw new IOException(impossible);
        }
    }

    private static void downloadToFile(String url, File target, DownloadProgress cb,
                                       InstallerDelegate delegate)
            throws IOException, InstallCancelledException {
        throwIfCancelled(delegate);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(target.getParentFile(), target.getName() + ".part");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        try {
            int code = conn.getResponseCode();
            throwIfCancelled(delegate);
            if (code != 200) throw new IOException("HTTP " + code + " downloading " + url);
            long total = conn.getContentLengthLong();
            if (total > MAX_WHEEL_DOWNLOAD_BYTES) {
                throw new IOException(
                        "Wheel exceeds the download byte limit");
            }
            long done = 0;
            int lastPct = -1;
            try (InputStream is = conn.getInputStream();
                 FileOutputStream os = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = is.read(buf)) > 0) {
                    throwIfCancelled(delegate);
                    done += n;
                    if (done > MAX_WHEEL_DOWNLOAD_BYTES) {
                        throw new IOException(
                                "Wheel download exceeded "
                                        + "the byte limit");
                    }
                    os.write(buf, 0, n);
                    if (cb != null && total > 0) {
                        int pct = (int) (done * 100L / total);
                        if (pct != lastPct) { lastPct = pct; cb.onBytes(done, total); }
                    }
                }
                os.flush();
                os.getFD().sync();
            }
            if (!deleteArtifactAndVerify(target)) {
                throw new IOException(
                        "cannot replace stale wheel target "
                                + target);
            }
            try {
                android.system.Os.rename(
                        tmp.getAbsolutePath(),
                        target.getAbsolutePath());
            } catch (android.system.ErrnoException failure) {
                throw new IOException(
                        "rename failed: " + tmp + " -> "
                                + target,
                        failure);
            }
            syncDirectoryStrict(target.getParentFile());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            conn.disconnect();
        }
    }

    private final ConcurrentHashMap<String, String> metadataCache = new ConcurrentHashMap<>();

    private PureWheelInfo resolveSelectedWheelMetadata(
            String packageName, WheelCandidate candidate,
            InstallerDelegate delegate)
            throws IOException, InstallCancelledException {
        if (candidate == null
                || candidate.expectedSha256 == null
                || !candidate.expectedSha256.matches(
                        "(?i)[0-9a-f]{64}")) {
            throw new IOException(
                    "Selected wheel has no exact SHA-256 identity");
        }
        File cached = candidate.resolvedWheel;
        PureWheelInfo cachedInfo = candidate.resolvedInfo;
        if (cached != null && cachedInfo != null
                && verifySha256(
                        cached, candidate.expectedSha256)) {
            return cachedInfo;
        }

        File installed = new File(
                getWheelsDir(),
                normalizePackageName(packageName) + "-"
                        + candidate.version + ".whl");
        File resolved = installed.isFile()
                && verifySha256(
                        installed, candidate.expectedSha256)
                ? installed : new File(
                getWheelsDir(),
                ".resolve-"
                        + candidate.expectedSha256.toLowerCase(
                                Locale.ROOT)
                        + ".whl");
        boolean resolverTemporary =
                !resolved.equals(installed);
        if (resolved.exists()
                && (!resolved.isFile()
                        || !verifySha256(
                                resolved,
                                candidate.expectedSha256))) {
            if (!deleteArtifactAndVerify(resolved)) {
                throw new IOException(
                        "Cannot remove an untrusted resolver wheel "
                                + resolved);
            }
        }
        if (!resolved.isFile()) {
            downloadToFile(
                    candidate.downloadUrl, resolved,
                    null, delegate);
        }
        if (!verifySha256(
                resolved, candidate.expectedSha256)) {
            if (resolverTemporary) {
                deleteArtifactAndVerify(resolved);
            }
            throw new IOException(
                    "Selected wheel digest changed for "
                            + packageName);
        }
        try {
            PureWheelInfo info = inspectPureWheel(resolved);
            String normalized =
                    normalizePackageName(packageName);
            if (!normalized.equals(info.distribution)
                    || !versionsEquivalent(
                            candidate.version, info.version)) {
                throw new IOException(
                        "Selected wheel METADATA identity mismatch "
                                + "for " + packageName);
            }
            candidate.resolvedWheel = resolved;
            candidate.resolvedInfo = info;
            return info;
        } catch (Throwable failure) {
            if (resolverTemporary) {
                deleteArtifactAndVerify(resolved);
            }
            if (failure instanceof IOException) {
                throw (IOException) failure;
            }
            throw new IOException(
                    "Cannot inspect selected wheel for "
                            + packageName, failure);
        }
    }

    public WheelCandidate resolveWheel(ParsedRequirement req) throws IOException {
        refreshRuntimeMarkerEnvironment();
        try {
            List<WheelCandidate> candidates = resolveWheelCandidates(req, null);
            return candidates.isEmpty() ? null : candidates.get(0);
        } catch (InstallCancelledException impossible) {
            throw new IOException(impossible);
        }
    }

    private List<WheelCandidate> resolveWheelCandidates(ParsedRequirement req,
                                                        InstallerDelegate delegate)
            throws IOException, InstallCancelledException {
        throwIfCancelled(delegate);
        String url = PYPI_BASE + "/" + req.name + "/json";
        String body = metadataCache.get(req.name);
        if (body == null) {
            body = httpGet(url, delegate);
            metadataCache.put(req.name, body);
        }
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonObject releases = root.has("releases") && root.get("releases").isJsonObject()
                ? root.getAsJsonObject("releases") : null;
        if (releases == null) return Collections.emptyList();
        List<String> candidates = new ArrayList<>();
        for (String version : releases.keySet()) {
            if (!satisfies(version, req.specs)) continue;
            
            candidates.add(version);
        }
        candidates.sort(VersionComparator.INSTANCE.reversed());

        List<WheelCandidate> result = new ArrayList<>();
        
        for (String version : candidates) {
            if (isPreRelease(version)) continue;
            WheelCandidate c = pickWheelFromRelease(releases.getAsJsonArray(version), version);
            if (c != null) result.add(c);
        }
        
        for (String version : candidates) {
            if (!isPreRelease(version)) continue;
            WheelCandidate c = pickWheelFromRelease(releases.getAsJsonArray(version), version);
            if (c != null) result.add(c);
        }
        return result;
    }

    private WheelCandidate pickWheelFromRelease(JsonArray files, String version) {
        if (files == null) return null;
        
        WheelCandidate best = null;
        int bestScore = -1;
        for (JsonElement el : files) {
            if (!el.isJsonObject()) continue;
            JsonObject f = el.getAsJsonObject();
            if (f.has("yanked") && !f.get("yanked").isJsonNull() && f.get("yanked").getAsBoolean()) continue;
            String requiresPython = optString(f, "requires_python");
            if (requiresPython != null && !requiresPython.trim().isEmpty()
                    && !matchesSpec(pythonVersion, requiresPython)) continue;
            String fname = optString(f, "filename");
            if (fname == null || !fname.endsWith(".whl")) continue;
            String url = optString(f, "url");
            if (url == null) continue;
            String sha = null;
            if (f.has("digests") && f.get("digests").isJsonObject()) {
                sha = optString(f.getAsJsonObject("digests"), "sha256");
            }
            if (sha == null || !sha.matches("(?i)[0-9a-f]{64}")) continue;
            String tag = fname.substring(0, fname.length() - 4);
            
            String[] parts = tag.split("-");
            if (parts.length < 5) continue;
            String python = parts[parts.length - 3];
            String abi = parts[parts.length - 2];
            String platform = parts[parts.length - 1];
            if (!"none".equals(abi)
                    || !"any".equals(platform)) {
                continue;
            }
            int score = purePythonTagScore(python);
            if (score > bestScore) {
                bestScore = score;
                best = new WheelCandidate(
                        version, url, sha, true);
            }
        }
        return best;
    }

    private int purePythonTagScore(String compressedTag) {
        if (compressedTag == null) return -1;
        int[] runtime = pythonMajorMinor();
        String generic = "py" + runtime[0];
        String exact = generic + runtime[1];
        String cpython = "cp" + runtime[0] + runtime[1];
        int score = -1;
        for (String tag : compressedTag
                .toLowerCase(Locale.ROOT).split("\\.")) {
            if (cpython.equals(tag)) {
                score = Math.max(score, 4);
            } else if (exact.equals(tag)) {
                score = Math.max(score, 3);
            } else if (generic.equals(tag)) {
                score = Math.max(score, 2);
            }
        }
        return score;
    }

    private int[] pythonMajorMinor() {
        Matcher matcher = Pattern.compile(
                "^(\\d+)\\.(\\d+)")
                .matcher(pythonVersion != null
                        ? pythonVersion.trim() : "");
        if (!matcher.find()) {
            throw new IllegalStateException(
                    "Invalid Python runtime version "
                            + pythonVersion);
        }
        return new int[]{
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2))
        };
    }

    private boolean isCompatiblePureWheelTag(String tag) {
        if (tag == null) return false;
        String[] parts = tag.trim()
                .toLowerCase(Locale.ROOT).split("-");
        return parts.length == 3
                && "none".equals(parts[1])
                && "any".equals(parts[2])
                && purePythonTagScore(parts[0]) >= 0;
    }

    private static String optString(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return null;
        return o.get(key).getAsString();
    }

    private static boolean isPreRelease(String version) {
        return VersionComparator.isPreRelease(version);
    }

    private static void throwIfCancelled(InstallerDelegate delegate)
            throws InstallCancelledException {
        if (Thread.currentThread().isInterrupted()
                || (delegate != null && delegate.isCancelled())) {
            throw new InstallCancelledException();
        }
    }

    private static boolean verifySha256(File file, String expected) throws IOException {
        return file != null && file.isFile()
                && file.length() >= 0
                && file.length() <= MAX_WHEEL_DOWNLOAD_BYTES
                && expected != null
                && expected.equalsIgnoreCase(calculateSha256(file));
    }

    private File extractionDirForWheel(File wheelFile) {
        String base = wheelFile.getName();
        if (base.toLowerCase(Locale.ROOT).endsWith(".whl")) {
            base = base.substring(0, base.length() - 4);
        }
        return new File(getLibsDir(), "site/" + base);
    }

    private PureWheelInfo inspectPureWheel(File wheel)
            throws IOException {
        if (wheel == null || !wheel.isFile()) {
            throw new IOException("Pure wheel artifact is missing");
        }
        try (java.util.zip.ZipFile zip =
                new java.util.zip.ZipFile(wheel)) {
            java.util.zip.ZipEntry metadataEntry = null;
            java.util.zip.ZipEntry wheelEntry = null;
            java.util.zip.ZipEntry recordEntry = null;
            java.util.zip.ZipEntry topLevelEntry = null;
            String distInfoPrefix = null;
            java.util.Enumeration<? extends java.util.zip.ZipEntry>
                    entries = zip.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry =
                        entries.nextElement();
                String name = normalizedWheelEntryName(
                        entry.getName());
                if (name == null) {
                    throw new IOException(
                            "Wheel contains an unsafe archive path");
                }
                if (isNativeWheelEntry(name)) {
                    throw new IOException(
                            "Native extension rejected in pure wheel: "
                                    + name);
                }
                if (isPlatlibWheelEntry(name)) {
                    throw new IOException(
                            "platlib payload rejected in pure wheel: "
                                    + name);
                }
                if (entry.isDirectory()) continue;
                if (name.endsWith(".dist-info/METADATA")) {
                    String prefix = name.substring(
                            0,
                            name.length() - "METADATA".length());
                    if (metadataEntry != null
                            || (distInfoPrefix != null
                                    && !distInfoPrefix.equals(
                                            prefix))) {
                        throw new IOException(
                                "Wheel contains multiple dist-info "
                                        + "identities");
                    }
                    metadataEntry = entry;
                    distInfoPrefix = prefix;
                } else if (name.endsWith(
                        ".dist-info/WHEEL")) {
                    String prefix = name.substring(
                            0,
                            name.length() - "WHEEL".length());
                    if (wheelEntry != null
                            || (distInfoPrefix != null
                                    && !distInfoPrefix.equals(
                                            prefix))) {
                        throw new IOException(
                                "Wheel contains multiple dist-info "
                                        + "identities");
                    }
                    wheelEntry = entry;
                    distInfoPrefix = prefix;
                } else if (name.endsWith(
                        ".dist-info/RECORD")) {
                    String prefix = name.substring(
                            0,
                            name.length() - "RECORD".length());
                    if (recordEntry != null
                            || (distInfoPrefix != null
                                    && !distInfoPrefix.equals(
                                            prefix))) {
                        throw new IOException(
                                "Wheel contains multiple dist-info "
                                        + "identities");
                    }
                    recordEntry = entry;
                    distInfoPrefix = prefix;
                } else if (name.endsWith(
                        ".dist-info/top_level.txt")) {
                    String prefix = name.substring(
                            0,
                            name.length()
                                    - "top_level.txt".length());
                    if (topLevelEntry != null
                            || (distInfoPrefix != null
                                    && !distInfoPrefix.equals(
                                            prefix))) {
                        throw new IOException(
                                "Wheel contains multiple dist-info "
                                        + "identities");
                    }
                    topLevelEntry = entry;
                    distInfoPrefix = prefix;
                }
            }
            if (metadataEntry == null || wheelEntry == null
                    || recordEntry == null) {
                throw new IOException(
                        "Wheel is missing METADATA, WHEEL or RECORD");
            }
            byte[] metadata;
            byte[] wheelMetadata;
            byte[] record;
            try (InputStream input =
                    zip.getInputStream(metadataEntry)) {
                metadata = readBoundedStream(
                        input, MAX_WHEEL_METADATA_BYTES,
                        "wheel METADATA");
            }
            try (InputStream input =
                    zip.getInputStream(wheelEntry)) {
                wheelMetadata = readBoundedStream(
                        input, MAX_WHEEL_METADATA_BYTES,
                        "wheel WHEEL");
            }
            try (InputStream input =
                    zip.getInputStream(recordEntry)) {
                record = readBoundedStream(
                        input, MAX_DISTRIBUTION_RECORD_BYTES,
                        "wheel RECORD");
            }
            String[] identity =
                    metadataIdentity(metadata);
            Set<String> tags =
                    validatePureWheelMetadata(wheelMetadata);
            LinkedHashSet<String> importRoots =
                    parseRecordImportRoots(record);
            if (topLevelEntry != null) {
                try (InputStream input =
                        zip.getInputStream(topLevelEntry)) {
                    validateTopLevelHint(
                            readBoundedStream(
                                    input,
                                    MAX_DISTRIBUTION_METADATA_BYTES,
                                    "wheel top_level.txt"),
                            importRoots);
                }
            }
            return new PureWheelInfo(
                    identity[0], identity[1], tags,
                    metadataRequiresDist(metadata),
                    importRoots);
        }
    }

    private static byte[] readBoundedStream(
            InputStream input, int maxBytes, String label)
            throws IOException {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) > 0) {
            if (output.size() + count > maxBytes) {
                throw new IOException(label + " is too large");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String[] metadataIdentity(byte[] metadata)
            throws IOException {
        String distribution = null;
        String version = null;
        for (String line : new String(
                metadata, StandardCharsets.UTF_8)
                .split("\\r?\\n")) {
            if (line.isEmpty()) break;
            if (line.regionMatches(
                    true, 0, "Name:", 0, 5)) {
                distribution = normalizePackageName(
                        line.substring(5).trim());
            } else if (line.regionMatches(
                    true, 0, "Version:", 0, 8)) {
                version = line.substring(8).trim();
            }
        }
        if (distribution == null || distribution.isEmpty()
                || version == null || version.isEmpty()) {
            throw new IOException(
                    "Wheel METADATA has no exact name/version");
        }
        return new String[]{distribution, version};
    }

    private static List<String> metadataRequiresDist(
            byte[] metadata) throws IOException {
        ArrayList<String> result = new ArrayList<>();
        StringBuilder current = null;
        String[] lines = new String(
                metadata, StandardCharsets.UTF_8)
                .split("\\r?\\n", -1);
        for (String line : lines) {
            if (line.isEmpty()) {
                appendRequiresDist(result, current);
                current = null;
                break;
            }
            if (line.charAt(0) == ' '
                    || line.charAt(0) == '\t') {
                if (current == null) {
                    continue;
                }
                current.append(' ').append(line.trim());
                if (current.length() > 4096) {
                    throw new IOException(
                            "Wheel Requires-Dist header is too large");
                }
                continue;
            }
            appendRequiresDist(result, current);
            current = null;
            int separator = line.indexOf(':');
            if (separator <= 0) continue;
            String name = line.substring(0, separator).trim();
            if ("Requires-Dist".equalsIgnoreCase(name)) {
                current = new StringBuilder(
                        line.substring(separator + 1).trim());
            }
        }
        appendRequiresDist(result, current);
        if (result.size() > 4096) {
            throw new IOException(
                    "Wheel has too many Requires-Dist headers");
        }
        return result;
    }

    private static void appendRequiresDist(
            List<String> output, StringBuilder value)
            throws IOException {
        if (value == null) return;
        String requirement = value.toString().trim();
        if (requirement.isEmpty()
                || requirement.length() > 4096) {
            throw new IOException(
                    "Wheel has an invalid Requires-Dist header");
        }
        output.add(requirement);
    }

    private Set<String> validatePureWheelMetadata(
            byte[] wheelMetadata) throws IOException {
        Boolean rootIsPurelib = null;
        LinkedHashSet<String> tags =
                new LinkedHashSet<>();
        boolean compatible = false;
        for (String line : new String(
                wheelMetadata, StandardCharsets.UTF_8)
                .split("\\r?\\n")) {
            if (line.regionMatches(
                    true, 0,
                    "Root-Is-Purelib:", 0, 16)) {
                String value = line.substring(16).trim();
                if (rootIsPurelib != null) {
                    throw new IOException(
                            "WHEEL repeats Root-Is-Purelib");
                }
                rootIsPurelib =
                        "true".equalsIgnoreCase(value);
            } else if (line.regionMatches(
                    true, 0, "Tag:", 0, 4)) {
                String tag = line.substring(4).trim()
                        .toLowerCase(Locale.ROOT);
                String[] parts = tag.split("-");
                if (parts.length != 3
                        || !"none".equals(parts[1])
                        || !"any".equals(parts[2])) {
                    throw new IOException(
                            "Native/platform WHEEL tag rejected: "
                                    + tag);
                }
                tags.add(tag);
                compatible |= isCompatiblePureWheelTag(tag);
            }
        }
        if (!Boolean.TRUE.equals(rootIsPurelib)
                || tags.isEmpty() || !compatible) {
            throw new IOException(
                    "WHEEL is not purelib-compatible with Python "
                            + pythonVersion);
        }
        return tags;
    }

    private void validateExtractedPureWheel(
            File physicalRoot, File distInfo)
            throws IOException {
        byte[] wheelMetadata = readBoundedFile(
                new File(distInfo, "WHEEL"),
                MAX_WHEEL_METADATA_BYTES,
                "extracted WHEEL");
        validatePureWheelMetadata(wheelMetadata);
        validateNoNativeExtractionEntries(
                physicalRoot, physicalRoot);
    }

    private static void validateNoNativeExtractionEntries(
            File root, File current) throws IOException {
        File[] children = current.listFiles();
        if (children == null) {
            throw new IOException(
                    "Cannot enumerate extracted pure wheel");
        }
        String rootCanonical = root.getCanonicalPath();
        for (File child : children) {
            String canonical = child.getCanonicalPath();
            if (!canonical.equals(rootCanonical)
                    && !canonical.startsWith(
                            rootCanonical + File.separator)) {
                throw new IOException(
                        "Extracted wheel path escapes its root");
            }
            String relative = canonical.substring(
                    rootCanonical.length());
            while (relative.startsWith(File.separator)) {
                relative = relative.substring(1);
            }
            relative = relative.replace(
                    File.separatorChar, '/');
            if (isNativeWheelEntry(relative)
                    || isPlatlibWheelEntry(relative)) {
                throw new IOException(
                        "Native/platlib content rejected in "
                                + "extracted wheel: " + relative);
            }
            if (child.isDirectory()) {
                validateNoNativeExtractionEntries(
                        root, child);
            }
        }
    }

    private static String normalizedWheelEntryName(
            String raw) {
        if (raw == null || raw.isEmpty()
                || raw.indexOf('\0') >= 0) {
            return null;
        }
        String name = raw.replace('\\', '/');
        while (name.startsWith("./")) {
            name = name.substring(2);
        }
        if (name.isEmpty() || name.startsWith("/")
                || name.equals("..")
                || name.startsWith("../")
                || name.contains("/../")
                || name.contains("//")) {
            return null;
        }
        return name;
    }

    private static boolean isNativeWheelEntry(String name) {
        return name != null
                && NATIVE_WHEEL_ENTRY_PATTERN.matcher(
                        name).find();
    }

    private static boolean isPlatlibWheelEntry(String name) {
        if (name == null) return false;
        String[] parts = name.split("/");
        return parts.length >= 2
                && parts[0].toLowerCase(
                        Locale.ROOT).endsWith(".data")
                && "platlib".equalsIgnoreCase(parts[1]);
    }

    private boolean extractionDigestMatches(
            File marker, File extraction,
            String expectedDigest) throws IOException {
        return extractionDigestMatches(
                marker, extraction, expectedDigest,
                registryRuntimeRoots);
    }

    private boolean extractionDigestMatches(
            File marker, File extraction,
            String expectedDigest,
            List<RegistryRootDisk> authoritativeRoots)
            throws IOException {
        if (marker == null || !marker.isFile()
                || expectedDigest == null) {
            return false;
        }
        byte[] bytes = readBoundedFile(
                marker, 256, "extraction digest marker");
        String recorded = new String(
                bytes, StandardCharsets.US_ASCII).trim();
        if (expectedDigest.equalsIgnoreCase(recorded)) {
            return true;
        }
        
        if (!recorded.isEmpty()) return false;
        String canonical =
                extraction.getCanonicalPath();
        if (authoritativeRoots == null) return false;
        for (RegistryRootDisk root : authoritativeRoots) {
            if (root != null
                    && expectedDigest.equalsIgnoreCase(root.sha256)
                    && canonical.equals(
                            resolveArtifactPath(root.root)
                                    .getCanonicalPath())) {
                return true;
            }
        }
        return false;
    }

    private static void writeExtractionMarker(
            File extraction, String digest) throws IOException {
        if (digest == null
                || !digest.matches("(?i)[0-9a-f]{64}")) {
            throw new IOException(
                    "Invalid extraction digest");
        }
        File marker = new File(extraction, ".extracted");
        try (FileOutputStream output =
                new FileOutputStream(marker)) {
            output.write(
                    digest.toLowerCase(Locale.ROOT)
                            .getBytes(StandardCharsets.US_ASCII));
            output.write('\n');
            output.flush();
            output.getFD().sync();
        }
    }

    private static File stagedSibling(File target, String transactionId, String kind) {
        return new File(target.getParentFile(), "." + target.getName() + "."
                + transactionId + "." + kind + ".stage");
    }

    private static final class StagedReplacement {
        final File staged;
        final File target;
        final File backup;
        boolean backedUp;
        boolean committed;

        StagedReplacement(File staged, File target, String transactionId) {
            this.staged = staged;
            this.target = target;
            this.backup = new File(target.getParentFile(), "." + target.getName()
                    + "." + transactionId + ".backup");
        }

        void commit() throws IOException {
            try {
                if (target.exists()) {
                    android.system.Os.rename(
                            target.getAbsolutePath(),
                            backup.getAbsolutePath());
                    syncDirectoryStrict(target.getParentFile());
                    backedUp = true;
                }
                android.system.Os.rename(
                        staged.getAbsolutePath(),
                        target.getAbsolutePath());
                syncDirectoryStrict(target.getParentFile());
                committed = true;
            } catch (android.system.ErrnoException failure) {
                throw new IOException(
                        "cannot atomically commit " + target, failure);
            }
        }

        boolean rollback() {
            if (backedUp) {
                if (!backup.exists()) {
                    FileLog.e("PipController rollback backup is missing for " + target);
                    return false;
                }

                if (committed && target.exists()) {
                    if (staged.exists()) {
                        FileLog.e("PipController could not park replacement " + target);
                        return false;
                    }
                    //noinspection ResultOfMethodCallIgnored
                    target.renameTo(staged);
                    if (target.exists() || !staged.exists()) {
                        FileLog.e("PipController could not verify parked replacement " + target);
                        return false;
                    }
                }

                if (!deleteAndVerify(staged)) {
                    FileLog.e("PipController could not remove parked replacement " + staged
                            + "; recovery backup retained at " + backup);
                    return false;
                }
                if (target.exists()) {
                    FileLog.e("PipController restore path is occupied for " + target
                            + "; recovery backup retained at " + backup);
                    return false;
                }
                //noinspection ResultOfMethodCallIgnored
                backup.renameTo(target);
                if (!target.exists() || backup.exists()) {
                    FileLog.e("PipController could not verify restore of " + target
                            + " from " + backup + "; recovery backup retained");
                    return false;
                }

                committed = false;
                backedUp = false;
                return true;
            }

            if (committed && target.exists()) {
                if (staged.exists()) {
                    FileLog.e("PipController could not park committed artifact " + target);
                    return false;
                }
                //noinspection ResultOfMethodCallIgnored
                target.renameTo(staged);
                if (target.exists() || !staged.exists()) {
                    FileLog.e("PipController could not verify parked committed artifact " + target);
                    return false;
                }
            }
            if (!deleteAndVerify(staged)) {
                FileLog.e("PipController could not remove parked/staged artifact " + staged);
                return false;
            }
            committed = false;
            return true;
        }

        void finish() {
            if (backup.exists()) FileUtils.deleteRecursive(backup, true);
            if (staged.exists()) FileUtils.deleteRecursive(staged, true);
            syncDirectory(target.getParentFile());
            committed = false;
            backedUp = false;
        }

        private static boolean deleteAndVerify(File file) {
            if (file == null || !file.exists()) return true;
            FileUtils.deleteRecursive(file, true);
            return !file.exists();
        }

        File recoveryArtifact() {
            if (backup.exists()) return backup;
            if (staged.exists()) return staged;
            return target;
        }
    }

    private void extractPureWheel(
            File zip, File destDir,
            InstallerDelegate delegate)
            throws IOException, InstallCancelledException {
        throwIfCancelled(delegate);
        inspectPureWheel(zip);
        if (!destDir.exists() && !destDir.mkdirs() && !destDir.exists()) {
            throw new IOException("cannot create " + destDir);
        }
        String destCanon = destDir.getCanonicalPath();
        LinkedHashSet<String> outputs =
                new LinkedHashSet<>();
        long totalBytes = 0;
        try (java.util.zip.ZipInputStream zis =
                     new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(new FileInputStream(zip)))) {
            java.util.zip.ZipEntry e;
            byte[] buf = new byte[16 * 1024];
            while ((e = zis.getNextEntry()) != null) {
                throwIfCancelled(delegate);
                String archiveName =
                        normalizedWheelEntryName(
                                e.getName());
                if (archiveName == null) {
                    throw new IOException(
                            "Wheel contains an unsafe archive path");
                }
                if (isNativeWheelEntry(archiveName)
                        || isPlatlibWheelEntry(archiveName)) {
                    throw new IOException(
                            "Native/platlib wheel entry rejected: "
                                    + archiveName);
                }
                String outputName = archiveName;
                int slash = archiveName.indexOf('/');
                String first = slash >= 0
                        ? archiveName.substring(0, slash)
                        : archiveName;
                if (first.endsWith(".data")) {
                    String remainder = slash >= 0
                            ? archiveName.substring(slash + 1)
                            : "";
                    if (!remainder.startsWith("purelib/")) {
                        
                        zis.closeEntry();
                        continue;
                    }
                    outputName = remainder.substring(
                            "purelib/".length());
                    if (outputName.isEmpty()) {
                        zis.closeEntry();
                        continue;
                    }
                }
                File out = new File(destDir, outputName);
                if (!out.getCanonicalPath().startsWith(destCanon + File.separator)
                        && !out.getCanonicalPath().equals(destCanon)) {
                    throw new IOException(
                            "Wheel extraction escapes its root");
                }
                if (e.isDirectory()) {
                    if (!out.exists() && !out.mkdirs()
                            && !out.exists()) {
                        throw new IOException(
                                "Cannot create wheel directory "
                                        + out);
                    }
                } else {
                    String canonical =
                            out.getCanonicalPath();
                    if (!outputs.add(canonical)) {
                        throw new IOException(
                                "Wheel entries collide after purelib "
                                        + "relocation: " + outputName);
                    }
                    File parent = out.getParentFile();
                    if (parent == null
                            || (!parent.exists()
                                    && !parent.mkdirs()
                                    && !parent.exists())) {
                        throw new IOException(
                                "Cannot create wheel output parent");
                    }
                    try (OutputStream os = new FileOutputStream(out)) {
                        int n;
                        while ((n = zis.read(buf)) > 0) {
                            throwIfCancelled(delegate);
                            totalBytes += n;
                            if (totalBytes
                                    > 512L * 1024L * 1024L) {
                                throw new IOException(
                                        "Expanded wheel is too large");
                            }
                            os.write(buf, 0, n);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    public static final class ParsedRequirement {
        public final String name;
        public final List<String[]> specs; 
        public final String marker;
        public final Set<String> extras;
        public ParsedRequirement(String n, List<String[]> s, String m) {
            this(n, s, m, Collections.emptySet());
        }
        public ParsedRequirement(String n, List<String[]> s, String m, Set<String> extras) {
            this.name = n;
            this.specs = s == null ? Collections.emptyList() : s;
            this.marker = m;
            this.extras = extras == null ? Collections.emptySet()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(extras));
        }
    }

    public static ParsedRequirement parseRequirement(String requirement) {
        if (requirement == null) return null;
        Matcher m = REGEX_REQ_PARSE.matcher(requirement.trim());
        if (!m.find()) {
            return null;
        }
        String name = m.group(1);
        String extrasStr = m.group(2);
        String specStr = m.group(3);
        String marker = m.group(4);
        List<String[]> specs = new ArrayList<>();
        Set<String> extras = new LinkedHashSet<>();
        if (extrasStr != null && !extrasStr.trim().isEmpty()) {
            for (String extra : extrasStr.split(",")) {
                String normalized = normalizePackageName(extra.trim());
                if (normalized.isEmpty() || !normalized.matches("[a-z0-9][a-z0-9-]*")) return null;
                extras.add(normalized);
            }
        }
        if (specStr != null) {
            String normalizedSpecs =
                    unwrapCoreMetadataSpecifiers(specStr);
            if (normalizedSpecs == null) return null;
            Matcher sm = REGEX_REQ_SPECS.matcher(
                    normalizedSpecs);
            while (sm.find()) {
                specs.add(new String[]{ sm.group(1), sm.group(2) });
            }
            String residue = REGEX_REQ_SPECS.matcher(
                    normalizedSpecs).replaceAll("")
                    .replace(",", "").trim();
            if (!residue.isEmpty()) return null;
        }
        return new ParsedRequirement(name, specs, marker, extras);
    }

    private static String unwrapCoreMetadataSpecifiers(
            String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.isEmpty()) return "";
        if (value.charAt(0) != '(') {
            return value.indexOf('(') >= 0
                    || value.indexOf(')') >= 0
                    ? null : value;
        }
        if (value.length() < 2
                || value.charAt(value.length() - 1) != ')') {
            return null;
        }
        int depth = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '(') {
                depth++;
                if (depth > 1) return null;
            } else if (character == ')') {
                depth--;
                if (depth < 0
                        || (depth == 0
                                && index
                                        != value.length() - 1)) {
                    return null;
                }
            }
        }
        return depth == 0
                ? value.substring(1, value.length() - 1).trim()
                : null;
    }

    private static String parseRequirementName(String requirement) {
        ParsedRequirement r = parseRequirement(requirement);
        return r == null ? null : r.name;
    }

    public static String normalizePackageName(String name) {
        if (name == null) return "";
        return REGEX_NORMALIZE.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("-");
    }

    private static String parseWheelPackage(String filename) {
        if (filename == null) return "";
        int dash = filename.indexOf('-');
        return dash > 0 ? filename.substring(0, dash) : filename;
    }

    public static boolean satisfies(String version, List<String[]> specs) {
        if (specs == null || specs.isEmpty()) return true;
        for (String[] s : specs) {
            if (!compareSpec(version, s[0], s[1])) return false;
        }
        return true;
    }

    public static boolean compareSpec(String version, String op, String target) {
        if (version == null || op == null || target == null) {
            return false;
        }
        if (("==".equals(op) || "!=".equals(op))
                && target.endsWith(".*")) {
            String prefix = stripLocal(target.substring(0, target.length() - 2));
            String actual = stripLocal(version);
            boolean match = actual.equals(prefix) || actual.startsWith(prefix + ".");
            return "!=".equals(op) ? !match : match;
        }
        if ("===".equals(op)) {
            return version != null && target != null
                    && version.trim().equalsIgnoreCase(
                            target.trim());
        }
        if ("==".equals(op) || "!=".equals(op)) {
            String comparedVersion =
                    target.indexOf('+') >= 0
                            ? version : stripLocal(version);
            boolean equal = VersionComparator.INSTANCE.compare(
                    comparedVersion, target) == 0;
            return "!=".equals(op) ? !equal : equal;
        }
        String orderedVersion =
                target.indexOf('+') >= 0
                        ? version : stripLocal(version);
        int cmp = VersionComparator.INSTANCE.compare(
                orderedVersion, target);
        switch (op) {
            case "<": return cmp < 0;
            case "<=": return cmp <= 0;
            case ">": return cmp > 0;
            case ">=": return cmp >= 0;
            case "~=": {
                
                if (cmp < 0) return false;
                String[] tParts = stripLocal(target).split("\\.");
                if (tParts.length < 2) return cmp >= 0;
                try {
                    int bumpIndex = tParts.length - 2;
                    int bumped = Integer.parseInt(tParts[bumpIndex]) + 1;
                    StringBuilder upperBuilder = new StringBuilder();
                    for (int i = 0; i <= bumpIndex; i++) {
                        if (i > 0) upperBuilder.append('.');
                        upperBuilder.append(i == bumpIndex ? bumped : Integer.parseInt(tParts[i]));
                    }
                    String upper = upperBuilder.toString();
                    return VersionComparator.INSTANCE.compare(version, upper) < 0;
                } catch (NumberFormatException nfe) {
                    return cmp >= 0;
                }
            }
            default: return true;
        }
    }

    public static boolean matchesSpec(String version, String spec) {
        Matcher sm = REGEX_REQ_SPECS.matcher(spec);
        while (sm.find()) {
            if (!compareSpec(version, sm.group(1), sm.group(2))) return false;
        }
        return true;
    }

    private static String stripLocal(String v) {
        if (v == null) return "";
        int plus = v.indexOf('+');
        return plus >= 0 ? v.substring(0, plus) : v;
    }

    public static String calculateSha256(File file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format(Locale.US, "%02x", b & 0xFF));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    public static final class WheelCandidate {
        public final String version;
        public final String downloadUrl;
        public final String expectedSha256;
        public final boolean isPure;
        private volatile File resolvedWheel;
        private volatile PureWheelInfo resolvedInfo;

        public WheelCandidate(String v, String u, String h, boolean pure) {
            this.version = v; this.downloadUrl = u; this.expectedSha256 = h; this.isPure = pure;
        }
        public String getVersion() { return version; }
        public String getDownloadUrl() { return downloadUrl; }
        public String getExpectedSha256() { return expectedSha256; }
        public boolean isPure() { return isPure; }
    }

    public static final class VersionComparator implements Comparator<String> {
        public static final VersionComparator INSTANCE = new VersionComparator();
        private static final Pattern PEP440 = Pattern.compile(
                "^v?(?:(\\d+)!)?(\\d+(?:[._-]\\d+)*)"
                + "(?:[-_.]?(a|b|c|rc|alpha|beta|pre|preview)[-_.]?(\\d*)?)?"
                + "(?:(?:-(\\d+))|(?:[-_.]?(post|rev|r)[-_.]?(\\d*)?))?"
                + "(?:[-_.]?(dev)[-_.]?(\\d*)?)?(?:\\+([a-z0-9]+(?:[-_.][a-z0-9]+)*))?$",
                Pattern.CASE_INSENSITIVE);

        @Override
        public int compare(String a, String b) {
            PepVersion left = PepVersion.parse(a);
            PepVersion right = PepVersion.parse(b);
            if (left == null || right == null) {
                return normalizeVersionText(a).compareTo(normalizeVersionText(b));
            }
            return left.compareTo(right);
        }

        static boolean isPreRelease(String value) {
            PepVersion parsed = PepVersion.parse(value);
            return parsed != null && (parsed.prePhase >= 0 || parsed.dev != null);
        }

        private static String normalizeVersionText(String value) {
            return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('-', '.').replace('_', '.');
        }

        private static final class PepVersion implements Comparable<PepVersion> {
            final int epoch;
            final int[] release;
            final int prePhase; 
            final int preNumber;
            final Integer post;
            final Integer dev;
            final String local;

            PepVersion(int epoch, int[] release, int prePhase, int preNumber,
                       Integer post, Integer dev, String local) {
                this.epoch = epoch; this.release = release; this.prePhase = prePhase;
                this.preNumber = preNumber; this.post = post; this.dev = dev; this.local = local;
            }

            static PepVersion parse(String value) {
                if (value == null) return null;
                Matcher matcher = PEP440.matcher(value.trim());
                if (!matcher.matches()) return null;
                try {
                    int epoch = matcher.group(1) == null ? 0 : Integer.parseInt(matcher.group(1));
                    String[] releaseText = matcher.group(2).split("[._-]");
                    int end = releaseText.length;
                    while (end > 1 && Integer.parseInt(releaseText[end - 1]) == 0) end--;
                    int[] release = new int[end];
                    for (int i = 0; i < end; i++) release[i] = Integer.parseInt(releaseText[i]);
                    String pre = matcher.group(3);
                    int phase = -1;
                    if (pre != null) {
                        pre = pre.toLowerCase(Locale.ROOT);
                        phase = ("a".equals(pre) || "alpha".equals(pre)) ? 0
                                : ("b".equals(pre) || "beta".equals(pre)) ? 1 : 2;
                    }
                    int preNumber = parseOptionalNumber(matcher.group(4));
                    Integer post = matcher.group(5) != null
                            ? Integer.valueOf(matcher.group(5))
                            : matcher.group(6) != null ? parseOptionalNumber(matcher.group(7)) : null;
                    Integer dev = matcher.group(8) != null ? parseOptionalNumber(matcher.group(9)) : null;
                    String local = matcher.group(10);
                    return new PepVersion(epoch, release, phase, preNumber, post, dev,
                            local == null ? null : normalizeVersionText(local));
                } catch (RuntimeException badVersion) {
                    return null;
                }
            }

            private static int parseOptionalNumber(String value) {
                return value == null || value.isEmpty() ? 0 : Integer.parseInt(value);
            }

            @Override public int compareTo(PepVersion other) {
                int cmp = Integer.compare(epoch, other.epoch);
                if (cmp != 0) return cmp;
                int count = Math.max(release.length, other.release.length);
                for (int i = 0; i < count; i++) {
                    cmp = Integer.compare(i < release.length ? release[i] : 0,
                            i < other.release.length ? other.release[i] : 0);
                    if (cmp != 0) return cmp;
                }
                
                int thisPre = prePhase < 0 ? (dev != null && post == null ? -1 : 3) : prePhase;
                int otherPre = other.prePhase < 0
                        ? (other.dev != null && other.post == null ? -1 : 3) : other.prePhase;
                cmp = Integer.compare(thisPre, otherPre);
                if (cmp != 0) return cmp;
                if (prePhase >= 0 || other.prePhase >= 0) {
                    cmp = Integer.compare(preNumber, other.preNumber);
                    if (cmp != 0) return cmp;
                }
                
                cmp = Integer.compare(post == null ? -1 : post, other.post == null ? -1 : other.post);
                if (cmp != 0) return cmp;
                
                cmp = Integer.compare(dev == null ? Integer.MAX_VALUE : dev,
                        other.dev == null ? Integer.MAX_VALUE : other.dev);
                if (cmp != 0) return cmp;
                if (local == null || other.local == null) {
                    return local == null ? (other.local == null ? 0 : -1) : 1;
                }
                return compareLocal(local, other.local);
            }

            private static int compareLocal(String left, String right) {
                String[] a = left.split("\\.");
                String[] b = right.split("\\.");
                int count = Math.max(a.length, b.length);
                for (int i = 0; i < count; i++) {
                    if (i >= a.length) return -1;
                    if (i >= b.length) return 1;
                    boolean an = a[i].matches("\\d+");
                    boolean bn = b[i].matches("\\d+");
                    int cmp;
                    if (an && bn) cmp = new java.math.BigInteger(a[i]).compareTo(new java.math.BigInteger(b[i]));
                    else if (an != bn) cmp = an ? 1 : -1;
                    else cmp = a[i].compareTo(b[i]);
                    if (cmp != 0) return cmp;
                }
                return 0;
            }
        }
    }

    public static final class ParsedVersion {
        public final int epoch;
        public final String publicVersion;
        public final List<String> parts;
        public ParsedVersion(int e, String v, List<String> p) {
            this.epoch = e; this.publicVersion = v; this.parts = p;
        }
        public int getEpoch() { return epoch; }
        public String getPublicVersion() { return publicVersion; }
        public List<String> getParts() { return parts; }
    }

    public static final class MarkerParser {
        private final String marker;
        private final String activeExtra;
        private int pos;
        private final String[] tokens;

        public MarkerParser(String marker) {
            this(marker, "");
        }

        public MarkerParser(String marker, String activeExtra) {
            this.marker = marker == null ? "" : marker.trim();
            this.activeExtra = activeExtra == null ? "" : normalizePackageName(activeExtra);
            this.tokens = tokenize(this.marker);
            this.pos = 0;
        }

        public boolean parse() {
            if (marker.isEmpty()) return true;
            try {
                boolean v = parseOr();
                if (pos != tokens.length) throw new IllegalArgumentException("trailing marker tokens");
                return v;
            } catch (Throwable t) {
                FileLog.w("nimarko: marker '" + marker + "' parse failed: " + t);
                return false; 
            }
        }

        private boolean parseOr() {
            boolean left = parseAnd();
            while (peek("or")) {
                consume();
                boolean right = parseAnd();
                left = left || right;
            }
            return left;
        }

        private boolean parseAnd() {
            boolean left = parseNot();
            while (peek("and")) {
                consume();
                boolean right = parseNot();
                left = left && right;
            }
            return left;
        }

        private boolean parseNot() {
            if (peek("not") && !peekAhead(1, "in")) {
                consume();
                return !parseNot();
            }
            return parsePrimary();
        }

        private boolean parsePrimary() {
            if (peek("(")) {
                consume();
                boolean v = parseOr();
                if (peek(")")) consume();
                return v;
            }
            
            String leftToken = requireToken();
            String op = consume();
            
            if ("not".equals(op) && peek("in")) {
                consume();
                String rightToken = requireToken();
                String leftValue = operand(leftToken);
                String rightValue = operand(rightToken);
                if ("extra".equals(leftToken)) leftValue = normalizePackageName(leftValue);
                return !rightValue.contains(leftValue);
            }
            if (op.isEmpty()) throw new IllegalArgumentException("missing marker operator");
            String rightToken = requireToken();
            String lhs = operand(leftToken);
            String value = operand(rightToken);
            if ("extra".equals(leftToken)) value = normalizePackageName(value);
            if ("extra".equals(rightToken)) lhs = normalizePackageName(lhs);
            boolean versionComparison = isPythonVersionVariable(leftToken)
                    || isPythonVersionVariable(rightToken);
            int comparison = versionComparison
                    ? PipController.VersionComparator.INSTANCE.compare(lhs, value)
                    : lhs.compareTo(value);
            switch (op) {
                case "==": return lhs.equals(value);
                case "!=": return !lhs.equals(value);
                case "<": return comparison < 0;
                case "<=": return comparison <= 0;
                case ">": return comparison > 0;
                case ">=": return comparison >= 0;
                case "in": return value.contains(lhs);
                default: throw new IllegalArgumentException("unsupported marker operator: " + op);
            }
        }

        private String requireToken() {
            String value = consume();
            if (value.isEmpty()) throw new IllegalArgumentException("missing marker operand");
            return value;
        }

        private String operand(String token) {
            if (token == null) return "";
            if (isQuoted(token)) return unquote(token);
            return envLookup(token);
        }

        private static boolean isQuoted(String value) {
            return value.length() >= 2
                    && (value.charAt(0) == '\'' || value.charAt(0) == '"')
                    && value.charAt(value.length() - 1) == value.charAt(0);
        }

        private static boolean isPythonVersionVariable(String token) {
            return "python_version".equals(token) || "python_full_version".equals(token);
        }

        private boolean peek(String s) {
            return pos < tokens.length && tokens[pos].equalsIgnoreCase(s);
        }
        private boolean peekAhead(int off, String s) {
            return pos + off < tokens.length && tokens[pos + off].equalsIgnoreCase(s);
        }
        private String consume() {
            return pos < tokens.length ? tokens[pos++] : "";
        }

        private static String[] tokenize(String s) {
            List<String> out = new ArrayList<>();
            int i = 0;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c)) { i++; continue; }
                if (c == '(' || c == ')') { out.add(String.valueOf(c)); i++; continue; }
                if (c == '\'' || c == '"') {
                    int j = s.indexOf(c, i + 1);
                    if (j < 0) j = s.length();
                    out.add(s.substring(i, Math.min(j + 1, s.length())));
                    i = j + 1; continue;
                }
                if (c == '!' || c == '=' || c == '<' || c == '>' || c == '~') {
                    int j = i + 1;
                    if (j < s.length() && s.charAt(j) == '=') j++;
                    out.add(s.substring(i, j));
                    i = j; continue;
                }
                int j = i;
                while (j < s.length() && !Character.isWhitespace(s.charAt(j))
                        && s.charAt(j) != '(' && s.charAt(j) != ')'
                        && s.charAt(j) != '\'' && s.charAt(j) != '"'
                        && s.charAt(j) != '!' && s.charAt(j) != '=' && s.charAt(j) != '<' && s.charAt(j) != '>') {
                    j++;
                }
                out.add(s.substring(i, j));
                i = j;
            }
            return out.toArray(new String[0]);
        }

        private static String unquote(String s) {
            if (s == null) return "";
            if (s.length() >= 2 && (s.charAt(0) == '\'' || s.charAt(0) == '"')
                    && s.charAt(s.length() - 1) == s.charAt(0)) {
                return s.substring(1, s.length() - 1);
            }
            return s;
        }

        private String envLookup(String name) {
            if (name == null) return "";
            switch (name) {
                case "python_version":
                    return PipController.getInstance().getPythonVersion();
                case "python_full_version":
                    return PipController.getInstance()
                            .getPythonFullVersion();
                case "sys_platform": return "linux";
                case "platform_system": return "Linux";
                case "platform_machine":
                    return PipController.getInstance()
                            .getPlatformMachine();
                case "os_name": return "posix";
                case "implementation_name": return "cpython";
                case "platform_python_implementation": return "CPython";
                case "platform_release": return "android";
                case "extra": return activeExtra;
                default: throw new IllegalArgumentException("unknown marker variable: " + name);
            }
        }
    }

    private PipController() {}

    public List<String> parseDependenciesFromMetadata(File metadataFile) {
        if (metadataFile == null || !metadataFile.isFile()) {
            return Collections.emptyList();
        }
        try {
            return metadataRequiresDist(
                    readBoundedFile(
                            metadataFile,
                            MAX_DISTRIBUTION_METADATA_BYTES,
                            "dependency METADATA"));
        } catch (Throwable failure) {
            FileLog.e("PipController could not parse dependency "
                    + "METADATA " + metadataFile, failure);
            return Collections.emptyList();
        }
    }
}
