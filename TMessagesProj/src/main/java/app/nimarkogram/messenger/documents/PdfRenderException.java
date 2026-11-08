/* Modifications Copyright (C) 2026 Ettacent */
package app.nimarkogram.messenger.documents;

final class PdfRenderException extends IllegalStateException {
    private static final String PREFIX = "NGPDF:";

    PdfRenderException(int code) {
        super(PREFIX + code);
    }

    static int code(Throwable error, int fallback) {
        String message = error.getMessage();
        if (error instanceof IllegalStateException && message != null && message.startsWith(PREFIX)) {
            try {
                int code = Integer.parseInt(message.substring(PREFIX.length()));
                if (code >= 1 && code <= 6) return code;
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }
}
