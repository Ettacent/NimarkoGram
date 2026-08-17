package app.nimarkogram.messenger.wsbypass;

import org.telegram.messenger.FileLog;

import java.security.MessageDigest;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class CryptoCtx {

    private static final byte[] ZERO_64 = new byte[64];

    public final Cipher cltDec;
    public final Cipher cltEnc;
    public final Cipher tgEnc;
    public final Cipher tgDec;

    private CryptoCtx(Cipher cltDec, Cipher cltEnc, Cipher tgEnc, Cipher tgDec) {
        this.cltDec = cltDec;
        this.cltEnc = cltEnc;
        this.tgEnc = tgEnc;
        this.tgDec = tgDec;
    }

    public static CryptoCtx build(byte[] clientDecPrekeyIv, byte[] secret, byte[] relayInit) {
        try {
            if (clientDecPrekeyIv == null || secret == null || relayInit == null) return null;
            final int PREKEY_LEN = MtprotoHandshake.PREKEY_LEN;
            final int IV_LEN = MtprotoHandshake.IV_LEN;
            final int SKIP_LEN = MtprotoHandshake.SKIP_LEN;

            if (clientDecPrekeyIv.length < PREKEY_LEN + IV_LEN) return null;
            if (relayInit.length < SKIP_LEN + PREKEY_LEN + IV_LEN) return null;

            byte[] cltDecPrekey = new byte[PREKEY_LEN];
            System.arraycopy(clientDecPrekeyIv, 0, cltDecPrekey, 0, PREKEY_LEN);
            byte[] cltDecIv = new byte[IV_LEN];
            System.arraycopy(clientDecPrekeyIv, PREKEY_LEN, cltDecIv, 0, IV_LEN);
            byte[] cltDecKey = sha256(cltDecPrekey, secret);
            Cipher cltDec = newCipher(cltDecKey, cltDecIv);
            cltDec.update(ZERO_64);

            byte[] cltEncPrekeyIv = reverse(clientDecPrekeyIv, PREKEY_LEN + IV_LEN);
            byte[] cltEncPrekey = new byte[PREKEY_LEN];
            System.arraycopy(cltEncPrekeyIv, 0, cltEncPrekey, 0, PREKEY_LEN);
            byte[] cltEncIv = new byte[IV_LEN];
            System.arraycopy(cltEncPrekeyIv, PREKEY_LEN, cltEncIv, 0, IV_LEN);
            byte[] cltEncKey = sha256(cltEncPrekey, secret);
            Cipher cltEnc = newCipher(cltEncKey, cltEncIv);

            byte[] relayEncKey = new byte[PREKEY_LEN];
            System.arraycopy(relayInit, SKIP_LEN, relayEncKey, 0, PREKEY_LEN);
            byte[] relayEncIv = new byte[IV_LEN];
            System.arraycopy(relayInit, SKIP_LEN + PREKEY_LEN, relayEncIv, 0, IV_LEN);
            Cipher tgEnc = newCipher(relayEncKey, relayEncIv);
            tgEnc.update(ZERO_64);

            byte[] relayDecChunk = new byte[PREKEY_LEN + IV_LEN];
            System.arraycopy(relayInit, SKIP_LEN, relayDecChunk, 0, PREKEY_LEN + IV_LEN);
            byte[] relayDecPrekeyIv = reverse(relayDecChunk, PREKEY_LEN + IV_LEN);
            byte[] relayDecKey = new byte[PREKEY_LEN];
            System.arraycopy(relayDecPrekeyIv, 0, relayDecKey, 0, PREKEY_LEN);
            byte[] relayDecIv = new byte[IV_LEN];
            System.arraycopy(relayDecPrekeyIv, PREKEY_LEN, relayDecIv, 0, IV_LEN);
            Cipher tgDec = newCipher(relayDecKey, relayDecIv);

            return new CryptoCtx(cltDec, cltEnc, tgEnc, tgDec);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static byte[] sha256(byte[] a, byte[] b) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(a);
        md.update(b);
        return md.digest();
    }

    private static byte[] reverse(byte[] src, int len) {
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) out[i] = src[len - 1 - i];
        return out;
    }

    private static Cipher newCipher(byte[] key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher;
    }
}
