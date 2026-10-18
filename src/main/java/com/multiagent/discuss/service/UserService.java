package com.multiagent.discuss.service;

import com.multiagent.discuss.db.entity.UserEntity;
import com.multiagent.discuss.db.repo.UserRepository;
import com.multiagent.discuss.security.AuthException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 用户注册 / 登录 / 注销 / 自我查询。
 *
 * <p>规则：
 * <ul>
 *   <li>loginId：3-32 字符，{@code [A-Za-z0-9_\-.]+}，不区分大小写判重</li>
 *   <li>displayName：1-24 字符，可重复</li>
 *   <li>password：6-64 字符；存 BCrypt 哈希（不可逆）</li>
 *   <li>token：UUID，登录时刷新；存 DB 而不是内存以支持「重启不丢登录」</li>
 * </ul>
 */
@Service
public class UserService {

    private static final Pattern LOGIN_PATTERN = Pattern.compile("^[A-Za-z0-9_\\-.]+$");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public Map<String, Object> register(String loginIdRaw, String displayNameRaw, String password) {
        String loginId = normalizeLoginId(loginIdRaw);
        String displayName = normalizeDisplayName(displayNameRaw);
        validatePassword(password);

        if (userRepository.existsByLoginIdIgnoreCase(loginId)) {
            throw new IllegalStateException("账号「" + loginId + "」已被注册");
        }

        UserEntity user = new UserEntity();
        user.setLoginId(loginId);
        user.setDisplayName(displayName);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setAuthToken(UUID.randomUUID().toString());
        user = userRepository.save(user);

        return authPayload(user);
    }

    @Transactional
    public Map<String, Object> login(String loginIdRaw, String password) {
        String loginId = normalizeLoginId(loginIdRaw);
        if (password == null || password.isEmpty()) {
            throw new AuthException("账号或密码错误");
        }
        UserEntity user = userRepository.findByLoginIdIgnoreCase(loginId).orElse(null);
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            // 不区分「账号不存在」与「密码错」，避免账号枚举
            throw new AuthException("账号或密码错误");
        }
        // 登录刷新 token，旧 token 失效
        user.setAuthToken(UUID.randomUUID().toString());
        user = userRepository.save(user);
        return authPayload(user);
    }

    @Transactional
    public void logout(UserEntity user) {
        user.setAuthToken(null);
        userRepository.save(user);
    }

    public Map<String, Object> me(UserEntity user) {
        return userView(user);
    }

    private Map<String, Object> authPayload(UserEntity user) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", user.getAuthToken());
        m.put("user", userView(user));
        return m;
    }

    private Map<String, Object> userView(UserEntity user) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", user.getId());
        m.put("loginId", user.getLoginId());
        m.put("displayName", user.getDisplayName());
        m.put("createdAt", user.getCreatedAt() != null ? user.getCreatedAt().toEpochMilli() : null);
        return m;
    }

    private String normalizeLoginId(String raw) {
        if (raw == null) throw new IllegalArgumentException("账号不能为空");
        String s = raw.trim();
        if (s.length() < 3 || s.length() > 32) {
            throw new IllegalArgumentException("账号长度需在 3-32 字符之间");
        }
        if (!LOGIN_PATTERN.matcher(s).matches()) {
            throw new IllegalArgumentException("账号只能含字母、数字、下划线、点、横线");
        }
        return s;
    }

    private String normalizeDisplayName(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("显示名不能为空");
        }
        String s = raw.trim();
        if (s.length() > 24) {
            throw new IllegalArgumentException("显示名不超过 24 字符");
        }
        return s;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 6 || password.length() > 64) {
            throw new IllegalArgumentException("密码长度需在 6-64 字符之间");
        }
    }
}