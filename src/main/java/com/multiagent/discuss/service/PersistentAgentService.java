package com.multiagent.discuss.service;

import com.multiagent.discuss.db.entity.PersistentAgentEntity;
import com.multiagent.discuss.db.entity.UserEntity;
import com.multiagent.discuss.db.repo.PersistentAgentRepository;
import com.multiagent.discuss.domain.DomainAgentConfig;
import com.multiagent.discuss.domain.KnowledgeStatus;
import com.multiagent.discuss.domain.MemoryTargetType;
import com.multiagent.discuss.domain.PageBlock;
import com.multiagent.discuss.knowledge.AgentKnowledgeStore;
import com.multiagent.discuss.security.AuthException;
import com.multiagent.discuss.security.KeyVault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 常驻智能体 CRUD。所有操作都强制 {@code owner_user_id = currentUser.id}。
 *
 * <p><b>网关（apiKey / baseUrl / model）加密入库</b>：它自 0.9.0 起存 jasypt {@code ENC(...)} 密文，
 * 与导师、LLM 预设同款。密文就落在 {@code persistent_agent} 行上，而该表按 {@code owner_user_id}
 * 隔离，所以凭据**跟随账号**：列表只回本人行、{@code reveal} 走 {@code requireOwned}、
 * 跨账号一律 401，库里没有跨账号共享的 Key。
 *
 * <p>保存时机是「保存为常驻」那一刻，直接从**源席位**取它当时在用的网关 —— 不新增填写入口。
 * 代价需知悉：席位若走的是会话网关，存下来的就是**会话 Key 的一份副本**，
 * 之后轮换会话 Key 不会同步过来（可在常驻列表里改）。
 *
 * <p>返回给前端的一律是<b>掩码视图</b>（{@link KeyVault#mask}）：{@code create} / {@code update}
 * 也不例外，因为前端会把返回值直接塞进列表 state 与界面。明文只走 {@code reveal}，仅 owner。
 */
@Service
public class PersistentAgentService {

    private static final Logger log = LoggerFactory.getLogger(PersistentAgentService.class);

    private final PersistentAgentRepository repo;
    private final AgentMemoryService agentMemoryService;
    private final SessionService sessionService;
    private final KeyVault keyVault;
    /** 账号级知识库。单向依赖：docService 只认 repo，不回头认本类，所以不成环。 */
    private final PersistentAgentDocService docService;
    private final AgentKnowledgeStore knowledgeStore;
    private final SkillBindingService skillBindingService;

    @Autowired
    public PersistentAgentService(PersistentAgentRepository repo,
                                  AgentMemoryService agentMemoryService,
                                  SessionService sessionService,
                                  KeyVault keyVault,
                                  PersistentAgentDocService docService,
                                  AgentKnowledgeStore knowledgeStore,
                                  SkillBindingService skillBindingService) {
        this.repo = repo;
        this.agentMemoryService = agentMemoryService;
        this.sessionService = sessionService;
        this.keyVault = keyVault;
        this.docService = docService;
        this.knowledgeStore = knowledgeStore;
        this.skillBindingService = skillBindingService;
    }

    PersistentAgentService(PersistentAgentRepository repo,
                           AgentMemoryService agentMemoryService,
                           SessionService sessionService,
                           KeyVault keyVault,
                           PersistentAgentDocService docService,
                           AgentKnowledgeStore knowledgeStore) {
        this(repo, agentMemoryService, sessionService, keyVault, docService, knowledgeStore, null);
    }

    /**
     * 列表。附带每个常驻的知识规模（{@code docCount} / {@code pageCount}），卡片上的
     * 「N 个文件 · M 页」直接用，不必为每个 agent 再打一次接口。
     */
    public List<Map<String, Object>> list(UserEntity user) {
        List<PersistentAgentEntity> rows = repo.findAllByOwnerUserIdOrderByCreatedAtAsc(user.getId());
        // 一次聚合查回来，不是 N+1。没有任何知识的 agent 不出现在结果里，用 EMPTY 兜底。
        Map<Long, PersistentAgentDocService.DocCounts> counts = docService.countsByAgentIds(
                rows.stream().map(PersistentAgentEntity::getId).toList());
        return rows.stream().map(e -> {
            Map<String, Object> v = view(e);
            PersistentAgentDocService.DocCounts c =
                    counts.getOrDefault(e.getId(), PersistentAgentDocService.DocCounts.EMPTY);
            v.put("docCount", c.docCount());
            v.put("pageCount", c.pageCount());
            return v;
        }).toList();
    }

    /**
     * 新建常驻智能体。
     *
     * <p>给了 {@code sourceSessionId + sourceAgentId} 时，**从那个席位复制它当前生效的网关**
     * 并加密入库（见 {@link SessionService#seatGateway}）；否则只存名片（老行为）。
     *
     * @return 掩码视图，另带一个 {@code gatewaySaved} 布尔 —— 前端据此提示「这个没存下网关」。
     *         席位取不到时**降级而不抛**：保存是批量循环，为一个边角席位把这一轮已成功的
     *         其它智能体一起回滚是更坏的结果。
     */
    @Transactional
    public Map<String, Object> create(UserEntity user,
                                      String name, String customName,
                                      String baseUrl,
                                      String model, String persona,
                                      String personality,
                                      String sourceSessionId, String sourceAgentId) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("name 不能为空");
        }

        PersistentAgentEntity e = new PersistentAgentEntity();
        e.setOwnerUserId(user.getId());
        e.setAgentUuid(UUID.randomUUID().toString());
        e.setAgentName(name.trim());
        e.setCustomName(emptyToNull(customName));
        e.setPersona(emptyToNull(persona));
        e.setPersonality(emptyToNull(personality));

        Optional<SessionService.SeatGateway> gw =
                (StringUtils.hasText(sourceSessionId) && StringUtils.hasText(sourceAgentId))
                        ? sessionService.seatGateway(sourceSessionId.trim(), sourceAgentId.trim())
                        : Optional.empty();

        String key = gw.map(SessionService.SeatGateway::apiKey).orElse(null);
        if (key != null) {
            try {
                // ⚠️ 加密失败宁可降级成「没存网关」，**绝不落明文**
                e.setApiKeyEnc(keyVault.encrypt(key));
                e.setBaseUrl(gw.get().baseUrl());
                e.setModel(gw.get().model());
            } catch (Exception ex) {
                log.warn("保存常驻时加密网关失败，降级为只存名片 name={}: {}", e.getAgentName(), ex.toString());
                key = null;
            }
        }
        if (key == null) {
            // 没有源席位（或加密失败）：保留调用方传来的名片字段，与本功能之前的行为一致。
            // 三件套是**原子**的 —— 没有 Key 就不留 baseUrl/model，否则库里会出现一行
            // 「看着像自带网关、实际没有 Key」的配置，加入时又静默回退会话网关，UI 解释不通。
            e.setBaseUrl(emptyToNull(baseUrl));
            e.setModel(emptyToNull(model));
        }

        e = repo.save(e);
        Map<String, Object> v = viewWithCounts(e);
        v.put("gatewaySaved", key != null);
        return v;
    }

    @Transactional
    public Map<String, Object> updateCustomName(UserEntity user, Long id, String customName) {
        PersistentAgentEntity e = requireOwned(user, id);
        e.setCustomName(emptyToNull(customName));
        e = repo.save(e);
        return viewWithCounts(e);
    }

    /**
     * 改这个常驻智能体自己的网关。为什么另开一个端点而不并进 {@code updateCustomName}：
     * 改名是高频窄操作（点一下名字就 PUT），混进凭据字段会让「空 = 不改」和「空 = 清空」
     * 永远歧义；而「改回用本场网关」恰好就是个清空语义。
     *
     * @param mode    {@code SESSION} = 改回随本场会话网关（三列一起清空）；其它值按 {@code OWN} 处理
     * @param apiKey  非空 = 覆盖（加密入库）；空 = 不改（与导师、LLM 预设的语义一致）
     * @param baseUrl / model：{@code null} = 不改，空串 = 清空该字段
     */
    @Transactional
    public Map<String, Object> updateGateway(UserEntity user, Long id, String mode,
                                             String apiKey, String baseUrl, String model) {
        PersistentAgentEntity e = requireOwned(user, id);
        if ("SESSION".equalsIgnoreCase(mode)) {
            e.setApiKeyEnc(null);
            e.setBaseUrl(null);
            e.setModel(null);
            log.info("常驻智能体改为随会话网关 id={} name={}", id, e.getAgentName());
        } else {
            if (StringUtils.hasText(apiKey)) {
                // 同样不接受「加密失败就存明文」
                e.setApiKeyEnc(keyVault.encrypt(apiKey.trim()));
            }
            if (baseUrl != null) {
                e.setBaseUrl(emptyToNull(baseUrl));
            }
            if (model != null) {
                e.setModel(emptyToNull(model));
            }
        }
        e = repo.save(e);
        return viewWithCounts(e);
    }

    /** 明文网关，仅 owner，供「编辑网关」弹窗回填。**调用方用完即弃，别塞进列表 state。** */
    public Map<String, Object> reveal(UserEntity user, Long id) {
        PersistentAgentEntity e = requireOwned(user, id);
        Map<String, Object> m = viewWithCounts(e);
        m.put("apiKey", safeDecrypt(e.getApiKeyEnc()));
        return m;
    }

    /**
     * 从常驻库加入本场会话（供 {@code SessionController.registerAgent} 在带 {@code agentUuid} 时调用）。
     *
     * <p>网关默认用**它自己**存的；{@code useSessionLlm=true} 时才改走会话网关。
     *
     * <p>{@code name / persona / personality} 一律**以库为准**，不取请求体：卡片显示的就是库里的值，
     * 用库值能防止陈旧的前端 state 把名字或人设写歪，也让「加入不改动常驻里的人设」成为
     * 结构保证而不是约定。
     *
     * <p>解密失败要降级而不是 500 —— 换过 {@code JASYPT_ENCRYPTOR_PASSWORD} 的历史行会抛。
     */
    public DomainAgentConfig joinSession(UserEntity user, String sessionId, String agentUuid,
                                         Boolean useSessionLlm) {
        PersistentAgentEntity e = repo.findByAgentUuidAndOwnerUserId(agentUuid, user.getId())
                .orElseThrow(() -> new AuthException("常驻智能体不存在或无权限"));

        String key = null;
        if (!Boolean.TRUE.equals(useSessionLlm) && StringUtils.hasText(e.getApiKeyEnc())) {
            try {
                key = keyVault.decrypt(e.getApiKeyEnc());
            } catch (Exception ex) {
                log.warn("常驻智能体网关解密失败，降级为会话网关 uuid={}: {}", agentUuid, ex.toString());
            }
        }
        DomainAgentConfig seat = sessionService.registerPersistentAgent(
                sessionId, displayName(e), e.getPersona(), e.getPersonality(),
                e.getModel(), e.getBaseUrl(), key, useSessionLlm);
        seat.setAgentUuid(e.getAgentUuid());
        if (skillBindingService != null) {
            seat.setSkillUuids(skillBindingService.boundSkills(user.getId(),
                    SkillBindingService.TargetType.PERSISTENT, e.getAgentUuid()).stream()
                    .map(com.multiagent.discuss.db.entity.SkillEntity::getSkillUuid).toList());
        }
        attachKnowledge(sessionId, seat, e.getAgentUuid());
        return seat;
    }

    /**
     * 把知识库里攒下的页知识灌进这个新席位 —— **加入即快照**，与 persona / 网关同款语义：
     * 之后往库里追加文件不会回流到本会话，下次新建会话再加入时才带上。
     *
     * <p>这也是这个特性的收益兑现点：用户在别的会话里喂给它的资料，这次加入直接就能用，
     * **不用重传**。空库时一个字段都不改 —— 席位状态与提示保持
     * {@link SessionService#registerPersistentAgent} 刚设好的样子。
     */
    private void attachKnowledge(String sessionId, DomainAgentConfig seat, String agentUuid) {
        PersistentAgentDocService.DocCounts c = docService.counts(agentUuid);
        if (c.docCount() == 0) {
            return;
        }
        List<PageBlock> pages = docService.loadPages(agentUuid);
        if (pages.isEmpty()) {
            return;
        }
        knowledgeStore.putPages(sessionId, seat.getId(), pages);
        seat.setKnowledgeStatus(KnowledgeStatus.KNOWLEDGE_READY);
        seat.setDocumentFileName(c.docCount() + " 个文件");
        // ⚠️ 把网关提示**接在后面**而不是盖掉：0.9.0 的卖点就是「常驻自带网关、不用再配」，
        // 整句替换会让用户以为这次加入没继承网关。
        seat.setStatusMessage("知识就绪：" + c.docCount() + " 个文件，" + c.pageCount() + " 页 · "
                + seat.getStatusMessage());
        log.info("常驻知识灌回 session={} uuid={} docs={} pages={}",
                sessionId, agentUuid, c.docCount(), pages.size());
    }

    @Transactional
    public void delete(UserEntity user, Long id) {
        PersistentAgentEntity e = requireOwned(user, id);
        // 经验心得 / 钉子按 target_uuid 多态引用，建不了外键，必须在这里显式清掉。
        // 不清就是一批看不见的孤儿行 —— 而 uk_agent_memory_target 还会让那个 uuid 被永久占住。
        agentMemoryService.deleteAllFor(MemoryTargetType.PERSISTENT_AGENT, e.getAgentUuid());
        skillBindingService.deleteBindingsForTarget(
                user.getId(), SkillBindingService.TargetType.PERSISTENT, e.getAgentUuid());
        repo.delete(e);
    }

    // ── view ──────────────────────────────────────────────

    /**
     * 视图 + 知识规模。
     *
     * <p>⚠️ <b>所有带返回值的写操作（create / updateCustomName / updateGateway / reveal）都必须用它</b>，
     * 否则前端每次改名或改网关之后 `toView` 拿到的 `docCount` 就是 undefined，
     * 卡片上那条「N 个文件 · M 页」会**凭空消失**（前端会把它直接塞进列表 state）。
     *
     * <p>为什么不并进 {@link #view}：那是纯对象转换、不查库，而且它是包级可见、被
     * {@code PersistentAgentServiceTest} 钉住「掩码不变量」的地方 —— 给它塞一次 DB 查询，
     * 那个纯 POJO 测试也得跟着起仓储层。列表路径另走批量查询（{@code countsByAgentIds}），不是 N+1。
     */
    private Map<String, Object> viewWithCounts(PersistentAgentEntity e) {
        Map<String, Object> v = view(e);
        PersistentAgentDocService.DocCounts c = docService.counts(e.getAgentUuid());
        v.put("docCount", c.docCount());
        v.put("pageCount", c.pageCount());
        return v;
    }

    /**
     * 常驻智能体的对外视图。{@code apiKey} 是**掩码**（前 4 + **** + 后 4），
     * 无自带网关时为 null；另有 {@code hasOwnGateway} 让前端不必去猜 null 的含义。
     *
     * <p>包级可见是为了让 {@code PersistentAgentServiceTest} 直接断言掩码不变量
     * —— 这是「Key 不出后端」这条硬约束唯一能被单测钉住的地方。
     */
    Map<String, Object> view(PersistentAgentEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("agentId", e.getAgentUuid());
        m.put("name", e.getAgentName());
        m.put("customName", e.getCustomName());
        m.put("persona", e.getPersona());
        m.put("personality", e.getPersonality());
        m.put("model", e.getModel());
        m.put("baseUrl", e.getBaseUrl());
        // 解密失败（换过 JASYPT_ENCRYPTOR_PASSWORD 的历史行）降级成 ***，**不能让 list 整个 500** ——
        // 那样用户连列出、删除这些坏行都做不到，直接锁死。hasOwnGateway 仍如实反映库里有没有密文。
        String plain = safeDecrypt(e.getApiKeyEnc());
        m.put("apiKey", plain == null && !StringUtils.hasText(e.getApiKeyEnc())
                ? null
                : KeyVault.mask(plain));
        m.put("hasOwnGateway", StringUtils.hasText(e.getApiKeyEnc()));
        m.put("createdAt", e.getCreatedAt() != null ? e.getCreatedAt().toEpochMilli() : null);
        m.put("updatedAt", e.getUpdatedAt() != null ? e.getUpdatedAt().toEpochMilli() : null);
        return m;
    }

    /** 解不开就返回 null（并记一条 warn），绝不把异常抛给调用方。 */
    private String safeDecrypt(String enc) {        if (!StringUtils.hasText(enc)) {
            return null;
        }
        try {
            return keyVault.decrypt(enc);
        } catch (Exception ex) {
            log.warn("常驻智能体网关解密失败（master password 换过？）: {}", ex.toString());
            return null;
        }
    }

    /** 展示名：用户改过的自定义名优先，否则后端存的 agent_name。与心得视图同口径。 */
    private static String displayName(PersistentAgentEntity e) {
        return StringUtils.hasText(e.getCustomName()) ? e.getCustomName() : e.getAgentName();
    }

    private PersistentAgentEntity requireOwned(UserEntity user, Long id) {
        return repo.findByIdAndOwnerUserId(id, user.getId())
                .orElseThrow(() -> new AuthException("常驻智能体不存在或无权限"));
    }

    private String emptyToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
