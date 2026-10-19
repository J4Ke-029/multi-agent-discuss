package com.multiagent.discuss.security;

import org.jasypt.encryption.StringEncryptor;
import org.springframework.stereotype.Component;

/**
 * 把 jasypt {@link StringEncryptor} 包装成「加密总是包 {@code ENC(...)}」「解密兼容明文」的统一接口，
 * 供数据库列使用：{@code llm_preset.api_key_enc}、{@code mentor.api_key_enc}、
 * {@code persistent_agent.api_key_enc}。
 *
 * <p>关于常驻智能体：那列是 V1 建表时就有的，V2 曾清空并声明「新数据一律不存 Key」；
 * **该决策自 2.4.0 起被推翻** —— 现在保存为常驻时会把席位当时在用的网关加密写进去。
 * 所以这三个使用者的口径已经完全一致：加密入库、列表掩码、明文只走各自的 reveal 接口（仅 owner）。
 *
 * <p>与 {@code application.yml} 里 {@code jasypt.encryptor.*} 共用同一份配置（算法 / 迭代次数 / IV 生成器）。
 * 启动时通过环境变量 {@code JASYPT_ENCRYPTOR_PASSWORD} 注入 master password。
 */
@Component
public class KeyVault {

    private final StringEncryptor encryptor;

    public KeyVault(StringEncryptor encryptor) {
        this.encryptor = encryptor;
    }

    /** 明文 → {@code ENC(密文)}，可直接写入 DB 列。 */
    public String encrypt(String plain) {
        if (plain == null) return null;
        return "ENC(" + encryptor.encrypt(plain) + ")";
    }

    /**
     * 解密：传入 {@code ENC(...)} 形式则剥掉外层后解密；若是历史明文则原样返回（兼容迁移）。
     * 解密失败抛 {@code org.jasypt.exceptions.EncryptionOperationNotPossibleException}，由调用方处理。
     */
    public String decrypt(String stored) {
        if (stored == null) return null;
        if (stored.startsWith("ENC(") && stored.endsWith(")")) {
            return encryptor.decrypt(stored.substring(4, stored.length() - 1));
        }
        return stored;
    }

    /** 给前端脱敏用：前 4 + **** + 后 4；不足 8 字符直接返回 *** */
    public static String mask(String apiKey) {
        if (apiKey == null || apiKey.length() < 8) return "***";
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
