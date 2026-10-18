package com.multiagent.discuss.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 常驻智能体知识库里的一页：该页的文字层 + Vision 理解结果（{@code PageBlock.Understanding} 的 JSON）。
 *
 * <p>页是讨论阶段的**检索单元**，所以一页一行而不是塞进 {@link PersistentAgentDocEntity}
 * 的一个 JSON 列：知识列表不该顺带把全部页理解拉进内存，且独立成行才能按文件、再按页过滤。
 *
 * <p>{@code PageBlock.imageRef} **不入库** —— 它指向临时目录里的渲染页图，
 * 重启或删会话即失效，前端从不读它。
 *
 * <p>{@code PageBlock.sourceName} 同样**没有对应列**：它就是所属文件的
 * {@code file_name}，读回来时由 {@code loadPages} 回填（照 {@code MentorService} 的做法）。
 */
@Entity
@Table(name = "persistent_agent_doc_page")
public class PersistentAgentDocPageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "doc_id", nullable = false)
    private Long docId;

    @Column(name = "page_index", nullable = false)
    private int pageIndex;

    @Column(name = "plain_hint", columnDefinition = "LONGTEXT")
    private String plainHint;

    @Column(name = "understanding", columnDefinition = "LONGTEXT")
    private String understanding;

    public Long getId() { return id; }
    public Long getDocId() { return docId; }
    public void setDocId(Long docId) { this.docId = docId; }
    public int getPageIndex() { return pageIndex; }
    public void setPageIndex(int pageIndex) { this.pageIndex = pageIndex; }
    public String getPlainHint() { return plainHint; }
    public void setPlainHint(String plainHint) { this.plainHint = plainHint; }
    public String getUnderstanding() { return understanding; }
    public void setUnderstanding(String understanding) { this.understanding = understanding; }
}
