package com.multiagent.discuss.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiagent.discuss.agent.AgentFactory;
import com.multiagent.discuss.config.AppProperties;
import com.multiagent.discuss.domain.*;
import com.multiagent.discuss.knowledge.AgentKnowledgeStore;
import com.multiagent.discuss.knowledge.InMemorySessionRepository;
import com.multiagent.discuss.util.ChatUsageExtractor;
import com.multiagent.discuss.service.SkillActivationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 独立思考栅栏 + Host 讨论 + 可选风格拍板与改稿。
 */
@Service
public class DiscussionPipeline {

    private static final Logger log = LoggerFactory.getLogger(DiscussionPipeline.class);

    private final InMemorySessionRepository repo;
    private final AgentKnowledgeStore knowledgeStore;
    private final AgentFactory agentFactory;
    private final AppProperties props;
    private final ObjectMapper objectMapper;
    private final Executor thinkExecutor;
    private final SkillActivationService skillActivationService;

    public DiscussionPipeline(InMemorySessionRepository repo,
                              AgentKnowledgeStore knowledgeStore,
                              AgentFactory agentFactory,
                              AppProperties props,
                              ObjectMapper objectMapper,
                              SkillActivationService skillActivationService,
                              @Qualifier("thinkExecutor") Executor thinkExecutor) {
        this.repo = repo;
        this.knowledgeStore = knowledgeStore;
        this.agentFactory = agentFactory;
        this.props = props;
        this.objectMapper = objectMapper;
        this.skillActivationService = skillActivationService;
        this.thinkExecutor = thinkExecutor;
    }

    public Question submitQuestion(String sessionId, String content) {
        Session session = repo.requireSession(sessionId);
        List<DomainAgentConfig> ready = session.readyAgents();
        if (ready.isEmpty()) {
            throw new IllegalStateException("至少需要 1 个 KnowledgeReady 的领域智能体才能提问");
        }

        String qid = "q-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Question question = new Question(qid, sessionId, content);
        question.setStatus(QuestionStatus.THINKING);
        question.setStageHint("智能体并行独立思考中…（" + ready.size() + " 位专家）");
        session.getQuestionIds().add(qid);
        session.addHistory(new ChatHistoryItem("user", content));
        repo.saveQuestion(question);

        // 未就绪标记 skipped
        for (DomainAgentConfig a : session.getAgents().values()) {
            if (a.getKnowledgeStatus() != KnowledgeStatus.KNOWLEDGE_READY) {
                question.getSkippedAgents().add(a.getId() + ":" + a.getName() + "(" + a.getKnowledgeStatus() + ")");
                AgentThought skipped = new AgentThought();
                skipped.setAgentId(a.getId());
                skipped.setAgentName(a.getName());
                skipped.setState(AgentThought.State.SKIPPED);
                skipped.setErrorMessage("知识未就绪: " + a.getKnowledgeStatus());
                question.getThoughts().put(a.getId(), skipped);
            }
        }

        // 并行独立思考（UserDoc 不出场）
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (DomainAgentConfig agent : ready) {
            AgentThought thought = new AgentThought();
            thought.setAgentId(agent.getId());
            thought.setAgentName(agent.getName());
            thought.setState(AgentThought.State.PENDING);
            question.getThoughts().put(agent.getId(), thought);

            futures.add(CompletableFuture.runAsync(
                    () -> runIndependentThink(session, question, agent),
                    thinkExecutor));
        }

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .orTimeout(props.getDiscuss().getThinkTimeoutSeconds(), TimeUnit.SECONDS)
                .whenComplete((ok, err) -> {
                    if (err != null) {
                        log.warn("思考阶段异常 qid={}: {}", qid, err.toString());
                        // 超时/异常时底层任务不会被取消（在途的 LLM 调用让它自己跑完），
                        // 而 orTimeout 之后 whenComplete 只触发这一次 —— 不在这里把未完成者
                        // 标成终态，栅栏就永远不齐套，议题会永久停在 THINKING。
                        failUnfinishedThoughts(question, err);
                    }
                    onAllThoughtsReady(session, question);
                });

        return question;
    }

    /**
     * 这个席位要不要走「按问题检索 + 带《文件名》页头」的知识注入。
     *
     * <p>判据是**知识库里可能混着多份文件**：导师库天然多篇；带 {@code agentUuid} 的常驻席位
     * 可能已经从账号知识库里灌回了多份资料（见 {@code PersistentAgentService.attachKnowledge}）。
     *
     * <p>刻意用 {@code agentUuid != null} 而不是「这次灌了几份」：后者要在席位上再维护一个
     * 「多文档」标志，join 时刻与上传之后都得跟着变，多一处会漂的状态。用 uuid 判只依赖
     * 「它是不是账号级对象」这一件事 —— 何况即便只有一份文件，带来源的页头也只会让引用更准。
     *
     * <p>⚠️ 这是 <b>prompt 可见</b>的行为变更：带 uuid 的席位页数从 8/6 变成 10/8、页头多《文件名》。
     * 不带 uuid 的普通席位走的分支**逐字不变**，那是回归底线。
     */
    static boolean useRetrievalFor(DomainAgentConfig agent) {
        return agent.isMentor() || agent.getAgentUuid() != null;
    }

