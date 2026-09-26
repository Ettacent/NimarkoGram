"""Focused JVM tests using production filters, hook methods and watchdog methods.

Uses an already cached MVEL jar; never resolves dependencies or invokes Gradle.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest


JAVA = Path(__file__).resolve().parents[2] / 'main/java'
PLUGINS = JAVA / 'app/nimarkogram/messenger/plugins'


def method(source, signature):
    start = source.index(signature)
    brace = source.index('{', start)
    depth, end = 1, brace + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
class PluginFiltersTest(unittest.TestCase):
    def run_java(self, body, real_filter=False):
        sources = {
            'de/robv/android/xposed/XC_MethodHook.java': '''
package de.robv.android.xposed;
public class XC_MethodHook {
 public static class MethodHookParam {
  public Object thisObject, result; public Object[] args;
  public Object getResult(){return result;}
 }
}''',
            'android/util/Log.java': '''package android.util;
public class Log { public static int e(String a,String b,Throwable t){return 0;} }''',
        }
        classpath = []
        if real_filter:
            cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle')))
            jars = list((cache / 'caches/modules-2/files-2.1/org.mvel/mvel2').glob('*/*/mvel2-*.jar'))
            if not jars:
                self.skipTest('MVEL jar not cached; no dependency downloads permitted')
            classpath.append(str(sorted(jars)[-1]))
            sources['app/nimarkogram/messenger/plugins/hooks/HookFilter.java'] = (
                PLUGINS / 'hooks/HookFilter.java').read_text()
        sources['AuditHarness.java'] = '''
import java.util.*;
import de.robv.android.xposed.XC_MethodHook;
''' + ('import app.nimarkogram.messenger.plugins.hooks.HookFilter;\n' if real_filter else '') + '''
public class AuditHarness {
 static void check(boolean value){if(!value)throw new AssertionError();}
''' + body + '\n}'
        with tempfile.TemporaryDirectory(prefix='nm-audit-plugin-filters-') as folder:
            files = []
            for name, contents in sources.items():
                path = Path(folder) / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(contents)
                files.append(str(path))
            cp = os.pathsep.join([folder] + classpath)
            for command in (['javac', '-cp', cp, '-d', folder] + files,
                            ['java', '-cp', cp, 'AuditHarness']):
                result = subprocess.run(command, text=True, capture_output=True, timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_real_mvel_reentrancy_instanceof_and_exception_cleanup(self):
        self.run_java(r'''
 public static class Target {
  public String marker="outer";
  public boolean nested(){
   HookFilter inner=new HookFilter("condition");inner.object="inner";
   inner.mvelExpression="param.args[0] == 'inner' && result == 'inner' && object == 'inner'";
   XC_MethodHook.MethodHookParam p=param("inner");
   check(inner.execute(p,false));
   // A failed nested evaluation must also leave the outer map untouched.
   inner.mvelExpression="object.noSuchMethod()";
   check(!inner.execute(p,false));
   return true;
  }
 }
 static XC_MethodHook.MethodHookParam param(Object value){
  XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();
  p.args=new Object[]{value};p.result=value;return p;
 }
 public static void main(String[] args)throws Exception {
  HookFilter arg=new HookFilter("argument_is_instance_of");arg.argIndex=0;
  arg.instanceOf=CharSequence.class;check(arg.execute(param("str"),true));
  arg.instanceOf=Number.class;check(arg.execute(param(Integer.valueOf(2)),true));
  check(!arg.execute(param("str"),true));check(!arg.execute(param(null),true));
  arg.instanceOf=null;check(!arg.execute(param("str"),true));
  HookFilter result=new HookFilter("result_is_instance_of");result.instanceOf=List.class;
  check(result.execute(param(new ArrayList<>()),false));
  check(!result.execute(param(new ArrayList<>()),true));
  check(!result.execute(param(null),false));
  HookFilter outer=new HookFilter("condition");outer.object=new Target();
  outer.mvelExpression="object.nested() && param.args[0] == 'outer' && result == 'outer' && object.marker == 'outer'";
  for(int i=0;i<3;i++)check(outer.execute(param("outer"),false));
  java.lang.reflect.Field field=HookFilter.class.getDeclaredField("VARS_TL");
  field.setAccessible(true);
  check(((Map<?,?>)((ThreadLocal<?>)field.get(null)).get()).isEmpty());
  outer.mvelExpression="object.noSuchMethod()";check(!outer.execute(param("outer"),false));
  check(((Map<?,?>)((ThreadLocal<?>)field.get(null)).get()).isEmpty());
 }
''', real_filter=True)

    def test_watchdog_covers_filters_and_preserves_outer_timestamp(self):
        hooks = (PLUGINS / 'xposed/PyMethodHook.java').read_text()
        watchdog = (PLUGINS / 'utils/PluginsWatchdog.java').read_text()
        before = method(hooks, 'protected void beforeHookedMethod(')
        after = method(hooks, 'protected void afterHookedMethod(')
        start = method(watchdog, 'public void onPluginExecutionStarted(')
        finish = method(watchdog, 'public void onPluginExecutionFinished(')
        self.run_java(r'''
 static class SystemClock { static long clock=100; static long elapsedRealtime(){return ++clock;} }
 static class ExecutionInfo {
  String pluginId;Object runtimeToken;long startTime;ExecutionInfo prev;
  ExecutionInfo(String p,Object r,long s,ExecutionInfo v){pluginId=p;runtimeToken=r;startTime=s;prev=v;}
  String getPluginId(){return pluginId;}
 }
 static class Plugin {void setNotResponding(boolean value){} }
 static class NotificationCenter {
  static int pluginIsNotResponding=1;
  static NotificationCenter getGlobalInstance(){return new NotificationCenter();}
  void postNotificationNameOnUIThread(int id){}
 }
 static class Watchdog {
  PluginsController controller;
  Map<Thread,ExecutionInfo> executingPlugins=new HashMap<>();
  Map<Thread,Object> callbackFailures=new HashMap<>();
  String lastReportedFrozenPluginId;Object lastReportedFrozenRuntime;
  void dismissStaleAlert(String id){}
  void onPluginExecutionFailed(String id,Throwable error){}
  START
  FINISH
 }
 static class PluginsController {
  static PluginsController instance=new PluginsController();
  Watchdog watchdog=new Watchdog();Map<String,Plugin> plugins=new HashMap<>();int entered;
  PluginsController(){watchdog.controller=this;}
  static PluginsController getInstance(){return instance;}
  Watchdog getWatchdog(){return watchdog;}
  Object captureCurrentPluginRuntime(){return "token";}
 }
 static class PyException extends RuntimeException {}
 static class Callback {
  int calls;boolean fail;
  void call(Object p){calls++;if(fail)throw new PyException();}
  void callAttr(String name,Object p){call(p);}
 }
 static class PluginsConstants {static class Xposed {
  static String BEFORE_HOOKED_METHOD="before",AFTER_HOOKED_METHOD="after";
 }}
 static class HookFilter {
  boolean accept=true,fail; Runnable nested;
  boolean execute(XC_MethodHook.MethodHookParam p,boolean before){
   check(PluginsController.instance.watchdog.executingPlugins.get(Thread.currentThread())!=null);
   if(nested!=null)nested.run();if(fail)throw new IllegalStateException();return accept;
  }
 }
 static class Hook {
  String pluginId="audit";boolean hasBeforeHook=true,hasAfterHook=true;
  ArrayList<HookFilter> beforeHookedFilters=new ArrayList<>(),afterHookedFilters=new ArrayList<>();
  Callback boundBefore,boundAfter,pythonCallback=new Callback();
  boolean enterRuntime(PluginsController c){c.entered++;return true;}
  void exitRuntime(PluginsController c){c.entered--;}
  void handleHookError(String name,Throwable t){}
  BEFORE
  AFTER
 }
 static void invoke(Hook hook,boolean before)throws Throwable {
  if(before)hook.beforeHookedMethod(new XC_MethodHook.MethodHookParam());
  else hook.afterHookedMethod(new XC_MethodHook.MethodHookParam());
 }
 public static void main(String[] args)throws Throwable {
  PluginsController c=PluginsController.instance;Watchdog w=c.watchdog;
  for(boolean before:new boolean[]{true,false}){
   Hook h=new Hook();HookFilter f=new HookFilter();
   (before?h.beforeHookedFilters:h.afterHookedFilters).add(f);
   w.onPluginExecutionStarted("audit");
   ExecutionInfo outer=w.executingPlugins.get(Thread.currentThread());long timestamp=outer.startTime;
   f.nested=()->{
    ExecutionInfo filtering=w.executingPlugins.get(Thread.currentThread());
    w.onPluginExecutionStarted("audit");w.onPluginExecutionFinished("audit");
    check(w.executingPlugins.get(Thread.currentThread())==filtering);
   };
   f.accept=false;invoke(h,before);check(h.pythonCallback.calls==0);
   check(w.executingPlugins.get(Thread.currentThread())==outer&&outer.startTime==timestamp);
   f.accept=true;invoke(h,before);check(h.pythonCallback.calls==1);
   f.fail=true;try{invoke(h,before);throw new AssertionError();}catch(IllegalStateException expected){}
   f.fail=false;h.pythonCallback.fail=true;
   try{invoke(h,before);throw new AssertionError();}catch(PyException expected){}
   check(c.entered==0);
   check(w.executingPlugins.get(Thread.currentThread())==outer&&outer.startTime==timestamp);
   w.onPluginExecutionFinished("audit");check(w.executingPlugins.isEmpty());
  }
 }
'''.replace('START', start).replace('FINISH', finish)
            .replace('BEFORE\n', before + '\n').replace('AFTER\n', after + '\n'))


if __name__ == '__main__':
    unittest.main()
