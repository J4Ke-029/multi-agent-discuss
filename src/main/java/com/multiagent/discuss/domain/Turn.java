package com.multiagent.discuss.domain;

import java.time.Instant;
import java.util.List;

public class Turn {

    public enum Phase {
        INDEPENDENT_THINK,
        DISCUSSION,
        USER_INTERJECTION,
        CONSENSUS,
        STYLE_FINAL,
        REVISION_PLAN,
        REVISION
    }

    private String speakerId;
    private String speakerName;
    private String role;
    private Phase phase;
    private int round;
    private String content;
    private Instant at = Instant.now();
    /** 本次发言的 token 用量（可能为 null，例如 Host 的总结发言尚未取到 usage）。 */
    private TokenUsage usage;
    /** 本次发言实际启用的 Skill；空列表表示没有相关 Skill。 */
    private List<SkillInvocation> skillInvocations = List.of();
    /** 是否为这次发言执行过 Skill 相关性判断；旧记录被裁剪后会重置为 false。 */
    private boolean skillEvaluated;

    public Turn() {
    }

    public Turn(String speakerId, String speakerName, String role, Phase phase, int round, String content) {
        this.speakerId = speakerId;
        this.speakerName = speakerName;
        this.role = role;
        this.phase = phase;
        this.round = round;
        this.content = content;
    }

    public String getSpeakerId() {
        return speakerId;
    }

    public void setSpeakerId(String speakerId) {
        this.speakerId = speakerId;
    }

    public String getSpeakerName() {
        return speakerName;
    }

    public void setSpeakerName(String speakerName) {
        this.speakerName = speakerName;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Phase getPhase() {
        return phase;
    }

    public void setPhase(Phase phase) {
        this.phase = phase;
    }

    public int getRound() {
        return round;
    }

    public void setRound(int round) {
        this.round = round;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Instant getAt() {
        return at;
    }

    public void setAt(Instant at) {
        this.at = at;
    }

    public TokenUsage getUsage() {
        return usage;
    }

    public void setUsage(TokenUsage usage) {
        this.usage = usage;
    }

    public List<SkillInvocation> getSkillInvocations() {
        return skillInvocations;
    }

    public void setSkillInvocations(List<SkillInvocation> skillInvocations) {
        this.skillInvocations = skillInvocations == null ? List.of() : List.copyOf(skillInvocations);
    }

    public boolean isSkillEvaluated() { return skillEvaluated; }
    public void setSkillEvaluated(boolean skillEvaluated) { this.skillEvaluated = skillEvaluated; }
}
