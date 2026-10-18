package com.multiagent.discuss.api.dto;

import jakarta.validation.constraints.NotBlank;

public class RegisterAgentRequest {

    @NotBlank
    private String name;
    /** 可空：空则继承会话默认网关 */
    private String apiKey;
    private String baseUrl;
    /** 可空：空则继承会话默认 model */
    private String model;
    private String persona;
    /** 性格关键词（可选）：开启后影响该智能体所有发言 */
    private String personality;
    /**
     * true 时强制只用会话默认网关，忽略本请求的 key/url/model。
     *
     * <p>带 {@code agentUuid}（从常驻库加入）时它的含义是「**用本场网关**」——把常驻自带的那份
     * 覆盖掉；不传或 false 则用常驻自己存的网关。此时本请求的 {@code apiKey/baseUrl/model}
     * 一律被忽略（网关与人设都取自常驻行）。
     */
    private Boolean useSessionLlm;
    /**
     * 可空。从常驻库加入时带上该常驻智能体的 uuid，后端据此把它与本场席位对上号，
     * 才能在下一次加入时带上它积累的经验，并取用它自己存的网关。普通注册（临时智能体）留空。
     */
    private String agentUuid;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public String getPersona() {
        return persona;
    }

    public void setPersona(String persona) {
        this.persona = persona;
    }

    public String getPersonality() {
        return personality;
    }

    public void setPersonality(String personality) {
        this.personality = personality;
    }

    public Boolean getUseSessionLlm() {
        return useSessionLlm;
    }

    public void setUseSessionLlm(Boolean useSessionLlm) {
        this.useSessionLlm = useSessionLlm;
    }

    public String getAgentUuid() {
        return agentUuid;
    }

    public void setAgentUuid(String agentUuid) {
        this.agentUuid = agentUuid;
    }
}
