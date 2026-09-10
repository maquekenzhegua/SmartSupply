package com.smartsupply.common;

import org.springframework.ai.chat.model.ChatResponse;

public final class TokenContext {
    private TokenContext() {}
    public record Usage(int promptTokens, int completionTokens, String source) {}
    private static final ThreadLocal<Usage> CTX = new ThreadLocal<>();
    public static void set(int p, int c, String s) { CTX.set(new Usage(p, c, s == null ? "estimated" : s)); }
    public static Usage consume() { Usage u = CTX.get(); CTX.remove(); return u; }
    public static void clear() { CTX.remove(); }

    /** 从 ChatClient.call() 返回的 ChatResponse 元数据提取真实 usage。OpenAI 兼容网关的非流式
     *  响应均携带 usage；mimo 等标准模型走默认 ChatModel——此前只有 muse 专属实现回填
     *  ThreadLocal，其余模型一律落 estimated，台账 token 数与真实用量脱节（实跑实锤：
     *  同一网关同一模型，python-deep 链路 source=actual、java-direct 恒 estimated）。
     *  无 usage（如 Mock 实现）返回 null，由调用方回退估算，不影响 mock 口径。 */
    public static Usage fromChatMetadata(ChatResponse response) {
        if (response == null || response.getMetadata() == null) return null;
        org.springframework.ai.chat.metadata.Usage u = response.getMetadata().getUsage();
        if (u == null) return null;
        int p = (int) Math.max(0, u.getPromptTokens());
        int c = (int) Math.max(0, u.getCompletionTokens());
        if (p <= 0 && c <= 0) return null;
        return new Usage(p, c, "actual");
    }
}
