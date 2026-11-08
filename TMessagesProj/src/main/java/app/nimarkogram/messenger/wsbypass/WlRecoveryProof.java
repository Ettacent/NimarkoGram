/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.wsbypass;

import android.util.Base64;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;

final class WlRecoveryProof {
    static final String AUTHORITY = "https://bypass.nimarko.org:15435";
    static final String BASE = "/api/v1/banners/wl/recovery/v1";
    static final long GRANT_TTL = 30L * 86400;
    private static final BigInteger P256_ORDER = new BigInteger(
            "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16);

    static final class Challenge {
        final long uid, expires, serverTime, revision, generation;
        final String keyId, clientNonce, purpose, id, nonce;

        Challenge(JSONObject json, long uid, String keyId, String clientNonce, String purpose) throws Exception {
            subject(json, uid, keyId, purpose);
            if (!AUTHORITY.equals(string(json, "audience"))
                    || !clientNonce.equals(string(json, "client_nonce"))) throw invalid();
            this.uid = uid;
            this.keyId = keyId;
            this.clientNonce = clientNonce;
            this.purpose = purpose;
            id = string(json, "challenge_id");
            nonce = string(json, "server_nonce");
            encoded(id, 32);
            encoded(nonce, 32);
            serverTime = serverTime(json);
            expires = integer(json, "expires");
            revision = integer(json, "approval_revision");
            generation = integer(json, "device_generation");
            if (expires <= serverTime || expires > serverTime + 300
                    || revision < 0 || generation < 0) throw invalid();
        }
    }

    static String publicKey(PublicKey key) throws Exception {
        if (!(key instanceof ECPublicKey)) throw invalid();
        ECPublicKey ec = (ECPublicKey) key;
        if (ec.getParams() == null || ec.getParams().getCurve().getField().getFieldSize() != 256
                || !P256_ORDER.equals(ec.getParams().getOrder())) throw invalid();
        byte[] der = key.getEncoded();
        if (der == null || der.length > 192) throw invalid();
        return encode(der);
    }

    static String keyId(String publicKey) throws Exception {
        if (publicKey == null || publicKey.length() > 256) throw invalid();
        byte[] der = Base64.decode(publicKey, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        if (!encode(der).equals(publicKey) || der.length == 0 || der.length > 192) throw invalid();
        return hash(der);
    }

    static JSONObject start(long uid, String publicKey, String clientNonce, String purpose, PrivateKey key) throws Exception {
        return new JSONObject().put("uid", uid).put("public_key", publicKey)
                .put("client_nonce", clientNonce).put("purpose", purpose)
                .put("signature", sign(key, startMessage(uid, keyId(publicKey), clientNonce, purpose)));
    }

    static byte[] startMessage(long uid, String keyId, String clientNonce, String purpose) throws Exception {
        identifiers(uid, keyId, clientNonce, purpose);
        return ("nimarko-wl-recovery-start-v1\n" + AUTHORITY + "\nPOST\n" + BASE
                + "/challenge\n" + uid + "\n" + keyId + "\n" + clientNonce + "\n" + purpose)
                .getBytes(StandardCharsets.US_ASCII);
    }

    static JSONObject complete(Challenge challenge, String publicKey, String priorToken, PrivateKey key) throws Exception {
        if (!challenge.keyId.equals(keyId(publicKey))) throw invalid();
        return new JSONObject().put("uid", challenge.uid).put("public_key", publicKey)
                .put("client_nonce", challenge.clientNonce).put("purpose", challenge.purpose)
                .put("challenge_id", challenge.id).put("prior_token", priorToken)
                .put("signature", sign(key, completeMessage(challenge, priorToken)));
    }

    static byte[] completeMessage(Challenge c, String priorToken) throws Exception {
        identifiers(c.uid, c.keyId, c.clientNonce, c.purpose);
        if (priorToken == null || !priorToken.isEmpty() && !token(priorToken)) throw invalid();
        return ("nimarko-wl-recovery-complete-v1\n" + AUTHORITY + "\nPOST\n" + BASE
                + "/complete\n" + c.uid + "\n" + c.keyId + "\n" + c.clientNonce + "\n"
                + c.purpose + "\n" + c.id + "\n" + c.nonce + "\n" + c.expires + "\n"
                + c.revision + "\n" + c.generation + "\n"
                + hash(priorToken.getBytes(StandardCharsets.US_ASCII))).getBytes(StandardCharsets.US_ASCII);
    }

    static void result(JSONObject json, long uid, String keyId, String purpose) throws Exception {
        subject(json, uid, keyId, purpose);
        long now = serverTime(json);
        String status = string(json, "status");
        if ("forget".equals(purpose)) {
            if (!"forgotten".equals(status) || json.has("token") || json.has("expires")) throw invalid();
            return;
        }
        if ("approved".equals(status)) {
            long expires = integer(json, "expires");
            long paid = integer(json, "expires_at");
            if (!token(string(json, "token")) || paid <= now || expires <= now
                    || expires > paid || expires > now + GRANT_TTL) throw invalid();
        } else if ("expired".equals(status) || "payment_required".equals(status)
                || "blocked".equals(status) || "revoked".equals(status)) {
            if (json.has("token") || json.has("expires")) throw invalid();
            if (json.has("expires_at") && json.opt("expires_at") != JSONObject.NULL
                    && integer(json, "expires_at") < 0) throw invalid();
        } else throw invalid();
    }

    static long serverTime(JSONObject json) throws Exception {
        long value = integer(json, "server_time");
        if (value < 946684800L || value > 4102444800L) throw invalid();
        return value;
    }

    static long integer(JSONObject json, String field) throws Exception {
        Object value = json.opt(field);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw invalid();
        return ((Number) value).longValue();
    }

    static String string(JSONObject json, String field) throws Exception {
        Object value = json.opt(field);
        if (!(value instanceof String)) throw invalid();
        return (String) value;
    }

    static boolean token(String value) {
        try { encoded(value, 32); return true; }
        catch (Exception ignored) { return false; }
    }

    static JSONObject parse(String body) throws Exception {
        if (body == null || body.length() > 8192) throw invalid();
        JSONTokener reader = new JSONTokener(body);
        if (reader.nextClean() != '{') throw invalid();
        JSONObject result = new JSONObject();
        char next = reader.nextClean();
        if (next != '}') {
            reader.back();
            while (true) {
                if (reader.nextClean() != '"') throw invalid();
                reader.back();
                Object name = reader.nextValue();
                if (!(name instanceof String) || ((String) name).length() > 64 || result.has((String) name)
                        || result.length() >= 32 || reader.nextClean() != ':') throw invalid();
                Object value;
                char first = reader.nextClean();
                if (first == '"') {
                    reader.back();
                    value = reader.nextValue();
                } else {
                    StringBuilder raw = new StringBuilder();
                    while (first != ',' && first != '}' && first != 0) {
                        raw.append(first);
                        first = reader.next();
                    }
                    if (first != 0) reader.back();
                    String scalar = raw.toString().trim();
                    if ("null".equals(scalar)) value = JSONObject.NULL;
                    else if ("true".equals(scalar)) value = Boolean.TRUE;
                    else if ("false".equals(scalar)) value = Boolean.FALSE;
                    else {
                        if (!scalar.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw invalid();
                        value = new JSONTokener(scalar).nextValue();
                        if (!(value instanceof Number)) throw invalid();
                    }
                }
                if (value instanceof String && ((String) value).length() > 4096) throw invalid();
                result.put((String) name, value);
                next = reader.nextClean();
                if (next == '}') break;
                if (next != ',') throw invalid();
            }
        }
        if (reader.nextClean() != 0) throw invalid();
        return result;
    }

    private static void subject(JSONObject json, long uid, String keyId, String purpose) throws Exception {
        if (integer(json, "protocol") != 1 || integer(json, "uid") != uid
                || !keyId.equals(string(json, "key_id")) || !purpose.equals(string(json, "purpose"))) throw invalid();
    }

    private static void identifiers(long uid, String keyId, String nonce, String purpose) throws Exception {
        if (uid <= 0 || keyId == null || !keyId.matches("[0-9a-f]{64}")
                || !("recover".equals(purpose) || "forget".equals(purpose))) throw invalid();
        encoded(nonce, 24);
    }

    private static void encoded(String value, int bytes) throws Exception {
        if (value == null || value.length() != (bytes * 8 + 5) / 6 || !value.matches("[A-Za-z0-9_-]+")) throw invalid();
        byte[] decoded = Base64.decode(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        if (decoded.length != bytes || !encode(decoded).equals(value)) throw invalid();
    }

    private static String sign(PrivateKey key, byte[] data) throws Exception {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(key);
        signature.update(data);
        return encode(signature.sign());
    }

    private static String hash(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out = new StringBuilder(64);
        for (byte value : digest) out.append(Character.forDigit((value & 255) >>> 4, 16)).append(Character.forDigit(value & 15, 16));
        return out.toString();
    }

    private static String encode(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static IOException invalid() { return new IOException("invalid_recovery_response"); }
    private WlRecoveryProof() { }
}
