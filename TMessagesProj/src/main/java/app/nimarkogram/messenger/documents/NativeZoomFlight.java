/* Modifications Copyright (C) 2026 Ettacent */
package app.nimarkogram.messenger.documents;

final class NativeZoomFlight {
    static final class Slot { private Slot() {} }

    private final Object view;
    private Slot pending;

    NativeZoomFlight(Object view) {
        if (view == null) throw new IllegalArgumentException("view");
        this.view = view;
    }

    boolean owns(Object candidate) { return view == candidate; }
    boolean busy() { return pending != null; }

    Slot acquire() {
        if (pending != null) return null;
        return pending = new Slot();
    }

    boolean retire(Slot slot) {
        if (slot == null || pending != slot) return false;
        pending = null;
        return true;
    }
}