    private void runIndependentThink(Session session, Question question, DomainAgentConfig agent) {
        AgentThought thought = question.getThoughts().get(agent.getId());
        thought.setState(AgentThought.State.RUNNING);
        try {
            // 多文档知识库（导师 / 带 uuid 的常驻智能体）必须走按问题检索：
            //   ① 取页序前 N 页只会拿到第一个文件的头几页，后面几个文件在讨论中**完全不可见**；
            //   ② 更要命的是 buildKnowledgeContext 的页头不带文件名，而 prompt 要求
            //      「引用文献时必须给真实的页/表/图/DOI」—— 模型不知道手上这段来自哪篇，
            //      唯一能满足这条要求的办法就是**编一个文件名出来**。
            //      走检索版页头是「《文件名》第 N 页」，它才有真实的来源可引。
            String knowledge = useRetrievalFor(agent)
                    ? knowledgeStore.buildRetrievedContext(session.getId(), agent.getId(), question.getContent(), 10)
                    : knowledgeStore.buildKnowledgeContext(session.getId(), agent.getId(), 8);
            String history = recentHistory(session);
            String userExcerpt = optionalUserExcerpt(session);
            ChatClient client = agentFactory.forAgent(agent);
            SkillActivationService.Activation skills = skillActivationService.activate(
                    session.getOwnerUserId(), agent, question.getContent());
            String prompt = independentPrompt(question.getContent(), userExcerpt, knowledge, history,
                    AgentFactory.personalityReminder(agent) + mentorInstruction(agent), skills.promptAddition());

            ChatResponse resp = client.prompt().user(prompt).call().chatResponse();
            String raw = resp.getResult().getOutput().getText();
            thought.setRawText(raw);
            fillThoughtFromJson(thought, raw);
            thought.setState(AgentThought.State.DONE);
            thought.setFinishedAt(Instant.now());

            TokenUsage usage = ChatUsageExtractor.extract(resp, agent.getModel());
            question.addTokenUsage(usage);
            Turn turn = new Turn(
                    agent.getId(), agent.getName(), "DOMAIN",
                    Turn.Phase.INDEPENDENT_THINK, 0,
                    thought.asSpeech());
            turn.setUsage(usage);
            turn.setSkillInvocations(toDomainInvocations(skills));
            turn.setSkillEvaluated(true);
            question.addTurn(turn);
            repo.trimSkillInvocations(session.getId(), agent.getId());
        } catch (Exception e) {
            log.warn("独立思考失败 agent={}: {}", agent.getId(), e.toString());
            thought.setState(AgentThought.State.FAILED);
            thought.setErrorMessage(e.getMessage());
            thought.setViewpoint("（思考失败）" + e.getMessage());
            thought.setFinishedAt(Instant.now());
        }
    }

    static String independentPrompt(String question, String userExcerpt, String knowledge,
                                    String history, String roleInstructions, String skillInstructions) {
        return """
                    【独立思考】请仅依据你的私有文献回答用户问题。UserDoc 不参与本阶段。

                    用户问题：
                    %s
                    %s

                    你的私有文献/Vision 知识：
                    %s

                    近期会话历史（可选）：
                    %s

                    %s
                    %s

                    请输出 JSON（不要 Markdown 围栏）：
                    {
                      "viewpoint": "你的专业观点",
                      "selectedEvidence": ["页/表/图依据"],
                      "doubtsOnVision": ["对识图的质疑"],
                      "confidence": 0.0,
                      "requestVisionRefine": false
                    }
                    """.formatted(
                    question,
                    userExcerpt == null ? "" : "用户原文相关摘录（仅供对照，非你的文献）：\n" + userExcerpt,
                    knowledge,
                    history,
                    roleInstructions,
                    skillInstructions);
    }

    private void onAllThoughtsReady(Session session, Question question) {
        synchronized (question) {
            if (question.getStatus() != QuestionStatus.THINKING) {
                return;
            }
            // 硬栅栏：任一智能体仍在排队/思考中，不得进入讨论
            if (!allAgentsFinishedThinking(question)) {
                log.warn("思考栅栏未齐套，暂不切换状态 qid={}", question.getId());
                return;
            }
            question.setStatus(QuestionStatus.ALL_THOUGHTS_READY);
            question.setStageHint("全部智能体已完成独立思考，正在汇总");
        }
        if (session.isAutoDiscuss()) {
            try {
                startDiscussion(session.getId(), question.getId());
            } catch (Exception e) {
                log.error("autoDiscuss 失败: {}", e.toString());
                question.setStatus(QuestionStatus.FAILED);
                question.setErrorMessage(e.getMessage());
            }
        }
    }

    /**
     * 是否所有智能体均已结束独立思考（DONE / FAILED / SKIPPED）。
     * PENDING、RUNNING 视为未完成——讨论不得开始。
     */
    private static boolean allAgentsFinishedThinking(Question question) {
        if (question.getThoughts().isEmpty()) {
            return false;
        }
        return question.getThoughts().values().stream().allMatch(t -> {
            AgentThought.State s = t.getState();
            return s == AgentThought.State.DONE
                    || s == AgentThought.State.FAILED
                    || s == AgentThought.State.SKIPPED;
        });
    }

    /**
     * 超时 / 异常兜底：把仍处于 PENDING / RUNNING 的思考标记为 FAILED，
     * 让 {@link #allAgentsFinishedThinking} 得以放行，议题不至于卡死在 THINKING。
     *
     * <p>晚到的成功结果会把状态改回 DONE —— 那比「超时失败」更准确，故不阻止；
     * 讨论阶段只读已完成者的 viewpoint，晚到者的补充不会打乱已经开始的讨论。
     */
    private void failUnfinishedThoughts(Question question, Throwable err) {
        boolean timeout = err instanceof TimeoutException
                || err.getCause() instanceof TimeoutException;
        String reason = timeout
                ? "独立思考超时（超过 " + props.getDiscuss().getThinkTimeoutSeconds() + " 秒未返回）"
                : "思考阶段异常：" + err;
        for (AgentThought t : question.getThoughts().values()) {
            AgentThought.State s = t.getState();
            if (s == AgentThought.State.PENDING || s == AgentThought.State.RUNNING) {
                t.setErrorMessage(reason);
                t.setViewpoint("（独立思考未按时返回，本轮不参与发言）");
                t.setFinishedAt(Instant.now());
                t.setState(AgentThought.State.FAILED);
            }
        }
    }

