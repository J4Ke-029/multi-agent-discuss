package com.multiagent.discuss.security;

import com.multiagent.discuss.db.entity.UserEntity;

/**
 * 请求作用域的当前登录用户。
 * <p>由 {@link AuthFilter} 在解析 {@code X-Auth-Token} 后写入，service / controller 通过 {@link #require()} 读取。
 * 线程本地实现足够本项目使用（不引入异步线程分发）。
 */
public final class AuthContext {

    private static final ThreadLocal<UserEntity> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<String> CLIENT_ID = new ThreadLocal<>();

    private AuthContext() {}

    public static void set(UserEntity user) {
        CURRENT.set(user);
    }

    public static UserEntity get() {
        return CURRENT.get();
    }

    /**
     * 浏览器实例标识（请求头 {@code X-Client-Id}）。
     * <p>它不是登录凭证，只用来把「匿名会话」归属到某个浏览器，
     * 让内网里不同人的匿名会话不互相可见。客户端不带该头时为 null。
     */
    public static void setClientId(String clientId) {
        CLIENT_ID.set(clientId);
    }

    /** 当前请求的浏览器实例标识；未带 / 为空返回 null。 */
    public static String currentClientIdOrNull() {
        return CLIENT_ID.get();
    }

    public static void clear() {
        CURRENT.remove();
        CLIENT_ID.remove();
    }

    /** 读取当前用户；未登录则抛 {@link AuthException}，由 {@link com.multiagent.discuss.api.ApiExceptionHandler} 翻译为 401。 */
    public static UserEntity require() {
        UserEntity u = CURRENT.get();
        if (u == null) throw new AuthException("未登录或登录已失效");
        return u;
    }

    /** 当前登录用户的 id；匿名（未带 / 无效 token）返回 null。不抛错，供「可选归属」场景使用。 */
    public static Long currentUserIdOrNull() {
        UserEntity u = CURRENT.get();
        return u == null ? null : u.getId();
    }
}