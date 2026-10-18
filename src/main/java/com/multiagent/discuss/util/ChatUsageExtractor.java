package com.multiagent.discuss.util;

import com.multiagent.discuss.domain.TokenUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.Usage;

/**
 * 从 Spring AI 的 {@link ChatResponse} 抽取 {@link TokenUsage}。
 *
 * <p>注意：
 * <ul>
 *   <li>OpenAI 兼容接口通常返回 usage；部分中转不返回（{@code usage=null}）。</li>
 *   <li>{@code promptTokens} / {@code completionTokens} 可能为 0 而非 null。</li>
 *   <li>模型名取自请求 options，不再额外解析。</li>
 * </ul>
 */
public final class ChatUsageExtractor {

    private ChatUsageExtractor() {}

    public static TokenUsage extract(ChatResponse response, String model) {
        if (response == null) {
            return TokenUsage.empty();
        }
        Usage usage = null;
        try {
            // Spring AI 1.0：response.getMetadata().getUsage() 直接可用
            if (response.getMetadata() != null) {
                usage = response.getMetadata().getUsage();
            }
        } catch (Exception ignored) {
        }
        if (usage == null) {
            return new TokenUsage(model, 0, 0);
        }
        long in = safeLong(usage.getPromptTokens());
        long out = safeLong(usage.getCompletionTokens());
        return new TokenUsage(model, in, out);
    }

    private static long safeLong(Number n) {
        return n == null ? 0L : Math.max(0L, n.longValue());
    }
}