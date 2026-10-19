-- V6: 常驻智能体的文件知识库 —— 「它读过的资料」跟着账号走
--
-- 背景：2.4.0 之后，常驻智能体的名字 / 人设 / 网关都跨会话跟着账号走了，
-- 唯独**知识**没有 —— 它一直只活在 AgentKnowledgeStore（一个按 sessionId::agentId
-- 键的 ConcurrentHashMap）里，会话一销毁就全没了。后果是：同一个常驻智能体
-- 每次加入新会话都得重传文件，它昨天读过的那篇资料，今天完全不记得。
--
-- 本表就是补上这一块：结束会话、把它存为常驻时，把它当场读过的每个文件的
-- 逐页理解结果 + 九字段结构化摘要一并落库；以后加入任何会话自动灌回该会话的
-- 知识库（见 PersistentAgentDocService.loadPages），不用重传。
--
-- 与 mentor_paper / mentor_paper_page 是**同一套范式**（连九个字段都逐列对齐），
-- 差别只有三点：
--   ① 挂靠对象是 persistent_agent 而不是 mentor；
--   ② 用真外键（导师那张被迫只能挂父表，这里是同款做法）；
--   ③ 写入时机是「结束会话」而不是「上传当时」——因为上传时它可能还没被选为常驻。
--
-- ⚠️ 原始文件字节同样**不入库**（照导师的既有取舍）：入库的是 Vision 逐页理解的结果。
--    因此换模型后摘要可以重跑（走 POST /docs/{docId}/summarize），
--    但**页理解不能重跑**，且删掉的知识无法重建 —— 界面上的删除入口必须二次确认。

-- 一个常驻智能体读过的一个文件一行。
-- 刻意不加 (persistent_agent_id, file_name) 唯一键（与 mentor_paper 一致）：
-- 同一份资料真的传两次就是两条记录，由用户在界面上自己删掉不要的那条。
-- 幂等靠**内存里的待保存清单摘取**（DomainAgentConfig.drainPendingDocs），
-- 那一层防的是「同一批重复提交」（取消后重来、重试、双标签页），不是「用户真的传了两次」。
CREATE TABLE persistent_agent_doc (
  id                  BIGINT       AUTO_INCREMENT PRIMARY KEY,
  persistent_agent_id BIGINT       NOT NULL,
  file_name           VARCHAR(256) NOT NULL,
  page_count          INT          NOT NULL DEFAULT 0,
  title               VARCHAR(512) NULL,
  authors             VARCHAR(512) NULL,
  pub_year            VARCHAR(16)  NULL     COMMENT '容 "2021" / "2021(online)" 这类写法',
  abstract_text       LONGTEXT     NULL     COMMENT '具体摘要（研究背景、对象与总体结论）',
  research_question   TEXT         NULL     COMMENT '研究问题或目标',
  methodology         LONGTEXT     NULL     COMMENT '研究方法、数据或实验设计',
  findings            LONGTEXT     NULL     COMMENT '主要发现及证据边界',
  limitations         TEXT         NULL     COMMENT '局限、适用条件或原文未说明之处',
  keywords            VARCHAR(512) NULL     COMMENT '关键词，顿号分隔',
  summary_status      VARCHAR(32)  NOT NULL COMMENT 'READY / FAILED',
  summary_message     VARCHAR(512) NULL     COMMENT '失败原因',
  sort_order          INT          NOT NULL DEFAULT 0 COMMENT '按入库顺序稳定排列',
  created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_pa_doc_agent FOREIGN KEY (persistent_agent_id) REFERENCES persistent_agent(id) ON DELETE CASCADE,
  KEY idx_pa_doc (persistent_agent_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 一页一行。页是讨论时的**检索单元**，所以独立成行而不是塞进上一张表的一个 JSON 列：
--   ① 知识列表只读标题/作者/年份/关键词，不该顺带把全部页理解拉进内存；
--   ② 独立成行才能按文件、再按页过滤与统计（「N 个文件 · M 页」里的 M 就是这里的行数）。
-- imageRef 不入库：它指向 <tmp>/multi-agent-discuss/<sid>/<agentId>/ 下的临时页图，
-- 重启或删会话即失效，且前端从不读它。
CREATE TABLE persistent_agent_doc_page (
  id            BIGINT   AUTO_INCREMENT PRIMARY KEY,
  doc_id        BIGINT   NOT NULL,
  page_index    INT      NOT NULL COMMENT '0 基页序',
  plain_hint    LONGTEXT NULL     COMMENT '该页可提取的文字层（可能为空）',
  understanding LONGTEXT NULL     COMMENT 'PageBlock.Understanding 的 JSON（narrative/tables/figures/claims/uncertainties）',
  CONSTRAINT fk_pa_doc_page_doc FOREIGN KEY (doc_id) REFERENCES persistent_agent_doc(id) ON DELETE CASCADE,
  KEY idx_pa_doc_page (doc_id, page_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
