package com.multiagent.discuss.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class AgentThought {

    public enum State {
        PENDING, RUNNING, DONE, FAILED, SKIPPED
    }

    private String agentId;
    private String agentName;
    private State state = State.PENDING;
    private String viewpoint;
    private List<String> selectedEvidence = new ArrayList<>();
    private List<String> doubtsOnVision = new ArrayList<>();
    private Double confidence;
    private Boolean requestVisionRefine;
    private String rawText;
    private String errorMessage;
    private Instant finishedAt;

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

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public String getViewpoint() {
        return viewpoint;
    }

    public void setViewpoint(String viewpoint) {
        this.viewpoint = viewpoint;
    }

    public List<String> getSelectedEvidence() {
        return selectedEvidence;
    }

    public void setSelectedEvidence(List<String> selectedEvidence) {
        this.selectedEvidence = selectedEvidence;
    }

    public List<String> getDoubtsOnVision() {
        return doubtsOnVision;
    }

    public void setDoubtsOnVision(List<String> doubtsOnVision) {
        this.doubtsOnVision = doubtsOnVision;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public Boolean getRequestVisionRefine() {
        return requestVisionRefine;
    }

    public void setRequestVisionRefine(Boolean requestVisionRefine) {
        this.requestVisionRefine = requestVisionRefine;
    }

    public String getRawText() {
        return rawText;
    }

    public void setRawText(String rawText) {
        this.rawText = rawText;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String asSpeech() {
        if (viewpoint != null && !viewpoint.isBlank()) {
            return viewpoint;
        }
        return rawText == null ? "" : rawText;
    }
}
