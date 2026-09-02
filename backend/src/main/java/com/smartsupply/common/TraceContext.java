package com.smartsupply.common;

public final class TraceContext {
    private TraceContext() {}
    private static final ThreadLocal<String> TRACE = new ThreadLocal<>();
    public static void set(String traceId) { TRACE.set(traceId); }
    public static String get() { return TRACE.get(); }
    public static void clear() { TRACE.remove(); }
}
