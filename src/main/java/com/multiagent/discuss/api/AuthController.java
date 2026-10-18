package com.multiagent.discuss.api;

import com.multiagent.discuss.db.entity.UserEntity;
import com.multiagent.discuss.security.AuthContext;
import com.multiagent.discuss.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 鉴权路由：注册 / 登录 / 注销 / 当前用户。
 *
 * <p>注册 / 登录不需要 token（filter 写入 AuthContext 失败也无所谓）；
 * 注销 / me 依赖 {@code X-Auth-Token}，未带则由 {@link AuthContext#require()} 抛 {@code AuthException} → 401。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody(required = false) RegisterRequest req) {
        if (req == null) req = new RegisterRequest();
        return userService.register(req.loginId, req.displayName, req.password);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody(required = false) LoginRequest req) {
        if (req == null) req = new LoginRequest();
        return userService.login(req.loginId, req.password);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout() {
        UserEntity user = AuthContext.require();
        userService.logout(user);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loggedOut", true);
        return m;
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        UserEntity user = AuthContext.require();
        return userService.me(user);
    }

    public static class RegisterRequest {
        public String loginId;
        public String displayName;
        public String password;
    }

    public static class LoginRequest {
        public String loginId;
        public String password;
    }
}