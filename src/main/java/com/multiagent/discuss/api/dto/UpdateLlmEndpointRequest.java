package com.multiagent.discuss.api.dto;

public class UpdateLlmEndpointRequest {
    private String llmApiKey;
    private String llmBaseUrl;
    private String llmModel;
    private String llmLabel;

    public String getLlmApiKey() {
        return llmApiKey;
    }

    public void setLlmApiKey(String llmApiKey) {
        this.llmApiKey = llmApiKey;
    }

    public String getLlmBaseUrl() {
        return llmBaseUrl;
    }

    public void setLlmBaseUrl(String llmBaseUrl) {
        this.llmBaseUrl = llmBaseUrl;
    }

    public String getLlmModel() {
        return llmModel;
    }

    public void setLlmModel(String llmModel) {
        this.llmModel = llmModel;
    }

    public String getLlmLabel() {
        return llmLabel;
    }

    public void setLlmLabel(String llmLabel) {
        this.llmLabel = llmLabel;
    }
}
