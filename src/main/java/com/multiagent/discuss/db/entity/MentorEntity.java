package com.multiagent.discuss.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 导师：账号级可复用的论文语料库，供「导师模式」智能体使用。
 *
 * <p>导师**保存 API Key**（{@code apiKeyEnc}，jasypt {@code ENC(...)} 密文），这样跨会话、跨设备
 * 能直接以自己配置的网关跑讨论，不必每次重选预设。列表接口只回脱敏值，明文走 reveal 接口（仅 owner）。
 *
 * <p>⚠️ 这条曾是导师与 {@link PersistentAgentEntity} 的「关键差别」，但自 0.9.0 起常驻智能体
 * 也保存网关（同一套 {@code KeyVault} 加密 + 掩码 + reveal 口径），两者在这点上已经一致。
 *
 * <p>{@code mentorUuid} 是跨会话引用用的稳定标识（对齐 {@code persistent_agent.agent_uuid}）：
 * 会话里的智能体席位记录 uuid 而不是主键 id，这样账号级行被删掉后，历史归档仍能解释那个席位是什么。
 */
@Entity
@Table(name = "mentor")
public class MentorEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "mentor_uuid", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private String mentorUuid;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "discipline", length = 128)
    private String discipline;

    @Column(name = "focus", columnDefinition = "TEXT")
    private String focus;

    @Column(name = "persona", columnDefinition = "TEXT")
    private String persona;

    @Column(name = "personality", length = 256)
    private String personality;

    @Column(name = "model", length = 128)
    private String model;

    @Column(name = "base_url", length = 512, columnDefinition = "VARCHAR(512)")
    private String baseUrl;

    @Column(name = "api_key_enc", columnDefinition = "TEXT")
    private String apiKeyEnc;

    @Column(name = "style_profile", columnDefinition = "LONGTEXT")
    private String styleProfile;

    @Column(name = "knowledge_map", columnDefinition = "LONGTEXT")
    private String knowledgeMap;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "status_message", length = 512)
    private String statusMessage;

    @Column(name = "paper_count", nullable = false)
    private int paperCount;

    @Column(name = "page_count", nullable = false)
    private int pageCount;

    @Column(name = "progress_current", nullable = false)
    private int progressCurrent;

    @Column(name = "progress_total", nullable = false)
    private int progressTotal;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getMentorUuid() { return mentorUuid; }
    public void setMentorUuid(String mentorUuid) { this.mentorUuid = mentorUuid; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDiscipline() { return discipline; }
    public void setDiscipline(String discipline) { this.discipline = discipline; }
    public String getFocus() { return focus; }
    public void setFocus(String focus) { this.focus = focus; }
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
    public String getStyleProfile() { return styleProfile; }
    public void setStyleProfile(String styleProfile) { this.styleProfile = styleProfile; }
    public String getKnowledgeMap() { return knowledgeMap; }
    public void setKnowledgeMap(String knowledgeMap) { this.knowledgeMap = knowledgeMap; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStatusMessage() { return statusMessage; }
    public void setStatusMessage(String statusMessage) { this.statusMessage = statusMessage; }
    public int getPaperCount() { return paperCount; }
    public void setPaperCount(int paperCount) { this.paperCount = paperCount; }
    public int getPageCount() { return pageCount; }
    public void setPageCount(int pageCount) { this.pageCount = pageCount; }
    public int getProgressCurrent() { return progressCurrent; }
    public void setProgressCurrent(int progressCurrent) { this.progressCurrent = progressCurrent; }
    public int getProgressTotal() { return progressTotal; }
    public void setProgressTotal(int progressTotal) { this.progressTotal = progressTotal; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
