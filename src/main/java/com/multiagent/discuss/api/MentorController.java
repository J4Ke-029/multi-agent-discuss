package com.multiagent.discuss.api;

import com.multiagent.discuss.security.AuthContext;
import com.multiagent.discuss.service.MentorService;
import com.multiagent.discuss.service.SessionService;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 导师常驻库（账号级）。全部接口都需要登录，且一律按 {@code owner_user_id} 隔离。
 *
 * <p>与 {@code /api/llm-presets}、{@code /api/agents/persistent} 同一套范式：
 * list 返回脱敏 Key，明文走 {@code /{id}/reveal}。
 *
 * <p>论文上传是<b>一篇一次请求</b>（字段名 {@code file}）：批量处理是分钟级，塞进单个请求必然超时。
 * 前端逐篇串行调用并用 {@code /{id}/status} 轮询进度。
 */
@RestController
@RequestMapping("/api/mentors")
public class MentorController {

    private final MentorService service;
    private final SessionService sessionService;

    public MentorController(MentorService service, SessionService sessionService) {
        this.service = service;
        this.sessionService = sessionService;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list(AuthContext.require());
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        return service.detail(AuthContext.require(), id);
    }

    /** 返回明文 apiKey，仅 owner；list 接口已脱敏。 */
    @GetMapping("/{id}/reveal")
    public Map<String, Object> reveal(@PathVariable Long id) {
        return service.reveal(AuthContext.require(), id);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody(required = false) UpsertRequest req) {
        if (req == null) req = new UpsertRequest();
        return service.create(AuthContext.require(),
                req.name, req.discipline, req.focus, req.personality,
                req.model, req.baseUrl, req.apiKey);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id,
                                      @RequestBody(required = false) UpsertRequest req) {
        if (req == null) req = new UpsertRequest();
        return service.update(AuthContext.require(), id,
                req.name, req.discipline, req.focus, req.personality,
                req.model, req.baseUrl, req.apiKey);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        service.delete(AuthContext.require(), id);
        return Map.of("deleted", true, "id", id);
    }

    @GetMapping("/{id}/status")
    public Map<String, Object> status(@PathVariable Long id) {
        return service.status(AuthContext.require(), id);
    }

    /**
     * 上传一篇导师论文并生成摘要。
     *
     * @param sessionId 可选。导师自己没配网关时用它所在会话的网关做分析；
     *                  传了就要求调用方对该会话有访问权（不能借别人的会话网关烧 token）。
     */
    @PostMapping(value = "/{id}/papers", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadPaper(@PathVariable Long id,
                                           @RequestPart("file") MultipartFile file,
                                           @RequestParam(required = false) String sessionId) {
        if (StringUtils.hasText(sessionId)) {
            sessionService.requireAccess(sessionId);
        }
        return service.addPaper(AuthContext.require(), id, file, sessionId);
    }

    @GetMapping("/{id}/papers")
    public Map<String, Object> listPapers(@PathVariable Long id) {
        return Map.of("papers", service.listPapers(AuthContext.require(), id));
    }

    @GetMapping("/{id}/papers/{pid}")
    public Map<String, Object> paperDetail(@PathVariable Long id, @PathVariable Long pid) {
        return service.paperDetail(AuthContext.require(), id, pid);
    }

    @DeleteMapping("/{id}/papers/{pid}")
    public Map<String, Object> deletePaper(@PathVariable Long id, @PathVariable Long pid) {
        service.deletePaper(AuthContext.require(), id, pid);
        return Map.of("deleted", true, "id", pid);
    }

    public static class UpsertRequest {
        public String name;
        public String discipline;
        public String focus;
        public String personality;
        public String model;
        public String baseUrl;
        public String apiKey;
    }
}
