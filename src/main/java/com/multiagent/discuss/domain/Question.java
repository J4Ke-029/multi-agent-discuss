package com.multiagent.discuss.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class Question {

    private final String id;
    private final String sessionId;
    private final String content;
    private volatile QuestionStatus status = QuestionStatus.THINKING;
    private String errorMessage;
    private final Instant createdAt;
    private Instant updatedAt;

    private final Map<String, AgentThought> thoughts = new ConcurrentHashMap<>();
    private final List<String> skippedAgents = new CopyOnWriteArrayList<>();
    private final List<Turn> transcript = new CopyOnWriteArrayList<>();
    private volatile int completedDiscussionRounds;
    private volatile boolean discussionStarted;

    private ProfessionalConsensus professionalConsensus;
    private String finalAnswer;
    private String explanation;
    private RevisionPlan revisionPlan;
    private List<String> confirmedItemIds = new ArrayList<>();
    private String userConfirmNote;
    private RevisedDocument revisedDocument;

    /** 人话描述当前阶段，驱动前端顶部状态条提示 */
    private volatile String stageHint;

    /** 本议题累计 token 用量（独立思考 + 讨论 + 共识 + 改稿）。 */
    private final TokenUsage tokenUsage = new TokenUsage();

    public Question(String id, String sessionId, String content) {
        this(id, sessionId, content, null, null);
    }

    /**
     * 还原构造器：从归档快照读回议题的真实时间。
     *
     * <p>两个时间传 {@code null} 表示「取当前时间」—— 新建议题走的就是这条（三参构造委托过来）。
     * 归档还原必须走这个重载：{@code createdAt} 是 final 且没有 setter，用三参构造还原的话
     * 它会变成「还原那一刻」，归档总结里的时间行就会显示成重新生成总结的时间。
     */
    public Question(String id, String sessionId, String content, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.content = content;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getContent() {
        return content;
    }

    public QuestionStatus getStatus() {
        return status;
    }

    public synchronized void setStatus(QuestionStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Map<String, AgentThought> getThoughts() {
        return thoughts;
    }

    public List<String> getSkippedAgents() {
        return skippedAgents;
    }

    public List<Turn> getTranscript() {
        return transcript;
    }

    public void addTurn(Turn turn) {
        transcript.add(turn);
        keepMostRecentSkillInvocations(turn.getSpeakerId(), 3);
        this.updatedAt = Instant.now();
    }

    public int getCompletedDiscussionRounds() { return completedDiscussionRounds; }
    public void setCompletedDiscussionRounds(int rounds) {
        this.completedDiscussionRounds = Math.max(0, rounds);
        this.updatedAt = Instant.now();
    }
    public boolean isDiscussionStarted() { return discussionStarted; }
    public void setDiscussionStarted(boolean discussionStarted) {
        this.discussionStarted = discussionStarted;
        this.updatedAt = Instant.now();
    }

    /** 从本议题最近的发言向前保留指定数量的 Skill 调用记录，返回未用完的名额。 */
    public int keepMostRecentSkillInvocations(String speakerId, int allowance) {
        List<Turn> withRecords = transcript.stream()
                .filter(t -> java.util.Objects.equals(speakerId, t.getSpeakerId()))
                .filter(t -> !t.getSkillInvocations().isEmpty())
                .toList();
        int remaining = allowance;
        for (int i = withRecords.size() - 1; i >= 0; i--) {
            Turn existing = withRecords.get(i);
            List<SkillInvocation> records = existing.getSkillInvocations();
            if (remaining <= 0) {
                existing.setSkillInvocations(List.of());
                existing.setSkillEvaluated(false);
            } else if (records.size() > remaining) {
                existing.setSkillInvocations(records.subList(records.size() - remaining, records.size()));
                remaining = 0;
            } else {
                remaining -= records.size();
            }
        }
        return remaining;
    }

    public ProfessionalConsensus getProfessionalConsensus() {
        return professionalConsensus;
    }

    public void setProfessionalConsensus(ProfessionalConsensus professionalConsensus) {
        this.professionalConsensus = professionalConsensus;
        this.updatedAt = Instant.now();
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public void setFinalAnswer(String finalAnswer) {
        this.finalAnswer = finalAnswer;
        this.updatedAt = Instant.now();
    }

    public String getExplanation() {
        return explanation;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation;
        this.updatedAt = Instant.now();
    }

    public RevisionPlan getRevisionPlan() {
        return revisionPlan;
    }

    public void setRevisionPlan(RevisionPlan revisionPlan) {
        this.revisionPlan = revisionPlan;
        this.updatedAt = Instant.now();
    }

    public List<String> getConfirmedItemIds() {
        return confirmedItemIds;
    }

    public void setConfirmedItemIds(List<String> confirmedItemIds) {
        this.confirmedItemIds = confirmedItemIds == null ? new ArrayList<>() : new ArrayList<>(confirmedItemIds);
        this.updatedAt = Instant.now();
    }

    public String getUserConfirmNote() {
        return userConfirmNote;
    }

    public void setUserConfirmNote(String userConfirmNote) {
        this.userConfirmNote = userConfirmNote;
        this.updatedAt = Instant.now();
    }

    public RevisedDocument getRevisedDocument() {
        return revisedDocument;
    }

    public void setRevisedDocument(RevisedDocument revisedDocument) {
        this.revisedDocument = revisedDocument;
        this.updatedAt = Instant.now();
    }

    public String getStageHint() {
        return stageHint;
    }

    public void setStageHint(String stageHint) {
        this.stageHint = stageHint;
        this.updatedAt = Instant.now();
    }

    public TokenUsage getTokenUsage() {
        return tokenUsage;
    }

    /** 累加一次 LLM 调用的 token 用量（线程安全：转写 volatile 字段或合并 AtomicLong）。 */
    public synchronized void addTokenUsage(TokenUsage usage) {
        if (usage == null) return;
        this.tokenUsage.merge(usage);
    }

    public Map<String, Object> progressView() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("questionId", id);
        m.put("status", status.name());
        m.put("stageHint", stageHint);
        m.put("errorMessage", errorMessage);
        Map<String, String> thoughtStatus = new LinkedHashMap<>();
        thoughts.forEach((k, v) -> thoughtStatus.put(k, v.getState().name()));
        m.put("thoughts", thoughtStatus);
        m.put("skippedAgents", skippedAgents);
        m.put("transcriptTurns", transcript.size());
        m.put("completedDiscussionRounds", completedDiscussionRounds);
        m.put("hasRevisionPlan", revisionPlan != null);
        m.put("hasRevisedDocument", revisedDocument != null);
        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("input", tokenUsage.getInputTokens());
        tokens.put("output", tokenUsage.getOutputTokens());
        tokens.put("total", tokenUsage.getTotalTokens());
        tokens.put("cost", tokenUsage.getCostEstimate());
        m.put("tokenUsage", tokens);
        m.put("updatedAt", updatedAt.toString());
        return m;
    }
}
