package com.multiagent.discuss.security;

import com.multiagent.discuss.db.entity.UserEntity;
import com.multiagent.discuss.db.repo.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 鉴权 filter：
 * <ul>
 *   <li>读取请求头 {@code X-Auth-Token}</li>
 *   <li>查 {@link UserRepository#findByAuthToken}，找到则写入 {@link AuthContext}</li>
 *   <li>命中「需要登录」的路径（{@code /api/agents/**} / {@code /api/llm-presets/**}，除 register / login 外）但未拿到 user 时，
 *       <b>不直接拦截</b>（避免影响 {@link com.multiagent.discuss.api.ApiExceptionHandler} 的统一错误格式），
 *       而是在 service 层通过 {@link AuthContext#require()} 抛 {@link AuthException} → 401</li>
 *   <li>无论结果，最后 {@link AuthContext#clear()} 防止 ThreadLocal 跨请求污染</li>
 * </ul>
 */
public class AuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);
    public static final String TOKEN_HEADER = "X-Auth-Token";
    /**
     * 浏览器实例标识。不是凭证，只用来把「匿名会话」归属到某个浏览器
     * （见 {@link AuthContext#currentClientIdOrNull()}）。
     */
    public static final String CLIENT_ID_HEADER = "X-Client-Id";

    private final UserRepository userRepository;

    public AuthFilter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            String clientId = request.getHeader(CLIENT_ID_HEADER);
            if (clientId != null && !clientId.isBlank()) {
                AuthContext.setClientId(clientId.trim());
            }
            String token = request.getHeader(TOKEN_HEADER);
            if (token != null && !token.isBlank()) {
                UserEntity user = userRepository.findByAuthToken(token.trim()).orElse(null);
                if (user != null) {
                    AuthContext.set(user);
                    if (log.isDebugEnabled()) {
                        log.debug("auth ok: loginId={}", user.getLoginId());
                    }
                }
            }
            chain.doFilter(request, response);
        } finally {
            AuthContext.clear();
        }
    }
}