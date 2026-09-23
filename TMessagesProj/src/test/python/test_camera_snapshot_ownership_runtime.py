"""Run actual production snapshot ownership methods on JVM EGL/Handler stubs."""
import shutil
import unittest
from pathlib import Path
from test_recording_composer_lifecycle import method
from test_sender_infocard_transitions import run_java

VIEW = (Path(__file__).resolve().parents[2] / 'main/java/org/telegram/ui/Components/InstantCameraView.java').read_text()


@unittest.skipUnless(shutil.which('javac'), 'JDK required')
class SnapshotOwnershipRuntimeTests(unittest.TestCase):
    def test_queued_references_delayed_deletion_and_old_gl_owner(self):
        snapshot = method(VIEW, 'private final class CameraSnapshot')
        clear = method(VIEW, 'private void clearCameraXSnapshot()')
        run_java('''import java.util.*;import java.nio.*;
public class Transitions {
 static void check(boolean b){if(!b)throw new AssertionError();}
 static class GLES20 {
  static Set<Integer> deleted=new HashSet<>();static int finished;
  static void glFinish(){finished++;}
  static void glDeleteTextures(int n,int[] names,int offset){
   for(int i=0;i<n;i++)check(deleted.add(names[offset+i]));
  }
 }
 static class EGL {boolean eglMakeCurrent(Object a,Object b,Object c,Object d){return true;}}
 static class Handler {
  List<Runnable> tasks=new ArrayList<>();void post(Runnable r){tasks.add(r);}
  void drain(){var copy=new ArrayList<>(tasks);tasks.clear();copy.forEach(Runnable::run);}
 }
 FloatBuffer cameraXSnapshotTextureBuffer=FloatBuffer.allocate(8);
 float[] cameraXSnapshotIdentityMatrix=new float[16];
 CameraGLThread.CameraSnapshot cameraXSingleSwitchSnapshotHandle;
 CameraGLThread cameraThread;
 int cameraXSingleSwitchSnapshot,cameraXSingleSwitchSnapshotWidth,cameraXSingleSwitchSnapshotHeight;
 long cameraXSingleSwitchSnapshotTimestamp;
 class CameraGLThread {
  boolean snapshotContextClosed;Handler handler=new Handler();EGL egl10=new EGL();
  Object eglDisplay=new Object(),eglSurface=new Object(),eglContext=new Object();
  Handler getHandler(){return handler;}
  boolean isCurrentGeneration(){return Transitions.this.cameraThread==this;}
  SNAPSHOT
  CLEAR
 }
 public static void main(String[] args){
  var t=new Transitions();var gl=t.new CameraGLThread();t.cameraThread=gl;
  List<CameraGLThread.CameraSnapshot> consumers=new ArrayList<>();
  for(int generation=1;generation<=4;generation++){
   gl.clearCameraXSnapshot();
   var snap=gl.new CameraSnapshot(generation,512,512,generation*100);
   t.cameraXSingleSwitchSnapshotHandle=snap;t.cameraXSingleSwitchSnapshot=generation;
   check(snap.belongsTo(gl));
   for(int frame=0;frame<3;frame++){check(snap.retain());consumers.add(snap);}
   gl.handler.drain();check(GLES20.deleted.isEmpty());
  }
  gl.clearCameraXSnapshot();check(t.cameraXSingleSwitchSnapshot==0);
  for(int i=0;i<consumers.size();i++){
   var snap=consumers.get(i);check(!GLES20.deleted.contains(snap.texture));
   snap.release();check(!GLES20.deleted.contains(snap.texture));
   gl.handler.drain();check(GLES20.deleted.contains(snap.texture)==(i%3==2));
  }
  check(GLES20.deleted.size()==4&&GLES20.finished==4);
  check(!consumers.get(0).retain()); // cannot resurrect deleted name
  var old=gl.new CameraSnapshot(5,512,512,500);check(old.retain());
  t.cameraXSingleSwitchSnapshotHandle=old;
  gl.snapshotContextClosed=true;gl.clearCameraXSnapshot();
  old.release();gl.handler.drain();check(!GLES20.deleted.contains(5));
  // A replaced owner must not clear/free a fresh generation's publication.
  var next=t.new CameraGLThread();var fresh=next.new CameraSnapshot(6,512,512,600);
  t.cameraXSingleSwitchSnapshotHandle=fresh;t.cameraXSingleSwitchSnapshot=6;
  gl.clearCameraXSnapshot();check(t.cameraXSingleSwitchSnapshotHandle==fresh);
  next.clearCameraXSnapshot();
  next.snapshotContextClosed=true;next.handler.drain();
  check(!GLES20.deleted.contains(6)); // teardown between scheduling and GL deletion
 }
}'''.replace('SNAPSHOT', snapshot).replace('CLEAR', clear))
