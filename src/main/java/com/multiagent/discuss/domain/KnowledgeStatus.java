package com.multiagent.discuss.domain;

public enum KnowledgeStatus {
    AGENT_CREATED,
    DOCUMENT_UPLOADING,
    VISION_PROCESSING,
    /** 用户文档专用——Vision 完成后的文风预分析阶段 */
    STYLE_ANALYZING,
    KNOWLEDGE_READY,
    VISION_FAILED
}
