package com.smartsupply.agent;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Prompt 注入与幻觉放行管控。
 * 面试可讲：输入层消毒 + 角色标签隔离 + 输出层引用校验，非“只在 Prompt 里写一句话”。
 */
@Component
public class PromptGuard {

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("ignore\\s+previous\\s+instructions", Pattern.CASE_INSENSITIVE),
            Pattern.compile("system\\s*:\\s*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("忽略.*之前.*指令", Pattern.CASE_INSENSITIVE),
            Pattern.compile("你是.*现在.*扮演", Pattern.CASE_INSENSITIVE),
            Pattern.compile("jailbreak|DAN\\s+mode", Pattern.CASE_INSENSITIVE),
            Pattern.compile("```system", Pattern.CASE_INSENSITIVE)
    );

    private static final int MAX_USER_LEN = 4000;

    public String sanitizeUserInput(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() > MAX_USER_LEN) s = s.substring(0, MAX_USER_LEN) + "…[截断]";
        for (Pattern p : INJECTION_PATTERNS) {
            if (p.matcher(s).find()) {
                s = s.replaceAll("(?i)ignore\\s+previous\\s+instructions", "[已过滤指令注入]");
                s = s.replaceAll("(?i)system\\s*:", "[已过滤角色伪造]:");
            }
        }
        return s;
    }

    public boolean containsInjection(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String lower = raw.toLowerCase(Locale.ROOT);
        for (Pattern p : INJECTION_PATTERNS) {
            if (p.matcher(lower).find()) return true;
        }
        return false;
    }

    public String wrapUserContent(String sanitizedUserContent, String ragContext) {
        StringBuilder sb = new StringBuilder();
        if (ragContext != null && !ragContext.isBlank()) {
            sb.append("<knowledge>\n").append(ragContext).append("\n</knowledge>\n\n");
        }
        sb.append("<user_query>\n").append(sanitizedUserContent).append("\n</user_query>");
        sb.append("\n[约束] 仅基于 <knowledge> 作答；未在 knowledge 中出现的事实必须声明“依据不足”并拒绝臆断；给出 doc 引用。");
        return sb.toString();
    }

    public String citationInstruction(String ragContext) {
        if (ragContext == null || ragContext.isBlank()) return "本次无召回依据，请声明依据不足，不要编造条款。";
        return "请在回答末尾以 [引用] 列出所依据的 knowledge 标题。";
    }
}
