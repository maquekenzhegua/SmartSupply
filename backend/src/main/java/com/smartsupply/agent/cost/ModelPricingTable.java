package com.smartsupply.agent.cost;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多模型价格表：成本核算从"一对全局单价"升级为"按模型查价"。
 *
 * 配置（application.yml 或环境变量，SPRING_RELAXED_BINDING）：
 *   smartsupply.ai.pricing.models.<model名>.prompt-per-1k: 0.0015
 *   smartsupply.ai.pricing.models.<model名>.completion-per-1k: 0.002
 * 未命中价格的模型回退到 fallback（原全局单价），保证零配置可运行；
 * 但配置了多模型路由而不配价格时会在日志里大声提醒（成本口径失真比缺省更糟）。
 *
 * 单位与 TokenEstimator 一致：USD / 1k tokens。
 */
@Component
public class ModelPricingTable {

    private static final Logger log = LoggerFactory.getLogger(ModelPricingTable.class);

    public record ModelPrice(double promptPer1k, double completionPer1k) {}

    private final Map<String, ModelPrice> models = new ConcurrentHashMap<>();
    private final ModelPrice fallback;
    private volatile boolean warnedUnpriced = false;

    public ModelPricingTable(Environment env) {
        ModelPrice def = new ModelPrice(
                required(env, "smartsupply.ai.pricing.prompt-per-1k", 0.0015),
                required(env, "smartsupply.ai.pricing.completion-per-1k", 0.002));
        this.fallback = def;
        try {
            var bound = org.springframework.boot.context.properties.bind.Binder.get(env)
                    .bind("smartsupply.ai.pricing.models",
                            org.springframework.boot.context.properties.bind.Bindable.mapOf(String.class, ModelPrice.class))
                    .orElse(Map.of());
            models.putAll(bound);
        } catch (Exception e) {
            log.warn("ModelPricingTable 绑定 smartsupply.ai.pricing.models 失败，全部回退默认单价: {}", e.toString());
        }
        if (!models.isEmpty()) {
            log.info("ModelPricingTable loaded {} model prices (fallback {}/{} per 1k)",
                    models.size(), def.promptPer1k(), def.completionPer1k());
        }
    }

    private static double required(Environment env, String key, double def) {
        String v = env.getProperty(key);
        try { return v == null ? def : Double.parseDouble(v.trim()); }
        catch (NumberFormatException e) { return def; }
    }

    /** 精确匹配优先，其次最长前缀匹配（mimo-v2.5 → mimo 前缀价），最后回退全局单价。 */
    public ModelPrice forModel(String model) {
        if (model == null || model.isBlank()) return fallback;
        ModelPrice exact = models.get(model);
        if (exact != null) return exact;
        String best = null;
        for (String key : models.keySet()) {
            if (model.startsWith(key) && (best == null || key.length() > best.length())) best = key;
        }
        if (best != null) return models.get(best);
        if (!models.isEmpty() && !warnedUnpriced) {
            warnedUnpriced = true;
            log.warn("模型 [{}] 未配置单价（smartsupply.ai.pricing.models），按默认价计成本——多模型路由下成本口径会失真", model);
        }
        return fallback;
    }

    public double costUsd(String model, int promptTokens, int completionTokens) {
        ModelPrice p = forModel(model);
        return promptTokens / 1000.0 * p.promptPer1k() + completionTokens / 1000.0 * p.completionPer1k();
    }

    public Map<String, ModelPrice> all() { return Map.copyOf(models); }
    public ModelPrice fallbackPrice() { return fallback; }
}
