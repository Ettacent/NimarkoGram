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
import org.telegram.messenger.ApplicationLoader;

public class SQLiteDatabase {

	private final long sqliteHandle;

	private boolean isOpen;
    private boolean terminal;
    private boolean inTransaction;

    private StatementHandle statements;

    static final class StatementHandle {
        long handle;
        StatementHandle next;
    }

    synchronized StatementHandle registerStatement() throws SQLiteException {
        checkOpened();
        StatementHandle statement = new StatementHandle();
        statement.next = statements;
        statements = statement;
        return statement;
    }

    synchronized void releaseStatement(StatementHandle statement) {
        if (statement.handle != 0) {
            finalizeStatement(statement.handle);
            statement.handle = 0;
        }
        StatementHandle previous = null;
        for (StatementHandle current = statements; current != null; current = current.next) {
            if (current == statement) {
                if (previous == null) {
                    statements = current.next;
                } else {
                    previous.next = current.next;
                }
                current.next = null;
                return;
            }
            previous = current;
        }
    }

	public synchronized long getSQLiteHandle() {
		if (!isOpen || terminal) {
			throw new IllegalStateException("Database closed");
		}
		return sqliteHandle;
	}

	public SQLiteDatabase(String fileName) throws SQLiteException {
		sqliteHandle = opendb(fileName, ApplicationLoader.getFilesDirFixed().getPath());
		isOpen = true;
	}

	public boolean tableExists(String tableName) throws SQLiteException {
		checkOpened();
		String s = "SELECT rowid FROM sqlite_master WHERE type='table' AND name=?;";
		return executeInt(s, tableName) != null;
	}

    public SQLitePreparedStatement executeFast(String sql) throws SQLiteException {
        return new SQLitePreparedStatement(this, sql);
    }

	public Integer executeInt(String sql, Object... args) throws SQLiteException {
		checkOpened();
		SQLiteCursor cursor = queryFinalized(sql, args);
		try {
			if (!cursor.next()) {
				return null;
			}
			return cursor.intValue(0);
		} finally {
			cursor.dispose();
		}
	}

	public void explainQuery(String sql, Object... args) throws SQLiteException {
		checkOpened();
		SQLiteCursor cursor = queryFinalized("EXPLAIN QUERY PLAN " + sql, args);
		try {
		while (cursor.next()) {
			int count = cursor.getColumnCount();
			StringBuilder builder = new StringBuilder();
			for (int a = 0; a < count; a++) {
			    builder.append(cursor.stringValue(a)).append(", ");
            }
            FileLog.d("EXPLAIN QUERY PLAN " + builder.toString());
		}
		} finally {
		cursor.dispose();
		}
	}

	public SQLiteCursor queryFinalized(String sql, Object... args) throws SQLiteException {
		checkOpened();
		SQLitePreparedStatement statement = new SQLitePreparedStatement(this, sql);
		boolean transferred = false;
		try {
			SQLiteCursor cursor = statement.query(args);
			transferred = true;
			return cursor;
		} finally {
			if (!transferred) {
				statement.dispose();
			}
		}
	}

	public void close() {
			try {
			closeOrThrow();
			} catch (SQLiteException e) {
				if (BuildVars.LOGS_ENABLED) {
					FileLog.e(e.getMessage(), e);
				}
			}
	}

	public synchronized void closeOrThrow() throws SQLiteException {
		if (isOpen) {
			commitTransaction();
			closedb(sqliteHandle);
			isOpen = false;
		}
	}

    public synchronized void closeForDestruction() throws SQLiteException {
        terminal = true;
        while (statements != null) {
            releaseStatement(statements);
        }
        closeOrThrow();
    }

	synchronized void checkOpened() throws SQLiteException {
		if (!isOpen || terminal) {
			throw new SQLiteException("Database closed");
		}
	}

	public void finalize() throws Throwable {
        super.finalize();
		close();
	}

    public synchronized void beginTransaction() throws SQLiteException {
        checkOpened();
        if (inTransaction) {
			if (BuildVars.DEBUG_PRIVATE_VERSION) {
				throw new SQLiteException("database already in transaction");
			} else {
				commitTransaction();
			}
        }
        inTransaction = true;
        beginTransaction(sqliteHandle);
    }

    public synchronized void commitTransaction() {
        if (!inTransaction) {
            return;
        }
        inTransaction = false;
        commitTransaction(sqliteHandle);
    }

	native long opendb(String fileName, String tempDir) throws SQLiteException;
	native void closedb(long sqliteHandle) throws SQLiteException;
    native void beginTransaction(long sqliteHandle);
    native void commitTransaction(long sqliteHandle);
    private static native void finalizeStatement(long statementHandle);
}
