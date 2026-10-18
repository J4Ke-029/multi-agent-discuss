package com.multiagent.discuss.api.dto;

/** 改会话配置（文风 / 最高讨论轮次）。字段为 null 表示「不改这一项」。 */
public class UpdateSessionSettingsRequest {
    private String userStyle;
    private Integer maxRounds;

    public String getUserStyle() {
        return userStyle;
    }

    public void setUserStyle(String userStyle) {
        this.userStyle = userStyle;
    }

    public Integer getMaxRounds() {
        return maxRounds;
    }

    public void setMaxRounds(Integer maxRounds) {
        this.maxRounds = maxRounds;
    }
}
