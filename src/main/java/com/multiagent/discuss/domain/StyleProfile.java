package com.multiagent.discuss.domain;

import java.util.ArrayList;
import java.util.List;

public class StyleProfile {
    private String tone;
    private String person;
    private String rhythm;
    private String vocabularyLevel;
    private String summary;
    private List<String> signaturePhrases = new ArrayList<>();

    public String getTone() {
        return tone;
    }

    public void setTone(String tone) {
        this.tone = tone;
    }

    public String getPerson() {
        return person;
    }

    public void setPerson(String person) {
        this.person = person;
    }

    public String getRhythm() {
        return rhythm;
    }

    public void setRhythm(String rhythm) {
        this.rhythm = rhythm;
    }

    public String getVocabularyLevel() {
        return vocabularyLevel;
    }

    public void setVocabularyLevel(String vocabularyLevel) {
        this.vocabularyLevel = vocabularyLevel;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public List<String> getSignaturePhrases() {
        return signaturePhrases;
    }

    public void setSignaturePhrases(List<String> signaturePhrases) {
        this.signaturePhrases = signaturePhrases;
    }

    public String asPromptText() {
        StringBuilder sb = new StringBuilder();
        sb.append("文风画像：\n");
        if (summary != null) sb.append("- 总述：").append(summary).append('\n');
        if (tone != null) sb.append("- 语气：").append(tone).append('\n');
        if (person != null) sb.append("- 人称：").append(person).append('\n');
        if (rhythm != null) sb.append("- 节奏：").append(rhythm).append('\n');
        if (vocabularyLevel != null) sb.append("- 用词：").append(vocabularyLevel).append('\n');
        if (signaturePhrases != null && !signaturePhrases.isEmpty()) {
            sb.append("- 惯用表达：").append(String.join("；", signaturePhrases)).append('\n');
        }
        return sb.toString();
    }
}
