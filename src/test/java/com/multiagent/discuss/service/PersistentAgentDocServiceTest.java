package com.multiagent.discuss.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiagent.discuss.db.entity.PersistentAgentDocEntity;
import com.multiagent.discuss.db.entity.PersistentAgentDocPageEntity;
import com.multiagent.discuss.db.entity.PersistentAgentEntity;
import com.multiagent.discuss.db.repo.PersistentAgentDocPageRepository;
import com.multiagent.discuss.db.repo.PersistentAgentDocRepository;
import com.multiagent.discuss.db.repo.PersistentAgentRepository;
import com.multiagent.discuss.domain.MentorPaperSummary;
import com.multiagent.discuss.domain.PageBlock;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
/**
 * 常驻知识库的两处**纯逻辑**：摘要解析的降级、以及送给模型的证据怎么拼。
 *
 * <p>两处都做成包级可见的静态方法就是为了能被这里直接钉死 —— 它们出错的方式都很安静：
 * 解析失败若抛出去，整批文件会一起标红；证据拼错则模型看到的资料是残缺的，
 * 而输出看上去仍然像模像样。
 */
class PersistentAgentDocServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String FILE = "材料力学报告.pdf";

    // ── 摘要解析 ─────────────────────────────────────────────

    @Test
    void parsesNineFieldsFromJson() {
        String raw = """
                {
                  "title":"某合金疲劳寿命研究",
                  "authors":"张三、李四",
                  "publicationYear":"2021",
                  "abstractText":"研究了……",
                  "researchQuestion":"疲劳寿命如何随温度变化",
                  "methods":"恒幅加载试验",
                  "keyFindings":"温度升高寿命下降",
                  "limitations":"仅覆盖 20–200°C",
                  "keywords":"疲劳、温度、合金"
                }
                """;
        MentorPaperSummary s = PersistentAgentDocService.parseSummary(raw, FILE, MAPPER);
        assertEquals("某合金疲劳寿命研究", s.title());
        assertEquals("张三、李四", s.authors());
        assertEquals("2021", s.pubYear());
        assertEquals("研究了……", s.abstractText());
        assertEquals("疲劳寿命如何随温度变化", s.researchQuestion());
        assertEquals("恒幅加载试验", s.methodology());
        assertEquals("温度升高寿命下降", s.findings());
        assertEquals("仅覆盖 20–200°C", s.limitations());
        assertEquals("疲劳、温度、合金", s.keywords());
    }

    @Test
    void stripsMarkdownFenceBeforeParsing() {
        // 模型很爱包一层 ```json —— 与导师侧共用 DiscussionPipeline.stripFencePublic
        String raw = "```json\n{\"title\":\"带围栏的标题\"}\n```";
        assertEquals("带围栏的标题", PersistentAgentDocService.parseSummary(raw, FILE, MAPPER).title());
    }

    @Test
    void invalidJsonDegradesInsteadOfThrowing() {
        // ⚠️ 这是本测试最重要的一条：解析不出来也**不能让这份资料不可用**。
        // 抛出去的话 saveFromSeat 会把整篇标 FAILED —— 而页知识其实已经落库了，
        // 用户会以为这份文件白传了。降级成「标题=文件名、其余未识别」并保留模型原文。
        MentorPaperSummary s = PersistentAgentDocService.parseSummary("模型今天不想输出 JSON", FILE, MAPPER);
        assertEquals(FILE, s.title(), "标题回落文件名");
        assertEquals(MentorPaperSummary.UNKNOWN, s.authors());
        assertEquals(MentorPaperSummary.UNKNOWN, s.keywords());
        assertEquals("模型今天不想输出 JSON", s.abstractText(), "模型原文要留下来，免得用户只看到一片未识别");
    }

    @Test
    void emptyOutputAlsoDegrades() {
        MentorPaperSummary s = PersistentAgentDocService.parseSummary(null, FILE, MAPPER);
        assertEquals(FILE, s.title());
        assertEquals(MentorPaperSummary.UNKNOWN, s.abstractText());
    }

    @Test
    void missingFieldsFallBackIndividually() {
        // 模型只给了一半字段时，缺的那些各回各的「未识别」，不能整篇作废
        MentorPaperSummary s = PersistentAgentDocService.parseSummary(
                "{\"title\":\"标题在\",\"authors\":\"   \"}", FILE, MAPPER);
        assertEquals("标题在", s.title());
        assertEquals(MentorPaperSummary.UNKNOWN, s.authors(), "全空白等于没给");
        assertEquals(MentorPaperSummary.UNKNOWN, s.findings());
    }

    // ── 证据拼装 ─────────────────────────────────────────────

    @Test
    void evidencePrefersPlainHintPerPage() {
        PageBlock a = page(0, "甲页的文字层", "甲页的视觉理解");
        PageBlock b = page(1, null, "乙页的视觉理解");

        String evidence = PersistentAgentDocService.buildEvidence(List.of(a, b));

        assertTrue(evidence.contains("甲页的文字层"), "有文字层的页要用原文");
        assertFalse(evidence.contains("甲页的视觉理解"), "有文字层时不该再退回视觉理解");
        assertTrue(evidence.contains("乙页的视觉理解"), "没有文字层的页（扫描件）才退回视觉理解");
        assertTrue(evidence.contains("【第 1 页】") && evidence.contains("【第 2 页】"), "页号要在，模型要靠它引用");
    }

    @Test
    void evidenceTruncatesAtBudget() {
        PageBlock big = page(0, "字".repeat(PersistentAgentDocService.EVIDENCE_BUDGET + 5000), null);

        String evidence = PersistentAgentDocService.buildEvidence(List.of(big));

        assertTrue(evidence.length() < PersistentAgentDocService.EVIDENCE_BUDGET + 100,
                "超长资料必须截断，否则会把 prompt 撑爆");
        assertTrue(evidence.endsWith("…（截断）"), "截断要让模型知道内容不完整");
    }

    @Test
    void evidenceIsEmptyForNoPages() {
        assertEquals("", PersistentAgentDocService.buildEvidence(List.of()));
    }

    // ── loadPages 往返 ───────────────────────────────────────

    /**
     * 落库 → 还原的往返：{@code sourceName} 由 {@code file_name} 回填（表里没有这一列）、
     * {@code imageRef} 必须是 null（临时渲染产物不入库）。
     *
     * <p>这一条是「加入会话后模型能引用到真实来源」的地基 —— 回填漏了的话页头会退回
     * 不带文件名的版本，而 prompt 又要求给出真实出处，等于逼模型编一个。
     */
    @Test
    void loadPagesRoundTripsAndBackfillsSourceName() throws Exception {
        // ⚠️ 不 mock 实体：本机 JDK 25 下 Byte Buddy 只能 mock **接口**（走 JDK 动态代理），
        // mock 具体类要改写字节码，而它官方只支持到 Java 24。实体的 id 只有 JPA 能写
        // （没有 setter），所以用反射塞一个 —— 它正是 loadPages 拿去查文档的那把键。
        PersistentAgentEntity agent = new PersistentAgentEntity();
        agent.setAgentUuid("uuid-1");
        agent.setAgentName("材料专家");
        setId(agent, 7L);

        PersistentAgentDocEntity doc = new PersistentAgentDocEntity();
        doc.setPersistentAgentId(7L);
        doc.setFileName("疲劳报告.pdf");
        setId(doc, 11L); // 同理：查页要用它

        PersistentAgentDocPageEntity row = new PersistentAgentDocPageEntity();
        row.setDocId(11L);
        row.setPageIndex(2);
        row.setPlainHint("第三页文字层");
        row.setUnderstanding("{\"narrative\":\"第三页叙述\",\"claims\":[\"结论A\"]}");

        PersistentAgentDocRepository docRepo = mock(PersistentAgentDocRepository.class);
        PersistentAgentDocPageRepository pageRepo = mock(PersistentAgentDocPageRepository.class);
        PersistentAgentRepository agentRepo = mock(PersistentAgentRepository.class);
        when(agentRepo.findByAgentUuid("uuid-1")).thenReturn(Optional.of(agent));
        when(docRepo.findAllByPersistentAgentIdOrderBySortOrderAscIdAsc(7L)).thenReturn(List.of(doc));
        when(pageRepo.findAllByDocIdOrderByPageIndexAsc(11L)).thenReturn(List.of(row));

        List<PageBlock> pages = service(docRepo, pageRepo, agentRepo).loadPages("uuid-1");

        assertEquals(1, pages.size());
        PageBlock p = pages.get(0);
        assertEquals(2, p.getPageIndex());
        assertEquals("第三页文字层", p.getPlainHint());
        assertEquals("疲劳报告.pdf", p.getSourceName(), "sourceName 必须由 file_name 回填");
        assertEquals("第三页叙述", p.getUnderstanding().getNarrative());
        assertEquals(List.of("结论A"), p.getUnderstanding().getClaims());
        assertNull(p.getImageRef(), "imageRef 不入库，还原时必须是 null");
        // 带上来源之后，页头才是「《文件名》第 N 页」—— 模型据此引用才落得到实处
        assertTrue(p.asContextSnippetWithSource().contains("《疲劳报告.pdf》第 3 页"));
    }

    @Test
    void loadPagesOfUnknownAgentIsEmptyNotError() {
        PersistentAgentRepository agentRepo = mock(PersistentAgentRepository.class);
        when(agentRepo.findByAgentUuid("gone")).thenReturn(Optional.empty());
        assertTrue(service(null, null, agentRepo).loadPages("gone").isEmpty());
    }

    // ── 夹具 ─────────────────────────────────────────────────

    private static PersistentAgentDocService service(PersistentAgentDocRepository docRepo,
                                                     PersistentAgentDocPageRepository pageRepo,
                                                     PersistentAgentRepository agentRepo) {
        // loadPages / buildEvidence / parseSummary 都不碰事务与网关，传 null 即可；
        // 事务管理器给个 mock 而不是 null —— TransactionTemplate 对 null 的容忍度随版本而变，
        // 但真实调用永远不会走到它（这两个方法都不开事务）。
        return new PersistentAgentDocService(docRepo, pageRepo, agentRepo, null, null, null,
                MAPPER, mock(PlatformTransactionManager.class));
    }

    private static PageBlock page(int index, String plainHint, String narrative) {
        PageBlock b = new PageBlock();
        b.setPageIndex(index);
        b.setPlainHint(plainHint);
        PageBlock.Understanding u = new PageBlock.Understanding();
        u.setNarrative(narrative);
        b.setUnderstanding(u);
        return b;
    }

    /** 主键列没有 setter（只有 JPA 写它），测试里用反射补上。 */
    private static void setId(Object entity, Long id) throws Exception {
        java.lang.reflect.Field f = entity.getClass().getDeclaredField("id");
        f.setAccessible(true);
        f.set(entity, id);
    }
}
