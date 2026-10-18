package com.multiagent.discuss.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class Session {

    private final String id;
    private final Instant createdAt;
    /** 创建该会话的用户 id；匿名创建为 null。用于「禁止跨用户访问他人会话」。 */
    private Long ownerUserId;
    /**
     * 匿名创建时的浏览器实例标识（请求头 X-Client-Id）。
     * 登录创建、或客户端没带该头时为 null —— 此时不做匿名间的收窄，行为与从前一致。
     */
    private String anonymousClientId;
    private String userStyle;
    private boolean autoDiscuss;
    /** 每轮结束后是否停下来等待用户补充或指定追问。 */
    private volatile boolean allowInterjections;
    private boolean docEditMode;
    private int historyLimit;
    private int maxRounds;
    private final Map<String, DomainAgentConfig> agents = new ConcurrentHashMap<>();
    private UserDocument userDocument;
    private final List<String> questionIds = new CopyOnWriteArrayList<>();
    private final List<ChatHistoryItem> history = new CopyOnWriteArrayList<>();
    /** 会话默认网关：Host + 智能体可继承 */
    private LlmEndpoint llmEndpoint;

    public Session(String id, String userStyle, boolean autoDiscuss, boolean docEditMode,
                   int historyLimit, int maxRounds) {
        this(id, userStyle, autoDiscuss, docEditMode, historyLimit, maxRounds, null);
    }

    /**
     * 还原构造器：从归档快照读回会话创建时间。
     *
     * <p>{@code createdAt} 传 {@code null} 表示「取当前时间」—— 新建会话走的就是这条。
     */
    public Session(String id, String userStyle, boolean autoDiscuss, boolean docEditMode,
                   int historyLimit, int maxRounds, Instant createdAt) {
        this.id = id;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.userStyle = userStyle;
        this.autoDiscuss = autoDiscuss;
        this.docEditMode = docEditMode;
        this.historyLimit = historyLimit;
        this.maxRounds = maxRounds;
    }

    public String getId() {
        return id;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public String getAnonymousClientId() {
        return anonymousClientId;
    }

    public void setAnonymousClientId(String anonymousClientId) {
        this.anonymousClientId = anonymousClientId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getUserStyle() {
        return userStyle;
    }

    public void setUserStyle(String userStyle) {
        this.userStyle = userStyle;
    }

    public boolean isAutoDiscuss() {
        return autoDiscuss;
    }

    public void setAutoDiscuss(boolean autoDiscuss) {
        this.autoDiscuss = autoDiscuss;
    }

    public boolean isAllowInterjections() {
        return allowInterjections;
    }

    public void setAllowInterjections(boolean allowInterjections) {
        this.allowInterjections = allowInterjections;
    }

    public boolean isDocEditMode() {
        return docEditMode;
    }

    public void setDocEditMode(boolean docEditMode) {
        this.docEditMode = docEditMode;
    }

    public int getHistoryLimit() {
        return historyLimit;
    }

    public void setHistoryLimit(int historyLimit) {
        this.historyLimit = historyLimit;
    }

    public int getMaxRounds() {
        return maxRounds;
    }

    public void setMaxRounds(int maxRounds) {
        this.maxRounds = maxRounds;
    }

    public Map<String, DomainAgentConfig> getAgents() {
        return agents;
    }

    public UserDocument getUserDocument() {
        return userDocument;
    }

    public void setUserDocument(UserDocument userDocument) {
        this.userDocument = userDocument;
    }

    public List<String> getQuestionIds() {
        return questionIds;
    }

    public List<ChatHistoryItem> getHistory() {
        return history;
    }

    public LlmEndpoint getLlmEndpoint() {
        return llmEndpoint;
    }

    public void setLlmEndpoint(LlmEndpoint llmEndpoint) {
        this.llmEndpoint = llmEndpoint;
    }

    public void addHistory(ChatHistoryItem item) {
        // historyLimit 必须 >= 1；0 会导致每次 add 后立即 remove(0) 形成死循环空 history
        if (historyLimit < 1) historyLimit = 1;
        history.add(item);
        while (history.size() > historyLimit) {
            history.remove(0);
        }
    }

    public List<DomainAgentConfig> readyAgents() {
        List<DomainAgentConfig> list = new ArrayList<>();
        for (DomainAgentConfig a : agents.values()) {
            if (a.getKnowledgeStatus() == KnowledgeStatus.KNOWLEDGE_READY) {
                list.add(a);
            }
        }
        return list;
    }
}
