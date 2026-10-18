package com.multiagent.discuss.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiagent.discuss.domain.DomainAgentConfig;
import com.multiagent.discuss.domain.LlmEndpoint;
import com.multiagent.discuss.domain.Session;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 归档快照的**凭据红线**：payload 里永远不许出现任何 API Key。
 *
 * <p>这条约束在 0.9.0 之后变得更紧要 —— 常驻智能体自己也开始存 Key 了，席位上的
 * {@code DomainAgentConfig.apiKey} 不再只可能是「会话网关的副本」。而「结束会话」正是把
 * 席位整体快照落库的时机，一旦有人图省事改成直接序列化 {@code Session}（或者往白名单里
 * 顺手加一个 {@code apiKey}），这条红线就会无声地破掉：不报错、测试不红、只是库里多了一串明文。
 *
 * <p>所以这里构造一个**各方面都带 Key** 的会话，断言序列化结果里连 {@code sk-} 都搜不到。
 * {@code sanitizedSession} 是包级可见的，见它的 javadoc。
 */
class ArchivedSessionServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 只用到 objectMapper，repo 本测试不碰 —— 与 AgentMemoryPromptTest 传 null 同一手法。 */
    private static ArchivedSessionService service() {
        return new ArchivedSessionService(null, new ObjectMapper());
    }

    /** 会话网关与席位各带一份 Key，且两份不一样 —— 漏哪一份都能被下面的断言抓到。 */
    private static Session sessionWithKeys() {
        Session s = new Session("sid-1", "简洁直接", false, false, 20, 3);
        s.setLlmEndpoint(new LlmEndpoint(
                "sk-session-999999", "https://session-gw.example.com", "session-model", "中转A"));

        DomainAgentConfig a = new DomainAgentConfig("aid-1", "sid-1", "材料专家",
                "sk-seat-12345678", "https://seat-gw.example.com", "own-model", "你是一位材料学专家。");
        a.setPersonality("冷静");
        a.setDocumentFileName("页岩相.pdf");
        a.setAgentUuid("uuid-1");
        s.getAgents().put(a.getId(), a);
        return s;
    }

    @Test
    void snapshotNeverCarriesAnyApiKey() throws Exception {
        String json = MAPPER.writeValueAsString(service().sanitizedSession(sessionWithKeys()));

        assertFalse(json.contains("sk-"), "快照里连 sk- 都不该出现，实际：" + json);
        assertFalse(json.contains("apiKey"), "连键名都不该有 —— 免得以后有人填个空值又慢慢填实");
        assertFalse(json.contains("999999"), "会话网关的 Key 不能进快照");
        assertFalse(json.contains("12345678"), "席位自己的 Key 不能进快照");

        // 反面：不能因为堵 Key 把该留的字段也一起丢了。归档是只读回看，
        // 「看的」字段（模型 / 人设 / 挂的文档名）少一个，归档页就解释不了当年那场讨论。
        assertTrue(json.contains("own-model"), "席位的模型名要留下");
        assertTrue(json.contains("材料专家"), "席位名要留下");
        assertTrue(json.contains("页岩相.pdf"), "文档名要留下");
        assertTrue(json.contains("中转A"), "网关的展示标签要留下");
        assertTrue(json.contains("session-model"), "会话网关的模型名要留下");
        assertTrue(json.contains("uuid-1"), "常驻身份 uuid 要留下（归档靠它解释席位是谁）");
    }

    @Test
    void gatewayBlockKeepsOnlyTheReadableHalf() {
        Map<String, Object> s = service().sanitizedSession(sessionWithKeys());

        @SuppressWarnings("unchecked")
        Map<String, Object> llm = (Map<String, Object>) s.get("llm");
        assertFalse(llm.containsKey("apiKey"));
        assertTrue((Boolean) llm.get("configured"), "配了 Key 就该标 configured=true");
        assertTrue(llm.containsKey("baseUrl") && llm.containsKey("model"));
    }

    @Test
    void agentMemoryStaysOutOfTheSnapshot() {
        // 心得是账号级可变数据，进快照就成了一份会过期的副本，payload 也会显著膨胀。
        // 快照只带 uuid，读回时再去账号级库里取（见 toSession 的注释）。
        Session s = sessionWithKeys();
        DomainAgentConfig seat = s.getAgents().get("aid-1");
        seat.setAgentMemory("【做法】\n先看页岩相。");
        seat.setAgentPins("- 对齐单位");

        Map<String, Object> snap = service().sanitizedSession(s);

        assertFalse(snap.toString().contains("先看页岩相"), "心得正文不进快照");
        assertFalse(snap.toString().contains("对齐单位"), "钉子也不进快照");
    }
}