    public Question startDiscussion(String sessionId, String questionId) {
        Session session = repo.requireSession(sessionId);
        Question question = repo.requireQuestion(sessionId, questionId);

        synchronized (question) {
            QuestionStatus st = question.getStatus();
            if (st == QuestionStatus.DISCUSSING
                    || st == QuestionStatus.COMPLETED
                    || st == QuestionStatus.REVISED_COMPLETED
                    || st == QuestionStatus.PROFESSIONAL_CONSENSUS_READY
                    || st == QuestionStatus.STYLE_FINAL_PASS
                    || st == QuestionStatus.REVISION_PLAN_READY
                    || st == QuestionStatus.AWAITING_CONFIRM
                    || st == QuestionStatus.REVISING
                    || st == QuestionStatus.FAILED) {
                throw new IllegalStateException("当前状态不允许开始讨论: " + st);
            }

            // 必须等全部智能体思考完毕；THINKING 中即使部分完成也禁止开讨论
            if (!allAgentsFinishedThinking(question)) {
                long pending = question.getThoughts().values().stream()
                        .filter(t -> t.getState() == AgentThought.State.PENDING
                                || t.getState() == AgentThought.State.RUNNING)
                        .count();
                throw new IllegalStateException(
                        "独立思考尚未全部完成（仍有 " + pending + " 位智能体未结束），无法开始讨论");
            }

            if (st == QuestionStatus.THINKING) {
                question.setStatus(QuestionStatus.ALL_THOUGHTS_READY);
                question.setStageHint("全部智能体已完成独立思考，正在汇总");
            } else if (st != QuestionStatus.ALL_THOUGHTS_READY) {
                throw new IllegalStateException("当前状态不允许开始讨论: " + st);
            }

            question.setStatus(QuestionStatus.DISCUSSING);
            int total = session.getMaxRounds();
            question.setStageHint("圆桌讨论中（第 1 / " + total + " 轮）");
        }

        try {
            runDiscussion(session, question);
            return question;
        } catch (Exception e) {
            question.setStatus(QuestionStatus.FAILED);
            question.setErrorMessage(e.getMessage());
            throw e;
        }
    }

    private void runDiscussion(Session session, Question question) {
        ChatClient host = agentFactory.hostClient(session);
        int maxRounds = session.getMaxRounds();

        String thoughtsSummary = question.getThoughts().values().stream()
                .filter(t -> t.getState() == AgentThought.State.DONE || t.getState() == AgentThought.State.FAILED)
                .map(t -> "- " + t.getAgentName() + "：" + t.asSpeech())
                .collect(Collectors.joining("\n"));

        if (!question.isDiscussionStarted()) {
            question.addTurn(new Turn("host", "Host", "HOST", Turn.Phase.DISCUSSION, 0,
                    "开始专业讨论。独立观点汇总：\n" + thoughtsSummary));
            question.setDiscussionStarted(true);
        }

        StringBuilder discussionLog = discussionLog(question, thoughtsSummary);

        // 仅邀请已完成独立思考的智能体入席；未结束者本就不该走到讨论
        List<DomainAgentConfig> participants = session.readyAgents().stream()
                .filter(a -> {
                    AgentThought t = question.getThoughts().get(a.getId());
                    return t != null && t.getState() == AgentThought.State.DONE;
                })
                .toList();
        if (participants.isEmpty()) {
            throw new IllegalStateException("没有完成独立思考的智能体，无法开始讨论");
        }

        for (int round = question.getCompletedDiscussionRounds() + 1; round <= maxRounds; round++) {
            // Host 提出本轮焦点
            ChatResponse focusResp = host.prompt().user("""
                    你是主持人。根据以下讨论记录，用简短中文提出第 %d 轮讨论焦点（分歧、需补充或需反驳点）。
                    不要让 UserDoc 出场。

                    用户问题：%s

                    记录：
                    %s
                    """.formatted(round, question.getContent(), discussionLog)).call().chatResponse();
            String focus = focusResp.getResult().getOutput().getText();
            TokenUsage focusUsage = ChatUsageExtractor.extract(focusResp, null);
            question.addTokenUsage(focusUsage);

            Turn focusTurn = new Turn("host", "Host", "HOST", Turn.Phase.DISCUSSION, round,
                    "【第 " + round + " 轮焦点】" + focus);
            focusTurn.setUsage(focusUsage);
            question.addTurn(focusTurn);
            discussionLog.append("\nHost 焦点 R").append(round).append(": ").append(focus).append('\n');

            for (DomainAgentConfig agent : participants) {
                // 本轮焦点是最相关的检索信号，与原始问题拼在一起喂给检索
                String knowledge = useRetrievalFor(agent)
                        ? knowledgeStore.buildRetrievedContext(session.getId(), agent.getId(),
                        question.getContent() + "\n" + focus, 8)
                        : knowledgeStore.buildKnowledgeContext(session.getId(), agent.getId(), 6);
                ChatClient client = agentFactory.forAgent(agent);
                SkillActivationService.Activation skills = skillActivationService.activate(
                        session.getOwnerUserId(), agent, question.getContent() + "\n" + focus);
                ChatResponse replyResp = client.prompt().user(discussionPrompt(
                        round, question.getContent(), focus, discussionLog.toString(), knowledge,
                        AgentFactory.personalityReminder(agent) + mentorInstruction(agent),
                        skills.promptAddition()))
                        .call().chatResponse();
                String reply = replyResp.getResult().getOutput().getText();
                TokenUsage replyUsage = ChatUsageExtractor.extract(replyResp, agent.getModel());
                question.addTokenUsage(replyUsage);
                Turn replyTurn = new Turn(agent.getId(), agent.getName(), "DOMAIN",
                        Turn.Phase.DISCUSSION, round, reply);
                replyTurn.setUsage(replyUsage);
                replyTurn.setSkillInvocations(toDomainInvocations(skills));
                replyTurn.setSkillEvaluated(true);
                question.addTurn(replyTurn);
                repo.trimSkillInvocations(session.getId(), agent.getId());
                discussionLog.append(agent.getName()).append(": ").append(reply).append('\n');
            }
            question.setCompletedDiscussionRounds(round);
            if (session.isAllowInterjections()) {
                pauseForInterjection(question, round >= maxRounds);
                return;
            } else if (round < maxRounds) {
                question.setStageHint("圆桌讨论中（第 " + (round + 1) + " / " + maxRounds + " 轮）");
            } else {
                question.setStageHint("正在整理专业共识…");
            }
        }

        // 冻结专业共识
        String consensusPrompt = """
                专业讨论已结束。请冻结专业共识，输出 JSON：
                {
                  "summary": "共识摘要",
                  "conclusions": ["最终专业结论要点"],
                  "corrections": ["若改稿：何处有误应改成什么"],
                  "additions": ["应增补什么"],
                  "finalAnswer": "面向用户的最终专业答案",
                  "explanation": "解释如何得出该答案"
                }
                用户问题：%s
                讨论记录：
                %s
                """.formatted(question.getContent(), discussionLog);
        // 本场没有导师席位时 mentorSynthesisInstruction 返回空串，prompt 与改动前逐字节相同
        String mentorBlock = mentorSynthesisInstruction(session);
        if (!mentorBlock.isEmpty()) {
            consensusPrompt = consensusPrompt + "\n" + mentorBlock;
        }
        ChatResponse consensusResp = host.prompt().user(consensusPrompt).call().chatResponse();
        String consensusRaw = consensusResp.getResult().getOutput().getText();
        TokenUsage consensusUsage = ChatUsageExtractor.extract(consensusResp, null);
        question.addTokenUsage(consensusUsage);

        ProfessionalConsensus consensus = parseConsensus(consensusRaw);
        question.setProfessionalConsensus(consensus);
        question.setStatus(QuestionStatus.PROFESSIONAL_CONSENSUS_READY);
        question.setStageHint("正在整理专业共识…");
        Turn consensusTurn = new Turn("host", "Host", "HOST", Turn.Phase.CONSENSUS, 0,
                consensus.asPromptText());
        consensusTurn.setUsage(consensusUsage);
        question.addTurn(consensusTurn);

        if (session.isDocEditMode()) {
            runStyleAndRevisionPlan(session, question, host);
        } else {
            question.setFinalAnswer(extractField(consensusRaw, "finalAnswer", consensus.getSummary()));
            question.setExplanation(extractField(consensusRaw, "explanation", consensus.asPromptText()));
            question.setStatus(QuestionStatus.COMPLETED);
            question.setStageHint("已完成");
            session.addHistory(new ChatHistoryItem("assistant", question.getFinalAnswer()));
            question.addTurn(new Turn("host", "Host", "HOST", Turn.Phase.CONSENSUS, 0,
                    "【终稿】\n" + question.getFinalAnswer() + "\n\n【解释】\n" + question.getExplanation()));
        }
    }

