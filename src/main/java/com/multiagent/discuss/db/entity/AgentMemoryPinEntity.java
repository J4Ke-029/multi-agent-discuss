package com.multiagent.discuss.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 一条「钉子」：用户在纠错对话里确认过的一条修改要求。
 *
 * <p>追加式 —— 只插、不改。钉子<b>永远不写进</b> {@link AgentMemoryEntity#getContent()}，
 * 而是每次渲染 system prompt 时从本表实时拼出一段独立的【已锁定 · 硬前提】。这是有意的：
 * 心得正文每场都要被模型整体重写，把钉子混在里面就只能靠 prompt 求它「别删」；
 * 放在表里单独渲染，模型想推翻也没有着力点 —— 是<b>结构保证</b>，不是提示词保证。
 *
 * <p>{@code content} 存的是用户当时确认的那句要求原文，就是注入 prompt 的文字本身。
 */
@Entity
@Table(name = "agent_memory_pin")
public class AgentMemoryPinEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "target_type", nullable = false, length = 32)
    private String targetType;

    @Column(name = "target_uuid", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private String targetUuid;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public String getTargetUuid() { return targetUuid; }
    public void setTargetUuid(String targetUuid) { this.targetUuid = targetUuid; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Instant getCreatedAt() { return createdAt; }
}
