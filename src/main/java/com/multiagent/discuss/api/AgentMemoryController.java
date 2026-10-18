package com.multiagent.discuss.api;

import com.multiagent.discuss.domain.MemoryTargetType;
import com.multiagent.discuss.security.AuthContext;
import com.multiagent.discuss.service.AgentMemoryService;
import com.multiagent.discuss.service.SessionService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 常驻智能体 / 导师的「经验心得」（账号级）。全部接口需登录，按 {@code owner_user_id} 隔离。
 *
 * <p>路径里的 {@code {type}} 是 {@code PERSISTENT_AGENT} 或 {@code MENTOR}，
 * {@code {uuid}} 分别是 {@code persistent_agent.agent_uuid} / {@code mentor.mentor_uuid}。
 * 用 uuid 而不是主键 id：这样「删除常驻智能体后，仍能解释某个历史归档里的席位当年是谁」，
 * 与导师侧既有的口径一致。
 *
 * <p><b>总结接口不在这里</b> —— 它是 {@code POST /api/sessions/{sid}/agents/{aid}/memory/summarize}，
 * 因为目标必须从席位推导（那个席位的网关、那个席位在本场的发言），而不是从 URL 里传一个 uuid 进来。
 * 本控制器只负责「账号级」的那半边：查看、纠错、拔钉子。
 */
@RestController
@RequestMapping("/api/agent-memory")
public class AgentMemoryController {

    private final AgentMemoryService service;
    private final SessionService sessionService;

    public AgentMemoryController(AgentMemoryService service, SessionService sessionService) {
        this.service = service;
        this.sessionService = sessionService;
    }

    /**
     * 查看某个常驻对象的经验心得（只读）。
     *
     * <p>没总结过时 {@code content} 为 null、{@code pins} 为空数组 —— 前端据此显示空态，
     * 而不是报错。
     */
    @GetMapping("/{type}/{uuid}")
    public Map<String, Object> view(@PathVariable String type, @PathVariable String uuid) {
        return service.view(AuthContext.require().getId(), MemoryTargetType.parse(type), uuid);
    }

    /**
     * 纠错对话的一轮。
     *
     * @param req {@code sessionId} 可选但强烈建议带上：普通常驻智能体没有自己的 API Key
     *            （产品决策），不带会话就只能落到服务端默认网关。带了会话则要求调用方
     *            对该会话有访问权 —— 否则就是借别人的会话网关烧 token。
     */
    @PostMapping("/{type}/{uuid}/correct")
    public Map<String, Object> correct(@PathVariable String type, @PathVariable String uuid,
                                       @RequestBody(required = false) CorrectRequest req) {
        CorrectRequest r = req == null ? new CorrectRequest() : req;
        if (StringUtils.hasText(r.sessionId)) {
            sessionService.requireAccess(r.sessionId);
        }
        return service.correct(AuthContext.require().getId(), MemoryTargetType.parse(type), uuid,
                r.sessionId, r.message, toHistoryLines(r.history));
    }

    /**
     * 应用一张修改方案卡片 —— <b>用户点了「确认」才会走到这里</b>。
     *
     * <p>语义是「改心得正文 + 插一条钉子」，钉子才是权威约束：即便正文补丁没找到位置，
     * 这次纠正也会以硬前提的形式生效。
     */
    @PostMapping("/{type}/{uuid}/correct/apply")
    public Map<String, Object> apply(@PathVariable String type, @PathVariable String uuid,
                                     @RequestBody(required = false) ProposalRequest req) {
        ProposalRequest r = req == null ? new ProposalRequest() : req;
        return service.applyProposal(AuthContext.require().getId(), MemoryTargetType.parse(type), uuid,
                r.before, r.after, r.rationale);
    }

    /** 解除一条锁定。解除后它不再进硬前提段，但仍然留在这次纠错的对话记录里（前端）。 */
    @DeleteMapping("/pins/{pinId}")
    public Map<String, Object> unpin(@PathVariable Long pinId) {
        service.unpin(AuthContext.require().getId(), pinId);
        return Map.of("deleted", true, "id", pinId);
    }

    /**
     * 前端传来的对话历史 → 交给模型的文本行。
     *
     * <p>在这里转换而不是让前端直接传字符串：角色前缀是 prompt 的一部分，
     * 不该由调用方决定（否则前端可以塞任意文本进 prompt）。
     */
    private static List<String> toHistoryLines(List<HistoryItem> history) {
        List<String> lines = new ArrayList<>();
        if (history == null) {
            return lines;
        }
        for (HistoryItem h : history) {
            if (h == null || !StringUtils.hasText(h.text)) {
                continue;
            }
            String who = "agent".equalsIgnoreCase(h.role) ? "你" : "用户";
            lines.add(who + "：" + h.text.trim().replace("\n", " "));
        }
        return lines;
    }

    public static class CorrectRequest {
        private String message;
        private String sessionId;
        private List<HistoryItem> history;

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getSessionId() {
            return sessionId;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }

        public List<HistoryItem> getHistory() {
            return history;
        }

        public void setHistory(List<HistoryItem> history) {
            this.history = history;
        }
    }

    public static class HistoryItem {
        private String role;
        private String text;

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }

    public static class ProposalRequest {
        private String before;
        private String after;
        private String rationale;

        public String getBefore() {
            return before;
        }

        public void setBefore(String before) {
            this.before = before;
        }

        public String getAfter() {
            return after;
        }

        public void setAfter(String after) {
            this.after = after;
        }

        public String getRationale() {
            return rationale;
        }

        public void setRationale(String rationale) {
            this.rationale = rationale;
        }
    }
}
