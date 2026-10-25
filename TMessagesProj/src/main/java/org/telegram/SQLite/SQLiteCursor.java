/* Modifications Copyright (C) 2026 Ettacent */

/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.SQLite;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.Vector;

public class SQLiteCursor {

	public static final int FIELD_TYPE_INT = 1;
	public static final int FIELD_TYPE_FLOAT = 2;
	public static final int FIELD_TYPE_STRING = 3;
	public static final int FIELD_TYPE_BYTEARRAY = 4;
	public static final int FIELD_TYPE_NULL = 5;

	private SQLitePreparedStatement preparedStatement;
	private boolean inRow = false;

	public SQLiteCursor(SQLitePreparedStatement stmt) {
		preparedStatement = stmt;
	}

	public boolean isNull(int columnIndex) throws SQLiteException {
		synchronized (preparedStatement.getDatabase()) {
		checkRow();
			return columnIsNull(preparedStatement.getStatementHandleChecked(), columnIndex) == 1;
		}
	}

	public SQLitePreparedStatement getPreparedStatement() {
		return preparedStatement;
	}

	public int intValue(int columnIndex) throws SQLiteException {
		synchronized (preparedStatement.getDatabase()) {
		checkRow();
			return columnIntValue(preparedStatement.getStatementHandleChecked(), columnIndex);
		}
	}

	public double doubleValue(int columnIndex) throws SQLiteException {
		synchronized (preparedStatement.getDatabase()) {
		checkRow();
			return columnDoubleValue(preparedStatement.getStatementHandleChecked(), columnIndex);
		}
	}

	public long longValue(int columnIndex) throws SQLiteException {
		synchronized (preparedStatement.getDatabase()) {
		checkRow();
			return columnLongValue(preparedStatement.getStatementHandleChecked(), columnIndex);
		}
	}

	public String stringValue(int columnIndex) throws SQLiteException {
		synchronized (preparedStatement.getDatabase()) {
		checkRow();
			return columnStringValue(preparedStatement.getStatementHandleChecked(), columnIndex);
		}
	}

	public byte[] byteArrayValue(int columnIndex) throws SQLiteException {
		synchronized (preparedStatement.getDatabase()) {
		checkRow();
			return columnByteArrayValue(preparedStatement.getStatementHandleChecked(), columnIndex);
		}
	}

	public NativeByteBuffer byteBufferValue(int columnIndex) throws SQLiteException {
		synchronized (preparedStatement.getDatabase()) {
		checkRow();
			long ptr = columnByteBufferValue(preparedStatement.getStatementHandleChecked(), columnIndex);
		if (ptr != 0) {
			return NativeByteBuffer.wrap(ptr);
		}
		return null;
		}
	}

	public <T extends TLObject> T tlObjectValue(int columnIndex, Vector.TLDeserializer<T> deserializer, boolean exception) throws SQLiteException {
		NativeByteBuffer data = byteBufferValue(columnIndex);
		if (data == null) {
			return null;
		}

		try {
			int constructor = data.readInt32(exception);
			return deserializer.deserialize(data, constructor, exception);
		} finally {
			data.reuse();
		}
	}

	public int getTypeOf(int columnIndex) throws SQLiteException {
		synchronized (preparedStatement.getDatabase()) {
		checkRow();
			return columnType(preparedStatement.getStatementHandleChecked(), columnIndex);
		}
	}

	public boolean next() throws SQLiteException {
		int res = stepCursor();
		if (res == -1) {
			int repeatCount = 6;
			while (repeatCount-- != 0) {
				try {
					if (BuildVars.LOGS_ENABLED) {
						FileLog.d("sqlite busy, waiting...");
					}
					Thread.sleep(500);
				} catch (InterruptedException e) {
					FileLog.e(e);
				}
				res = stepCursor();
				if (res != -1) {
						break;
				}
			}
			if (res == -1) {
				throw new SQLiteException("sqlite busy");
			}
		}
		return res == 0;
	}

	private int stepCursor() throws SQLiteException {
		synchronized (preparedStatement.getDatabase()) {
			inRow = false;
			int result = preparedStatement.step();
			inRow = result == 0;
			return result;
		}
	}

	public long getStatementHandle() {
		return preparedStatement.getStatementHandle();
	}

	public int getColumnCount() {
		synchronized (preparedStatement.getDatabase()) {
		return columnCount(preparedStatement.getStatementHandle());
	}

	}
	public void dispose() {
		synchronized (preparedStatement.getDatabase()) {
			inRow = false;
		preparedStatement.dispose();
	}

	}
	void checkRow() throws SQLiteException {
		preparedStatement.checkFinalized();
		if (!inRow) {
			throw new SQLiteException("You must call next before");
		}
	}

	native int columnType(long statementHandle, int columnIndex);
	native int columnCount(long statementHandle);
	native int columnIsNull(long statementHandle, int columnIndex);
	native int columnIntValue(long statementHandle, int columnIndex);
	native long columnLongValue(long statementHandle, int columnIndex);
	native double columnDoubleValue(long statementHandle, int columnIndex);
	native String columnStringValue(long statementHandle, int columnIndex);
	native byte[] columnByteArrayValue(long statementHandle, int columnIndex);
	native long columnByteBufferValue(long statementHandle, int columnIndex);
}
