package com.multiagent.discuss.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiagent.discuss.db.entity.ArchivedSessionEntity;
import com.multiagent.discuss.db.entity.UserEntity;
import com.multiagent.discuss.db.repo.ArchivedSessionRepository;
import com.multiagent.discuss.domain.AgentThought;
import com.multiagent.discuss.domain.DomainAgentConfig;
import com.multiagent.discuss.domain.LlmEndpoint;
import com.multiagent.discuss.domain.ProfessionalConsensus;
import com.multiagent.discuss.domain.Question;
import com.multiagent.discuss.domain.QuestionStatus;
import com.multiagent.discuss.domain.Session;
import com.multiagent.discuss.domain.TokenUsage;
import com.multiagent.discuss.domain.Turn;
import com.multiagent.discuss.security.AuthException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 归档会话：「结束会话」时把整场讨论快照落库，供「已归档」只读查看。
 *
 * <p><b>脱敏是硬约束</b>：payload 不是直接序列化 {@code Session}，而是显式挑字段拼出来的。
 * 直接序列化会把 {@code DomainAgentConfig.apiKey} / {@code LlmEndpoint.apiKey} 的明文
 * 一起写进库，还会带上 visionProgress 之类的运行态噪声。
 *
 * <p>代价：快照里没有网关凭据，所以「从归档生成总结」只能走服务端默认网关
 * ，不能复用原会话的第三方网关。
 */
@Service
public class ArchivedSessionService {

    private final ArchivedSessionRepository repo;
    private final ObjectMapper objectMapper;

