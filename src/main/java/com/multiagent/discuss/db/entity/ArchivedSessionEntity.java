package com.multiagent.discuss.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 归档会话：「结束会话」时把整场讨论的快照落库，供「已归档」只读查看。
 *
 * <ul>
 *   <li>{@code payload} 是会话快照 JSON（会话元数据 + 全部议题产物），
 *       <b>已脱敏，不含任何 API Key</b> —— 由 {@code ArchivedSessionService} 显式挑字段拼出，
 *       不是直接序列化 {@code Session} 对象（那样会把明文 Key 带进来）。</li>
 *   <li>列表用的标量列（title / question_count / …）单独存，避免列表接口去解析大 payload。</li>
 * </ul>
 */
@Entity
@Table(name = "archived_session")
public class ArchivedSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "source_session_id", nullable = false, length = 32)
    private String sourceSessionId;

    @Column(name = "title", nullable = false, length = 128)
    private String title;

    @Column(name = "user_style", length = 64)
    private String userStyle;

    @Column(name = "question_count", nullable = false)
    private int questionCount;

    @Column(name = "agent_count", nullable = false)
    private int agentCount;

    @Column(name = "token_total", nullable = false)
    private long tokenTotal;

    @Column(name = "payload", nullable = false, columnDefinition = "LONGTEXT")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getSourceSessionId() { return sourceSessionId; }
    public void setSourceSessionId(String sourceSessionId) { this.sourceSessionId = sourceSessionId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getUserStyle() { return userStyle; }
    public void setUserStyle(String userStyle) { this.userStyle = userStyle; }
    public int getQuestionCount() { return questionCount; }
    public void setQuestionCount(int questionCount) { this.questionCount = questionCount; }
    public int getAgentCount() { return agentCount; }
    public void setAgentCount(int agentCount) { this.agentCount = agentCount; }
    public long getTokenTotal() { return tokenTotal; }
    public void setTokenTotal(long tokenTotal) { this.tokenTotal = tokenTotal; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
