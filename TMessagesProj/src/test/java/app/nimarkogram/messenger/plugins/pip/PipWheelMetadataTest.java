package app.nimarkogram.messenger.plugins.pip;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class PipWheelMetadataTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(
                "pip-wheel-metadata-test");
        try {
            Path valid = root.resolve(
                    "demo-1.0-py3-none-any.whl");
            writeWheel(valid, "demo\n", false);
            Object info = inspect(valid.toFile());
            require(field(info, "requiresDist", List.class)
                            .equals(List.of("child (>=1.0)")),
                    "Requires-Dist must come from the selected wheel");
            require(field(info, "importRoots", Set.class)
                            .equals(Set.of("demo", "extra")),
                    "RECORD roots must not be hidden by top_level.txt");

            Path invalidHint = root.resolve(
                    "bad-1.0-py3-none-any.whl");
            writeWheel(invalidHint, "demo\nghost\n", true);
            try {
                inspect(invalidHint.toFile());
                throw new AssertionError(
                        "an unproved top_level hint must fail closed");
            } catch (InvocationTargetException expected) {
                require(expected.getCause() instanceof IOException,
                        "invalid top_level must surface as IOException");
            }

            Method register = PipController.class.getDeclaredMethod(
                    "registerImportRootOwnership",
                    Map.class, String.class, Set.class);
            register.setAccessible(true);
            Map<String, String> owners = new LinkedHashMap<>();
            register.invoke(
                    null, owners, "dist-a", Set.of("common"));
            try {
                register.invoke(
                        null, owners, "dist-b",
                        Set.of("common"));
                throw new AssertionError(
                        "cross-distribution import collision "
                                + "must fail closed");
            } catch (InvocationTargetException expected) {
                require(expected.getCause() instanceof IOException,
                        "collision must surface as IOException");
            }
        } finally {
            try (java.util.stream.Stream<Path> paths =
                    Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (Exception ignored) {
                            }
                        });
            }
        }
    }

    private static Object inspect(File wheel) throws Exception {
        Method inspect = PipController.class.getDeclaredMethod(
                "inspectPureWheel", File.class);
        inspect.setAccessible(true);
        return inspect.invoke(
                PipController.getInstance(), wheel);
    }

    private static <T> T field(
            Object source, String name, Class<T> type)
            throws Exception {
        Field field = source.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(source));
    }

    private static void writeWheel(
            Path target, String topLevel,
            boolean omitExtraFromRecord) throws Exception {
        try (ZipOutputStream output = new ZipOutputStream(
                Files.newOutputStream(target))) {
            write(output, "demo-1.0.dist-info/METADATA",
                    "Metadata-Version: 2.1\n"
                            + "Name: demo\n"
                            + "Version: 1.0\n"
                            + "Requires-Dist: child (>=1.0)\n\n");
            write(output, "demo-1.0.dist-info/WHEEL",
                    "Wheel-Version: 1.0\n"
                            + "Root-Is-Purelib: true\n"
                            + "Tag: py3-none-any\n");
            write(output, "demo-1.0.dist-info/top_level.txt",
                    topLevel);
            write(output, "demo/__init__.py", "");
            write(output, "extra.py", "");
            write(output, "demo-1.0.dist-info/RECORD",
                    "demo/__init__.py,,\n"
                            + (omitExtraFromRecord
                            ? "" : "extra.py,,\n")
                            + "demo-1.0.dist-info/METADATA,,\n"
                            + "demo-1.0.dist-info/WHEEL,,\n"
                            + "demo-1.0.dist-info/top_level.txt,,\n"
                            + "demo-1.0.dist-info/RECORD,,\n");
        }
    }

    private static void write(
            ZipOutputStream output, String name,
            String value) throws Exception {
        output.putNextEntry(new ZipEntry(name));
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private static void require(
            boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private PipWheelMetadataTest() {
    }
}
