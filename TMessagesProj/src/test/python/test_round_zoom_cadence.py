import re
import unittest
from pathlib import Path
from test_sender_infocard_transitions import run_java

ROOT = Path(__file__).resolve().parents[2]


class ZoomCadenceTests(unittest.TestCase):
    def test_small_steps_are_submitted_without_second_frame_delay(self):
        source = (ROOT / 'main/java/app/nimarkogram/messenger/camera/CameraXZoomCoordinator.java').read_text()
        source = re.sub(r'^(?:package|import) .*;\s*', '', source, flags=re.M)
        source = source.replace('@Nullable', '').replace('final class CameraXZoomCoordinator', 'static final class CameraXZoomCoordinator')
        run_java('''import java.util.*;
import java.util.concurrent.*;
public class Transitions {
 static class Looper {
  static final Looper main=new Looper();
  static Looper myLooper(){return main;} static Looper getMainLooper(){return main;}
 }
 static class AndroidUtilities {
  static List<Runnable> queue=new ArrayList<>();
  static void runOnUIThread(Runnable r){queue.add(r);}
 }
 static class FileLog {static void e(String s,Throwable t){throw new AssertionError(s,t);}}
 static class NimarkoCameraLog {static final boolean DEBUG=false;static void log(String s){}}
 static class Choreographer {
  interface FrameCallback {void doFrame(long n);}
  static final Choreographer instance=new Choreographer();
  static Choreographer getInstance(){return instance;}
  List<FrameCallback> callbacks=new ArrayList<>();
  void postFrameCallback(FrameCallback c){callbacks.add(c);}
  void removeFrameCallback(FrameCallback c){callbacks.remove(c);}
 }
 interface ListenableFuture<T> {void addListener(Runnable r,Executor e);T get() throws Exception;}
 static class CameraControl {
  static class OperationCanceledException extends Exception {}
  List<Float> values=new ArrayList<>();
  ListenableFuture<Void> setZoomRatio(float value){values.add(value);return new ListenableFuture<Void>() {
   public void addListener(Runnable r,Executor e){} // deliberately never completes
   public Void get(){return null;}
  };}
 }
 static class ZoomState {float getMinZoomRatio(){return .5f;}float getMaxZoomRatio(){return 10f;}}
 static class State {ZoomState getValue(){return new ZoomState();}}
 static class Info {State getZoomState(){return new State();}}
 static class Camera {
  CameraControl control=new CameraControl();
  Info getCameraInfo(){return new Info();} CameraControl getCameraControl(){return control;}
 }
 SOURCE
 public static void main(String[] args){
  for(int fps:new int[]{30,60,120}){
   Camera camera=new Camera();CameraXZoomCoordinator c=new CameraXZoomCoordinator("test");
   c.attach(camera,1);c.setReady(camera,1,true);
   for(int i=0;i<fps;i++) c.requestAnimatedZoomRatio(1f+i*.02f/fps);
   if(camera.control.values.size()!=fps)throw new AssertionError("dropped slow steps at "+fps);
   if(!Choreographer.instance.callbacks.isEmpty())throw new AssertionError("extra frame delay");
   c.requestAnimatedZoomRatio(camera.control.values.get(fps-1));
   if(camera.control.values.size()!=fps)throw new AssertionError("duplicate");
   c.detach();c.requestAnimatedZoomRatio(2f);
   if(camera.control.values.size()!=fps)throw new AssertionError("detached write");
  }
 }
}'''.replace('SOURCE', source))
