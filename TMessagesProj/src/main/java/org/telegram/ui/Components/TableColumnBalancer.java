/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import android.text.Layout;
import android.text.TextPaint;

import java.text.BreakIterator;
import java.util.List;

public final class TableColumnBalancer {

    public static final int MAX_LINES = 4;
    private static final float WRAP_SLACK = 1.12f;

    public static final class Cell {
        public int column;
        public int span;
        public int padding;
        public int minWidth;
        public int maxWidth;
        public float[] paragraphs;
        public boolean fixed;

        int widthFor(int lines) {
            if (fixed || lines <= 1 || paragraphs == null || minWidth >= maxWidth) {
                return padding + maxWidth;
            }
            if (lineCount(maxWidth) > lines) {
                return padding + maxWidth;
            }
            int lo = minWidth, hi = maxWidth;
            while (lo < hi) {
                final int mid = (lo + hi) >>> 1;
                if (lineCount(mid) <= lines) {
                    hi = mid;
                } else {
                    lo = mid + 1;
                }
            }
            return padding + lo;
        }

        private int lineCount(int width) {
            if (width <= 0) return Integer.MAX_VALUE;
            int count = 0;
            for (float p : paragraphs) {
                if (p <= width) {
                    count++;
                } else {
                    count += (int) Math.ceil(p * WRAP_SLACK / width);
                }
            }
            return Math.max(1, count);
        }
    }

    private TableColumnBalancer() {}

    public static Cell fixed(int column, int span, int width) {
        final Cell cell = new Cell();
        cell.column = column;
        cell.span = Math.max(1, span);
        cell.fixed = true;
        cell.minWidth = cell.maxWidth = Math.max(0, width);
        return cell;
    }

    public static Cell measure(CharSequence text, TextPaint paint, int column, int span, int padding, int maxContentWidth, int laidOutWidth) {
        final Cell cell = new Cell();
        cell.column = column;
        cell.span = Math.max(1, span);
        cell.padding = padding;
        cell.maxWidth = Math.max(0, Math.min(maxContentWidth, laidOutWidth));
        if (text == null || text.length() == 0 || paint == null) {
            cell.minWidth = cell.maxWidth;
            cell.fixed = true;
            return cell;
        }

        int paragraphCount = 1;
        for (int i = 0, n = text.length(); i < n; i++) {
            if (text.charAt(i) == '\n') paragraphCount++;
        }
        cell.paragraphs = new float[paragraphCount];
        int start = 0, p = 0;
        for (int i = 0, n = text.length(); i <= n; i++) {
            if (i == n || text.charAt(i) == '\n') {
                cell.paragraphs[p++] = i > start ? measure(text, start, i, paint) : 0;
                start = i + 1;
            }
        }

        float minWidth = 0;
        final String string = text.toString();
        final BreakIterator breaks = BreakIterator.getLineInstance();
        breaks.setText(string);
        int s = breaks.first();
        for (int e = breaks.next(); e != BreakIterator.DONE; s = e, e = breaks.next()) {
            int end = e;
            while (end > s && Character.isWhitespace(string.charAt(end - 1))) end--;
            if (end > s) {
                minWidth = Math.max(minWidth, measure(text, s, end, paint));
            }
        }
        cell.minWidth = Math.min(cell.maxWidth, (int) Math.ceil(minWidth));
        return cell;
    }

    private static float measure(CharSequence text, int start, int end, TextPaint paint) {
        try {
            return Layout.getDesiredWidth(text, start, end, paint);
        } catch (Exception e) {
            return paint.measureText(text, start, end);
        }
    }

    public static int[] compute(List<Cell> cells, int columnCount, int available, int minColumnWidth) {
        int[] widths = null;
        for (int lines = 1; lines <= MAX_LINES; lines++) {
            widths = widthsFor(cells, columnCount, lines, minColumnWidth);
            if (sum(widths) <= available) break;
        }
        if (widths == null) return new int[Math.max(0, columnCount)];

        int leftover = available - sum(widths);
        if (leftover > 0) {
            final int[] natural = widthsFor(cells, columnCount, 1, minColumnWidth);
            int growable = 0;
            for (int c = 0; c < columnCount; c++) growable += Math.max(0, natural[c] - widths[c]);
            if (growable > 0) {
                final float k = Math.min(1f, leftover / (float) growable);
                for (int c = 0; c < columnCount; c++) {
                    widths[c] += (int) Math.floor(Math.max(0, natural[c] - widths[c]) * k);
                }
            }
        }
        return widths;
    }

    private static int[] widthsFor(List<Cell> cells, int columnCount, int lines, int minColumnWidth) {
        final int[] widths = new int[Math.max(0, columnCount)];
        for (int c = 0; c < widths.length; c++) widths[c] = minColumnWidth;
        for (int i = 0, n = cells.size(); i < n; i++) {
            final Cell cell = cells.get(i);
            if (cell.span != 1 || cell.column < 0 || cell.column >= columnCount) continue;
            widths[cell.column] = Math.max(widths[cell.column], cell.widthFor(lines));
        }
        for (int i = 0, n = cells.size(); i < n; i++) {
            final Cell cell = cells.get(i);
            if (cell.span <= 1 || cell.column < 0 || cell.column >= columnCount) continue;
            final int end = Math.min(columnCount, cell.column + cell.span);
            int covered = 0;
            for (int c = cell.column; c < end; c++) covered += widths[c];
            int deficit = cell.widthFor(lines) - covered;
            for (int c = cell.column; c < end && deficit > 0; c++) {
                final int left = end - c;
                final int extra = (deficit + left - 1) / left;
                widths[c] += extra;
                deficit -= extra;
            }
        }
        return widths;
    }

    private static int sum(int[] widths) {
        int s = 0;
        for (int w : widths) s += w;
        return s;
    }
}
