package com.smartsupply.agent.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * 滑动窗口切分：800 字窗口，100 字重叠，适配中文。
 */
public final class TextSplitter {
    private TextSplitter() {}

    public static List<String> split(String text, int chunkSize, int overlap) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        String normalized = text.replaceAll("\\s+", " ").trim();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + chunkSize, normalized.length());
            // 尽量在句号/换行处切断
            if (end < normalized.length()) {
                int dot = normalized.lastIndexOf("。", end);
                int nl = normalized.lastIndexOf("\n", end);
                int cut = Math.max(dot, nl);
                if (cut > start + chunkSize / 2) end = cut + 1;
            }
            out.add(normalized.substring(start, end).trim());
            if (end >= normalized.length()) break;
            start = end - overlap;
        }
        return out;
    }

    public static List<String> split(String text) { return split(text, 800, 100); }
}
