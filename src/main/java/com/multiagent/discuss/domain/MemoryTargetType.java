package com.multiagent.discuss.domain;

import org.springframework.util.StringUtils;

/**
 * 「经验心得」挂在哪种账号级对象上。
 *
 * <p>取值直接落库为 {@code agent_memory.target_type}，所以枚举名即存储值 ——
 * 改名等于改数据，别动。
 */
public enum MemoryTargetType {

    /** 常驻智能体，uuid 对齐 {@code persistent_agent.agent_uuid}。 */
    PERSISTENT_AGENT,

    /** 导师，uuid 对齐 {@code mentor.mentor_uuid}。 */
    MENTOR;

    /**
     * 解析路径参数 / 请求体里的类型字符串。
     *
     * <p>刻意只认全大写枚举名，不做容错放宽：这个值决定「查哪张账号级表」，
     * 放宽容错只会让拼错的前端悄悄地查到另一个对象上。
     */
    public static MemoryTargetType parse(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("targetType 不能为空");
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("targetType 只能是 PERSISTENT_AGENT 或 MENTOR: " + raw);
        }
    }
}