    public ArchivedSessionService(ArchivedSessionRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /**
     * 归档一场会话。
     *
     * @param title 列表显示名，由调用方算好（与会话列表口径保持一致）
     */
    @Transactional
    public ArchivedSessionEntity archive(UserEntity user, Session session, List<Question> questions,
                                         String title) {
        ArchivedSessionEntity e = new ArchivedSessionEntity();
        e.setOwnerUserId(user.getId());
        e.setSourceSessionId(session.getId());
        e.setTitle(title);
        e.setUserStyle(session.getUserStyle());
        e.setQuestionCount(questions.size());
        e.setAgentCount(session.getAgents().size());
        e.setTokenTotal(sumTokens(questions));
        e.setPayload(buildPayload(session, questions));
        return repo.save(e);
    }

    /** 列表：只回标量列，绝不碰 payload（可能很大） */
    public List<Map<String, Object>> list(UserEntity user) {
        return repo.findAllByOwnerUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(this::listView)
                .toList();
    }

    /** 详情：标量列 + 完整快照，交给前端只读渲染 */
    public Map<String, Object> detail(UserEntity user, Long id) {
        ArchivedSessionEntity e = requireOwned(user, id);
        Map<String, Object> m = new LinkedHashMap<>(listView(e));
        m.put("payload", readPayload(e));
        return m;
    }

    /** 读取归档快照的原始 JSON 文本（「从归档生成总结」用） */
    public String payloadOf(UserEntity user, Long id) {
        return requireOwned(user, id).getPayload();
    }

    /** 还原出来的会话 + 议题，供 {@code SummaryService.generateForArchived} 使用 */
    public record Reconstructed(Session session, List<Question> questions) {}

    /**
     * 把归档快照还原成真正的 {@code Session} / {@code Question} 对象。
     *
     * <p>为什么要手写还原：这两个类只有「多参构造函数 + final 字段 + 无 setter」，
     * 既没有 {@code @JsonCreator} 也没有 {@code -parameters} 编译参数，
     * Jackson 反序列化不了它们（嵌套的 Turn / AgentThought / ProfessionalConsensus
     * 反而是普通 POJO，可以直接 {@code convertValue}）。所以这里走
     * 「JsonNode 手工取值 + 公共 API 拼装」，而不是给领域类加注解。
     *
     * <p>时间字段（Session / Question 的 createdAt、updatedAt）都从快照读回，
     * 所以归档总结里的时间行显示的是**当初讨论的时间**，不是重新生成总结的时间。
     * 快照里没有该字段（旧归档）或格式非法时回退 {@code Instant.now()}，行为与从前一致。
     */
    public Reconstructed reconstruct(UserEntity user, Long id) {
        ArchivedSessionEntity e = requireOwned(user, id);
        JsonNode root;
        try {
            root = objectMapper.readTree(e.getPayload());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("归档内容解析失败（payload 可能已损坏）", ex);
        }
        JsonNode sn = root.path("session");

        Session session = new Session(
                sn.path("id").asText(e.getSourceSessionId()),
                sn.path("userStyle").asText(null),
                sn.path("autoDiscuss").asBoolean(true),
                sn.path("docEditMode").asBoolean(false),
                1,
                sn.path("maxRounds").asInt(2),
                parseInstant(sn.path("createdAt").asText(null)));
        // 只还原总结用得到的字段（名字 / 人设 / 性格）；网关凭据从来就没存过
        for (JsonNode an : sn.path("agents")) {
            DomainAgentConfig a = new DomainAgentConfig(
                    an.path("agentId").asText(),
                    session.getId(),
                    an.path("name").asText(),
                    null, null,
                    an.path("model").asText(null),
                    an.path("persona").asText(null));
            a.setPersonality(an.path("personality").asText(null));
            // 旧归档没有这几个键，asBoolean(false)/asText(null) 会安全回退，存量数据不受影响
            if (an.path("mentor").asBoolean(false)) {
                a.setMentor(true);
                a.setMentorUuid(an.path("mentorUuid").asText(null));
                a.setMentorDiscipline(an.path("mentorDiscipline").asText(null));
            }
            // 心得正文不在快照里（见 sanitizedSession 的注释），所以还原出来的席位没有记忆。
            // 这不影响归档的读路径：归档只做只读回看与「重新生成总结」，两者都不注入心得。
            a.setAgentUuid(an.path("agentUuid").asText(null));
            session.getAgents().put(a.getId(), a);
        }

        List<Question> questions = new ArrayList<>();
        for (JsonNode qn : root.path("questions")) {
            questions.add(toQuestion(qn, session.getId()));
        }
        session.getQuestionIds().addAll(questions.stream().map(Question::getId).toList());
        return new Reconstructed(session, questions);
    }

    private Question toQuestion(JsonNode qn, String sessionId) {
        Question q = new Question(qn.path("id").asText(), sessionId, qn.path("content").asText(),
                parseInstant(qn.path("createdAt").asText(null)),
                parseInstant(qn.path("updatedAt").asText(null)));

        String st = qn.path("status").asText(null);
        if (st != null) {
            try {
                q.setStatus(QuestionStatus.valueOf(st));
            } catch (IllegalArgumentException ignored) {
                // 快照里的状态名对不上当前枚举（例如旧版本归档），保持默认 THINKING
            }
        }
        if (qn.hasNonNull("finalAnswer")) q.setFinalAnswer(qn.get("finalAnswer").asText());
        if (qn.hasNonNull("explanation")) q.setExplanation(qn.get("explanation").asText());
        if (qn.hasNonNull("stageHint")) q.setStageHint(qn.get("stageHint").asText());
        if (qn.hasNonNull("professionalConsensus")) {
            q.setProfessionalConsensus(objectMapper.convertValue(
                    qn.get("professionalConsensus"), ProfessionalConsensus.class));
        }
        if (qn.hasNonNull("thoughts")) {
            Map<String, AgentThought> thoughts = objectMapper.convertValue(
                    qn.get("thoughts"), new TypeReference<Map<String, AgentThought>>() {});
            if (thoughts != null) q.getThoughts().putAll(thoughts);
        }
        if (qn.hasNonNull("transcript")) {
            List<Turn> turns = objectMapper.convertValue(
                    qn.get("transcript"), new TypeReference<List<Turn>>() {});
            if (turns != null) q.getTranscript().addAll(turns);
        }
        if (qn.hasNonNull("tokenUsage")) {
            q.addTokenUsage(objectMapper.convertValue(qn.get("tokenUsage"), TokenUsage.class));
        }
        return q;
    }

    @Transactional
    public void delete(UserEntity user, Long id) {
        repo.delete(requireOwned(user, id));
    }

    // ── 内部 ────────────────────────────────────────────────

    /**
     * 解析快照里的时间字符串（{@code Instant.toString()} 的 ISO-8601 UTC 形态）。
     *
     * <p>缺失、空白或格式非法一律返回 {@code null}，交给领域类的构造函数回退成「当前时间」——
     * 这样手改过或旧版本的归档也不会让还原直接抛异常。
     */
    private static Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(iso.trim());
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private ArchivedSessionEntity requireOwned(UserEntity user, Long id) {
        return repo.findByIdAndOwnerUserId(id, user.getId())
                .orElseThrow(() -> new AuthException("归档记录不存在或无权限"));
    }

    private Map<String, Object> listView(ArchivedSessionEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("title", e.getTitle());
        m.put("questionCount", e.getQuestionCount());
        m.put("agentCount", e.getAgentCount());
        m.put("tokenTotal", e.getTokenTotal());
        m.put("userStyle", e.getUserStyle());
        m.put("sourceSessionId", e.getSourceSessionId());
        m.put("archivedAt", e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
        return m;
    }

    private String buildPayload(Session session, List<Question> questions) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("session", sanitizedSession(session));
        // Question 本身不含任何凭据（只有 LLM 产出的文本），可以直接交给 Jackson
        root.put("questions", new ArrayList<>(questions));
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("归档失败：会话快照序列化异常", ex);
        }
    }

