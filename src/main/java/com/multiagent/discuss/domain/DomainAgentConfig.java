package com.multiagent.discuss.domain;

import java.time.Instant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DomainAgentConfig {

    private final String id;
    private final String sessionId;
    private String name;
    private String apiKey;
    private String baseUrl;
    private String model;
    private String persona;
    /** 性格关键词（可选）：开启后影响该智能体所有发言 */
    private String personality;
    private KnowledgeStatus knowledgeStatus = KnowledgeStatus.AGENT_CREATED;
    private String statusMessage;
    private String documentFileName;
    /** Vision 进度：已完成页数（0 表示未开始） */
    private volatile int visionProgressCurrent = 0;
    /** Vision 进度：总页数（0 表示未知） */
    private volatile int visionProgressTotal = 0;
    /**
     * 是否为「导师模式」席位。这是判断导师的唯一口径 ——
     * 一个会话可以有多个导师，判别依据只有席位上的这个标记。
     */
    private boolean mentor;
    /**
     * 对应账号级常驻智能体的 uuid（不是主键 id）。与 {@link #mentorUuid} 同构：
     * 普通注册的临时席位为 null，从常驻库加入的席位才有值。
     *
     * <p>这是「跨会话积累心得」的身份键，也是「同场重复加入」去重的依据 ——
     * 后端此前完全认不出「加入的这个席位是库里哪个智能体」（席位 id 是随机生成的
     * {@code agent-xxxxxxxx}），所以这一列是必需的地基，而非可选装饰。
     *
     * <p>纯内存字段，不入库：加入会话即快照，与 persona / 页知识的口径一致。
     */
    private String agentUuid;
    /**
     * 加入会话那一刻的心得正文快照。null = 这个席位没有可带入的经验，
     * 此时 system prompt 必须与改动前**逐字节相同**（见 {@code AgentFactory.buildDomainSystem}）。
     */
    private String agentMemory;
    /**
     * 加入会话那一刻的钉子快照 —— 已经渲染成【已锁定 · 硬前提】段的文本。
     *
     * <p>刻意在这里就渲染好、而不是存一组 pin 对象：钉子是结构性硬约束，
     * 它的渲染规则只属于 {@code AgentMemoryService} 一处，席位只负责原样透传。
     */
    private String agentPins;
    /** 本场可用的账号级 Skill UUID；临时席位随会话销毁。 */
    private volatile List<String> skillUuids = List.of();
    /**
     * 快照对应的 {@code agent_memory.updated_at}。
     *
     * <p>两个用途：① 左栏卡片上「带经验上场 · 更新于 X」要显示它；
     * ② 作为 ChatClient 缓存键里的版本号 —— 纠错入口就在活会话上，
     * 用户能在讨论进行中钉一条钉子，而该席位的 system prompt 早已缓存在 ChatClient 里。
     */
    private Instant agentMemoryUpdatedAt;
    /** 对应账号级 mentor 的 uuid（不是主键 id：导师行被删后归档仍能解释这个席位）。 */
    private String mentorUuid;
    /** 导师的学科方向，列表上给用户看的，避免只显示一个称呼认不出是谁。 */
    private String mentorDiscipline;
    /** 加入会话那一刻导师库里的论文篇数。 */
    private int mentorPaperCount;
    /**
     * 加入会话那一刻的学术风格画像原文。
     *
     * <p>persona 里其实已经内嵌了这段文字（给该席位自己当 system prompt 用），但主持人看不到
     * 其他席位的 persona —— 要把「终稿需吸收导师学术风格」落到实处理解，Host 得直接读到这段画像。
     * 存在席位上而不是讨论时回查数据库：加入会话即快照，与页知识的口径一致，也免得编排层多一个依赖。
     */
    private String mentorStyleProfile;
    /**
     * 本场**成功读进内存知识库、但还没落库到常驻知识库**的文件，按成功先后排列。
     *
     * <p>它是「结束会话时该把这个席位的哪些文件存进常驻库」的唯一判据，同时兼任**幂等闸**：
     * 落库前先 {@link #drainPendingDocs()} 取走，所以「取消后重来」「摘要失败后重试」
     * 「两个标签页同时结束」都不会把同一批文件入库两遍。
     *
     * <p>为什么是「清单」而不是一个 {@code uploadedFileName} 标记 —— 那个做法有四个洞，
     * 最要命的一个是：{@code SessionService.ingestAgentDocument} 在**开头**就写
     * {@code documentFileName}，失败分支只改状态、不清它，而 {@code putPages} 在失败时
     * 根本没执行，store 里留着的还是**上一次成功的页** —— 于是「传 B 失败」会以 B 的名字
     * 把 A 的页存进库，数据错位且无人发现。清单只在完整成功的那一支追加，天然没有这个问题。
     *
     * <p>纯内存字段，不入库，**也绝不能进归档快照白名单**（它携带整份页知识）。
     * 席位销毁即随之一并消失 —— 这与「加入会话即快照」的既有口径一致。
     */
    private final List<PendingDoc> pendingDocs = new ArrayList<>();
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    public DomainAgentConfig(String id, String sessionId, String name, String apiKey,
                             String baseUrl, String model, String persona) {
        this.id = id;
        this.sessionId = sessionId;
        this.name = name;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.persona = persona;
    }

    public String getId() {
        return id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        touch();
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
        touch();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
        touch();
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
        touch();
    }

    public String getPersona() {
        return persona;
    }

    public void setPersona(String persona) {
        this.persona = persona;
        touch();
    }

    public String getPersonality() {
        return personality;
    }

    public void setPersonality(String personality) {
        this.personality = personality;
        touch();
    }

    public KnowledgeStatus getKnowledgeStatus() {
        return knowledgeStatus;
    }

    public void setKnowledgeStatus(KnowledgeStatus knowledgeStatus) {
        this.knowledgeStatus = knowledgeStatus;
        touch();
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
        touch();
    }

    public String getDocumentFileName() {
        return documentFileName;
    }

    public void setDocumentFileName(String documentFileName) {
        this.documentFileName = documentFileName;
        touch();
    }

    public int getVisionProgressCurrent() {
        return visionProgressCurrent;
    }

    public void setVisionProgressCurrent(int visionProgressCurrent) {
        this.visionProgressCurrent = visionProgressCurrent;
    }

    public int getVisionProgressTotal() {
        return visionProgressTotal;
    }

    public void setVisionProgressTotal(int visionProgressTotal) {
        this.visionProgressTotal = visionProgressTotal;
    }

    /** 进度比例 0..1，总数 0 时返回 0。 */
    public double visionProgressRatio() {
        int t = visionProgressTotal;
        if (t <= 0) return 0d;
        return Math.min(1d, Math.max(0d, (double) visionProgressCurrent / (double) t));
    }

    public boolean isMentor() {
        return mentor;
    }

    public void setMentor(boolean mentor) {
        this.mentor = mentor;
    }

    public String getMentorUuid() {
        return mentorUuid;
    }

    public void setMentorUuid(String mentorUuid) {
        this.mentorUuid = mentorUuid;
    }

    public String getAgentUuid() {
        return agentUuid;
    }

    public void setAgentUuid(String agentUuid) {
        this.agentUuid = agentUuid;
    }

    public String getAgentMemory() {
        return agentMemory;
    }

    public void setAgentMemory(String agentMemory) {
        this.agentMemory = agentMemory;
    }

    public String getAgentPins() {
        return agentPins;
    }

    public void setAgentPins(String agentPins) {
        this.agentPins = agentPins;
    }

    public List<String> getSkillUuids() {
        return skillUuids;
    }

    public void setSkillUuids(List<String> skillUuids) {
        this.skillUuids = skillUuids == null ? List.of() : List.copyOf(skillUuids);
        touch();
    }

    public Instant getAgentMemoryUpdatedAt() {
        return agentMemoryUpdatedAt;
    }

    public void setAgentMemoryUpdatedAt(Instant agentMemoryUpdatedAt) {
        this.agentMemoryUpdatedAt = agentMemoryUpdatedAt;
    }

    /**
     * 这个席位是不是带着经验上场的（正文或钉子任一非空）。
     *
     * <p>注意「空」的三种形态：null、空串、纯空白。判定必须用 hasText 口径，
     * 否则一个 {@code " "} 会让前端显示「带经验上场」却点开一片空白。
     */
    public boolean hasMemory() {
        return (agentMemory != null && !agentMemory.isBlank())
                || (agentPins != null && !agentPins.isBlank());
    }

    /** 缓存键里的版本号；没有心得时为 null（键与改动前一致）。 */
    public Long memoryVersion() {
        return agentMemoryUpdatedAt == null ? null : agentMemoryUpdatedAt.toEpochMilli();
    }

    /** 用于「经验心得」接口的目标解析：导师优先，其次常驻智能体，都没有则 null。 */
    public MemoryTargetType memoryTargetType() {
        if (mentor && mentorUuid != null) {
            return MemoryTargetType.MENTOR;
        }
        return agentUuid == null ? null : MemoryTargetType.PERSISTENT_AGENT;
    }

    /** 与 {@link #memoryTargetType()} 配对的目标 uuid。 */
    public String memoryTargetUuid() {
        MemoryTargetType t = memoryTargetType();
        if (t == null) {
            return null;
        }
        return t == MemoryTargetType.MENTOR ? mentorUuid : agentUuid;
    }

    public String getMentorDiscipline() {
        return mentorDiscipline;
    }

    public void setMentorDiscipline(String mentorDiscipline) {
        this.mentorDiscipline = mentorDiscipline;
    }

    public int getMentorPaperCount() {
        return mentorPaperCount;
    }

    public void setMentorPaperCount(int mentorPaperCount) {
        this.mentorPaperCount = mentorPaperCount;
    }

    public String getMentorStyleProfile() {
        return mentorStyleProfile;
    }

    public void setMentorStyleProfile(String mentorStyleProfile) {
        this.mentorStyleProfile = mentorStyleProfile;
    }

    /**
     * 待保存到常驻知识库的一个文件：文件名 + **落定那一刻**的页知识快照。
     *
     * <p>存快照而不是存引用：{@code AgentKnowledgeStore.putPages} 是**覆盖**语义，
     * 同一席位本场再传一个文件就会把 store 里那份换掉。若等到结束会话时才回读 store，
     * 中间那些文件会被静默丢掉 —— 而原始文件字节不入库，丢了就救不回来。
     */
    public record PendingDoc(String fileName, List<PageBlock> pages) {
        public PendingDoc {
            pages = pages == null ? List.of() : List.copyOf(pages);
        }
    }

    /** 记下一个**完整成功**读入的文件。失败路径绝不调用它。 */
    public synchronized void addPendingDoc(String fileName, List<PageBlock> pages) {
        pendingDocs.add(new PendingDoc(fileName, pages));
    }

    /**
     * 取走并清空全部待保存文件 —— 这就是幂等闸：第二次调用必然拿到空列表。
     *
     * <p>返回**不可变的拷贝**：调用方（{@code PersistentAgentDocService}）会安全地遍历它，
     * 而上传线程与「结束会话」线程是两个 HTTP 请求，直接遍历底层 {@code ArrayList}
     * 会抛 {@code ConcurrentModificationException} 或静默丢元素。
     * 不可变还挡住了「调用方往返回值里塞东西」—— 那种改动影响不到席位，反而会让
     * 调用方以为自己改成功了。
     */
    public synchronized List<PendingDoc> drainPendingDocs() {
        if (pendingDocs.isEmpty()) {
            return List.of();
        }
        List<PendingDoc> taken = new ArrayList<>(pendingDocs);
        pendingDocs.clear();
        return Collections.unmodifiableList(taken);
    }

    /** 本场是否有文件等着落库（只读探测，不摘清单）。 */
    public synchronized boolean hasPendingDocs() {
        return !pendingDocs.isEmpty();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    /** 对外展示时脱敏 API Key */
    public String maskedApiKey() {
        if (apiKey == null || apiKey.length() < 8) {
            return "***";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
