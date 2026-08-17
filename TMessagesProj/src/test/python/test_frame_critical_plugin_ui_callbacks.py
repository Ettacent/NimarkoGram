import pathlib
import unittest

REPO = pathlib.Path(__file__).resolve().parents[4]
TEMPLATES = (
    REPO / 'TMessagesProj/src/main/java/app/nimarkogram/messenger/plugins/'
    'ui/components/templates'
)

def read_java(name):
    return (TEMPLATES / name).read_text(encoding='utf-8')

def method_body(source, signature, occurrence=0):
    start = -1
    for _ in range(occurrence + 1):
        start = source.index(signature, start + 1)
    opening = source.index('{', start)
    depth = 0
    for index in range(opening, len(source)):
        char = source[index]
        if char == '{':
            depth += 1
        elif char == '}':
            depth -= 1
            if depth == 0:
                return source[opening + 1:index]
    raise AssertionError(f'unclosed method: {signature}')

class FrameCriticalPluginUiCallbacksTest(unittest.TestCase):
    def test_traversal_methods_only_consume_java_state_and_enqueue(self):
        view = read_java('UniversalView.java')
        frame = read_java('UniversalFrameLayout.java')
        methods = [
            (view, 'protected void onDraw(Canvas canvas)'),
            (view, 'protected void onMeasure('),
            (view, 'public void invalidate()'),
            (view, 'public void invalidate(int left, int top, int right, int bottom)'),
            (frame, 'protected void onLayout('),
            (frame, 'protected void onMeasure('),
            (frame, 'protected void dispatchDraw(Canvas canvas)'),
            (frame, 'protected void onDraw(Canvas canvas)'),
            (frame, 'protected boolean drawChild('),
            (frame, 'public void requestLayout()'),
            (frame, 'public void invalidate()'),
            (frame, 'public void invalidate(int l, int t, int r, int b)'),
            (frame, 'public void setTranslationX(float translationX)'),
            (frame, 'public void setTranslationY(float translationY)'),
            (frame, 'public void setVisibility(int visibility)'),
        ]
        forbidden = (
            'PluginRuntimeDelegate.run(',
            'PluginRuntimeDelegate.call(',
            'PluginRuntimeDelegate.runScoped(',
            'PluginRuntimeDelegate.callScoped(',
        )
        for source, signature in methods:
            body = method_body(source, signature)
            for call in forbidden:
                self.assertNotIn(call, body, f'{signature} enters Python')

        self.assertIn('DrawState state = this.drawState;', methods[0][0])
        self.assertIn('scheduleDrawCallback();', method_body(
            view, 'protected void onDraw(Canvas canvas)'))
        self.assertIn('scheduleMeasureCallback(', method_body(
            view, 'protected void onMeasure('))
        self.assertIn('scheduleDispatchDrawCallback();', method_body(
            frame, 'protected void dispatchDraw(Canvas canvas)'))
        self.assertIn('scheduleDrawChildCallback(', method_body(
            frame, 'protected boolean drawChild('))

    def test_mailbox_is_exact_token_bounded_coalesced_and_revoked(self):
        runtime = read_java('PluginRuntimeDelegate.java')
        queue = runtime[
            runtime.index('public static final class FrameCallbackQueue'):
            runtime.index('/**\n     * Capability handed', runtime.index(
                'public static final class FrameCallbackQueue'))
        ]

        self.assertIn(
            'implements PluginsController.RuntimeCallbackHolder', queue)
        self.assertIn('MAX_PENDING_CALLBACKS = 24', queue)
        self.assertIn('pendingCallbacks.remove(key);', queue)
        self.assertIn(
            'pendingCallbacks.size() >= MAX_PENDING_CALLBACKS', queue)
        self.assertIn('iterator.remove();', queue)
        self.assertIn('MAIN_HANDLER.post(drainRunnable)', queue)
        self.assertIn(
            'getPluginRuntimeTaskDecision(runtimeToken)', queue)
        self.assertIn('runtimeToken.equals(expectedRuntime)', queue)
        self.assertIn('pendingCallbacks.clear();', queue)
        self.assertIn('void revokePluginRuntime()', queue)
        self.assertIn('unregisterRuntimeCallbackHolder(runtimeToken, this)',
                      queue)

    def test_publication_uses_recording_canvas_and_bypasses_observer_loop(self):
        view = read_java('UniversalView.java')
        frame = read_java('UniversalFrameLayout.java')

        for source in (view, frame):
            self.assertIn('picture.beginRecording(', source)
            self.assertIn('recordingCanvas', source)
            self.assertIn('private static final class DrawState', source)
            draw_state = source[
                source.index('private static final class DrawState'):
                source.index('private static final class', source.index(
                    'private static final class DrawState') + 1)
            ]
            self.assertIn('final Picture picture;', draw_state)
            self.assertIn('final long revision;', draw_state)
            self.assertIn('super.invalidate();', source)

        self.assertIn('ownedQueue.clear(ownedToken);', view)
        self.assertIn('ownedQueue.clear(ownedToken);', frame)
        self.assertIn('expectedRuntime.equals(this.delegateRuntimeToken)',
                      view)
        self.assertIn('expectedRuntime.equals(this.listenerRuntimeToken)',
                      frame)
        self.assertIn(
            'publishing one state schedules exactly one', view)
        self.assertNotIn(
            'drawRevision++;\n            super.invalidate();',
            view[view.index('private void scheduleDrawCallback()'):])

if __name__ == '__main__':
    unittest.main()
