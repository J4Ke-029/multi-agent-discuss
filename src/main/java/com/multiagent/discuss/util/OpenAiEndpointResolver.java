package com.multiagent.discuss.util;

import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

/**
 * 解析 OpenAI 兼容网关地址，得到 (baseUrl, completionsPath)。
 *
 * <p>Spring AI 的 {@code OpenAiApi} 默认在 baseUrl 后追加 {@code /v1/chat/completions}，
 * 这对智谱 GLM（{@code /v4/chat/completions}）等非 /v1 网关会拼出 {@code /v4/v1/chat/completions} 而 404。
 * 这里按 base-url 末尾的版本段动态决定 completionsPath，使官方 /v1、智谱 /v4、各类中转都能正确接入。
 *
 * <ul>
 *   <li>末尾是 {@code /vN}（v1/v4/…）：保留版本段，path 用 {@code /chat/completions}
 *       → {@code .../v1/chat/completions} 或 {@code .../v4/chat/completions}</li>
 *   <li>用户误粘完整 endpoint {@code .../chat/completions}：剥掉后缀，path 用 {@code /chat/completions}</li>
 *   <li>无版本段（官方根 / 中转根）：走 OpenAI 默认 {@code /v1/chat/completions}</li>
 * </ul>
 */
public final class OpenAiEndpointResolver {

    private OpenAiEndpointResolver() {
    }

    private static final Pattern VERSION_SUFFIX = Pattern.compile("/(v\\d+)$", Pattern.CASE_INSENSITIVE);

    private static final String DEFAULT_BASE = "https://api.openai.com";
    private static final String DEFAULT_PATH = "/v1/chat/completions";
    private static final String CHAT_COMPLETIONS = "/chat/completions";

    public record Resolved(String baseUrl, String completionsPath) {
    }

    public static Resolved resolve(String rawBaseUrl) {
        if (!StringUtils.hasText(rawBaseUrl)) {
            return new Resolved(DEFAULT_BASE, DEFAULT_PATH);
        }
        String url = rawBaseUrl.trim().replace("\r", "").replace("\n", "");
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.isEmpty()) {
            return new Resolved(DEFAULT_BASE, DEFAULT_PATH);
        }

        // 用户误粘完整 endpoint：.../chat/completions
        if (url.toLowerCase().endsWith(CHAT_COMPLETIONS)) {
            String root = url.substring(0, url.length() - CHAT_COMPLETIONS.length());
            while (root.endsWith("/")) {
                root = root.substring(0, root.length() - 1);
            }
            if (root.isEmpty()) {
                return new Resolved(DEFAULT_BASE, CHAT_COMPLETIONS);
            }
            return new Resolved(root, CHAT_COMPLETIONS);
        }

        // 末尾带版本段 /vN：保留版本，path 用 /chat/completions
        // v1 → .../v1/chat/completions；v4（智谱）→ .../v4/chat/completions
        if (VERSION_SUFFIX.matcher(url).find()) {
            return new Resolved(url, CHAT_COMPLETIONS);
        }

        // 无版本段（官方根 / 中转根）：OpenAI 默认 /v1/chat/completions
        return new Resolved(url, DEFAULT_PATH);
    }
}
