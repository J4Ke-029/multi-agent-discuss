package com.multiagent.discuss.domain;

/**
 * 一篇导师论文的逐篇结构化摘要 —— 模型输出的九字段，落库与接口两用。
 *
 * <p>除 {@code title} 外，其余字段在模型没给出时一律回落「未识别」而不是 null：
 * 「原文未说明」本身就是要展示给用户的信息，显示成空白会让人以为是界面出错。
 */
public record MentorPaperSummary(
        String title,
        String authors,
        String pubYear,
        String abstractText,
        String researchQuestion,
        String methodology,
        String findings,
        String limitations,
        String keywords) {

    /** 字段缺失时的统一占位——与提示词里要求模型自己写的措辞保持一致。 */
    public static final String UNKNOWN = "未识别";

    /** 模型漏给标题时用文件名兜底（文件名总归是有的，比「未识别」有用）。 */
    public static MentorPaperSummary fallback(String fileName) {
        return new MentorPaperSummary(fileName, UNKNOWN, UNKNOWN, UNKNOWN,
                UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN);
    }
}
