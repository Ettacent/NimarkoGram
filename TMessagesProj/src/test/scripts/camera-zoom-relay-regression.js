const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const cp = require('node:child_process');
const assert = require('node:assert/strict');

const javaRoot = path.resolve(__dirname, '../../main/java');
const read = name => fs.readFileSync(path.join(javaRoot, name), 'utf8');
const camera = read('app/nimarkogram/messenger/camera/NimarkoCameraXView.java');
const utils = read('app/nimarkogram/messenger/camera/CameraXUtils.java');
const domain = read('app/nimarkogram/messenger/wsbypass/DomainPool.java');
const region = read('app/nimarkogram/messenger/wsbypass/RelayRegion.java');
const voip = read('app/nimarkogram/messenger/wsbypass/voip/VoipBypassConfig.java');
const asiaHost = voip.match(/ASIA_RELAY_HOST\s*=\s*"([^"]+)"/)?.[1] ?? 'asia.example.test';

function method(source, signature) {
    const start = source.indexOf(signature);
    assert(start >= 0, signature);
    const body = source.indexOf('{', start);
    let depth = 1, end = body + 1;
    while (depth && end < source.length) {
        if (source[end] === '{') depth++;
        if (source[end] === '}') depth--;
        end++;
    }
    assert.equal(depth, 0);
    return source.slice(start, end);
}

assert(!camera.includes('CONTROL_ZOOM_RATIO'), 'No persistent interop zoom override');
assert(camera.includes('if (show && !cameraControlsReady) return;'), 'Keep initial preview hidden');
assert(camera.includes('cancelPendingPreviewReady();\n                    cameraControlsReady = false;'));
assert(camera.includes('if (boundCamera != camera || generation != cameraGeneration) return;'));
assert(read('app/nimarkogram/messenger/NimarkoCameraLog.java').includes('DEBUG = false'));

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-zoom-relay-test-'));
const files = [];
function write(name, data) {
    const dest = path.join(dir, name);
    fs.mkdirSync(path.dirname(dest), {recursive: true});
    fs.writeFileSync(dest, data);
    files.push(dest);
}

write('app/nimarkogram/messenger/NimarkoConfig.java', `package app.nimarkogram.messenger;
public class NimarkoConfig { public static String id="test"; public static String ensureWsInstallId(){return id;} }`);
write('org/telegram/messenger/UserConfig.java', `package org.telegram.messenger;
public class UserConfig { public static int selectedAccount; }`);
write('org/telegram/messenger/BuildConfig.java', `package org.telegram.messenger;
public class BuildConfig { public static final String NIMARKO_ASIA_RELAY_HOST=${JSON.stringify(asiaHost)}; }`);
write('app/nimarkogram/messenger/wsbypass/voip/VoipBypassConfig.java', `package app.nimarkogram.messenger.wsbypass.voip;
public class VoipBypassConfig { public static final String ASIA_RELAY_HOST=${JSON.stringify(asiaHost)}; }`);
write('app/nimarkogram/messenger/wsbypass/RelayRegion.java', `package app.nimarkogram.messenger.wsbypass;
public class RelayRegion {
 public static boolean ASIA_RELAY_ENABLED=${region.match(/ASIA_RELAY_ENABLED\s*=\s*(true|false)/)[1]};
 public static boolean asia;
 public static boolean isAsia(){return asia;}
 public static boolean isAsia(int account){return asia;}
}`);
write('app/nimarkogram/messenger/wsbypass/DomainPool.java', domain);
write('VoipRoutes.java', `import app.nimarkogram.messenger.wsbypass.RelayRegion;
class VoipRoutes {
 ${voip.includes('private static String configuredHost(') ? method(voip, 'private static String configuredHost(') : ''}
 ${voip.match(/public static final String RELAY_HOST = [^;]+;/)[0]}
 ${voip.match(/public static final String ASIA_RELAY_HOST = [^;]+;/)[0]}
 ${method(voip, 'public static String relayHost()')}
 ${method(voip, 'public static String relayHost(int account)')}
 ${method(voip, 'public static String[] relayHosts(int account)')}
}`);

