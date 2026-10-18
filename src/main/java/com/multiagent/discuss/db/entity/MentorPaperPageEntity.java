package com.multiagent.discuss.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 导师论文的一页：该页的文字层 + Vision 理解结果（{@code PageBlock.Understanding} 的 JSON）。
 *
 * <p>页是讨论阶段的**检索单元**，所以一页一行而不是塞进 {@link MentorPaperEntity} 的一个 JSON 列：
 * 论文索引列表不该顺带把全部页理解拉进内存，且独立成行才能按论文、再按页过滤。
 *
 * <p>{@code PageBlock.imageRef} **不入库** —— 它指向临时目录里的渲染页图，
 * 重启或删会话即失效，前端从不读它。
 */
@Entity
@Table(name = "mentor_paper_page")
public class MentorPaperPageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "paper_id", nullable = false)
    private Long paperId;

    @Column(name = "page_index", nullable = false)
    private int pageIndex;

    @Column(name = "plain_hint", columnDefinition = "LONGTEXT")
    private String plainHint;

    @Column(name = "understanding", columnDefinition = "LONGTEXT")
    private String understanding;

    public Long getId() { return id; }
    public Long getPaperId() { return paperId; }
    public void setPaperId(Long paperId) { this.paperId = paperId; }
    public int getPageIndex() { return pageIndex; }
    public void setPageIndex(int pageIndex) { this.pageIndex = pageIndex; }
    public String getPlainHint() { return plainHint; }
    public void setPlainHint(String plainHint) { this.plainHint = plainHint; }
    public String getUnderstanding() { return understanding; }
    public void setUnderstanding(String understanding) { this.understanding = understanding; }
}
