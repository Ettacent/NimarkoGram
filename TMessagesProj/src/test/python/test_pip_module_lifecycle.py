import copy
import hashlib
import json
import os
import pathlib
import re
import tempfile
import types
import unittest
import zipfile

REPO = pathlib.Path(__file__).resolve().parents[4]
PIP = (
    REPO
    / "TMessagesProj/src/main/java/app/nimarkogram/messenger/plugins/pip"
    / "PipController.java"
)

def _inside(path, root):
    if path is None:
        return False
    path = pathlib.PurePosixPath(path)
    root = pathlib.PurePosixPath(root)
    return path == root or root in path.parents

def _model_eviction(modules, roots):
    """Small executable model of the Java provenance rules."""
    selected = []
    for name, module in modules.items():
        top_level = name.split(".", 1)[0]
        origin_matches = {
            root
            for root in roots
            if _inside(module.get("file"), root)
            or _inside(module.get("origin"), root)
        }
        namespace_matches = set()
        namespace_outside = False
        for path in module.get("path", ()):
            matches = {root for root in roots if _inside(path, root)}
            if matches:
                namespace_matches.update(matches)
            else:
                namespace_outside = True
        matches = origin_matches | namespace_matches
        if not matches:
            continue
        if not any(top_level in roots[root] for root in matches):
            raise RuntimeError("restart required: unproved import root")
        if origin_matches or (namespace_matches and not namespace_outside):
            selected.append(name)
    return sorted(
        selected,
        key=lambda name: (name.count("."), len(name), name),
        reverse=True,
    )

def _atomic_transition(paths, modules, expected_paths, desired_paths, removals,
                       fail_after=None, discovered=None):
    """Executable host model of the one-call Python transition helper."""
    before_paths = list(paths)
    before_modules = dict(modules)
    parent_attrs = []
    validated = []

    if paths != expected_paths:
        raise RuntimeError("managed sys.path identity changed")
    if len(desired_paths) != len(set(desired_paths)):
        raise RuntimeError("duplicate desired managed path")
    for name, identity in removals:
        module = modules.get(name)
        if module is None or id(module) != identity:
            raise RuntimeError("module identity changed")
        validated.append((name, module))
        parent_name, separator, child = name.rpartition(".")
        if separator and parent_name in modules:
            parent = modules[parent_name]
            parent_attrs.append(
                (parent.__dict__, child, parent.__dict__.get(child, _MISSING))
            )
    if discovered is not None:
        planned = {name: identity for name, identity in removals}
        observed = {name: identity for name, identity in discovered}
        if observed != planned:
            raise RuntimeError("managed eviction set changed")

    try:
        paths[:] = desired_paths
        for index, (name, module) in enumerate(validated):
            if modules.get(name) is not module:
                raise RuntimeError("module identity changed during eviction")
            del modules[name]
            parent_name, separator, child = name.rpartition(".")
            parent = modules.get(parent_name) if separator else None
            if parent is not None and parent.__dict__.get(child, _MISSING) is module:
                del parent.__dict__[child]
            if fail_after is not None and index == fail_after:
                raise RuntimeError("injected transition failure")
    except BaseException:
        paths[:] = before_paths
        modules.clear()
        modules.update(before_modules)
        for parent_dict, child, old in parent_attrs:
            if old is _MISSING:
                parent_dict.pop(child, None)
            else:
                parent_dict[child] = old
        raise

_MISSING = object()

def _java_string_constant(source, start, end):
    block = source[source.index(start):source.index(end)]
    fragments = re.findall(r'"((?:\\.|[^"\\])*)"', block)
    return "".join(
        json.loads(f'"{fragment}"') for fragment in fragments
    )

def _record_roots(paths):
    roots = set()
    for path in paths:
        path = path.replace("\\", "/")
        if path.startswith("../") or "/../" in path:
            continue
        first, separator, remainder = path.partition("/")
        if first.endswith(".data"):
            kind, separator, remainder = remainder.partition("/")
            if kind == "platlib":
                raise ValueError("platlib is not pure")
            if kind != "purelib" or not separator:
                continue
            first, separator, remainder = remainder.partition("/")
        if first.endswith((".dist-info", ".data")):
            continue
        if separator:
            if remainder.endswith((".py", ".pyi", ".pyc")):
                roots.add(first)
        elif first.endswith(".py"):
            roots.add(first[:-3])
        elif first.endswith((".pyi", ".pyc")):
            roots.add(first[:-4])
    return roots

def _registry_checksum(document):
    canonical = copy.deepcopy(document)
    canonical.pop("checksum", None)
    encoded = json.dumps(
        canonical, separators=(",", ":")
    )
    for character, escaped in (
            ("<", "\\u003c"),
            (">", "\\u003e"),
            ("&", "\\u0026"),
            ("=", "\\u003d"),
            ("'", "\\u0027")):
        encoded = encoded.replace(character, escaped)
    encoded = encoded.encode()
    return hashlib.sha256(encoded).hexdigest()

def _bootstrap_paths(registry, artifact_metadata):
    if registry.get("schema") != 2:
        raise ValueError("schema")
    expected = registry.get("checksum")
    if not expected or expected != _registry_checksum(registry):
        raise ValueError("checksum")
    paths = []
    distributions = set()
    for root in registry["roots"]:
        identity = artifact_metadata.get(root["root"])
        exact = (
            root["distribution"],
            root["version"],
            root["sha256"],
            tuple(root["importRoots"]),
        )
        if identity != exact or root["distribution"] in distributions:
            raise ValueError("runtime identity")
        distributions.add(root["distribution"])
        paths.append(root["root"])
    referenced = {
        package
        for owner in registry["ownership"].values()
        for package in owner
    }
    if referenced != distributions:
        raise ValueError("coverage")
    return paths