    private static StringBuilder discussionLog(Question question, String thoughtsSummary) {
        StringBuilder log = new StringBuilder("独立思考：\n").append(thoughtsSummary).append('\n');
        question.getTranscript().stream()
                .filter(turn -> turn.getPhase() == Turn.Phase.DISCUSSION
                        || turn.getPhase() == Turn.Phase.USER_INTERJECTION)
                .forEach(turn -> log.append(turn.getSpeakerName()).append(": ")
                        .append(turn.getContent()).append('\n'));
        return log;
    }

    private static void pauseForInterjection(Question question, boolean beforeFinal) {
        question.setStatus(QuestionStatus.WAITING_FOR_INTERJECTION);
        question.setStageHint(beforeFinal
                ? "讨论轮次已完成，可最后补充或继续生成共识"
                : "本轮已完成，可补充、指定追问或继续讨论");
    }

    public Question continueDiscussion(String sessionId, String questionId) {
        Session session = repo.requireSession(sessionId);
        Question question = repo.requireQuestion(sessionId, questionId);
        synchronized (question) {
            if (question.getStatus() != QuestionStatus.WAITING_FOR_INTERJECTION) {
                throw new IllegalStateException("当前不在等待插话状态");
            }
            question.setStatus(QuestionStatus.DISCUSSING);
            question.setStageHint(question.getCompletedDiscussionRounds() >= session.getMaxRounds()
                    ? "正在整理专业共识…"
                    : "正在继续下一轮讨论…");
        }
        try {
            runDiscussion(session, question);
            return question;
        } catch (Exception e) {
            question.setStatus(QuestionStatus.FAILED);
            question.setErrorMessage(e.getMessage());
            throw e;
        }
    }

    public Question addInterjection(String sessionId, String questionId, String content, String targetAgentId) {
        Session session = repo.requireSession(sessionId);
        Question question = repo.requireQuestion(sessionId, questionId);
        if (!StringUtils.hasText(content)) throw new IllegalArgumentException("插话内容不能为空");
        DomainAgentConfig target = null;
        if (StringUtils.hasText(targetAgentId)) {
            target = session.getAgents().get(targetAgentId);
            if (target == null || target.getKnowledgeStatus() != KnowledgeStatus.KNOWLEDGE_READY) {
                throw new IllegalArgumentException("指定的智能体不存在或尚未就绪");
            }
            AgentThought targetThought = question.getThoughts().get(targetAgentId);
            if (targetThought == null || targetThought.getState() != AgentThought.State.DONE) {
                throw new IllegalArgumentException("指定的智能体未参与当前讨论");
            }
        }
        synchronized (question) {
            if (question.getStatus() != QuestionStatus.WAITING_FOR_INTERJECTION) {
                throw new IllegalStateException("当前不在等待插话状态");
            }
            String speakerName = target == null ? "用户补充" : "用户追问 · " + target.getName();
            question.addTurn(new Turn("user", speakerName, "USER", Turn.Phase.USER_INTERJECTION,
                    question.getCompletedDiscussionRounds(), content.trim()));
            question.setStatus(QuestionStatus.DISCUSSING);
            question.setStageHint(target == null ? "智能体正在回应你的补充…" : "智能体正在回应你的指定追问…");
        }
        runInterjectionRound(session, question, content.trim(), target);
        pauseForInterjection(question, question.getCompletedDiscussionRounds() >= session.getMaxRounds());
        return question;
    }

