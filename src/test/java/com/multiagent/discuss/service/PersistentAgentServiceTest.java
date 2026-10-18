package com.multiagent.discuss.service;

import com.multiagent.discuss.db.entity.PersistentAgentEntity;
import com.multiagent.discuss.security.KeyVault;
import org.jasypt.encryption.StringEncryptor;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 常驻智能体对外视图的**掩码不变量**。
 *
 * <p>0.9.0 起常驻智能体自带网关、Key 加密入库，于是多出一条硬约束：**明文绝不出现在任何
 * 面向列表的返回值里**（{@code create} / {@code update} / {@code list} 都回掩码，
 * 明文只走 {@code reveal}）。前端会把返回值直接塞进列表 state 与界面，这里漏一次就是永久泄漏。
 *
 * <p>{@code view} 是包级可见的，见它的 javadoc。
 */
class PersistentAgentServiceTest {

    private static final String PLAIN = "sk-own-abcdefgh1234";
    private static final String MASKED = "sk-o****1234";

    /**
     * 假 KeyVault：不做真加解密，只把 {@code ENC(...)} 这层壳走通。
     * 密文不以 {@code cipher:} 开头就抛 —— 模拟「换过 master password」的真实失败。
     *
     * <p>⚠️ jasypt 的 {@link StringEncryptor} 有 {@code encrypt} 与 {@code decrypt} 两个抽象方法，
     * **不是函数式接口**，所以只能写成匿名类，传不了 lambda。
     */
    private static KeyVault fakeVault() {
        return new KeyVault(new StringEncryptor() {
            @Override
            public String encrypt(String message) {
                return "cipher:" + message;
            }

            @Override
            public String decrypt(String encryptedMessage) {
                if (!encryptedMessage.startsWith("cipher:")) {
                    throw new IllegalArgumentException("解不开这段密文");
                }
                return encryptedMessage.substring("cipher:".length());
            }
        });
    }

    /**
     * 只用到 keyVault，另外五个依赖本测试一个都不碰 —— 与 AgentMemoryPromptTest 传 null 同一手法。
     *
     * <p>末尾两个是 0.10.0 加的（知识库 + 会话知识灌回），{@code view} 一个字节都碰不到它们。
     */
    private static PersistentAgentService service(KeyVault vault) {
        return new PersistentAgentService(null, null, null, vault, null, null);
    }

    @Test
    void maskNeverLeaksPlaintext() {
        PersistentAgentEntity e = new PersistentAgentEntity();
        e.setAgentUuid("uuid-1");
        e.setAgentName("材料专家");
        e.setModel("own-model");
        e.setBaseUrl("https://gw.example.com");
        e.setApiKeyEnc(fakeVault().encrypt(PLAIN));

        Map<String, Object> v = service(fakeVault()).view(e);

        assertEquals(MASKED, v.get("apiKey"), "apiKey 必须是掩码");
        assertTrue(v.get("apiKey").toString().contains("****"), "掩码要能一眼看出来是掩码");
        assertFalse(v.toString().contains(PLAIN), "整个视图里都不能出现明文");
        assertFalse(v.toString().contains("cipher:"), "密文也不该露出来 —— 那等于把库里的列搬到了前端");
        assertEquals(Boolean.TRUE, v.get("hasOwnGateway"), "存过网关 → hasOwnGateway=true");
        // 「看的」字段照常回，掩码不能顺手把这些也吃掉
        assertEquals("own-model", v.get("model"));
        assertEquals("https://gw.example.com", v.get("baseUrl"));
    }

    @Test
    void noGatewayMeansNullKeyNotMaskOfNothing() {
        // 历史行（V2 清空过 api_key_enc）与「改回用本场网关」之后的行都长这样。
        // 这里必须是 null 而不是 mask(null) 的 "***" —— 前端靠 null + hasOwnGateway 两个信号
        // 决定卡片上那颗「用它自己的网关」要不要置灰，混进一个 "***" 会让它看起来像配过。
        PersistentAgentEntity e = new PersistentAgentEntity();
        e.setAgentUuid("uuid-2");
        e.setAgentName("老智能体");
        e.setApiKeyEnc(null);

        Map<String, Object> v = service(fakeVault()).view(e);

        assertNull(v.get("apiKey"));
        assertEquals(Boolean.FALSE, v.get("hasOwnGateway"));
    }

    @Test
    void undecryptableRowDegradesInsteadOfBlowingUpTheList() {
        // 换过 JASYPT_ENCRYPTOR_PASSWORD 之后，老行的密文解不开了。
        // 此时 list 必须还能用 —— 否则用户连把这些坏行删掉都做不到，直接锁死。
        PersistentAgentEntity e = new PersistentAgentEntity();
        e.setAgentUuid("uuid-3");
        e.setAgentName("解不开的");
        e.setApiKeyEnc("ENC(这是换过 master password 之后的密文)");

        Map<String, Object> v = service(fakeVault()).view(e);

        assertEquals("***", v.get("apiKey"), "解不开就降级成 ***，不抛异常");
        assertEquals(Boolean.TRUE, v.get("hasOwnGateway"), "hasOwnGateway 仍如实反映库里有没有密文");
    }
}
