package app.nimarkogram.messenger.plugins.pip;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;

public final class PipStagedReplacementTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("pip-staged-replacement-test");
        try {
            Path target = Files.createDirectory(root.resolve("package-extract"));
            Files.write(target.resolve("version.txt"), "old".getBytes(StandardCharsets.UTF_8));
            Path staged = Files.createDirectory(root.resolve("package-stage"));
            Files.write(staged.resolve("version.txt"), "new".getBytes(StandardCharsets.UTF_8));

            Object replacement = replacement(staged, target, "rollback");
            require(readUtf8(target.resolve("version.txt")).equals("old"),
                    "staging must not alter the live extraction");
            invoke(replacement, "commit");
            require(readUtf8(target.resolve("version.txt")).equals("new"),
                    "commit must publish the staged extraction");
            require(Boolean.TRUE.equals(invoke(replacement, "rollback")),
                    "rollback must report a verified restore");
            require(readUtf8(target.resolve("version.txt")).equals("old"),
                    "rollback must restore the old extraction");

            Path deleteFailureTarget = Files.createDirectory(root.resolve("delete-failure-target"));
            Files.write(deleteFailureTarget.resolve("version.txt"),
                    "old-delete".getBytes(StandardCharsets.UTF_8));
            Path deleteFailureStage = Files.createDirectory(root.resolve("delete-failure-stage"));
            Files.write(deleteFailureStage.resolve("version.txt"),
                    "new-delete".getBytes(StandardCharsets.UTF_8));
            File refusingStage = new RefusingDeleteFile(deleteFailureStage.toFile());
            Object deleteFailure = replacement(
                    refusingStage, deleteFailureTarget.toFile(), "delete-failure");
            invoke(deleteFailure, "commit");
            require(Boolean.FALSE.equals(invoke(deleteFailure, "rollback")),
                    "rollback must report a parked replacement deletion failure");
            Path deleteFailureBackup = root.resolve(
                    ".delete-failure-target.delete-failure.backup");
            require(Files.isDirectory(deleteFailureBackup),
                    "failed deletion must retain an explicit recovery backup");
            require(readUtf8(deleteFailureBackup.resolve("version.txt")).equals("old-delete"),
                    "failed deletion must not consume the only old-owner backup");

            Path restoreFailureTarget = Files.createDirectory(root.resolve("restore-failure-target"));
            Files.write(restoreFailureTarget.resolve("version.txt"),
                    "old-restore".getBytes(StandardCharsets.UTF_8));
            Path restoreFailureStage = Files.createDirectory(root.resolve("restore-failure-stage"));
            Files.write(restoreFailureStage.resolve("version.txt"),
                    "new-restore".getBytes(StandardCharsets.UTF_8));
            File sabotagingStage = new RestoreBlockingDeleteFile(
                    restoreFailureStage.toFile(), restoreFailureTarget.toFile());
            Object restoreFailure = replacement(
                    sabotagingStage, restoreFailureTarget.toFile(), "restore-failure");
            invoke(restoreFailure, "commit");
            require(Boolean.FALSE.equals(invoke(restoreFailure, "rollback")),
                    "rollback must report a blocked backup restore");
            Path restoreFailureBackup = root.resolve(
                    ".restore-failure-target.restore-failure.backup");
            require(Files.isDirectory(restoreFailureBackup),
                    "failed restore must retain an explicit recovery backup");
            require(readUtf8(restoreFailureBackup.resolve("version.txt")).equals("old-restore"),
                    "failed restore must leave the old owner recoverable");

            Path stagedAgain = Files.createDirectory(root.resolve("package-stage-2"));
            Files.write(stagedAgain.resolve("version.txt"), "newer".getBytes(StandardCharsets.UTF_8));
            Object committed = replacement(stagedAgain, target, "finish");
            invoke(committed, "commit");
            invoke(committed, "finish");
            require(readUtf8(target.resolve("version.txt")).equals("newer"),
                    "finish must retain the committed extraction");
            require(!Files.exists(root.resolve(".package-extract.finish.backup")),
                    "finish must remove the old extraction backup");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                });
            }
        }
    }

    private static Object replacement(Path staged, Path target, String transactionId)
            throws Exception {
        return replacement(staged.toFile(), target.toFile(), transactionId);
    }

    private static Object replacement(File staged, File target, String transactionId)
            throws Exception {
        Class<?> type = Class.forName(
                "app.nimarkogram.messenger.plugins.pip.PipController$StagedReplacement");
        Constructor<?> constructor = type.getDeclaredConstructor(
                java.io.File.class, java.io.File.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(staged, target, transactionId);
    }

    private static Object invoke(Object replacement, String methodName) throws Exception {
        Method method = replacement.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return method.invoke(replacement);
    }

    private static String readUtf8(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static class RefusingDeleteFile extends File {
        RefusingDeleteFile(File path) {
            super(path.getAbsolutePath());
        }

        @Override
        public boolean delete() {
            return false;
        }
    }

    private static final class RestoreBlockingDeleteFile extends File {
        private final File target;

        RestoreBlockingDeleteFile(File path, File target) {
            super(path.getAbsolutePath());
            this.target = target;
        }

        @Override
        public boolean delete() {
            boolean deleted = super.delete();
            if (deleted) {
                try {
                    if (!target.createNewFile() && !target.exists()) {
                        throw new IOException("could not occupy " + target);
                    }
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }
            return deleted;
        }
    }

    private PipStagedReplacementTest() {}
}