    private void runInterjectionRound(Session session, Question question, String content, DomainAgentConfig target) {
        List<DomainAgentConfig> participants = new ArrayList<>(session.readyAgents().stream()
                .filter(agent -> {
                    AgentThought thought = question.getThoughts().get(agent.getId());
                    return thought != null && thought.getState() == AgentThought.State.DONE;
                }).toList());
        if (target != null) {
            participants.sort(Comparator.comparingInt(agent -> agent.getId().equals(target.getId()) ? 0 : 1));
        }
        String thoughtsSummary = question.getThoughts().values().stream()
                .filter(thought -> thought.getState() == AgentThought.State.DONE)
                .map(thought -> "- " + thought.getAgentName() + "：" + thought.asSpeech())
                .collect(Collectors.joining("\n"));
        StringBuilder interjectionLog = discussionLog(question, thoughtsSummary);
        int displayRound = question.getCompletedDiscussionRounds() + 1;
        for (DomainAgentConfig agent : participants) {
            try {
                String knowledge = useRetrievalFor(agent)
                        ? knowledgeStore.buildRetrievedContext(session.getId(), agent.getId(), content, 8)
                        : knowledgeStore.buildKnowledgeContext(session.getId(), agent.getId(), 6);
                SkillActivationService.Activation skills = skillActivationService.activate(
                        session.getOwnerUserId(), agent, question.getContent() + "\n用户插话：" + content);
                ChatResponse response = agentFactory.forAgent(agent).prompt().user(discussionPrompt(
                        displayRound, question.getContent(), "用户插话：" + content, interjectionLog.toString(), knowledge,
                        AgentFactory.personalityReminder(agent) + mentorInstruction(agent), skills.promptAddition()))
                        .call().chatResponse();
                String reply = response.getResult().getOutput().getText();
                TokenUsage usage = ChatUsageExtractor.extract(response, agent.getModel());
                question.addTokenUsage(usage);
                Turn turn = new Turn(agent.getId(), agent.getName(), "DOMAIN",
                        Turn.Phase.USER_INTERJECTION, displayRound, reply);
                turn.setUsage(usage);
                turn.setSkillInvocations(toDomainInvocations(skills));
                turn.setSkillEvaluated(true);
                question.addTurn(turn);
                repo.trimSkillInvocations(session.getId(), agent.getId());
                interjectionLog.append(agent.getName()).append(": ").append(reply).append('\n');
            } catch (Exception e) {
                log.warn("回应用户插话失败 agent={}: {}", agent.getId(), e.toString());
                question.addTurn(new Turn(agent.getId(), agent.getName(), "DOMAIN",
                        Turn.Phase.USER_INTERJECTION, displayRound, "（回应失败，已跳过）"));
            }
        }
    }

    public Session setAllowInterjections(String sessionId, boolean allow) {
        Session session = repo.requireSession(sessionId);
        session.setAllowInterjections(allow);
        if (!allow && !session.getQuestionIds().isEmpty()) {
            String latestId = session.getQuestionIds().get(session.getQuestionIds().size() - 1);
            Question latest = repo.findQuestion(latestId).orElse(null);
            if (latest != null && latest.getStatus() == QuestionStatus.WAITING_FOR_INTERJECTION) {
                continueDiscussion(sessionId, latestId);
            }
        }
        return session;
    }

    private void runStyleAndRevisionPlan(Session session, Question question, ChatClient host) {
        question.setStatus(QuestionStatus.STYLE_FINAL_PASS);
        question.setStageHint("正在用作者文风收束专业要点…");
        UserDocument doc = session.getUserDocument();
        if (doc == null || doc.getStatus() != KnowledgeStatus.KNOWLEDGE_READY) {
            // 无用户文档时仍可给专业终稿
            question.setFinalAnswer(question.getProfessionalConsensus() == null
                    ? "" : question.getProfessionalConsensus().asPromptText());
            question.setExplanation("docEditMode 已开但用户文档未就绪，仅输出专业共识。");
            question.setStatus(QuestionStatus.COMPLETED);
            question.setStageHint("已完成");
            return;
        }

        DomainAgentConfig styleCarrier = session.readyAgents().stream().findFirst().orElse(null);
        ChatClient userDoc = agentFactory.userDocClient(styleCarrier);

        String styleText = doc.getStyleProfile() == null ? "中性书面语" : doc.getStyleProfile().asPromptText();
        String original = doc.getOriginalText() == null ? "" : doc.getOriginalText();
        if (original.length() > 8000) {
            original = original.substring(0, 8000) + "\n…(截断)";
        }

        ChatResponse styleResp = userDoc.prompt().user("""
                【最终拍板 · 风格收束】
                你是 UserDocAgent。专业共识已冻结，你不得反驳或修改专业含义。

                专业共识：
                %s

                作者文风：
                %s

                用户原文（片段）：
                %s

                用户问题：%s
                用户风格偏好：%s

                请：
                1) 用贴合作者风格重述专业要点（不改含义）
                2) 生成修改建议清单 JSON：
                {
                  "summary": "",
                  "styleDiffNotes": "",
                  "items": [
                    {
                      "id": "r1",
                      "location": "章节/段落/原文摘录",
                      "type": "FIX_ERROR|ADD_KNOWLEDGE",
                      "reason": "专业依据",
                      "professionalSuggestion": "专业要点",
                      "styleAlignedSuggestion": "贴合作者风格的写法",
                      "sourceAgents": []
                    }
                  ]
                }
                只输出 JSON。
                """.formatted(
                question.getProfessionalConsensus().asPromptText(),
                styleText,
                original,
                question.getContent(),
                session.getUserStyle() == null ? "" : session.getUserStyle()
        )).call().chatResponse();
        String stylePass = styleResp.getResult().getOutput().getText();
        TokenUsage styleUsage = ChatUsageExtractor.extract(styleResp, styleCarrier == null ? null : styleCarrier.getModel());
        question.addTokenUsage(styleUsage);

        Turn styleTurn = new Turn("userDocAgent", "UserDoc", "USER_DOC",
                Turn.Phase.STYLE_FINAL, 0, "风格收束完成（详见 RevisionPlan）");
        styleTurn.setUsage(styleUsage);
        question.addTurn(styleTurn);

        RevisionPlan plan = parseRevisionPlan(stylePass);
        question.setRevisionPlan(plan);
        question.setStatus(QuestionStatus.REVISION_PLAN_READY);
        question.setStageHint("修改建议已生成，等待你确认");
        question.addTurn(new Turn("userDocAgent", "UserDoc", "USER_DOC",
                Turn.Phase.REVISION_PLAN, 0, plan.getSummary() == null ? "已生成修改建议" : plan.getSummary()));

        // Host 浅校验：不把 UserDoc 当专业依据
        question.setFinalAnswer(question.getProfessionalConsensus().asPromptText());
        question.setExplanation("专业共识已冻结；改稿请查看 revision-plan 并确认后 revise。");
        question.setStatus(QuestionStatus.AWAITING_CONFIRM);
        question.setStageHint("等待你勾选要改的条目");
    }

