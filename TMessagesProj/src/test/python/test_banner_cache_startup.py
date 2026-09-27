"""Extracted production startup scheduling and network gate; no Android IO mock claims."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_gif_loop_transition import method

SOURCE = (Path(__file__).resolve().parents[2] /
          'main/java/app/nimarkogram/messenger/banners/NimarkoBannerController.java').read_text()


class BannerCacheStartupTests(unittest.TestCase):
    def test_start_schedules_even_when_identity_was_already_known(self):
        start = method(SOURCE, 'public void ensureStarted()')
        self.assertLess(start.index('myId();'), start.index('scheduleCacheRestore(currentScope, null);'))
        switch = method(SOURCE, 'private void switchScope(')
        self.assertIn('scheduleCacheRestore(next,', switch)

    def test_restore_once_before_network_with_early_identity_and_switches(self):
        production = '\n'.join(method(SOURCE, signature) for signature in (
            'private synchronized void scheduleCacheRestore(', 'private void loadBannerAsync('))
        source = r'''
import java.util.*;
public class CacheStartupTest {
 static class Scope {long uid=1;}
 static class CacheKey {Scope scope;CacheKey(Scope s){scope=s;}}
 static class Executor {Deque<Runnable> jobs=new ArrayDeque<>();
  void submit(Runnable r){jobs.add(r);}void drain(){while(!jobs.isEmpty())jobs.remove().run();}}
 static class AndroidUtilities {static void runOnUIThread(Runnable r){r.run();}}
 boolean started,failRead; Scope currentScope=new Scope(),cacheRestoreScheduledFor,pendingCacheRestore;
 Object indexPersistenceLock=new Object(),cacheLock=new Object();
 Map<Scope,Long> indexRevisions=new HashMap<>();Map<CacheKey,Long> failTimes=new HashMap<>();
 Map<CacheKey,Object> loading=new HashMap<>();Set<CacheKey> deferredBannerLoads=new HashSet<>();
 Set<Scope> restored=new HashSet<>(); Executor executor=new Executor();
 int reads,downloads,invalidations;static final long FAIL_CD=30000;
 long now(){return 100000;}static long getOr(Map<CacheKey,Long> m,CacheKey k){return m.getOrDefault(k,0L);}
 boolean isCurrentScope(Scope s){return s==currentScope;}
 void cleanupScopeFiles(Scope s){}void migrateLegacyScopeFiles(Scope s){}void readStatusCache(Scope s){}
 void readIndex(Scope s,long revision){reads++;if(failRead)throw new RuntimeException("IO");restored.add(s);}
 void reloadSettings(){}void invalidate(){invalidations++;}
 void syncBanner(CacheKey k,Object request){if(!restored.contains(k.scope))downloads++;loading.remove(k);}
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 /* PRODUCTION */
 public static void main(String[] args){
  CacheStartupTest c=new CacheStartupTest();Scope s=c.currentScope;
  c.scheduleCacheRestore(s,null);check(c.executor.jobs.isEmpty(),"identity before startup cannot do IO");
  c.started=true;c.scheduleCacheRestore(s,null);c.scheduleCacheRestore(s,null);
  check(c.executor.jobs.size()==1,"same preexisting identity restores once after startup");
  CacheKey k=new CacheKey(s);for(int i=0;i<120;i++)c.loadBannerAsync(k);
  check(c.executor.jobs.size()==1&&c.deferredBannerLoads.size()==1,"frames do not race disk with network");
  c.executor.drain();check(c.reads==1&&c.downloads==0,"cached file avoids download");
  check(c.pendingCacheRestore==null&&c.deferredBannerLoads.isEmpty(),"restore gate released");
  c.scheduleCacheRestore(s,null);check(c.executor.jobs.isEmpty(),"idempotent repeated open");
  c=new CacheStartupTest();c.started=true;s=c.currentScope;c.scheduleCacheRestore(s,null);
  c.loadBannerAsync(new CacheKey(s));Scope next=new Scope();c.currentScope=next;
  c.scheduleCacheRestore(next,null);c.loadBannerAsync(new CacheKey(next));c.executor.drain();
  check(c.reads==1&&c.restored.contains(next)&&!c.restored.contains(s),"obsolete activation cannot restore");
  check(c.downloads==0&&c.pendingCacheRestore==null,"new activation keeps its gate and cache");
  c=new CacheStartupTest();c.started=true;c.failRead=true;s=c.currentScope;
  c.scheduleCacheRestore(s,null);c.loadBannerAsync(new CacheKey(s));
  try{c.executor.jobs.remove().run();}catch(RuntimeException expected){}
  c.executor.drain();check(c.pendingCacheRestore==null&&c.downloads==1,"failed restore allows normal download");
 }
}
'''.replace('/* PRODUCTION */', production)
        with tempfile.TemporaryDirectory(prefix='banner-cache-startup-') as directory:
            path = Path(directory) / 'CacheStartupTest.java'
            path.write_text(source)
            for command in (['javac', str(path)], ['java', '-cp', directory, 'CacheStartupTest']):
                result = subprocess.run(command, capture_output=True, text=True, timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
