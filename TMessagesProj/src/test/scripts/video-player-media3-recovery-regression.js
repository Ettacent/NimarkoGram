// Runs the actual VideoPlayer methods with host fakes, not an Android/Gradle build.
// Fakes enforce the ownership/thread conditions checked in the pinned Media3 source.
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../../..');
const source = fs.readFileSync(path.join(root, 'TMessagesProj/src/main/java/org/telegram/ui/Components/VideoPlayer.java'), 'utf8');
const media = path.join(root, 'TMessagesProj_Modules/media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer');
assert(fs.readFileSync(path.join(media, 'DefaultLoadControl.java'), 'utf8').includes('threadId == C.INDEX_UNSET || threadId == currentThreadId'));
assert(fs.readFileSync(path.join(media, 'trackselection/TrackSelector.java'), 'utf8').includes('checkState(this.listener == null)'));
function method(signature) {
    const start = source.indexOf('    ' + signature);
    assert(start >= 0, signature);
    let end = source.indexOf('{', start), depth = 0;
    do {
        if (source[end] === '{') depth++;
        if (source[end] === '}') depth--;
        end++;
    } while (depth);
    return source.slice(start, end);
}
const methods = [
    'private DefaultLoadControl createLoadControl()',
    'private void ensurePlayerCreated()',
    'public void onPlayerError(',
    'private void recoverPlayer(',
    'public void setPlayWhenReady(',
].map(method).join('\n');
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'video-player-media3-'));
try {
    fs.writeFileSync(path.join(dir, 'VideoPlayer.java'), `import java.util.*;
public class VideoPlayer {
 static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
 static class Looper {
  static final Looper MAIN = new Looper(), WORKER = new Looper();
  static Looper current = MAIN;
  final ArrayList<Runnable> pending = new ArrayList<>();
  static Looper myLooper() { return current; }
  void drain() { Looper previous=current; current=this; try { while(!pending.isEmpty()) pending.remove(0).run(); } finally { current=previous; } }
 }
 static class Handler { final Looper looper; Handler(Looper l) { looper=l; } boolean post(Runnable r) { return looper.pending.add(r); } }
 static class AndroidUtilities { static void runOnUIThread(Runnable r) { if(Looper.current==Looper.MAIN)r.run();else Looper.MAIN.pending.add(r); } }
 static class ApplicationLoader { static Object applicationContext = new Object(); }
 static class C { static int DEFAULT_BUFFER_SEGMENT_SIZE=65536; }
 static class DefaultAllocator { DefaultAllocator(boolean trim, int size) {} }
 static class DefaultLoadControl {
  static int DEFAULT_MIN_BUFFER_MS=50000, DEFAULT_MAX_BUFFER_MS=50000, DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS=5000, DEFAULT_TARGET_BUFFER_BYTES=-1, DEFAULT_BACK_BUFFER_DURATION_MS=0;
  static boolean DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS=false, DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME=false;
  Object playbackThread; DefaultAllocator allocator; int start, rebuffer;
  void prepared(Object thread) { check(playbackThread==null || playbackThread==thread,"shared LoadControl playback thread"); playbackThread=thread; }
  static class Builder {
   final DefaultLoadControl value=new DefaultLoadControl();
   Builder setAllocator(DefaultAllocator a){value.allocator=a;return this;}
   Builder setBufferDurationsMs(int min,int max,int start,int rebuffer){value.start=start;value.rebuffer=rebuffer;return this;}
   Builder setTargetBufferBytes(int n){return this;}
   Builder setPrioritizeTimeOverSizeThresholds(boolean b){return this;}
   Builder setBackBuffer(int n,boolean b){return this;}
   DefaultLoadControl build(){return value;}
  }
 }
 static class AdaptiveTrackSelection { static class Factory {} }
 static class DefaultTrackSelector {
  boolean initialized; Object parameters=new Object();
  DefaultTrackSelector(Object context,AdaptiveTrackSelection.Factory factory){}
  Object getParameters(){return parameters;}
  void setParameters(Object p){parameters=p;}
  void init(){check(!initialized,"shared TrackSelector owner");initialized=true;}
 }
 static class DefaultRenderersFactory {
  static int EXTENSION_RENDERER_MODE_ON=1, EXTENSION_RENDERER_MODE_PREFER=2;
  DefaultRenderersFactory(Object context){}
  void setExtensionRendererMode(int mode){} void setEnableDecoderFallback(boolean enabled){}
 }
 static class AudioVisualizerRenderersFactory extends DefaultRenderersFactory { AudioVisualizerRenderersFactory(Object c){super(c);} }
 interface Player { int STATE_READY=3; interface Listener { default void onPlayerStateChanged(boolean ready,int state){} } }
 static class ExoPlayer {
  static int REPEAT_MODE_ALL=2,REPEAT_MODE_OFF=0;
  Looper looper; DefaultLoadControl load; DefaultTrackSelector selector;
  final Object playbackThread=new Object(); boolean playing; int binds,repeat;
  void checkThread(){check(Looper.current==looper,"wrong player thread");}
  Looper getApplicationLooper(){return looper;}
  boolean getPlayWhenReady(){checkThread();return playing;}
  void setPlayWhenReady(boolean b){checkThread();playing=b;}
  void setRepeatMode(int mode){repeat=mode;}
  void addListener(Object o){} void addAnalyticsListener(Object o){} void addVideoListener(Object o){}
  void setVideoTextureView(TextureView t){checkThread();binds++;}
  void clearVideoTextureView(TextureView t){checkThread();binds++;}
  void setVideoSurface(Object s){} void setVideoSurfaceView(Object s){}
  static class Builder {
   final ExoPlayer value=new ExoPlayer();
   Builder(Object context){value.looper=Looper.current;}
   Builder setRenderersFactory(DefaultRenderersFactory f){return this;}
   Builder setTrackSelector(DefaultTrackSelector s){value.selector=s;return this;}
   Builder setLoadControl(DefaultLoadControl c){value.load=c;return this;}
   Builder setLooper(Looper l){value.looper=l;return this;}
   ExoPlayer build(){value.selector.init();return value;}
  }
 }
 interface ViewParent {}
 static class ViewGroup implements ViewParent { int indexOfChild(TextureView t){return 0;} void removeView(TextureView t){} void addView(TextureView t,int index){} }
 static class TextureView { ViewParent getParent(){return new ViewGroup();} }
 static class PlaybackException extends Exception { PlaybackException(Throwable cause){super(cause);} }
 static class MediaCodecDecoderException extends Exception { MediaCodecDecoderException(){super("av01 decoder");} }
 static class MediaCodecRenderer { static class DecoderInitializationException extends Exception {} }
 static class SurfaceNotValidException extends Exception {}
 static class FileLog { static void e(Object o){} }
 static class MessagesController {
  static MessagesController getGlobalMainSettings(){return new MessagesController();}
  MessagesController edit(){return this;} MessagesController putBoolean(String s,boolean b){return this;} boolean commit(){return true;}
 }
 static class Quality {
  final boolean supported; Quality(boolean s){supported=s;}
  static ArrayList<Quality> filterByCodec(ArrayList<Quality> qualities){if(qualities!=null)qualities.removeIf(q->!q.supported);return qualities;}
 }
 interface VideoPlayerDelegate { void onError(VideoPlayer p,Exception error); }
 boolean isStory,mixedAudio,autoplay,looping,audioPlayerReady,videoPlayerReady,mixedPlayWhenReady,released,triedReinit,loopingMediaSource;
 ExoPlayer player,audioPlayer; Looper looper; Object audioVisualizerDelegate,surface,surfaceView;
 TextureView textureView; DefaultTrackSelector trackSelector=new DefaultTrackSelector(null,new AdaptiveTrackSelection.Factory());
 ArrayList<Quality> videoQualities; Quality videoQualityToSelect; Map<String,Boolean> cachedSupportedCodec=new HashMap<>();
 String videoUri="video",videoType="other",audioUri="audio",audioType="other";
 int errors,prepares; VideoPlayerDelegate delegate=(p,e)->{check(Looper.current==Looper.MAIN,"error delegate must run on main");errors++;};
 void checkPlayersReady(){}
 void preparePlayer(String uri,String type){player.checkThread();prepares++;player.load.prepared(player.playbackThread);}
 void preparePlayer(ArrayList<Quality> qualities,Quality selected){check(!qualities.isEmpty(),"empty fallback");check(selected==null||qualities.contains(selected),"retired quality");preparePlayer("quality","hls");}
 void preparePlayerLoop(String v,String vt,String a,String at){preparePlayer(v,vt);audioPlayer.load.prepared(audioPlayer.playbackThread);audioPlayerReady=videoPlayerReady=false;}
 ${methods}
 static VideoPlayer fresh(Looper owner, boolean playing) {
  Looper.current=owner; VideoPlayer p=new VideoPlayer();p.ensurePlayerCreated();p.player.setPlayWhenReady(playing);Looper.current=Looper.MAIN;return p;
 }
 public static void main(String[] args) {
  for(boolean story:new boolean[]{false,true}) {
   VideoPlayer p=new VideoPlayer();p.isStory=story;p.mixedAudio=true;p.looping=true;p.ensurePlayerCreated();
   check(p.player.load!=p.audioPlayer.load,"independent load controls");
   check(p.player.load.allocator!=p.audioPlayer.load.allocator,"independent allocators");
   check(p.player.selector!=p.audioPlayer.selector,"independent selectors");
   check(p.audioPlayer.selector.getParameters()==p.trackSelector.getParameters(),"copy initial selection parameters");
   check(p.player.load.start==(story?1000:100)&&p.audioPlayer.load.start==p.player.load.start,"buffer start settings");
   check(p.player.load.rebuffer==(story?1000:5000)&&p.audioPlayer.load.rebuffer==p.player.load.rebuffer,"rebuffer settings");
   p.player.load.prepared(p.player.playbackThread);p.audioPlayer.load.prepared(p.audioPlayer.playbackThread);
   check(p.player.repeat==ExoPlayer.REPEAT_MODE_ALL,"repeat preserved");
  }
  VideoPlayer explicit=new VideoPlayer();explicit.mixedAudio=true;explicit.looper=Looper.WORKER;Looper.current=Looper.WORKER;explicit.ensurePlayerCreated();Looper.current=Looper.MAIN;
  check(explicit.audioPlayer.looper==explicit.player.looper,"mixed application loopers match");
  for(Looper owner:new Looper[]{Looper.MAIN,Looper.WORKER}) for(boolean playing:new boolean[]{false,true}) {
   VideoPlayer p=fresh(owner,playing);p.textureView=new TextureView();
   Looper.current=owner;p.onPlayerError(new PlaybackException(new SurfaceNotValidException()));Looper.current=Looper.MAIN;
   Looper.MAIN.drain();owner.drain();
   check(p.prepares==1&&p.player.playing==playing&&p.player.binds==2,"texture recovery preserves intent/thread");
  }
  for(int kind=0;kind<3;kind++) {
   VideoPlayer p=fresh(Looper.MAIN,false);
   if(kind>0)p.videoQualities=new ArrayList<>();if(kind==2)p.videoQualities.add(new Quality(false));
   p.onPlayerError(new PlaybackException(new MediaCodecDecoderException()));
   check(p.errors==1&&p.prepares==0,"AV1 without fallback reaches delegate");
  }
  VideoPlayer av1=fresh(Looper.WORKER,false);av1.videoQualities=new ArrayList<>();
  av1.videoQualityToSelect=new Quality(false);av1.videoQualities.add(av1.videoQualityToSelect);av1.videoQualities.add(new Quality(true));
  av1.onPlayerError(new PlaybackException(new MediaCodecDecoderException()));Looper.WORKER.drain();
  check(av1.prepares==1&&av1.errors==0&&!av1.player.playing&&av1.videoQualityToSelect==null,"AV1 fallback thread/intent/selection");
  VideoPlayer paused=fresh(Looper.WORKER,true);paused.textureView=new TextureView();
  Looper.WORKER.pending.add(()->paused.setPlayWhenReady(false));
  paused.onPlayerError(new PlaybackException(new SurfaceNotValidException()));Looper.WORKER.drain();
  check(paused.prepares==1&&!paused.player.playing,"queued pause wins over recovery");
  for(int stale=0;stale<3;stale++) {
   VideoPlayer p=fresh(Looper.WORKER,true);p.textureView=new TextureView();p.onPlayerError(new PlaybackException(new SurfaceNotValidException()));
   if(stale==0)p.released=true;if(stale==1)p.textureView=new TextureView();if(stale==2)p.player=null;
   Looper.WORKER.drain();check(p.prepares==0,"stale recovery rejected");
  }
  VideoPlayer mixed=new VideoPlayer();mixed.mixedAudio=true;mixed.loopingMediaSource=true;mixed.mixedPlayWhenReady=true;mixed.ensurePlayerCreated();
  mixed.recoverPlayer(mixed.player,null);
  check(mixed.prepares==1&&mixed.mixedPlayWhenReady&&!mixed.player.playing&&!mixed.audioPlayer.playing,"mixed readiness preserves requested play");
  System.out.println("PASS: actual player creation/error/recovery methods: separate Media3 ownership, buffering, loopers, repeat, AV1 forwarding/fallback, paused/playing recovery, queued pause, stale callbacks, mixed readiness");
 }
}`);
    cp.execFileSync('javac', ['VideoPlayer.java'], {cwd: dir, stdio: 'inherit'});
    cp.execFileSync('java', ['VideoPlayer'], {cwd: dir, stdio: 'inherit'});
} finally {
    fs.rmSync(dir, {recursive: true, force: true});
}
