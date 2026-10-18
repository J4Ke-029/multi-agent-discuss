package com.multiagent.discuss.api;

import com.multiagent.discuss.security.AuthContext;
import com.multiagent.discuss.service.PersistentAgentDocService;
import com.multiagent.discuss.service.PersistentAgentService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agents/persistent")
public class PersistentAgentController {

    private final PersistentAgentService service;
    private final PersistentAgentDocService docService;

    public PersistentAgentController(PersistentAgentService service,
                                     PersistentAgentDocService docService) {
        this.service = service;
        this.docService = docService;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list(AuthContext.require());
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody(required = false) CreateRequest req) {
        if (req == null) req = new CreateRequest();
        return service.create(AuthContext.require(),
                req.name, req.customName,
                req.baseUrl, req.model,
                req.persona, req.personality,
                req.sessionId, req.agentId);
    }

    /**
     * 改名。**只改名字**，凭据走 {@code /{id}/gateway}。
     *
     * <p>刻意不把 baseUrl/model/apiKey 并进来：这个端点被「点一下名字就提交」的
     * {@code EditableName} 高频调用，混进凭据字段等于每次改名都带着一串空值往返，
     * 「空 = 不改」与「空 = 清空」的歧义会一直悬着。
     */
    @PutMapping("/{id}")
    public Map<String, Object> updateCustomName(@PathVariable Long id,
                                                @RequestBody(required = false) UpdateRequest req) {
        if (req == null) req = new UpdateRequest();
        return service.updateCustomName(AuthContext.require(), id, req.customName);
    }

    /**
     * 改这个常驻智能体自己的网关（加密入库）。
     *
     * @param req {@code mode="SESSION"} 改回随本场会话网关（清空三列）；
     *            {@code mode="OWN"}（或缺省）填/改自己的网关，其中 {@code apiKey} 留空 = 不改
     */
    @PutMapping("/{id}/gateway")
    public Map<String, Object> updateGateway(@PathVariable Long id,
                                             @RequestBody(required = false) GatewayRequest req) {
        if (req == null) req = new GatewayRequest();
        return service.updateGateway(AuthContext.require(), id, req.mode, req.apiKey, req.baseUrl, req.model);
    }

    /** 返回**明文** apiKey，仅 owner；列表接口回的是掩码。供「编辑网关」弹窗回填。 */
    @GetMapping("/{id}/reveal")
    public Map<String, Object> reveal(@PathVariable Long id) {
        return service.reveal(AuthContext.require(), id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        service.delete(AuthContext.require(), id);
        return Map.of("deleted", true, "id", id);
    }

    // ── 知识库（它读过的文件，跨会话累积、跟随账号） ──────────────
    //
    // ⚠️ 下面四个端点的路径参数一律是 **agentUuid**（那个 CHAR(36) 的稳定标识），
    // 与本类上面那些 `/{id}` 用的 **DB 主键**是两种东西。混用是这个项目已经踩过一次的坑
    // （0.9.0 的 CHANGELOG 记着 agentId 与 agentUuid 比错那次），所以宁可路径写长一点。
    //
    // 知识的**唯一入口是「结束会话时自动带上」**（POST /api/sessions/{sid}/agents/{aid}/knowledge/save），
    // 这里刻意**不做上传端点** —— 攒下来的就是它在讨论里真读过的那些资料。

    @GetMapping("/{uuid}/docs")
    public List<Map<String, Object>> listDocs(@PathVariable String uuid) {
        return docService.list(AuthContext.require(), uuid);
    }

    @GetMapping("/{uuid}/docs/{docId}")
    public Map<String, Object> docDetail(@PathVariable String uuid, @PathVariable Long docId) {
        return docService.detail(AuthContext.require(), uuid, docId);
    }

    /**
     * 重跑某一篇的摘要。
     *
     * <p>页知识早就落库了、检索也一直可用，失败的只是摘要这一步，所以重跑**不会产生第二条记录**。
     * 摘要仍然失败时返回的 {@code status} 是 {@code FAILED}，**HTTP 仍是 200** —— 与保存路径的
     * 失败语义一致：让前端把这一篇渲染成「已保存 N 页（摘要未生成）+ 重试」，而不是一个红叉。
     *
     * @param req 可选，{@code sessionId} —— 这个常驻自己没有网关时借该会话的网关跑（没有活会话也能跑）
     */
    @PostMapping("/docs/{docId}/summarize")
    public Map<String, Object> summarizeDoc(@PathVariable Long docId,
                                            @RequestBody(required = false) SummarizeDocRequest req) {
        return docService.summarize(AuthContext.require(), docId, req == null ? null : req.sessionId);
    }

    @DeleteMapping("/{uuid}/docs/{docId}")
    public Map<String, Object> deleteDoc(@PathVariable String uuid, @PathVariable Long docId) {
        docService.delete(AuthContext.require(), uuid, docId);
        return Map.of("deleted", true, "docId", docId);
    }

    public static class SummarizeDocRequest {
        public String sessionId;
    }

    public static class CreateRequest {
        public String name;
        public String customName;
        public String baseUrl;
        public String model;
        public String persona;
        public String personality;
        /**
         * 源会话 + 源席位。两者都给时，后端**从那个席位复制它当前生效的网关**并加密入库。
         *
         * <p>为什么不直接收 apiKey：席位回给前端的 key 是掩码，前端把它传回来存进去的
         * 就是一串掩码。明文只在服务端内存里，由后端自己取、自己加密。
         */
        public String sessionId;
        public String agentId;
    }

    public static class UpdateRequest {
        public String customName;
    }

    public static class GatewayRequest {
        /** {@code OWN}（缺省）| {@code SESSION} */
        public String mode;
        /** mode=OWN 时：非空 = 覆盖（加密入库）；空 = 保留原 Key */
        public String apiKey;
        public String baseUrl;
        public String model;
    }
}