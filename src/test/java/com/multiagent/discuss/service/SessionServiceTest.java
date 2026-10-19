package com.multiagent.discuss.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionService#useOwnGateway} —— 「这个席位到底用谁的网关」那一条判定。
 *
 * <p>它同时决定普通注册、导师加入、常驻加入三条路径的语义，而这三条路径彼此看不见对方。
 * 所以这里把它当纯函数钉死：改动前导师恒用自带的，改动后常驻默认也用自带的，
 * 唯一能翻掉这个默认值的只有 {@code useSessionLlm=true} 这个显式覆盖。
 *
 * <p>不构造 {@code SessionService} —— 该方法是静态的，而实例化要拖进整个仓储层。
 */
class SessionServiceTest {

    private static final String KEY = "sk-own-abcdefgh";

    @Test
    void ownKeyWinsByDefault() {
        // 常驻智能体加入本场时走的就是这条：请求里不带 useSessionLlm（省略 = null）
        assertTrue(SessionService.useOwnGateway(KEY, null), "自己有 Key 且没被显式覆盖 → 用自己的");
        assertTrue(SessionService.useOwnGateway(KEY, false), "显式 false 与省略同义 → 用自己的");
    }

    @Test
    void explicitTrueForcesSessionGateway() {
        // 卡片上「用本场网关」那颗就是传 true；此时哪怕自己有 Key 也要让位
        assertFalse(SessionService.useOwnGateway(KEY, true), "useSessionLlm=true 必须压过自带的 Key");
    }

    @Test
    void noKeyMeansInherit() {
        // 历史行（V2 清空过 api_key_enc）与「导师没配 Key」都落在这里：
        // 行为必须与改动前逐字节相同 —— 交给 registerAgent 去继承会话网关，
        // 两者都没有时抛那句既有的 400，本方法不负责报错。
        assertFalse(SessionService.useOwnGateway(null, null));
        assertFalse(SessionService.useOwnGateway(null, true));
        assertFalse(SessionService.useOwnGateway(null, false));
    }

    @Test
    void blankKeyCountsAsNoKey() {
        // 空串 / 全空白不算「有 Key」：否则会拿着一个空 Key 去建 ChatClient，
        // 报出来的错跟「没配网关」完全对不上，用户看不懂。
        assertFalse(SessionService.useOwnGateway("", null));
        assertFalse(SessionService.useOwnGateway("   ", null));
        assertFalse(SessionService.useOwnGateway("\t\n ", false));
    }

    @Test
    void workDirectoryChildrenCannotEscapeTheirConfiguredRoot() {
        Path root = Path.of("build", "sessions").toAbsolutePath().normalize();
        assertEquals(root.resolve("agent-123"), SessionService.safeChildPath(root, "agent-123"));
        assertThrows(IllegalArgumentException.class, () -> SessionService.safeChildPath(root, "../outside"));
        assertThrows(IllegalArgumentException.class, () -> SessionService.safeChildPath(root, "nested/path"));
    }
}
