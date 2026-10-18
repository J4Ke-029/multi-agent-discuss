package com.multiagent.discuss.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 常驻智能体读过的**一个文件**：逐页理解的结果 + 九字段结构化摘要。
 *
 * <p>这张表存在的理由：0.9.0 之后常驻智能体的名字 / 人设 / 网关都跨会话跟着账号走了，
 * 唯独知识还只活在 {@code AgentKnowledgeStore} 那个内存 map 里，会话一销毁就没了 ——
 * 每次加入新会话都得重传文件。有了它，加入会话时直接把页知识灌回去即可。
 *
 * <p>与 {@link MentorPaperEntity} 逐列对齐（连九个字段都一样），差别只在挂靠对象
 * 从 {@code mentor} 换成了 {@code persistent_agent}，以及**写入时机是「结束会话」
 * 而不是「上传当时」** —— 上传时它可能还没被选为常驻。
 *
 * <p>只存摘要与页理解，**不存原始文件**：代价是换模型后摘要可以重跑（走
 * {@code POST /api/agents/persistent/docs/{docId}/summarize}），**页理解不能**，
 * 且删掉的知识无法重建 —— 界面上的删除入口因此必须二次确认。
 *
 * <p>{@code sortOrder} 让知识列表按入库顺序稳定排列（{@code created_at} 同毫秒时会乱序）。
 */
@Entity
@Table(name = "persistent_agent_doc")
public class PersistentAgentDocEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "persistent_agent_id", nullable = false)
    private Long persistentAgentId;

    @Column(name = "file_name", nullable = false, length = 256)
    private String fileName;

    @Column(name = "page_count", nullable = false)
    private int pageCount;

    @Column(name = "title", length = 512)
    private String title;

    @Column(name = "authors", length = 512)
    private String authors;

    @Column(name = "pub_year", length = 16)
    private String pubYear;

    @Column(name = "abstract_text", columnDefinition = "LONGTEXT")
    private String abstractText;

    @Column(name = "research_question", columnDefinition = "TEXT")
    private String researchQuestion;

    @Column(name = "methodology", columnDefinition = "LONGTEXT")
    private String methodology;

    @Column(name = "findings", columnDefinition = "LONGTEXT")
    private String findings;

    @Column(name = "limitations", columnDefinition = "TEXT")
    private String limitations;

    @Column(name = "keywords", length = 512)
    private String keywords;

    @Column(name = "summary_status", nullable = false, length = 32)
    private String summaryStatus;

    @Column(name = "summary_message", length = 512)
    private String summaryMessage;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public Long getPersistentAgentId() { return persistentAgentId; }
    public void setPersistentAgentId(Long persistentAgentId) { this.persistentAgentId = persistentAgentId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public int getPageCount() { return pageCount; }
    public void setPageCount(int pageCount) { this.pageCount = pageCount; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getAuthors() { return authors; }
    public void setAuthors(String authors) { this.authors = authors; }
    public String getPubYear() { return pubYear; }
    public void setPubYear(String pubYear) { this.pubYear = pubYear; }
    public String getAbstractText() { return abstractText; }
    public void setAbstractText(String abstractText) { this.abstractText = abstractText; }
    public String getResearchQuestion() { return researchQuestion; }
    public void setResearchQuestion(String researchQuestion) { this.researchQuestion = researchQuestion; }
    public String getMethodology() { return methodology; }
    public void setMethodology(String methodology) { this.methodology = methodology; }
    public String getFindings() { return findings; }
    public void setFindings(String findings) { this.findings = findings; }
    public String getLimitations() { return limitations; }
    public void setLimitations(String limitations) { this.limitations = limitations; }
    public String getKeywords() { return keywords; }
    public void setKeywords(String keywords) { this.keywords = keywords; }
    public String getSummaryStatus() { return summaryStatus; }
    public void setSummaryStatus(String summaryStatus) { this.summaryStatus = summaryStatus; }
    public String getSummaryMessage() { return summaryMessage; }
    public void setSummaryMessage(String summaryMessage) { this.summaryMessage = summaryMessage; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
