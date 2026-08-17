package app.nimarkogram.messenger.plugins.pip;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class PipRegistryChecksumTest {
    public static void main(String[] args) throws Exception {
        String hash = repeat('a', 64);
        String escapedRequirement =
                "demo" + "\\" + "u003e"
                        + "\\" + "u003d1";
        String canonical = "{\"schema\":2,\"ownership\":{\"alpha\":"
                + "{\"demo\":[\"" + escapedRequirement
                + "\"]}},\"roots\":[{"
                + "\"distribution\":\"demo\",\"version\":\"1.0\","
                + "\"root\":\"site/demo-1.0\","
                + "\"wheel\":\"wheels/demo-1.0.whl\","
                + "\"sha256\":\"" + hash + "\","
                + "\"importRoots\":[\"demo\"]}]}";
        String serialized = canonical.substring(
                0, canonical.length() - 1)
                + ",\"checksum\":\"ignored\"}";

        String expected = sha256(canonical);
        String actual =
                PipController.registryChecksumForTest(serialized);
        require(expected.equals(actual),
                "registry checksum must hash Gson's checksum-omitted payload");
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(
                digest.length * 2);
        for (byte item : digest) {
            result.append(String.format("%02x", item & 0xff));
        }
        return result.toString();
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }

    private static void require(
            boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private PipRegistryChecksumTest() {
    }
}
