package com.multiagent.discuss.api.dto;

import jakarta.validation.constraints.NotBlank;

public class AskQuestionRequest {

    @NotBlank
    private String content;

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
