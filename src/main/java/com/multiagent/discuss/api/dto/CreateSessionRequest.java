package com.multiagent.discuss.api.dto;

public class CreateSessionRequest {
    private String userStyle;
    private Boolean autoDiscuss;
    private Boolean docEditMode;
    private Integer historyLimit;
    private Integer maxRounds;

    /** 会话默认第三方网关（可随时换供应商） */
    private String llmApiKey;
    private String llmBaseUrl;
    private String llmModel;
    private String llmLabel;

    public String getUserStyle() {
        return userStyle;
    }

    public void setUserStyle(String userStyle) {
        this.userStyle = userStyle;
    }

    public Boolean getAutoDiscuss() {
        return autoDiscuss;
    }

    public void setAutoDiscuss(Boolean autoDiscuss) {
        this.autoDiscuss = autoDiscuss;
    }

    public Boolean getDocEditMode() {
        return docEditMode;
    }

    public void setDocEditMode(Boolean docEditMode) {
        this.docEditMode = docEditMode;
    }

    public Integer getHistoryLimit() {
        return historyLimit;
    }

    public void setHistoryLimit(Integer historyLimit) {
        this.historyLimit = historyLimit;
    }

    public Integer getMaxRounds() {
        return maxRounds;
    }

    public void setMaxRounds(Integer maxRounds) {
        this.maxRounds = maxRounds;
    }

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
