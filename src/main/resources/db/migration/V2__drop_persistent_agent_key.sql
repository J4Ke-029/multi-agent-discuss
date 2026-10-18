-- V2: 常驻智能体不再保存 API Key
-- 历史沿革：V1 里 persistent_agent.api_key_enc NOT NULL（存 jasypt ENC(...) 密文）。
-- 产品决定：常驻智能体只是「可复用智能体配置名片」，不存任何 Key；Key 由加入会话时选择网关提供。
-- 这里把 api_key_enc 改为可空，并清空既有密文（用户已确认不再需要这些老 Key）。

ALTER TABLE persistent_agent MODIFY COLUMN api_key_enc TEXT NULL COMMENT '保留列（历史数据兼容）；新数据一律不存 Key';

UPDATE persistent_agent SET api_key_enc = NULL WHERE api_key_enc IS NOT NULL;
