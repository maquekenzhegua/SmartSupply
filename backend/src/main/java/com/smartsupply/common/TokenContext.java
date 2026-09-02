package com.smartsupply.common;

public final class TokenContext {
    private TokenContext() {}
    public record Usage(int promptTokens, int completionTokens, String source) {}
    private static final ThreadLocal<Usage> CTX = new ThreadLocal<>();
    public static void set(int p, int c, String s) { CTX.set(new Usage(p, c, s == null ? "estimated" : s)); }
    public static Usage consume() { Usage u = CTX.get(); CTX.remove(); return u; }
    public static void clear() { CTX.remove(); }
}
