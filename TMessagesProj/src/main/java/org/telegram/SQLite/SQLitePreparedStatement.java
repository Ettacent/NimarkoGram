/* Modifications Copyright (C) 2026 Ettacent */

/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.SQLite;

import android.os.SystemClock;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLObject;

import java.nio.ByteBuffer;

public class SQLitePreparedStatement {

    private final SQLiteDatabase database;
    private final SQLiteDatabase.StatementHandle statementHandle;

    private long startTime;
    private String query;

    public long getStatementHandle() {
        synchronized (database) {
            try {
                return getStatementHandleChecked();
            } catch (SQLiteException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    SQLiteDatabase getDatabase() {
        return database;
    }

    long getStatementHandleChecked() throws SQLiteException {
        checkFinalized();
        return statementHandle.handle;
    }

    public SQLitePreparedStatement(SQLiteDatabase db, String sql) throws SQLiteException {
        database = db;
        synchronized (database) {
            statementHandle = database.registerStatement();
            boolean initialized = false;
            try {
                statementHandle.handle = prepare(database.getSQLiteHandle(), sql);
                if (statementHandle.handle == 0) {
                    throw new SQLiteException("Prepared query is empty");
                }
        if (BuildVars.LOGS_ENABLED) {
            query = sql;
            startTime = SystemClock.elapsedRealtime();
            /*if (BuildVars.DEBUG_PRIVATE_VERSION) {
                if (hashMap == null) {
                    hashMap = new HashMap<>();
                }
                hashMap.put(this, sql);
            }*/
        }
                initialized = true;
            } finally {
                if (!initialized) {
                    database.releaseStatement(statementHandle);
                }
            }
        }
    }


    public SQLiteCursor query(Object[] args) throws SQLiteException {
        synchronized (database) {
        if (args == null) {
            throw new IllegalArgumentException();
        }

        checkFinalized();

            reset(statementHandle.handle);

        int i = 1;
        for (int a = 0; a < args.length; a++) {
            Object obj = args[a];
            if (obj == null) {
                    bindNull(statementHandle.handle, i);
            } else if (obj instanceof Integer) {
                    bindInt(statementHandle.handle, i, (Integer) obj);
            } else if (obj instanceof Double) {
                    bindDouble(statementHandle.handle, i, (Double) obj);
            } else if (obj instanceof String) {
                    bindString(statementHandle.handle, i, (String) obj);
            } else if (obj instanceof Long) {
                    bindLong(statementHandle.handle, i, (Long) obj);
            } else {
                throw new IllegalArgumentException();
            }
            i++;
        }

        return new SQLiteCursor(this);
    }

    }
    public int step() throws SQLiteException {
        synchronized (database) {
            return step(getStatementHandleChecked());
        }
    }

    public SQLitePreparedStatement stepThis() throws SQLiteException {
        step();
        return this;
    }

    public void requery() throws SQLiteException {
        synchronized (database) {
            reset(getStatementHandleChecked());
        }
    }

    public void dispose() {
        finalizeQuery();
    }

    void checkFinalized() throws SQLiteException {
        database.checkOpened();
        if (statementHandle.handle == 0) {
            throw new SQLiteException("Prepared query finalized");
        }
    }

    public void finalizeQuery() {
        synchronized (database) {
            if (statementHandle.handle == 0) {
            return;
        }
        if (BuildVars.LOGS_ENABLED) {
            long diff = SystemClock.elapsedRealtime() - startTime;
            if (diff > 500) {
                FileLog.d("sqlite query " + query + " took " + diff + "ms");
            }
        }
            database.releaseStatement(statementHandle);
        }
    }

    public void bindInteger(int index, int value) throws SQLiteException {
        synchronized (database) {
            bindInt(getStatementHandleChecked(), index, value);
        }
    }

    public void bindDouble(int index, double value) throws SQLiteException {
        synchronized (database) {
            bindDouble(getStatementHandleChecked(), index, value);
        }
    }

    public void bindByteBuffer(int index, ByteBuffer value) throws SQLiteException {
        synchronized (database) {
            bindByteBuffer(getStatementHandleChecked(), index, value, value.limit());
        }
    }

    public void bindByteBuffer(int index, NativeByteBuffer value) throws SQLiteException {
        synchronized (database) {
            bindByteBuffer(getStatementHandleChecked(), index, value.buffer, value.limit());
        }
    }

    public void bindTlObject(int index, TLObject object) throws Exception {
        NativeByteBuffer data = new NativeByteBuffer(object.getObjectSize());
        try {
            object.serializeToStream(data);
            bindByteBuffer(index, data);
        } finally {
            data.reuse();
        }
    }

    public void bindString(int index, String value) throws SQLiteException {
        synchronized (database) {
            bindString(getStatementHandleChecked(), index, value);
        }
    }

    public void bindLong(int index, long value) throws SQLiteException {
        synchronized (database) {
            bindLong(getStatementHandleChecked(), index, value);
        }
    }

    public void bindNull(int index) throws SQLiteException {
        synchronized (database) {
            bindNull(getStatementHandleChecked(), index);
        }
    }

    native void bindByteBuffer(long statementHandle, int index, ByteBuffer value, int length) throws SQLiteException;
    native void bindString(long statementHandle, int index, String value) throws SQLiteException;
    native void bindInt(long statementHandle, int index, int value) throws SQLiteException;
    native void bindLong(long statementHandle, int index, long value) throws SQLiteException;
    native void bindDouble(long statementHandle, int index, double value) throws SQLiteException;
    native void bindNull(long statementHandle, int index) throws SQLiteException;
    native void reset(long statementHandle) throws SQLiteException;
    native long prepare(long sqliteHandle, String sql) throws SQLiteException;
    native void finalize(long statementHandle) throws SQLiteException;
    native int step(long statementHandle) throws SQLiteException;
}
