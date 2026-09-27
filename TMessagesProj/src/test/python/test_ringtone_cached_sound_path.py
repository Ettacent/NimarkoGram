"""Run extracted ringtone resolver/download checks on the JVM with real files.

Android scheduling and FileLoader are stubbed; no app build or network access.
"""
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_gif_loop_transition import method

JAVA = Path(__file__).resolve().parents[2] / "main/java/org/telegram"


class RingtoneCachedSoundPathTests(unittest.TestCase):
    def test_original_deletion_uses_document_cache_without_changing_selection(self):
        source = (JAVA / "messenger/ringtone/RingtoneDataStore.java").read_text()
        production = "\n".join(method(source, marker) for marker in (
            "public String getSoundPath(", "public void checkRingtoneSoundsLoaded("))
        harness = r'''
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;

public class RingtonePathHarness {
    boolean loaded;
    int currentAccount = 2, prefLoads;
    final ArrayList<CachedTone> userRingtones = new ArrayList<>();
    void loadFromPrefs(boolean notify) { prefLoads++; }
    static class TextUtils {
        static boolean isEmpty(String s) { return s == null || s.isEmpty(); }
    }
    static class TLRPC {
        static class Document { long id; }
    }
    static class CachedTone {
        String localUri;
        TLRPC.Document document;
    }
    static class AndroidUtilities {
        static void runOnUIThread(Runnable r) { r.run(); }
    }
    static class Utilities {
        static final Queue globalQueue = new Queue();
        static class Queue { void postRunnable(Runnable r) { r.run(); } }
    }
    static class FileLoader {
        static final int PRIORITY_LOW = 0;
        static final FileLoader instance = new FileLoader();
        File cache;
        int downloads;
        static FileLoader getInstance(int account) {
            check(account == 2, "account routing unchanged");
            return instance;
        }
        File getPathToAttach(TLRPC.Document d) { return cache; }
        void loadFile(TLRPC.Document d, TLRPC.Document parent, int priority, int type) {
            check(d.id == 42 && d == parent, "download selected document");
            downloads++;
        }
    }
    static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
    }
    /* PRODUCTION */
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        File original = new File(root, "original #1?.ogg");
        File cache = new File(root, "document.ogg");
        Files.write(original.toPath(), new byte[]{1});
        FileLoader loader = FileLoader.instance;
        loader.cache = cache;
        RingtonePathHarness store = new RingtonePathHarness();
        CachedTone uploading = new CachedTone();
        store.userRingtones.add(uploading); // No document yet: skip safely.
        CachedTone tone = new CachedTone();
        tone.localUri = original.toString();
        tone.document = new TLRPC.Document();
        tone.document.id = 42;
        store.userRingtones.add(tone);

        check(store.getSoundPath(42).equals(original.toString()), "existing original wins");
        check(store.loaded && store.prefLoads == 1, "lazy load retained");
        store.checkRingtoneSoundsLoaded();
        check(loader.downloads == 0, "no download for existing original");
        Files.delete(original.toPath());
        check(store.getSoundPath(42).equals(cache.toString()), "missing original uses cache target");
        store.checkRingtoneSoundsLoaded();
        check(loader.downloads == 1, "missing original/cache schedules document download");
        Files.write(cache.toPath(), new byte[]{2}); // Simulated download completion.
        check(store.getSoundPath(42).equals(cache.toString()), "downloaded cache now used");
        store.checkRingtoneSoundsLoaded();
        check(loader.downloads == 1, "existing cache avoids another download");
        check(tone.document.id == 42 && tone.localUri.equals(original.toString()),
                "resolution does not reset selection or rewrite source");

        Files.createDirectory(original.toPath());
        check(store.getSoundPath(42).equals(cache.toString()), "directory is not an original sound");
        store.checkRingtoneSoundsLoaded();
        check(loader.downloads == 1, "directory original uses existing cache");
        Files.delete(cache.toPath());
        Files.createDirectory(cache.toPath());
        store.checkRingtoneSoundsLoaded();
        check(loader.downloads == 2, "directory cache is not a loaded sound");
        for (String path : new String[]{null, ""}) {
            tone.localUri = path;
            check(store.getSoundPath(42).equals(cache.toString()), "unset original uses cache target");
        }
        check(store.getSoundPath(999).equals("NoSound"), "unknown ID behavior unchanged");
        check(tone.document.id == 42, "selected document survives all fallbacks");
    }
}
'''.replace("/* PRODUCTION */", production)
        with tempfile.TemporaryDirectory(prefix="ringtone-path-") as folder:
            path = Path(folder) / "RingtonePathHarness.java"
            path.write_text(harness)
            for command in (
                ["javac", str(path)],
                ["java", "-cp", folder, "RingtonePathHarness", folder],
            ):
                result = subprocess.run(command, capture_output=True, text=True, timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