    public Question confirmRevision(String sessionId, String questionId,
                                    List<String> acceptedItemIds, String note, boolean autoRevise) {
        Question question = repo.requireQuestion(sessionId, questionId);
        if (question.getStatus() != QuestionStatus.AWAITING_CONFIRM
                && question.getStatus() != QuestionStatus.REVISION_PLAN_READY) {
            throw new IllegalStateException("当前状态不可确认改稿: " + question.getStatus());
        }
        question.setConfirmedItemIds(acceptedItemIds);
        question.setUserConfirmNote(note);
        question.setStatus(QuestionStatus.AWAITING_CONFIRM);
        if (autoRevise) {
            return revise(sessionId, questionId);
        }
        return question;
    }

    public Question revise(String sessionId, String questionId) {
        Session session = repo.requireSession(sessionId);
        Question question = repo.requireQuestion(sessionId, questionId);
        if (question.getRevisionPlan() == null) {
            throw new IllegalStateException("尚无 RevisionPlan");
        }
        if (question.getConfirmedItemIds() == null || question.getConfirmedItemIds().isEmpty()) {
            throw new IllegalStateException("请先确认至少一项修改（acceptedItemIds）");
        }
        UserDocument doc = session.getUserDocument();
        if (doc == null || !StringUtils.hasText(doc.getOriginalText())) {
            throw new IllegalStateException("用户原文不可用");
        }

        question.setStatus(QuestionStatus.REVISING);
        question.setStageHint("正在按已确认的条目改写全文…");
        DomainAgentConfig styleCarrier = session.readyAgents().stream().findFirst().orElse(null);
        ChatClient userDoc = agentFactory.userDocClient(styleCarrier);

        List<RevisionPlan.Item> accepted = question.getRevisionPlan().getItems().stream()
                .filter(i -> question.getConfirmedItemIds().contains(i.getId()))
                .toList();

        String itemsJson;
        try {
            itemsJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(accepted);
        } catch (Exception e) {
            itemsJson = accepted.toString();
        }

        String style = doc.getStyleProfile() == null ? "" : doc.getStyleProfile().asPromptText();
        ChatResponse reviseResp = userDoc.prompt().user("""
                【阶段二改写】只应用用户已确认的修改项；未确认的错误不改、未确认的增补不加。
                对齐作者文风。输出 JSON：
                {
                  "revisedFullText": "全文",
                  "changeLog": [
                    {"planItemId":"","originalSnippet":"","newSnippet":"","note":""}
                  ]
                }

                文风：
                %s

                已确认修改项：
                %s

                用户补充说明：
                %s

                原文：
                %s
                """.formatted(
                style,
                itemsJson,
                question.getUserConfirmNote() == null ? "" : question.getUserConfirmNote(),
                doc.getOriginalText().length() > 12000
                        ? doc.getOriginalText().substring(0, 12000)
                        : doc.getOriginalText()
        )).call().chatResponse();
        String raw = reviseResp.getResult().getOutput().getText();
        TokenUsage reviseUsage = ChatUsageExtractor.extract(reviseResp, styleCarrier == null ? null : styleCarrier.getModel());
        question.addTokenUsage(reviseUsage);

        RevisedDocument revised = parseRevised(raw, doc.getOriginalText(), accepted);
        question.setRevisedDocument(revised);
        question.setStatus(QuestionStatus.REVISED_COMPLETED);
        question.setStageHint("改稿完成");
        Turn reviseTurn = new Turn("userDocAgent", "UserDoc", "USER_DOC",
                Turn.Phase.REVISION, 0, "改写完成，变更条数=" + revised.getChangeLog().size());
        reviseTurn.setUsage(reviseUsage);
        question.addTurn(reviseTurn);
        session.addHistory(new ChatHistoryItem("assistant", "改写完成"));
        return question;
    }

