package app.nimarkogram.messenger.plugins.pip;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class PipRestartRequiredTest {
    public static void main(String[] args) throws Exception {
        PipController controller = PipController.getInstance();
        require(!controller.requiresProcessRestart(),
                "a fresh host process must not start with PIP restart debt");

        Method enter = PipController.class
                .getDeclaredMethod("enterPipMutation");
        Method exit = PipController.class
                .getDeclaredMethod("exitPipMutation");
        enter.setAccessible(true);
        exit.setAccessible(true);
        enter.invoke(null);

        Method restartRequired = PipController.class
                .getDeclaredMethod("restartRequired", String.class);
        restartRequired.setAccessible(true);
        Object failure = restartRequired.invoke(
                null, "injected regression check");
        require(failure instanceof
                        PipController.RestartRequiredRuntimeException,
                "restartRequired must return the dedicated exception");
        require(controller.requiresProcessRestart(),
                "restart debt must remain visible after the first failure");

        enter.invoke(null);
        exit.invoke(null);
        exit.invoke(null);

        try {
            enter.invoke(null);
            throw new AssertionError(
                    "a new mutation must fail before it starts");
        } catch (InvocationTargetException expected) {
            require(expected.getCause() instanceof
                            PipController.RestartRequiredRuntimeException,
                    "fail-fast must preserve restart-required semantics");
        }
        require(controller.requiresProcessRestart(),
                "a failed retry must not clear restart debt");
    }

    private static void require(
            boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private PipRestartRequiredTest() {
    }
}
