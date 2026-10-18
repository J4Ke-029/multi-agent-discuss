package com.multiagent.discuss.api;

import com.multiagent.discuss.api.dto.AskQuestionRequest;
import com.multiagent.discuss.api.dto.CreateSessionRequest;
import com.multiagent.discuss.api.dto.GenerateSummaryRequest;
import com.multiagent.discuss.api.dto.RegisterAgentRequest;
import com.multiagent.discuss.api.dto.RevisionConfirmRequest;
import com.multiagent.discuss.api.dto.UpdateLlmEndpointRequest;
import com.multiagent.discuss.api.dto.UpdateSessionSettingsRequest;
import com.multiagent.discuss.db.entity.ArchivedSessionEntity;
import com.multiagent.discuss.domain.DomainAgentConfig;
import com.multiagent.discuss.domain.LlmEndpoint;
import com.multiagent.discuss.domain.MemoryTargetType;
import com.multiagent.discuss.domain.Question;
import com.multiagent.discuss.domain.QuestionStatus;
import com.multiagent.discuss.domain.Session;
import com.multiagent.discuss.knowledge.InMemorySessionRepository;
import com.multiagent.discuss.orchestrator.DiscussionPipeline;
import com.multiagent.discuss.security.AuthContext;
import com.multiagent.discuss.service.AgentMemoryService;
import com.multiagent.discuss.service.ArchivedSessionService;
import com.multiagent.discuss.service.MentorService;
import com.multiagent.discuss.service.PersistentAgentDocService;
import com.multiagent.discuss.service.PersistentAgentService;
import com.multiagent.discuss.service.SessionService;
import com.multiagent.discuss.service.SummaryService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class SessionController {

    private final SessionService sessionService;
    private final DiscussionPipeline discussionPipeline;
    private final SummaryService summaryService;
    private final InMemorySessionRepository repo;
    private final ArchivedSessionService archivedSessionService;
    private final MentorService mentorService;
    private final AgentMemoryService agentMemoryService;
    private final PersistentAgentService persistentAgentService;
    private final PersistentAgentDocService persistentAgentDocService;

    public SessionController(SessionService sessionService,
                             DiscussionPipeline discussionPipeline,
                             SummaryService summaryService,
                             InMemorySessionRepository repo,
                             ArchivedSessionService archivedSessionService,
                             MentorService mentorService,
                             AgentMemoryService agentMemoryService,
                             PersistentAgentService persistentAgentService,
                             PersistentAgentDocService persistentAgentDocService) {
        this.sessionService = sessionService;
        this.discussionPipeline = discussionPipeline;
        this.summaryService = summaryService;
        this.repo = repo;
        this.archivedSessionService = archivedSessionService;
        this.mentorService = mentorService;
        this.agentMemoryService = agentMemoryService;
        this.persistentAgentService = persistentAgentService;
        this.persistentAgentDocService = persistentAgentDocService;
    }

    @PostMapping("/sessions")
    public Map<String, Object> createSession(@RequestBody(required = false) CreateSessionRequest req) {
        if (req == null) req = new CreateSessionRequest();
        Session s = sessionService.createSession(
                req.getUserStyle(),
                req.getAutoDiscuss(),
                req.getDocEditMode(),
                req.getHistoryLimit(),
                req.getMaxRounds(),
                req.getLlmApiKey(),
                req.getLlmBaseUrl(),
                req.getLlmModel(),
                req.getLlmLabel());
        return sessionView(s);
    }

    /**
     * 当前用户的会话列表（「对话」侧栏用）。
     *
     * <p>只返回摘要——各议题的详细内容仍要走 {@code GET /api/sessions/{sid}}；
     * 侧栏需要高频轮询以显示「思考中 / 讨论中」这类实时状态，所以这里必须足够轻。
     */
    @GetMapping("/sessions")
    public List<Map<String, Object>> listSessions() {
        return sessionService.listVisibleSessions().stream().map(this::sessionSummary).toList();
    }

    /**
     * 归档并销毁会话 —— 「结束会话」的最后一步。
     *
     * <p>把整场讨论快照写进 {@code archived_session}，再清掉内存会话。快照已脱敏
     * （见 {@code ArchivedSessionService}），不含任何 API Key。
     *
     * <p>两种情况不归档，只销毁：
     * <ul>
     *   <li>会话没有议题 —— 没有内容可看，归档只是噪音；</li>
     *   <li>匿名调用 —— archived_session.owner_user_id 是 NOT NULL 外键，
     *       匿名用户没有库里的行，无处归属。此时行为与旧版一致（直接丢失）。</li>
     * </ul>
     */
    @PostMapping("/sessions/{sid}/archive")
    public Map<String, Object> archiveSession(@PathVariable String sid) {
        sessionService.requireAccess(sid);
        Session s = sessionService.getSession(sid);

        List<String> qids = s.getQuestionIds();
        if (qids.isEmpty()) {
            sessionService.deleteSession(sid);
            return Map.of("archived", false, "reason", "会话还没有议题，已直接结束");
        }
        if (AuthContext.currentUserIdOrNull() == null) {
            sessionService.deleteSession(sid);
            return Map.of("archived", false, "reason", "未登录，会话未归档（登录后结束的对话才会存档）");
        }

        List<Question> questions = new ArrayList<>();
        for (String qid : qids) {
            repo.findQuestion(qid).ifPresent(questions::add);
        }
        if (questions.isEmpty()) {
            sessionService.deleteSession(sid);
            return Map.of("archived", false, "reason", "会话还没有议题，已直接结束");
        }

        ArchivedSessionEntity saved = archivedSessionService.archive(
                AuthContext.require(), s, questions, summaryTitle(s, questions.get(0)));
        sessionService.deleteSession(sid);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("archived", true);
        m.put("id", saved.getId());
        m.put("title", saved.getTitle());
        m.put("questionCount", questions.size());
        return m;
    }

    @GetMapping("/sessions/{sid}")
    public Map<String, Object> getSession(@PathVariable String sid) {
        sessionService.requireAccess(sid);
        return sessionView(sessionService.getSession(sid));
    }

    /** 热切换会话默认第三方网关（Host + 后续继承该配置的智能体） */
    @PutMapping("/sessions/{sid}/llm")
    public Map<String, Object> updateLlm(@PathVariable String sid,
                                         @RequestBody UpdateLlmEndpointRequest req) {
        sessionService.requireAccess(sid);
        if (req == null) req = new UpdateLlmEndpointRequest();
        Session s = sessionService.updateLlmEndpoint(
                sid,
                req.getLlmApiKey(),
                req.getLlmBaseUrl(),
                req.getLlmModel(),
                req.getLlmLabel(),
                true);
        return sessionView(s);
    }

    /**
     * 改会话配置：文风 / 最高讨论轮次。
     * 改稿模式 / 继续讨论不在这里 —— 它们建会话时决定后不可变。
     */
    @PutMapping("/sessions/{sid}/settings")
    public Map<String, Object> updateSettings(@PathVariable String sid,
                                              @RequestBody(required = false) UpdateSessionSettingsRequest req) {
        sessionService.requireAccess(sid);
        UpdateSessionSettingsRequest r = req == null ? new UpdateSessionSettingsRequest() : req;
        Session s = sessionService.updateSettings(sid, r.getUserStyle(), r.getMaxRounds());
        return sessionView(s);
    }

    @PutMapping("/sessions/{sid}/interjections")
    public Map<String, Object> updateInterjections(@PathVariable String sid,
                                                    @RequestBody(required = false) InterjectionSettingRequest req) {
        sessionService.requireAccess(sid);
        Session session = discussionPipeline.setAllowInterjections(sid, req != null && req.allowInterjections);
        return sessionView(session);
    }

    @DeleteMapping("/sessions/{sid}")
    public Map<String, Object> deleteSession(@PathVariable String sid) {
        sessionService.requireAccess(sid);
        sessionService.deleteSession(sid);
        return Map.of("deleted", true, "sessionId", sid);
    }

    @PostMapping("/sessions/{sid}/agents")
    public Map<String, Object> registerAgent(@PathVariable String sid,
                                             @Valid @RequestBody RegisterAgentRequest req) {
        sessionService.requireAccess(sid);
        // 从常驻库加入时先验身份：归属 + 同场去重。刻意放在注册之前 ——
        // 这两件事都会抛错，先注册再校验会在会话里留下一个挂在 uuid 上的悬空席位。
        String agentUuid = StringUtils.hasText(req.getAgentUuid()) ? req.getAgentUuid().trim() : null;
        agentMemoryService.assertJoinable(sid, MemoryTargetType.PERSISTENT_AGENT, agentUuid);

        // 带 agentUuid = 从常驻库加入：网关取自常驻行（它自己存的），请求体里的
        // apiKey/baseUrl/model 一律忽略，人设也以库为准。明文 Key 只在服务端内存里流转，
        // 前端拿不到（agentView 回的是掩码），所以这一步只能由后端做。
        //
        // ⚠️ 不带 agentUuid 的那一支必须与本功能之前**逐字节相同** —— 同一个方法、同一组实参。
        DomainAgentConfig a = (agentUuid == null)
                ? sessionService.registerAgent(
                        sid, req.getName(), req.getApiKey(), req.getBaseUrl(), req.getModel(),
                        req.getPersona(), req.getUseSessionLlm(), req.getPersonality())
                : persistentAgentService.joinSession(
                        AuthContext.require(), sid, agentUuid, req.getUseSessionLlm());
        // 注册之后再挂心得快照：此后这个席位的 system prompt 才带着既往经验
        if (agentUuid != null) {
            agentMemoryService.attachIdentity(a, MemoryTargetType.PERSISTENT_AGENT, agentUuid);
        }
        return agentView(a);
    }

    /**
     * 把「本次新保存为常驻」的智能体与它的席位对上号。
     *
     * <p>只在「结束会话」链路里由前端调用，用于堵住续跑洞：用户可以在总结弹窗点取消，
     * 会话保留，而「哪个席位对应刚保存的哪个常驻对象」原本只活在前端 state 里，
     * 第二次点「结束会话」就丢了，那几个席位便永远总结不了。落回席位之后，
     * 「待总结目标」恒等于「所有挂着 uuid 的席位」。
     */
    @PutMapping("/sessions/{sid}/agents/{aid}/persistent-link")
    public Map<String, Object> linkPersistentSeat(@PathVariable String sid, @PathVariable String aid,
                                                  @RequestBody(required = false) PersistentLinkRequest req) {
        sessionService.requireAccess(sid);
        DomainAgentConfig seat = agentMemoryService.linkSeat(sid, aid, req == null ? null : req.getAgentUuid());
        return agentView(seat);
    }

    public static class PersistentLinkRequest {
        private String agentUuid;

        public String getAgentUuid() {
            return agentUuid;
        }

        public void setAgentUuid(String agentUuid) {
            this.agentUuid = agentUuid;
        }
    }

    /**
     * 让某个席位回顾本场讨论，重写它自己的经验心得（阻塞式，一席位一次请求）。
     *
     * <p>空 body：目标（是常驻智能体还是导师、uuid 是多少）由后端从席位推导，
     * 前端传不了也传不错。前端据此做「每 agent 一格」的真实进度条。
     */
    @PostMapping("/sessions/{sid}/agents/{aid}/memory/summarize")
    public Map<String, Object> summarizeMemory(@PathVariable String sid, @PathVariable String aid) {
        sessionService.requireAccess(sid);
        return agentMemoryService.summarize(sid, aid);
    }

    /**
     * 把这个席位本场读过、但还没入库的文件存进它的常驻知识库（阻塞式，一席位一次请求）。
     *
     * <p>挂在会话侧、空 body，与上面那条 {@code memory/summarize} 一比一同款：存给哪个常驻
     * 由后端从席位的 {@code agentUuid} 推导，前端传不了也传不错，进度条的目标集合与后端判定
     * 天然一致。前端据此做「每 agent 一格」的真实进度条。
     *
     * <p><b>幂等</b>：席位上的待保存清单取走即清空，「取消后重来」「失败重试」都只会存一次。
     * 清单本来就空时返回 {@code ok=true, docCount=0} —— 那是**成功**不是错误，否则取消一次
     * 再结束会在界面上标出一片红。席位不是常驻（没挂 uuid）时 {@code ok=false}，前端标灰。
     */
    @PostMapping("/sessions/{sid}/agents/{aid}/knowledge/save")
    public Map<String, Object> saveKnowledge(@PathVariable String sid, @PathVariable String aid) {
        sessionService.requireAccess(sid);
        return persistentAgentDocService.saveFromSeat(AuthContext.require(), sid, aid);
    }

    @DeleteMapping("/sessions/{sid}/agents/{aid}")
    public Map<String, Object> removeAgent(@PathVariable String sid, @PathVariable String aid) {
        sessionService.requireAccess(sid);
        sessionService.removeAgent(sid, aid);
        return Map.of("removed", true, "sessionId", sid, "agentId", aid);
    }

    /**
     * 把账号级导师加入本会话：注册一个导师席位，并把导师库里已有的论文页知识灌进本会话。
     * 不重跑 Vision、不需要重传文件，所以是秒级操作；同一导师加入多个会话互不影响。
     */
    @PostMapping("/sessions/{sid}/mentors")
    public Map<String, Object> joinMentor(@PathVariable String sid,
                                          @RequestBody(required = false) JoinMentorRequest req) {
        sessionService.requireAccess(sid);
        if (req == null || req.mentorId == null) {
            throw new IllegalArgumentException("mentorId 不能为空");
        }
        Map<String, Object> v = mentorService.joinSession(AuthContext.require(), req.mentorId, sid);
        // 导师的同场去重已经在 joinSession 里做过了，这里只补挂心得快照
        agentMemoryService.attachIdentity(
                sessionService.getSession(sid).getAgents().get((String) v.get("agentId")),
                MemoryTargetType.MENTOR, (String) v.get("mentorUuid"));
        return v;
    }

    public static class JoinMentorRequest {
        public Long mentorId;
    }

    @PostMapping(value = "/sessions/{sid}/agents/{aid}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadAgentDoc(@PathVariable String sid,
                                              @PathVariable String aid,
                                              @RequestPart("file") MultipartFile file) throws Exception {
        sessionService.requireAccess(sid);
        return sessionService.ingestAgentDocument(sid, aid, file.getBytes(), file.getOriginalFilename());
    }

    @GetMapping("/sessions/{sid}/agents/{aid}/knowledge/status")
    public Map<String, Object> knowledgeStatus(@PathVariable String sid, @PathVariable String aid) {
        sessionService.requireAccess(sid);
        return sessionService.knowledgeStatus(sid, aid);
    }

    @PostMapping(value = "/sessions/{sid}/user-document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadUserDoc(@PathVariable String sid,
                                             @RequestPart("file") MultipartFile file) throws Exception {
        sessionService.requireAccess(sid);
        return sessionService.uploadUserDocument(sid, file.getBytes(), file.getOriginalFilename());
    }

    @GetMapping("/sessions/{sid}/user-document/status")
    public Map<String, Object> userDocStatus(@PathVariable String sid) {
        sessionService.requireAccess(sid);
        return sessionService.userDocumentStatus(sid);
    }

    @PostMapping("/sessions/{sid}/questions")
    public Map<String, Object> ask(@PathVariable String sid, @Valid @RequestBody AskQuestionRequest req) {
        sessionService.requireAccess(sid);
        Question q = discussionPipeline.submitQuestion(sid, req.getContent());
        return q.progressView();
    }

    @GetMapping("/sessions/{sid}/questions/{qid}")
    public Map<String, Object> questionStatus(@PathVariable String sid, @PathVariable String qid,
                                              HttpServletResponse response) {
        sessionService.requireAccess(sid);
        Question q = repo.requireQuestion(sid, qid);
        // 把累计 token 用量写到响应头，方便集成方无需解析 body 即可看到
        response.setHeader("X-Token-Input", String.valueOf(q.getTokenUsage().getInputTokens()));
        response.setHeader("X-Token-Output", String.valueOf(q.getTokenUsage().getOutputTokens()));
        response.setHeader("X-Token-Total", String.valueOf(q.getTokenUsage().getTotalTokens()));
        response.setHeader("X-Token-Cost", String.valueOf(q.getTokenUsage().getCostEstimate()));
        Map<String, Object> m = new LinkedHashMap<>(q.progressView());
        m.put("finalAnswer", q.getFinalAnswer());
        m.put("explanation", q.getExplanation());
        return m;
    }

    @PostMapping("/sessions/{sid}/questions/{qid}/discuss")
    public Map<String, Object> discuss(@PathVariable String sid, @PathVariable String qid) {
        sessionService.requireAccess(sid);
        Question q = discussionPipeline.startDiscussion(sid, qid);
        return q.progressView();
    }

    @PostMapping("/sessions/{sid}/questions/{qid}/interjections")
    public Map<String, Object> interject(@PathVariable String sid, @PathVariable String qid,
                                         @RequestBody(required = false) InterjectionRequest req) {
        sessionService.requireAccess(sid);
        InterjectionRequest request = req == null ? new InterjectionRequest() : req;
        return discussionPipeline.addInterjection(sid, qid, request.content, request.targetAgentId).progressView();
    }

    @PostMapping("/sessions/{sid}/questions/{qid}/continue")
    public Map<String, Object> continueDiscussion(@PathVariable String sid, @PathVariable String qid) {
        sessionService.requireAccess(sid);
        return discussionPipeline.continueDiscussion(sid, qid).progressView();
    }

    @PostMapping("/sessions/{sid}/questions/{qid}/summary")
    public Map<String, Object> generateSummary(@PathVariable String sid,
                                               @PathVariable String qid,
                                               @RequestBody(required = false) GenerateSummaryRequest req) {
        sessionService.requireAccess(sid);
        String scope = req != null ? req.getScope() : null;
        String targetQid = req != null && StringUtils.hasText(req.getQuestionId()) ? req.getQuestionId() : qid;
        return summaryService.generate(sid, scope, targetQid);
    }

    @GetMapping("/sessions/{sid}/questions/{qid}/transcript")
    public Map<String, Object> transcript(@PathVariable String sid, @PathVariable String qid,
                                          HttpServletResponse response) {
        sessionService.requireAccess(sid);
        Question q = repo.requireQuestion(sid, qid);
        response.setHeader("X-Token-Total", String.valueOf(q.getTokenUsage().getTotalTokens()));
        response.setHeader("X-Token-Cost", String.valueOf(q.getTokenUsage().getCostEstimate()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("questionId", q.getId());
        m.put("status", q.getStatus().name());
        m.put("content", q.getContent());
        m.put("thoughts", q.getThoughts());
        m.put("skippedAgents", q.getSkippedAgents());
        m.put("transcript", q.getTranscript());
        m.put("professionalConsensus", q.getProfessionalConsensus());
        m.put("finalAnswer", q.getFinalAnswer());
        m.put("explanation", q.getExplanation());
        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("input", q.getTokenUsage().getInputTokens());
        tokens.put("output", q.getTokenUsage().getOutputTokens());
        tokens.put("total", q.getTokenUsage().getTotalTokens());
        tokens.put("cost", q.getTokenUsage().getCostEstimate());
        m.put("tokenUsage", tokens);
        return m;
    }

    @GetMapping("/sessions/{sid}/questions/{qid}/revision-plan")
    public Map<String, Object> revisionPlan(@PathVariable String sid, @PathVariable String qid) {
        sessionService.requireAccess(sid);
        Question q = repo.requireQuestion(sid, qid);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", q.getStatus().name());
        m.put("revisionPlan", q.getRevisionPlan());
        return m;
    }

    @PostMapping("/sessions/{sid}/questions/{qid}/revision-confirm")
    public Map<String, Object> revisionConfirm(@PathVariable String sid,
                                               @PathVariable String qid,
                                               @RequestBody RevisionConfirmRequest req) {
        sessionService.requireAccess(sid);
        boolean auto = req.getAutoRevise() == null || req.getAutoRevise();
        Question q = discussionPipeline.confirmRevision(
                sid, qid, req.getAcceptedItemIds(), req.getNote(), auto);
        Map<String, Object> m = new LinkedHashMap<>(q.progressView());
        m.put("confirmedItemIds", q.getConfirmedItemIds());
        if (q.getRevisedDocument() != null) {
            m.put("revisedDocument", q.getRevisedDocument());
        }
        return m;
    }

    @PostMapping("/sessions/{sid}/questions/{qid}/revise")
    public Map<String, Object> revise(@PathVariable String sid, @PathVariable String qid) {
        sessionService.requireAccess(sid);
        Question q = discussionPipeline.revise(sid, qid);
        Map<String, Object> m = new LinkedHashMap<>(q.progressView());
        m.put("revisedDocument", q.getRevisedDocument());
        return m;
    }

    /**
     * 「继续改稿」：在当前改写结果上，按用户临时提的要求再改一轮。
     * 与 {@code /revise} 的区别是不需要已确认的修改项 —— 用户的要求直接进 prompt。
     */
    @PostMapping("/sessions/{sid}/questions/{qid}/continue-revision")
    public Map<String, Object> continueRevision(@PathVariable String sid,
                                                @PathVariable String qid,
                                                @RequestBody(required = false) ContinueRevisionRequest req) {
        sessionService.requireAccess(sid);
        ContinueRevisionRequest r = req == null ? new ContinueRevisionRequest() : req;
        Question q = discussionPipeline.continueRevision(sid, qid, r.getRequirement());
        Map<String, Object> m = new LinkedHashMap<>(q.progressView());
        m.put("revisedDocument", q.getRevisedDocument());
        return m;
    }

    /** 请求体沿用项目惯例：私有字段 + getter/setter（见 UpdateLlmEndpointRequest） */
    public static class ContinueRevisionRequest {
        private String requirement;

        public String getRequirement() {
            return requirement;
        }

        public void setRequirement(String requirement) {
            this.requirement = requirement;
        }
    }

    @GetMapping("/sessions/{sid}/questions/{qid}/revised-document")
    public Map<String, Object> revisedDocument(@PathVariable String sid, @PathVariable String qid) {
        sessionService.requireAccess(sid);
        Question q = repo.requireQuestion(sid, qid);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", q.getStatus().name());
        m.put("revisedDocument", q.getRevisedDocument());
        return m;
    }

    // ── 会话列表摘要（GET /api/sessions） ──────────────────────

    /**
     * 一条「对话」列表项。状态取**最后一个议题**的状态与 stageHint ——
     * 侧栏要回答的是「这个对话现在在干什么」，而最新议题就是当前进展。
     */
    private Map<String, Object> sessionSummary(Session s) {
        List<String> qids = s.getQuestionIds();
        Question first = qids.isEmpty() ? null : repo.findQuestion(qids.get(0)).orElse(null);
        Question latest = qids.isEmpty() ? null : repo.findQuestion(qids.get(qids.size() - 1)).orElse(null);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", s.getId());
        m.put("title", summaryTitle(s, first));
        m.put("createdAt", s.getCreatedAt().toString());
        m.put("updatedAt", updatedAtOf(s, latest));
        m.put("questionCount", qids.size());
        m.put("agentCount", s.getAgents().size());
        m.put("latestQuestionId", latest == null ? null : latest.getId());
        m.put("status", latest == null ? null : latest.getStatus().name());
        m.put("stageHint", latest == null ? null : latest.getStageHint());
        m.put("activeCount", countActiveQuestions(s));
        m.put("docEditMode", s.isDocEditMode());
        return m;
    }

    /** 列表标题：首个议题的前 24 字；还没提问就用占位名 */
    private String summaryTitle(Session s, Question first) {
        if (first == null || !StringUtils.hasText(first.getContent())) {
            return s.getAgents().isEmpty() ? "新对话" : "新对话 · " + s.getAgents().size() + " 个席位";
        }
        String t = first.getContent().replaceAll("\\s+", " ").trim();
        return t.length() > 24 ? t.substring(0, 24) + "…" : t;
    }

    private String updatedAtOf(Session s, Question latest) {
        if (latest == null || latest.getUpdatedAt() == null) {
            return s.getCreatedAt().toString();
        }
        return latest.getUpdatedAt().toString();
    }

    private int countActiveQuestions(Session s) {
        int n = 0;
        for (String qid : s.getQuestionIds()) {
            Question q = repo.findQuestion(qid).orElse(null);
            if (q != null && !isTerminal(q.getStatus())) {
                n++;
            }
        }
        return n;
    }

    /** 与前端 {@code lib/labels.ts} 的 isQuestionActive 口径保持一致 */
    private static boolean isTerminal(QuestionStatus st) {
        return st == QuestionStatus.COMPLETED
                || st == QuestionStatus.REVISED_COMPLETED
                || st == QuestionStatus.FAILED;
    }

    private Map<String, Object> sessionView(Session s) {        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", s.getId());
        m.put("userStyle", s.getUserStyle());
        m.put("autoDiscuss", s.isAutoDiscuss());
        m.put("allowInterjections", s.isAllowInterjections());
        m.put("docEditMode", s.isDocEditMode());
        m.put("historyLimit", s.getHistoryLimit());
        m.put("maxRounds", s.getMaxRounds());
        m.put("createdAt", s.getCreatedAt().toString());
        m.put("agents", s.getAgents().values().stream().map(this::agentView).collect(Collectors.toList()));
        m.put("questionIds", s.getQuestionIds());
        m.put("llm", llmView(s.getLlmEndpoint()));
        if (s.isDocEditMode()) {
            m.put("userDocument", sessionService.userDocumentView(s));
        }
        return m;
    }

    public static class InterjectionSettingRequest {
        public boolean allowInterjections;
    }

    public static class InterjectionRequest {
        public String content;
        public String targetAgentId;
    }

    private Map<String, Object> llmView(LlmEndpoint ep) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (ep == null) {
            m.put("configured", false);
            return m;
        }
        m.put("configured", ep.hasCredentials());
        m.put("apiKey", ep.maskedApiKey());
        m.put("baseUrl", ep.getBaseUrl());
        m.put("model", ep.getModel());
        m.put("label", ep.getLabel());
        return m;
    }

    private Map<String, Object> agentView(DomainAgentConfig a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agentId", a.getId());
        m.put("name", a.getName());
        m.put("model", a.getModel());
        m.put("baseUrl", a.getBaseUrl());
        m.put("apiKey", a.maskedApiKey());
        m.put("persona", a.getPersona());
        m.put("personality", a.getPersonality());
        m.put("knowledgeStatus", a.getKnowledgeStatus().name());
        m.put("statusMessage", a.getStatusMessage());
        m.put("documentFileName", a.getDocumentFileName());
        // 导师标记：mentor 对所有席位都给（便于前端统一判断），其余只在导师席位上给
        m.put("mentor", a.isMentor());
        if (a.isMentor()) {
            m.put("mentorUuid", a.getMentorUuid());
            m.put("mentorDiscipline", a.getMentorDiscipline());
            m.put("mentorPaperCount", a.getMentorPaperCount());
        }
        // 常驻身份 + 心得摘要。普通注册的临时席位没有 agentUuid，键不出现（与归档快照同口径）。
        if (a.getAgentUuid() != null) {
            m.put("agentUuid", a.getAgentUuid());
        }
        // 本场有文件等着存进常驻知识库。**只在为真时写键**，普通席位的视图逐字节不变
        // （与上面 agentUuid 同口径）。只透出「有」而**不透出 pages** ——
        // 那是整份页知识，不该随每次会话查询回传。
        if (a.hasPendingDocs()) {
            m.put("hasNewUpload", true);
        }
        m.put("hasMemory", a.hasMemory());
        if (a.getAgentMemoryUpdatedAt() != null) {
            m.put("memoryUpdatedAt", a.getAgentMemoryUpdatedAt().toEpochMilli());
        }
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("current", a.getVisionProgressCurrent());
        progress.put("total", a.getVisionProgressTotal());
        progress.put("ratio", a.visionProgressRatio());
        m.put("progress", progress);
        return m;
    }
}
