/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.documents;

import android.os.ParcelFileDescriptor;
import app.nimarkogram.messenger.documents.IPdfDocument;

interface IDocumentRenderer {
    IPdfDocument open(in ParcelFileDescriptor source, IBinder owner, String password);
}
