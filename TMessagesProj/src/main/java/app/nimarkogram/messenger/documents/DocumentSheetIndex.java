/* Modifications Copyright (C) 2026 Ettacent */
package app.nimarkogram.messenger.documents;

import java.util.Locale;

final class DocumentSheetIndex {
    private final String[] names, searchable;
    private final int[] rows;
    private int size;

    DocumentSheetIndex(String[] names) {
        this.names = names.clone();
        searchable = new String[names.length];
        rows = new int[names.length];
        for (int i = 0; i < names.length; i++) searchable[i] = names[i].toLowerCase(Locale.ROOT);
        filter("");
    }

    void filter(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int number = -1;
        try { number = Integer.parseInt(needle) - 1; } catch (NumberFormatException ignored) {}
        size = 0;
        for (int i = 0; i < names.length; i++) {
            if (needle.isEmpty() || searchable[i].contains(needle) || i == number) rows[size++] = i;
        }
    }

    int size() { return size; }
    int indexAt(int row) {
        if (row < 0 || row >= size) throw new IndexOutOfBoundsException();
        return rows[row];
    }
    String nameAt(int row) { return names[indexAt(row)]; }
}
