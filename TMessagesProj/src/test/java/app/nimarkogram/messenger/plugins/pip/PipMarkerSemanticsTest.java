package app.nimarkogram.messenger.plugins.pip;

import java.util.Collections;
import java.util.Set;

public final class PipMarkerSemanticsTest {
    public static void main(String[] args) {
        require(!PipController.markerAppliesForTest("extra != 'foo'", Set.of("foo")),
                "parent[foo] must not gain an empty/base extra context");
        require(PipController.markerAppliesForTest("extra == 'foo'", Set.of("foo")),
                "the selected foo context must be evaluated");
        require(PipController.markerAppliesForTest("extra != 'foo'", Collections.emptySet()),
                "a base install must still evaluate extra as empty");

        PipController.ParsedRequirement parenthesized =
                PipController.parseRequirement("demo (>=1.0, !=1.5)");
        require(parenthesized != null && parenthesized.specs.size() == 2,
                "legacy parenthesized Core Metadata must remain valid");
        require(PipController.compareSpec("1.0+local", "==", "1.0"),
                "a public == specifier must ignore the candidate local label");
        require(!PipController.compareSpec("1.0+local", "!=", "1.0"),
                "a public != specifier must reject the matching local build");
        require(PipController.compareSpec("1.0+local", "!=", "1.0+other"),
                "an explicit different local label must remain unequal");

        PipController.getInstance().setPythonVersion("3.11.9");
        require(PipController.markerAppliesForTest(
                        "python_full_version == '3.11.9'",
                        Collections.emptySet()),
                "python_full_version must include the runtime micro version");
        require(PipController.markerAppliesForTest(
                        "python_version == '3.11'",
                        Collections.emptySet()),
                "python_version must remain major.minor");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private PipMarkerSemanticsTest() {}
}
