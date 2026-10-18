-- V3: 归档会话 —— 「结束会话」时把整场讨论快照落库。
--
-- 用途：结束的对话不再直接销毁，而是存档；用户在右侧「已归档」里只读查看，
-- 并可按需生成议题总结。按 owner_user_id 隔离，删用户级联清掉。
--
-- payload 存会话快照 JSON（会话元数据 + 全部议题产物）。
-- ⚠️ 落库前必须脱敏：快照里绝不能出现 agent / 会话网关的 API Key，
--    所以 payload 不是直接序列化 Session 对象，而是显式挑字段拼出来的（见 ArchivedSessionService）。

CREATE TABLE archived_session (
  id                BIGINT       AUTO_INCREMENT PRIMARY KEY,
  owner_user_id     BIGINT       NOT NULL,
  source_session_id VARCHAR(32)  NOT NULL COMMENT '原内存会话 id（16 位 hex），仅作溯源',
  title             VARCHAR(128) NOT NULL,
  user_style        VARCHAR(64)  NULL,
  question_count    INT          NOT NULL DEFAULT 0,
  agent_count       INT          NOT NULL DEFAULT 0,
  token_total       BIGINT       NOT NULL DEFAULT 0,
  payload           LONGTEXT     NOT NULL COMMENT '会话快照 JSON（已脱敏，不含任何 API Key）',
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_archived_session_owner FOREIGN KEY (owner_user_id) REFERENCES user(id) ON DELETE CASCADE,
  KEY idx_archived_session_owner_time (owner_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
