package com.multiagent.discuss.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class UserDocument {

    private String fileName;
    private String originalText;
    private StyleProfile styleProfile;
    private KnowledgeStatus status = KnowledgeStatus.AGENT_CREATED;
    private String statusMessage;
    /** 当前阶段的人话描述，驱动前端状态条 */
    private volatile String stageHint;
    /** Vision 进度：已完成页数（0 表示未开始） */
    private volatile int progressCurrent = 0;
    /** Vision 进度：总页数（0 表示未知） */
    private volatile int progressTotal = 0;
    private final List<PageBlock> pages = new ArrayList<>();
    private Instant updatedAt = Instant.now();

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
        touch();
    }

    public String getOriginalText() {
        return originalText;
    }

    public void setOriginalText(String originalText) {
        this.originalText = originalText;
        touch();
    }

    public StyleProfile getStyleProfile() {
        return styleProfile;
    }

    public void setStyleProfile(StyleProfile styleProfile) {
        this.styleProfile = styleProfile;
        touch();
    }

    public KnowledgeStatus getStatus() {
        return status;
    }

    public void setStatus(KnowledgeStatus status) {
        this.status = status;
        touch();
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
        touch();
    }

    public String getStageHint() {
        return stageHint;
    }

    public void setStageHint(String stageHint) {
        this.stageHint = stageHint;
        touch();
    }

    public int getProgressCurrent() {
        return progressCurrent;
    }

    public void setProgressCurrent(int progressCurrent) {
        this.progressCurrent = progressCurrent;
    }

    public int getProgressTotal() {
        return progressTotal;
    }

    public void setProgressTotal(int progressTotal) {
        this.progressTotal = progressTotal;
    }

    /** 进度比例 0..1，总数 0 时返回 0。 */
    public double progressRatio() {
        int t = progressTotal;
        if (t <= 0) return 0d;
        return Math.min(1d, Math.max(0d, (double) progressCurrent / (double) t));
    }

    public List<PageBlock> getPages() {
        return pages;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
