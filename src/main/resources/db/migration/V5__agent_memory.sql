-- V5: 常驻智能体 / 导师的跨会话「经验心得」
--
-- 用途：常驻智能体和导师此前是「无记忆」的 —— 下一局加入时前端只把 persona / personality
-- 复制过去重新注册一个席位，后端从不读 persistent_agent 表，所以同一个专家用十场，
-- 每一场都从零开始。本迁移给它们一份账号级的、跨会话累积的心得。
--
-- 两张表：
--   * agent_memory     —— 一个目标一份心得，每场讨论结束后**整体重写覆盖**，不存流水账；
--   * agent_memory_pin —— 用户在「纠错对话」里确认过的修改，追加式落库、永不更新。
--
-- ⚠️ 钉子是**结构性硬约束**，不是提示词请求：它永远不写进 agent_memory.content，
--    而是每次渲染 system prompt 时从本表实时拼出一段独立的【已锁定 · 硬前提】。
--    这样模型想推翻也没有着力点。
--
-- ⚠️ target_uuid 是**多态引用**（PERSISTENT_AGENT → persistent_agent.agent_uuid，
--    MENTOR → mentor.mentor_uuid），所以建不了外键。删常驻智能体 / 导师时必须由
--    service 显式清理这两张表，否则会留下一批看不见的孤儿行 —— 而
--    uk_agent_memory_target 会让孤儿行把那个 uuid 永久占住。

CREATE TABLE agent_memory (
  id             BIGINT       AUTO_INCREMENT PRIMARY KEY,
  owner_user_id  BIGINT       NOT NULL,
  target_type    VARCHAR(32)  NOT NULL COMMENT 'PERSISTENT_AGENT / MENTOR',
  target_uuid    CHAR(36)     NOT NULL COMMENT '对齐 persistent_agent.agent_uuid 或 mentor.mentor_uuid',
  content        LONGTEXT     NULL     COMMENT '心得正文（【做法】+【结论】）；NULL 表示还没总结过',
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_agent_memory_owner FOREIGN KEY (owner_user_id) REFERENCES user(id) ON DELETE CASCADE,
  UNIQUE KEY uk_agent_memory_target (target_type, target_uuid),
  KEY idx_agent_memory_owner (owner_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 追加式，只插不改：一条钉子就是「用户某次明确纠正过的一条要求」+ 当时确认的原文。
-- 刻意不拆成「一条心得 = 一行 + 若干钉子行」的结构：钉子与心得正文的读路径不同
-- （钉子进硬前提段、正文进参考段），拆开才能各自独立增删而不互相覆盖。
CREATE TABLE agent_memory_pin (
  id             BIGINT       AUTO_INCREMENT PRIMARY KEY,
  owner_user_id  BIGINT       NOT NULL,
  target_type    VARCHAR(32)  NOT NULL COMMENT 'PERSISTENT_AGENT / MENTOR',
  target_uuid    CHAR(36)     NOT NULL,
  content        TEXT         NOT NULL COMMENT '用户确认过的纠错原文；作为硬前提注入 system prompt',
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_agent_memory_pin_owner FOREIGN KEY (owner_user_id) REFERENCES user(id) ON DELETE CASCADE,
  KEY idx_agent_memory_pin_target (target_type, target_uuid, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
