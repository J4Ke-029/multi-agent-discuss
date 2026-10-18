package com.multiagent.discuss.domain;

import java.time.Instant;

public class ChatHistoryItem {

    private String role;
    private String content;
    private Instant at = Instant.now();

    public ChatHistoryItem() {
    }

    public ChatHistoryItem(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Instant getAt() {
        return at;
    }

    public void setAt(Instant at) {
        this.at = at;
    }
}
