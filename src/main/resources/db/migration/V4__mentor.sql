-- V4: 导师常驻库 —— 「导师模式」智能体的账号级数据
--
-- 用途：用户在「添加智能体」时可以选择导师模式。导师不是会话级的，而是挂在账号下的一套
-- 可复用论文语料库：一个导师 = 称呼 / 学科方向 / 指导重点 / 学术风格画像 / 知识版图画像 /
-- persona / 性格 / 网关（含加密的 API Key）+ 若干篇导师论文。
-- 新建即入库（默认常驻），可随时删除；同一个导师可以加入任意多个会话，不用重传论文。
--
-- ⚠️ 导师的 API Key 是**加密入库**的（jasypt `ENC(...)`，见 KeyVault），
--    这是相对「常驻智能体不存 Key」的一处有意例外；列表接口只回脱敏值，
--    明文走 GET /api/mentors/{id}/reveal（仅 owner）。
--
-- ⚠️ 论文的**原始文件字节不入库**：讨论时引用与检索依赖的是 Vision 逐页理解的结果
--    （mentor_paper_page），不是原文。因此换模型后可以重跑「逐篇摘要 + 画像」，
--    但无法重跑页理解。这是已知且已确认接受的限制。

CREATE TABLE mentor (
  id                BIGINT       AUTO_INCREMENT PRIMARY KEY,
  owner_user_id     BIGINT       NOT NULL,
  mentor_uuid       CHAR(36)     NOT NULL COMMENT '跨会话引用用的稳定标识；对齐 persistent_agent.agent_uuid',
  name              VARCHAR(64)  NOT NULL COMMENT '导师称呼',
  discipline        VARCHAR(128) NULL     COMMENT '学科方向',
  focus             TEXT         NULL     COMMENT '希望获得的指导',
  persona           TEXT         NULL     COMMENT '由模板 + 两份画像拼出，注册智能体席位时直接用',
  personality       VARCHAR(256) NULL     COMMENT '性格关键词（可选）',
  model             VARCHAR(128) NULL,
  base_url          VARCHAR(512) NULL,
  api_key_enc       TEXT         NULL     COMMENT 'jasypt 加密的 ENC(...) 字符串（本表按产品要求保存 Key）',
  style_profile     LONGTEXT     NULL     COMMENT '学术风格画像（论据结构/术语密度/句式/证据偏好/批评方式）',
  knowledge_map     LONGTEXT     NULL     COMMENT '知识版图画像（研究主题/常用方法/核心理论/证据边界）',
  status            VARCHAR(32)  NOT NULL COMMENT 'WAITING_PAPERS / READY / FAILED',
  status_message    VARCHAR(512) NULL,
  paper_count       INT          NOT NULL DEFAULT 0,
  page_count        INT          NOT NULL DEFAULT 0,
  progress_current  INT          NOT NULL DEFAULT 0 COMMENT '本次论文处理已完成页数',
  progress_total    INT          NOT NULL DEFAULT 0 COMMENT '本次论文处理总页数',
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_mentor_owner FOREIGN KEY (owner_user_id) REFERENCES user(id) ON DELETE CASCADE,
  UNIQUE KEY uk_mentor_uuid (mentor_uuid),
  KEY idx_mentor_owner (owner_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 一篇导师论文一行。九个摘要字段是「逐篇结构化摘要」的落地形态。
-- 刻意不加 (mentor_id, file_name) 唯一键：同一篇论文重传一次就是一条新记录，
-- 由用户在界面上自己删掉不要的那条，比数据库直接拒绝更好理解。
CREATE TABLE mentor_paper (
  id                BIGINT       AUTO_INCREMENT PRIMARY KEY,
  mentor_id         BIGINT       NOT NULL,
  file_name         VARCHAR(256) NOT NULL,
  page_count        INT          NOT NULL DEFAULT 0,
  title             VARCHAR(512) NULL,
  authors           VARCHAR(512) NULL,
  pub_year          VARCHAR(16)  NULL     COMMENT '容 "2021" / "2021(online)" 这类写法',
  abstract_text     LONGTEXT     NULL     COMMENT '具体摘要（研究背景、对象与总体结论）',
  research_question TEXT         NULL     COMMENT '研究问题或目标',
  methodology       LONGTEXT     NULL     COMMENT '研究方法、数据或实验设计',
  findings          LONGTEXT     NULL     COMMENT '主要发现及证据边界',
  limitations       TEXT         NULL     COMMENT '局限、适用条件或原文未说明之处',
  keywords          VARCHAR(512) NULL     COMMENT '关键词，顿号分隔',
  summary_status    VARCHAR(32)  NOT NULL COMMENT 'READY / FAILED',
  summary_message   VARCHAR(512) NULL     COMMENT '失败原因',
  sort_order        INT          NOT NULL DEFAULT 0 COMMENT '按上传顺序稳定排列',
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_mentor_paper_mentor FOREIGN KEY (mentor_id) REFERENCES mentor(id) ON DELETE CASCADE,
  KEY idx_mentor_paper (mentor_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 一页一行。页是讨论时的**检索单元**，所以独立成行而不是塞进 mentor_paper 的一个 JSON 列：
--   ① 论文索引列表只读标题/作者/年份/关键词，不该顺带把全部页理解拉进内存；
--   ② 独立成行才能按论文、再按页过滤与统计。
-- imageRef 不入库：它指向 <tmp>/multi-agent-discuss/<sid>/<agentId>/ 下的临时页图，
-- 重启或删会话即失效，且前端从不读它。
CREATE TABLE mentor_paper_page (
  id            BIGINT   AUTO_INCREMENT PRIMARY KEY,
  paper_id      BIGINT   NOT NULL,
  page_index    INT      NOT NULL COMMENT '0 基页序',
  plain_hint    LONGTEXT NULL     COMMENT '该页可提取的文字层（可能为空）',
  understanding LONGTEXT NULL     COMMENT 'PageBlock.Understanding 的 JSON（narrative/tables/figures/claims/uncertainties）',
  CONSTRAINT fk_mentor_page_paper FOREIGN KEY (paper_id) REFERENCES mentor_paper(id) ON DELETE CASCADE,
  KEY idx_mentor_page (paper_id, page_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