    /**
     * 「继续改稿」：在当前改写结果的基础上，按用户临时提的要求再改一轮。
     *
     * <p>与 {@link #revise} 的区别：不走「生成修改建议 → 用户勾选确认」那套，
     * 用户的要求直接进 prompt，也不需要已确认条目。底稿取上一轮的改写结果
     * （还没改写过则退回用户原文）。结果**覆盖** {@code revisedDocument}，历史版本不保留。
     */
    public Question continueRevision(String sessionId, String questionId, String requirement) {
        Session session = repo.requireSession(sessionId);
        Question question = repo.requireQuestion(sessionId, questionId);
        if (!StringUtils.hasText(requirement)) {
            throw new IllegalArgumentException("请说明这一次想怎么改");
        }
        UserDocument doc = session.getUserDocument();
        if (doc == null || !StringUtils.hasText(doc.getOriginalText())) {
            throw new IllegalStateException("用户原文不可用");
        }

        RevisedDocument prev = question.getRevisedDocument();
        String baseText = (prev != null && StringUtils.hasText(prev.getRevisedFullText()))
                ? prev.getRevisedFullText()
                : doc.getOriginalText();
        String baseSnippet = baseText.length() > 12000 ? baseText.substring(0, 12000) : baseText;

        question.setStatus(QuestionStatus.REVISING);
        question.setStageHint("正在按你的要求再改一轮…");

        DomainAgentConfig styleCarrier = session.readyAgents().stream().findFirst().orElse(null);
        ChatClient userDoc = agentFactory.userDocClient(styleCarrier);
        String style = doc.getStyleProfile() == null ? "" : doc.getStyleProfile().asPromptText();

        ChatResponse resp = userDoc.prompt().user("""
                【继续改稿】在给定底稿上，按用户这一次的要求做进一步修改。
                只动要求涉及的部分，其余尽量保持不动。对齐作者文风。输出 JSON：
                {
                  "revisedFullText": "全文",
                  "changeLog": [
                    {"planItemId":"","originalSnippet":"","newSnippet":"","note":""}
                  ]
                }

                文风：
                %s

                用户这一次的要求：
                %s

                底稿：
                %s
                """.formatted(style, requirement.trim(), baseSnippet)).call().chatResponse();

        String raw = resp.getResult().getOutput().getText();
        TokenUsage usage = ChatUsageExtractor.extract(
                resp, styleCarrier == null ? null : styleCarrier.getModel());
        question.addTokenUsage(usage);

        RevisedDocument revised = parseRevised(raw, baseText, List.of());
        question.setRevisedDocument(revised);
        question.setStatus(QuestionStatus.REVISED_COMPLETED);
        question.setStageHint("改稿完成（继续改稿）");

        Turn turn = new Turn("userDocAgent", "UserDoc", "USER_DOC",
                Turn.Phase.REVISION, 0, "继续改稿，变更条数=" + revised.getChangeLog().size());
        turn.setUsage(usage);
        question.addTurn(turn);
        session.addHistory(new ChatHistoryItem("assistant", "继续改稿完成"));
        return question;
    }

    private void fillThoughtFromJson(AgentThought thought, String raw) {        try {
            JsonNode n = objectMapper.readTree(stripFence(raw));
            if (n.has("viewpoint")) thought.setViewpoint(n.get("viewpoint").asText());
            if (n.has("confidence")) thought.setConfidence(n.get("confidence").asDouble());
            if (n.has("requestVisionRefine")) thought.setRequestVisionRefine(n.get("requestVisionRefine").asBoolean());
            if (n.has("selectedEvidence") && n.get("selectedEvidence").isArray()) {
                List<String> list = new ArrayList<>();
                n.get("selectedEvidence").forEach(x -> list.add(x.asText()));
                thought.setSelectedEvidence(list);
            }
            if (n.has("doubtsOnVision") && n.get("doubtsOnVision").isArray()) {
                List<String> list = new ArrayList<>();
                n.get("doubtsOnVision").forEach(x -> list.add(x.asText()));
                thought.setDoubtsOnVision(list);
            }
            if (!StringUtils.hasText(thought.getViewpoint())) {
                thought.setViewpoint(raw);
            }
        } catch (Exception e) {
            thought.setViewpoint(raw);
        }
    }

    private ProfessionalConsensus parseConsensus(String raw) {
        ProfessionalConsensus c = new ProfessionalConsensus();
        c.setRawText(raw);
        try {
            JsonNode n = objectMapper.readTree(stripFence(raw));
            if (n.has("summary")) c.setSummary(n.get("summary").asText());
            c.setConclusions(readStringArray(n, "conclusions"));
            c.setCorrections(readStringArray(n, "corrections"));
            c.setAdditions(readStringArray(n, "additions"));
        } catch (Exception e) {
            c.setSummary(raw);
        }
        return c;
    }

    private RevisionPlan parseRevisionPlan(String raw) {
        RevisionPlan plan = new RevisionPlan();
        try {
            JsonNode n = objectMapper.readTree(stripFence(raw));
            if (n.has("summary")) plan.setSummary(n.get("summary").asText());
            if (n.has("items") && n.get("items").isArray()) {
                int i = 1;
                for (JsonNode it : n.get("items")) {
                    RevisionPlan.Item item = new RevisionPlan.Item();
                    item.setId(it.has("id") ? it.get("id").asText() : "r" + i);
                    item.setLocation(text(it, "location"));
                    item.setType(text(it, "type"));
                    item.setReason(text(it, "reason"));
                    item.setProfessionalSuggestion(text(it, "professionalSuggestion"));
                    item.setStyleAlignedSuggestion(text(it, "styleAlignedSuggestion"));
                    if (it.has("sourceAgents") && it.get("sourceAgents").isArray()) {
                        List<String> sa = new ArrayList<>();
                        it.get("sourceAgents").forEach(x -> sa.add(x.asText()));
                        item.setSourceAgents(sa);
                    }
                    plan.getItems().add(item);
                    i++;
                }
            }
        } catch (Exception e) {
            plan.setSummary(raw);
            RevisionPlan.Item item = new RevisionPlan.Item();
            item.setId("r1");
            item.setType("ADD_KNOWLEDGE");
            item.setLocation("全文");
            item.setReason("解析失败，保留模型原文为建议");
            item.setProfessionalSuggestion(raw);
            item.setStyleAlignedSuggestion(raw);
            plan.getItems().add(item);
        }
        if (plan.getItems().isEmpty()) {
            RevisionPlan.Item item = new RevisionPlan.Item();
            item.setId("r1");
            item.setType("ADD_KNOWLEDGE");
            item.setLocation("全文");
            item.setReason("无结构化条目");
            item.setProfessionalSuggestion(plan.getSummary());
            item.setStyleAlignedSuggestion(plan.getSummary());
            plan.getItems().add(item);
        }
        return plan;
    }

