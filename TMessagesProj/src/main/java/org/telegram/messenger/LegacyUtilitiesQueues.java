/* Modifications Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.messenger;

public class LegacyUtilitiesQueues {

    protected static final DispatchQueue2 stageQueue2 = new DispatchQueue2("stageQueue");
    protected static final DispatchQueue2 globalQueue2 = new DispatchQueue2("globalQueue");
    protected static final DispatchQueue2 cacheClearQueue2 = new DispatchQueue2("cacheClearQueue");
    protected static final DispatchQueue2 searchQueue2 = new DispatchQueue2("searchQueue");
    protected static final DispatchQueue2 phoneBookQueue2 = new DispatchQueue2("phoneBookQueue");
    protected static final DispatchQueue2 themeQueue2 = new DispatchQueue2("themeQueue");
    protected static final DispatchQueue2 externalNetworkQueue2 = new DispatchQueue2("externalNetworkQueue");

    public static volatile DispatchQueue stageQueue = stageQueue2;
    public static volatile DispatchQueue globalQueue = globalQueue2;
    public static volatile DispatchQueue cacheClearQueue = cacheClearQueue2;
    public static volatile DispatchQueue searchQueue = searchQueue2;
    public static volatile DispatchQueue phoneBookQueue = phoneBookQueue2;
    public static volatile DispatchQueue themeQueue = themeQueue2;
    public static volatile DispatchQueue externalNetworkQueue = externalNetworkQueue2;

    protected LegacyUtilitiesQueues() {
    }
}
