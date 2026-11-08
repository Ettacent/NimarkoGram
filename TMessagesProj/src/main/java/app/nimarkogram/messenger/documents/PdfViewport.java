/* Copyright (C) 2026 Ettacent. */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.documents;

final class PdfViewport {
    private final double[] ratios;
    private final double[] tops;
    private final boolean[] known;
    double pageWidth;
    double margin;
    double gap;
    double height;

    PdfViewport(int count) {
        ratios = new double[count];
        tops = new double[count + 1];
        known = new boolean[count];
        java.util.Arrays.fill(ratios, Math.sqrt(2));
    }

    int count() { return ratios.length; }
    boolean known(int page) { return known[page]; }
    double top(int page) { return tops[page]; }
    double pageHeight(int page) { return pageWidth * ratios[page]; }

    void layout(double width, double inset, double spacing) {
        margin = inset;
        gap = spacing;
        pageWidth = Math.max(1, width - 2 * inset);
        rebuild();
    }

    void size(int page, int width, int height) {
        ratios[page] = (double) height / width;
        known[page] = true;
        rebuild();
    }

    private void rebuild() {
        double y = margin;
        for (int i = 0; i < count(); i++) {
            tops[i] = y;
            y += pageHeight(i) + gap;
        }
        tops[count()] = y;
        height = count() == 0 ? 0 : y - gap + margin;
    }

    int pageAt(double y) {
        int low = 0, high = count() - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (tops[mid] <= y) low = mid;
            else high = mid - 1;
        }
        return low;
    }

    double fraction(int page, double y) {
        return (y - top(page)) / Math.max(1, pageHeight(page));
    }
}
