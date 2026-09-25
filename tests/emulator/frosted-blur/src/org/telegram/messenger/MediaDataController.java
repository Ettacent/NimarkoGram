package org.telegram.messenger;
public class MediaDataController {
    public static long calcHash(long hash, long value) { return hash * 31 + value; }
}
