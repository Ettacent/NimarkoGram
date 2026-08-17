package app.nimarkogram.messenger.wsbypass;

import org.telegram.messenger.FileLog;

import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class MsgSplitter {

    private static final int MAX_BUF = 16 * 1024 * 1024;

    private static final int PROTO_ABRIDGED     = 0xEFEFEFEF;
    private static final int PROTO_INTERMEDIATE = 0xEEEEEEEE;
    private static final int PROTO_INTERMEDIATE_PADDED = 0xDDDDDDDD;

    private final Cipher cipher;
    private final int proto;

    private final ByteArrayOutputStream cipherBuf = new ByteArrayOutputStream();
    private final ByteArrayOutputStream plainBuf  = new ByteArrayOutputStream();

    private boolean disabled = false;

    public MsgSplitter(byte[] initData, int protoInt) throws GeneralSecurityException {
        byte[] raw = initData != null ? initData : new byte[0];
        if (raw.length < 56) {
            throw new GeneralSecurityException("initData too short: " + raw.length);
        }
        byte[] key = Arrays.copyOfRange(raw, 8, 40);
        byte[] iv  = Arrays.copyOfRange(raw, 40, 56);
        Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        try {
            
            c.update(new byte[64]);
        } catch (Exception ignored) {
        }
        this.cipher = c;
        this.proto = protoInt;
    }

    public List<byte[]> split(byte[] chunk) {
        if (chunk == null || chunk.length == 0) {
            return Collections.emptyList();
        }
        if (disabled) {
            return Collections.singletonList(chunk);
        }
        try {
            cipherBuf.write(chunk);
            byte[] plain = cipher.update(chunk);
            if (plain != null && plain.length > 0) {
                plainBuf.write(plain);
            }
        } catch (Exception e) {
            FileLog.e(e);
            return Collections.singletonList(chunk);
        }
        
        byte[] cArr = cipherBuf.toByteArray();
        byte[] pArr = plainBuf.toByteArray();
        List<byte[]> parts = new ArrayList<>();
        int offset = 0;
        while (offset < cArr.length) {
            Integer packetLen = nextPacketLen(pArr, offset);
            if (packetLen == null) {
                if (cArr.length - offset > MAX_BUF) {
                    
                    parts.add(Arrays.copyOfRange(cArr, offset, cArr.length));
                    offset = cArr.length;
                    disabled = true;
                }
                break;
            }
            int len = packetLen;
            if (len <= 0 || cArr.length - offset < len) {
                if (len <= 0) {
                    parts.add(Arrays.copyOfRange(cArr, offset, cArr.length));
                    offset = cArr.length;
                    disabled = true;
                }
                break;
            }
            parts.add(Arrays.copyOfRange(cArr, offset, offset + len));
            offset += len;
        }
        
        if (offset > 0) {
            cipherBuf.reset();
            cipherBuf.write(cArr, offset, cArr.length - offset);
            plainBuf.reset();
            plainBuf.write(pArr, offset, pArr.length - offset);
        }
        return parts;
    }

    public int pendingBytes() {
        return cipherBuf.size();
    }

    public boolean isDisabled() {
        return disabled;
    }

    public List<byte[]> flush() {
        if (cipherBuf.size() == 0) {
            return Collections.emptyList();
        }
        byte[] tail = cipherBuf.toByteArray();
        cipherBuf.reset();
        plainBuf.reset();
        return Collections.singletonList(tail);
    }

    private Integer nextPacketLen(byte[] p, int off) {
        if (off >= p.length) {
            return null;
        }
        if (proto == PROTO_ABRIDGED) {
            return nextAbridgedLen(p, off);
        }
        if (proto == PROTO_INTERMEDIATE || proto == PROTO_INTERMEDIATE_PADDED) {
            return nextIntermediateLen(p, off);
        }
        return 0;
    }

    private Integer nextAbridgedLen(byte[] p, int off) {
        int avail = p.length - off;
        int first = p[off] & 0xFF;
        int payloadLen;
        int headerLen;
        if (first == 0x7F || first == 0xFF) {
            if (avail < 4) {
                return null;
            }
            payloadLen = ((p[off + 1] & 0xFF) | ((p[off + 2] & 0xFF) << 8) | ((p[off + 3] & 0xFF) << 16)) * 4;
            headerLen = 4;
        } else {
            payloadLen = (first & 0x7F) * 4;
            headerLen = 1;
        }
        if (payloadLen <= 0) {
            return 0;
        }
        int packetLen = headerLen + payloadLen;
        if (avail < packetLen) {
            return null;
        }
        return packetLen;
    }

    private Integer nextIntermediateLen(byte[] p, int off) {
        int avail = p.length - off;
        if (avail < 4) {
            return null;
        }
        int payloadLen = ((p[off] & 0xFF)
                | ((p[off + 1] & 0xFF) << 8)
                | ((p[off + 2] & 0xFF) << 16)
                | ((p[off + 3] & 0xFF) << 24)) & 0x7FFFFFFF;
        if (payloadLen <= 0) {
            return 0;
        }
        int packetLen = 4 + payloadLen;
        if (avail < packetLen) {
            return null;
        }
        return packetLen;
    }
}
