package com.multiagent.discuss.service;

import com.multiagent.discuss.db.entity.LlmPresetEntity;
import com.multiagent.discuss.db.entity.UserEntity;
import com.multiagent.discuss.db.repo.LlmPresetRepository;
import com.multiagent.discuss.security.AuthException;
import com.multiagent.discuss.security.KeyVault;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 网关预设 CRUD。所有查询 / 修改都强制 {@code owner_user_id = currentUser.id}，
 * 跨用户无法互相看到或改动。
 */
@Service
public class LlmPresetService {

    private final LlmPresetRepository repo;
    private final KeyVault keyVault;

    public LlmPresetService(LlmPresetRepository repo, KeyVault keyVault) {
        this.repo = repo;
        this.keyVault = keyVault;
    }

    public List<Map<String, Object>> list(UserEntity user) {
        return repo.findAllByOwnerUserIdOrderByUpdatedAtDesc(user.getId()).stream()
                .map(this::maskedView)
                .toList();
    }

    /**
     * 明文接口：仅本人按 id 取预设的明文 Key，供「入座时选预设」场景使用。
     * <p>list 不再返回明文，避免给前端无意中把密钥写进日志 / 截图 / 共享 tab。
     */
    public Map<String, Object> reveal(UserEntity user, Long id) {
        LlmPresetEntity e = requireOwned(user, id);
        return fullView(e);
    }

    @Transactional
    public Map<String, Object> create(UserEntity user,
                                      String label, String apiKey,
                                      String baseUrl, String model) {
        validateLabel(label);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey 不能为空");
        }
        if (repo.existsByOwnerUserIdAndLabel(user.getId(), label.trim())) {
            throw new IllegalStateException("已存在同名预设「" + label.trim() + "」");
        }

        LlmPresetEntity e = new LlmPresetEntity();
        e.setOwnerUserId(user.getId());
        e.setLabel(label.trim());
        e.setApiKeyEnc(keyVault.encrypt(apiKey));
        e.setBaseUrl(emptyToNull(baseUrl));
        e.setModel(emptyToNull(model));
        e = repo.save(e);
        return fullView(e);
    }

    @Transactional
    public Map<String, Object> update(UserEntity user, Long id,
                                      String label, String apiKey,
                                      String baseUrl, String model) {
        LlmPresetEntity e = requireOwned(user, id);
        if (label != null && !label.isBlank()) {
            String newLabel = label.trim();
            if (!newLabel.equals(e.getLabel())
                    && repo.existsByOwnerUserIdAndLabel(user.getId(), newLabel)) {
                throw new IllegalStateException("已存在同名预设「" + newLabel + "」");
            }
            e.setLabel(newLabel);
        }
        if (apiKey != null && !apiKey.isBlank()) {
            e.setApiKeyEnc(keyVault.encrypt(apiKey));
        }
        // baseUrl / model 允许置空（用户想清空）
        e.setBaseUrl(baseUrl == null ? e.getBaseUrl() : emptyToNull(baseUrl));
        e.setModel(model == null ? e.getModel() : emptyToNull(model));
        e = repo.save(e);
        return fullView(e);
    }

    @Transactional
    public void delete(UserEntity user, Long id) {
        LlmPresetEntity e = requireOwned(user, id);
        repo.delete(e);
    }

    // ── view ──────────────────────────────────────────────

    /**
     * list 接口：apiKey 返回脱敏（{@code sk-12****abcd}）。
     * 明文请走 {@link #reveal}。
     */
    private Map<String, Object> maskedView(LlmPresetEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("label", e.getLabel());
        m.put("baseUrl", e.getBaseUrl());
        m.put("model", e.getModel());
        m.put("apiKey", KeyVault.mask(keyVault.decrypt(e.getApiKeyEnc())));
        m.put("updatedAt", e.getUpdatedAt() != null ? e.getUpdatedAt().toEpochMilli() : null);
        return m;
    }

    /** create / update / reveal 接口：apiKey 明文返回（前端要拿去注册 Agent） */
    private Map<String, Object> fullView(LlmPresetEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("label", e.getLabel());
        m.put("baseUrl", e.getBaseUrl());
        m.put("model", e.getModel());
        m.put("apiKey", keyVault.decrypt(e.getApiKeyEnc()));
        m.put("updatedAt", e.getUpdatedAt() != null ? e.getUpdatedAt().toEpochMilli() : null);
        return m;
    }

    private LlmPresetEntity requireOwned(UserEntity user, Long id) {
        return repo.findByIdAndOwnerUserId(id, user.getId())
                .orElseThrow(() -> new AuthException("预设不存在或无权限"));
    }

    private void validateLabel(String label) {
        if (label == null || label.trim().isEmpty()) {
            throw new IllegalArgumentException("label 不能为空");
        }
        if (label.trim().length() > 64) {
            throw new IllegalArgumentException("label 不超过 64 字符");
        }
    }

    private String emptyToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}