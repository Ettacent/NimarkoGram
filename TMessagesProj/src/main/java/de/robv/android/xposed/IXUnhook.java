package de.robv.android.xposed;

public interface IXUnhook<T> {
    T getCallback();
    void unhook();
}
