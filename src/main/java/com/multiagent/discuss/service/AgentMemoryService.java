package com.multiagent.discuss.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiagent.discuss.agent.AgentFactory;
import com.multiagent.discuss.agent.AgentMemoryPrompt;
import com.multiagent.discuss.config.AppProperties;
import com.multiagent.discuss.db.entity.AgentMemoryEntity;
import com.multiagent.discuss.db.entity.AgentMemoryPinEntity;
import com.multiagent.discuss.db.entity.MentorEntity;
import com.multiagent.discuss.db.entity.PersistentAgentEntity;
import com.multiagent.discuss.db.repo.AgentMemoryPinRepository;
import com.multiagent.discuss.db.repo.AgentMemoryRepository;
import com.multiagent.discuss.db.repo.MentorRepository;
import com.multiagent.discuss.db.repo.PersistentAgentRepository;
import com.multiagent.discuss.domain.DomainAgentConfig;
import com.multiagent.discuss.domain.LlmEndpoint;
import com.multiagent.discuss.domain.MemoryTargetType;
import com.multiagent.discuss.domain.Question;
import com.multiagent.discuss.domain.Session;
import com.multiagent.discuss.domain.Turn;
import com.multiagent.discuss.knowledge.InMemorySessionRepository;
import com.multiagent.discuss.orchestrator.DiscussionPipeline;
import com.multiagent.discuss.security.AuthContext;
import com.multiagent.discuss.security.AuthException;
import com.multiagent.discuss.security.KeyVault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 常驻智能体 / 导师的跨会话「经验心得」。
 *
 * <p>解决的原始问题：常驻智能体和导师此前是<b>无记忆</b>的 —— 下一局加入时前端只把
 * persona / personality 复制过去重新注册一个席位，后端从不读 persistent_agent 表，
 * 所以同一个专家用十场，每一场都从零开始。
 *
 * <p>三条链路：
 * <ol>
 *   <li><b>身份</b>：{@link #assertJoinable} + {@link #attachIdentity} 把「这个席位是库里哪个对象」
 *       落到 {@link DomainAgentConfig#getAgentUuid()}，并把当时的心得快照挂上去；</li>
 *   <li><b>积累</b>：{@link #summarize} 在讨论结束时把「旧心得 + 本场经历」重写成一份新的，
 *       整体覆盖（不是追加流水账）；</li>
 *   <li><b>纠错</b>：{@link #correct} / {@link #applyProposal} / {@link #unpin} ——
 *       与用户单独对话，每轮可以给一张修改方案卡片，<b>用户点了确认才落库</b>。</li>
 * </ol>
 *
 * <p><b>钉子为什么单独一张表。</b>用户确认过的纠错不进 {@code agent_memory.content}，
 * 而是落进 {@code agent_memory_pin}，每次渲染 system prompt 时实时拼成一段
 * 【已锁定 · 硬前提】。心得正文每场都要被模型整体重写，钉子混在里面就只能靠 prompt
 * 求它「别删」；拆出来独立渲染，模型想推翻也没有着力点 —— 这是<b>结构保证</b>，
 * 不是提示词保证。
 */
@Service
public class AgentMemoryService {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryService.class);

    /** 心得正文的两个骨架标签。必须原样出现在总结 prompt 与模型输出里，是解析的锚点。 */
    private static final String DOING_MARKER = AgentMemoryPrompt.DOING_MARKER;
    private static final String FINDING_MARKER = AgentMemoryPrompt.FINDING_MARKER;

    /** 单条发言截断长度：一场讨论里同一个 agent 可能有几十条 Turn，逐条全量会撑爆 prompt。 */
    private static final int TURN_CHAR_BUDGET = 800;
    /** 全场素材总预算。超了<b>从最早的发言开始丢</b> —— 讨论末段才是结论沉淀的地方。 */
    private static final int EVIDENCE_CHAR_BUDGET = 12000;
    /** 心得正文的防御性上限。产品上不设字数限制，但模型跑飞时必须有个兜底，不至于写进 4GB 的 LONGTEXT。 */
    private static final int MEMORY_HARD_CAP = 20000;
    /** 纠错对话最多带几轮历史（前端也会截，这里是服务端的第二道闸）。 */
    private static final int MAX_HISTORY_LINES = 40;

    private static final String SUMMARY_SYSTEM = """
            你在替一位智能体整理它自己的跨会话工作心得 —— 写给它自己看的，不是给别人看的报告。
            只依据给定素材写作，不补写素材里没有的经历，不编造本场讨论中不存在的结论。
            严格按要求的两个标签输出，不要 Markdown 围栏，不要 JSON。""";

    private static final String CORRECT_SYSTEM = """
            你在和用户一起审阅一份「工作心得」，这份心得会决定某个智能体以后在圆桌讨论里的行为。

            规则：
            1. 先用自然语言和用户讨论，把问题问清楚、把影响说清楚；不要急着改。
            2. 只有当确实存在一处**具体、可落地**的措辞需要修改时，才在 JSON 里给出 proposal。
               不具体、还在讨论、或用户在提问时，proposal 一律为 null。
            3. proposal 的 before 必须**逐字**取自下面给出的心得原文（能被程序原样找到并替换）；
               若该问题在原心得里没有对应措辞，before 留空字符串。
            4. proposal 的 after 是改写后的措辞，要写成心得里的口吻（第一人称），不要写成"用户要求…"。
            5. 你只能依据用户告诉你的信息判断。你看不到当时那场讨论的记录，若用户没提，
               不要假装你能查证 —— 需要上下文就问。

            只输出 JSON：
            {"reply":"给用户看的中文回复","proposal":{"before":"心得里的原文片段","after":"改写后的措辞","rationale":"为什么这么改"} }
            没有修改方案时 proposal 写 null。""";

    private final AgentMemoryRepository memoryRepo;
    private final AgentMemoryPinRepository pinRepo;
    private final PersistentAgentRepository persistentAgentRepo;
    private final MentorRepository mentorRepo;
    private final InMemorySessionRepository sessionRepo;
    private final AgentFactory agentFactory;
    private final KeyVault keyVault;
    private final AppProperties props;
    private final ObjectMapper objectMapper;

    public AgentMemoryService(AgentMemoryRepository memoryRepo,
                              AgentMemoryPinRepository pinRepo,
                              PersistentAgentRepository persistentAgentRepo,
                              MentorRepository mentorRepo,
                              InMemorySessionRepository sessionRepo,
                              AgentFactory agentFactory,
                              KeyVault keyVault,
                              AppProperties props,
                              ObjectMapper objectMapper) {
        this.memoryRepo = memoryRepo;
        this.pinRepo = pinRepo;
        this.persistentAgentRepo = persistentAgentRepo;
        this.mentorRepo = mentorRepo;
        this.sessionRepo = sessionRepo;
        this.agentFactory = agentFactory;
        this.keyVault = keyVault;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    // ── 加入会话：身份校验 + 心得快照 ──────────────────────────

    /**
     * 注册席位<b>之前</b>调：校验这个账号级对象属于当前用户，并确认它还没在本场里。
     *
     * <p>放在注册之前是有意为之 —— 校验和去重都要抛错，若先注册再校验，失败时会在会话里
     * 留下一个挂着 uuid 的悬空席位，用户看到的是「报错了但列表里多了一个智能体」。
     *
     * <p>{@code uuid} 为空是正常路径（普通注册），直接放行。
     *
     * @return 该对象的展示名，供调用方复用；uuid 为空时返回 null
     */
    public String assertJoinable(String sessionId, MemoryTargetType type, String uuid) {
        if (type == null || !StringUtils.hasText(uuid)) {
            return null;
        }
        Long ownerUserId = AuthContext.currentUserIdOrNull();
        if (ownerUserId == null) {
            // 没登录却拿着一个 uuid 来加入常驻对象 —— 只能是伪造或过期登录态
            throw new AuthException(label(type) + "不存在或无权限");
        }
        String name = requireOwnedName(type, uuid, ownerUserId);

        Session session = sessionRepo.requireSession(sessionId);
        boolean joined = session.getAgents().values().stream()
                .anyMatch(a -> uuid.equals(a.memoryTargetUuid()));
        if (joined) {
            // 同一个常驻对象在一场里占两个席位，会让两个席位写同一份心得互相覆盖
            throw new IllegalStateException("该" + label(type) + "已经在本场讨论中了");
        }
        return name;
    }

    /**
     * 「保存为常驻之后」把身份补挂到一个已有席位上。
     *
     * <p>这个端点存在的唯一理由是<b>续跑洞</b>：用户可以在总结弹窗点「取消」（会话保留），
     * 而「本次新保存的智能体 ↔ 席位」这层对应只活在前端 state 里，第二次点「结束会话」时
     * 已经拿不到了，那几个席位就永远总结不了。把身份<b>落回席位</b>之后，
     * 「待总结目标」恒等于「所有挂着 uuid 的席位」，第二轮第三轮都自洽。
     *
     * <p>与 {@link #assertJoinable} 的差别：这里允许「同一个席位重复挂同一个 uuid」
     * （幂等，重试友好），但拒绝<b>别的</b>席位挂上同一个 uuid（那才会互相覆盖心得）。
     */
    public DomainAgentConfig linkSeat(String sessionId, String agentId, String uuid) {
        Session session = sessionRepo.requireSession(sessionId);
        DomainAgentConfig seat = session.getAgents().get(agentId);
        if (seat == null) {
            throw new IllegalArgumentException("智能体不存在: " + agentId);
        }
        if (!StringUtils.hasText(uuid)) {
            throw new IllegalArgumentException("agentUuid 不能为空");
        }
        String clean = uuid.trim();
        Long ownerUserId = AuthContext.currentUserIdOrNull();
        if (ownerUserId == null) {
            throw new AuthException("常驻智能体不存在或无权限");
        }
        requireOwnedName(MemoryTargetType.PERSISTENT_AGENT, clean, ownerUserId);
        for (DomainAgentConfig other : session.getAgents().values()) {
            if (other != seat && clean.equals(other.memoryTargetUuid())) {
                throw new IllegalStateException("该常驻智能体已经在本场讨论中了");
            }
        }
        attachIdentity(seat, MemoryTargetType.PERSISTENT_AGENT, clean);
        log.info("席位补挂常驻身份 session={} agent={} uuid={}", sessionId, agentId, clean);
        return seat;
    }

    /**
     * 席位注册<b>之后</b>调：把心得快照挂到席位上。
     *
     * <p>挂的是<b>快照</b>而不是活引用，与会话网关、导师页知识的口径一致：加入那一刻是什么
     * 就是什么，本场讨论中途该对象在库里被改，不会回流到已经在跑的会话。
     * （例外是纠错确认，那条路径会显式刷新活席位并踢掉 ChatClient 缓存。）
     */
    public void attachIdentity(DomainAgentConfig seat, MemoryTargetType type, String uuid) {
        if (seat == null || type == null || !StringUtils.hasText(uuid)) {
            return;
        }
        if (type == MemoryTargetType.PERSISTENT_AGENT) {
            seat.setAgentUuid(uuid);
        }
        Snapshot snap = loadSnapshot(type, uuid);
        seat.setAgentMemory(snap.content());
        seat.setAgentPins(snap.pins());
        seat.setAgentMemoryUpdatedAt(snap.updatedAt());
        if (snap.hasAny()) {
            log.info("席位带上经验 session={} agent={} type={} chars={} pins={}",
                    seat.getSessionId(), seat.getId(), type, lengthOf(snap.content()), lengthOf(snap.pins()));
        }
    }

    // ── 总结 ─────────────────────────────────────────────────

    /**
     * 让某个席位回顾本场讨论，把「旧心得 + 本场经历」重写成一份新心得，整体覆盖。
     *
     * <p>目标（是常驻智能体还是导师、uuid 是多少）<b>一律从席位推导</b>，不接受前端传入：
     * 少一个可以被构造成任意 uuid 的入参，前端进度条的目标集合与后端判定口径也天然一致。
     *
     * <p>解析失败<b>绝不写库</b> —— 宁可这一格标红让用户重试，也不能用一段垃圾覆盖掉
     * 之前攒下的旧心得。
     */
    public Map<String, Object> summarize(String sessionId, String agentId) {
        Session session = sessionRepo.requireSession(sessionId);
        DomainAgentConfig seat = session.getAgents().get(agentId);
        if (seat == null) {
            throw new IllegalArgumentException("智能体不存在: " + agentId);
        }
        MemoryTargetType type = seat.memoryTargetType();
        String uuid = seat.memoryTargetUuid();
        if (type == null || uuid == null) {
            throw new IllegalArgumentException("该席位不是常驻智能体或导师，没有可积累的经验");
        }
        if (session.getOwnerUserId() == null) {
            // 匿名会话没有账号，心得无处归属。前端本就跳过，这里是后端的兜底。
            throw new AuthException("会话未登录，经验无处归属");
        }
        // 席位上的 uuid 可能在讨论期间被用户从库里删掉了；此时不能拿旧快照硬写，
        // 否则会凭空复活一条指向已删对象的记忆
        requireOwnedName(type, uuid, session.getOwnerUserId());

        String evidence = collectEvidence(session, agentId);
        if (!StringUtils.hasText(evidence)) {
            throw new IllegalStateException("这位智能体在本场还没有发言，没有可总结的经验");
        }

        String oldContent = loadSnapshot(type, uuid).content();
        List<AgentMemoryPinEntity> pins = pinRepo
                .findAllByTargetTypeAndTargetUuidOrderByCreatedAtAsc(type.name(), uuid);
        String pinsText = StringUtils.hasText(renderPins(pins)) ? renderPins(pins) : "（暂无）";

        ChatClient client = agentFactory.taskClient(seat, SUMMARY_SYSTEM);
        String raw = client.prompt().user("""
                %s
                你在过去多场讨论中积累了下面这份心得。这场讨论刚结束，
                请把【已有心得】与【本次讨论素材】合并，重写一份更精炼的心得。

                必须原样保留下面两个标签，按此顺序输出，不要 Markdown 围栏，不要 JSON：
                %s关于我在这个圆桌里怎么干活 —— 我习惯先看什么、怎么组织发言、什么时候该收敛。
                %s我攒下的判断 —— 我在本领域反复验证过、对同类问题有效的判断及其边界。

                硬要求：
                1. 精简优先，只留精华。旧条目若没有本次新印证，可以压缩合并甚至删除，不要机械堆砌。
                2. 【已锁定】里的每一条都是用户明确纠正过的硬前提：不得删除、不得推翻、不得与之矛盾。
                3. 只写经验与判断，不复述本次讨论的具体内容与文献细节。
                4. 允许在两段内少量自由发挥，但不得新增第三个标签、不得输出标签以外的段落。
                5. 用第一人称写给自己，不要写成给别人的报告。

                【已锁定】（不可推翻，必须与之保持一致）
                %s

                【已有心得】（首次为空）
                %s

                【本次讨论素材】（你在本场的发言与议题结论）
                %s
                """.formatted(SUMMARY_SYSTEM, DOING_MARKER, FINDING_MARKER, pinsText,
                StringUtils.hasText(oldContent) ? oldContent : "（暂无，这是你的第一份心得）",
                evidence)).call().content();

        String content = parseMemory(raw);
        AgentMemoryEntity row = upsert(type, uuid, session.getOwnerUserId(), content);
        // 本场席位立刻用上新心得：用户若接着继续讨论，这位智能体的下一句话就该带着新经验
        seat.setAgentMemory(content);
        seat.setAgentMemoryUpdatedAt(row.getUpdatedAt());
        agentFactory.evictSession(sessionId);
        log.info("经验总结完成 session={} agent={} type={} uuid={} chars={} pins={}",
                sessionId, agentId, type, uuid, content.length(), pins.size());
        return memoryView(type, uuid, content, row.getUpdatedAt(), pins);
    }

    // ── 查看 / 纠错 ───────────────────────────────────────────

    public Map<String, Object> view(Long userId, MemoryTargetType type, String uuid) {
        String name = requireOwnedName(type, uuid, userId);
        Snapshot snap = loadSnapshot(type, uuid);
        List<AgentMemoryPinEntity> pins = pinRepo
                .findAllByTargetTypeAndTargetUuidOrderByCreatedAtAsc(type.name(), uuid);
        Map<String, Object> m = memoryView(type, uuid, snap.content(), snap.updatedAt(), pins);
        m.put("name", name);
        return m;
    }

    /**
     * 纠错对话的一轮：把当前心得全文 + 已锁定项 + 对话历史交给模型，让它边聊边（可选地）给方案。
     *
     * <p>刻意<b>不落库</b>：关掉弹窗这段对话就没了。持久产物是用户点过确认的那条钉子。
     *
     * @param sessionIdOrNull 可选。仅当这个账号级对象**自己没有网关**时才会用到 ——
     *                        那时借这场会话的网关。导师与 2.4.0 起保存过的常驻智能体都自带，
     *                        所以通常可以不传。调用方必须已对该会话做过 {@code requireAccess}。
     */
    public Map<String, Object> correct(Long userId, MemoryTargetType type, String uuid,
                                       String sessionIdOrNull, String message, List<String> historyLines) {
        if (!StringUtils.hasText(message)) {
            throw new IllegalArgumentException("请先输入要纠正的内容");
        }
        requireOwnedName(type, uuid, userId);
        Session session = StringUtils.hasText(sessionIdOrNull)
                ? sessionRepo.findSession(sessionIdOrNull).orElse(null)
                : null;

        AgentMemoryEntity row = memoryRepo.findByTargetTypeAndTargetUuid(type.name(), uuid).orElse(null);
        String content = row == null ? null : trimToNull(row.getContent());
        List<AgentMemoryPinEntity> pins = pinRepo
                .findAllByTargetTypeAndTargetUuidOrderByCreatedAtAsc(type.name(), uuid);

        ChatClient client = correctionClient(type, uuid, userId, session);

        StringBuilder sb = new StringBuilder();
        sb.append("【当前心得全文】\n")
                .append(StringUtils.hasText(content) ? content : "（这位智能体还没有任何心得）").append("\n\n");
        sb.append("【已锁定 · 硬前提】（用户之前确认过的纠正，同样不可推翻）\n")
                .append(pins.isEmpty() ? "（暂无）" : renderPins(pins)).append("\n\n");
        if (historyLines != null && !historyLines.isEmpty()) {
            sb.append("【对话历史】\n");
            int from = Math.max(0, historyLines.size() - MAX_HISTORY_LINES);
            for (String line : historyLines.subList(from, historyLines.size())) {
                sb.append(line).append('\n');
            }
            sb.append('\n');
        }
        sb.append("【用户这一轮说】\n").append(message.trim()).append('\n');
        sb.append("\n注意：你看不到这场讨论的原始记录，只能依据用户告诉你的信息判断。\n");

        String raw = client.prompt().user(sb.toString()).call().content();
        return parseCorrection(raw);
    }

    /**
     * 落地一张修改方案卡片：用户点了「确认」才会走到这里。
     *
     * <p>顺序有讲究 —— <b>无论 content 补丁成不成功，钉子都要插</b>：
     * 补丁是尽力而为（模型给的 before 未必能在原文里逐字找到），而钉子才是权威约束。
     * 补丁失败时把 after 追加到心得末尾，保证用户在正文里也看得见这次纠正。
     */
    @Transactional
    public Map<String, Object> applyProposal(Long userId, MemoryTargetType type, String uuid,
                                             String before, String after, String rationale) {
        requireOwnedName(type, uuid, userId);
        String cleanAfter = trimToNull(after);
        if (!StringUtils.hasText(cleanAfter)) {
            throw new IllegalArgumentException("修改方案内容为空，无法应用");
        }

        AgentMemoryEntity row = memoryRepo.findByTargetTypeAndTargetUuid(type.name(), uuid).orElse(null);
        String content = row == null ? null : trimToNull(row.getContent());
        if (StringUtils.hasText(content)) {
            String patched = patch(content, before, cleanAfter);
            if (row == null) {
                row = newRow(type, uuid, userId);
            }
            row.setContent(patched);
            row.setUpdatedAt(Instant.now());
            row = memoryRepo.save(row);
        } else if (row == null) {
            // 还没有心得就被纠错了：先建一行，正文就是这次纠正本身
            row = newRow(type, uuid, userId);
            row.setContent(cleanAfter);
            row.setUpdatedAt(Instant.now());
            row = memoryRepo.save(row);
        }

        AgentMemoryPinEntity pin = new AgentMemoryPinEntity();
        pin.setOwnerUserId(userId);
        pin.setTargetType(type.name());
        pin.setTargetUuid(uuid);
        pin.setContent(cleanAfter);
        pinRepo.save(pin);
        log.info("纠错已落地 type={} uuid={} pinId={} rationale={}", type, uuid, pin.getId(), shorten(rationale, 200));

        refreshLiveSeats(userId, type, uuid);
        return view(userId, type, uuid);
    }

    /** 拔钉子：解开会话里所有持有该对象的席位，下次重建 prompt 时它就不在硬前提里了。 */
    @Transactional
    public void unpin(Long userId, Long pinId) {
        AgentMemoryPinEntity pin = pinRepo.findByIdAndOwnerUserId(pinId, userId)
                .orElseThrow(() -> new AuthException("该锁定项不存在或无权限"));
        MemoryTargetType type = MemoryTargetType.parse(pin.getTargetType());
        String uuid = pin.getTargetUuid();
        pinRepo.delete(pin);
        log.info("解除锁定 pinId={} type={} uuid={}", pinId, type, uuid);
        refreshLiveSeats(userId, type, uuid);
    }

    /** 删常驻智能体 / 导师时的级联清理。target_uuid 建不了外键，不显式清就是孤儿行。 */
    @Transactional
    public void deleteAllFor(MemoryTargetType type, String uuid) {
        if (type == null || !StringUtils.hasText(uuid)) {
            return;
        }
        memoryRepo.deleteAllByTargetTypeAndTargetUuid(type.name(), uuid);
        pinRepo.deleteAllByTargetTypeAndTargetUuid(type.name(), uuid);
        log.info("清理经验数据 type={} uuid={}", type, uuid);
    }

    // ── prompt 注入（供 AgentFactory 用） ─────────────────────

    /**
     * 把钉子渲染成 {@code - xxx} 的行。空列表返回空串。
     *
     * <p>渲染好的这段文本会被快照到席位上（{@code DomainAgentConfig.agentPins}），
     * 再由 {@link AgentMemoryPrompt#memoryBlock} 包上【已锁定 · 硬前提】的外壳。
     * 拆成两步是为了让「哪些字是钉子」在服务端一处可查 —— 排查线上问题时能一眼分辨
     * 模型看到的硬前提，到底来自哪几条用户确认。
     */
    static String renderPins(List<AgentMemoryPinEntity> pins) {
        if (pins == null || pins.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (AgentMemoryPinEntity p : pins) {
            String c = trimToNull(p.getContent());
            if (c != null) {
                sb.append("- ").append(c.replace("\n", " ")).append('\n');
            }
        }
        return sb.toString().trim();
    }

    // ── 解析 ─────────────────────────────────────────────────

    /**
     * 解析总结输出：必须同时命中 {@code 【做法】} 与 {@code 【结论】} 两个标签。
     *
     * <p>比 {@code MentorService.rebuildProfile} 的「切不出来就原样存」严格得多，
     * 这是有意的：导师画像的首份值是空的，存一段原始文本不算破坏；而心得是<b>整体覆盖写</b>的，
     * 一次解析失败就会把之前攒了几场的心得整份冲掉。宁可 409 让前端标红重试。
     */
    static String parseMemory(String raw) {
        String normalized = DiscussionPipeline.stripFencePublic(raw);
        if (!StringUtils.hasText(normalized)) {
            throw new IllegalStateException("模型没有返回内容，本次经验未保存");
        }
        int doing = normalized.indexOf(DOING_MARKER);
        int finding = normalized.indexOf(FINDING_MARKER);
        if (doing < 0 || finding < 0 || finding < doing) {
            throw new IllegalStateException("模型输出缺少【做法】/【结论】标签，本次经验未保存");
        }
        String doingText = normalized.substring(doing + DOING_MARKER.length(), finding).trim();
        String findingText = normalized.substring(finding + FINDING_MARKER.length()).trim();
        if (doingText.isEmpty() && findingText.isEmpty()) {
            throw new IllegalStateException("模型输出的两段都是空的，本次经验未保存");
        }
        String content = DOING_MARKER + "\n" + doingText + "\n\n" + FINDING_MARKER + "\n" + findingText;
        if (content.length() > MEMORY_HARD_CAP) {
            log.warn("心得超长已截断 chars={} cap={}", content.length(), MEMORY_HARD_CAP);
            content = content.substring(0, MEMORY_HARD_CAP) + "\n…（超长截断）";
        }
        return content;
    }

    /** 解析纠错对话输出：{@code reply} 必填，{@code proposal} 可空。 */
    private Map<String, Object> parseCorrection(String raw) {
        Map<String, Object> m = new LinkedHashMap<>();
        String cleaned = DiscussionPipeline.stripFencePublic(raw);
        JsonNode n = null;
        try {
            n = objectMapper.readTree(cleaned);
        } catch (Exception e) {
            log.warn("纠错回复 JSON 解析失败: {}", e.toString());
        }
        if (n == null || !n.isObject()) {
            // 模型没按 JSON 回，就把原文当回复给用户看 —— 对话还能继续，
            // 只是这一轮没有方案卡片。比直接报错好。
            m.put("reply", StringUtils.hasText(cleaned) ? cleaned : "（模型没有返回内容）");
            m.put("proposal", null);
            return m;
        }
        m.put("reply", text(n, "reply"));
        JsonNode p = n.get("proposal");
        if (p != null && p.isObject()) {
            Map<String, Object> proposal = new LinkedHashMap<>();
            proposal.put("before", text(p, "before"));
            proposal.put("after", text(p, "after"));
            proposal.put("rationale", text(p, "rationale"));
            m.put("proposal", StringUtils.hasText((String) proposal.get("after")) ? proposal : null);
        } else {
            m.put("proposal", null);
        }
        return m;
    }

    // ── 内部：素材收集 ───────────────────────────────────────

    /**
     * 收集这位智能体在本场的发言 + 各议题的结论。
     *
     * <p>预算超了就<b>从最早的议题开始丢</b>并标注截断 —— 讨论末段才是结论沉淀的地方，
     * 开场那几轮通常是试探性的、信息量最低。
     */
    private String collectEvidence(Session session, String agentId) {
        List<String> chunks = new ArrayList<>();
        for (String qid : session.getQuestionIds()) {
            Question q = sessionRepo.findQuestion(qid).orElse(null);
            if (q == null) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("—— 议题：").append(oneLine(q.getContent())).append('\n');
            int spoken = 0;
            for (Turn t : q.getTranscript()) {
                if (!agentId.equals(t.getSpeakerId()) || !StringUtils.hasText(t.getContent())) {
                    continue;
                }
                sb.append("  · ").append(shorten(t.getContent().replaceAll("\\s+", " ").trim(), TURN_CHAR_BUDGET))
                        .append('\n');
                spoken++;
            }
            if (spoken == 0) {
                continue;
            }
            if (StringUtils.hasText(q.getFinalAnswer())) {
                sb.append("  议题结论：").append(shorten(oneLine(q.getFinalAnswer()), TURN_CHAR_BUDGET)).append('\n');
            }
            chunks.add(sb.toString());
        }
        if (chunks.isEmpty()) {
            return "";
        }
        StringBuilder all = new StringBuilder();
        boolean truncated = false;
        for (String c : chunks) {
            if (all.length() + c.length() > EVIDENCE_CHAR_BUDGET) {
                truncated = true;
                break;
            }
            all.append(c).append('\n');
        }
        if (all.length() == 0) {
            // 最后一并超预算：至少留下最后一并，否则等于什么都没给模型
            all.append(chunks.get(chunks.size() - 1));
            truncated = true;
        }
        if (truncated) {
            all.insert(0, "（本次讨论较早的发言已截断）\n");
        }
        return all.toString().trim();
    }

    // ── 内部：持久化 ─────────────────────────────────────────

    private AgentMemoryEntity newRow(MemoryTargetType type, String uuid, Long ownerUserId) {
        AgentMemoryEntity row = new AgentMemoryEntity();
        row.setOwnerUserId(ownerUserId);
        row.setTargetType(type.name());
        row.setTargetUuid(uuid);
        return row;
    }

    private AgentMemoryEntity upsert(MemoryTargetType type, String uuid, Long ownerUserId, String content) {
        AgentMemoryEntity row = memoryRepo.findByTargetTypeAndTargetUuid(type.name(), uuid)
                .orElseGet(() -> newRow(type, uuid, ownerUserId));
        row.setContent(content);
        // 本表唯一由 Java 维护的时间戳列，见 AgentMemoryEntity 的类注释：别删这行
        row.setUpdatedAt(Instant.now());
        return memoryRepo.save(row);
    }

    private Snapshot loadSnapshot(MemoryTargetType type, String uuid) {
        Optional<AgentMemoryEntity> row = memoryRepo.findByTargetTypeAndTargetUuid(type.name(), uuid);
        String content = row.map(r -> trimToNull(r.getContent())).orElse(null);
        Instant updatedAt = row.map(AgentMemoryEntity::getUpdatedAt).orElse(null);
        List<AgentMemoryPinEntity> pins = pinRepo
                .findAllByTargetTypeAndTargetUuidOrderByCreatedAtAsc(type.name(), uuid);
        String pinsText = trimToNull(renderPins(pins));
        return new Snapshot(content, pinsText, updatedAt);
    }

    private Map<String, Object> memoryView(MemoryTargetType type, String uuid, String content,
                                           Instant updatedAt, List<AgentMemoryPinEntity> pins) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("targetType", type.name());
        m.put("targetUuid", uuid);
        m.put("content", content);
        m.put("updatedAt", updatedAt == null ? null : updatedAt.toEpochMilli());
        List<Map<String, Object>> pinViews = new ArrayList<>();
        if (pins != null) {
            for (AgentMemoryPinEntity p : pins) {
                Map<String, Object> pv = new LinkedHashMap<>();
                pv.put("id", p.getId());
                pv.put("content", p.getContent());
                pv.put("createdAt", p.getCreatedAt() == null ? null : p.getCreatedAt().toEpochMilli());
                pinViews.add(pv);
            }
        }
        m.put("pins", pinViews);
        return m;
    }

    /**
     * 归属校验：这个 uuid 必须是该账号名下真实存在的常驻智能体 / 导师。
     * 返回展示名（导师用称呼；常驻智能体优先用用户改过的自定义名）。
     */
    private String requireOwnedName(MemoryTargetType type, String uuid, Long userId) {
        if (type == MemoryTargetType.MENTOR) {
            return mentorRepo.findByMentorUuidAndOwnerUserId(uuid, userId)
                    .map(MentorEntity::getName)
                    .orElseThrow(() -> new AuthException("导师不存在或无权限"));
        }
        return persistentAgentRepo.findByAgentUuidAndOwnerUserId(uuid, userId)
                .map(AgentMemoryService::displayName)
                .orElseThrow(() -> new AuthException("常驻智能体不存在或无权限"));
    }

    private static String displayName(PersistentAgentEntity e) {
        return StringUtils.hasText(e.getCustomName()) ? e.getCustomName() : e.getAgentName();
    }

    /**
     * 纠错确认后刷新活会话：把持有该对象的席位快照换新，并踢掉该会话的 ChatClient 缓存。
     *
     * <p>两件事缺一不可 —— 换快照是正确性（下次发言的 system prompt 才带新钉子），
     * 踢缓存是让新 prompt 真的生效（{@code AgentFactory} 按会话粒度缓存 ChatClient）。
     * 缓存键里也有版本号兜底，但那只防「忘了刷快照」，防不了「刷了快照却复用旧客户端」。
     */
    private void refreshLiveSeats(Long ownerUserId, MemoryTargetType type, String uuid) {
        Snapshot snap = loadSnapshot(type, uuid);
        for (Session s : sessionRepo.findAll()) {
            if (!ownerUserId.equals(s.getOwnerUserId())) {
                continue;
            }
            boolean touched = false;
            for (DomainAgentConfig seat : s.getAgents().values()) {
                if (type == seat.memoryTargetType() && uuid.equals(seat.memoryTargetUuid())) {
                    seat.setAgentMemory(snap.content());
                    seat.setAgentPins(snap.pins());
                    seat.setAgentMemoryUpdatedAt(snap.updatedAt());
                    touched = true;
                }
            }
            if (touched) {
                agentFactory.evictSession(s.getId());
                log.info("纠错后刷新活席位 session={} type={} uuid={}", s.getId(), type, uuid);
            }
        }
    }

    // ── 内部：网关 ───────────────────────────────────────────

    /**
     * 纠错对话用的客户端。解析顺序：
     * <b>账号级对象自己的 Key（导师 或 常驻智能体）→ 指定会话的网关 → 服务端默认</b>。
     *
     * <p>与 {@code MentorService.analysisClient} 同构。到 2.4.0 为止两种账号级对象都能自带网关
     * （常驻智能体的网关是「保存为常驻」那一刻从席位快照下来的，jasypt 加密入库），
     * 所以这里两个分支并列；都没有才借会话网关或服务端默认。
     *
     */
    private ChatClient correctionClient(MemoryTargetType type, String uuid, Long userId, Session sessionOrNull) {
        // ① 账号级对象自带的 Key 优先 —— 导师与常驻智能体都可能有
        String ownKey = null;
        String ownUrl = null;
        String ownModel = null;
        if (type == MemoryTargetType.MENTOR) {
            MentorEntity m = mentorRepo.findByMentorUuidAndOwnerUserId(uuid, userId).orElse(null);
            if (m != null) {
                ownKey = safeDecrypt(m.getApiKeyEnc());
                ownUrl = m.getBaseUrl();
                ownModel = m.getModel();
            }
        } else {
            PersistentAgentEntity p =
                    persistentAgentRepo.findByAgentUuidAndOwnerUserId(uuid, userId).orElse(null);
            if (p != null) {
                ownKey = safeDecrypt(p.getApiKeyEnc());
                ownUrl = p.getBaseUrl();
                ownModel = p.getModel();
            }
        }
        if (StringUtils.hasText(ownKey)) {
            return agentFactory.openAiCompatible(ownKey, ownUrl,
                    StringUtils.hasText(ownModel) ? ownModel : "gpt-4o-mini", CORRECT_SYSTEM);
        }
        // ② 会话网关。刻意不缓存：缓存键只能拿明文 Key 或它的弱哈希，前者把密钥留在堆里的 map 键上，
        //    后者可能撞键用错网关；一次纠错只调一轮，现建客户端的开销可以接受。
        LlmEndpoint ep = sessionOrNull == null ? null : sessionOrNull.getLlmEndpoint();
        if (ep != null && ep.hasCredentials()) {
            return agentFactory.openAiCompatible(ep.getApiKey(), ep.getBaseUrl(),
                    StringUtils.hasText(ep.getModel()) ? ep.getModel() : "gpt-4o-mini", CORRECT_SYSTEM);
        }
        // ③ 服务端默认
        return agentFactory.hostClient();
    }

    /** 解不开就返回 null —— 换过 master password 的历史行不该让纠错对话直接 500。 */
    private String safeDecrypt(String enc) {
        if (!StringUtils.hasText(enc)) {
            return null;
        }
        try {
            return keyVault.decrypt(enc);
        } catch (Exception ex) {
            log.warn("账号级对象网关解密失败（master password 换过？）: {}", ex.toString());
            return null;
        }
    }

    // ── 内部：杂项 ───────────────────────────────────────────

    /**
     * 尽力把心得正文里的 {@code before} 换成 {@code after}。
     * 找不到就追加到末尾 —— 补丁失败不该让这次纠错白费，用户确认过的意思必须落地。
     */
    private static String patch(String content, String before, String after) {
        String b = trimToNull(before);
        if (b != null && content.contains(b)) {
            int i = content.indexOf(b);
            return content.substring(0, i) + after + content.substring(i + b.length());
        }
        return content + "\n\n（用户纠正）" + after;
    }

    private static String label(MemoryTargetType type) {
        return type == MemoryTargetType.MENTOR ? "导师" : "常驻智能体";
    }

    private static String text(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) {
            return null;
        }
        String v = n.get(field).asText();
        return StringUtils.hasText(v) ? v.trim() : null;
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static int lengthOf(String s) {
        return s == null ? 0 : s.length();
    }

    /** 一次心得读取的快照：正文 / 钉子文本 / 更新时间。 */
    private record Snapshot(String content, String pins, Instant updatedAt) {
        boolean hasAny() {
            return content != null || pins != null;
        }
    }
}
