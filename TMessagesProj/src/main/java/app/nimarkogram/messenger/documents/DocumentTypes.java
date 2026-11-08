/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.documents;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class DocumentTypes {
    public enum Kind { PDF, TEXT, OFFICE, NONE }

    private static final Set<String> TEXT = new HashSet<>(Arrays.asList(
            "txt", "text", "log", "csv", "tsv", "json", "jsonl", "ndjson", "xml",
            "yaml", "yml", "ini", "cfg", "conf", "properties", "md", "markdown",
            "java", "kt", "kts", "c", "h", "cc", "cpp", "cxx", "hpp", "cs",
            "py", "pyw", "js", "jsx", "ts", "tsx", "mjs", "cjs", "go", "rs",
            "swift", "m", "mm", "rb", "php", "sh", "bash", "zsh", "fish",
            "sql", "css", "scss", "less", "html", "htm", "xhtml", "svg",
            "toml", "gradle", "groovy", "scala", "lua", "r", "dart", "vue",
            "svelte", "tex", "diff", "patch", "bat", "cmd", "ps1"));
    private static final Set<String> MIME = new HashSet<>(Arrays.asList(
            "text/plain", "text/csv", "text/tab-separated-values", "text/markdown",
            "text/x-markdown", "application/json", "application/x-ndjson",
            "application/xml", "text/xml", "application/yaml", "application/x-yaml",
            "text/yaml", "text/x-yaml", "text/x-log", "text/x-java-source",
            "text/x-python", "text/x-c", "text/x-c++", "text/css",
            "text/javascript", "application/javascript", "application/x-sh",
            "text/html", "application/xhtml+xml", "image/svg+xml"));

    private DocumentTypes() {}

    public static Kind detect(String fileName, String mimeType) {
        String name = baseName(fileName);
        String extension = extension(name);
        String mime = mimeType == null ? "" : mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if ("pdf".equals(extension)) return Kind.PDF;
        if (!officeFormat(fileName, mimeType).isEmpty()) return Kind.OFFICE;
        if (TEXT.contains(extension) || "makefile".equals(name) || "dockerfile".equals(name)) return Kind.TEXT;
        if (!extension.isEmpty()) return Kind.NONE;
        if ("application/pdf".equals(mime)) return Kind.PDF;
        return MIME.contains(mime) ? Kind.TEXT : Kind.NONE;
    }

    public static boolean isMonospaced(String fileName) {
        String name = baseName(fileName);
        String ext = extension(name);
        return "makefile".equals(name) || "dockerfile".equals(name)
                || TEXT.contains(ext) && !"txt".equals(ext) && !"text".equals(ext)
                && !"md".equals(ext) && !"markdown".equals(ext);
    }

    public static String officeFormat(String fileName, String mimeType) {
        String ext = extension(baseName(fileName));
        if ("docx".equals(ext) || "xlsx".equals(ext) || "pptx".equals(ext)) return ext;
        if (!ext.isEmpty()) return "";
        String mime = mimeType == null ? "" : mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        switch (mime) {
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document": return "docx";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": return "xlsx";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation": return "pptx";
            default: return "";
        }
    }

    public static String officeMime(String format) {
        switch (format) {
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            default: return "application/octet-stream";
        }
    }

    private static String baseName(String value) {
        if (value == null) return "";
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        return value.substring(slash + 1).toLowerCase(Locale.ROOT);
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }
}
