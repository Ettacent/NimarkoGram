package app.nimarkogram.messenger.wsbypass;

import org.telegram.messenger.FileLog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class MtprotoHandshake {

    public static final int HANDSHAKE_LEN = 64;
    public static final int SKIP_LEN = 8;
    public static final int PREKEY_LEN = 32;
    public static final int IV_LEN = 16;
    public static final int PROTO_TAG_POS = 56;
    public static final int DC_IDX_POS = 60;

    public static final byte[] PROTO_TAG_ABRIDGED     = { (byte) 0xEF, (byte) 0xEF, (byte) 0xEF, (byte) 0xEF };
    public static final byte[] PROTO_TAG_INTERMEDIATE = { (byte) 0xEE, (byte) 0xEE, (byte) 0xEE, (byte) 0xEE };
    public static final byte[] PROTO_TAG_SECURE       = { (byte) 0xDD, (byte) 0xDD, (byte) 0xDD, (byte) 0xDD };

    private static final Set<Integer> RESERVED_FIRST_BYTES;
    private static final Set<String> RESERVED_STARTS;

    static {
        RESERVED_FIRST_BYTES = new HashSet<>();
        RESERVED_FIRST_BYTES.add(0xEF);

        RESERVED_STARTS = new HashSet<>();
        RESERVED_STARTS.add(hex4(new byte[] { 'H', 'E', 'A', 'D' }));
        RESERVED_STARTS.add(hex4(new byte[] { 'P', 'O', 'S', 'T' }));
        RESERVED_STARTS.add(hex4(new byte[] { 'G', 'E', 'T', ' ' }));
        RESERVED_STARTS.add(hex4(PROTO_TAG_INTERMEDIATE));
        RESERVED_STARTS.add(hex4(PROTO_TAG_SECURE));
        RESERVED_STARTS.add(hex4(new byte[] { (byte) 0x16, (byte) 0x03, (byte) 0x01, (byte) 0x02 }));
    }

    private static final SecureRandom RNG = new SecureRandom();

    private MtprotoHandshake() {}

    public static final class HandshakeResult {
        public final int dcId;
        public final boolean isMedia;
        public final int protoInt;
        public final byte[] protoTag;
        public final byte[] decPrekeyIv;

        public HandshakeResult(int dcId, boolean isMedia, int protoInt, byte[] protoTag, byte[] decPrekeyIv) {
            this.dcId = dcId;
            this.isMedia = isMedia;
            this.protoInt = protoInt;
            this.protoTag = protoTag;
            this.decPrekeyIv = decPrekeyIv;
        }
    }

    public static HandshakeResult tryHandshake(byte[] handshake, byte[] secret) {
        try {
            if (handshake == null || secret == null) return null;
            if (handshake.length < HANDSHAKE_LEN || secret.length != 16) return null;

            byte[] decPrekeyIv = new byte[PREKEY_LEN + IV_LEN];
            System.arraycopy(handshake, SKIP_LEN, decPrekeyIv, 0, PREKEY_LEN + IV_LEN);

            byte[] decPrekey = new byte[PREKEY_LEN];
            System.arraycopy(decPrekeyIv, 0, decPrekey, 0, PREKEY_LEN);
            byte[] decIv = new byte[IV_LEN];
            System.arraycopy(decPrekeyIv, PREKEY_LEN, decIv, 0, IV_LEN);

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(decPrekey);
            md.update(secret);
            byte[] decKey = md.digest();

            Cipher dec = newCipher(decKey, decIv);
            byte[] block = new byte[HANDSHAKE_LEN];
            System.arraycopy(handshake, 0, block, 0, HANDSHAKE_LEN);
            byte[] decrypted = dec.update(block);
            if (decrypted == null || decrypted.length < HANDSHAKE_LEN) return null;

            byte[] protoTag = new byte[4];
            System.arraycopy(decrypted, PROTO_TAG_POS, protoTag, 0, 4);
            if (!equals(protoTag, PROTO_TAG_ABRIDGED)
                    && !equals(protoTag, PROTO_TAG_INTERMEDIATE)
                    && !equals(protoTag, PROTO_TAG_SECURE)) {
                return null;
            }

            short dcIdx = ByteBuffer.wrap(decrypted, DC_IDX_POS, 2).order(ByteOrder.LITTLE_ENDIAN).getShort();
            int dcId = Math.abs((int) dcIdx);
            if (dcId <= 0 || dcId > 1000) return null;

            int protoInt = ByteBuffer.wrap(protoTag).order(ByteOrder.LITTLE_ENDIAN).getInt();

            return new HandshakeResult(dcId, dcIdx < 0, protoInt, protoTag, decPrekeyIv);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    public static byte[] generateRelayInit(byte[] protoTag, int dcIdx) {
        try {
            byte[] proto = new byte[4];
            if (protoTag != null && protoTag.length >= 4) {
                System.arraycopy(protoTag, 0, proto, 0, 4);
            } else {
                System.arraycopy(PROTO_TAG_ABRIDGED, 0, proto, 0, 4);
            }

            byte[] rnd = new byte[HANDSHAKE_LEN];
            while (true) {
                RNG.nextBytes(rnd);
                if (RESERVED_FIRST_BYTES.contains(rnd[0] & 0xFF)) continue;
                byte[] first4 = new byte[4];
                System.arraycopy(rnd, 0, first4, 0, 4);
                if (RESERVED_STARTS.contains(hex4(first4))) continue;
                if (rnd[4] == 0 && rnd[5] == 0 && rnd[6] == 0 && rnd[7] == 0) continue;
                break;
            }

            byte[] encKey = new byte[PREKEY_LEN];
            System.arraycopy(rnd, SKIP_LEN, encKey, 0, PREKEY_LEN);
            byte[] encIv = new byte[IV_LEN];
            System.arraycopy(rnd, SKIP_LEN + PREKEY_LEN, encIv, 0, IV_LEN);

            Cipher enc = newCipher(encKey, encIv);
            byte[] encryptedFull = enc.update(rnd);
            if (encryptedFull == null || encryptedFull.length < HANDSHAKE_LEN) {
                return rnd;
            }

            byte[] keystreamTail = new byte[8];
            for (int i = 0; i < 8; i++) {
                keystreamTail[i] = (byte) (encryptedFull[56 + i] ^ rnd[56 + i]);
            }

            byte[] tailPlain = new byte[8];
            System.arraycopy(proto, 0, tailPlain, 0, 4);
            ByteBuffer.wrap(tailPlain, 4, 2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) dcIdx);
            byte[] pad = new byte[2];
            RNG.nextBytes(pad);
            tailPlain[6] = pad[0];
            tailPlain[7] = pad[1];

            byte[] result = new byte[HANDSHAKE_LEN];
            System.arraycopy(rnd, 0, result, 0, HANDSHAKE_LEN);
            for (int i = 0; i < 8; i++) {
                result[56 + i] = (byte) (tailPlain[i] ^ keystreamTail[i]);
            }
            return result;
        } catch (Exception e) {
            FileLog.e(e);
            byte[] fallback = new byte[HANDSHAKE_LEN];
            RNG.nextBytes(fallback);
            return fallback;
        }
    }

    public static String generateSecretHex() {
        byte[] r = new byte[16];
        RNG.nextBytes(r);
        return toHex(r);
    }

    public static String validSecretHex(String value) {
        try {
            if (value == null) return "";
            String s = value.trim().toLowerCase(Locale.ROOT);
            if (s.startsWith("dd") || s.startsWith("ee")) {
                if (s.length() >= 34) {
                    s = s.substring(2, 34);
                } else {
                    s = s.substring(2);
                }
            }
            if (s.length() != 32) return "";
            for (int i = 0; i < 32; i++) {
                char c = s.charAt(i);
                boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
                if (!ok) return "";
            }
            return s;
        } catch (Exception e) {
            return "";
        }
    }

    public static byte[] toBytes16(String hex) {
        if (hex == null || hex.length() != 32) return null;
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static Cipher newCipher(byte[] key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher;
    }

    private static boolean equals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }

    private static String hex4(byte[] b) {
        return String.format(Locale.ROOT, "%02x%02x%02x%02x", b[0] & 0xFF, b[1] & 0xFF, b[2] & 0xFF, b[3] & 0xFF);
    }

    private static String toHex(byte[] b) {
        char[] HEX = "0123456789abcdef".toCharArray();
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
