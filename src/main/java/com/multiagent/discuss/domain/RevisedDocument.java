package com.multiagent.discuss.domain;

import java.util.ArrayList;
import java.util.List;

public class RevisedDocument {

    private String revisedFullText;
    private List<ChangeLogEntry> changeLog = new ArrayList<>();

    public String getRevisedFullText() {
        return revisedFullText;
    }

    public void setRevisedFullText(String revisedFullText) {
        this.revisedFullText = revisedFullText;
    }

    public List<ChangeLogEntry> getChangeLog() {
        return changeLog;
    }

    public void setChangeLog(List<ChangeLogEntry> changeLog) {
        this.changeLog = changeLog;
    }

    public static class ChangeLogEntry {
        private String planItemId;
        private String originalSnippet;
        private String newSnippet;
        private String note;

        public String getPlanItemId() {
            return planItemId;
        }

        public void setPlanItemId(String planItemId) {
            this.planItemId = planItemId;
        }

        public String getOriginalSnippet() {
            return originalSnippet;
        }

        public void setOriginalSnippet(String originalSnippet) {
            this.originalSnippet = originalSnippet;
        }

        public String getNewSnippet() {
            return newSnippet;
        }

        public void setNewSnippet(String newSnippet) {
            this.newSnippet = newSnippet;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }
}
