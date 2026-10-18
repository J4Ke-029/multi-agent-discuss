package com.multiagent.discuss.domain;

import java.time.Instant;

/**
 * 单次 LLM 调用的 token 用量。
 *
 * <p>Spring AI 的 {@code ChatResponse.getMetadata().getUsage()} 字段为非 null 但可能为 0；
 * 部分 OpenAI 兼容网关不返回 usage（{@code usage=null}），此时 model=unknown。
 */
public class TokenUsage {

    private String model;
    private long inputTokens;
    private long outputTokens;
    private long totalTokens;
    /** 估算成本（人民币），按粗略单价 0.002 元/1k tokens；网关未给出价格时仅做参考。 */
    private double costEstimate;

    public TokenUsage() {
    }

    public TokenUsage(String model, long inputTokens, long outputTokens) {
        this.model = model;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.totalTokens = inputTokens + outputTokens;
        this.costEstimate = estimate(inputTokens, outputTokens);
    }

    public static TokenUsage empty() {
        return new TokenUsage(null, 0, 0);
    }

    public TokenUsage merge(TokenUsage other) {
        if (other == null) return this;
        this.inputTokens += other.inputTokens;
        this.outputTokens += other.outputTokens;
        this.totalTokens = this.inputTokens + this.outputTokens;
        this.costEstimate = estimate(this.inputTokens, this.outputTokens);
        return this;
    }

    /** 粗略估算：0.002 元/1k tokens（不分输入输出）。生产应按网关单价表查询。 */
    public static double estimate(long in, long out) {
        return Math.round(((in + out) / 1000.0) * 0.002 * 10000.0) / 10000.0;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public long getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(long inputTokens) {
        this.inputTokens = inputTokens;
    }

    public long getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(long outputTokens) {
        this.outputTokens = outputTokens;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(long totalTokens) {
        this.totalTokens = totalTokens;
    }

    public double getCostEstimate() {
        return costEstimate;
    }

    public void setCostEstimate(double costEstimate) {
        this.costEstimate = costEstimate;
    }
}