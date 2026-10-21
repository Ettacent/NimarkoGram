/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.messenger;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.List;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
public class NotificationImageProvider extends ContentProvider implements NotificationCenter.NotificationCenterDelegate {

	private static volatile String authority;
	private static volatile UriMatcher matcher;

	private final HashMap<String, Integer> waitingForFiles = new HashMap<>();
	private final Object sync = new Object();
	private final AtomicBoolean initializationPosted = new AtomicBoolean();
	private Handler mainHandler;
	private volatile boolean closed;
	private int observedAccounts;
	private final FutureTask<Void> initialization = new FutureTask<>(() -> {
		if (closed) return null;
		if (ApplicationLoader.applicationContext == null) {
			throw new IllegalStateException("Application is not ready");
		}
		ApplicationLoader.postInitApplication();
		for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT && !closed; i++) {
			NotificationCenter.getInstance(i).addObserver(this, NotificationCenter.fileLoaded);
			observedAccounts = i + 1;
		}
		return null;
	}) {
		@Override
		protected void setException(Throwable failure) {
			super.setException(failure);
			if (failure instanceof Error) throw (Error) failure;
			if (failure instanceof RuntimeException) throw (RuntimeException) failure;
			throw new RuntimeException(failure);
		}
	};

	public static String getAuthority() {
		if (authority == null) {
			authority = ApplicationLoader.getApplicationId() + ".notification_image_provider";
		}
		return authority;
	}

	private static synchronized UriMatcher getUriMatcher() {
		if (matcher == null) {
			UriMatcher value = new UriMatcher(UriMatcher.NO_MATCH);
			value.addURI(getAuthority(), "msg_media_raw/#/*", 1); // content://org.telegram..../msg_media_raw/account/filename.ext
			matcher = value;
		}
		return matcher;
	}

	@Override
	public boolean onCreate() {
		if (getContext() == null) return false;
		authority = getContext().getPackageName() + ".notification_image_provider";
		mainHandler = new Handler(Looper.getMainLooper());
		return true;
	}

	@Override
	public void shutdown() {
		closed = true;
		initialization.cancel(false);
		synchronized (sync) {
			sync.notifyAll();
		}
		if (Looper.myLooper() == Looper.getMainLooper()) {
			removeObservers();
		} else if (mainHandler != null) {
			mainHandler.post(this::removeObservers);
		}
	}

	private void removeObservers() {
		for (int i = 0; i < observedAccounts; i++) {
			NotificationCenter.getInstance(i).removeObserver(this, NotificationCenter.fileLoaded);
		}
		observedAccounts = 0;
	}

	private void ensureInitialized(long deadline) throws FileNotFoundException {
		if (closed || mainHandler == null) throw new FileNotFoundException("Provider is unavailable");
		if (Looper.myLooper() == Looper.getMainLooper()) {
			if (ApplicationLoader.applicationContext == null || ApplicationLoader.applicationHandler == null) {
				throw new FileNotFoundException("Application is not ready");
			}
			initialization.run();
			if (!initialization.isDone()) throw new FileNotFoundException("Image provider initialization in progress");
		} else if (initializationPosted.compareAndSet(false, true) && !mainHandler.post(initialization)) {
			initialization.cancel(false);
		}
		try {
			initialization.get(Math.max(0, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new FileNotFoundException("Image request interrupted");
		} catch (ExecutionException | CancellationException | TimeoutException e) {
			FileNotFoundException failure = new FileNotFoundException("Image provider initialization unavailable");
			failure.initCause(e);
			throw failure;
		}
		if (closed) throw new FileNotFoundException("Provider is unavailable");
	}

	@Nullable
	@Override
	public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
		return null;
	}

	@Nullable
	@Override
	public String getType(@NonNull Uri uri) {
		return null;
	}

	@Nullable
	@Override
	public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
		return null;
	}

	@Override
	public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
		return 0;
	}

	@Override
	public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
		return 0;
	}

	@Nullable
	@Override
	public String[] getStreamTypes(@NonNull Uri uri, @NonNull String mimeTypeFilter) {
		if (mimeTypeFilter.startsWith("*/") || mimeTypeFilter.startsWith("image/")) {
			return new String[]{"image/jpeg", "image/png", "image/webp"};
		}
		return null;
	}

	@Nullable
	@Override
	public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
		if (!"r".equals(mode)) {
			throw new SecurityException("Can only open files for read");
		}
		if (getUriMatcher().match(uri) == 1) {
			List<String> path = uri.getPathSegments();
			int account;
			try {
				account = Integer.parseInt(path.get(1));
			} catch (NumberFormatException e) {
				throw new FileNotFoundException("Invalid account");
			}
			if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) throw new FileNotFoundException("Invalid account");
			String name = path.get(2);
			String finalPath = uri.getQueryParameter("final_path");
			String fallbackPath = uri.getQueryParameter("fallback");
			if (TextUtils.isEmpty(finalPath)) throw new FileNotFoundException("Missing image path");
			File finalFile = new File(finalPath);
			long deadline = SystemClock.elapsedRealtime() + 3000;
			ensureInitialized(deadline);
			if (AndroidUtilities.isInternalUri(Uri.fromFile(finalFile))) {
				throw new SecurityException("trying to read internal file");
			}
			if (!finalFile.exists()) {
				String key = account + ":" + name;
				synchronized (sync) {
					waitingForFiles.put(key, waitingForFiles.getOrDefault(key, 0) + 1);
					try {
						while (!finalFile.exists() && !closed) {
							long remaining = deadline - SystemClock.elapsedRealtime();
							if (remaining <= 0 || Looper.myLooper() == Looper.getMainLooper()) break;
							sync.wait(Math.min(1000, remaining));
						}
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new FileNotFoundException("Image request interrupted");
					} finally {
						int count = waitingForFiles.get(key);
						if (count == 1) waitingForFiles.remove(key);
						else waitingForFiles.put(key, count - 1);
					}
				}
				if (closed) throw new FileNotFoundException("Provider is unavailable");
				if (!finalFile.exists()) {
					if (TextUtils.isEmpty(fallbackPath)) throw new FileNotFoundException("Download timed out");
						File file = new File(fallbackPath);
						if (AndroidUtilities.isInternalUri(Uri.fromFile(file))) {
							throw new SecurityException("trying to read internal file");
						}
						return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
				}
				if (AndroidUtilities.isInternalUri(Uri.fromFile(finalFile))) {
					throw new SecurityException("trying to read internal file");
				}
			}
			return ParcelFileDescriptor.open(finalFile, ParcelFileDescriptor.MODE_READ_ONLY);
		}
		throw new FileNotFoundException("Invalid URI");
	}

	@Override
	public void didReceivedNotification(int id, int account, Object... args) {
		if (id == NotificationCenter.fileLoaded) {
			synchronized (sync) {
				String name = (String) args[0];
				if (waitingForFiles.containsKey(account + ":" + name)) {
					sync.notifyAll();
				}
			}
		}
	}
}
