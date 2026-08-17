import pathlib
import unittest

REPO = pathlib.Path(__file__).resolve().parents[4]
JAVA = (
    REPO / 'TMessagesProj/src/main/java/'
    'app/nimarkogram/messenger/plugins'
)

class JavaPythonBoundaryProxySafetyTest(unittest.TestCase):
    def test_interface_proxy_rechecks_exact_state_and_target_after_entry(self):
        proxy = (
            JAVA / 'bridge/PythonInterfaceProxy.java'
        ).read_text(encoding='utf-8')
        invoke = proxy[
            proxy.index('public Object invoke('):
            proxy.index('@Override', proxy.index('public Object invoke(') + 1)
        ]

        entered = invoke.index(
            'controller.enterPluginRuntime(runtimeToken)')
        post_enter_decision = invoke.index(
            'controller.getPluginRuntimeTaskDecision(runtimeToken)',
            entered + 1,
        )
        target_recheck = invoke.index(
            'target.get() != localTarget', post_enter_decision)
        python_call = invoke.index(
            'localTarget.callAttr(', target_recheck)
        self.assertLess(entered, post_enter_decision)
        self.assertLess(post_enter_decision, target_recheck)
        self.assertLess(target_recheck, python_call)
        self.assertIn(
            'PythonBoundarySanitizer.convertPythonResult(', invoke)
        self.assertNotIn('result.toJava(returnType)', invoke)

    def test_sanitizer_wraps_interfaces_and_rejects_nested_raw_pyproxy(self):
        sanitizer = (
            JAVA / 'bridge/PythonBoundarySanitizer.java'
        ).read_text(encoding='utf-8')

        self.assertIn('import com.chaquo.python.PyProxy;', sanitizer)
        self.assertIn('value instanceof PyProxy', sanitizer)
        self.assertIn('value instanceof PyObject', sanitizer)
        self.assertIn(
            'catch (Throwable traversalFailure)', sanitizer)
        self.assertIn('declaredType.isInterface()', sanitizer)
        self.assertIn(
            'getPluginRuntimeTaskDecision(runtimeToken)', sanitizer)
        self.assertIn(
            'PythonInterfaceProxy.create(\n'
            '                    result, runtimeToken, '
            'new Class<?>[]{declaredType})',
            sanitizer,
        )
        for nested_type in (
                'type.isArray()', 'value instanceof Collection',
                'value instanceof Map'):
            self.assertIn(nested_type, sanitizer)
        conversion = sanitizer[
            sanitizer.index('public static Object convertPythonResult('):
            sanitizer.index(
                'public static boolean containsRawPythonProxy(')
        ]
        self.assertIn('containsRawPythonProxy(converted)', conversion)
        self.assertIn('? UNSAFE_VALUE : converted', conversion)

    def test_replacement_closes_scope_before_original_fallback(self):
        replacement = (
            JAVA / 'xposed/PyMethodReplacement.java'
        ).read_text(encoding='utf-8')
        dispatch = replacement[
            replacement.index('protected Object replaceHookedMethod('):
            replacement.index(
                'private Throwable reportReplacementFailure(')
        ]

        entered = dispatch.index(
            'controller.enterPluginRuntime(this.runtimeToken)')
        post_enter_decision = dispatch.index(
            'controller.getPluginRuntimeTaskDecision(this.runtimeToken)',
            entered + 1,
        )
        close = dispatch.rindex('closeExecutionScope(')
        fallback = dispatch.rindex('invokeOriginalWithSuppressed(')
        self.assertLess(entered, post_enter_decision)
        self.assertLess(close, fallback)
        self.assertIn(
            'reportReplacementFailure(controller, failure)', dispatch)
        self.assertNotIn('.toJava(Object.class)', replacement)

        report = replacement[
            replacement.index(
                'private Throwable reportReplacementFailure('):
            replacement.index(
                'private Throwable closeExecutionScope(')
        ]
        self.assertIn('onPluginExecutionFailed(', report)

        close_helper = replacement[
            replacement.index('private Throwable closeExecutionScope('):
            replacement.index('private static Object invokeOriginal(')
        ]
        self.assertLess(
            close_helper.index('onPluginExecutionFinished('),
            close_helper.index('controller.exitPluginRuntime('),
        )
        self.assertIn('mergeFailures(', close_helper)
        self.assertIn('rethrowIfFatal(replacementFailure)', replacement)
        self.assertIn(
            'originalFailure, replacementFailure', replacement)

    def test_pine_rejects_raw_proxy_receivers_args_and_results(self):
        pine = (
            JAVA / 'xposed/PineAdapter.java'
        ).read_text(encoding='utf-8')
        receiver = pine[
            pine.index('private boolean receiverMismatched('):
            pine.index('private static String receiverName(')
        ]
        args = pine[
            pine.index('private boolean argsTypeMismatched('):
            pine.index('private static boolean argCompatible(')
        ]
        result = pine[
            pine.index('private static Object normalizeResult('):
            pine.index('private static Class<?>[] parameterTypesOf(')
        ]
        before = pine[
            pine.index('public void beforeCall('):
            pine.index('public void afterCall(')
        ]

        self.assertIn(
            'PythonBoundarySanitizer.containsRawPythonProxy(', receiver)
        self.assertIn(
            'PythonBoundarySanitizer.containsRawPythonProxy(args)', args)
        self.assertIn(
            'PythonBoundarySanitizer.containsRawPythonProxy(value)', result)
        self.assertIn('return INVALID_RESULT;', result)
        raw_state_check = before.index(
            'invocationContainsRawPythonProxy(cf)')
        throwable = before.index('if (param.hasThrowable())')
        self.assertLess(raw_state_check, throwable)
        self.assertIn('param.restoreInvocation(cf);', before)

if __name__ == '__main__':
    unittest.main()