    private RevisedDocument parseRevised(String raw, String original, List<RevisionPlan.Item> accepted) {
        RevisedDocument doc = new RevisedDocument();
        try {
            JsonNode n = objectMapper.readTree(stripFence(raw));
            if (n.has("revisedFullText")) {
                doc.setRevisedFullText(n.get("revisedFullText").asText());
            }
            if (n.has("changeLog") && n.get("changeLog").isArray()) {
                for (JsonNode c : n.get("changeLog")) {
                    RevisedDocument.ChangeLogEntry e = new RevisedDocument.ChangeLogEntry();
                    e.setPlanItemId(text(c, "planItemId"));
                    e.setOriginalSnippet(text(c, "originalSnippet"));
                    e.setNewSnippet(text(c, "newSnippet"));
                    e.setNote(text(c, "note"));
                    doc.getChangeLog().add(e);
                }
            }
        } catch (Exception e) {
            doc.setRevisedFullText(raw);
        }
        if (!StringUtils.hasText(doc.getRevisedFullText())) {
            doc.setRevisedFullText(original);
        }
        if (doc.getChangeLog().isEmpty()) {
            for (RevisionPlan.Item item : accepted) {
                RevisedDocument.ChangeLogEntry e = new RevisedDocument.ChangeLogEntry();
                e.setPlanItemId(item.getId());
                e.setOriginalSnippet(item.getLocation());
                e.setNewSnippet(item.getStyleAlignedSuggestion());
                e.setNote("模型未返回细粒度 changeLog");
                doc.getChangeLog().add(e);
            }
        }
        return doc;
    }

    private String optionalUserExcerpt(Session session) {
        if (!session.isDocEditMode() || session.getUserDocument() == null) {
            return null;
        }
        String t = session.getUserDocument().getOriginalText();
        if (!StringUtils.hasText(t)) return null;
        return t.length() > 1500 ? t.substring(0, 1500) + "…" : t;
    }

    /**
     * 导师席位的额外发言要求。
     *
     * <p>非导师席位返回<b>空串</b>，调用处直接在 personalityReminder 后面拼接 ——
     * 这样普通智能体的 prompt 逐字节不变，导师模式是纯增量。
     */
    private static String mentorInstruction(DomainAgentConfig agent) {
        if (!agent.isMentor()) {
            return "";
        }
        return """

                【导师模式 · 必须】
                你是基于该导师论文语料的模拟，不代表导师本人；提到该导师时用第三人称表述。
                事实性结论必须给出可核查来源，写成「《论文文件名》第 N 页」；给不出来就直说资料不足，
                绝不编造引用、页码、实验数据或该导师的观点。
                论文语料没有覆盖的问题，明确说明「论文中未涉及」，并建议补充材料或向真实导师确认。
                论证结构与表达尽量贴合该导师论文的学术风格。
                """;
    }

    /**
     * 主持人冻结共识时的导师要求。本场没有导师席位时返回<b>空串</b>，
     * 调用处据此保持原 prompt 不变。
     *
     * <p>画像直接取席位上的字段（加入会话时快照下来的），讨论期间不查库 ——
     * 编排层因此不必依赖 MentorService，也就不会和 SessionService 形成循环依赖。
     * 席位按 mentorUuid 排序，保证同一场讨论的 prompt 稳定可复现。
     */
    private static String mentorSynthesisInstruction(Session session) {
        List<DomainAgentConfig> mentors = session.getAgents().values().stream()
                .filter(DomainAgentConfig::isMentor)
                .sorted(Comparator.comparing(a -> a.getMentorUuid() == null ? "" : a.getMentorUuid()))
                .toList();
        if (mentors.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("导师指导要求：\n本场有 ").append(mentors.size()).append(" 位导师席位的论文语料参与了讨论。");
        for (DomainAgentConfig m : mentors) {
            sb.append("\n- ").append(m.getName());
            if (StringUtils.hasText(m.getMentorDiscipline())) {
                sb.append("（").append(m.getMentorDiscipline()).append("）");
            }
            if (StringUtils.hasText(m.getMentorStyleProfile())) {
                sb.append("\n  学术风格画像：").append(m.getMentorStyleProfile());
            }
        }
        sb.append("""

                最终答案必须：
                1) 吸收上述导师论文的知识与学术风格，给出可执行的论文修改建议；
                2) 每条事实性结论附可核查来源，写成「《论文文件名》第 N 页」；
                3) 明确声明这是基于论文语料的模拟指导，不代表导师本人；
                4) 语料未覆盖之处直说，不得编造引用、页码或实验数据。
                """);
        return sb.toString();
    }

    private String recentHistory(Session session) {
        return session.getHistory().stream()
                .map(h -> h.getRole() + ": " + h.getContent())
                .collect(Collectors.joining("\n"));
    }

    private static List<String> readStringArray(JsonNode n, String field) {
        List<String> list = new ArrayList<>();
        if (n != null && n.has(field) && n.get(field).isArray()) {
            n.get(field).forEach(x -> list.add(x.asText()));
        }
        return list;
    }

    private static String text(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) return null;
        return n.get(field).asText();
    }

    private static String extractField(String raw, String field, String fallback) {
        try {
            JsonNode n = new ObjectMapper().readTree(stripFence(raw));
            if (n.has(field)) return n.get(field).asText();
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static String stripFence(String s) {
        return stripFencePublic(s);
    }

    /** 去掉模型输出里的 Markdown 围栏（```json ... ``` 等），公共方法供其他服务复用。 */
    public static String stripFencePublic(String s) {
        if (s == null) return "{}";
        String t = s.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            int last = t.lastIndexOf("```");
            if (firstNl > 0 && last > firstNl) {
                t = t.substring(firstNl + 1, last).trim();
            }
        }
        return t;
    }

    private static List<SkillInvocation> toDomainInvocations(SkillActivationService.Activation activation) {
        return activation.invocations().stream()
                .map(i -> new SkillInvocation(i.skillUuid(), i.name(), i.reason(), i.at()))
                .toList();
    }

    static String discussionPrompt(int round, String question, String focus, String discussionLog,
                                   String knowledge, String roleInstructions, String skillInstructions) {
        return """
                【专业讨论 第 %d 轮】
                用户问题：%s
                主持人焦点：%s
                目前讨论记录：
                %s

                你的私有知识（仅你可见）：
                %s

                %s
                %s

                请基于自己的文献补充、反驳或同意他人观点。只输出发言正文，不要输出他人看不到的原文大段粘贴。
                """.formatted(round, question, focus, discussionLog, knowledge,
                roleInstructions, skillInstructions);
    }
}
