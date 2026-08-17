package app.nimarkogram.messenger.plugins.pip;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Set;

public final class PipArtifactOwnershipTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("pip-ownership-test");
        try {
            Path foo = extraction(root, "foo-1.0.dist-info", "foo");
            Path fooBar = extraction(root, "foo_bar-1.0.dist-info", "foo-bar");
            Set<String> referenced = Set.of("foo");

            require(referenced.contains(PipController.packageNameFromArtifact(foo.toFile())),
                    "foo must remain owned by foo");
            require(!referenced.contains(PipController.packageNameFromArtifact(fooBar.toFile())),
                    "foo must not own foo-bar through a filename prefix");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                });
            }
        }
    }

    private static Path extraction(Path root, String distInfo, String packageName) throws Exception {
        Path extraction = Files.createDirectory(root.resolve(distInfo + "-extract"));
        Path metadataDir = Files.createDirectory(extraction.resolve(distInfo));
        Files.write(metadataDir.resolve("METADATA"),
                ("Metadata-Version: 2.1\nName: " + packageName + "\nVersion: 1.0\n\n")
                        .getBytes(StandardCharsets.UTF_8));
        return extraction;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private PipArtifactOwnershipTest() {}
}
