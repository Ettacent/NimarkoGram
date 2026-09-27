"""Run production worker/lifecycle bodies on a JVM with a fake bitmap encoder."""
from pathlib import Path
import unittest
from test_recording_composer_lifecycle import method
from test_photo_story_shared_media_handoff import run_java

JAVA = Path(__file__).resolve().parents[2] / 'main/java/org/telegram'


class StickerCacheWorkerTests(unittest.TestCase):
    def test_concurrent_first_stickers_share_one_cache_queue(self):
        source = (JAVA / 'ui/Components/RLottieDrawable.java').read_text()
        create = method(source, 'public static synchronized void createCacheGenQueue()')
        run_java('''
import java.util.concurrent.atomic.*;
public class Transitions {
    static volatile DispatchQueue lottieCacheGenerateQueue;
    static class DispatchQueue {
        static AtomicInteger created=new AtomicInteger();
        DispatchQueue(String name){created.incrementAndGet();}
    }
    CREATE
    public static void main(String[] args)throws Exception{
        Thread[] threads=new Thread[40];
        for(int i=0;i<threads.length;i++){
            threads[i]=new Thread(()->{for(int n=0;n<100;n++)createCacheGenQueue();});
            threads[i].start();
        }
        for(Thread t:threads)t.join();
        assert DispatchQueue.created.get()==1;
    }
}
'''.replace('CREATE',create))

    def test_webm_failure_releases_queue_and_uses_decoder(self):
        source = (JAVA / 'ui/Components/AnimatedFileDrawable.java').read_text()
        bodies = '\n'.join(method(source,s) for s in (
            'private final class CacheGenerationTask',
            'private void cancelQueuedCacheGeneration(',
            'private void finishCacheGeneration('))
        self.assertIn('if (bitmapsCache != null && !cacheGenerationFailed)', source)
        release = method(source,'private void releaseResources()')
        self.assertLess(release.index('if (cacheGenRunnable != null) return'),release.index('releaseResourcesLocked()'))
        run_java('''
public class Transitions {
    CacheGenerationTask cacheGenRunnable;
    boolean generatingCache=true,cacheGenerationFailed,isRecycled,destroyWhenDone;
    int schedules,checks;
    static class FileLog {static void e(Throwable e){}}
    static class BitmapsCache {
        static int count=1; boolean fail=true; Runnable during;
        void createCache(){if(during!=null)during.run();if(fail)throw new IllegalStateException();}
        boolean needGenCache(){return fail;}
        static void decrementTaskCounter(){count--;}
    }
    BitmapsCache bitmapsCache=new BitmapsCache();
    static class Queue {void cancelRunnable(Runnable r){}}
    static class RLottieDrawable {static Queue lottieCacheGenerateQueue=new Queue();}
    static class AndroidUtilities {
        static Runnable pending; static void runOnUIThread(Runnable r){pending=r;}
    }
    void chekDestroyDecoder(){checks++;}
    void scheduleNextGetFrame(){schedules++;}
    BODIES
    public static void main(String[] args){
        Transitions t=new Transitions();
        CacheGenerationTask active=t.new CacheGenerationTask();t.cacheGenRunnable=active;
        t.bitmapsCache.during=()->{
            t.cancelQueuedCacheGeneration();
            assert t.cacheGenRunnable==active && t.generatingCache;
        };
        active.run();assert AndroidUtilities.pending!=null;
        AndroidUtilities.pending.run();
        assert BitmapsCache.count==0 && !t.generatingCache && t.cacheGenRunnable==null;
        assert t.cacheGenerationFailed && t.schedules==1 && t.checks==1;
        t.finishCacheGeneration(active);assert t.schedules==1 && BitmapsCache.count==0;
        CacheGenerationTask queued=t.new CacheGenerationTask();t.cacheGenRunnable=queued;
        t.generatingCache=true;BitmapsCache.count=1;
        t.cancelQueuedCacheGeneration();queued.run();
        assert !t.generatingCache && t.cacheGenRunnable==null && BitmapsCache.count==0;
    }
}
'''.replace('BODIES',bodies))

    def test_every_encoder_exit_completes_and_next_task_runs(self):
        source = (JAVA / 'messenger/utils/BitmapsCache.java').read_text()
        worker = method(source, 'private void compressFrame(')
        run_java('''
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public class Transitions {
    AtomicBoolean cancelled=new AtomicBoolean(); final Object mutex=new Object();
    int compressQuality=60;
    static class Build { static class VERSION { static int SDK_INT=36; } }
    static class FileLog { static void e(Throwable e) {} }
    static class ImmutableByteArrayOutputStream {
        byte[] buf=new byte[32]; int count;
        void reset(){count=0;}
    }
    static class FrameOffset {
        int frameOffset,frameSize; FrameOffset(int i){}
    }
    static class Bitmap {
        enum CompressFormat {PNG,WEBP}
        int mode;
        boolean compress(CompressFormat f,int q,ImmutableByteArrayOutputStream s){
            if(mode==1)return false;
            if(mode==2)throw new IllegalStateException();
            if(mode==3)throw new OutOfMemoryError();
            if(mode!=4)s.count=4;
            return true;
        }
    }
    WORKER
    public static void main(String[] args)throws Exception{
        File file=File.createTempFile("sticker-worker-",".cache");
        try(RandomAccessFile target=new RandomAccessFile(file,"rw")){
            for(int mode=0;mode<8;mode++){
                Transitions t=new Transitions(); Bitmap bitmap=new Bitmap(); bitmap.mode=mode;
                AtomicBoolean closed=new AtomicBoolean(mode==6);
                t.cancelled.set(mode==5);
                CountDownLatch done=new CountDownLatch(1);
                ArrayList<FrameOffset> offsets=new ArrayList<>();
                RandomAccessFile dest=mode==7?new RandomAccessFile(file,"r"):target;
                try {
                    t.compressFrame(bitmap,new ImmutableByteArrayOutputStream(),dest,0,offsets,closed,done);
                    assert done.await(100,TimeUnit.MILLISECONDS):"stuck mode="+mode;
                    assert offsets.size()==(mode==0?1:0);
                    if(mode>=1&&mode<=4||mode==7)assert closed.get();
                }finally{if(dest!=target)dest.close();}
                // Failure/cancellation must not poison the shared executor's next job.
                t.cancelled.set(false); closed.set(false);bitmap.mode=0;
                done=new CountDownLatch(1); offsets.clear();
                t.compressFrame(bitmap,new ImmutableByteArrayOutputStream(),target,1,offsets,closed,done);
                assert done.getCount()==0 && offsets.size()==1;
            }
        }finally{file.delete();}
    }
}
'''.replace('WORKER',worker))

    def test_only_queued_generation_can_be_cancelled(self):
        source = (JAVA / 'ui/Components/RLottieDrawable.java').read_text()
        bodies = '\n'.join(method(source,s) for s in (
            'private final class CacheGenerationTask',
            'private void cancelQueuedCacheGeneration(',
            'private void uiRunnableCacheFinishedImpl('))
        run_java('''
import java.util.concurrent.atomic.*;
public class Transitions {
    CacheGenerationTask cacheGenerateTask;
    boolean generatingCache=true,genCacheSend=true,isRecycled,destroyWhenDone;
    boolean allowDrawFramesWhileCacheGenerating; Runnable whenCacheDone;
    BitmapsCache bitmapsCache=new BitmapsCache(); int decodes;
    static class FileLog {static void e(Throwable e){}}
    static class BitmapsCache {
        static int count; boolean needs=true; Runnable during;
        void createCache(){if(during!=null)during.run();}
        boolean needGenCache(){return needs;}
        static void decrementTaskCounter(){count--;}
    }
    static class Queue {void cancelRunnable(Runnable r){}}
    Queue lottieCacheGenerateQueue=new Queue();
    static class AndroidUtilities {
        static Runnable pending; static void runOnUIThread(Runnable r){pending=r;}
    }
    void decodeFrameFinishedInternal(){decodes++;}
    BODIES
    public static void main(String[] args){
        Transitions t=new Transitions();BitmapsCache.count=1;
        CacheGenerationTask old=t.new CacheGenerationTask();t.cacheGenerateTask=old;
        t.cancelQueuedCacheGeneration();old.run();
        assert t.cacheGenerateTask==null && !t.generatingCache && !t.genCacheSend;
        assert BitmapsCache.count==0 && AndroidUtilities.pending==null;
        t.generatingCache=t.genCacheSend=true;BitmapsCache.count=1;
        CacheGenerationTask active=t.new CacheGenerationTask();t.cacheGenerateTask=active;
        t.bitmapsCache.during=()->{
            t.cancelQueuedCacheGeneration();
            assert t.cacheGenerateTask==active && t.generatingCache;
            assert BitmapsCache.count==1;
        };
        active.run();
        t.cancelQueuedCacheGeneration(); // finished worker, UI completion still pending
        assert t.cacheGenerateTask==active;
        t.uiRunnableCacheFinishedImpl(old); // stale completion must not clear new owner
        assert BitmapsCache.count==1 && t.decodes==0;
        AndroidUtilities.pending.run();
        assert BitmapsCache.count==0 && t.cacheGenerateTask==null && !t.generatingCache;
        assert t.allowDrawFramesWhileCacheGenerating && t.genCacheSend && t.decodes==1;
        AndroidUtilities.pending.run();assert BitmapsCache.count==0 && t.decodes==1;
    }
}
'''.replace('BODIES',bodies))


if __name__ == '__main__':
    unittest.main()
