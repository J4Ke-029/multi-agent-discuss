package com.multiagent.discuss.agent;

import org.springframework.util.StringUtils;

/**
 * 「经验心得」注入 system prompt 的渲染规则 —— 全项目唯一一处。
 *
 * <p>刻意做成独立工具类而不是塞进 {@code AgentMemoryService} 的静态方法：那样
 * {@code AgentFactory}（agent 包）要引用 service 包，而 {@code AgentMemoryService} 又注入
 * {@code AgentFactory}，形成包级双向依赖。这里只放「格式」，不放业务，两边都只向下依赖它。
 *
 * <p>四个标签是<b>接口</b>：解析模型输出、单测断言、注入 prompt 都认这几个字面量，
 * 改动它们等于改协议。
 */
public final class AgentMemoryPrompt {

    /** 心得正文的两个骨架标签，必须原样出现在总结 prompt 与模型输出里，是解析的锚点。 */
    public static final String DOING_MARKER = "【做法】";
    public static final String FINDING_MARKER = "【结论】";

    /** 注入 system prompt 的两个段标签。单测断言「无记忆时不含【既往经验】」用的就是它。 */
    public static final String MEMORY_BLOCK_MARKER = "【既往经验】";
    public static final String PIN_BLOCK_MARKER = "【已锁定 · 硬前提】";

    private AgentMemoryPrompt() {
    }

    /**
     * 把心得与钉子渲染成注入 system prompt 的一段文字。
     *
     * <p><b>空输入必须返回空串</b>（不是 null、不是只有标题的骨架）：调用方是
     * {@code sb.append(memoryBlock(...))}，返回空串时 append 是 no-op，于是「没有记忆的席位，
     * 其 system prompt 与本次改动之前逐字节相同」。这条是硬要求，由
     * {@code AgentMemoryPromptTest} 钉死 —— 它保证了新功能对现存讨论路径<b>零扰动</b>。
     *
     * @param injectMaxChars &gt;0 时截断心得正文；<b>钉子永不截断</b>，硬前提被截断等于没有
     */
    static String memoryBlock(String content, String pinsText, int injectMaxChars) {
        String body = trimToNull(content);
        String pins = trimToNull(pinsText);
        if (body == null && pins == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        // 钉子在前：它是硬前提，要先于「仅供参考的心得」被模型读到
        if (pins != null) {
            sb.append(PIN_BLOCK_MARKER).append('\n')
                    .append("以下是用户明确纠正过你的地方，属于不可推翻的硬前提：\n")
                    .append("不得删除、不得推翻、不得与之矛盾。若你的心得或本场判断与它们冲突，以它们为准。\n")
                    .append(pins).append("\n\n");
        }
        if (body != null) {
            if (injectMaxChars > 0 && body.length() > injectMaxChars) {
                body = body.substring(0, injectMaxChars) + "\n…（心得过长已截断）";
            }
            sb.append(MEMORY_BLOCK_MARKER).append('\n')
                    .append("这是你在过去多场讨论里攒下的心得，不是本场讨论的内容。\n")
                    .append("把它当作自己的工作习惯与判断参考；不要向用户复述这段文字，\n")
                    .append("也不要声称\"我记得上次讨论\"——你只是在按自己的经验做事。\n")
                    .append(body).append("\n\n");
        }
        return sb.toString();
    }

    private static String trimToNull(String s) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
