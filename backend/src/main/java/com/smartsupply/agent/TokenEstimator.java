package com.smartsupply.agent;

import org.springframework.stereotype.Component;

/**
 * Token 估算：优先按模型实际计费口径的近似，中文按字符/词混合，英文按 BPE 近似。
 * 生产替换为 tiktoken / tokenizer 服务即可，此处保证“可观测可归因”且离线可跑。
 */
@Component
public class TokenEstimator {

    public int estimate(String text) {
        if (text == null || text.isBlank()) return 0;
        int len = text.length();
        int cjk = 0;
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) cjk++;
        }
        int nonCjk = len - cjk;
        double tokens = cjk * 0.6 + nonCjk * 0.25;
        return Math.max(1, (int) Math.ceil(tokens));
    }

    public double estimateCostUsd(int promptTokens, int completionTokens) {
        double promptPer1k = 0.0015;
        double completionPer1k = 0.002;
        return promptTokens / 1000.0 * promptPer1k + completionTokens / 1000.0 * completionPer1k;
    }
}
