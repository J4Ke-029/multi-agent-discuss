package com.multiagent.discuss.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 常驻智能体 / 导师的跨会话「经验心得」—— 一个目标一份，每场讨论结束后整体重写覆盖。
 *
 * <p>与 {@link MentorEntity} 等表的一处<b>有意破例</b>：{@code updatedAt} 是
 * {@code insertable = true, updatable = true}，由 Java 侧显式 {@code setUpdatedAt(Instant.now())}。
 *
 * <p>项目其余表都把时间戳交给 MySQL（{@code insertable=false, updatable=false} + DDL 的
 * {@code DEFAULT / ON UPDATE}），那样写起来省事，但 {@code repo.save()} 返回的实体里
 * 时间戳仍然是 {@code null}（Hibernate 不会回读数据库默认值）。而「最后一次总结时间」
 * 恰恰是本功能查看弹窗的全部意义所在 —— summarize 完要立刻把它返回给前端，不能是 null。
 *
 * <p>⚠️ 改这张表的写入路径时，<b>务必记得 setUpdatedAt</b>：漏了会把上一场的时间原样写回去，
 * 表现为「总结了但时间没变」。DDL 里的 {@code ON UPDATE CURRENT_TIMESTAMP(3)} 保留作裸 SQL 兜底。
 *
 * <p>{@code targetUuid} 是多态引用（{@code PERSISTENT_AGENT} → {@code persistent_agent.agent_uuid}，
 * {@code MENTOR} → {@code mentor.mentor_uuid}），建不了外键，删除级联由 service 显式处理。
 */
@Entity
@Table(name = "agent_memory")
public class AgentMemoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "target_type", nullable = false, length = 32)
    private String targetType;

    @Column(name = "target_uuid", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private String targetUuid;

    @Column(name = "content", columnDefinition = "LONGTEXT")
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    /** 见类注释：本表唯一破例由 Java 侧维护的列。 */
    @Column(name = "updated_at", nullable = false, insertable = true, updatable = true)
    private Instant updatedAt;

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
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
