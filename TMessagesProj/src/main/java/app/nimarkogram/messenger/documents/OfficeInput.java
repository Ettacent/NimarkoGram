/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.documents;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class OfficeInput {
    public static final int ERROR_FILE = 1, ERROR_TOO_LARGE = 2, ERROR_PASSWORD = 4, ERROR_TIMEOUT = 5;
    public static final int MAX_COMPRESSED = 24 * 1024 * 1024;
    public static final int MAX_INFLATED = 96 * 1024 * 1024;
    public static final int MAX_ENTRY = 24 * 1024 * 1024;
    public static final int MAX_ENTRIES = 4096;

    public interface Cancellation { boolean isCancelled(); }

    public static final class InputException extends IOException {
        public final int code;
        public InputException(int code) { this.code = code; }
    }

    private OfficeInput() {}

    public static byte[] read(InputStream input, String format, long deadlineNanos,
                              Cancellation cancellation) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        for (;;) {
            check(deadlineNanos, cancellation);
            int n = input.read(buffer);
            check(deadlineNanos, cancellation);
            if (n == -1) break;
            if (n == 0) continue;
            if (n > MAX_COMPRESSED - output.size()) throw new InputException(ERROR_TOO_LARGE);
            output.write(buffer, 0, n);
        }
        byte[] bytes = output.toByteArray();
        validate(bytes, format, deadlineNanos, cancellation);
        return bytes;
    }

    public static void validate(byte[] bytes, String format, long deadlineNanos,
                                Cancellation cancellation) throws IOException {
        check(deadlineNanos, cancellation);
        if (bytes == null) throw new InputException(ERROR_FILE);
        if (bytes.length > MAX_COMPRESSED) throw new InputException(ERROR_TOO_LARGE);
        byte[] ole = {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1};
        boolean isOle = bytes.length >= ole.length;
        for (int i = 0; isOle && i < ole.length; i++) isOle = bytes[i] == ole[i];
        if (isOle) throw new InputException(ERROR_PASSWORD);
        String main = "docx".equals(format) ? "word/document.xml" : "xlsx".equals(format)
                ? "xl/workbook.xml" : "pptx".equals(format) ? "ppt/presentation.xml" : null;
        if (main == null || bytes.length < 22 || u32(bytes, 0) != 0x04034b50L) throw new InputException(ERROR_FILE);
        int end = bytes.length - 22;
        while (end >= Math.max(0, bytes.length - 65557)) {
            check(deadlineNanos, cancellation);
            if (u32(bytes, end) == 0x06054b50L && end + 22 + u16(bytes, end + 20) == bytes.length) break;
            end--;
        }
        if (end < Math.max(0, bytes.length - 65557)) throw new InputException(ERROR_FILE);
        int count = u16(bytes, end + 10);
        if (count > MAX_ENTRIES) throw new InputException(ERROR_TOO_LARGE);
        long central = u32(bytes, end + 16), size = u32(bytes, end + 12);
        if (count == 0 || u16(bytes, end + 4) != 0 || u16(bytes, end + 6) != 0
                || u16(bytes, end + 8) != count || central + size != end) throw new InputException(ERROR_FILE);
        Map<String, Long> entries = new HashMap<>();
        Set<Long> offsets = new HashSet<>();
        int p = (int) central;
        for (int i = 0; i < count; i++) {
            check(deadlineNanos, cancellation);
            if (p < 0 || p > end - 46 || u32(bytes, p) != 0x02014b50L) throw new InputException(ERROR_FILE);
            int flags = u16(bytes, p + 8), method = u16(bytes, p + 10);
            if ((flags & 0x41) != 0) throw new InputException(ERROR_PASSWORD);
            int length = u16(bytes, p + 28), extra = u16(bytes, p + 30), comment = u16(bytes, p + 32);
            if (length == 0 || length > 1024 || p + 46L + length + extra + comment > end
                    || u16(bytes, p + 34) != 0 || (method != 0 && method != 8)) throw new InputException(ERROR_FILE);
            String name = name(bytes, p + 46, length);
            long inflated = u32(bytes, p + 24), compressed = u32(bytes, p + 20), offset = u32(bytes, p + 42);
            if (inflated > MAX_ENTRY) throw new InputException(ERROR_TOO_LARGE);
            if (entries.put(name, inflated) != null || !offsets.add(offset) || offset + 30 > central) throw new InputException(ERROR_FILE);
            int local = (int) offset;
            if (u32(bytes, local) != 0x04034b50L || u16(bytes, local + 6) != flags
                    || u16(bytes, local + 8) != method || u16(bytes, local + 26) != length
                    || offset + 30 + length + u16(bytes, local + 28) + compressed > central
                    || !name.equals(name(bytes, local + 30, length))) throw new InputException(ERROR_FILE);
            p += 46 + length + extra + comment;
        }
        if (p != end || !entries.containsKey(main) || !entries.containsKey("[Content_Types].xml")
                || entries.get(main) == 0 || entries.get("[Content_Types].xml") == 0) throw new InputException(ERROR_FILE);
        for (String other : new String[]{"word/document.xml", "xl/workbook.xml", "ppt/presentation.xml"}) {
            if (!other.equals(main) && entries.containsKey(other)) throw new InputException(ERROR_FILE);
        }
        long total = 0;
        byte[] buffer = new byte[16384];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                check(deadlineNanos, cancellation);
                Long expected = entries.remove(entry.getName());
                if (expected == null) throw new InputException(ERROR_FILE);
                long actual = 0;
                int n;
                while ((n = zip.read(buffer)) != -1) {
                    check(deadlineNanos, cancellation);
                    actual += n;
                    total += n;
                    if (actual > MAX_ENTRY || total > MAX_INFLATED) throw new InputException(ERROR_TOO_LARGE);
                }
                if (actual != expected) throw new InputException(ERROR_FILE);
            }
        } catch (IllegalArgumentException e) {
            throw new InputException(ERROR_FILE);
        }
        check(deadlineNanos, cancellation);
        if (!entries.isEmpty()) throw new InputException(ERROR_FILE);
    }

    private static String name(byte[] bytes, int offset, int length) throws IOException {
        String name;
        try {
            name = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, offset, length)).toString();
        } catch (CharacterCodingException e) { throw new InputException(ERROR_FILE); }
        if (name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0
                || name.indexOf('%') >= 0 || name.indexOf('?') >= 0 || name.indexOf('#') >= 0) throw new InputException(ERROR_FILE);
        for (int i = 0; i < name.length(); i++) if (name.charAt(i) < 32 || name.charAt(i) == 127) throw new InputException(ERROR_FILE);
        String[] parts = name.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals(".") || parts[i].equals("..") || parts[i].isEmpty() && i != parts.length - 1) throw new InputException(ERROR_FILE);
        }
        return name;
    }

    private static int u16(byte[] b, int p) { return (b[p] & 255) | (b[p + 1] & 255) << 8; }
    private static long u32(byte[] b, int p) { return (long) u16(b, p) | (long) u16(b, p + 2) << 16; }
    private static void check(long deadline, Cancellation cancellation) throws InputException {
        if (Thread.currentThread().isInterrupted() || cancellation != null && cancellation.isCancelled()) throw new CancellationException();
        if (System.nanoTime() - deadline >= 0) throw new InputException(ERROR_TIMEOUT);
    }
}
