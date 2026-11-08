/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.documents;

import android.os.ParcelFileDescriptor;

interface IPdfDocument {
    int getPageCount();
    int[] getPageSize(int page);
    void render(int page, int renderWidth, int renderHeight, int x, int y,
            int width, int height, in ParcelFileDescriptor output);
    void close();
}