def _migrate_legacy(ownership, roots):
    """Deterministic model: highest compatible stable root per distribution."""
    selected = []
    referenced = sorted({
        package
        for owner in ownership.values()
        for package in owner
    })
    for distribution in referenced:
        candidates = [
            root for root in roots
            if root["distribution"] == distribution
        ]
        stable = [root for root in candidates if not root["prerelease"]]
        candidates = stable or candidates
        if not candidates:
            raise ValueError("missing legacy root")
        selected.append(max(
            candidates,
            key=lambda root: tuple(
                int(part) for part in root["version"].split(".")
            ),
        ))
    return selected

def _cleanup_exact(active, journal, artifacts, rollback=()):
    protected = set(active)
    protected.update(rollback)
    for entry in journal:
        protected.update(
            entry[key] for key in ("target", "staged", "backup")
        )
    return {artifact for artifact in artifacts if artifact not in protected}

def _python_tag_compatible(tag, version=(3, 11)):
    python, abi, platform = tag.lower().split("-")
    accepted = {
        f"py{version[0]}",
        f"py{version[0]}{version[1]}",
        f"cp{version[0]}{version[1]}",
    }
    return abi == "none" and platform == "any" and bool(
        accepted.intersection(python.split("."))
    )

def _validate_and_extract_pure_wheel(wheel, destination):
    with zipfile.ZipFile(wheel) as archive:
        names = archive.namelist()
        wheel_name = next(
            (name for name in names if name.endswith(".dist-info/WHEEL")),
            None,
        )
        if wheel_name is None:
            raise ValueError("missing WHEEL")
        headers = archive.read(wheel_name).decode().splitlines()
        pure = [
            line.split(":", 1)[1].strip().lower()
            for line in headers if line.lower().startswith("root-is-purelib:")
        ]
        tags = [
            line.split(":", 1)[1].strip()
            for line in headers if line.lower().startswith("tag:")
        ]
        parsed_tags = [tag.lower().split("-") for tag in tags]
        if (
            pure != ["true"]
            or not tags
            or any(
                len(parts) != 3
                or parts[1] != "none"
                or parts[2] != "any"
                for parts in parsed_tags
            )
            or not any(_python_tag_compatible(tag) for tag in tags)
        ):
            raise ValueError("incompatible WHEEL")
        for name in names:
            lower = name.lower()
            parts = name.split("/")
            if (
                lower.endswith((".so", ".pyd", ".dll", ".dylib"))
                or ".so." in lower
                or (
                    len(parts) >= 2
                    and parts[0].endswith(".data")
                    and parts[1].lower() == "platlib"
                )
            ):
                raise ValueError("native or platlib")

        outputs = set()
        for info in archive.infolist():
            name = info.filename
            parts = name.split("/")
            if parts[0].endswith(".data"):
                if len(parts) < 3 or parts[1] != "purelib":
                    continue
                name = "/".join(parts[2:])
            if not name or info.is_dir():
                continue
            target = destination / name
            if target in outputs:
                raise ValueError("relocation collision")
            outputs.add(target)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(info))
    return outputs

def _make_wheel(path, *, tag="py3-none-any", root_is_pure=True,
                payload="purelib"):
    dist_info = "demo-1.0.dist-info"
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr(
            f"{dist_info}/METADATA",
            "Metadata-Version: 2.1\nName: demo\nVersion: 1.0\n",
        )
        archive.writestr(
            f"{dist_info}/WHEEL",
            "Wheel-Version: 1.0\n"
            f"Root-Is-Purelib: {str(root_is_pure).lower()}\n"
            f"Tag: {tag}\n",
        )
        if payload == "purelib":
            archive.writestr(
                "demo-1.0.data/purelib/demo/__init__.py",
                "VALUE = 1\n",
            )
        elif payload == "platlib":
            archive.writestr(
                "demo-1.0.data/platlib/demo/__init__.py",
                "VALUE = 1\n",
            )
        elif payload == "native":
            archive.writestr("demo/native.cpython-311.so", b"\x7fELF")
        archive.writestr(
            f"{dist_info}/RECORD",
            "demo-1.0.data/purelib/demo/__init__.py,,\n",
        )

class PipModuleLifecycleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = PIP.read_text()

    def test_install_v1_to_v2_evicts_only_v1_children_first(self):
        roots = {"/site/demo-1": {"demo"}}
        modules = {
            "demo": {"file": "/site/demo-1/demo/__init__.py"},
            "demo.child": {"file": "/site/demo-1/demo/child.py"},
            "other": {"file": "/site/other-1/other.py"},
        }
        self.assertEqual(
            ["demo.child", "demo"],
            _model_eviction(modules, roots),
        )

    def test_rollback_evicts_candidate_before_old_reimport(self):
        roots = {"/site/demo-2": {"demo"}}
        modules = {
            "demo": {"file": "/site/demo-2/demo/__init__.py"},
            "demo.cache": {"file": "/site/demo-2/demo/cache.py"},
        }
        self.assertEqual(
            ["demo.cache", "demo"],
            _model_eviction(modules, roots),
        )
        rollback = self.source[
            self.source.index(
                "public synchronized boolean "
                "rollbackDeferredArtifactTransaction("):
            self.source.index(
                "public synchronized boolean "
                "discardDeferredArtifactTransaction(")
        ]
        self.assertLess(
            rollback.index("executeManagedRuntimeTransition("),
            rollback.index("rollbackDeferredArtifactEntry(entry)"),
        )

    def test_last_owner_evicts_shared_owner_does_not(self):
        owners = {"a": {"demo"}, "b": {"demo"}}
        remaining_after_a = set().union(
            *(packages for owner, packages in owners.items() if owner != "a")
        )
        self.assertIn("demo", remaining_after_a)
        remaining_after_b = set().union(
            *(packages for owner, packages in {"b": {"demo"}}.items()
              if owner != "b"),
        )
        self.assertNotIn("demo", remaining_after_b)
        uninstall = self.source[
            self.source.index(
                "private ManagedTransition buildUninstallTransition("):
            self.source.index(
                "private LinkedHashSet<String> canonicalManagedPaths(")
        ]
        self.assertIn("hasOtherDistributionOwner(", uninstall)

    def test_distribution_import_mismatch_uses_top_level(self):
        self.assertIn(
            'new File(distInfo, "top_level.txt")',
            self.source,
        )
        self.assertNotEqual("pillow", "PIL")
        self.assertIn("PIL", {"PIL"})
        parser = self.source[
            self.source.index(
                "private static Set<String> "
                "readDistributionImportRoots("):
            self.source.index(
                "private static boolean isPythonModuleRecord(")
        ]
        self.assertLess(
            parser.index('new File(distInfo, "RECORD")'),
            parser.index('new File(distInfo, "top_level.txt")'),
        )
        self.assertIn(
            "validateTopLevelHint(bytes, roots)",
            parser,
        )

    def test_record_fallback_finds_import_names(self):
        self.assertEqual(
            {"PIL", "single", "relocated"},
            _record_roots([
                "Pillow-11.0.dist-info/METADATA",
                "PIL/__init__.py",
                "PIL/Image.py",
                "single.py",
                "demo-1.0.data/purelib/relocated/__init__.py",
                "../../../bin/tool",
            ]),
        )
        with self.assertRaisesRegex(ValueError, "platlib"):
            _record_roots([
                "demo-1.0.data/platlib/demo/__init__.py",
            ])
        parser = self.source[
            self.source.index(
                "private static Set<String> "
                "readDistributionImportRoots("):
            self.source.index(
                "private static boolean isPythonModuleRecord(")
        ]
        self.assertIn('new File(distInfo, "RECORD")', parser)
        self.assertIn(
            "parseRecordImportRoots(recordBytes)",
            parser,
        )

    def test_selected_wheel_metadata_drives_transitive_resolution(self):
        resolver = self.source[
            self.source.index(
                "private ResolutionState solve("):
            self.source.index(
                "private String getPreinstalledVersion(")
        ]
        self.assertIn(
            "resolveSelectedWheelMetadata(",
            resolver,
        )
        self.assertIn(
            "selectedWheel.requiresDist",
            resolver,
        )
        self.assertNotIn(
            "getTransitiveRequirements(",
            self.source,
        )
        self.assertNotIn(
            'getAsJsonArray("requires_dist")',
            self.source,
        )
        selected = self.source[
            self.source.index(
                "private PureWheelInfo "
                "resolveSelectedWheelMetadata("):
            self.source.index(
                "public WheelCandidate resolveWheel(")
        ]
        self.assertIn(
            "verifySha256(",
            selected,
        )
        self.assertLess(
            selected.index("verifySha256("),
            selected.index("inspectPureWheel("),
        )
        self.assertIn(
            "metadataRequiresDist(metadata)",
            self.source,
        )

    def test_stdlib_and_preinstalled_outside_managed_root_survive(self):
        roots = {"/site/demo-1": {"demo"}}
        modules = {
            "json": {"file": "/stdlib/json/__init__.py"},
            "PIL": {"file": "/apk/site-packages/PIL/__init__.py"},
            "demo": {"file": "/site/demo-1/demo/__init__.py"},
        }
        self.assertEqual(["demo"], _model_eviction(modules, roots))
        self.assertIn(
            "PREINSTALLED_PACKAGES.contains(\n"
            "                            root.distribution)",
            self.source,
        )

    def test_mixed_namespace_root_survives_but_retired_child_does_not(self):
        roots = {"/site/part-a": {"shared_ns"}}
        modules = {
            "shared_ns": {
                "path": (
                    "/site/part-a/shared_ns",
                    "/site/part-b/shared_ns",
                )
            },
            "shared_ns.a": {
                "file": "/site/part-a/shared_ns/a.py",
            },
            "shared_ns.b": {
                "file": "/site/part-b/shared_ns/b.py",
            },
        }
        self.assertEqual(
            ["shared_ns.a"],
            _model_eviction(modules, roots),
        )

    def test_embedded_helper_rewrites_mixed_namespace_path_atomically(self):
        helper = _java_string_constant(
            self.source,
            "private static final String "
            "PYTHON_RUNTIME_TRANSITION_HELPER",
            "private static final Set<String> "
            "PREINSTALLED_PACKAGES",
        )
        namespace = {}
        exec(helper, namespace)
        package_name = "_nimarko_mixed_namespace"
        child_name = f"{package_name}.retired"
        previous_package = os.sys.modules.get(package_name, _MISSING)
        previous_child = os.sys.modules.get(child_name, _MISSING)
        before_path = list(os.sys.path)
        try:
            with tempfile.TemporaryDirectory() as tmp:
                root = pathlib.Path(tmp)
                managed_root = (root / "site").resolve()
                old_root = managed_root / "mixed-1"
                new_root = managed_root / "mixed-2"
                outside_root = (root / "outside").resolve()
                for package_root in (old_root, new_root, outside_root):
                    (package_root / package_name).mkdir(parents=True)

                package = types.ModuleType(package_name)
                old_package_path = str(old_root / package_name)
                outside_package_path = str(outside_root / package_name)
                package.__path__ = [
                    old_package_path,
                    outside_package_path,
                ]
                package.__spec__ = types.SimpleNamespace(
                    origin=None,
                    submodule_search_locations=package.__path__,
                )
                child = types.ModuleType(child_name)
                child.__file__ = str(
                    old_root / package_name / "retired.py"
                )
                package.retired = child
                os.sys.modules[package_name] = package
                os.sys.modules[child_name] = child
                os.sys.path.insert(0, str(old_root))

                namespace["transition"](json.dumps({
                    "managed_root": str(managed_root),
                    "expected_paths": [str(old_root)],
                    "desired_paths": [str(new_root)],
                    "modules": [{
                        "name": child_name,
                        "identity": id(child),
                    }],
                    "eviction_roots": [{
                        "path": str(old_root),
                        "import_roots": [package_name],
                    }],
                    "namespace_patches": [{
                        "name": package_name,
                        "identity": id(package),
                        "expected_paths": [
                            old_package_path,
                            outside_package_path,
                        ],
                    }],
                    "desired_roots": [{
                        "path": str(new_root),
                        "import_roots": [package_name],
                    }],
                }))
                expected = [
                    str(new_root / package_name),
                    outside_package_path,
                ]
                self.assertIs(os.sys.modules[package_name], package)
                self.assertNotIn(child_name, os.sys.modules)
                self.assertEqual(expected, package.__path__)
                self.assertIs(
                    package.__spec__.submodule_search_locations,
                    package.__path__,
                )
        finally:
            os.sys.path[:] = before_path
            if previous_package is _MISSING:
                os.sys.modules.pop(package_name, None)
            else:
                os.sys.modules[package_name] = previous_package
            if previous_child is _MISSING:
                os.sys.modules.pop(child_name, None)
            else:
                os.sys.modules[child_name] = previous_child

    def test_mixed_namespace_path_rolls_back_on_patch_failure(self):
        helper = _java_string_constant(
            self.source,
            "private static final String "
            "PYTHON_RUNTIME_TRANSITION_HELPER",
            "private static final Set<String> "
            "PREINSTALLED_PACKAGES",
        )
        namespace = {}
        exec(helper, namespace)

        class FailingSpec:
            origin = None

            def __init__(self, value):
                self.value = value
                self.fail_once = True

            @property
            def submodule_search_locations(self):
                return self.value

            @submodule_search_locations.setter
            def submodule_search_locations(self, value):
                if self.fail_once:
                    self.fail_once = False
                    raise RuntimeError("injected namespace failure")
                self.value = value

        package_name = "_nimarko_mixed_rollback"
        previous = os.sys.modules.get(package_name, _MISSING)
        before_path = list(os.sys.path)
        try:
            with tempfile.TemporaryDirectory() as tmp:
                root = pathlib.Path(tmp)
                managed_root = (root / "site").resolve()
                old_root = managed_root / "mixed-1"
                new_root = managed_root / "mixed-2"
                outside_root = (root / "outside").resolve()
                for package_root in (old_root, new_root, outside_root):
                    (package_root / package_name).mkdir(parents=True)
                old_paths = [
                    str(old_root / package_name),
                    str(outside_root / package_name),
                ]
                package = types.ModuleType(package_name)
                package.__path__ = old_paths
                package.__spec__ = FailingSpec(old_paths)
                os.sys.modules[package_name] = package
                os.sys.path.insert(0, str(old_root))
                expected_runtime_path = list(os.sys.path)
                with self.assertRaisesRegex(
                        RuntimeError, "injected namespace"):
                    namespace["transition"](json.dumps({
                        "managed_root": str(managed_root),
                        "expected_paths": [str(old_root)],
                        "desired_paths": [str(new_root)],
                        "modules": [],
                        "eviction_roots": [{
                            "path": str(old_root),
                            "import_roots": [package_name],
                        }],
                        "namespace_patches": [{
                            "name": package_name,
                            "identity": id(package),
                            "expected_paths": old_paths,
                        }],
                        "desired_roots": [{
                            "path": str(new_root),
                            "import_roots": [package_name],
                        }],
                    }))
                self.assertEqual(
                    expected_runtime_path, os.sys.path)
                self.assertIs(package.__path__, old_paths)
                self.assertIs(
                    package.__spec__.submodule_search_locations,
                    old_paths,
                )
        finally:
            os.sys.path[:] = before_path
            if previous is _MISSING:
                os.sys.modules.pop(package_name, None)
            else:
                os.sys.modules[package_name] = previous

    def test_cross_distribution_import_root_collision_fails_before_journal(self):
        roots = {}
        for distribution, import_roots in (
                ("dist-a", {"common"}),
                ("dist-b", {"common"})):
            for import_root in import_roots:
                previous = roots.setdefault(import_root, distribution)
                if previous != distribution:
                    collision = (import_root, previous, distribution)
                    break
        self.assertEqual(
            ("common", "dist-a", "dist-b"),
            collision,
        )
        install = self.source[
            self.source.index(
                "public synchronized List<String> "
                "installDependencies("):
            self.source.index(
                "private static final class "
                "InstallCancelledException")
        ]
        self.assertLess(
            install.index(
                "validateSelectedImportRootCollisions("),
            install.index("beginLocalArtifactTransaction("),
        )
        self.assertIn(
            "cross-distribution namespaces",
            self.source,
        )

    def test_restart_required_is_sticky_and_new_mutations_fail_fast(self):
        self.assertIn(
            "private static final AtomicBoolean "
            "PIP_RESTART_REQUIRED",
            self.source,
        )
        self.assertIn(
            "public boolean requiresProcessRestart()",
            self.source,
        )
        restart = self.source[
            self.source.index(
                "private static RestartRequiredRuntimeException "
                "restartRequired("):
            self.source.index(
                "private boolean hasOtherDistributionOwner(")
        ]
        self.assertGreaterEqual(
            restart.count("PIP_RESTART_REQUIRED.set(true)"),
            2,
        )
        install = self.source[
            self.source.index(
                "public synchronized List<String> "
                "installDependencies("):
            self.source.index(
                "private static final class "
                "InstallCancelledException")
        ]
        self.assertLess(
            install.index("enterPipMutation();"),
            install.index("loadRegistryOrThrow();"),
        )
        self.assertIn(
            "PIP_MUTATION_DEPTH",
            self.source,
        )

    def test_unproved_metadata_fails_closed(self):
        with self.assertRaisesRegex(RuntimeError, "restart required"):
            _model_eviction(
                {"mystery": {"file": "/site/demo-1/mystery.py"}},
                {"/site/demo-1": {"demo"}},
            )
        self.assertIn(
            "Dependency metadata has no provable import roots",
            self.source,
        )
        self.assertIn(
            "Restart required: ",
            self.source,
        )

    def test_shared_version_guard_precedes_artifact_publication(self):
        install = self.source[
            self.source.index(
                "public synchronized List<String> "
                "installDependencies("):
            self.source.index(
                "private static final class "
                "InstallCancelledException")
        ]
        guard = install.index(
            "validateSharedDistributionTransitions(")
        journal = install.index(
            "beginLocalArtifactTransaction(")
        publish = install.index(
            "replacement.commit()")
        self.assertLess(guard, journal)
        self.assertLess(guard, publish)
        self.assertIn(
            "while another plugin owns it",
            self.source,
        )

    def test_install_and_uninstall_prepare_before_registry_commit(self):
        install = self.source[
            self.source.index(
                "public synchronized List<String> "
                "installDependencies("):
            self.source.index(
                "private static final class "
                "InstallCancelledException")
        ]
        self.assertLess(
            install.index("prepareManagedModuleEviction("),
            install.index("registry.put(pluginId, staged)"),
        )
        uninstall = self.source[
            self.source.index(
                "public synchronized boolean uninstallDependencies("):
            self.source.index(
                "public synchronized DependencySnapshot snapshotState(")
        ]
        self.assertLess(
            uninstall.index("prepareManagedModuleEviction("),
            uninstall.index("registry.remove(pluginId)"),
        )

    def test_atomic_transition_is_parent_aware_and_rolls_back_fully(self):
        package = types.ModuleType("demo")
        child = types.ModuleType("demo.child")
        leaf = types.ModuleType("demo.child.leaf")
        package.child = child
        child.leaf = leaf
        paths = ["/site/demo-1"]
        modules = {
            "demo": package,
            "demo.child": child,
            "demo.child.leaf": leaf,
        }
        removals = [
            ("demo.child.leaf", id(leaf)),
            ("demo.child", id(child)),
            ("demo", id(package)),
        ]
        _atomic_transition(
            paths, modules, ["/site/demo-1"],
            ["/site/demo-2"], removals,
        )
        self.assertEqual(["/site/demo-2"], paths)
        self.assertEqual({}, modules)
        self.assertNotIn("child", package.__dict__)
        self.assertNotIn("leaf", child.__dict__)

        package.child = child
        child.leaf = leaf
        paths[:] = ["/site/demo-1"]
        modules.update({
            "demo": package,
            "demo.child": child,
            "demo.child.leaf": leaf,
        })
        with self.assertRaisesRegex(RuntimeError, "injected"):
            _atomic_transition(
                paths, modules, ["/site/demo-1"],
                ["/site/demo-2"], removals, fail_after=0,
            )
        self.assertEqual(["/site/demo-1"], paths)
        self.assertIs(modules["demo.child.leaf"], leaf)
        self.assertIs(package.child, child)
        self.assertIs(child.leaf, leaf)

    def test_parent_attr_is_cleared_only_on_identity_match(self):
        package = types.ModuleType("demo")
        retired = types.ModuleType("demo.child")
        replacement = object()
        package.child = replacement
        modules = {"demo": package, "demo.child": retired}
        _atomic_transition(
            ["/site/v1"], modules, ["/site/v1"], ["/site/v2"],
            [("demo.child", id(retired))],
        )
        self.assertIs(package.child, replacement)

    def test_identity_change_fails_before_any_path_or_module_mutation(self):
        module = types.ModuleType("demo")
        paths = ["/site/v1"]
        modules = {"demo": module}
        with self.assertRaisesRegex(RuntimeError, "identity"):
            _atomic_transition(
                paths, modules, ["/site/v1"], ["/site/v2"],
                [("demo", id(object()))],
            )
        self.assertEqual(["/site/v1"], paths)
        self.assertIs(modules["demo"], module)

    def test_new_retiring_module_fails_before_transition(self):
        package = types.ModuleType("demo")
        late = types.ModuleType("demo.late")
        paths = ["/site/v1"]
        modules = {"demo": package, "demo.late": late}
        with self.assertRaisesRegex(RuntimeError, "eviction set"):
            _atomic_transition(
                paths, modules, ["/site/v1"], ["/site/v2"],
                [("demo", id(package))],
                discovered=[
                    ("demo", id(package)),
                    ("demo.late", id(late)),
                ],
            )
        self.assertEqual(["/site/v1"], paths)
        self.assertIs(modules["demo"], package)
        self.assertIs(modules["demo.late"], late)

    def test_java_transition_is_one_import_locked_critical_section(self):
        helper = self.source[
            self.source.index(
                "private static final String "
                "PYTHON_RUNTIME_TRANSITION_HELPER"):
            self.source.index(
                "private static final Set<String> "
                "PREINSTALLED_PACKAGES")
        ]
        self.assertIn("_pip_imp.acquire_lock()", helper)
        self.assertIn("observed != expected", helper)
        self.assertIn(
            "module identity changed before transition", helper)
        self.assertLess(
            helper.index("observed != expected"),
            helper.index("sys.path[:] = desired"),
        )
        self.assertIn(
            "vars(parent).get(child, sentinel) is module",
            helper,
        )
        self.assertIn("managed eviction set changed", helper)
        self.assertIn("set(selected) != set(planned)", helper)
        self.assertIn(
            "sys.modules changed during identity validation",
            helper,
        )
        self.assertIn("sys.path[:] = before_path", helper)
        self.assertIn("sys.modules.clear()", helper)
        self.assertIn("sys.modules.update(module_snapshot)", helper)
        self.assertIn(
            "os.path.join(path, *name.split('.'))",
            helper,
        )
        transition = self.source[
            self.source.index(
                "private void executeManagedRuntimeTransition("):
            self.source.index(
                "private static int moduleDepth(")
        ]
        self.assertIn(
            'namespace.callAttr(\n'
            '                            "__getitem__", "transition")',
            transition,
        )
        self.assertIn(
            '"callable", helper).toBoolean()',
            transition,
        )
        self.assertNotIn(
            'namespace.get("transition")',
            transition,
        )
        self.assertIn(
            'PYTHON_RUNTIME_TRANSITION_HELPER,\n'
            '                    namespace,\n'
            '                    namespace)',
            transition,
        )
        self.assertIn("helper.call(gson.toJson(payload))", transition)
        self.assertIn(
            'payload.put("eviction_roots", evictionRoots)',
            transition,
        )

    def test_best_effort_cleanup_cannot_crash_plugins_queue(self):
        cleanup = self.source[
            self.source.index(
                "public synchronized void cleanup()"):
            self.source.index(
                "private void cleanupInternal()")
        ]
        strict_start = cleanup.index(
            "public synchronized boolean cleanupAndReport()")
        best_effort = cleanup[:strict_start]
        strict = cleanup[strict_start:]
        self.assertIn(
            "catch (RestartRequiredRuntimeException failure)",
            best_effort,
        )
        self.assertIn("cleanupAndReport();", best_effort)
        self.assertIn(
            "catch (RestartRequiredRuntimeException failure)",
            strict,
        )
        self.assertIn("throw failure;", strict)

    def test_runtime_bootstrap_is_explicit_and_not_in_singleton_constructor(
            self):
        bootstrap = self.source[
            self.source.index(
                "public synchronized void "
                "bootstrapRuntimeForPluginStartup()"):
            self.source.index(
                "public synchronized void saveRegistry()")
        ]
        self.assertIn("enterPipMutation();", bootstrap)
        self.assertIn("loadRegistryStrict();", bootstrap)
        self.assertIn("exitPipMutation();", bootstrap)
        constructor = self.source[
            self.source.index("private PipController()"):
            self.source.index(
                "/** Parses METADATA", self.source.index(
                    "private PipController()"))
        ]
        self.assertNotIn("loadRegistry", constructor)

    def test_embedded_transition_helper_executes_on_host_python(self):
        helper = _java_string_constant(
            self.source,
            "private static final String "
            "PYTHON_RUNTIME_TRANSITION_HELPER",
            "private static final Set<String> "
            "PREINSTALLED_PACKAGES",
        )
        compile(helper, "<pip-transition-helper>", "exec")
        namespace = {}
        exec(helper, namespace)
        with tempfile.TemporaryDirectory() as tmp:
            managed_root = pathlib.Path(tmp).resolve()
            before_path = list(os.sys.path)
            before_modules = {
                name: id(module)
                for name, module in os.sys.modules.items()
            }
            namespace["transition"](json.dumps({
                "managed_root": str(managed_root),
                "expected_paths": [],
                "desired_paths": [],
                "modules": [],
                "eviction_roots": [],
                "namespace_patches": [],
                "desired_roots": [],
            }))
            self.assertEqual(before_path, os.sys.path)
            for name, identity in before_modules.items():
                self.assertIn(name, os.sys.modules)
                self.assertEqual(identity, id(os.sys.modules[name]))

    def test_embedded_helper_evicts_real_modules_deepest_first(self):
        helper = _java_string_constant(
            self.source,
            "private static final String "
            "PYTHON_RUNTIME_TRANSITION_HELPER",
            "private static final Set<String> "
            "PREINSTALLED_PACKAGES",
        )
        namespace = {}
        exec(helper, namespace)
        package_name = "_nimarko_pip_probe"
        child_name = f"{package_name}.child"
        previous_package = os.sys.modules.get(package_name, _MISSING)
        previous_child = os.sys.modules.get(child_name, _MISSING)
        before_path = list(os.sys.path)
        try:
            with tempfile.TemporaryDirectory() as tmp:
                managed_root = pathlib.Path(tmp, "site").resolve()
                old_root = managed_root / "probe-1"
                new_root = managed_root / "probe-2"
                old_root.mkdir(parents=True)
                new_root.mkdir()
                package = types.ModuleType(package_name)
                child = types.ModuleType(child_name)
                package.__file__ = str(
                    old_root / package_name / "__init__.py"
                )
                package.__path__ = [
                    str(old_root / package_name)
                ]
                child.__file__ = str(
                    old_root / package_name / "child.py"
                )
                package.child = child
                os.sys.modules[package_name] = package
                os.sys.modules[child_name] = child
                os.sys.path.insert(0, str(old_root))
                namespace["transition"](json.dumps({
                    "managed_root": str(managed_root),
                    "expected_paths": [str(old_root)],
                    "desired_paths": [str(new_root)],
                    "modules": [
                        {"name": child_name, "identity": id(child)},
                        {"name": package_name, "identity": id(package)},
                    ],
                    "eviction_roots": [{
                        "path": str(old_root),
                        "import_roots": [package_name],
                    }],
                    "namespace_patches": [],
                    "desired_roots": [{
                        "path": str(new_root),
                        "import_roots": [package_name],
                    }],
                }))
                self.assertNotIn(package_name, os.sys.modules)
                self.assertNotIn(child_name, os.sys.modules)
                self.assertNotIn("child", package.__dict__)
                self.assertEqual(str(new_root), os.sys.path[0])
        finally:
            os.sys.path[:] = before_path
            if previous_package is _MISSING:
                os.sys.modules.pop(package_name, None)
            else:
                os.sys.modules[package_name] = previous_package
            if previous_child is _MISSING:
                os.sys.modules.pop(child_name, None)
            else:
                os.sys.modules[child_name] = previous_child

    def test_versioned_registry_bootstraps_exact_root_version_and_order(self):
        roots = [
            {
                "distribution": "alpha",
                "version": "2.0",
                "root": "site/alpha-2.0",
                "wheel": "wheels/alpha-2.0.whl",
                "sha256": "a" * 64,
                "importRoots": ["alpha"],
            },
            {
                "distribution": "beta",
                "version": "1.4",
                "root": "site/beta-1.4",
                "wheel": "wheels/beta-1.4.whl",
                "sha256": "b" * 64,
                "importRoots": ["beta"],
            },
        ]
        registry = {
            "schema": 2,
            "ownership": {
                "one": {"alpha": ["alpha"], "beta": ["beta"]}
            },
            "roots": roots,
            "checksum": None,
        }
        registry["checksum"] = _registry_checksum(registry)
        metadata = {
            "site/alpha-2.0": ("alpha", "2.0", "a" * 64, ("alpha",)),
            "site/beta-1.4": ("beta", "1.4", "b" * 64, ("beta",)),
        }
        self.assertEqual(
            ["site/alpha-2.0", "site/beta-1.4"],
            _bootstrap_paths(registry, metadata),
        )
        bad = copy.deepcopy(registry)
        bad["roots"][0]["version"] = "3.0"
        bad["checksum"] = _registry_checksum(bad)
        with self.assertRaisesRegex(ValueError, "identity"):
            _bootstrap_paths(bad, metadata)
        self.assertIn("private static final int REGISTRY_SCHEMA = 2", self.source)
        self.assertIn("bootstrapManagedRuntimeStrict();", self.source)

    def test_legacy_registry_migration_is_deterministic_and_atomic(self):
        selected = _migrate_legacy(
            {"one": {"demo": {"demo>=1"}}},
            [
                {"distribution": "demo", "version": "1.0",
                 "prerelease": False, "root": "site/demo-1.0"},
                {"distribution": "demo", "version": "2.0",
                 "prerelease": False, "root": "site/demo-2.0"},
                {"distribution": "demo", "version": "3.0",
                 "prerelease": True, "root": "site/demo-3.0"},
            ],
        )
        self.assertEqual("2.0", selected[0]["version"])
        load = self.source[
            self.source.index("private void loadRegistryStrict("):
            self.source.index("private void loadRegistryOrThrow(")
        ]
        self.assertLess(
            load.index(
                "writeRegistryStateStrict(validated, validatedRoots)"),
            load.index("registry.clear()"),
        )
        self.assertIn("migrateLegacyRegistryRoots(validated)", load)

    def test_legacy_registry_quarantines_only_unproven_dependency(self):
        migration = self.source[
            self.source.index(
                "private List<RegistryRootDisk> migrateLegacyRegistryRoots("):
            self.source.index(
                "private RegistryRootDisk registryRootForManaged(")
        ]
        self.assertIn(
            "discardUnprovenLegacyDistribution(", migration)
        self.assertNotIn(
            "legacy registry cannot prove an installed", migration)
        self.assertIn(
            "packages.remove(distribution)", migration)
        self.assertIn(
            "it will be resolved again when required", migration)
        self.assertIn(
            "ignored stale legacy sys.path", migration)

    def test_registry_post_rename_fsync_is_reread_and_compensated(self):
        writer = self.source[
            self.source.index(
                "private void writeRegistryPayloadStrict("):
            self.source.index(
                "private static boolean fileContentsEqual(")
        ]
        rename = writer.index("android.system.Os.rename(")
        fsync = writer.index("syncDirectoryStrict(parent)", rename)
        reread = writer.index("fileContentsEqual(target, payload)", fsync)
        self.assertLess(rename, fsync)
        self.assertLess(fsync, reread)
        self.assertIn("for (int attempt = 0; attempt < 2; attempt++)", writer)
        self.assertIn("compensating atomic write", writer)

    def test_registry_checksum_model_matches_gson_field_contract(self):
        registry = {
            "schema": 2,
            "ownership": {
                "alpha": {"demo": ["demo>=1"]},
            },
            "roots": [{
                "distribution": "demo",
                "version": "1.0",
                "root": "site/demo-1.0",
                "wheel": "wheels/demo-1.0.whl",
                "sha256": "a" * 64,
                "importRoots": ["demo"],
            }],
            "checksum": None,
        }
        expected_payload = (
            '{"schema":2,"ownership":{"alpha":{"demo":'
            '["demo\\u003e\\u003d1"]}},"roots":'
            '[{"distribution":"demo",'
            '"version":"1.0","root":"site/demo-1.0",'
            '"wheel":"wheels/demo-1.0.whl","sha256":"'
            + "a" * 64
            + '","importRoots":["demo"]}]}'
        ).encode()
        self.assertEqual(
            hashlib.sha256(expected_payload).hexdigest(),
            _registry_checksum(registry),
        )
        self.assertIn(
            "registryChecksumForTest(",
            self.source,
        )

    def test_snapshot_read_is_complete_and_fail_closed(self):
        read = self.source[
            self.source.index(
                "public synchronized DependencySnapshot "
                "readDependencySnapshot("):
            self.source.index(
                "public synchronized void "
                "beginDeferredArtifactTransaction(")
        ]
        self.assertIn("DEPENDENCY_SNAPSHOT_SCHEMA", read)
        self.assertIn("disk.managedRoots", read)
        self.assertIn(
            "unmanaged import path", read)
        self.assertIn(
            "duplicate import path", read)
        self.assertIn(
            "root order", read)
        self.assertIn(
            "validateRegistryRootRecords(\n"
            "                    disk.managedRoots, true)",
            read,
        )
        self.assertIn(
            "validateRegistryCoverage(\n"
            "                    restoredOwnership, roots)",
            read,
        )
        self.assertNotIn(
            "if (isManagedImportPath(path)", read)

    def test_rollback_discovers_cached_candidate_without_journal_entry(self):
        capture = self.source[
            self.source.index(
                "private ManagedModuleEvictionPlan "
                "captureRollbackModuleEviction("):
            self.source.index(
                "public synchronized List<String> "
                "snapshotRequirements(")
        ]
        self.assertIn("snapshotManagedRuntimeStrict(", capture)
        self.assertIn("previousPaths.contains(", capture)
        self.assertIn("plan.add(root);", capture)
        self.assertNotIn("hasOtherDistributionOwner(", capture)

    def test_restore_commit_survives_retryable_cleanup_failure(self):
        restore = self.source[
            self.source.index(
                "public synchronized boolean restoreState("):
            self.source.index(
                "private static Map<String, Set<String>> "
                "deepCopyOwnership(")
        ]
        save = restore.index("saveRegistryStrict();")
        transition = restore.index(
            "executeManagedRuntimeTransition(", save)
        cleanup = restore.index("cleanupInternal();", transition)
        deferred = restore.index(
            "inactive artifact cleanup", cleanup)
        self.assertLess(save, transition)
        self.assertLess(transition, cleanup)
        self.assertLess(cleanup, deferred)
        self.assertNotIn("restoredPrevious", restore)
        self.assertNotIn(
            "previousImportPaths", restore)

    def test_cleanup_removes_inactive_versions_but_preserves_journal(self):
        active = {
            "site/demo-2.0",
            "wheels/demo-2.0.whl",
        }
        journal = [{
            "target": "site/demo-3.0",
            "staged": "site/.demo-3.0.stage",
            "backup": "site/.demo-3.0.backup",
        }]
        artifacts = active | {
            "site/demo-1.0",
            "wheels/demo-1.0.whl",
            "site/demo-3.0",
            "site/.demo-3.0.stage",
            "site/.demo-3.0.backup",
            "site/demo-rollback",
            "wheels/demo-rollback.whl",
        }
        self.assertEqual(
            {"site/demo-1.0", "wheels/demo-1.0.whl"},
            _cleanup_exact(
                active,
                journal,
                artifacts,
                {
                    "site/demo-rollback",
                    "wheels/demo-rollback.whl",
                },
            ),
        )
        cleanup = self.source[
            self.source.index(
                "private void removeOrphanedDirectories("):
            self.source.index(
                "private static boolean isWheelReferenced(")
        ]
        self.assertIn("activeRoots", cleanup)
        self.assertIn("activeWheels", cleanup)
        self.assertIn("protectedRecovery", cleanup)
        recovery = self.source[
            self.source.index(
                "private Set<String> "
                "collectDeferredArtifactRecoveryPaths("):
            self.source.index(
                "private void "
                "recoverInterruptedArtifactTransactionsIn(")
        ]
        self.assertIn("journal.previousRuntimeRoots", recovery)

    def test_real_purelib_wheel_is_relocated(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            wheel = root / "demo-1.0-py3-none-any.whl"
            destination = root / "installed"
            _make_wheel(wheel)
            outputs = _validate_and_extract_pure_wheel(
                wheel, destination)
            module = destination / "demo/__init__.py"
            self.assertIn(module, outputs)
            self.assertEqual("VALUE = 1\n", module.read_text())
            self.assertFalse(
                (destination / "demo-1.0.data").exists())
        self.assertIn("extractPureWheel(", self.source)
        self.assertIn('"purelib/"', self.source)

    def test_wheel_tags_and_metadata_reject_native_or_platlib(self):
        self.assertTrue(_python_tag_compatible("py3-none-any"))
        self.assertTrue(_python_tag_compatible("py311-none-any"))
        self.assertTrue(_python_tag_compatible("cp311-none-any"))
        self.assertTrue(_python_tag_compatible("py2.py3-none-any"))
        self.assertFalse(_python_tag_compatible("py310-none-any"))
        self.assertFalse(_python_tag_compatible("cp311-abi3-any"))
        self.assertFalse(_python_tag_compatible("cp311-none-linux_aarch64"))
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            for payload in ("platlib", "native"):
                wheel = root / f"{payload}.whl"
                _make_wheel(wheel, payload=payload)
                with self.assertRaisesRegex(
                        ValueError, "native or platlib"):
                    _validate_and_extract_pure_wheel(
                        wheel, root / f"out-{payload}")
            impure = root / "impure.whl"
            _make_wheel(impure, root_is_pure=False)
            with self.assertRaisesRegex(ValueError, "WHEEL"):
                _validate_and_extract_pure_wheel(
                    impure, root / "out-impure")
            mixed = root / "mixed.whl"
            _make_wheel(
                mixed,
                tag="py3-none-any\nTag: cp311-abi3-any",
            )
            with self.assertRaisesRegex(ValueError, "WHEEL"):
                _validate_and_extract_pure_wheel(
                    mixed, root / "out-mixed")
        self.assertIn("Root-Is-Purelib:", self.source)
        self.assertIn("Native/platform WHEEL tag rejected", self.source)
        self.assertIn("Native/platlib wheel entry rejected", self.source)

    def test_core_metadata_and_pep440_regressions_have_runtime_tests(self):
        parser = self.source[
            self.source.index(
                "public static ParsedRequirement "
                "parseRequirement("):
            self.source.index(
                "private static String parseRequirementName(")
        ]
        self.assertIn(
            "unwrapCoreMetadataSpecifiers(",
            parser,
        )
        compare = self.source[
            self.source.index(
                "public static boolean compareSpec("):
            self.source.index(
                "public static boolean matchesSpec(")
        ]
        self.assertIn(
            "target.indexOf('+')",
            compare,
        )
        self.assertIn(
            '"!=".equals(op) ? !equal : equal',
            compare,
        )

    def test_marker_environment_uses_runtime_micro_and_machine(self):
        self.assertIn(
            'case "python_full_version":',
            self.source,
        )
        self.assertIn(
            ".getPythonFullVersion()",
            self.source,
        )
        self.assertIn(
            ".getPlatformMachine()",
            self.source,
        )
        self.assertIn(
            'python.getModule("platform")',
            self.source,
        )
        self.assertIn(
            "android.os.Build.SUPPORTED_ABIS",
            self.source,
        )
        self.assertNotIn(
            'case "platform_machine": return "aarch64"',
            self.source,
        )

    def test_network_limits_and_strict_startup_sweep_are_present(self):
        http = self.source[
            self.source.index(
                "private static String httpGet("):
            self.source.index(
                "// ---------------------------------------------------------------------\n"
                "    // Resolver")
        ]
        self.assertIn(
            "MAX_PYPI_JSON_BYTES",
            http,
        )
        self.assertIn(
            "MAX_WHEEL_DOWNLOAD_BYTES",
            http,
        )
        self.assertIn(
            "getContentLengthLong()",
            http,
        )
        sweep = self.source[
            self.source.index(
                "private void sweepStartupTemporaryFilesStrict("):
            self.source.index(
                "private void "
                "recoverInterruptedArtifactTransactions(")
        ]
        for pattern in (
                "REGISTRY_STAGE_PATTERN",
                "ARTIFACT_JOURNAL_STAGE_PATTERN",
                "RESOLVER_WHEEL_PATTERN",
                "ARTIFACT_DOWNLOAD_PART_PATTERN",
                "DEPENDENCY_SNAPSHOT_STAGE_PATTERN"):
            self.assertIn(pattern, sweep)
        self.assertNotIn(
            'name.endsWith(".part")',
            sweep,
        )

if __name__ == "__main__":
    unittest.main()
