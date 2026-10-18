package com.multiagent.discuss.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiagent.discuss.agent.AgentFactory;
import com.multiagent.discuss.db.entity.PersistentAgentDocEntity;
import com.multiagent.discuss.db.entity.PersistentAgentDocPageEntity;
import com.multiagent.discuss.db.entity.PersistentAgentEntity;
import com.multiagent.discuss.db.entity.UserEntity;
import com.multiagent.discuss.db.repo.PersistentAgentDocPageRepository;
import com.multiagent.discuss.db.repo.PersistentAgentDocRepository;
import com.multiagent.discuss.db.repo.PersistentAgentRepository;
import com.multiagent.discuss.domain.DomainAgentConfig;
import com.multiagent.discuss.domain.LlmEndpoint;
import com.multiagent.discuss.domain.MentorPaperSummary;
import com.multiagent.discuss.domain.PageBlock;
import com.multiagent.discuss.domain.Session;
import com.multiagent.discuss.knowledge.InMemorySessionRepository;
import com.multiagent.discuss.orchestrator.DiscussionPipeline;
import com.multiagent.discuss.security.AuthException;
import com.multiagent.discuss.security.KeyVault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 常驻智能体的**文件知识库**：把它读过的资料跨会话攒在账号下。
 *
 * <p><b>为什么需要它。</b> 0.9.0 之后常驻智能体的名字 / 人设 / 网关都跟着账号走了，
 * 唯独知识还只活在 {@link com.multiagent.discuss.knowledge.AgentKnowledgeStore} 里 ——
 * 那是个按 {@code sessionId::agentId} 键的内存 map，会话一销毁就全没了。结果就是
 * 「每次都重传文件」，它昨天读过的资料今天完全不记得。
 *
 * <p><b>知识什么时候入库。</b> 不是上传当时（那时它可能还没被选为常驻），而是
 * **结束会话、用户把它存为常驻之后** —— 届时前端对每个带 {@code agentUuid} 的席位调一次
 * {@code saveFromSeat}，把该席位本场成功读入的文件（{@link DomainAgentConfig#drainPendingDocs()}）
 * 连同九字段结构化摘要一并落库。
 *
 * <p><b>幂等靠「摘清单」，不靠唯一键。</b> {@code drainPendingDocs()} 在落库前就把清单取走并清空，
 * 所以「取消后再结束一次」「摘要失败后点重试」「两个标签页同时结束」都不会让同一批文件入库两遍。
 * 库里刻意**不加** {@code (agent, file_name)} 唯一键（与 {@code mentor_paper} 一致）：
 * 用户真的把同一份资料传了两次，就该是两条记录，由他在界面上自己删。
 *
 * <p><b>原始文件字节不入库</b>（照导师的既有取舍）：存的是 Vision 逐页理解的结果 + 摘要。
 * 代价要明说 —— 换模型后摘要可以重跑（{@link #summarize}），**页理解不能**，
 * 且删掉的知识无法重建。界面上的删除入口因此必须二次确认。
 *
 * <p><b>与导师侧的两处刻意差别</b>：
 * <ol>
 *   <li>证据来源：导师上传时有原始字节，摘要走「文字层优先」；我们在结束会话时**没有字节**
 *       （{@code ingestAgentDocument} 收的 {@code byte[]} 用完就扔），所以改成
 *       **{@code plainHint} 优先**（它本身是入库的），该页没有文字层才退回拼
 *       {@link PageBlock#asContextSnippet()}。</li>
 *   <li>落库是**一个事务**（doc 行 + 全部 page 行一次提交）。导师那边
 *       {@code paperRepo.save} 之后逐页 save、没有事务包裹，崩在中间会留下「有摘要、页数不全」
 *       的论文 —— 新表没有历史包袱，反过来做对。</li>
 * </ol>
 */
@Service
public class PersistentAgentDocService {

    private static final Logger log = LoggerFactory.getLogger(PersistentAgentDocService.class);

    /** 页已落库、摘要还没跑完（正常只存在一瞬间，进程半途崩掉才会被用户看见）。 */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_FAILED = "FAILED";

    /** 送给模型的证据上限，防止一份长报告把 prompt 撑爆（与导师侧同值）。 */
    static final int EVIDENCE_BUDGET = 18000;

    /**
     * 摘要用的 system prompt —— 与 {@code MentorService.ANALYSIS_SYSTEM} 逐字相同。
     *
     * <p>「不得补写原文没有的信息」「未给出的字段一律写未识别」正是用户要的
     * 「保证严谨性，不能造假或者虚构」。刻意各写一份而不是把它提成公共常量：
     * 两处的证据来源不同（导师是原文、我们是页理解），将来任一方要调措辞时不该互相牵制。
     */
    private static final String ANALYSIS_SYSTEM = """
            你是资料分析助手。严格依据给定内容作答，不得补写原文没有的信息；
            原文未给出的字段一律写「未识别」。""";

    private final PersistentAgentDocRepository docRepo;
    private final PersistentAgentDocPageRepository pageRepo;
    private final PersistentAgentRepository agentRepo;
    private final InMemorySessionRepository sessionRepo;
    private final AgentFactory agentFactory;
    private final KeyVault keyVault;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public PersistentAgentDocService(PersistentAgentDocRepository docRepo,
                                     PersistentAgentDocPageRepository pageRepo,
                                     PersistentAgentRepository agentRepo,
                                     InMemorySessionRepository sessionRepo,
                                     AgentFactory agentFactory,
                                     KeyVault keyVault,
                                     ObjectMapper objectMapper,
                                     PlatformTransactionManager txManager) {
        this.docRepo = docRepo;
        this.pageRepo = pageRepo;
        this.agentRepo = agentRepo;
        this.sessionRepo = sessionRepo;
        this.agentFactory = agentFactory;
        this.keyVault = keyVault;
        this.objectMapper = objectMapper;
        // 同类自调用 @Transactional 不生效，所以显式用模板而不是给方法加注解 ——
        // 摘要那步是分钟级 LLM 调用，绝不能占着事务跑（见 saveFromSeat）。
        this.txTemplate = new TransactionTemplate(txManager);
    }

    // ── 保存：结束会话时，把席位本场读过的文件落库 ──────────────

    /**
     * 把某个席位本场**已成功读入、但还没落库**的文件存进它的常驻知识库。
     *
     * <p>目标（存给哪个常驻智能体）<b>一律从席位推导</b>，不接受前端传入 —— 与
     * {@code AgentMemoryService.summarize} 同口径：前端传不了也传不错，进度条的目标集合
     * 与后端判定天然一致。
     *
     * @return {@code ok} / {@code docCount} / {@code pageCount} / {@code failedCount} / {@code docs}。
     *         清单本来就空（或重复调用）时 {@code ok=true, docCount=0} —— 那是**成功**不是错误，
     *         否则「取消后重来」会被标成一片红。席位没挂 {@code agentUuid}（不是常驻席位）时
     *         {@code ok=false}，前端据此把这一格标灰。
     */
    public Map<String, Object> saveFromSeat(UserEntity user, String sessionId, String seatAgentId) {
        Session session = sessionRepo.requireSession(sessionId);
        DomainAgentConfig seat = session.getAgents().get(seatAgentId);
        if (seat == null) {
            throw new IllegalArgumentException("智能体不存在: " + seatAgentId);
        }
        if (session.getOwnerUserId() == null) {
            // 匿名会话没有账号，知识无处归属。前端本就跳过，这里是后端的兜底。
            throw new AuthException("会话未登录，知识无处归属");
        }
        String uuid = seat.getAgentUuid();
        if (!StringUtils.hasText(uuid)) {
            return result(false, uuid, List.of(), 0);
        }
        // ⚠️ 顺序要紧：先校验归属、再摘清单。反过来的话，常驻被删后的一次调用会把清单
        // 摘走再抛异常 —— 那批页知识就永久丢了（原始字节不入库，救不回来）。
        PersistentAgentEntity agent = requireOwnedAgent(uuid, session.getOwnerUserId());
        return save(user, agent, seat.drainPendingDocs(), agentFactory.taskClient(seat, ANALYSIS_SYSTEM));
    }

    /** {@link #saveFromSeat} 的主体。抽出来是为了让「先校验再摘清单」这条顺序在调用处一目了然。 */
    private Map<String, Object> save(UserEntity user, PersistentAgentEntity agent,
                                     List<DomainAgentConfig.PendingDoc> pending, ChatClient client) {
        if (pending.isEmpty()) {
            return result(true, agent.getAgentUuid(), List.of(), 0);
        }
        List<Map<String, Object>> docs = new ArrayList<>();
        int pageTotal = 0;
        int failed = 0;
        for (DomainAgentConfig.PendingDoc pd : pending) {
            Long docId;
            try {
                docId = insertDoc(agent.getId(), pd);
            } catch (Exception e) {
                // 整篇没落进去（DB 层的问题）。标红让用户重试，但**不中断其余文件** ——
                // 一个文件写不进去，不该让同一批里其它文件也白跑。
                log.error("常驻知识落库失败 agent={} file={}: {}", agent.getId(), pd.fileName(), e.toString());
                failed++;
                // pageCount 记 0：这一篇整篇没进库，报「12 页」会让用户以为它读到了
                docs.add(docEntry(null, pd.fileName(), STATUS_FAILED, "落库失败：" + e.getMessage(), 0));
                continue;
            }
            pageTotal += pd.pages().size();

            // 摘要放在事务**之外**：它是分钟级 LLM 调用，占着事务会把连接池拖干。
            // 失败也不回滚页 —— 页知识照常可检索、可讨论，只是界面上看不到九字段。
            String status = STATUS_READY;
            String message = null;
            try {
                applySummary(docId, summarize(client, pd.fileName(), pd.pages()));
            } catch (Exception e) {
                log.warn("常驻知识摘要失败 doc={} file={}: {}", docId, pd.fileName(), e.toString());
                status = STATUS_FAILED;
                message = trimTo(e.getMessage(), 500);
                failed++;
                markSummaryFailed(docId, message);
            }
            docs.add(docEntry(docId, pd.fileName(), status, message, pd.pages().size()));
        }
        log.info("常驻知识入库 agent={} uuid={} docs={} pages={} failed={}",
                agent.getId(), agent.getAgentUuid(), docs.size(), pageTotal, failed);
        return result(true, agent.getAgentUuid(), docs, failed);
    }

    /**
     * doc 行 + 它的全部 page 行，**一个事务**。
     *
     * <p>崩在中间时宁可整篇不落，也不要留下「有摘要、页数不全」的行 —— 那种行看上去正常，
     * 检索却永远少几页，用户没有任何线索能发现。
     */
    private Long insertDoc(Long persistentAgentId, DomainAgentConfig.PendingDoc pd) {
        return txTemplate.execute(status -> {
            PersistentAgentDocEntity doc = new PersistentAgentDocEntity();
            doc.setPersistentAgentId(persistentAgentId);
            doc.setFileName(pd.fileName());
            doc.setPageCount(pd.pages().size());
            // 摘要出来前先拿文件名占位：用户此刻在列表里至少能认出这是哪份文件
            doc.setTitle(pd.fileName());
            doc.setSummaryStatus(STATUS_PENDING);
            doc.setSortOrder(docRepo.nextSortOrder(persistentAgentId));
            PersistentAgentDocEntity saved = docRepo.save(doc);

            for (PageBlock b : pd.pages()) {
                PersistentAgentDocPageEntity pe = new PersistentAgentDocPageEntity();
                pe.setDocId(saved.getId());
                pe.setPageIndex(b.getPageIndex());
                pe.setPlainHint(b.getPlainHint());
                pe.setUnderstanding(writeJson(b.getUnderstanding()));
                pageRepo.save(pe);
            }
            return saved.getId();
        });
    }

    // ── 摘要 ─────────────────────────────────────────────────

    /** 重跑某一篇的摘要（界面上那篇标 FAILED 时的「重试」按钮）。 */
    public Map<String, Object> summarize(UserEntity user, Long docId, String sessionIdOrNull) {
        PersistentAgentDocEntity doc = requireOwnedDoc(user, docId);
        List<PageBlock> pages = pagesOf(doc);
        // 重跑时可能已经没有活会话了（弹窗是账号级的），所以这里走依附 uuid 的阶梯，
        // 不能像 saveFromSeat 那样直接拿席位当 client。
        Session session = StringUtils.hasText(sessionIdOrNull)
                ? sessionRepo.findSession(sessionIdOrNull).orElse(null)
                : null;
        ChatClient client = retryClient(doc.getPersistentAgentId(), session);
        try {
            applySummary(docId, summarize(client, doc.getFileName(), pages));
        } catch (Exception e) {
            log.warn("常驻知识摘要重跑失败 doc={} file={}: {}", docId, doc.getFileName(), e.toString());
            markSummaryFailed(docId, trimTo(e.getMessage(), 500));
        }
        // 重新读一遍：上面两条分支都已经改了 summary_status，用旧对象会把状态回退给前端
        return docView(docRepo.findById(docId).orElse(doc), true);
    }

    private void applySummary(Long docId, MentorPaperSummary s) {
        PersistentAgentDocEntity doc = docRepo.findById(docId).orElse(null);
        if (doc == null) {
            return;
        }
        doc.setTitle(s.title());
        doc.setAuthors(s.authors());
        doc.setPubYear(s.pubYear());
        doc.setAbstractText(s.abstractText());
        doc.setResearchQuestion(s.researchQuestion());
        doc.setMethodology(s.methodology());
        doc.setFindings(s.findings());
        doc.setLimitations(s.limitations());
        doc.setKeywords(s.keywords());
        doc.setSummaryStatus(STATUS_READY);
        doc.setSummaryMessage(null);
        docRepo.save(doc);
    }

    private void markSummaryFailed(Long docId, String message) {
        docRepo.findById(docId).ifPresent(doc -> {
            doc.setSummaryStatus(STATUS_FAILED);
            doc.setSummaryMessage(message);
            docRepo.save(doc);
        });
    }

    /**
     * 让模型读这份资料的页理解、产出九字段结构化摘要。
     *
     * <p>解析失败**不抛**：退回「标题=文件名、其余=未识别」并把模型原文塞进摘要首字段，
     * 免得用户只看到一片「未识别」却不知道发生了什么（与 {@code MentorService} 同款降级）。
     */
    private MentorPaperSummary summarize(ChatClient client, String fileName, List<PageBlock> pages) {
        String evidence = buildEvidence(pages);
        String raw = client.prompt().user("""
                【资料逐篇摘要】请只依据下面这份资料的内容生成结构化摘要，不得补写资料中没有的信息。
                不确定或原文未给出的字段请写"未识别"。输出且只输出 JSON：
                {
                  "title":"资料标题",
                  "authors":"作者或发布方",
                  "publicationYear":"发布年份",
                  "abstractText":"具体摘要（背景、对象与总体结论）",
                  "researchQuestion":"要解决的问题或目标",
                  "methods":"研究方法、数据或实验设计",
                  "keyFindings":"主要发现与证据边界",
                  "limitations":"局限、适用条件或原文未说明之处",
                  "keywords":"关键词，使用顿号分隔"
                }

                文件名：%s
                资料内容：
                %s
                """.formatted(fileName, evidence)).call().content();
        return parseSummary(raw, fileName, objectMapper);
    }

    /**
     * 把模型返回的 JSON 解析成九字段摘要。
     *
     * <p>包级可见是为了让单测能直接钉住降级行为：喂非法 JSON 必须**仍返回可用摘要而不是抛**。
     */
    static MentorPaperSummary parseSummary(String raw, String fileName, ObjectMapper objectMapper) {
        try {
            JsonNode n = objectMapper.readTree(DiscussionPipeline.stripFencePublic(raw));
            return new MentorPaperSummary(
                    field(n, "title", fileName),
                    field(n, "authors", MentorPaperSummary.UNKNOWN),
                    field(n, "publicationYear", MentorPaperSummary.UNKNOWN),
                    field(n, "abstractText", MentorPaperSummary.UNKNOWN),
                    field(n, "researchQuestion", MentorPaperSummary.UNKNOWN),
                    field(n, "methods", MentorPaperSummary.UNKNOWN),
                    field(n, "keyFindings", MentorPaperSummary.UNKNOWN),
                    field(n, "limitations", MentorPaperSummary.UNKNOWN),
                    field(n, "keywords", MentorPaperSummary.UNKNOWN));
        } catch (Exception e) {
            log.warn("逐篇摘要 JSON 解析失败 file={}: {}", fileName, e.toString());
            return new MentorPaperSummary(fileName, MentorPaperSummary.UNKNOWN, MentorPaperSummary.UNKNOWN,
                    StringUtils.hasText(raw) ? raw : MentorPaperSummary.UNKNOWN,
                    MentorPaperSummary.UNKNOWN, MentorPaperSummary.UNKNOWN,
                    MentorPaperSummary.UNKNOWN, MentorPaperSummary.UNKNOWN, MentorPaperSummary.UNKNOWN);
        }
    }

    /**
     * 拼给模型的证据：**逐页**优先用可提取的文字层（{@code plainHint}），该页没有才退回
     * Vision 理解的渲染文本。
     *
     * <p>与导师侧的差别是必然的：导师上传时手上有原始文件字节，可以整篇走「文字层优先」；
     * 我们在结束会话时**没有字节**（{@code byte[]} 用完就扔，不入库），手上只有
     * {@code plainHint} 与页理解两样。逐页判定比整篇判定更贴合：一份扫描件里往往
     * 前几页是文字、后面全是图，逐页能取到最全的证据。
     *
     * <p>包级可见是为了让单测直接钉住「优先顺序 + 截断」。
     */
    static String buildEvidence(List<PageBlock> pages) {
        StringBuilder sb = new StringBuilder();
        for (PageBlock p : pages) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            if (StringUtils.hasText(p.getPlainHint())) {
                sb.append("【第 ").append(p.getPageIndex() + 1).append(" 页】\n").append(p.getPlainHint());
            } else {
                sb.append(p.asContextSnippet());
            }
        }
        if (sb.length() > EVIDENCE_BUDGET) {
            return sb.substring(0, EVIDENCE_BUDGET) + "\n…（截断）";
        }
        return sb.toString();
    }

    private static String field(JsonNode n, String name, String fallback) {
        if (n == null || !n.has(name) || n.get(name).isNull() || !StringUtils.hasText(n.get(name).asText())) {
            return fallback;
        }
        return n.get(name).asText().trim();
    }

    /**
     * 明细重跑摘要用的客户端。网关阶梯：**它自己的 → 指定会话的 → 服务端默认**。
     *
     * <p>形态照 {@code AgentMemoryService.correctionClient}。
     */
    private ChatClient retryClient(Long persistentAgentId, Session sessionOrNull) {
        PersistentAgentEntity e = agentRepo.findById(persistentAgentId).orElse(null);
        String ownKey = e == null ? null : safeDecrypt(e.getApiKeyEnc());
        if (StringUtils.hasText(ownKey)) {
            return agentFactory.openAiCompatible(ownKey, e.getBaseUrl(),
                    modelOrDefault(e.getModel()), ANALYSIS_SYSTEM);
        }
        LlmEndpoint ep = sessionOrNull == null ? null : sessionOrNull.getLlmEndpoint();
        if (ep != null && ep.hasCredentials()) {
            return agentFactory.openAiCompatible(ep.getApiKey(), ep.getBaseUrl(),
                    modelOrDefault(ep.getModel()), ANALYSIS_SYSTEM);
        }
        return agentFactory.hostClient();
    }

    private static String modelOrDefault(String model) {
        return StringUtils.hasText(model) ? model : "gpt-4o-mini";
    }

    // ── 读 ───────────────────────────────────────────────────

    /** 知识列表（轻，不带摘要正文）。 */
    public List<Map<String, Object>> list(UserEntity user, String agentUuid) {
        PersistentAgentEntity agent = requireOwnedAgent(agentUuid, user.getId());
        return docRepo.findAllByPersistentAgentIdOrderBySortOrderAscIdAsc(agent.getId()).stream()
                .map(d -> docView(d, false))
                .toList();
    }

    /** 九字段详情（重）。 */
    public Map<String, Object> detail(UserEntity user, String agentUuid, Long docId) {
        PersistentAgentEntity agent = requireOwnedAgent(agentUuid, user.getId());
        return docView(requireDoc(agent.getId(), docId), true);
    }

    @Transactional
    public void delete(UserEntity user, String agentUuid, Long docId) {
        PersistentAgentEntity agent = requireOwnedAgent(agentUuid, user.getId());
        PersistentAgentDocEntity doc = requireDoc(agent.getId(), docId);
        // 显式删页再删文件：DB 层有 ON DELETE CASCADE，但那要等 persistent_agent_doc 的行真的
        // 被删掉才触发；显式删让「删了多少页」在日志里可查，也与 MentorService.deletePaper 一致。
        pageRepo.deleteAllByDocId(docId);
        docRepo.delete(doc);
    }

    /**
     * 加入会话时把整个知识库还原成页知识，灌进该会话的知识库。
     *
     * <p>还原时 {@code imageRef} 置 null（临时渲染产物，不入库），
     * {@code sourceName} 用 {@code file_name} 回填 —— 表里**没有** source_name 列，
     * 它就是所属文件的名字。这个回填很关键：讨论时页头是「《文件名》第 N 页」，
     * 模型才有一个**真实存在**的来源可引，而不是被逼着编一个。
     *
     * <p>无 owner 校验：调用方（{@code PersistentAgentService.joinSession}）已经
     * {@code requireOwned} 过了。加一道只是重复；不加的话**任何调用方都必须先校验**。
     */
    public List<PageBlock> loadPages(String agentUuid) {
        PersistentAgentEntity agent = agentRepo.findByAgentUuid(agentUuid).orElse(null);
        if (agent == null) {
            return List.of();
        }
        List<PageBlock> pages = new ArrayList<>();
        for (PersistentAgentDocEntity doc : docRepo
                .findAllByPersistentAgentIdOrderBySortOrderAscIdAsc(agent.getId())) {
            for (PersistentAgentDocPageEntity row : pageRepo.findAllByDocIdOrderByPageIndexAsc(doc.getId())) {
                PageBlock block = new PageBlock();
                block.setPageIndex(row.getPageIndex());
                block.setPlainHint(row.getPlainHint());
                block.setImageRef(null);
                block.setSourceName(doc.getFileName());
                block.setUnderstanding(readUnderstanding(row.getUnderstanding()));
                pages.add(block);
            }
        }
        return pages;
    }

    /** 单个常驻的「N 个文件 · M 页」。 */
    public DocCounts counts(String agentUuid) {
        PersistentAgentEntity agent = agentRepo.findByAgentUuid(agentUuid).orElse(null);
        if (agent == null) {
            return DocCounts.EMPTY;
        }
        // 复用批量聚合那条查询，省掉一个只为一个 agent 服务的重复 JPQL
        return countsByAgentIds(List.of(agent.getId())).getOrDefault(agent.getId(), DocCounts.EMPTY);
    }

    /**
     * 一次查回多个常驻的计数，供常驻列表用（避免对每个 agent 各查一次造成 N+1）。
     *
     * @return agentId 主键 → 计数；没有知识的 agent 不在 map 里，调用方按 {@link DocCounts#EMPTY} 兜底
     */
    public Map<Long, DocCounts> countsByAgentIds(List<Long> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, DocCounts> out = new LinkedHashMap<>();
        for (PersistentAgentDocRepository.DocCount c : docRepo.countByAgentIds(agentIds)) {
            out.put(c.getAgentId(), new DocCounts(c.getDocCount(), c.getPageCount()));
        }
        return out;
    }

    /** 一个常驻智能体的知识规模。 */
    public record DocCounts(long docCount, long pageCount) {
        public static final DocCounts EMPTY = new DocCounts(0, 0);
    }

    // ── 内部：视图与杂项 ─────────────────────────────────────

    /** 列表不带摘要正文，避免一次拉回几十篇长文。 */
    private Map<String, Object> docView(PersistentAgentDocEntity d, boolean withSummary) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("fileName", d.getFileName());
        m.put("title", d.getTitle());
        m.put("authors", d.getAuthors());
        m.put("pubYear", d.getPubYear());
        m.put("keywords", d.getKeywords());
        m.put("pageCount", d.getPageCount());
        m.put("status", d.getSummaryStatus());
        m.put("errorMessage", d.getSummaryMessage());
        m.put("createdAt", d.getCreatedAt() != null ? d.getCreatedAt().toEpochMilli() : null);
        if (withSummary) {
            m.put("abstractText", d.getAbstractText());
            m.put("researchQuestion", d.getResearchQuestion());
            m.put("methodology", d.getMethodology());
            m.put("findings", d.getFindings());
            m.put("limitations", d.getLimitations());
        }
        return m;
    }

    private Map<String, Object> docEntry(Long docId, String fileName, String status, String message, int pages) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("docId", docId);
        m.put("fileName", fileName);
        m.put("summaryStatus", status);
        m.put("summaryMessage", message);
        m.put("pageCount", pages);
        return m;
    }

    private Map<String, Object> result(boolean ok, String agentUuid, List<Map<String, Object>> docs, int failed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", ok);
        m.put("agentUuid", agentUuid);
        m.put("docCount", docs.size());
        m.put("pageCount", docs.stream()
                .mapToInt(d -> ((Number) d.get("pageCount")).intValue())
                .sum());
        m.put("failedCount", failed);
        m.put("docs", docs);
        return m;
    }

    private List<PageBlock> pagesOf(PersistentAgentDocEntity doc) {
        List<PageBlock> pages = new ArrayList<>();
        for (PersistentAgentDocPageEntity row : pageRepo.findAllByDocIdOrderByPageIndexAsc(doc.getId())) {
            PageBlock block = new PageBlock();
            block.setPageIndex(row.getPageIndex());
            block.setPlainHint(row.getPlainHint());
            block.setImageRef(null);
            block.setSourceName(doc.getFileName());
            block.setUnderstanding(readUnderstanding(row.getUnderstanding()));
            pages.add(block);
        }
        return pages;
    }

    private PageBlock.Understanding readUnderstanding(String json) {
        if (!StringUtils.hasText(json)) {
            return new PageBlock.Understanding();
        }
        try {
            return objectMapper.readValue(json, PageBlock.Understanding.class);
        } catch (Exception e) {
            log.warn("页理解 JSON 解析失败: {}", e.toString());
            return new PageBlock.Understanding();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private PersistentAgentEntity requireOwnedAgent(String agentUuid, Long userId) {
        return agentRepo.findByAgentUuidAndOwnerUserId(agentUuid, userId)
                .orElseThrow(() -> new AuthException("常驻智能体不存在或无权限"));
    }

    /**
     * 先按 docId 取行、再用它的父 agent 过 owner。
     *
     * <p>按 docId 直接查而不走「先取 agent 再在它的文档里找」：docId 是全局自增主键，
     * 拿到别人的 docId 时这里会**先查到行、再被 owner 挡下** → 401，
     * 与「文档不存在」的 400 区分得开。
     */
    private PersistentAgentDocEntity requireOwnedDoc(UserEntity user, Long docId) {
        PersistentAgentDocEntity doc = docRepo.findById(docId)
                .orElseThrow(() -> new IllegalArgumentException("知识条目不存在: " + docId));
        agentRepo.findByIdAndOwnerUserId(doc.getPersistentAgentId(), user.getId())
                .orElseThrow(() -> new AuthException("常驻智能体不存在或无权限"));
        return doc;
    }

    private PersistentAgentDocEntity requireDoc(Long persistentAgentId, Long docId) {
        return docRepo.findByIdAndPersistentAgentId(docId, persistentAgentId)
                .orElseThrow(() -> new IllegalArgumentException("知识条目不存在: " + docId));
    }

    private String safeDecrypt(String enc) {
        if (!StringUtils.hasText(enc)) {
            return null;
        }
        try {
            return keyVault.decrypt(enc);
        } catch (Exception ex) {
            log.warn("常驻智能体网关解密失败（master password 换过？）: {}", ex.toString());
            return null;
        }
    }

    private static String trimTo(String value, int max) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String t = value.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
