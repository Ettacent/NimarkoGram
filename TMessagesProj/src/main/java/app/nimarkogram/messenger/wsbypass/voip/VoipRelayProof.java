/* Modifications Copyright (C) 2026 Ettacent */
package app.nimarkogram.messenger.wsbypass.voip;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class VoipRelayProof {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] REQUEST_DOMAIN = "nimarko-voip-alloc-v3\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RESPONSE_DOMAIN = "nimarko-voip-reply-v3\0".getBytes(StandardCharsets.US_ASCII);

    static byte[] request(String ip, int port, long expiry, long uid, byte[] credential, long nowMillis) {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        return request(ip, port, expiry, uid, credential, nowMillis, nonce);
    }

    static byte[] request(String ip, int port, long expiry, long uid, byte[] credential, long nowMillis, byte[] nonce) {
        try {
            if (ip == null || port < 1 || port > 65535 || uid <= 0 || expiry <= nowMillis / 1000
                    || credential == null || credential.length != 32 || nonce == null || nonce.length != 16) return null;
            String[] octets = ip.split("\\.", -1);
            if (octets.length != 4) return null;
            ByteBuffer out = ByteBuffer.allocate(80);
            out.put((byte) 0xc1).put((byte) 3).putLong(nowMillis);
            for (String octet : octets) {
                if (!octet.matches("[0-9]{1,3}")) return null;
                int value = Integer.parseInt(octet);
                if (value > 255) return null;
                out.put((byte) value);
            }
            out.putShort((short) port).putLong(expiry).putLong(uid).put(nonce);
            out.put(mac(credential, REQUEST_DOMAIN, Arrays.copyOf(out.array(), 48)));
            return out.array();
        } catch (Exception ignored) {
            return null;
        }
    }

    static int[] reply(byte[] response, int length, byte[] request, byte[] credential) {
        try {
            if (length != 52 || response == null || response.length < length || request == null
                    || request.length != 80 || credential == null || credential.length != 32
                    || (response[0] & 255) != 0xc1) return null;
            if (!MessageDigest.isEqual(Arrays.copyOfRange(request, 32, 48), Arrays.copyOfRange(response, 4, 20))) return null;
            byte[] expected = mac(credential, RESPONSE_DOMAIN, MessageDigest.getInstance("SHA-256").digest(request),
                    Arrays.copyOf(response, 20));
            if (!MessageDigest.isEqual(expected, Arrays.copyOfRange(response, 20, 52))) return null;
            int status = response[1] & 255;
            int port = ((response[2] & 255) << 8) | (response[3] & 255);
            if (status > 4 || status == 0 && (port < 20000 || port > 29999) || status != 0 && port != 0) return null;
            return new int[]{status, port};
        } catch (Exception ignored) {
            return null;
        }
    }

    private static byte[] mac(byte[] key, byte[]... values) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        for (byte[] value : values) mac.update(value);
        return mac.doFinal();
    }
}
