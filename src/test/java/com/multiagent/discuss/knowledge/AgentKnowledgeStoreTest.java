package com.multiagent.discuss.knowledge;

import com.multiagent.discuss.domain.PageBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentKnowledgeStore} 的检索与页头。
 *
 * <p>这些行为在 HTTP 层看不到（prompt 不回显），但正是「导师能引用到具体论文」与
 * 「普通智能体输出一字不变」两条硬约束的落点，所以单独钉住。
 */
class AgentKnowledgeStoreTest {

    private static final String SID = "s1";
    private static final String AID = "a1";

    private static PageBlock page(int index, String source, String text) {
        PageBlock p = new PageBlock();
        p.setPageIndex(index);
        p.setSourceName(source);
        p.getUnderstanding().setNarrative(text);
        return p;
    }

    private AgentKnowledgeStore storeWith(PageBlock... pages) {
        AgentKnowledgeStore store = new AgentKnowledgeStore();
        store.putPages(SID, AID, List.of(pages));
        return store;
    }

    @Test
    void retrievedContextCarriesSourceFileName() {
        AgentKnowledgeStore store = storeWith(
                page(0, "论文A.pdf", "时效工艺对第二相析出的影响"),
                page(1, "论文B.pdf", "透射电镜表征与尺寸统计"));

        String ctx = store.buildRetrievedContext(SID, AID, "透射电镜如何统计尺寸", 2);

        assertTrue(ctx.contains("《论文B.pdf》第 2 页"), "命中页应带来源文件名与页码：" + ctx);
        // 有命中就只给命中页 —— 不相关的页不该因为「凑数」混进上下文
        assertFalse(ctx.contains("论文A.pdf"), "未命中的页不应出现在检索结果里：" + ctx);
    }

    @Test
    void plainContextOutputIsUnchanged() {
        // 普通智能体走的就是这个：页头不带文件名，格式与本功能引入前完全一致
        AgentKnowledgeStore store = storeWith(page(0, "论文A.pdf", "正文"));

        String ctx = store.buildKnowledgeContext(SID, AID, 5);

        assertTrue(ctx.startsWith("【第 1 页】\n"), "普通上下文页头不应带文件名：" + ctx);
        assertFalse(ctx.contains("论文A.pdf"), "普通上下文不应出现文件名：" + ctx);
    }

    @Test
    void searchRanksByRelevance() {
        AgentKnowledgeStore store = storeWith(
                page(0, "论文A.pdf", "完全不相关的内容，讲的是别的东西"),
                page(1, "论文B.pdf", "时效工艺与硬度峰值的定量关系"),
                page(2, "论文C.pdf", "时效工艺对析出相尺寸的影响"));

        List<PageBlock> hits = store.search(SID, AID, "时效工艺 硬度", 2);

        assertEquals(2, hits.size());
        assertEquals(1, hits.get(0).getPageIndex(), "含全部查询词的页应排最前");
    }

    @Test
    void searchReturnsEmptyWhenNothingMatches() {
        AgentKnowledgeStore store = storeWith(page(0, "论文A.pdf", "时效工艺"));

        // 刻意不静默回退成「页序前 N 页」——调用方要能区分「命中了」与「没命中」
        assertTrue(store.search(SID, AID, "量子色动力学", 3).isEmpty());
        // 而检索版上下文仍要给出可用内容（回退到页序前 N 页）
        assertTrue(store.buildRetrievedContext(SID, AID, "量子色动力学", 3).contains("【《论文A.pdf》第 1 页】"));
    }

    @Test
    void searchKeepsPageOrderOnTies() {
        // 三页同分：必须保持页序，否则同一场讨论的上下文每次都不一样
        AgentKnowledgeStore store = storeWith(
                page(0, "论文A.pdf", "时效"),
                page(1, "论文B.pdf", "时效"),
                page(2, "论文C.pdf", "时效"));

        List<PageBlock> hits = store.search(SID, AID, "时效", 3);

        assertEquals(List.of(0, 1, 2), hits.stream().map(PageBlock::getPageIndex).toList());
    }

    @Test
    void emptyStoreIsHandled() {
        AgentKnowledgeStore store = new AgentKnowledgeStore();
        assertTrue(store.search(SID, AID, "任意问题", 3).isEmpty());
        assertEquals("（本智能体尚无文献知识）", store.buildRetrievedContext(SID, AID, "任意问题", 3));
    }
}
