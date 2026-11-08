/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.documents;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;

public final class BoundedTextLoader {
    public static final int ERROR_FILE = 1;
    public static final int ERROR_TOO_LARGE = 2;
    public static final int ERROR_ENCODING = 3;
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    public static final int MAX_BLOCK_CHARS = 4096;
    public static final int MAX_BLOCK_LINES = 64;
    public static final int MAX_BLOCKS = 8192;

    public static final class Block {
        public final int start;
        public final String text;

        private Block(int start, String text) {
            this.start = start;
            this.text = text;
        }
    }

    public static final class Result {
        public final String text;
        public final String encoding;
        public final List<Block> blocks;

        private Result(String text, String encoding, List<Block> blocks) {
            this.text = text;
            this.encoding = encoding;
            this.blocks = Collections.unmodifiableList(new ArrayList<>(blocks));
        }
    }

    public static final class LoadException extends Exception {
        public final int code;

        private LoadException(int code) {
            this.code = code;
        }
    }

    private BoundedTextLoader() {}

    public static Result load(File file, String encoding) throws LoadException {
        checkCancelled();
        byte[] bytes;
        try {
            if (file == null || !file.isFile()) throw new LoadException(ERROR_FILE);
            if (file.length() > MAX_BYTES) throw new LoadException(ERROR_TOO_LARGE);
            try (FileInputStream input = new FileInputStream(file);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkCancelled();
                    if (count > MAX_BYTES - output.size()) throw new LoadException(ERROR_TOO_LARGE);
                    output.write(buffer, 0, count);
                }
                bytes = output.toByteArray();
            }
        } catch (IOException | SecurityException e) {
            throw new LoadException(ERROR_FILE);
        }
        checkCancelled();
        String bom = null;
        int skip = 0;
        if (bytes.length >= 4 && ((bytes[0] == 0 && bytes[1] == 0 && (bytes[2] & 255) == 254 && (bytes[3] & 255) == 255)
                || ((bytes[0] & 255) == 255 && (bytes[1] & 255) == 254 && bytes[2] == 0 && bytes[3] == 0))) {
            throw new LoadException(ERROR_ENCODING);
        }
        if (bytes.length >= 3 && (bytes[0] & 255) == 239 && (bytes[1] & 255) == 187 && (bytes[2] & 255) == 191) {
            bom = "UTF-8";
            skip = 3;
        } else if (bytes.length >= 2 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 254) {
            bom = "UTF-16LE";
            skip = 2;
        } else if (bytes.length >= 2 && (bytes[0] & 255) == 254 && (bytes[1] & 255) == 255) {
            bom = "UTF-16BE";
            skip = 2;
        }
        String charset = encoding == null || encoding.trim().isEmpty() ? (bom == null ? "UTF-8" : bom) : canonicalEncoding(encoding.trim());
        if (charset == null || bom != null && !bom.equals(charset)) throw new LoadException(ERROR_ENCODING);
        String text;
        try {
            text = Charset.forName(charset).newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, skip, bytes.length - skip)).toString();
        } catch (CharacterCodingException | IllegalArgumentException e) {
            throw new LoadException(ERROR_ENCODING);
        }
        checkCancelled();
        ArrayList<Block> blocks = new ArrayList<>();
        for (int start = 0; start < text.length();) {
            checkCancelled();
            if (blocks.size() == MAX_BLOCKS) throw new LoadException(ERROR_TOO_LARGE);
            int end = Math.min(text.length(), start + MAX_BLOCK_CHARS);
            int lines = 0;
            for (int i = start; i < end; i++) {
                char c = text.charAt(i);
                if (c == '\n' || c == '\r' && (i + 1 == text.length() || text.charAt(i + 1) != '\n')
                        || c == '\u2028' || c == '\u2029') {
                    if (++lines == MAX_BLOCK_LINES) {
                        end = i + 1;
                        break;
                    }
                }
            }
            if (end < text.length()) {
                int newline = text.lastIndexOf('\n', end - 1);
                if (newline >= start + MAX_BLOCK_CHARS / 2) end = newline + 1;
                if (Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) end--;
                if (text.charAt(end - 1) == '\r' && text.charAt(end) == '\n') end--;
            }
            blocks.add(new Block(start, text.substring(start, end)));
            start = end;
        }
        return new Result(text, charset, blocks);
    }

    private static String canonicalEncoding(String encoding) {
        for (String candidate : new String[]{"UTF-8", "windows-1251", "GB18030", "UTF-16LE", "UTF-16BE"}) {
            if (candidate.equalsIgnoreCase(encoding)) return candidate;
        }
        return null;
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    }
}
