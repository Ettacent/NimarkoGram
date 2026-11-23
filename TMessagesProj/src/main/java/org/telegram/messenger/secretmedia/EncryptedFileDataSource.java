/* Modifications Copyright (C) 2026 Ettacent */

/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger.secretmedia;

import static java.lang.Math.min;

import android.net.Uri;
import androidx.annotation.Nullable;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSourceException;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;


import org.telegram.messenger.FileLoader;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

import java.nio.channels.FileChannel;
@OptIn(markerClass = UnstableApi.class)
public final class EncryptedFileDataSource extends BaseDataSource {

    public static class EncryptedFileDataSourceException extends IOException {

        public EncryptedFileDataSourceException(Throwable cause) {
            super(cause);
        }

    }

    private Uri uri;
    private boolean opened;
    private long bytesRemaining;
    EncryptedFileInputStream fileInputStream;

    public EncryptedFileDataSource() {
        super(/* isNetwork= */ false);
    }

    @Deprecated
    public EncryptedFileDataSource(@Nullable TransferListener listener) {
        this();
        if (listener != null) {
            addTransferListener(listener);
        }
    }


    @Override
    public long open(DataSpec dataSpec) throws IOException {
        if (uri != null) {
            throw new IllegalStateException("Data source is already open");
        }
        uri = dataSpec.uri;
        try {
            transferInitializing(dataSpec);
            File path = new File(uri.getPath());
            File keyPath = new File(FileLoader.getInternalCacheDir(), path.getName() + ".key");

        try {
            fileInputStream = new EncryptedFileInputStream(path, keyPath);
            } catch (FileNotFoundException e) {
                throw new DataSourceException(e, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND);
            }

            FileChannel channel = fileInputStream.getChannel();
            long fileLength = channel.size();
            if (dataSpec.position > fileLength) {
                throw new DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE);
            }
            channel.position(dataSpec.position);
            bytesRemaining = fileLength - dataSpec.position;
            if (dataSpec.length != C.LENGTH_UNSET) {
                bytesRemaining = min(bytesRemaining, dataSpec.length);
            }
            opened = true;
            transferStarted(dataSpec);
            return dataSpec.length != C.LENGTH_UNSET ? dataSpec.length : bytesRemaining;
        } catch (IOException | RuntimeException | Error e) {
            try {
                close();
            } catch (IOException | RuntimeException | Error closeError) {
                if (closeError != e) {
                    e.addSuppressed(closeError);
                }
            }
            throw e;
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        } else if (bytesRemaining == 0) {
            return C.RESULT_END_OF_INPUT;
        }
        int bytesRead = fileInputStream.read(buffer, offset, (int) min(length, bytesRemaining));
        if (bytesRead > 0) {
            bytesRemaining -= bytesRead;
            bytesTransferred(bytesRead);
        }
        return bytesRead;
    }

    @Override
    @Nullable
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() throws IOException {
        uri = null;
        bytesRemaining = 0;
        EncryptedFileInputStream stream = fileInputStream;
        fileInputStream = null;
        try {
            if (stream != null) {
                stream.close();
        }
        } finally {
        if (opened) {
            opened = false;
            transferEnded();
        }
    }
}
}
