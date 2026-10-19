package com.multiagent.discuss.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 常驻智能体：跨会话复用的领域 Agent 配置。
 * <p>{@code agentUuid} 兼容前端 {@code Agent.agentId}（UUID 字符串），便于跨设备 / 跨会话引用同一个智能体。
 *
 * <p><b>网关</b>：{@code baseUrl / model / apiKeyEnc} 三件套在「保存为常驻」时从源席位整体拷来，
 * {@code apiKeyEnc} 是 jasypt {@code ENC(...)} 密文。这三列是 V1 建表时就有的；V2 曾把
 * {@code apiKeyEnc} 清空并加注释「新数据一律不存 Key」，**那条决策已被 2.4.0 推翻** ——
 * 现在只有这张表自己、{@code SessionService} 与 {@code KeyVault} 能读它，对外一律掩码。
 *
 * <p>三件套是**原子**的：没有 Key 就不该有 baseUrl/model，否则会出现「看着像自带网关、
 * 实际加入时静默回退会话网关」的行。
 */
@Entity
@Table(name = "persistent_agent")
public class PersistentAgentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "agent_uuid", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private String agentUuid;

    @Column(name = "agent_name", nullable = false, length = 64)
    private String agentName;

    @Column(name = "custom_name", length = 64)
    private String customName;

    @Column(name = "persona", columnDefinition = "TEXT")
    private String persona;

    @Column(name = "personality", length = 256)
    private String personality;

    @Column(name = "model", length = 128)
    private String model;

    @Column(name = "base_url", length = 512, columnDefinition = "VARCHAR(512)")
    private String baseUrl;

    @Column(name = "api_key_enc", nullable = true, columnDefinition = "TEXT")
    private String apiKeyEnc;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getAgentUuid() { return agentUuid; }
    public void setAgentUuid(String agentUuid) { this.agentUuid = agentUuid; }
    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public String getCustomName() { return customName; }
    public void setCustomName(String customName) { this.customName = customName; }
    public String getPersona() { return persona; }
    public void setPersona(String persona) { this.persona = persona; }
    public String getPersonality() { return personality; }
    public void setPersonality(String personality) { this.personality = personality; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKeyEnc() { return apiKeyEnc; }
    public void setApiKeyEnc(String apiKeyEnc) { this.apiKeyEnc = apiKeyEnc; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
