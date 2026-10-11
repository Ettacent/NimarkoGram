/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.diagnostics;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.BadParcelableException;
import android.os.Build;
import android.os.Looper;

import androidx.annotation.DoNotInline;
import androidx.annotation.RequiresApi;
import androidx.annotation.WorkerThread;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public final class NativeExitInfoReader {
    private static final int MAX_ENTRIES = 8;
    private static final int MAX_SESSION_CHARS = 124;
    private static final long MAX_UNPAIRED_AGE_MS = 7L * 24 * 60 * 60 * 1000;

    private NativeExitInfoReader() {
    }

    public static final class Result {
        public final String status;
        public final boolean exactMatch;
        public final boolean timestampOutsideWindow;
        public final long timestamp;
        public final int pid;
        public final int reason;
        public final int statusCode;
        public final long pss;
        public final long rss;
        public final int importance;
        public final NativeTombstone.Summary tombstone;

        private Result(String status, boolean exactMatch, boolean timestampOutsideWindow,
                       long timestamp, int pid,
                       int reason, int statusCode, long pss, long rss, int importance,
                       NativeTombstone.Summary tombstone) {
            this.status = status;
            this.exactMatch = exactMatch;
            this.timestampOutsideWindow = timestampOutsideWindow;
            this.timestamp = timestamp;
            this.pid = pid;
            this.reason = reason;
            this.statusCode = statusCode;
            this.pss = pss;
            this.rss = rss;
            this.importance = importance;
            this.tombstone = tombstone;
        }

        private Result withTrace(String status, NativeTombstone.Summary tombstone) {
            return new Result(status, exactMatch, timestampOutsideWindow, timestamp, pid, reason, statusCode,
                    pss, rss, importance, tombstone);
        }
    }

    @WorkerThread
    public static void markSession(Context context, String session) {
        if (Build.VERSION.SDK_INT < 30 || context == null || isMainThread()) {
            return;
        }
        byte[] marker = marker(session);
        if (marker == null) {
            return;
        }
        try {
            Api30.markSession(context, marker);
        } catch (LinkageError unavailable) {
        }
    }

    @WorkerThread
    public static Result read(Context context, int pid, String session,
                              long startedWallMs, long endedBeforeWallMs) {
        if (Build.VERSION.SDK_INT < 30) {
            return empty("unsupported");
        }
        if (isMainThread()) {
            return empty("main_thread");
        }
        byte[] marker = marker(session);
        if (context == null || pid <= 0 || marker == null) {
            return empty("invalid_request");
        }
        try {
            return Api30.read(context, pid, marker, startedWallMs, endedBeforeWallMs);
        } catch (SecurityException denied) {
            return empty("permission_denied");
        } catch (IllegalArgumentException | IllegalStateException
                 | UnsupportedOperationException | BadParcelableException failure) {
            return empty("platform_error");
        } catch (LinkageError unavailable) {
            return empty("api_unavailable");
        }
    }

    @WorkerThread
    public static Result readLatestUnpaired(Context context, long endedBeforeWallMs) {
        if (Build.VERSION.SDK_INT < 30) {
            return empty("unsupported");
        }
        if (isMainThread()) {
            return empty("main_thread");
        }
        if (context == null || endedBeforeWallMs <= 0) {
            return empty("invalid_request");
        }
        try {
            Result result = Api30.readLatestUnpaired(context, endedBeforeWallMs);
            return result.pid > 0 ? result.withTrace("unpaired_" + result.status, result.tombstone) : result;
        } catch (SecurityException denied) {
            return empty("permission_denied");
        } catch (IllegalArgumentException | IllegalStateException
                 | UnsupportedOperationException | BadParcelableException failure) {
            return empty("platform_error");
        } catch (LinkageError unavailable) {
            return empty("api_unavailable");
        }
    }

    private static boolean isMainThread() {
        Looper main = Looper.getMainLooper();
        return main != null && Looper.myLooper() == main;
    }

    private static byte[] marker(String session) {
        if (session == null || session.isEmpty() || session.length() > MAX_SESSION_CHARS) {
            return null;
        }
        for (int i = 0; i < session.length(); i++) {
            char c = session.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F'))) {
                return null;
            }
        }
        return ("ng4:" + session).getBytes(StandardCharsets.UTF_8);
    }

    private static Result empty(String status) {
        return new Result(status, false, false, 0, 0, 0, 0, 0, 0, 0, null);
    }

    private static boolean hasTextTraceHeader(PushbackInputStream trace) throws IOException {
        byte[] prefix = new byte[64];
        int length = 0;
        while (length < prefix.length) {
            int value = trace.read();
            if (value < 0) {
                break;
            }
            prefix[length++] = (byte) value;
        }
        trace.unread(prefix, 0, length);
        int start = 0;
        while (start < length && (prefix[start] == '\n' || prefix[start] == '\r'
                || prefix[start] == ' ' || prefix[start] == '\t')) {
            start++;
        }
        return startsWith(prefix, start, length, "----- pid ")
                || startsWith(prefix, start, length, "DALVIK THREADS")
                || startsWith(prefix, start, length, "*** *** ***");
    }

    private static boolean startsWith(byte[] bytes, int start, int length, String header) {
        if (length - start < header.length()) {
            return false;
        }
        for (int i = 0; i < header.length(); i++) {
            if (bytes[start + i] != header.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    @RequiresApi(30)
    private static final class Api30 {
        @DoNotInline
        static Result readLatestUnpaired(Context context, long endedBeforeWallMs) {
            List<ApplicationExitInfo> entries;
            String mainProcessName;
            try {
                ActivityManager manager = (ActivityManager) context.getSystemService(
                        Context.ACTIVITY_SERVICE);
                if (manager == null) {
                    return empty("service_unavailable");
                }
                String packageName = context.getPackageName();
                if (packageName == null || packageName.isEmpty()) {
                    return empty("invalid_request");
                }
                ApplicationInfo applicationInfo = context.getApplicationInfo();
                mainProcessName = applicationInfo == null ? null : applicationInfo.processName;
                if (mainProcessName == null || mainProcessName.isEmpty()) {
                    return empty("main_process_unavailable");
                }
                entries = manager.getHistoricalProcessExitReasons(packageName, 0, MAX_ENTRIES);
            } catch (SecurityException denied) {
                return empty("permission_denied");
            } catch (RuntimeException platformFailure) {
                return empty("platform_error");
            }
            if (entries == null || entries.isEmpty()) {
                return empty("no_match");
            }
            ApplicationExitInfo latest = null;
            long latestTimestamp = 0;
            int count = Math.min(entries.size(), MAX_ENTRIES);
            for (int i = 0; i < count; i++) {
                ApplicationExitInfo entry = entries.get(i);
                if (entry == null || entry.getPid() <= 0
                        || entry.getReason() != ApplicationExitInfo.REASON_CRASH_NATIVE
                        || !mainProcessName.equals(entry.getProcessName())) {
                    continue;
                }
                long timestamp = entry.getTimestamp();
                if (timestamp <= 0 || timestamp > endedBeforeWallMs
                        || endedBeforeWallMs - timestamp > MAX_UNPAIRED_AGE_MS) {
                    continue;
                }
                if (timestamp > latestTimestamp) {
                    latest = entry;
                    latestTimestamp = timestamp;
                }
            }
            if (latest == null) {
                return empty("no_match");
            }
            Result result = new Result("metadata_only", false, false, latestTimestamp,
                    latest.getPid(), latest.getReason(), latest.getStatus(), latest.getPss(),
                    latest.getRss(), latest.getImportance(), null);
            if (Build.VERSION.SDK_INT < 31) {
                return result;
            }
            try {
                return Api31.readTrace(latest, result);
            } catch (LinkageError unavailable) {
                return result.withTrace("trace_api_unavailable", null);
            }
        }

        @DoNotInline
        static void markSession(Context context, byte[] marker) {
            try {
                ActivityManager manager = (ActivityManager) context.getSystemService(
                        Context.ACTIVITY_SERVICE);
                if (manager != null) {
                    manager.setProcessStateSummary(marker);
                }
            } catch (RuntimeException platformFailure) {
            }
        }

        @DoNotInline
        static Result read(Context context, int pid, byte[] marker,
                           long startedWallMs, long endedBeforeWallMs) {
            List<ApplicationExitInfo> entries;
            try {
                ActivityManager manager = (ActivityManager) context.getSystemService(
                        Context.ACTIVITY_SERVICE);
                if (manager == null) {
                    return empty("service_unavailable");
                }
                String packageName = context.getPackageName();
                if (packageName == null || packageName.isEmpty()) {
                    return empty("invalid_request");
                }
                entries = manager.getHistoricalProcessExitReasons(packageName, pid, MAX_ENTRIES);
            } catch (SecurityException denied) {
                return empty("permission_denied");
            } catch (RuntimeException platformFailure) {
                return empty("platform_error");
            }
            if (entries == null || entries.isEmpty()) {
                return empty("no_match");
            }
            ApplicationExitInfo match = null;
            boolean markerUnavailable = false;
            boolean markerMismatch = false;
            int count = Math.min(entries.size(), MAX_ENTRIES);
            for (int i = 0; i < count; i++) {
                ApplicationExitInfo entry = entries.get(i);
                if (entry == null || entry.getPid() != pid
                        || entry.getReason() != ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    continue;
                }
                byte[] savedMarker = entry.getProcessStateSummary();
                if (savedMarker == null || savedMarker.length == 0) {
                    markerUnavailable = true;
                    continue;
                }
                if (!Arrays.equals(marker, savedMarker)) {
                    markerMismatch = true;
                    continue;
                }
                if (match != null) {
                    return empty("ambiguous_match");
                }
                match = entry;
            }
            if (match == null) {
                return empty(markerUnavailable ? "marker_unavailable"
                        : markerMismatch ? "marker_mismatch" : "no_match");
            }
            long timestamp = match.getTimestamp();
            boolean timestampOutsideWindow = startedWallMs <= 0
                    || endedBeforeWallMs <= startedWallMs
                    || timestamp < startedWallMs || timestamp >= endedBeforeWallMs;
            Result result = new Result("metadata_only", true, timestampOutsideWindow, timestamp,
                    match.getPid(), match.getReason(), match.getStatus(), match.getPss(),
                    match.getRss(), match.getImportance(), null);
            if (Build.VERSION.SDK_INT < 31) {
                return result;
            }
            try {
                return Api31.readTrace(match, result);
            } catch (LinkageError unavailable) {
                return result.withTrace("trace_api_unavailable", null);
            }
        }
    }

    @RequiresApi(31)
    private static final class Api31 {
        @DoNotInline
        static Result readTrace(ApplicationExitInfo entry, Result result) {
            InputStream stream;
            try {
                stream = entry.getTraceInputStream();
            } catch (SecurityException denied) {
                return result.withTrace("trace_permission_denied", null);
            } catch (IOException failure) {
                return result.withTrace("trace_io_error", null);
            } catch (RuntimeException platformFailure) {
                return result.withTrace("trace_platform_error", null);
            }
            if (stream == null) {
                return result.withTrace("trace_unavailable", null);
            }
            try (PushbackInputStream trace = new PushbackInputStream(stream, 64)) {
                if (hasTextTraceHeader(trace)) {
                    return result.withTrace("trace_not_protobuf", null);
                }
                NativeTombstone.Summary summary = NativeTombstone.parse(trace);
                if (summary != null && "io_error".equals(summary.status)) {
                    return result.withTrace("trace_io_error", null);
                }
                if (summary == null || !("ok".equals(summary.status)
                        || "limited".equals(summary.status))) {
                    return result.withTrace("trace_parse_error", null);
                }
                if (summary.pid != result.pid || summary.tid <= 0) {
                    return result.withTrace("trace_identity_mismatch", null);
                }
                return result.withTrace(summary.truncated ? "trace_truncated" : "trace_ok", summary);
            } catch (IOException failure) {
                return result.withTrace("trace_io_error", null);
            } catch (SecurityException denied) {
                return result.withTrace("trace_permission_denied", null);
            } catch (IllegalArgumentException | IllegalStateException malformed) {
                return result.withTrace("trace_parse_error", null);
            }
        }
    }
}
