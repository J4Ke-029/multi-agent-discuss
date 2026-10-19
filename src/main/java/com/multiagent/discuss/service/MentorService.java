package com.multiagent.discuss.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiagent.discuss.agent.AgentFactory;
import com.multiagent.discuss.config.AppProperties;
import com.multiagent.discuss.db.entity.MentorEntity;
import com.multiagent.discuss.db.entity.MentorPaperEntity;
import com.multiagent.discuss.db.entity.MentorPaperPageEntity;
import com.multiagent.discuss.db.entity.UserEntity;
import com.multiagent.discuss.db.repo.MentorPaperPageRepository;
import com.multiagent.discuss.db.repo.MentorPaperRepository;
import com.multiagent.discuss.db.repo.MentorRepository;
import com.multiagent.discuss.domain.DomainAgentConfig;
import com.multiagent.discuss.domain.KnowledgeStatus;
import com.multiagent.discuss.domain.LlmEndpoint;
import com.multiagent.discuss.domain.MemoryTargetType;
import com.multiagent.discuss.domain.MentorPaperSummary;
import com.multiagent.discuss.domain.PageBlock;
import com.multiagent.discuss.domain.Session;
import com.multiagent.discuss.domain.TokenUsage;
import com.multiagent.discuss.ingest.DocumentIngestService;
import com.multiagent.discuss.ingest.DocumentIngestService.PageImage;
import com.multiagent.discuss.knowledge.AgentKnowledgeStore;
import com.multiagent.discuss.knowledge.InMemorySessionRepository;
import com.multiagent.discuss.orchestrator.DiscussionPipeline;
import com.multiagent.discuss.security.AuthException;
import com.multiagent.discuss.security.KeyVault;
import com.multiagent.discuss.vision.VisionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 导师（账号级论文语料库）的 CRUD、论文摄取与「加入会话」。
 *
 * <p>与普通领域智能体的根本差别：导师<b>跨会话复用</b>。论文的逐页理解结果落在
 * {@code mentor_paper_page}，加入某个会话时只需从库里读出来灌进该会话的知识库
 * （{@link AgentKnowledgeStore}），<b>不重跑 Vision、不需要重传文件</b>，因此是秒级操作。
 *
 * <p>另一处差别是知识注入方式：普通席位取「页序前 N 页」，导师的库里混着多篇论文，
 * 取前 N 页只会拿到第一篇的开头，所以讨论时改走按问题检索（见
 * {@link AgentKnowledgeStore#buildRetrievedContext}）。
 *
 * <p>⚠️ 导师**保存 API Key**（jasypt 加密），这样它能以自己配置的网关直接跑讨论。
 * 自 2.4.0 起常驻智能体也保存网关，两者在这点上已经一致（同一套加密 + 掩码 + reveal 口径）；
 * 差别只剩「导师没有『用本场网关』的覆盖项」。
 * 列表接口只回脱敏值，明文走 {@link #reveal}。
 */
@Service
public class MentorService {

    private static final Logger log = LoggerFactory.getLogger(MentorService.class);

    /** 导师还没有任何可用论文。 */
    public static final String STATUS_WAITING_PAPERS = "WAITING_PAPERS";
    /** 至少有一篇论文处理成功（含「已有论文、后续某篇失败」的情形——失败不会把已就绪的导师降级）。 */
    public static final String STATUS_READY = "READY";
    /** 一篇都没成功过。 */
    public static final String STATUS_FAILED = "FAILED";

    private static final String PAPER_READY = "READY";
    private static final String PAPER_FAILED = "FAILED";

    /** 单批上传上限（前端一次一篇，这个限制是给直接调 API 的场景兜底）。 */
    private static final int MAX_PAPERS_PER_BATCH = 20;

    /** 送给模型的论文样本上限，防止长论文把 prompt 撑爆。 */
    private static final int PROFILE_SAMPLE_BUDGET = 16000;

    private static final String ANALYSIS_SYSTEM = """
            你是学术论文分析助手。严格依据给定论文内容作答，不得补写原文没有的信息；
            原文未给出的字段一律写「未识别」。""";

    private final MentorRepository mentorRepo;
    private final MentorPaperRepository paperRepo;
    private final MentorPaperPageRepository pageRepo;
    private final KeyVault keyVault;
    private final SessionService sessionService;
    private final InMemorySessionRepository sessionRepo;
    private final AgentKnowledgeStore knowledgeStore;
    private final AgentFactory agentFactory;
    private final DocumentIngestService ingestService;
    private final VisionService visionService;
    private final AppProperties props;
    private final ObjectMapper objectMapper;
    private final AgentMemoryService agentMemoryService;
    private final SkillBindingService skillBindingService;

    /**
     * 导师自己配了网关时的 ChatClient 缓存。键含密文本身，所以改 Key 后自然重建。
     * 条目数受「用户配过几种网关」限制，不会随会话数增长。
     */
    private final Map<String, ChatClient> clientCache = new ConcurrentHashMap<>();

    public MentorService(MentorRepository mentorRepo,
                         MentorPaperRepository paperRepo,
                         MentorPaperPageRepository pageRepo,
                         KeyVault keyVault,
                         SessionService sessionService,
                         InMemorySessionRepository sessionRepo,
                         AgentKnowledgeStore knowledgeStore,
                         AgentFactory agentFactory,
                         DocumentIngestService ingestService,
                         VisionService visionService,
                         AppProperties props,
                         ObjectMapper objectMapper,
                         AgentMemoryService agentMemoryService,
                         SkillBindingService skillBindingService) {
        this.mentorRepo = mentorRepo;
        this.paperRepo = paperRepo;
        this.pageRepo = pageRepo;
        this.keyVault = keyVault;
        this.sessionService = sessionService;
        this.sessionRepo = sessionRepo;
        this.knowledgeStore = knowledgeStore;
        this.agentFactory = agentFactory;
        this.ingestService = ingestService;
        this.visionService = visionService;
        this.props = props;
        this.objectMapper = objectMapper;
        this.agentMemoryService = agentMemoryService;
        this.skillBindingService = skillBindingService;
    }

    // ── 账号级 CRUD ───────────────────────────────────────────

    public List<Map<String, Object>> list(UserEntity user) {
        return mentorRepo.findAllByOwnerUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(this::maskedView)
                .toList();
    }

    public Map<String, Object> detail(UserEntity user, Long id) {
        return fullView(requireOwned(user, id));
    }

    /** 明文接口：仅本人按 id 取明文 Key，供「用导师自己的网关跑讨论」场景使用。 */
    public Map<String, Object> reveal(UserEntity user, Long id) {
        return fullView(requireOwned(user, id));
    }

    @Transactional
    public Map<String, Object> create(UserEntity user, String name, String discipline, String focus,
                                      String personality, String model, String baseUrl, String apiKey) {
        String cleanName = trimTo(name, 64);
        if (!StringUtils.hasText(cleanName)) {
            throw new IllegalArgumentException("导师称呼不能为空");
        }
        MentorEntity e = new MentorEntity();
        e.setOwnerUserId(user.getId());
        e.setMentorUuid(UUID.randomUUID().toString());
        e.setName(cleanName);
        e.setDiscipline(trimTo(discipline, 128));
        e.setFocus(trimTo(focus, 4000));
        e.setPersonality(trimTo(personality, 256));
        e.setModel(trimTo(model, 128));
        e.setBaseUrl(trimTo(baseUrl, 512));
        e.setApiKeyEnc(StringUtils.hasText(apiKey) ? keyVault.encrypt(apiKey.trim()) : null);
        e.setStatus(STATUS_WAITING_PAPERS);
        e.setStatusMessage("等待上传导师论文");
        // 还没有论文，画像为空 —— persona 里如实写明「尚未完成画像」，避免模型凭空演一个风格
        e.setPersona(composePersona(e.getName(), e.getDiscipline(), e.getFocus(), null, null));
        e = mentorRepo.save(e);
        return fullView(e);
    }

    @Transactional
    public Map<String, Object> update(UserEntity user, Long id, String name, String discipline, String focus,
                                      String personality, String model, String baseUrl, String apiKey) {
        MentorEntity e = requireOwned(user, id);
        if (StringUtils.hasText(name)) {
            e.setName(trimTo(name, 64));
        }
        if (discipline != null) e.setDiscipline(trimTo(discipline, 128));
        if (focus != null) e.setFocus(trimTo(focus, 4000));
        if (personality != null) e.setPersonality(trimTo(personality, 256));
        if (model != null) e.setModel(trimTo(model, 128));
        if (baseUrl != null) e.setBaseUrl(trimTo(baseUrl, 512));
        // 空 apiKey = 不改（与 llm_preset 的语义一致），要清空得走单独的语义，本版不做
        if (StringUtils.hasText(apiKey)) {
            e.setApiKeyEnc(keyVault.encrypt(apiKey.trim()));
        }
        e.setPersona(composePersona(e.getName(), e.getDiscipline(), e.getFocus(),
                e.getStyleProfile(), e.getKnowledgeMap()));
        e = mentorRepo.save(e);
        return fullView(e);
    }

    @Transactional
    public void delete(UserEntity user, Long id) {
        MentorEntity e = requireOwned(user, id);
        // mentor_paper / mentor_paper_page 都是 ON DELETE CASCADE；
        // 但经验心得按 target_uuid 多态引用，建不了外键，必须在这里显式清掉，否则是孤儿行
        agentMemoryService.deleteAllFor(MemoryTargetType.MENTOR, e.getMentorUuid());
        skillBindingService.deleteBindingsForTarget(
                user.getId(), SkillBindingService.TargetType.MENTOR, e.getMentorUuid());
        mentorRepo.delete(e);
    }

    // ── 论文 ─────────────────────────────────────────────────

    public List<Map<String, Object>> listPapers(UserEntity user, Long mentorId) {
        requireOwned(user, mentorId);
        return paperRepo.findAllByMentorIdOrderBySortOrderAsc(mentorId).stream()
                .map(p -> paperView(p, false))
                .toList();
    }

    public Map<String, Object> paperDetail(UserEntity user, Long mentorId, Long paperId) {
        requireOwned(user, mentorId);
        return paperView(requirePaper(mentorId, paperId), true);
    }

    @Transactional
    public void deletePaper(UserEntity user, Long mentorId, Long paperId) {
        MentorEntity mentor = requireOwned(user, mentorId);
        MentorPaperEntity paper = requirePaper(mentorId, paperId);
        pageRepo.deleteAllByPaperId(paperId);
        paperRepo.delete(paper);
        refreshCounts(mentor);
        if (mentor.getPaperCount() == 0) {
            mentor.setStatus(STATUS_WAITING_PAPERS);
            mentor.setStatusMessage("等待上传导师论文");
        }
        mentorRepo.save(mentor);
    }

    /**
     * 上传一篇导师论文：解析 → 逐页 Vision → 逐篇结构化摘要 → 落库 → 重建画像。
     *
     * <p><b>刻意做成「一篇一次请求 + 逐篇落库」</b>：20 篇批量跑起来是分钟级，塞进一个
     * HTTP 请求必然超时；逐篇落库则保证中断时已完成的不白费。前端据此逐篇串行调用，
     * 进度靠 {@link #status} 轮询（{@code progressCurrent/Total}）。
     *
     * <p><b>失败不降级</b>：某篇失败只写一条 FAILED 记录并把原因回给用户，
     * 不会把已经有论文的导师打成 FAILED —— 否则一次手滑就毁掉整个已就绪的导师库。
     *
     * @param sessionIdOrNull 可选。导师自己没配网关时，退而使用这个会话的网关；
     *                        都没有才落到服务端默认网关。
     */
    public Map<String, Object> addPaper(UserEntity user, Long mentorId, MultipartFile file, String sessionIdOrNull) {
        MentorEntity mentor = requireOwned(user, mentorId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择一篇导师论文");
        }
        Session session = sessionIdOrNull == null ? null : sessionRepo.findSession(sessionIdOrNull).orElse(null);
        ChatClient client = analysisClient(mentor, session);

        String fileName = safeFileName(file.getOriginalFilename());
        Path workDir = mentorWorkDir(mentor.getMentorUuid());

        mentor.setProgressCurrent(0);
        mentor.setProgressTotal(0);
        mentor.setStatusMessage("正在解析《" + fileName + "》…");
        mentorRepo.save(mentor);

        try {
            byte[] bytes = file.getBytes();
            List<PageImage> images = ingestService.ingestToPages(bytes, fileName, workDir);
            mentor.setProgressTotal(images.size());
            mentor.setProgressCurrent(0);
            mentor.setStatusMessage("《" + fileName + "》阅读中 0 / " + images.size() + " 页");
            mentorRepo.save(mentor);

            TokenUsage sink = new TokenUsage();
            List<PageBlock> blocks = visionService.analyzePages(images, (done) -> {
                mentor.setProgressCurrent(done);
                mentor.setStatusMessage("《" + fileName + "》阅读中 " + done + " / " + images.size() + " 页");
            }, sink);
            log.info("导师论文 Vision 用量 mentor={} file={} in={} out={} cost={}",
                    mentorId, fileName, sink.getInputTokens(), sink.getOutputTokens(), sink.getCostEstimate());

            for (PageBlock b : blocks) {
                b.setSourceName(fileName);
                // 页面渲染图指向临时目录，重启即失效且前端从不读，不入库
                b.setImageRef(null);
            }

            MentorPaperSummary summary = summarize(client, fileName, bytes, blocks);

            MentorPaperEntity paper = new MentorPaperEntity();
            paper.setMentorId(mentorId);
            paper.setFileName(fileName);
            paper.setPageCount(blocks.size());
            paper.setTitle(summary.title());
            paper.setAuthors(summary.authors());
            paper.setPubYear(summary.pubYear());
            paper.setAbstractText(summary.abstractText());
            paper.setResearchQuestion(summary.researchQuestion());
            paper.setMethodology(summary.methodology());
            paper.setFindings(summary.findings());
            paper.setLimitations(summary.limitations());
            paper.setKeywords(summary.keywords());
            paper.setSummaryStatus(PAPER_READY);
            paper.setSortOrder((int) paperRepo.countByMentorId(mentorId));
            paper = paperRepo.save(paper);

            for (PageBlock b : blocks) {
                MentorPaperPageEntity pe = new MentorPaperPageEntity();
                pe.setPaperId(paper.getId());
                pe.setPageIndex(b.getPageIndex());
                pe.setPlainHint(b.getPlainHint());
                pe.setUnderstanding(writeJson(b.getUnderstanding()));
                pageRepo.save(pe);
            }

            rebuildProfile(mentor, client);
            refreshCounts(mentor);
            mentor.setStatus(STATUS_READY);
            mentor.setStatusMessage("导师论文库就绪：" + mentor.getPaperCount() + " 篇，"
                    + mentor.getPageCount() + " 页");
            mentorRepo.save(mentor);
            return paperView(paper, false);
        } catch (Exception e) {
            log.warn("导师论文处理失败 mentor={} file={}: {}", mentorId, fileName, e.toString());
            saveFailedPaper(mentorId, fileName, e.getMessage());
            refreshCounts(mentor);
            // 已有论文就不降级——只把这次失败写进 statusMessage
            mentor.setStatus(mentor.getPaperCount() > 0 ? STATUS_READY : STATUS_FAILED);
            mentor.setStatusMessage("《" + fileName + "》处理失败：" + e.getMessage());
            mentor.setProgressCurrent(0);
            mentor.setProgressTotal(0);
            mentorRepo.save(mentor);
            throw new IllegalStateException("导师论文处理失败: " + e.getMessage(), e);
        } finally {
            deleteTree(workDir);
        }
    }

    public Map<String, Object> status(UserEntity user, Long id) {
        MentorEntity e = requireOwned(user, id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("status", e.getStatus());
        m.put("statusMessage", e.getStatusMessage());
        m.put("paperCount", e.getPaperCount());
        m.put("pageCount", e.getPageCount());
        m.put("progressCurrent", e.getProgressCurrent());
        m.put("progressTotal", e.getProgressTotal());
        return m;
    }

    // ── 加入会话 ─────────────────────────────────────────────

    /**
     * 把导师加入某个会话：注册一个导师席位，并把库里已有的论文页知识灌进该会话的知识库。
     *
     * <p><b>加入即快照</b>：之后往导师库追加论文不会回流到本会话（用户已确认这一语义），
     * 下次新建会话再加入时才带上新论文。
     */
    public Map<String, Object> joinSession(UserEntity user, Long mentorId, String sessionId) {
        MentorEntity mentor = requireOwned(user, mentorId);
        Session session = sessionRepo.requireSession(sessionId);
        if (isJoined(session, mentor.getMentorUuid())) {
            throw new IllegalStateException("该导师已经在本场讨论中了");
        }

        List<MentorPaperEntity> papers = paperRepo.findAllByMentorIdOrderBySortOrderAsc(mentorId);
        List<PageBlock> pages = loadPages(papers);

        DomainAgentConfig agent = sessionService.registerMentorAgent(
                sessionId, mentor.getName(), mentor.getPersona(), mentor.getPersonality(),
                mentor.getModel(), mentor.getBaseUrl(), keyVault.decrypt(mentor.getApiKeyEnc()));

        agent.setMentor(true);
        agent.setMentorUuid(mentor.getMentorUuid());
        agent.setMentorDiscipline(mentor.getDiscipline());
        agent.setMentorPaperCount(papers.size());
        agent.setMentorStyleProfile(mentor.getStyleProfile());
        agent.setSkillUuids(skillBindingService.boundSkills(user.getId(),
                SkillBindingService.TargetType.MENTOR, mentor.getMentorUuid()).stream()
                .map(com.multiagent.discuss.db.entity.SkillEntity::getSkillUuid).toList());

        if (pages.isEmpty()) {
            agent.setKnowledgeStatus(KnowledgeStatus.AGENT_CREATED);
            agent.setStatusMessage("该导师还没有可用论文，请先上传导师论文");
        } else {
            knowledgeStore.putPages(sessionId, agent.getId(), pages);
            agent.setKnowledgeStatus(KnowledgeStatus.KNOWLEDGE_READY);
            agent.setDocumentFileName(papers.size() + " 篇导师论文");
            agent.setStatusMessage("导师知识就绪：" + papers.size() + " 篇，" + pages.size() + " 页");
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agentId", agent.getId());
        m.put("mentorId", mentor.getId());
        m.put("mentorUuid", mentor.getMentorUuid());
        m.put("name", agent.getName());
        m.put("paperCount", papers.size());
        m.put("pageCount", pages.size());
        m.put("knowledgeStatus", agent.getKnowledgeStatus().name());
        log.info("导师加入会话 session={} mentor={} agent={} papers={} pages={}",
                sessionId, mentorId, agent.getId(), papers.size(), pages.size());
        return m;
    }

    /** 该导师是否已经在本会话里（按 uuid 判，避免同一个导师占两个席位重复发言）。 */
    private boolean isJoined(Session session, String mentorUuid) {
        return session.getAgents().values().stream()
                .anyMatch(a -> a.isMentor() && mentorUuid != null && mentorUuid.equals(a.getMentorUuid()));
    }

    // ── 内部：论文摘要与画像 ──────────────────────────────────

    private MentorPaperSummary summarize(ChatClient client, String fileName, byte[] bytes, List<PageBlock> blocks) {
        String evidence = plainTextOrNull(bytes, fileName);
        if (!StringUtils.hasText(evidence)) {
            evidence = blocks.stream()
                    .map(PageBlock::asContextSnippet)
                    .collect(Collectors.joining("\n"));
        }
        if (evidence.length() > 18000) {
            evidence = evidence.substring(0, 18000) + "\n…（截断）";
        }

        String raw = client.prompt().user("""
                【导师论文逐篇摘要】请只依据下面这篇论文的内容生成结构化摘要，不得补写论文中没有的信息。
                不确定或原文未给出的字段请写"未识别"。输出且只输出 JSON：
                {
                  "title":"论文标题",
                  "authors":"作者",
                  "publicationYear":"发表年份",
                  "abstractText":"具体摘要（研究背景、对象与总体结论）",
                  "researchQuestion":"研究问题或目标",
                  "methods":"研究方法、数据或实验设计",
                  "keyFindings":"主要发现与证据边界",
                  "limitations":"局限、适用条件或原文未说明之处",
                  "keywords":"关键词，使用顿号分隔"
                }

                文件名：%s
                论文内容：
                %s
                """.formatted(fileName, evidence)).call().content();

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
            // 解析不出来也要让这篇论文可用：标题用文件名，其余字段标未识别，
            // 并把模型原文塞进摘要，免得用户只看到一片"未识别"却不知道发生了什么
            log.warn("逐篇摘要 JSON 解析失败 file={}: {}", fileName, e.toString());
            return new MentorPaperSummary(fileName, MentorPaperSummary.UNKNOWN, MentorPaperSummary.UNKNOWN,
                    StringUtils.hasText(raw) ? raw : MentorPaperSummary.UNKNOWN,
                    MentorPaperSummary.UNKNOWN, MentorPaperSummary.UNKNOWN,
                    MentorPaperSummary.UNKNOWN, MentorPaperSummary.UNKNOWN, MentorPaperSummary.UNKNOWN);
        }
    }

    /**
     * 用当前全部论文重建「学术风格」与「知识版图」两份画像，并据此重写 persona。
     *
     * <p>样本取各篇的<b>摘要正文</b>而不是整页理解：摘要是作者本人的行文，判风格最有效，
     * 体量也可控（20 篇约 20 KB）；把所有页理解拼起来反而会被表格和图表描述淹没。
     */
    private void rebuildProfile(MentorEntity mentor, ChatClient client) {
        List<MentorPaperEntity> papers = paperRepo.findAllByMentorIdOrderBySortOrderAsc(mentor.getId()).stream()
                .filter(p -> PAPER_READY.equals(p.getSummaryStatus()))
                .toList();
        if (papers.isEmpty()) {
            return;
        }
        String sample = papers.stream()
                .map(this::paperAsSample)
                .collect(Collectors.joining("\n\n--- 论文分隔 ---\n\n"));
        if (sample.length() > PROFILE_SAMPLE_BUDGET) {
            sample = sample.substring(0, PROFILE_SAMPLE_BUDGET) + "\n…（截断）";
        }

        String raw = client.prompt().user("""
                【导师画像】请基于以下导师论文样本，建立可复用的学术指导画像。
                必须区分「论文可证实的内容」与「风格推断」，不得推断导师的私人态度或立场。
                输出以下两段，保留标签原样输出：
                【学术风格】常用论证结构、术语密度、句法与措辞、证据偏好、批评方式。
                【知识版图】研究主题、常用方法、核心理论、证据边界、适合指导的问题。

                学科方向：%s
                指导重点：%s
                已有风格画像（首次可为空，若本次没有新信息可原样保留）：
                %s

                论文样本：
                %s
                """.formatted(nullToEmpty(mentor.getDiscipline()), nullToEmpty(mentor.getFocus()),
                nullToEmpty(mentor.getStyleProfile()), sample)).call().content();

        String normalized = raw == null ? "" : raw.trim();
        String marker = "【知识版图】";
        int split = normalized.indexOf(marker);
        String style = split >= 0
                ? normalized.substring(0, split).replace("【学术风格】", "").trim()
                : normalized;
        String knowledge = split >= 0 ? normalized.substring(split + marker.length()).trim() : "";

        if (StringUtils.hasText(style)) {
            mentor.setStyleProfile(style);
        }
        if (StringUtils.hasText(knowledge)) {
            mentor.setKnowledgeMap(knowledge);
        }
        mentor.setPersona(composePersona(mentor.getName(), mentor.getDiscipline(), mentor.getFocus(),
                mentor.getStyleProfile(), mentor.getKnowledgeMap()));
        mentorRepo.save(mentor);
    }

    private String paperAsSample(MentorPaperEntity p) {
        StringBuilder sb = new StringBuilder();
        sb.append("文件名：").append(p.getFileName()).append('\n');
        sb.append("标题：").append(nullToEmpty(p.getTitle())).append('\n');
        sb.append("作者：").append(nullToEmpty(p.getAuthors()))
                .append("（").append(nullToEmpty(p.getPubYear())).append("）\n");
        sb.append("关键词：").append(nullToEmpty(p.getKeywords())).append('\n');
        sb.append("摘要：").append(nullToEmpty(p.getAbstractText())).append('\n');
        sb.append("研究问题：").append(nullToEmpty(p.getResearchQuestion())).append('\n');
        sb.append("方法与数据：").append(nullToEmpty(p.getMethodology())).append('\n');
        sb.append("主要发现：").append(nullToEmpty(p.getFindings())).append('\n');
        sb.append("局限：").append(nullToEmpty(p.getLimitations())).append('\n');
        return sb.toString();
    }

    /**
     * persona 模板。四条边界是硬要求，不能因为用户改了称呼/学科就丢掉 ——
     * 它们决定了这个功能是「语料模拟」还是「冒充真人」。
     *
     * <p>⚠️ <b>经验心得绝不能拼进这里。</b> {@link #update} 与 {@link #rebuildProfile}
     * 每次改设置、每上传一篇论文都会用本方法<b>整体重写</b> persona，塞进去的心得下次上传论文
     * 就没了。心得走的是另一条路：加入会话时快照到席位，由
     * {@code AgentFactory.buildDomainSystem} 单独注入 system prompt。
     */
    private String composePersona(String name, String discipline, String focus,
                                  String styleProfile, String knowledgeMap) {
        return """
                你是「导师学术指导模拟器」，依据 %s 公开或已获授权的论文所形成的知识与写作风格画像，
                为研究生提供论文选题、论证、方法、结构与表达方面的指导。
                学科方向：%s。指导重点：%s。

                【必须遵守的边界】
                1. 你是基于论文语料的**模拟**，不代表 %s 本人。不要使用「我认为」「我的课题组」这类
                   冒充本人的措辞，提到该导师时用「该导师的论文」这类第三人称表述。
                2. 事实性结论必须给出可核查的来源（论文文件名 + 页码）；给不出来就说资料不足，
                   绝不编造引用、页码、实验数据或该导师的观点。
                3. 论文语料没有覆盖的问题，直接说明「论文中未涉及」，并建议补充材料或向真实导师确认。
                4. 批评要具体、可执行，同时保持学术诚信，不做人身评价。

                【学术风格画像】
                %s

                【知识版图】
                %s
                """.formatted(
                nullToEmpty(name),
                nullToEmpty(discipline).isEmpty() ? "未填写" : discipline,
                nullToEmpty(focus).isEmpty() ? "未填写" : focus,
                nullToEmpty(name),
                StringUtils.hasText(styleProfile) ? styleProfile : "尚未完成风格画像（还没有上传导师论文）。",
                StringUtils.hasText(knowledgeMap) ? knowledgeMap : "尚未建立知识版图（还没有上传导师论文）。");
    }

    // ── 内部：网关解析 ───────────────────────────────────────

    /**
     * 论文分析与画像用的 ChatClient。
     *
     * <p>网关解析顺序：<b>导师自己的网关 → 指定会话的网关 → 服务端默认网关</b>。
     * 中间那层是必要的：导师是账号级对象，创建时用户可能只配了会话网关，
     * 若不做这层回退，这类用户一上传论文就会因为「没有可用网关」而失败。
     */
    private ChatClient analysisClient(MentorEntity mentor, Session sessionOrNull) {
        String apiKey = keyVault.decrypt(mentor.getApiKeyEnc());
        if (StringUtils.hasText(apiKey)) {
            // 缓存键含 baseUrl/model/密文三者：只改网关地址或模型（不重设 Key）时密文不变，
            // 少了任一项都会拿到过期的客户端。密文而非明文入键，避免明文 Key 留在堆里的 map 键上。
            String cacheKey = "mentor:" + nullToEmpty(mentor.getBaseUrl()) + ":" + nullToEmpty(mentor.getModel())
                    + ":" + mentor.getApiKeyEnc();
            return clientCache.computeIfAbsent(cacheKey, k -> agentFactory.openAiCompatible(
                    apiKey, mentor.getBaseUrl(), modelOrDefault(mentor.getModel()), ANALYSIS_SYSTEM));
        }
        if (sessionOrNull != null) {
            LlmEndpoint ep = sessionOrNull.getLlmEndpoint();
            if (ep != null && ep.hasCredentials()) {
                // 会话网关不缓存：缓存键只能拿明文 Key 或它的弱哈希，前者留密钥、后者可能撞键用错网关，
                // 都不划算。一次上传只调一两次，现建客户端的开销可以接受。
                return agentFactory.openAiCompatible(
                        ep.getApiKey(), ep.getBaseUrl(), modelOrDefault(ep.getModel()), ANALYSIS_SYSTEM);
            }
        }
        return agentFactory.hostClient();
    }

    private static String modelOrDefault(String model) {
        return StringUtils.hasText(model) ? model : "gpt-4o-mini";
    }

    // ── 内部：持久化辅助 ─────────────────────────────────────

    private void saveFailedPaper(Long mentorId, String fileName, String message) {
        try {
            MentorPaperEntity paper = new MentorPaperEntity();
            paper.setMentorId(mentorId);
            paper.setFileName(fileName);
            paper.setPageCount(0);
            paper.setTitle(fileName);
            paper.setSummaryStatus(PAPER_FAILED);
            paper.setSummaryMessage(trimTo(message, 500));
            paper.setSortOrder((int) paperRepo.countByMentorId(mentorId));
            paperRepo.save(paper);
        } catch (Exception e) {
            // 记录失败本身不该再抛，否则会把真正的失败原因盖掉
            log.warn("写入失败论文记录时出错 mentor={} file={}: {}", mentorId, fileName, e.toString());
        }
    }

    private void refreshCounts(MentorEntity mentor) {
        List<MentorPaperEntity> papers = paperRepo.findAllByMentorIdOrderBySortOrderAsc(mentor.getId());
        mentor.setPaperCount(papers.size());
        mentor.setPageCount(papers.stream().mapToInt(MentorPaperEntity::getPageCount).sum());
    }

    /**
     * 从库里读出该导师全部论文的页知识，还原成 {@link PageBlock}。
     *
     * <p>按论文（已按 sort_order 排好）再按页序展开 —— 检索同分时保持的就是这个顺序，
     * 所以「论文的上传顺序」在讨论里是可复现的。
     */
    private List<PageBlock> loadPages(List<MentorPaperEntity> papers) {
        List<PageBlock> pages = new ArrayList<>();
        for (MentorPaperEntity paper : papers) {
            for (MentorPaperPageEntity row : pageRepo.findAllByPaperIdOrderByPageIndexAsc(paper.getId())) {
                PageBlock block = new PageBlock();
                block.setPageIndex(row.getPageIndex());
                block.setPlainHint(row.getPlainHint());
                // 页图与 imageRef 都不入库：临时渲染产物，重启即失效
                block.setImageRef(null);
                block.setSourceName(paper.getFileName());
                block.setUnderstanding(readUnderstanding(row.getUnderstanding()));
                pages.add(block);
            }
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

    private String plainTextOrNull(byte[] bytes, String fileName) {
        try {
            String text = ingestService.extractPlainText(bytes, fileName);
            return StringUtils.hasText(text) ? text : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ── 内部：杂项 ───────────────────────────────────────────

    private MentorEntity requireOwned(UserEntity user, Long id) {
        return mentorRepo.findByIdAndOwnerUserId(id, user.getId())
                .orElseThrow(() -> new AuthException("导师不存在或无权限"));
    }

    private MentorPaperEntity requirePaper(Long mentorId, Long paperId) {
        return paperRepo.findByIdAndMentorId(paperId, mentorId)
                .orElseThrow(() -> new IllegalArgumentException("论文不存在: " + paperId));
    }

    private Map<String, Object> maskedView(MentorEntity e) {
        Map<String, Object> m = baseView(e);
        m.put("apiKey", StringUtils.hasText(e.getApiKeyEnc())
                ? KeyVault.mask(keyVault.decrypt(e.getApiKeyEnc())) : null);
        return m;
    }

    private Map<String, Object> fullView(MentorEntity e) {
        Map<String, Object> m = baseView(e);
        m.put("apiKey", keyVault.decrypt(e.getApiKeyEnc()));
        m.put("styleProfile", e.getStyleProfile());
        m.put("knowledgeMap", e.getKnowledgeMap());
        m.put("persona", e.getPersona());
        return m;
    }

    private Map<String, Object> baseView(MentorEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("mentorUuid", e.getMentorUuid());
        m.put("name", e.getName());
        m.put("discipline", e.getDiscipline());
        m.put("focus", e.getFocus());
        m.put("personality", e.getPersonality());
        m.put("model", e.getModel());
        m.put("baseUrl", e.getBaseUrl());
        m.put("status", e.getStatus());
        m.put("statusMessage", e.getStatusMessage());
        m.put("paperCount", e.getPaperCount());
        m.put("pageCount", e.getPageCount());
        m.put("progressCurrent", e.getProgressCurrent());
        m.put("progressTotal", e.getProgressTotal());
        m.put("createdAt", e.getCreatedAt() != null ? e.getCreatedAt().toEpochMilli() : null);
        m.put("updatedAt", e.getUpdatedAt() != null ? e.getUpdatedAt().toEpochMilli() : null);
        return m;
    }

    /** 列表不带摘要正文，避免一次拉回几十篇长文。 */
    private Map<String, Object> paperView(MentorPaperEntity p, boolean withSummary) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("fileName", p.getFileName());
        m.put("title", p.getTitle());
        m.put("authors", p.getAuthors());
        m.put("pubYear", p.getPubYear());
        m.put("keywords", p.getKeywords());
        m.put("pageCount", p.getPageCount());
        m.put("status", p.getSummaryStatus());
        m.put("errorMessage", p.getSummaryMessage());
        m.put("createdAt", p.getCreatedAt() != null ? p.getCreatedAt().toEpochMilli() : null);
        if (withSummary) {
            m.put("abstractText", p.getAbstractText());
            m.put("researchQuestion", p.getResearchQuestion());
            m.put("methodology", p.getMethodology());
            m.put("findings", p.getFindings());
            m.put("limitations", p.getLimitations());
        }
        return m;
    }

    private Path mentorWorkDir(String mentorUuid) {
        Path dir = Path.of(props.getSession().getTempDir(), "mentor-" + mentorUuid,
                UUID.randomUUID().toString().substring(0, 8));
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            throw new IllegalStateException("无法创建导师临时目录: " + e.getMessage(), e);
        }
        return dir;
    }

    /** 页图与中间文本只在处理期间有用，处理完就删（best-effort）。 */
    private void deleteTree(Path dir) {
        try {
            if (dir == null || !Files.exists(dir)) {
                return;
            }
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // 删不掉就留着，不影响功能
                    }
                });
            }
        } catch (Exception e) {
            log.warn("清理导师临时目录失败 {}: {}", dir, e.toString());
        }
    }

    private String field(JsonNode n, String name, String fallback) {
        if (n == null || !n.has(name) || n.get(name).isNull() || !StringUtils.hasText(n.get(name).asText())) {
            return fallback;
        }
        return n.get(name).asText().trim();
    }

    private String safeFileName(String value) {
        if (!StringUtils.hasText(value)) {
            return "paper.pdf";
        }
        try {
            String name = Path.of(value).getFileName().toString();
            if (!StringUtils.hasText(name)) {
                return "paper.pdf";
            }
            return name.length() > 180 ? name.substring(name.length() - 180) : name;
        } catch (Exception e) {
            return "paper.pdf";
        }
    }

    private String trimTo(String value, int max) {
        if (!StringUtils.hasText(value)) return null;
        String t = value.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
