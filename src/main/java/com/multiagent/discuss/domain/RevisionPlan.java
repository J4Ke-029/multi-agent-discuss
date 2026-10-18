package com.multiagent.discuss.domain;

import java.util.ArrayList;
import java.util.List;

public class RevisionPlan {

    private String summary;
    private List<Item> items = new ArrayList<>();

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public List<Item> getItems() {
        return items;
    }

    public void setItems(List<Item> items) {
        this.items = items;
    }

    public static class Item {
        private String id;
        private String location;
        private String type;
        private String reason;
        private String professionalSuggestion;
        private String styleAlignedSuggestion;
        private List<String> sourceAgents = new ArrayList<>();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getLocation() {
            return location;
        }

        public void setLocation(String location) {
            this.location = location;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public String getProfessionalSuggestion() {
            return professionalSuggestion;
        }

        public void setProfessionalSuggestion(String professionalSuggestion) {
            this.professionalSuggestion = professionalSuggestion;
        }

        public String getStyleAlignedSuggestion() {
            return styleAlignedSuggestion;
        }

        public void setStyleAlignedSuggestion(String styleAlignedSuggestion) {
            this.styleAlignedSuggestion = styleAlignedSuggestion;
        }

        public List<String> getSourceAgents() {
            return sourceAgents;
        }

        public void setSourceAgents(List<String> sourceAgents) {
            this.sourceAgents = sourceAgents;
        }
    }
}
