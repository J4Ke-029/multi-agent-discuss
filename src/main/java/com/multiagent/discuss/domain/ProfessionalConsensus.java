package com.multiagent.discuss.domain;

import java.util.ArrayList;
import java.util.List;

public class ProfessionalConsensus {

    private String summary;
    private List<String> conclusions = new ArrayList<>();
    private List<String> corrections = new ArrayList<>();
    private List<String> additions = new ArrayList<>();
    private List<Contribution> contributions = new ArrayList<>();
    private String rawText;

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public List<String> getConclusions() {
        return conclusions;
    }

    public void setConclusions(List<String> conclusions) {
        this.conclusions = conclusions;
    }

    public List<String> getCorrections() {
        return corrections;
    }

    public void setCorrections(List<String> corrections) {
        this.corrections = corrections;
    }

    public List<String> getAdditions() {
        return additions;
    }

    public void setAdditions(List<String> additions) {
        this.additions = additions;
    }

    public List<Contribution> getContributions() {
        return contributions;
    }

    public void setContributions(List<Contribution> contributions) {
        this.contributions = contributions;
    }

    public String getRawText() {
        return rawText;
    }

    public void setRawText(String rawText) {
        this.rawText = rawText;
    }

    public String asPromptText() {
        if (rawText != null && !rawText.isBlank()) {
            return rawText;
        }
        StringBuilder sb = new StringBuilder();
        if (summary != null) {
            sb.append("共识摘要：").append(summary).append('\n');
        }
        if (conclusions != null) {
            for (String c : conclusions) {
                sb.append("- 结论：").append(c).append('\n');
            }
        }
        if (corrections != null) {
            for (String c : corrections) {
                sb.append("- 应修正：").append(c).append('\n');
            }
        }
        if (additions != null) {
            for (String c : additions) {
                sb.append("- 应增补：").append(c).append('\n');
            }
        }
        return sb.toString();
    }

    public static class Contribution {
        private String agentId;
        private String agentName;
        private String point;
        private String evidenceRef;

        public String getAgentId() {
            return agentId;
        }

        public void setAgentId(String agentId) {
            this.agentId = agentId;
        }

        public String getAgentName() {
            return agentName;
        }

        public void setAgentName(String agentName) {
            this.agentName = agentName;
        }

        public String getPoint() {
            return point;
        }

        public void setPoint(String point) {
            this.point = point;
        }

        public String getEvidenceRef() {
            return evidenceRef;
        }

        public void setEvidenceRef(String evidenceRef) {
            this.evidenceRef = evidenceRef;
        }
    }
}
