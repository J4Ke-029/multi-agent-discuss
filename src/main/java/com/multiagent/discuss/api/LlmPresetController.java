package com.multiagent.discuss.api;

import com.multiagent.discuss.db.entity.UserEntity;
import com.multiagent.discuss.security.AuthContext;
import com.multiagent.discuss.service.LlmPresetService;
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
@RequestMapping("/api/llm-presets")
public class LlmPresetController {

    private final LlmPresetService service;

    public LlmPresetController(LlmPresetService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list(AuthContext.require());
    }

    /**
     * 返回单条预设的明文 apiKey，仅 owner；list 接口已脱敏。
     * <p>前端「入座时选预设」自动注入 Key 走这里，不要再用 list 拿明文。
     */
    @GetMapping("/{id}/reveal")
    public Map<String, Object> reveal(@PathVariable Long id) {
        return service.reveal(AuthContext.require(), id);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody(required = false) UpsertRequest req) {
        if (req == null) req = new UpsertRequest();
        return service.create(AuthContext.require(),
                req.label, req.apiKey, req.baseUrl, req.model);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id,
                                      @RequestBody(required = false) UpsertRequest req) {
        if (req == null) req = new UpsertRequest();
        return service.update(AuthContext.require(), id,
                req.label, req.apiKey, req.baseUrl, req.model);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        service.delete(AuthContext.require(), id);
        return Map.of("deleted", true, "id", id);
    }

    public static class UpsertRequest {
        public String label;
        public String apiKey;
        public String baseUrl;
        public String model;
    }
}