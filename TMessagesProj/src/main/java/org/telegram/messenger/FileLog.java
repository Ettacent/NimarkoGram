/* Modifications Copyright (C) 2026 Ettacent */

/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import org.telegram.messenger.time.FastDateFormat;
import org.telegram.messenger.video.MediaCodecVideoConvertor;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.AnimatedFileDrawable;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class FileLog {
    private OutputStreamWriter streamWriter = null;
    private final FastDateFormat dateFormat = FastDateFormat.getInstance("dd_MM_yyyy_HH_mm_ss.SSS", Locale.US);
    private final FastDateFormat fileDateFormat = FastDateFormat.getInstance("dd_MM_yyyy_HH_mm_ss", Locale.US);
    private volatile DispatchQueue logQueue = null;
    private final Object initLock = new Object();

    private static final int MAX_PENDING_WRITES = 4096;
    private final AtomicInteger pendingWrites = new AtomicInteger();

    private boolean postLog(Runnable r) {
        return postTask(r, pendingWrites, MAX_PENDING_WRITES, true);
    }

    private boolean postTask(Runnable r, AtomicInteger pending, int limit, boolean requireWriter) {
        if (pending.incrementAndGet() > limit) {
            pending.decrementAndGet();
            return false;
        }
        boolean accepted = false;
        try {
            init();
            DispatchQueue queue = logQueue;
            if (queue != null) {
                accepted = queue.postRunnable(() -> {
                    try {
                        if (!requireWriter || streamWriter != null) {
                            r.run();
                        }
                    } finally {
                        pending.decrementAndGet();
                    }
                });
            }
            return accepted;
        } catch (Exception | OutOfMemoryError e) {
            Log.e(tag, "Unable to queue log", e);
            return false;
            } finally {
            if (!accepted) {
                pending.decrementAndGet();
            }
        }
    }

    private File currentFile = null;
    private volatile File networkFile = null;
    private volatile File tonlibFile = null;
    private volatile boolean initied;

    private final AtomicBoolean initStarted = new AtomicBoolean();
    public static boolean databaseIsMalformed = false;

    private OutputStreamWriter tlStreamWriter = null;
    private File tlRequestsFile = null;

    private final static String tag = "tmessages";
    private final static String mtproto_tag = "MTProto";

    private static volatile FileLog Instance = null;
    public static FileLog getInstance() {
        FileLog localInstance = Instance;
        if (localInstance == null) {
            synchronized (FileLog.class) {
                localInstance = Instance;
                if (localInstance == null) {
                    Instance = localInstance = new FileLog();
                }
            }
        }
                    if (BuildVars.LOGS_ENABLED) {
                        localInstance.init();
        }
        return localInstance;
    }

    public FileLog() {

    }

    private static Gson gson;
    private static ExclusionStrategy exclusionStrategy;
    private static final int MAX_PENDING_PROTOCOL_DUMPS = 8;
    private static final AtomicInteger pendingProtocolDumps = new AtomicInteger();

    private static void postProtocolDump(Runnable dump) {
        try {
            FileLog logger = getInstance();
            logger.postTask(() -> {
                try {
                    if (logger.tlStreamWriter != null) {
                    dump.run();
                    }
                } catch (Throwable e) {
                    Log.e(tag, "Unable to serialize asynchronous protocol dump", e);
                }
            }, pendingProtocolDumps, MAX_PENDING_PROTOCOL_DUMPS, false);
        } catch (Throwable e) {
            Log.e(tag, "Unable to queue protocol dump", e);
        }
    }

    public static void dumpResponseAndRequest(int account, TLObject request, TLObject response, TLRPC.TL_error error, long requestMsgId, long startRequestTimeInMillis, int requestToken) {
        if (!BuildVars.DEBUG_PRIVATE_VERSION || !BuildVars.LOGS_ENABLED || request == null) {
            return;
        }
        String requestSimpleName = request.getClass().getSimpleName();
        if (error == null && ("TL_upload_getFile".equals(requestSimpleName) || "TL_upload_getWebFile".equals(requestSimpleName))) {
            return;
        }
        long time = System.currentTimeMillis();
        long requestingTime = time - startRequestTimeInMillis;
        boolean hasError = error != null;
        postProtocolDump(() -> {
        checkGson();

            String req = "req -> " + requestSimpleName + " : " + gson.toJson(request);
            String res = "null";
            if (response != null) {
                res = "res -> " + response.getClass().getSimpleName() + " : " + gson.toJson(response);
            } else if (error != null) {
                res = "err -> " + error.getClass().getSimpleName() + " : " + gson.toJson(error);
            }
            try {
                String metadata = "requestMsgId=" + requestMsgId + " requestingTime=" + requestingTime + " request_token=" + requestToken + " account=" + account;
                FileLog logger = getInstance();
                logger.tlStreamWriter.write(logger.dateFormat.format(time) + " " + metadata);
                logger.tlStreamWriter.write("\n");
                logger.tlStreamWriter.write(req);
                logger.tlStreamWriter.write("\n");
                logger.tlStreamWriter.write(res);
                logger.tlStreamWriter.write("\n\n");
                logger.tlStreamWriter.flush();

                int priority = hasError ? Log.ERROR : Log.DEBUG;
                Log.println(priority, mtproto_tag, metadata);
                Log.println(priority, mtproto_tag, req);
                Log.println(priority, mtproto_tag, res);
                Log.println(priority, mtproto_tag, " ");
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public static void dumpUnparsedMessage(TLObject message, long messageId, int account) {
        if (!BuildVars.DEBUG_PRIVATE_VERSION || !BuildVars.LOGS_ENABLED || message == null) {
            return;
        }
        long time = System.currentTimeMillis();
        boolean skipGson = gsonDisabled;
        postProtocolDump(() -> {
            checkGson();
            String messageStr = "receive message -> " + message.getClass().getSimpleName() + " : " + (skipGson ? message : gson.toJson(message));
            try {
                FileLog logger = getInstance();
                String metadata = logger.dateFormat.format(time) + " msgId=" + messageId + " account=" + account;
                logger.tlStreamWriter.write(metadata);
                logger.tlStreamWriter.write("\n");
                logger.tlStreamWriter.write(messageStr);
                logger.tlStreamWriter.write("\n\n");
                logger.tlStreamWriter.flush();

                    Log.d(mtproto_tag, "msgId=" + messageId + " account=" + account);
                    Log.d(mtproto_tag, messageStr);
                    Log.d(mtproto_tag, " ");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
    }

    private static volatile boolean gsonDisabled;
    public static void disableGson(boolean disable) {
        gsonDisabled = disable;
    }

    private static HashSet<String> privateFields;
    private static void checkGson() {
        if (gson == null) {
            privateFields = new HashSet<>();
            privateFields.add("message");
            privateFields.add("phone");
            privateFields.add("about");
            privateFields.add("status_text");
            privateFields.add("bytes");
            privateFields.add("secret");
            privateFields.add("stripped_thumb");
            privateFields.add("strippedBitmap");

            privateFields.add("networkType");
            privateFields.add("disableFree");
            privateFields.add("mContext");
            privateFields.add("priority");
            privateFields.add("constructor");
            privateFields.add("constructorName");
            privateFields.add("parentRichText");
            privateFields.add("parentBlock");
            for (int i = 0; i < 32; i++) {
                privateFields.add("FLAG_" + i);
            }


            exclusionStrategy = new ExclusionStrategy() {

                @Override
                public boolean shouldSkipField(FieldAttributes f) {
                    if (privateFields.contains(f.getName()) || "message".equalsIgnoreCase(f.getName()) && String.class.equals(f.getDeclaredType())) {
                        return true;
                    }
                    return false;
                }

                @Override
                public boolean shouldSkipClass(Class<?> clazz) {
                    return NativeByteBuffer.class.isAssignableFrom(clazz) || clazz.isInstance(DispatchQueue.class) || clazz.isInstance(AnimatedFileDrawable.class) || clazz.isInstance(ColorStateList.class) || clazz.isInstance(Context.class);
                }
            };
            gson = new GsonBuilder()
                .addSerializationExclusionStrategy(exclusionStrategy)
                .registerTypeAdapter(byte[].class, new ByteArrayHexAdapter())
                .registerTypeAdapterFactory(RuntimeClassNameTypeAdapterFactory.of(TLObject.class, "type_", exclusionStrategy))
                .registerTypeHierarchyAdapter(TLObject.class, new TLObjectDeserializer())
                .create();
        }
    }

    public static class ByteArrayHexAdapter extends TypeAdapter<byte[]> {

        @Override
        public void write(JsonWriter out, byte[] value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }

            StringBuilder hex = new StringBuilder(2 + value.length * 2);
            hex.append("0x");
            for (byte b : value) {
                hex.append(String.format("%02x", b & 0xFF));
            }
            out.value(hex.toString());
        }

        @Override
        public byte[] read(JsonReader in) throws IOException {
            String hex = in.nextString();
            int len = hex.length();
            byte[] result = new byte[len / 2];
            for (int i = 0; i < len; i += 2) {
                result[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i+1), 16));
            }
            return result;
        }
    }

    private static class TLObjectDeserializer implements JsonSerializer<TLObject> {
        @Override
        public JsonElement serialize(TLObject src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject jsonObj = new JsonObject();
            String className = src.getClass().getName();
            final String usualPrefix = "org.telegram.tgnet.";
            if (className.startsWith(usualPrefix)) {
                className = className.substring(usualPrefix.length());
            }
            jsonObj.addProperty("_", className);
            try {
                Field[] fields = src.getClass().getFields();
                for (Field field : fields) {
                    if (privateFields != null && privateFields.contains(field.getName())) continue;
                    field.setAccessible(true);
                    try {
                        Object value = field.get(src);
                        if (value instanceof NativeByteBuffer) {
                            continue;
                        }
                        if (value != null) {
                            Class clazz = value.getClass();
                            if (clazz.isInstance(DispatchQueue.class) || clazz.isInstance(AnimatedFileDrawable.class) || clazz.isInstance(ColorStateList.class) || clazz.isInstance(Context.class)) {
                                continue;
                            }
                        }
                        JsonElement jsonElement = context.serialize(value);
                        jsonObj.add(field.getName(), jsonElement);
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return jsonObj;
        }
    }

    public void init() {
        if (initied) {
            return;
        }

        synchronized (initLock) {
            if (initied || !initStarted.compareAndSet(false, true)) {
                return;
            }
            boolean scheduled = false;
            try {
                if (logQueue == null) {
                    DispatchQueue queue = new DispatchQueue("logQueue", false);
                    queue.start(this::openWriters);
                    logQueue = queue;
                    scheduled = true;
                } else {
                    scheduled = logQueue.postRunnable(this::openWriters);
                }
            } catch (RuntimeException | OutOfMemoryError e) {
                Log.e(tag, "Unable to start log initialization", e);
            } finally {
                if (!scheduled) {
                    initStarted.set(false);
                }
            }
        }
    }

    private void openWriters() {
        OutputStreamWriter writer = null;
        OutputStreamWriter protocolWriter = null;
        try {
        String date = fileDateFormat.format(System.currentTimeMillis());
            File dir = AndroidUtilities.getLogsDir();
            if (dir == null) {

                return;
            }
            File logFile = new File(dir, date + ".txt");
            File protocolFile = new File(dir, date + "_mtproto.txt");
            writer = openWriter(logFile);
            writer.write("-----start log " + date + "-----\n");
            writer.flush();
            protocolWriter = openWriter(protocolFile);
            protocolWriter.write("-----start log " + date + "-----\n");
            protocolWriter.flush();
            currentFile = logFile;
            tlRequestsFile = protocolFile;
            streamWriter = writer;
            tlStreamWriter = protocolWriter;
            writer = null;
            protocolWriter = null;
            initied = true;
        } catch (Exception | OutOfMemoryError e) {
            Log.e(tag, "Unable to initialize log files", e);
        } finally {
            closeWriter(writer);
            closeWriter(protocolWriter);
                initStarted.set(false);
        }
    }

    private static void closeWriter(OutputStreamWriter writer) {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                Log.e(tag, "Unable to close log file", e);
            }
        }
    }

    private static OutputStreamWriter openWriter(File file) throws IOException {
        FileOutputStream stream = new FileOutputStream(file);
        try {
            return new OutputStreamWriter(stream);
        } catch (RuntimeException | Error e) {
            try {
                stream.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    public static void ensureInitied() {
        getInstance().init();
    }

    public static String getNetworkLogPath() {
        if (!BuildVars.LOGS_ENABLED) {
            return "";
        }
        try {
            File dir = AndroidUtilities.getLogsDir();
            if (dir == null) {
                return "";
            }
            getInstance().networkFile = new File(dir, getInstance().fileDateFormat.format(System.currentTimeMillis()) + "_net.txt");
            return getInstance().networkFile.getAbsolutePath();
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return "";
    }

    public static String getTonlibLogPath() {
        if (!BuildVars.LOGS_ENABLED) {
            return "";
        }
        try {
            File dir = AndroidUtilities.getLogsDir();
            if (dir == null) {
                return "";
            }
            getInstance().tonlibFile = new File(dir, getInstance().dateFormat.format(System.currentTimeMillis()) + "_tonlib.txt");
            return getInstance().tonlibFile.getAbsolutePath();
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return "";
    }

    public static void e(final String message, final Throwable exception) {
        if (!BuildVars.LOGS_ENABLED) {
            return;
        }
        ensureInitied();
        Log.e(tag, message, exception);
            getInstance().postLog(() -> {
                try {
                    getInstance().streamWriter.write(getInstance().dateFormat.format(System.currentTimeMillis()) + " E/tmessages: " + message + "\n");
                    getInstance().streamWriter.write(exception.toString());
                    StackTraceElement[] stack = exception.getStackTrace();
                    for (int a = 0; a < stack.length; a++) {
                        getInstance().streamWriter.write(getInstance().dateFormat.format(System.currentTimeMillis()) + " E/tmessages: \tat " + stack[a] + "\n");
                    }
                    getInstance().streamWriter.flush();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

    public static void e(final String message) {
        if (!BuildVars.LOGS_ENABLED) {
            return;
        }
        ensureInitied();
        Log.e(tag, message);
            getInstance().postLog(() -> {
                try {
                    getInstance().streamWriter.write(getInstance().dateFormat.format(System.currentTimeMillis()) + " E/tmessages: " + message + "\n");
                    getInstance().streamWriter.flush();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
    }

    public static void e(final Throwable e) {
        e(e, true);
    }

    public static void e(final Throwable e, boolean logToAppCenter) {
        if (!BuildVars.LOGS_ENABLED) {
            return;
        }
        if (BuildVars.DEBUG_VERSION && needSent(e) && logToAppCenter) {
            AndroidUtilities.appCenterLog(e);
        }
        if (BuildVars.DEBUG_VERSION && e.getMessage() != null && e.getMessage().contains("disk image is malformed") && !databaseIsMalformed) {
            FileLog.d("copy malformed files");
            databaseIsMalformed = true;
            File filesDir = ApplicationLoader.getFilesDirFixed();
            filesDir = new File(filesDir, "malformed_database/");
            filesDir.mkdirs();
            ArrayList<File> malformedFiles = MessagesStorage.getInstance(UserConfig.selectedAccount).getDatabaseFiles();
            for (int i = 0; i < malformedFiles.size(); i++) {
                try {
                    AndroidUtilities.copyFile(malformedFiles.get(i), new File(filesDir, malformedFiles.get(i).getName()));
                } catch (IOException ex) {
                    FileLog.e(ex);
                }
            }
        }
        ensureInitied();
        e.printStackTrace();
            getInstance().postLog(() -> {
                try {
                    getInstance().streamWriter.write(getInstance().dateFormat.format(System.currentTimeMillis()) + " E/tmessages: " + e + "\n");
                    StackTraceElement[] stack = e.getStackTrace();
                    for (int a = 0; a < stack.length; a++) {
                        getInstance().streamWriter.write(getInstance().dateFormat.format(System.currentTimeMillis()) + " E/tmessages: \tat " + stack[a] + "\n");
                    }
                    Throwable cause = e.getCause();
                    if (cause != null) {
                        getInstance().streamWriter.write(getInstance().dateFormat.format(System.currentTimeMillis()) + " E/tmessages: Caused by " + cause + "\n");
                        stack = cause.getStackTrace();
                        for (int a = 0; a < stack.length; a++) {
                            getInstance().streamWriter.write(getInstance().dateFormat.format(System.currentTimeMillis()) + " E/tmessages: \tat " + stack[a] + "\n");
                        }
                    }
                    getInstance().streamWriter.flush();
                } catch (Exception e1) {
                    e1.printStackTrace();
                }
            });
    }

    public static void fatal(final Throwable e) {
        fatal(e, true);
    }

    private static long dumpedHeap;
    public void dumpMemory(boolean force) {
        if (!force && System.currentTimeMillis() - dumpedHeap < 30_000) return;
        dumpedHeap = System.currentTimeMillis();
        if (force || app.nimarkogram.messenger.diagnostics.CrashDiagnostics.isExtendedMemoryDiagnosticsEnabled()) {
            app.nimarkogram.messenger.diagnostics.CrashDiagnostics.requestManualHeapDump(null);
        }
    }

    public static void dumpANR() {
        StringBuilder sb = new StringBuilder();
        Map<Thread, StackTraceElement[]> allThreads = Thread.getAllStackTraces();

        for (Map.Entry<Thread, StackTraceElement[]> entry : allThreads.entrySet()) {
            Thread thread = entry.getKey();
            StackTraceElement[] stackTrace = entry.getValue();

            sb.append("Thread: ").append(thread.getName()).append("\n");
            for (StackTraceElement element : stackTrace) {
                sb.append("\tat ").append(element).append("\n");
            }
            sb.append("\n\n");
        }

        FileLog.e("ANR thread dump\n" + sb.toString());
        getInstance().dumpMemory(false);
    }

    public static void fatal(final Throwable e, boolean logToAppCenter) {
        if (!BuildVars.LOGS_ENABLED) {
            return;
        }
        if (e instanceof OutOfMemoryError) {
            app.nimarkogram.messenger.diagnostics.CrashDiagnostics.requestObservedOomDump();
        }
        if (logToAppCenter && BuildVars.DEBUG_VERSION && needSent(e)) {
            AndroidUtilities.appCenterLog(e);
        }
        ensureInitied();
        e.printStackTrace();
        FileLog logger = getInstance();
        if (!logger.postTask(() -> {
                try {
                    if (logger.streamWriter == null) {
                        return;
                    }
                    getInstance().streamWriter.write(getInstance().dateFormat.format(System.currentTimeMillis()) + " FATAL/tmessages: " + e + "\n");
                    StackTraceElement[] stack = e.getStackTrace();
                    for (int a = 0; a < stack.length; a++) {
                        getInstance().streamWriter.write(getInstance().dateFormat.format(System.currentTimeMillis()) + " FATAL/tmessages: \tat " + stack[a] + "\n");
                    }
                    Throwable cause = e.getCause();
                    if (cause != null) {
                        getInstance().streamWriter.write(getInstance().dateFormat.format(System.currentTimeMillis()) + " E/tmessages: Caused by " + cause + "\n");
                        stack = cause.getStackTrace();
                        for (int a = 0; a < stack.length; a++) {
                            getInstance().streamWriter.write(getInstance().dateFormat.format(System.currentTimeMillis()) + " E/tmessages: \tat " + stack[a] + "\n");
                        }
                    }
                    getInstance().streamWriter.flush();
                } catch (Exception e1) {
                    e1.printStackTrace();
                } finally {

                if (BuildVars.DEBUG_PRIVATE_VERSION) {
                    System.exit(2);
                }
                }
            }, logger.pendingWrites, MAX_PENDING_WRITES, false)) {
            if (BuildVars.DEBUG_PRIVATE_VERSION) {
                System.exit(2);
            }
        }
    }

    private static boolean needSent(Throwable e) {
        if (e instanceof InterruptedException || e instanceof MediaCodecVideoConvertor.ConversionCanceledException || e instanceof IgnoreSentException) {
            return false;
        }
        return true;
    }

    public static void d(final String message) {
        if (!BuildVars.LOGS_ENABLED) {
            return;
        }
        ensureInitied();
        Log.d(tag, message);
            getInstance().postLog(() -> {
                try {
                    getInstance().streamWriter.write(getInstance().dateFormat.format(System.currentTimeMillis()) + " D/tmessages: " + message + "\n");
                    getInstance().streamWriter.flush();
                } catch (Exception e) {
                    e.printStackTrace();
                    if (AndroidUtilities.isENOSPC(e)) {
                        LaunchActivity.checkFreeDiscSpaceStatic(1);
                    }
                }
            });
        }

    public static void w(final String message) {
        if (!BuildVars.LOGS_ENABLED) {
            return;
        }
        ensureInitied();
        Log.w(tag, message);
            getInstance().postLog(() -> {
                try {
                    getInstance().streamWriter.write(getInstance().dateFormat.format(System.currentTimeMillis()) + " W/tmessages: " + message + "\n");
                    getInstance().streamWriter.flush();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

    public static void cleanupLogs() {
        FileLog logger = getInstance();
        logger.init();
        DispatchQueue queue = logger.logQueue;
        if (queue != null) {
            queue.postRunnable(logger::cleanupLogsInternal);
        }
    }

    private void cleanupLogsInternal() {
        File dir = AndroidUtilities.getLogsDir();
        if (dir == null) {
            return;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (int a = 0; a < files.length; a++) {
                File file = files[a];
                if (currentFile != null && file.equals(currentFile)) {
                    continue;
                }
                if (tlRequestsFile != null && file.equals(tlRequestsFile)) {
                    continue;
                }
                if (file.equals(networkFile) || file.equals(tonlibFile)) {
                    continue;
                }
                file.delete();
            }
        }
    }

    public static class IgnoreSentException extends Exception{

        public IgnoreSentException(String e) {
            super(e);
        }

    }
}
