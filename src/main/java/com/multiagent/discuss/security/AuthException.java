package com.multiagent.discuss.security;

/** 鉴权失败（401）。Service 层在「没拿到当前用户」或「账号密码错」时抛出。 */
public class AuthException extends RuntimeException {
    public AuthException(String message) {
        super(message);
    }
}