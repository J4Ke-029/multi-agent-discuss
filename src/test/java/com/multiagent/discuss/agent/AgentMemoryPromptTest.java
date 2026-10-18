package com.multiagent.discuss.agent;

import com.multiagent.discuss.config.AppProperties;
import com.multiagent.discuss.domain.DomainAgentConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 经验心得注入 system prompt 的两条硬约束。
 *
 * <p>这些行为在 HTTP 层看不到（prompt 不回显），但决定了新功能对现存讨论路径是不是
 * <b>零扰动</b>，所以单独钉住：
 *
 * <ol>
 *   <li><b>无记忆 ⇒ 提示词逐字节不变</b>。心得是每轮每一次发言都要带的 system 段，
 *       但凡它对「没有心得的席位」多出一个空行，所有既有讨论的输出都会跟着漂移；
 *   <li><b>钉子永不截断</b>。硬前提被截掉半句等于没有 —— 那是用户明确纠正过的东西。
 * </ol>
 */
class AgentMemoryPromptTest {

    private static final String PERSONA = "你是一位材料学专家。";

    /** defaultChatModel 传 null：本测试只碰纯字符串拼装，不构造任何 ChatModel。 */
    private static AgentFactory factory(int injectMaxChars) {
        AppProperties props = new AppProperties();
        props.getMemory().setInjectMaxChars(injectMaxChars);
        return new AgentFactory(null, props);
    }

    private static DomainAgentConfig seat() {
        DomainAgentConfig a = new DomainAgentConfig("agent-1", "s1", "材料专家", null, null, "gpt-4o", PERSONA);
        a.setPersonality(null);
        return a;
    }

    // ── memoryBlock 的空输入 ─────────────────────────────────

    @Test
    void memoryBlockIsEmptyForBlankInput() {
        // 全空 → 空串（不是 null，不是只有标题的骨架）：
        // 调用方是 sb.append(...)，空串 append 才是 no-op
        assertEquals("", AgentMemoryPrompt.memoryBlock(null, null, 0));
        assertEquals("", AgentMemoryPrompt.memoryBlock("", "", 0));
        assertEquals("", AgentMemoryPrompt.memoryBlock("   ", null, 0));
        assertEquals("", AgentMemoryPrompt.memoryBlock(null, "\n\t ", 0));
    }

    // ── 无记忆 ⇒ 逐字节不变 ─────────────────────────────────

    @Test
    void systemPromptIsByteIdenticalWhenSeatHasNoMemory() {
        AgentFactory f = factory(0);

        String first = f.buildDomainSystem(seat());
        String second = f.buildDomainSystem(seat());
        assertEquals(first, second, "同一席位两次组装必须完全一致");

        assertFalse(first.contains(AgentMemoryPrompt.MEMORY_BLOCK_MARKER),
                "无记忆的席位不该出现【既往经验】");
        assertFalse(first.contains(AgentMemoryPrompt.PIN_BLOCK_MARKER),
                "无钉子的席位不该出现【已锁定 · 硬前提】");

        // 最强的一句断言：人设与【规则】之间**一个字节都没有多**。
        // 这正是改动前 buildDomainSystem 拼出来的样子 —— 只要中间被插进任何字符（哪怕换行），
        // 这个 contains 就会失败。
        assertTrue(first.contains("【你的角色】\n" + PERSONA + "\n\n【规则】"),
                "无记忆时人设与【规则】之间不能有任何多余字符");
    }

    // ── 有记忆 ⇒ 位置正确 ────────────────────────────────────

    @Test
    void memoryBlockSitsBetweenPersonaAndRules() {
        AgentFactory f = factory(0);
        DomainAgentConfig a = seat();
        a.setAgentMemory("【做法】\n先看页岩相。\n\n【结论】\n时效温度决定析出相。");
        a.setAgentPins("- 遇到定量结论先对齐单位");

        String s = f.buildDomainSystem(a);

        int personaAt = s.indexOf("【你的角色】");
        int memoryAt = s.indexOf(AgentMemoryPrompt.MEMORY_BLOCK_MARKER);
        int pinAt = s.indexOf(AgentMemoryPrompt.PIN_BLOCK_MARKER);
        int rulesAt = s.indexOf("【规则】");

        assertTrue(personaAt >= 0 && memoryAt > personaAt, "心得必须在人设之后");
        assertTrue(pinAt > personaAt, "钉子也必须在人设之后");
        assertTrue(rulesAt > memoryAt, "心得必须在【规则】之前");
        assertTrue(pinAt < memoryAt, "钉子要排在心得之前 —— 它是硬前提，先被读到");

        assertTrue(s.contains("先看页岩相。"), "心得正文要进 prompt");
        assertTrue(s.contains("遇到定量结论先对齐单位"), "钉子要进 prompt");
    }

    @Test
    void pinsAloneStillProduceABlock() {
        // 只有钉子没有正文是真实场景：用户先纠错、这位智能体还没总结过任何一场
        AgentFactory f = factory(0);
        DomainAgentConfig a = seat();
        a.setAgentPins("- 不要用绝对化措辞");

        String s = f.buildDomainSystem(a);
        assertTrue(s.contains(AgentMemoryPrompt.PIN_BLOCK_MARKER));
        assertFalse(s.contains(AgentMemoryPrompt.MEMORY_BLOCK_MARKER));
        assertTrue(s.contains("不要用绝对化措辞"));
        assertTrue(s.contains("【你的角色】\n" + PERSONA + "\n\n"), "人设段本身不受影响");
    }

    // ── 截断：只截正文，绝不截钉子 ───────────────────────────

    @Test
    void truncationHitsBodyOnlyNeverPins() {
        String longBody = "正".repeat(500);
        String pin = "钉子-" + "钉".repeat(300);

        String block = AgentMemoryPrompt.memoryBlock(longBody, pin, 100);

        assertTrue(block.contains("钉子-" + "钉".repeat(300)), "钉子必须完整保留，一个字都不能少");
        assertFalse(block.contains("正".repeat(101)), "正文应被截到 100 字以内");
        assertTrue(block.contains("…（心得过长已截断）"), "截断要留可见的标记");
    }

    @Test
    void zeroMaxCharsMeansNoTruncation() {
        String longBody = "正".repeat(5000);
        String block = AgentMemoryPrompt.memoryBlock(longBody, null, 0);
        assertTrue(block.contains("正".repeat(5000)), "0 = 不截断（默认值）");
        assertFalse(block.contains("已截断"));
    }
}
