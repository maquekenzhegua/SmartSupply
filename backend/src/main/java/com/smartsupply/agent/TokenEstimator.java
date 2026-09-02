package com.smartsupply.agent;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TokenEstimator {

    private static final Logger log = LoggerFactory.getLogger(TokenEstimator.class);
    private final Encoding enc;
    private final double promptPer1k;
    private final double completionPer1k;

    public TokenEstimator(
            @Value("${smartsupply.ai.pricing.prompt-per-1k:0.0015}") double promptPer1k,
            @Value("${smartsupply.ai.pricing.completion-per-1k:0.002}") double completionPer1k) {
        this.promptPer1k = promptPer1k;
        this.completionPer1k = completionPer1k;
        Encoding resolved = null;
        try {
            EncodingRegistry reg = Encodings.newDefaultEncodingRegistry();
            resolved = reg.getEncoding("cl100k_base").orElse(null);
        } catch (Exception e) {
            log.warn("jtokkit init failed: {}", e.toString());
        }
        this.enc = resolved;
    }

    public int estimate(String text) {
        if (text == null || text.isBlank()) return 0;
        if (enc != null) {
            try { return enc.countTokens(text); } catch (Exception ignored) {}
        }
        int len = text.length();
        int cjk = 0;
        for (int i = 0; i < len; i++) { char c = text.charAt(i); if (c >= 0x4E00 && c <= 0x9FFF) cjk++; }
        int nonCjk = len - cjk;
        return Math.max(1, (int) Math.ceil(cjk * 0.6 + nonCjk * 0.25));
    }

    public double estimateCostUsd(int promptTokens, int completionTokens) {
        return promptTokens / 1000.0 * promptPer1k + completionTokens / 1000.0 * completionPer1k;
    }
}
