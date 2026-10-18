package com.multiagent.discuss.service;

import com.multiagent.discuss.agent.AgentFactory;
import com.multiagent.discuss.api.dto.DiscussionDigest;
import com.multiagent.discuss.domain.ProfessionalConsensus;
import com.multiagent.discuss.domain.Question;
import com.multiagent.discuss.domain.Session;
import com.multiagent.discuss.domain.Turn;
import com.multiagent.discuss.knowledge.InMemorySessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 讨论结束后生成 Markdown 总结：
 * 每轮讨论一句概括 + 最终结论。支持单个议题或整个会话。
 *
 * <p>只产出文本，<b>不落盘</b> —— 保存位置由用户在浏览器里自己选
 * （前端 {@code lib/save.ts} 用 File System Access API 弹原生「另存为」对话框，
 * 不支持该 API 的浏览器回退成下载）。
 */
@Service
public class SummaryService {

    private static final Logger log = LoggerFactory.getLogger(SummaryService.class);

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final InMemorySessionRepository repo;
    private final AgentFactory agentFactory;

    public SummaryService(InMemorySessionRepository repo,
                          AgentFactory agentFactory) {
        this.repo = repo;
        this.agentFactory = agentFactory;
    }

    /**
     * 生成 Markdown 总结。
     *
     * @param scope        CURRENT_QUESTION 或 ALL_QUESTIONS
     * @param questionId   仅 CURRENT_QUESTION 时用；缺省取会话最后一个议题
     * @return 生成结果（fileName / scope / questionId / bytes / markdown）
     */
    public java.util.Map<String, Object> generate(String sessionId, String scope, String questionId) {
        Session session = repo.requireSession(sessionId);
        boolean all = "ALL_QUESTIONS".equalsIgnoreCase(scope);

        List<Question> target;
        if (all) {
            target = loadQuestions(session.getQuestionIds());
        } else {
            String qid = StringUtils.hasText(questionId)
                    ? questionId
                    : (session.getQuestionIds().isEmpty() ? null : last(session.getQuestionIds()));
            if (qid == null) {
                throw new IllegalArgumentException("当前会话还没有议题，无法生成总结");
            }
            target = List.of(repo.requireQuestion(sessionId, qid));
        }
        return render(session, target, all);
    }

    /**
     * 用「已还原的会话 + 议题」生成总结 —— 归档链路专用。
     *
     * <p>归档会话不在内存仓库里（见 {@code ArchivedSessionService}），所以由调用方负责
     * 把对象还原好传进来；这里只做渲染，逻辑与 {@link #generate} 完全共用。
     */
    public java.util.Map<String, Object> generateForArchived(Session session, List<Question> pool,
                                                             String scope, String questionId) {
        boolean all = "ALL_QUESTIONS".equalsIgnoreCase(scope);
        List<Question> target;
        if (all) {
            target = pool;
        } else {
            String qid = StringUtils.hasText(questionId)
                    ? questionId
                    : (pool.isEmpty() ? null : pool.get(pool.size() - 1).getId());
            if (qid == null) {
                throw new IllegalArgumentException("该归档里还没有议题，无法生成总结");
            }
            target = pool.stream().filter(q -> qid.equals(q.getId())).toList();
            if (target.isEmpty()) {
                throw new IllegalArgumentException("该归档里没有这个议题: " + qid);
            }
        }
        return render(session, target, all);
    }

    /** 渲染总结文本。两条入口（活会话 / 归档）共用这一段。 */
    private java.util.Map<String, Object> render(Session session,
                                                 List<Question> target, boolean all) {
        if (target.isEmpty()) {
            throw new IllegalArgumentException("没有可总结的议题");
        }

        ChatClient host = agentFactory.hostClient(session);
        String markdown = all
                ? generateAll(host, session, target)
                : generateSingle(host, session, target.get(0));

        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("fileName", "讨论总结_" + timestamp() + ".md");
        m.put("scope", all ? "ALL_QUESTIONS" : "CURRENT_QUESTION");
        m.put("questionId", target.get(0).getId());
        m.put("bytes", markdown.length());
        m.put("markdown", markdown);
        return m;
    }

    // ─── 单议题 ───────────────────────────────────────────────

    private String generateSingle(ChatClient host, Session session, Question q) {
        DiscussionDigest digest = new DiscussionDigest(q, session);
        digest.fillConsensus(q.getProfessionalConsensus());

        String header = "# 讨论总结\n\n"
                + "- 议题：" + q.getContent() + "\n"
                + "- 参与者：" + String.join("、", digest.participants) + "\n"
                + "- 时间：" + digest.createdAt + "\n";

        String content = llmSummary(host, digest);

        StringBuilder sb = new StringBuilder();
        sb.append(header);
        if (StringUtils.hasText(content)) {
            sb.append("\n").append(content.trim()).append("\n");
        } else {
            sb.append("\n").append(fallbackSingle(digest));
        }
        return sb.toString();
    }

