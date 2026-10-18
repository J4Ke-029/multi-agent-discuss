package com.multiagent.discuss.api.dto;

/**
 * 生成讨论总结请求。
 * scope: CURRENT_QUESTION（单个议题，questionId 可选，缺省取最后一个议题）
 *        | ALL_QUESTIONS（整个会话所有议题）
 */
public class GenerateSummaryRequest {

    private String scope;
    private String questionId;

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getQuestionId() {
        return questionId;
    }

    public void setQuestionId(String questionId) {
        this.questionId = questionId;
    }
}
