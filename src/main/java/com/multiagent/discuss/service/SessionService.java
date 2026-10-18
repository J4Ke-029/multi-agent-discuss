package com.multiagent.discuss.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiagent.discuss.agent.AgentFactory;
import com.multiagent.discuss.config.AppProperties;
import com.multiagent.discuss.domain.DomainAgentConfig;
import com.multiagent.discuss.domain.KnowledgeStatus;
import com.multiagent.discuss.domain.LlmEndpoint;
import com.multiagent.discuss.domain.PageBlock;
import com.multiagent.discuss.domain.Session;
import com.multiagent.discuss.domain.StyleProfile;
import com.multiagent.discuss.domain.UserDocument;
import com.multiagent.discuss.ingest.DocumentIngestService;
import com.multiagent.discuss.ingest.DocumentIngestService.PageImage;
import com.multiagent.discuss.knowledge.AgentKnowledgeStore;
import com.multiagent.discuss.knowledge.InMemorySessionRepository;
import com.multiagent.discuss.orchestrator.DiscussionPipeline;
import com.multiagent.discuss.vision.VisionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final InMemorySessionRepository repo;
    private final AgentKnowledgeStore knowledgeStore;
    private final AppProperties props;
    private final DocumentIngestService ingestService;
    private final VisionService visionService;
    private final AgentFactory agentFactory;
    private final ObjectMapper objectMapper;

    public SessionService(InMemorySessionRepository repo,
                          AgentKnowledgeStore knowledgeStore,
                          AppProperties props,
                          DocumentIngestService ingestService,
                          VisionService visionService,
                          AgentFactory agentFactory,
                          ObjectMapper objectMapper) {
        this.repo = repo;
        this.knowledgeStore = knowledgeStore;
        this.props = props;
        this.ingestService = ingestService;
        this.visionService = visionService;
        this.agentFactory = agentFactory;
        this.objectMapper = objectMapper;
    }

    public Session createSession(String userStyle, Boolean autoDiscuss, Boolean docEditMode,
                                 Integer historyLimit, Integer maxRounds) {
        return createSession(userStyle, autoDiscuss, docEditMode, historyLimit, maxRounds,
                null, null, null, null);
    }

    public Session createSession(String userStyle, Boolean autoDiscuss, Boolean docEditMode,
                                 Integer historyLimit, Integer maxRounds,
                                 String llmApiKey, String llmBaseUrl, String llmModel, String llmLabel) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        boolean auto = autoDiscuss != null ? autoDiscuss : props.getDiscuss().isDefaultAutoDiscuss();
        boolean edit = docEditMode != null && docEditMode;
        int hist = historyLimit != null ? historyLimit : props.getSession().getHistoryLimit();
        int rounds = maxRounds != null ? maxRounds : props.getDiscuss().getDefaultMaxRounds();
        Session session = new Session(id, userStyle, auto, edit, hist, rounds);
        session.setOwnerUserId(com.multiagent.discuss.security.AuthContext.currentUserIdOrNull());
        if (session.getOwnerUserId() == null) {
            // 匿名会话：记下创建它的浏览器，之后只有同一个 clientId 能看见 / 访问
            session.setAnonymousClientId(com.multiagent.discuss.security.AuthContext.currentClientIdOrNull());
        }
        applyLlmEndpoint(session, llmApiKey, llmBaseUrl, llmModel, llmLabel, false);
        repo.saveSession(session);
        ensureWorkDir(id);
        return session;
    }

    /**
     * 更新会话默认网关。replaceKey=false 时空 key 表示保留原 key。
     */
    public Session updateLlmEndpoint(String sessionId, String llmApiKey, String llmBaseUrl,
                                     String llmModel, String llmLabel, boolean replaceKey) {
        Session session = repo.requireSession(sessionId);
        applyLlmEndpoint(session, llmApiKey, llmBaseUrl, llmModel, llmLabel, replaceKey);
        agentFactory.evictSession(sessionId);
        return session;
    }

    private void applyLlmEndpoint(Session session, String llmApiKey, String llmBaseUrl,
                                  String llmModel, String llmLabel, boolean keepExistingKeyIfBlank) {
        LlmEndpoint ep = session.getLlmEndpoint();
        if (ep == null) {
            ep = new LlmEndpoint();
            session.setLlmEndpoint(ep);
        }
        if (StringUtils.hasText(llmApiKey)) {
            ep.setApiKey(llmApiKey.trim());
        } else if (!keepExistingKeyIfBlank) {
            // 创建时允许不带 key（回落全局配置）
            if (llmApiKey != null && llmApiKey.isBlank()) {
                ep.setApiKey(null);
            }
        }
        if (llmBaseUrl != null) {
            ep.setBaseUrl(StringUtils.hasText(llmBaseUrl) ? llmBaseUrl.trim() : null);
        }
        if (llmModel != null) {
            ep.setModel(StringUtils.hasText(llmModel) ? llmModel.trim() : null);
        }
        if (llmLabel != null) {
            ep.setLabel(StringUtils.hasText(llmLabel) ? llmLabel.trim() : null);
        }
        // 若完全没有有效字段，清掉对象以免误导
        if (!ep.hasCredentials()
                && !StringUtils.hasText(ep.getBaseUrl())
                && !StringUtils.hasText(ep.getModel())
                && !StringUtils.hasText(ep.getLabel())) {
            session.setLlmEndpoint(null);
        }
    }

    public Session getSession(String sessionId) {
        return repo.requireSession(sessionId);
    }

    /**
     * 改会话配置：文风 / 最高讨论轮次。传 null 表示不动该项。
     *
     * <p>这两项都是「后续才读」的参数——文风在生成终稿时用，最高轮次是讨论轮数的上界——
     * 所以改它们不会动已经产生的发言与终稿，对正在跑的讨论也只是换了个上界。
     */
    public Session updateSettings(String sessionId, String userStyle, Integer maxRounds) {
        Session session = repo.requireSession(sessionId);
        if (userStyle != null) {
            session.setUserStyle(StringUtils.hasText(userStyle) ? userStyle.trim() : null);
        }
        if (maxRounds != null) {
            if (maxRounds < 1 || maxRounds > 20) {
                throw new IllegalArgumentException("最高讨论轮次需在 1 ~ 20 之间");
            }
            session.setMaxRounds(maxRounds);
        }
        return session;
    }

    /**
     * 会话归属访问控制：登录用户只能访问自己创建的会话；匿名只能访问匿名会话，
     * 且会话创建时带了 {@code X-Client-Id} 的，还要求客户端一致。
     * 不匹配一律按「不存在」抛 {@link AuthException}（401），避免泄露会话存在性。
     */
    public void requireAccess(String sessionId) {
        Session s = repo.requireSession(sessionId);
        Long current = com.multiagent.discuss.security.AuthContext.currentUserIdOrNull();
        if (s.getOwnerUserId() == null) {
            // 匿名会话：仅匿名（无 token）可访问；登录用户不得访问他人匿名会话
            if (current != null) {
                throw new com.multiagent.discuss.security.AuthException("会话不存在或无权限");
            }
            // 再按浏览器实例标识收窄，口径与 listVisibleSessions 共用 anonymousVisibleTo
            if (!anonymousVisibleTo(s, com.multiagent.discuss.security.AuthContext.currentClientIdOrNull())) {
                throw new com.multiagent.discuss.security.AuthException("会话不存在或无权限");
            }
            return;
        }
        if (current == null || !s.getOwnerUserId().equals(current)) {
            throw new com.multiagent.discuss.security.AuthException("会话不存在或无权限");
        }
    }

    /**
     * 席位当前生效的网关三件套。「保存为常驻」时原样拷进账号级对象。
     *
     * <p>它之所以是「当前生效的」而不是「注册时填的」：{@link #registerAgent} 在注册那一刻就把
     * 会话网关解析进了席位，讨论与总结全程用的都是这三个值。所以保存 = 快照，不需要二次解析。
     *
     * <p>副作用需知悉：席位若走的是会话网关，拷下来的 Key 就是**会话 Key 的一份副本**，
     * 之后轮换会话 Key 不会同步过去。
     */
    public record SeatGateway(String apiKey, String baseUrl, String model) {}

    /**
     * 取某个席位当前生效的网关，供「保存为常驻」使用。
     *
     * <p>三种情况返回 {@code empty} 让调用方降级为「只存名片」：
     * 会话已不在内存（进程重启过 / 刚被归档销毁）、席位不存在（弹窗开着时被移除）、席位没有 Key
     * （理论上不会发生，注册路径已经保证有）。<b>刻意不抛异常</b> —— 保存常驻是批量循环，
     * 一个席位缺失不该把这一轮已成功的其它智能体一起回滚。
     *
     * <p>但<b>越权必须照常抛 401</b>，不能静默降级成一个空网关。所以顺序是「先看会话在不在、
     * 再做归属校验」：用「在不在」分流，而不是去解析异常 message。
     */
    public java.util.Optional<SeatGateway> seatGateway(String sessionId, String agentId) {
        Session s = repo.findSession(sessionId).orElse(null);
        if (s == null) {
            return java.util.Optional.empty();
        }
        requireAccess(sessionId);
        DomainAgentConfig seat = s.getAgents().get(agentId);
        if (seat == null || !StringUtils.hasText(seat.getApiKey())) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new SeatGateway(
                seat.getApiKey().trim(), seat.getBaseUrl(), seat.getModel()));
    }

    /**
     * 匿名会话对某个匿名客户端的可见性。
     *
     * <p>会话创建时记了 {@code clientId} 就要求一致（内网里不同人的匿名会话互相隔离）；
     * 没记（客户端不带 {@code X-Client-Id}，或本次改动之前创建的老会话）时保持可见，
     * 避免升级瞬间把已有会话全部锁死。
     */
    private static boolean anonymousVisibleTo(Session s, String clientId) {
        if (s.getOwnerUserId() != null) {
            return false;
        }
        String owner = s.getAnonymousClientId();
        return owner == null || owner.equals(clientId);
    }

    /**
     * 当前调用者可见的会话列表，按创建时间倒序。
     *
     * <p>归属口径与 {@link #requireAccess} 完全一致，且刻意只写在这一处：
     * 登录用户只看自己创建的；匿名调用只看属于本浏览器的匿名会话。
     */
    public List<Session> listVisibleSessions() {
        Long current = com.multiagent.discuss.security.AuthContext.currentUserIdOrNull();
        String clientId = com.multiagent.discuss.security.AuthContext.currentClientIdOrNull();
        return repo.findAll().stream()
                .filter(s -> current == null
                        ? anonymousVisibleTo(s, clientId)
                        : current.equals(s.getOwnerUserId()))
                .sorted(java.util.Comparator.comparing(Session::getCreatedAt).reversed())
                .toList();
    }

    public DomainAgentConfig registerAgent(String sessionId, String name, String apiKey,
                                           String baseUrl, String model, String persona) {
        return registerAgent(sessionId, name, apiKey, baseUrl, model, persona, null, null);
    }

    public DomainAgentConfig registerAgent(String sessionId, String name, String apiKey,
                                           String baseUrl, String model, String persona,
                                           Boolean useSessionLlm) {
        return registerAgent(sessionId, name, apiKey, baseUrl, model, persona, useSessionLlm, null);
    }

    public DomainAgentConfig registerAgent(String sessionId, String name, String apiKey,
                                           String baseUrl, String model, String persona,
                                           Boolean useSessionLlm, String personality) {
        Session session = repo.requireSession(sessionId);
        LlmEndpoint ep = session.getLlmEndpoint();
        boolean inherit = Boolean.TRUE.equals(useSessionLlm)
                || !StringUtils.hasText(apiKey);

        String resolvedKey;
        String resolvedUrl;
        String resolvedModel;

        if (inherit) {
            if (ep == null || !ep.hasCredentials()) {
                throw new IllegalArgumentException(
                        "未提供 apiKey，且会话未配置默认网关。请在创建会话时填写 LLM 配置，或在此填写 apiKey。");
            }
            resolvedKey = ep.getApiKey();
            resolvedUrl = StringUtils.hasText(baseUrl) ? baseUrl
                    : (StringUtils.hasText(ep.getBaseUrl()) ? ep.getBaseUrl() : props.getVision().getBaseUrl());
            resolvedModel = StringUtils.hasText(model) ? model
                    : (StringUtils.hasText(ep.getModel()) ? ep.getModel() : "gpt-4o-mini");
        } else {
            resolvedKey = apiKey;
            resolvedUrl = StringUtils.hasText(baseUrl)
                    ? baseUrl
                    : (ep != null && StringUtils.hasText(ep.getBaseUrl())
                    ? ep.getBaseUrl()
                    : props.getVision().getBaseUrl());
            resolvedModel = StringUtils.hasText(model)
                    ? model
                    : (ep != null && StringUtils.hasText(ep.getModel()) ? ep.getModel() : "gpt-4o-mini");
        }

        String agentId = "agent-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        DomainAgentConfig agent = new DomainAgentConfig(
                agentId, sessionId, name, resolvedKey, resolvedUrl, resolvedModel, persona);
        agent.setPersonality(StringUtils.hasText(personality) ? personality.trim() : null);
        agent.setKnowledgeStatus(KnowledgeStatus.AGENT_CREATED);
        agent.setStatusMessage(inherit ? "已创建（使用会话默认网关），等待上传文献" : "已创建，等待上传文献");
        session.getAgents().put(agentId, agent);
        return agent;
    }

    /**
     * 注册一个「导师模式」席位（供 {@link MentorService#joinSession} 调用）。
     *
     * <p>导师沿用普通席位的全部机制，只是多挂几个标识（见 {@code DomainAgentConfig} 的 mentor 字段）。
     * 网关二选一：导师自己配了 Key 就用它，否则继承会话网关；两者都没有时由
     * {@link #registerAgent} 抛出既有的「未提供 apiKey，且会话未配置默认网关」提示。
     *
     * <p>刻意不走 {@code registerAgent} 的新重载而是单独一个方法：现有三个重载被控制器与前端
     * 直接依赖，动它们会牵动普通注册路径，而本方法对普通路径零影响。
     * 常驻智能体侧同构，见 {@link #registerPersistentAgent}。
     */
    public DomainAgentConfig registerMentorAgent(String sessionId, String mentorName, String persona,
                                                 String personality, String model, String baseUrl,
                                                 String apiKey) {
        boolean ownGateway = useOwnGateway(apiKey, null);
        DomainAgentConfig agent = registerAgent(
                sessionId,
                "导师指导 · " + mentorName,
                ownGateway ? apiKey : null,
                ownGateway ? baseUrl : null,
                ownGateway ? model : null,
                persona,
                !ownGateway,
                personality);
        agent.setStatusMessage(ownGateway ? "导师席位已就绪（使用导师自己的网关）" : "导师席位已就绪（使用会话网关）");
        return agent;
    }

    /**
     * 注册一个「常驻智能体」席位（供 {@code PersistentAgentService.joinSession} 调用）。
     *
     * <p>与 {@link #registerMentorAgent} 同构，区别只在文案；同样刻意不走 {@code registerAgent}
     * 的新重载，普通注册路径零影响。
     *
     * <p>网关二选一：{@code apiKey} 非空且没显式要求用会话网关时用它自己的，否则继承会话网关；
     * 两者都没有时由 {@link #registerAgent} 抛出既有的「未提供 apiKey，且会话未配置默认网关」提示
     * —— 那句话一个字都不用改，历史行（没存网关的常驻）行为与改动前完全一致。
     */
    public DomainAgentConfig registerPersistentAgent(String sessionId, String name, String persona,
                                                     String personality, String model, String baseUrl,
                                                     String apiKey, Boolean useSessionLlm) {
        boolean ownGateway = useOwnGateway(apiKey, useSessionLlm);
        DomainAgentConfig agent = registerAgent(
                sessionId,
                name,
                ownGateway ? apiKey : null,
                ownGateway ? baseUrl : null,
                ownGateway ? model : null,
                persona,
                !ownGateway,
                personality);
        agent.setStatusMessage(ownGateway
                ? "已加入本场（使用常驻智能体自带的网关）"
                : "已加入本场（使用会话网关）");
        return agent;
    }

    /**
     * 「用不用它自己的网关」的唯一判定点。
     *
     * <p>抽成静态纯函数是为了能被单测直接钉死 —— 这条判定同时决定普通注册、
     * 导师加入、常驻加入三条路径的网关语义，是最不能漂的一行。
     * {@code useSessionLlm} 为 {@code true} 时显式覆盖（用本场）；{@code null} / {@code false} 时
     * 只要自己有 Key 就用自己。
     */
    static boolean useOwnGateway(String apiKey, Boolean useSessionLlm) {
        return StringUtils.hasText(apiKey) && !Boolean.TRUE.equals(useSessionLlm);
    }

    public Map<String, Object> ingestAgentDocument(String sessionId, String agentId,
                                                   byte[] data, String fileName) {
        Session session = repo.requireSession(sessionId);
        DomainAgentConfig agent = session.getAgents().get(agentId);
        if (agent == null) {
            throw new IllegalArgumentException("智能体不存在: " + agentId);
        }
        agent.setKnowledgeStatus(KnowledgeStatus.DOCUMENT_UPLOADING);
        agent.setDocumentFileName(fileName);
        agent.setStatusMessage("文档上传中");
        agent.setVisionProgressCurrent(0);
        agent.setVisionProgressTotal(0);

        Path workDir = workDir(sessionId).resolve(agentId);
        try {
            List<PageImage> pages = ingestService.ingestToPages(data, fileName, workDir);
            agent.setKnowledgeStatus(KnowledgeStatus.VISION_PROCESSING);
            agent.setVisionProgressTotal(pages.size());
            agent.setVisionProgressCurrent(0);
            agent.setStatusMessage("Vision 分析中，共 " + pages.size() + " 页");

            com.multiagent.discuss.domain.TokenUsage sink = new com.multiagent.discuss.domain.TokenUsage();
            List<PageBlock> blocks = visionService.analyzePages(pages, (done) -> {
                agent.setVisionProgressCurrent(done);
                agent.setStatusMessage("Vision 分析中 " + done + " / " + pages.size() + " 页");
            }, sink);
            log.info("Vision 用量: in={} out={} cost={}", sink.getInputTokens(), sink.getOutputTokens(), sink.getCostEstimate());
            // 页头带上文件名。对普通席位是**惰性**的：它们走 asContextSnippet()，不读 sourceName，
            // 行为逐字不变。带 uuid 的常驻席位走 asContextSnippetWithSource()，靠它才能把结论
            // 引用到具体来源上 —— 否则模型手里这段没有出处，而 prompt 又要求「必须给真实的页/表/图/DOI」，
            // 等于逼它编一个文件名出来。
            for (PageBlock b : blocks) {
                b.setSourceName(fileName);
            }
            knowledgeStore.putPages(sessionId, agentId, blocks);

            boolean anyUncertaintyOnly = blocks.stream()
                    .allMatch(b -> b.getUnderstanding() != null
                            && (b.getUnderstanding().getNarrative() == null
                            || b.getUnderstanding().getNarrative().isBlank()));
            if (anyUncertaintyOnly && blocks.isEmpty()) {
                agent.setKnowledgeStatus(KnowledgeStatus.VISION_FAILED);
                agent.setStatusMessage("无有效页");
            } else {
                agent.setKnowledgeStatus(KnowledgeStatus.KNOWLEDGE_READY);
                agent.setStatusMessage("知识就绪，页数=" + blocks.size());
                // 记入待保存清单 —— 只有**完整成功**才记，失败路径绝不追加。
                // 结束会话时若这个席位已是常驻智能体，这份页知识会被落库进它的知识库
                // （见 PersistentAgentDocService.saveFromSeat）。
                agent.addPendingDoc(fileName, blocks);
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("agentId", agentId);
            resp.put("status", agent.getKnowledgeStatus().name());
            resp.put("message", agent.getStatusMessage());
            resp.put("pageCount", blocks.size());
            return resp;
        } catch (Exception e) {
            agent.setKnowledgeStatus(KnowledgeStatus.VISION_FAILED);
            agent.setStatusMessage(e.getMessage());
            // 顺手修一处既有 bug：失败时不清文件名、不重置进度，左栏卡片会一直显示
            // 「材料.pdf · 进度 7 / 12」，那个进度属于**这次失败的尝试**，会让用户以为还在跑。
            // 清掉文件名也是必要的：不清的话「这个席位传过文件」会一直成立，
            // 而 store 里留着的其实是上一次成功的页 —— 正是待保存清单要避免的那种错位。
            agent.setDocumentFileName(null);
            agent.setVisionProgressCurrent(0);
            agent.setVisionProgressTotal(0);
            log.error("Ingest failed session={} agent={}", sessionId, agentId, e);
            throw new IllegalStateException("文献处理失败: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> knowledgeStatus(String sessionId, String agentId) {
        Session session = repo.requireSession(sessionId);
        DomainAgentConfig agent = session.getAgents().get(agentId);
        if (agent == null) {
            throw new IllegalArgumentException("智能体不存在: " + agentId);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agentId", agentId);
        m.put("name", agent.getName());
        m.put("status", agent.getKnowledgeStatus().name());
        m.put("message", agent.getStatusMessage());
        m.put("documentFileName", agent.getDocumentFileName());
        m.put("pageCount", knowledgeStore.getPages(sessionId, agentId).size());
        // 进度
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("current", agent.getVisionProgressCurrent());
        progress.put("total", agent.getVisionProgressTotal());
        progress.put("ratio", agent.visionProgressRatio());
        m.put("progress", progress);
        return m;
    }

    public Map<String, Object> uploadUserDocument(String sessionId, byte[] data, String fileName) {
        Session session = repo.requireSession(sessionId);
        if (!session.isDocEditMode()) {
            throw new IllegalStateException("当前会话未开启 docEditMode，无法上传用户文档");
        }
        UserDocument doc = new UserDocument();
        doc.setFileName(fileName);
        doc.setStatus(KnowledgeStatus.DOCUMENT_UPLOADING);
        doc.setStatusMessage("用户文档上传中…");
        doc.setStageHint("正在接收用户文档…");
        doc.setProgressCurrent(0);
        doc.setProgressTotal(0);
        session.setUserDocument(doc);

        Path workDir = workDir(sessionId).resolve(AgentKnowledgeStore.USER_DOC_AGENT_ID);
        try {
            String plain = ingestService.extractPlainText(data, fileName);
            doc.setOriginalText(plain);

            List<PageImage> pages = ingestService.ingestToPages(data, fileName, workDir);
            doc.setStatus(KnowledgeStatus.VISION_PROCESSING);
            doc.setStageHint("正在逐页阅读你的文档…");
            doc.setProgressTotal(pages.size());
            doc.setProgressCurrent(0);
            doc.setStatusMessage("Vision 分析中，共 " + pages.size() + " 页");

            com.multiagent.discuss.domain.TokenUsage visionSink = new com.multiagent.discuss.domain.TokenUsage();
            List<PageBlock> blocks = visionService.analyzePages(pages, (done) -> {
                doc.setProgressCurrent(done);
                doc.setStatusMessage("Vision 分析中 " + done + " / " + pages.size() + " 页");
            }, visionSink);
            knowledgeStore.putPages(sessionId, AgentKnowledgeStore.USER_DOC_AGENT_ID, blocks);
            doc.getPages().clear();
            doc.getPages().addAll(blocks);

            // 文风预分析：Host 调一次，写入 STYLE_ANALYZING 中间态
            doc.setStatus(KnowledgeStatus.STYLE_ANALYZING);
            doc.setStageHint("正在提取作者文风（语气 / 人称 / 节奏 / 用词）…");
            doc.setStatusMessage("文风预分析中");
            StyleProfile profile = analyzeStyle(session, plain);
            doc.setStyleProfile(profile);

            doc.setStatus(KnowledgeStatus.KNOWLEDGE_READY);
            doc.setStageHint("用户文档就绪，等待专业共识生成修改建议");
            doc.setStatusMessage("用户文档就绪（文风已预分析，UserDoc 不发言）");

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", doc.getStatus().name());
            m.put("message", doc.getStatusMessage());
            m.put("stageHint", doc.getStageHint());
            m.put("pageCount", blocks.size());
            m.put("styleProfile", profile);
            Map<String, Object> progress = new LinkedHashMap<>();
            progress.put("current", doc.getProgressCurrent());
            progress.put("total", doc.getProgressTotal());
            progress.put("ratio", doc.progressRatio());
            m.put("progress", progress);
            return m;
        } catch (Exception e) {
            doc.setStatus(KnowledgeStatus.VISION_FAILED);
            doc.setStageHint("用户文档处理失败");
            doc.setStatusMessage(e.getMessage());
            log.error("UserDocument ingest failed session={}", sessionId, e);
            throw new IllegalStateException("用户文档处理失败: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> userDocumentStatus(String sessionId) {
        Session session = repo.requireSession(sessionId);
        if (!session.isDocEditMode()) {
            throw new IllegalStateException("当前会话未开启 docEditMode");
        }
        return userDocumentView(session);
    }

    /**
     * 把会话的 UserDocument 渲染为对外视图。没显 docEditMode 时也返回空态结构，
     * 方便 sessionView 一律透出。
     */
    public Map<String, Object> userDocumentView(Session session) {
        Map<String, Object> m = new LinkedHashMap<>();
        UserDocument doc = session.getUserDocument();
        if (doc == null) {
            m.put("status", KnowledgeStatus.AGENT_CREATED.name());
            m.put("statusMessage", "尚未上传用户文档");
            m.put("stageHint", "尚未上传用户文档");
            m.put("fileName", null);
            m.put("pageCount", 0);
            Map<String, Object> progress = new LinkedHashMap<>();
            progress.put("current", 0);
            progress.put("total", 0);
            progress.put("ratio", 0d);
            m.put("progress", progress);
            m.put("styleProfile", null);
            return m;
        }
        m.put("status", doc.getStatus().name());
        m.put("statusMessage", doc.getStatusMessage());
        m.put("stageHint", doc.getStageHint());
        m.put("fileName", doc.getFileName());
        m.put("pageCount", doc.getPages().size());
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("current", doc.getProgressCurrent());
        progress.put("total", doc.getProgressTotal());
        progress.put("ratio", doc.progressRatio());
        m.put("progress", progress);
        m.put("styleProfile", doc.getStyleProfile());
        return m;
    }

    public void deleteSession(String sessionId) {
        repo.requireSession(sessionId);
        knowledgeStore.clearSession(sessionId);
        agentFactory.evictSession(sessionId);
        repo.deleteSession(sessionId);
        deleteTree(workDir(sessionId));
    }

    /**
     * 从会话移除一个领域智能体：连带清掉它的私有知识 + 临时页图，
     * 并清掉该会话的 ChatClient 缓存（下次按新席位重建）。
     *
     * <p>只影响「本场席位」。历史议题的 transcript / thoughts 里已经写过这个
     * 智能体的发言，属于已发生的记录，不回改。
     */
    public void removeAgent(String sessionId, String agentId) {
        Session session = repo.requireSession(sessionId);
        DomainAgentConfig removed = session.getAgents().remove(agentId);
        if (removed == null) {
            throw new IllegalArgumentException("智能体不存在: " + agentId);
        }
        knowledgeStore.clearAgent(sessionId, agentId);
        agentFactory.evictSession(sessionId);
        deleteTree(workDir(sessionId).resolve(agentId));
        log.info("移除智能体 session={} agent={} name={}", sessionId, agentId, removed.getName());
    }

    /** 递归删除目录（best-effort：删不掉只记 warn，不抛） */
    private void deleteTree(Path dir) {
        try {
            if (!Files.exists(dir)) {
                return;
            }
            try (var walk = Files.walk(dir)) {
                walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        } catch (IOException e) {
            log.warn("清理临时目录失败 path={} err={}", dir, e.toString());
        }
    }

    private StyleProfile analyzeStyle(Session session, String text) {
        if (!StringUtils.hasText(text)) {
            return defaultStyle();
        }
        try {
            ChatClient client = agentFactory.hostClient();
            String sample = text.length() > 4000 ? text.substring(0, 4000) : text;
            String raw = client.prompt()
                    .user("""
                            请分析下列作者文本的文风，输出简洁 JSON：
                            {"tone":"","person":"","rhythm":"","vocabularyLevel":"","summary":"","signaturePhrases":[""]}
                            文本：
                            %s
                            """.formatted(sample))
                    .call()
                    .content();
            return parseStyle(raw);
        } catch (Exception e) {
            log.info("文风预分析回退默认: {}", e.toString());
            return defaultStyle();
        }
    }

    /** 默认中性文风：LLM 不可用、或返回内容解析不出来时用。 */
    private static StyleProfile defaultStyle() {
        StyleProfile p = new StyleProfile();
        p.setSummary("默认中性书面语");
        p.setTone("客观");
        p.setPerson("第三人称或中性");
        p.setRhythm("中等句长");
        return p;
    }

    /**
     * 把 Host 返回的文风 JSON 解析成 {@link StyleProfile}。
     *
     * <p>逐字段覆盖默认值：模型只回了一部分字段时，其余保留默认，
     * 不会因为个别字段缺失就把整份画像丢回默认（旧实现只判断 raw 里有没有
     * "summary" 字样，然后把整段 JSON 原文塞进 summary —— 等于这次调用白花）。
     */
    private StyleProfile parseStyle(String raw) {
        if (!StringUtils.hasText(raw)) {
            return defaultStyle();
        }
        try {
            JsonNode n = objectMapper.readTree(DiscussionPipeline.stripFencePublic(raw));
            if (n == null || !n.isObject()) {
                return defaultStyle();
            }
            StyleProfile p = defaultStyle();
            setIfPresent(n, "summary", p::setSummary);
            setIfPresent(n, "tone", p::setTone);
            setIfPresent(n, "person", p::setPerson);
            setIfPresent(n, "rhythm", p::setRhythm);
            setIfPresent(n, "vocabularyLevel", p::setVocabularyLevel);
            if (n.has("signaturePhrases") && n.get("signaturePhrases").isArray()) {
                List<String> phrases = new ArrayList<>();
                n.get("signaturePhrases").forEach(x -> {
                    String s = x.asText();
                    if (StringUtils.hasText(s)) {
                        phrases.add(s.trim());
                    }
                });
                p.setSignaturePhrases(phrases);
            }
            return p;
        } catch (Exception e) {
            log.info("文风 JSON 解析失败，回退默认: {}", e.toString());
            return defaultStyle();
        }
    }

    /** 字段存在且非空白才写回，避免模型给空串把默认值抹掉。 */
    private static void setIfPresent(JsonNode n, String field, java.util.function.Consumer<String> setter) {
        if (!n.has(field) || n.get(field).isNull()) {
            return;
        }
        String v = n.get(field).asText();
        if (StringUtils.hasText(v)) {
            setter.accept(v.trim());
        }
    }

    public Path workDir(String sessionId) {
        return Path.of(props.getSession().getTempDir(), sessionId);
    }

    private void ensureWorkDir(String sessionId) {
        try {
            Files.createDirectories(workDir(sessionId));
        } catch (IOException e) {
            throw new IllegalStateException("无法创建会话目录: " + e.getMessage(), e);
        }
    }
}
