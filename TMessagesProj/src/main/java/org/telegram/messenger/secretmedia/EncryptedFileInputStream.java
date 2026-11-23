/* Modifications Copyright (C) 2026 Ettacent */

/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger.secretmedia;

import org.telegram.messenger.SecureDocumentKey;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

public class EncryptedFileInputStream extends FileInputStream {

    private byte[] key = new byte[32];
    private byte[] iv = new byte[16];
    private int fileOffset;
    private int currentMode;

    private final static int MODE_CTR = 0;
    private final static int MODE_CBC = 1;

    public EncryptedFileInputStream(File file, File keyFile) throws IOException {
        super(file);

        currentMode = MODE_CTR;
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(keyFile, "r")) {
            randomAccessFile.readFully(key);
            randomAccessFile.readFully(iv);
        } catch (IOException | RuntimeException | Error e) {
            try {
                super.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    public EncryptedFileInputStream(File file, SecureDocumentKey secureDocumentKey) throws Exception {
        super(file);

        currentMode = MODE_CBC;
        System.arraycopy(secureDocumentKey.file_key, 0, key, 0, key.length);
        System.arraycopy(secureDocumentKey.file_iv, 0, iv, 0, iv.length);
    }

    @Override
    public int read() throws IOException {
        if (currentMode == MODE_CTR) {
            byte[] b = new byte[1];
            return read(b, 0, 1) == -1 ? -1 : b[0] & 0xff;
        }
        return super.read();
    }

    @Override
    public int read(byte[] b) throws IOException {
        if (currentMode == MODE_CTR) {
            return read(b, 0, b.length);
        }
        return super.read(b);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (currentMode == MODE_CTR) {
            int bytesRead = super.read(b, off, len);
            if (bytesRead > 0) {
                long position = getChannel().position() - bytesRead;
                Utilities.aesCtrDecryptionByteArrayAtOffset(b, key, iv, off, bytesRead, position);
            }
            return bytesRead;
        }

        if (fileOffset == 0) {
            byte[] temp = new byte[32];
            super.read(temp, 0, 32);
            Utilities.aesCbcEncryptionByteArraySafe(b, key, iv, off, len, fileOffset, 0);
            fileOffset += 32;
            skip((temp[0] & 0xff) - 32);
        }
        int result = super.read(b, off, len);
            Utilities.aesCbcEncryptionByteArraySafe(b, key, iv, off, len, fileOffset, 0);
        fileOffset += len;
        return result;
    }

    @Override
    public long skip(long n) throws IOException {
        if (currentMode == MODE_CBC) {
        fileOffset += n;
        }
        return super.skip(n);
    }

    public static void decryptBytesWithKeyFile(byte[] bytes, int offset, int length, SecureDocumentKey secureDocumentKey) {
        Utilities.aesCbcEncryptionByteArraySafe(bytes, secureDocumentKey.file_key, secureDocumentKey.file_iv, offset, length, 0, 0);
    }

    public static void decryptBytesWithKeyFile(byte[] bytes, int offset, int length, File keyFile) throws Exception {
        byte[] key = new byte[32];
        byte[] iv = new byte[16];
        RandomAccessFile randomAccessFile = new RandomAccessFile(keyFile, "r");
        randomAccessFile.read(key, 0, 32);
        randomAccessFile.read(iv, 0, 16);
        randomAccessFile.close();
        Utilities.aesCtrDecryptionByteArray(bytes, key, iv, offset, length, 0);
    }
}
