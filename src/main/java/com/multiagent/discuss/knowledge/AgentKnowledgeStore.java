package com.multiagent.discuss.knowledge;

import com.multiagent.discuss.domain.PageBlock;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级知识隔离：键为 sessionId + agentId。
 * UserDoc 使用固定 agentId = "userDocAgent"。
 */
@Component
public class AgentKnowledgeStore {

    public static final String USER_DOC_AGENT_ID = "userDocAgent";

    private final Map<String, List<PageBlock>> store = new ConcurrentHashMap<>();

    private static String key(String sessionId, String agentId) {
        return sessionId + "::" + agentId;
    }

    public void putPages(String sessionId, String agentId, List<PageBlock> pages) {
        store.put(key(sessionId, agentId), new ArrayList<>(pages == null ? List.of() : pages));
    }

    public void appendPage(String sessionId, String agentId, PageBlock page) {
        store.computeIfAbsent(key(sessionId, agentId), k -> new ArrayList<>()).add(page);
    }

    public List<PageBlock> getPages(String sessionId, String agentId) {
        List<PageBlock> pages = store.get(key(sessionId, agentId));
        if (pages == null) {
            return List.of();
        }
        return Collections.unmodifiableList(pages);
    }

    /**
     * 简单 TopK：按页序取前 k 页（MVP）。后续可换检索。
     */
    public List<PageBlock> topK(String sessionId, String agentId, int k) {
        List<PageBlock> pages = getPages(sessionId, agentId);
        if (pages.isEmpty() || k <= 0) {
            return List.of();
        }
        return pages.subList(0, Math.min(k, pages.size()));
    }

    public String buildKnowledgeContext(String sessionId, String agentId, int topK) {
        List<PageBlock> pages = topK(sessionId, agentId, topK);
        if (pages.isEmpty()) {
            return "（本智能体尚无文献知识）";
        }
        StringBuilder sb = new StringBuilder();
        for (PageBlock p : pages) {
            sb.append(p.asContextSnippet()).append('\n');
        }
        return sb.toString();
    }

    // ── 按问题检索（导师模式专用） ────────────────────────────────
    //
    // 普通智能体只有一个文件，取页序前 N 页就够。导师的知识库里混着多篇论文，
    // 取前 N 页只会拿到第一篇的开头，必须按问题挑选相关页。

    /**
     * 按问题给页打分，返回命中页（按分数降序，同分保持页序）。
     *
     * <p><b>全部 0 分时返回空列表</b>，由 {@link #buildRetrievedContext} 明确回退到
     * 「页序前 N 页」—— 而不是在这里假装检索成功、静默返回前几页。调用方要能区分
     * 「命中了」和「没命中」，否则检索质量出问题时无从发现。
     */
    public List<PageBlock> search(String sessionId, String agentId, String query, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        // 防御性拷贝：页可能在讨论进行中被追加，直接遍历 getPages 的视图会踩并发修改
        List<PageBlock> pages = new ArrayList<>(getPages(sessionId, agentId));
        if (pages.isEmpty()) {
            return List.of();
        }
        QueryTerms terms = parseTerms(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        return pages.stream()
                .map(page -> Map.entry(page, score(page, terms)))
                .filter(e -> e.getValue() > 0)
                // sorted 对有序流是稳定排序，同分自然保持页序
                .sorted(Map.Entry.<PageBlock, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * 检索版知识上下文（带《文件名》页头，供导师引用到具体论文）。
     *
     * <p>没命中任何页时回退到「页序前 topK 页」，与 {@code buildKnowledgeContext} 的取法一致，
     * 只是页头同样带来源文件名。这样「资料不足」时模型至少还能看到论文开头，
     * 而不是拿到一句「尚无文献知识」。
     */
    public String buildRetrievedContext(String sessionId, String agentId, String query, int topK) {
        List<PageBlock> hits = search(sessionId, agentId, query, topK);
        List<PageBlock> pages = hits.isEmpty() ? topK(sessionId, agentId, topK) : hits;
        if (pages.isEmpty()) {
            return "（本智能体尚无文献知识）";
        }
        StringBuilder sb = new StringBuilder();
        for (PageBlock p : pages) {
            sb.append(p.asContextSnippetWithSource()).append('\n');
        }
        return sb.toString();
    }

    /** 查询词集：整词权重高、二元片段权重低。 */
    private record QueryTerms(Set<String> whole, Set<String> bigrams) {
        boolean isEmpty() {
            return whole.isEmpty() && bigrams.isEmpty();
        }
    }

    /**
     * 切词。
     *
     * <p>中日韩文字没有空格，靠二元片段（滑动窗口取连续两字）兜住词形变化；
     * 拉丁词本身就是一个完整语义单位，再拆二元片段只会制造噪声（「method」拆出「me」「et」…），
     * 所以只对含中日韩字符的 token 生成二元片段。
     */
    private static QueryTerms parseTerms(String query) {
        Set<String> whole = new LinkedHashSet<>();
        Set<String> bigrams = new LinkedHashSet<>();
        if (query == null) {
            return new QueryTerms(whole, bigrams);
        }
        for (String token : query.toLowerCase().split("[\\s，。；：、,.!?！？()（）\\[\\]【】\"'“”‘’/\\\\|]+")) {
            if (token.length() < 2) {
                continue;
            }
            whole.add(token);
            if (containsCjk(token)) {
                for (int i = 0; i + 2 <= token.length(); i++) {
                    bigrams.add(token.substring(i, i + 2));
                }
            }
        }
        return new QueryTerms(whole, bigrams);
    }

    private static boolean containsCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(s.charAt(i));
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL) {
                return true;
            }
        }
        return false;
    }

    /**
     * 页得分：整词命中 +4，二元片段命中 +1。
     *
     * <p>刻意不用「命中词长度累加」那种写法 —— 一个 token 连同它的全部二元片段都在词集里时，
     * 长 token 会被自己反复命中，分数最终只反映「查询串有多长」，与相关度脱钩。
     */
    private static int score(PageBlock page, QueryTerms terms) {
        String text = ((page.getPlainHint() == null ? "" : page.getPlainHint()) + " "
                + page.asContextSnippet()).toLowerCase();
        int score = 0;
        for (String term : terms.whole()) {
            if (text.contains(term)) score += 4;
        }
        for (String bigram : terms.bigrams()) {
            if (text.contains(bigram)) score += 1;
        }
        return score;
    }

    public void clearSession(String sessionId) {
        String prefix = sessionId + "::";
        store.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public void clearAgent(String sessionId, String agentId) {
        store.remove(key(sessionId, agentId));
    }
}
