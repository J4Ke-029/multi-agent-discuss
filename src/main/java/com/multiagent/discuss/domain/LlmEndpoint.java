package com.multiagent.discuss.domain;

/**
 * 会话级 LLM 网关（第三方 Key / Base URL / Model）。
 * 有值时 Host 与未单独配置的领域 Agent 走此配置；无值时回落 application 全局默认。
 */
public class LlmEndpoint {

    private String apiKey;
    private String baseUrl;
    private String model;
    /** 展示用标签，如「智谱」「中转A」 */
    private String label;

    public LlmEndpoint() {
    }

    public LlmEndpoint(String apiKey, String baseUrl, String model, String label) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.label = label;
    }

    public boolean hasCredentials() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String maskedApiKey() {
        if (apiKey == null || apiKey.length() < 8) {
            return apiKey == null || apiKey.isBlank() ? "" : "***";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
