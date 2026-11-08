/* Modifications Copyright (C) 2026 Ettacent */
package app.nimarkogram.messenger.documents;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

final class PdfWebInput {
    static final int MAX_BYTES = 24 * 1024 * 1024;

    static final class NativeFallback extends IOException {}
    static final class Deadline extends IOException {}

    private PdfWebInput() {}

    static byte[] read(InputStream input, long length, long heapLimit, long deadline,
                       BooleanSupplier cancelled) throws IOException {
        long budget = Math.min(MAX_BYTES, Math.max(0, heapLimit / 8));
        if (length > budget) throw new NativeFallback();
        if (length < 5) throw new IOException("Invalid PDF input");
        check(deadline, cancelled);
        byte[] bytes = new byte[(int) length];
        int offset = 0;
        while (offset < bytes.length) {
            check(deadline, cancelled);
            int n = input.read(bytes, offset, Math.min(65536, bytes.length - offset));
            if (n < 0) throw new IOException("PDF size changed");
            if (n == 0) {
                int value = input.read();
                if (value < 0) throw new IOException("PDF size changed");
                bytes[offset++] = (byte) value;
            } else offset += n;
        }
        check(deadline, cancelled);
        if (input.read() != -1) throw new IOException("PDF size changed");
        boolean header = false;
        for (int i = 0; i <= Math.min(1024, bytes.length) - 5; i++) {
            if (bytes[i] == '%' && bytes[i + 1] == 'P' && bytes[i + 2] == 'D'
                    && bytes[i + 3] == 'F' && bytes[i + 4] == '-') {
                header = true;
                break;
            }
        }
        if (!header) throw new IOException("Invalid PDF input");
        return bytes;
    }

    private static void check(long deadline, BooleanSupplier cancelled) throws IOException {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new CancellationException();
        if (System.nanoTime() > deadline) throw new Deadline();
    }
}
