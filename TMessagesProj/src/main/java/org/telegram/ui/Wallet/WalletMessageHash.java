/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Wallet;

import org.telegram.messenger.Utilities;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

final class WalletMessageHash {

    private static final int MAX_REFS = 4;
    private static final int NORMALIZED_BITS = 2 + 2 + 267 + 4 + 1 + 1;

    private WalletMessageHash() {
    }

    static String normalized(byte[] boc) {
        try {
            ByteBuffer in = ByteBuffer.wrap(boc);
            if (in.getInt() != 0xb5ee9c72) return null;
            final int flags = in.get() & 0xff;
            final int refSize = flags & 7;
            final int offsetSize = in.get() & 0xff;
            if (refSize < 1 || refSize > 4 || offsetSize < 1 || offsetSize > 8) return null;
            final int count = (int) uint(in, refSize);
            final long roots = uint(in, refSize);
            uint(in, refSize);
            uint(in, offsetSize);
            if (roots != 1 || count < 1 || count > boc.length) return null;
            final int root = (int) uint(in, refSize);
            if ((flags & 0x80) != 0) in.position(in.position() + count * offsetSize);

            final byte[][] data = new byte[count][];
            final int[][] refs = new int[count][];
            final int[] descriptor = new int[count];
            for (int i = 0; i < count; i++) {
                final int d1 = in.get() & 0xff;
                final int d2 = in.get() & 0xff;
                if (d1 > MAX_REFS) return null;
                descriptor[i] = d2;
                data[i] = new byte[(d2 + 1) / 2];
                in.get(data[i]);
                refs[i] = new int[d1];
                for (int r = 0; r < d1; r++) {
                    refs[i][r] = (int) uint(in, refSize);
                    if (refs[i][r] <= i || refs[i][r] >= count) return null;
                }
            }

            final int[] depth = new int[count];
            final byte[][] hash = new byte[count][];
            for (int i = count - 1; i >= 0; i--) {
                final MessageDigest sha = MessageDigest.getInstance("SHA-256");
                sha.update((byte) refs[i].length);
                sha.update((byte) descriptor[i]);
                sha.update(data[i]);
                for (int ref : refs[i]) {
                    depth[i] = Math.max(depth[i], depth[ref] + 1);
                    sha.update((byte) (depth[ref] >> 8));
                    sha.update((byte) depth[ref]);
                }
                for (int ref : refs[i]) sha.update(hash[ref]);
                hash[i] = sha.digest();
            }

            final int body = bodyOf(data[root], refs[root]);
            if (body < 0) return null;

            final byte[] normalized = new byte[(NORMALIZED_BITS + 8) / 8];
            setBit(normalized, 0);
            for (int i = 0; i < 267; i++) {
                if (bit(data[root], 4 + i)) setBit(normalized, 4 + i);
            }
            setBit(normalized, NORMALIZED_BITS - 1);
            setBit(normalized, NORMALIZED_BITS);
            final MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update((byte) 1);
            sha.update((byte) (NORMALIZED_BITS / 8 + (NORMALIZED_BITS + 7) / 8));
            sha.update(normalized);
            sha.update((byte) (depth[body] >> 8));
            sha.update((byte) depth[body]);
            sha.update(hash[body]);
            return Utilities.bytesToHex(sha.digest()).toLowerCase(Locale.ROOT);
        } catch (RuntimeException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static int bodyOf(byte[] root, int[] refs) {
        if (!bit(root, 0) || bit(root, 1) || bit(root, 2) || bit(root, 3) || !bit(root, 4) || bit(root, 5) || bit(root, 6)) return -1;
        int at = 4 + 267;
        int feeBytes = 0;
        for (int i = 0; i < 4; i++) feeBytes = feeBytes << 1 | (bit(root, at++) ? 1 : 0);
        at += feeBytes * 8;
        if (bit(root, at++) && !bit(root, at++)) {
            if (bit(root, at++)) at += 5;
            if (bit(root, at++)) at += 2;
            at += 3;
        }
        return bit(root, at) && refs.length > 0 ? refs[refs.length - 1] : -1;
    }

    private static boolean bit(byte[] data, int at) {
        return (data[at / 8] >> (7 - at % 8) & 1) != 0;
    }

    private static void setBit(byte[] data, int at) {
        data[at / 8] |= (byte) (1 << (7 - at % 8));
    }

    private static long uint(ByteBuffer in, int size) {
        long value = 0;
        for (int i = 0; i < size; i++) value = value << 8 | in.get() & 0xff;
        return value;
    }
}
