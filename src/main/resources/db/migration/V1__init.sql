-- V1: 用户 / 常驻智能体 / LLM 网关预设
-- 三个表全部归属于 user（ON DELETE CASCADE），删除用户会清掉其全部数据。

CREATE TABLE user (
  id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
  login_id        VARCHAR(32)  NOT NULL,
  display_name    VARCHAR(24)  NOT NULL,
  password_hash   VARCHAR(72)  NOT NULL COMMENT 'BCrypt 哈希（不可逆）',
  auth_token      CHAR(36)     NULL     COMMENT '登录时刷新的 UUID；注销 / 重新登录会清掉',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_user_login_id (login_id),
  UNIQUE KEY uk_user_auth_token (auth_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE llm_preset (
  id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
  owner_user_id   BIGINT       NOT NULL,
  label           VARCHAR(64)  NOT NULL,
  api_key_enc     TEXT         NOT NULL COMMENT 'jasypt 加密的 ENC(...) 字符串',
  base_url        VARCHAR(512) NULL,
  model           VARCHAR(128) NULL,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_llm_preset_owner FOREIGN KEY (owner_user_id) REFERENCES user(id) ON DELETE CASCADE,
  UNIQUE KEY uk_llm_preset_owner_label (owner_user_id, label),
  KEY idx_llm_preset_owner (owner_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE persistent_agent (
  id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
  owner_user_id   BIGINT       NOT NULL,
  agent_uuid      CHAR(36)     NOT NULL COMMENT '兼容前端 Agent.agentId 形态；UUID 形式便于后续跨设备引用',
  agent_name      VARCHAR(64)  NOT NULL,
  custom_name     VARCHAR(64)  NULL,
  persona         TEXT         NULL,
  personality     VARCHAR(256) NULL,
  model           VARCHAR(128) NULL,
  base_url        VARCHAR(512) NULL,
  api_key_enc     TEXT         NULL     COMMENT '保留列（历史数据兼容）；新数据一律不存 Key',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_persistent_agent_owner FOREIGN KEY (owner_user_id) REFERENCES user(id) ON DELETE CASCADE,
  UNIQUE KEY uk_persistent_agent_uuid (agent_uuid),
  KEY idx_persistent_agent_owner (owner_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
