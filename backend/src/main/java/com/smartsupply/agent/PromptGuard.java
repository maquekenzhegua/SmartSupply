package com.smartsupply.agent;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Prompt 注入与幻觉放行管控。
 * 三层防护：输入层消毒 + 角色标签隔离 + 输出层引用校验。
 */
@Component
public class PromptGuard {

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("ignore\\s+(all\\s+)?(previous|prior|above)\\s+(instructions?|prompts?|rules?)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("disregard\\s+(all\\s+)?(previous|prior|above|your)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("system\\s*:\\s*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("忽略(以上|上面|之前|以前|前面)?(的)?(所有|全部)?(指令|提示词?|约束|规则)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("忘记(之前|以前|上面|前面)(的)?(所有|全部)?(指令|设置|提示)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("你是.*现在.*扮演|扮演(一个|新的|另一个)?(系统|管理员|DAN)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("jailbreak|DAN\\s+mode|越狱模式|开发者模式", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(输出|透露|显示|打印|reveal|print|show).{0,6}(你的)?(系统提示|system\\s+prompt|初始指令)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("</\\s*(system|assistant|knowledge|user_query)\\s*>", Pattern.CASE_INSENSITIVE),
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
            sb.append("<knowledge>\n").append(neutralizeTags(ragContext)).append("\n</knowledge>\n\n");
        }
        sb.append("<user_query>\n").append(neutralizeTags(sanitizedUserContent)).append("\n</user_query>");
        sb.append("\n[约束] 仅基于 <knowledge> 作答；未在 knowledge 中出现的事实必须声明“依据不足”并拒绝臆断；给出 doc 引用。");
        return sb.toString();
    }

    /**
     * 中和沙箱标签的闭合序列：用户输入里出现 </user_query> / </knowledge> 会在拼接时提前
     * 逃出沙箱标签，把后续系统约束变成"用户内容"。只转义这两个闭合标签，不影响正常代码片段。
     */
    private String neutralizeTags(String s) {
        if (s == null) return "";
        return s.replaceAll("(?i)</\\s*(user_query|knowledge)\\s*>", "&lt;/$1&gt;");
    }

    public String citationInstruction(String ragContext) {
        if (ragContext == null || ragContext.isBlank()) return "本次无召回依据，请声明依据不足，不要编造条款。";
        return "请在回答末尾以 [引用] 列出所依据的 knowledge 标题。";
    }
}