const methods = ['private ListenableFuture<Void> applyInitialZoom', 'private void cancelPendingPreviewReady',
    'private void schedulePreviewReady'].map(s => method(camera, s).replaceAll('androidx.camera.core.', '')).join('\n');
write('CameraHarness.java', `import java.util.*;
import java.util.concurrent.Executor;
class CameraHarness {
 @interface Nullable {}
 static class ListenableFuture<T> {
  boolean done; List<Runnable> listeners=new ArrayList<>();
  boolean isDone(){return done;}
  void addListener(Runnable r, Executor e){if(done)e.execute(r);else listeners.add(()->e.execute(r));}
  void complete(){done=true;new ArrayList<>(listeners).forEach(Runnable::run);listeners.clear();}
 }
 static class AndroidUtilities {
  static Map<Runnable,Long> tasks=new LinkedHashMap<>();
  static void runOnUIThread(Runnable r,long delay){tasks.put(r,delay);}
  static void cancelRunOnUIThread(Runnable r){tasks.remove(r);}
  static void run(){var copy=new ArrayList<>(tasks.keySet());tasks.clear();copy.forEach(Runnable::run);}
 }
 static class ContextCompat {static Executor getMainExecutor(Object c){return Runnable::run;}}
 static class FileLog {static void e(Throwable t){throw new AssertionError(t);}}
 static class NimarkoConfig {static boolean startFromUltraWideCam=true;}
 static class Value<T> {T value;Value(T v){value=v;}T getValue(){return value;}}
 static class ZoomState {
  float min=.6f,max=20f;float getMinZoomRatio(){return min;}float getMaxZoomRatio(){return max;}
 }
 static class Info {Value<ZoomState> state=new Value<>(new ZoomState());Value<ZoomState> getZoomState(){return state;}}
 static class Control {
  float ratio;ListenableFuture<Void> future=new ListenableFuture<>();
  ListenableFuture<Void> setZoomRatio(float v){ratio=v;return future;}
 }
 static class Camera {Info info=new Info();Control control=new Control();Info getCameraInfo(){return info;}Control getCameraControl(){return control;}}
 static class CameraXUtils {
  static boolean isWideAngleAvailable(Object provider){return true;}
  ${method(utils, 'public static float getBaseZoomRatio')}
 }
 static class PreviewView {
  enum StreamState {STREAMING,IDLE}
  Value<StreamState> state=new Value<>(StreamState.STREAMING);
  Value<StreamState> getPreviewStreamState(){return state;}float getAlpha(){return 0;}
 }
 static class Coordinator {
  float requested=Float.NaN;boolean ready;
  float getRequestedOr(float v){return Float.isNaN(requested)?v:requested;}
  void requestZoomRatio(float v){requested=v;}
  void setReady(Camera c,int g,boolean v){ready=v;}
 }
 Camera camera=new Camera();int cameraGeneration=1;Object provider=new Object();
 boolean frontFacing,cameraControlsReady,isStreaming,firstFrameRendered,cameraSwitchInProgress=true,streamingEnabled=true;
 float baseZoomRatio=1;Runnable pendingPreviewReady;PreviewView previewView=new PreviewView();
 Coordinator zoomCoordinator=new Coordinator();int reveals,controls;
 Object getContext(){return null;}
 void trackControlFuture(Object f,Camera c,int g,String s){}
 void applyStableCameraControls(Camera c,int g){controls++;}
 void hideSwitchPlaceholder(){reveals++;}void showTexture(boolean a,boolean b){}void onFirstFrameRendered(){}
 ${methods}
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 void start(){schedulePreviewReady(camera,cameraGeneration,applyInitialZoom(camera,cameraGeneration));}
 public static void main(String[] args){
  CameraHarness h=new CameraHarness();h.start();check(h.camera.control.ratio==.6f);check(h.reveals==0);
  check(AndroidUtilities.tasks.get(h.pendingPreviewReady)==1500L);
  h.camera.control.future.complete();check(AndroidUtilities.tasks.get(h.pendingPreviewReady)==120L);
  AndroidUtilities.run();check(h.reveals==1&&h.cameraControlsReady&&h.zoomCoordinator.ready);
  h.zoomCoordinator.requestZoomRatio(5f);h.cameraControlsReady=false;h.start();AndroidUtilities.run();
  check(h.camera.control.ratio==5f&&h.zoomCoordinator.requested==5f);
  h=new CameraHarness();h.frontFacing=true;h.start();check(h.camera.control.ratio==1f);h.cancelPendingPreviewReady();
  NimarkoConfig.startFromUltraWideCam=false;h=new CameraHarness();h.start();check(h.camera.control.ratio==1f);h.cancelPendingPreviewReady();
  NimarkoConfig.startFromUltraWideCam=true;h=new CameraHarness();h.camera.info.state.value.min=1;h.start();check(h.camera.control.ratio==1f);h.cancelPendingPreviewReady();
  h=new CameraHarness();h.start();h.cameraGeneration++;h.camera.control.future.complete();AndroidUtilities.run();check(h.reveals==0);
  h=new CameraHarness();h.start();h.previewView.state.value=PreviewView.StreamState.IDLE;AndroidUtilities.run();check(h.reveals==0);
  h=new CameraHarness();h.start();h.cancelPendingPreviewReady();h.camera.control.future.complete();AndroidUtilities.run();check(h.reveals==0);
  h=new CameraHarness();h.start();AndroidUtilities.run();check(h.reveals==1);h.camera.control.future.complete();AndroidUtilities.run();check(h.reveals==1);
  h=new CameraHarness();h.start();h.streamingEnabled=false;AndroidUtilities.run();check(h.reveals==0);
  System.out.println("PASS: zoom startup, front/rear, no ultrawide, resume, stale/idle/cancelled callbacks and timeout");
 }
}`);
write('RelayHarness.java', `import java.util.*;
import app.nimarkogram.messenger.wsbypass.*;
class RelayHarness {
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 public static void main(String[] args){
  check(!RelayRegion.ASIA_RELAY_ENABLED);
  for(boolean enabled:new boolean[]{false,true})for(boolean asia:new boolean[]{false,true}){
   RelayRegion.ASIA_RELAY_ENABLED=enabled;RelayRegion.asia=asia;
   check(DomainPool.relayHost().equals(${JSON.stringify(asiaHost)})== (enabled&&asia));
   check(DomainPool.relayHosts().contains(${JSON.stringify(asiaHost)})==enabled);
   for(int dc:new int[]{0,1,2,3,4,5,203}){
    var hosts=DomainPool.relayHostsForDc(dc);
    check(hosts.contains(${JSON.stringify(asiaHost)})==enabled);
    check(hosts.get(0).equals(${JSON.stringify(asiaHost)})== (enabled&&dc==5));
    check(new HashSet<>(hosts).size()==hosts.size());
    check(hosts.containsAll(List.of("r1.nimarko.org","r2.nimarko.org","r3.nimarko.org")));
   }
   for(int account:new int[]{-1,0,1,4,10}){
    check(VoipRoutes.relayHost(account).equals(${JSON.stringify(asiaHost)})== (enabled&&asia));
    check(Arrays.asList(VoipRoutes.relayHosts(account)).contains(${JSON.stringify(asiaHost)})==enabled);
    check(Arrays.asList(VoipRoutes.relayHosts(account)).contains("calls.nimarko.org"));
   }
  }
  System.out.println("PASS: data and calls exclude Asia in primary/fallback; re-enable preserves regional routing");
 }
}`);
cp.execFileSync('javac', ['-d', dir, ...files], {stdio: 'inherit'});
for (const test of ['CameraHarness', 'RelayHarness']) {
    cp.execFileSync('java', ['-cp', dir, test], {stdio: 'inherit'});
}
console.log('Fixtures:', dir);
