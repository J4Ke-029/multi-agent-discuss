package com.multiagent.discuss.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 一篇导师论文的逐篇结构化摘要。
 *
 * <p>只存摘要与元数据，**不存原始文件**：讨论时引用与检索靠的是 Vision 逐页理解的结果
 * （见 {@link MentorPaperPageEntity}），不是原文。代价是换模型后无法重跑页理解。
 *
 * <p>{@code sortOrder} 让论文列表按上传顺序稳定排列（{@code created_at} 同毫秒时会乱序）。
 */
@Entity
@Table(name = "mentor_paper")
public class MentorPaperEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "mentor_id", nullable = false)
    private Long mentorId;

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
    public Long getMentorId() { return mentorId; }
    public void setMentorId(Long mentorId) { this.mentorId = mentorId; }
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