    /**
     * 会话元数据白名单 —— 只放这里显式列出的字段。
     *
     * <p>包级可见是为了让 {@code ArchivedSessionServiceTest} 直接断言「快照里没有 apiKey」——
     * 这是「归档快照永不落地凭据」这条硬约束唯一能被单测钉住的地方。
     */
    Map<String, Object> sanitizedSession(Session session) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", session.getId());
        s.put("createdAt", session.getCreatedAt().toString());
        s.put("userStyle", session.getUserStyle());
        s.put("autoDiscuss", session.isAutoDiscuss());
        s.put("docEditMode", session.isDocEditMode());
        s.put("maxRounds", session.getMaxRounds());
        s.put("llm", sanitizedLlm(session.getLlmEndpoint()));

        List<Map<String, Object>> agents = new ArrayList<>();
        for (DomainAgentConfig a : session.getAgents().values()) {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("agentId", a.getId());
            am.put("name", a.getName());
            am.put("model", a.getModel());
            am.put("persona", a.getPersona());
            am.put("personality", a.getPersonality());
            am.put("documentFileName", a.getDocumentFileName());
            // 导师标记只在确实是导师席位时写 —— 普通会话的 payload 因此与本改动前逐字节相同。
            // 只放「uuid + 学科」：uuid 让归档在账号级导师被删掉之后仍能说明这个席位是什么，
            // 学科是列表上认人用的；画像与论文正文属账号级数据，不进快照。
            if (a.isMentor()) {
                am.put("mentor", true);
                am.put("mentorUuid", a.getMentorUuid());
                am.put("mentorDiscipline", a.getMentorDiscipline());
            }
            // 常驻身份同样只在有值时写，保证普通会话 payload 与本改动前逐字节相同。
            // 只带 uuid，**不带心得正文** —— 心得是账号级可变数据，进快照就成了一份会过期的副本，
            // payload 也会显著膨胀。带 uuid 是为了让归档能解释「这个席位当年是哪个常驻智能体」，
            // 与 mentorUuid 进快照的动机一致。
            if (a.getAgentUuid() != null) {
                am.put("agentUuid", a.getAgentUuid());
            }
            agents.add(am);
        }
        s.put("agents", agents);
        return s;
    }

    /** 网关只留「看的」不留「用的」：label / baseUrl / model，没有 apiKey */
    private Map<String, Object> sanitizedLlm(LlmEndpoint ep) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (ep == null) {
            m.put("configured", false);
            return m;
        }
        m.put("configured", ep.hasCredentials());
        m.put("label", ep.getLabel());
        m.put("baseUrl", ep.getBaseUrl());
        m.put("model", ep.getModel());
        return m;
    }

    private Map<String, Object> readPayload(ArchivedSessionEntity e) {
        try {
            return objectMapper.readValue(e.getPayload(), new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("归档内容解析失败（payload 可能已损坏）", ex);
        }
    }

    private long sumTokens(List<Question> questions) {
        long n = 0;
        for (Question q : questions) {
            if (q.getTokenUsage() != null) {
                n += q.getTokenUsage().getTotalTokens();
            }
        }
        return n;
    }
}
