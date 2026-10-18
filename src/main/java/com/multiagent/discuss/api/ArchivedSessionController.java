package com.multiagent.discuss.api;

import com.multiagent.discuss.security.AuthContext;
import com.multiagent.discuss.service.ArchivedSessionService;
import com.multiagent.discuss.service.SummaryService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 已归档会话：只读查看 + 生成总结 + 删除。
 *
 * <p>归档动作本身不在这里 —— 它是「结束会话」的一部分，见
 * {@code POST /api/sessions/{sid}/archive}。
 */
@RestController
@RequestMapping("/api/archived-sessions")
public class ArchivedSessionController {

    private final ArchivedSessionService service;
    private final SummaryService summaryService;

    public ArchivedSessionController(ArchivedSessionService service, SummaryService summaryService) {
        this.service = service;
        this.summaryService = summaryService;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list(AuthContext.require());
    }

    /** 详情：含完整会话快照，供中间舞台只读渲染 */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        return service.detail(AuthContext.require(), id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        service.delete(AuthContext.require(), id);
        return Map.of("deleted", true, "id", id);
    }

    /**
     * 用归档快照重新生成议题总结。
     *
     * <p>⚠️ 快照里不存 API Key（脱敏是硬约束），所以这里只能走**服务端默认网关**
     * （{@code spring.ai.openai} 那套），不能复用原会话的第三方网关。
     * 真实部署要能出总结，得配好服务端默认 Key。
     */
    @PostMapping("/{id}/summary")
    public Map<String, Object> summary(@PathVariable Long id,
                                       @RequestBody(required = false) SummaryRequest req) {
        SummaryRequest r = req == null ? new SummaryRequest() : req;
        ArchivedSessionService.Reconstructed rc =
                service.reconstruct(AuthContext.require(), id);
        return summaryService.generateForArchived(
                rc.session(), rc.questions(), r.scope, r.questionId);
    }

    /** 请求体沿用项目惯例：静态内部类 + public 字段（见 LlmPresetController） */
    public static class SummaryRequest {
        public String scope;
        public String questionId;
    }
}