    private String llmSummary(ChatClient host, DiscussionDigest digest) {
        try {
            return host.prompt().user("""
                    请为下面的多智能体讨论写一份简短的中文 Markdown 总结。

                    要求：
                    1. 开头一句话概括讨论主题与参与方。
                    2. 用「讨论过程」小节，按轮次概括每一轮讨论（独立思考算作第 0 轮），每轮 1~2 句话。
                    3. 用「最终结论」小节，整理最后达成的共识与结论（用要点列表）。
                    4. 全文 300~600 字左右，不要太长；不要粘贴原文大段内容。

                    讨论数据：
                    %s

                    已冻结的共识：
                    %s

                    只输出总结正文，不要输出额外说明。
                    """.formatted(digest.promptMaterial(), digest.consensus.promptText()))
                    .call()
                    .content();
        } catch (Exception e) {
            log.info("LLM 总结失败，回退本地模板: {}", e.toString());
            return null;
        }
    }

    /** LLM 不可用时的本地兜底模板。 */
    private String fallbackSingle(DiscussionDigest digest) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 讨论过程\n\n");
        sb.append("**独立思考**\n");
        for (DiscussionDigest.DigestTurn t : digest.independentThoughts) {
            sb.append("- ").append(t.speakerName).append("：").append(oneLine(t.content)).append('\n');
        }
        for (DiscussionDigest.DigestRound r : digest.rounds) {
            sb.append("\n**第 ").append(r.round).append(" 轮**\n");
            for (DiscussionDigest.DigestTurn t : r.turns) {
                sb.append("- ").append(t.speakerName).append("：").append(oneLine(t.content)).append('\n');
            }
        }
        sb.append("\n## 最终结论\n\n");
        if (StringUtils.hasText(digest.consensus.summary)) {
            sb.append(oneLine(digest.consensus.summary)).append('\n');
        }
        for (String c : digest.consensus.conclusions) {
            sb.append("- ").append(oneLine(c)).append('\n');
        }
        return sb.toString();
    }

    // ─── 整个会话 ─────────────────────────────────────────────

    private String generateAll(ChatClient host, Session session, List<Question> questions) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 多议题讨论总结\n\n");
        sb.append("- 会话：").append(session.getId()).append('\n');
        sb.append("- 议题数：").append(questions.size()).append('\n');
        sb.append("- 参与者：").append(String.join("、", DiscussionDigest.participantNames(questions))).append('\n');

        List<DiscussionDigest> digests = new ArrayList<>();
        int index = 1;
        for (Question q : questions) {
            DiscussionDigest d = new DiscussionDigest(q, session);
            d.fillConsensus(q.getProfessionalConsensus());
            digests.add(d);
            sb.append("\n## ").append(index++).append(". ").append(q.getContent()).append('\n');
            String content = llmSummary(host, d);
            sb.append(StringUtils.hasText(content) ? content.trim() : fallbackSingle(d));
            sb.append('\n');
        }

        sb.append("\n# 会话总体总结\n\n");
        String overall = llmOverall(host, digests);
        if (StringUtils.hasText(overall)) {
            sb.append(overall.trim()).append('\n');
        } else {
            sb.append("以上各议题的结论已分列如上。\n");
        }
        return sb.toString();
    }

    private String llmOverall(ChatClient host, List<DiscussionDigest> digests) {
        try {
            StringBuilder mat = new StringBuilder();
            for (DiscussionDigest d : digests) {
                mat.append("- 议题：").append(d.question).append('\n');
                mat.append("  共识：").append(d.consensus.summary == null ? "（无）" : oneLine(d.consensus.summary)).append('\n');
            }
            return host.prompt().user("""
                    下面是同一个多智能体讨论会话中若干议题的最终共识。请写一段简短的中文总体总结（200~350 字），
                    提炼共同主题、关键结论与后续建议。用 Markdown 要点或短段落均可。

                    各议题共识：
                    %s

                    只输出总结正文。
                    """.formatted(mat)).call().content();
        } catch (Exception e) {
            log.info("总体总结失败: {}", e.toString());
            return null;
        }
    }

    // ─── 辅助 ─────────────────────────────────────────────────

    private List<Question> loadQuestions(List<String> ids) {
        List<Question> out = new ArrayList<>();
        if (ids == null) {
            return out;
        }
        for (String id : ids) {
            repo.findQuestion(id).ifPresent(out::add);
        }
        return out;
    }

    private static String timestamp() {
        return LocalDateTime.now(ZoneId.systemDefault()).format(TS).replace(":", "-");
    }

    private static String last(List<String> list) {
        return list.get(list.size() - 1);
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
