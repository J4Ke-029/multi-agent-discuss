package com.multiagent.discuss.api.dto;

import java.util.ArrayList;
import java.util.List;

public class RevisionConfirmRequest {

    private List<String> acceptedItemIds = new ArrayList<>();
    private String note;
    /** 默认 true：confirm 后自动进入阶段二改写 */
    private Boolean autoRevise = true;

    public List<String> getAcceptedItemIds() {
        return acceptedItemIds;
    }

    public void setAcceptedItemIds(List<String> acceptedItemIds) {
        this.acceptedItemIds = acceptedItemIds;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Boolean getAutoRevise() {
        return autoRevise;
    }

    public void setAutoRevise(Boolean autoRevise) {
        this.autoRevise = autoRevise;
    }
}
