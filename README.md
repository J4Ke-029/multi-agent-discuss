# 多智能体讨论系统

让多位 AI 专家围绕同一问题独立思考、讨论并形成共识。系统也支持文档改稿、导师库、长期记忆和跨会话知识库。

当前版本：**0.10.0**

## 主要能力

- 多个领域 Agent 并行思考，Host 主持多轮讨论并输出终稿。
- 每个 Agent 使用独立私有知识库，资料不会直接共享给其他席位。
- 支持 PDF、DOCX、TXT、Markdown 和图片，可逐页进行 Vision 分析。
- 改稿模式支持修改建议、用户确认、自动改写和差异记录。
- 常驻 Agent 可跨会话保留人设、网关、经验和已经读过的资料。
- 导师库可保存论文摘要、知识版图和学术风格画像。
- 会话可归档并只读回看；快照会排除密钥等敏感信息。
- 账号 Skill 库支持网页创建、ZIP 和公开 GitHub 仓库导入，并可绑定到各类 Agent。
- “智能体库”可直接管理常驻 Agent 和导师的 Skill 绑定，单个 Agent 最多 5 个。
- 可在每轮讨论结束后向全体补充信息，或指定一位当前参与的 Agent 追问。
- 会话、议题、归档和智能体库具有独立网页地址，支持刷新恢复与浏览器前进/后退。
- 支持账号隔离、匿名使用和 OpenAI 兼容网关。

## 技术栈

| 层级 | 技术 |
|---|---|
| 后端 | Java 17、Spring Boot 3.4、Spring AI 1.0 |
| 前端 | React 19、TypeScript 6、Vite 8 |
| 数据库 | MySQL 8、JPA、Flyway |
| 文档处理 | PDFBox、docx4j、Vision 模型 |
| 安全 | BCrypt、`X-Auth-Token`、jasypt |

- 前端：<http://localhost:5173>
- 后端：<http://localhost:18080>

## 快速启动

### 1. 初始化 MySQL

首次使用时，用 MySQL 管理账号执行：

```sql
CREATE DATABASE mad CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'mad'@'localhost' IDENTIFIED BY 'CHANGE_ME';
GRANT ALL PRIVILEGES ON mad.* TO 'mad'@'localhost';
FLUSH PRIVILEGES;
```

设置后端所需环境变量：

```powershell
$env:DB_USER = 'mad'
$env:DB_PASSWORD = 'CHANGE_ME'
$env:JASYPT_ENCRYPTOR_PASSWORD = 'YOUR_MASTER_PASSWORD'
```

Flyway 会自动创建和升级数据表。主密码用于解密已保存的 API Key，更换后旧密文将无法使用。

### 2. 启动后端

```powershell
mvn spring-boot:run
```

也可以复制 `src/main/resources/application-local.yml.example` 为 `application-local.yml`，在其中填写本地配置。该文件已被忽略，不应提交或分享。

### 3. 启动前端

```powershell
cd frontend
npm install
npm run dev
```

打开 <http://localhost:5173>。

### 前端路由与部署

前端使用 Browser History 路由，主要地址包括 `/workbench`、`/sessions/:sessionId`、
`/sessions/:sessionId/questions/:questionId`、`/archives/:archiveId` 和 `/library/:section`。

生产环境必须把非静态文件的前端路径回退到 `index.html`，同时保留 `/api/` 的后端代理。
Nginx 可使用：

```nginx
location /api/ {
    proxy_pass http://backend:18080;
}

location / {
    try_files $uri $uri/ /index.html;
}
```

## 使用流程

1. 新建“圆桌会议”或“改稿模式”会话。
2. 按需配置会话网关。
3. 添加临时 Agent、常驻 Agent 或导师。
4. 上传资料并等待知识状态就绪。
5. 提交问题，查看独立思考、多轮讨论、共识和终稿。
6. 结束会话时保存 Agent、同步知识与经验，并归档讨论。

改稿模式还需要上传待修改文档。系统先形成专业意见，再生成修改计划；只有用户确认的项目会进入改写。

### 使用 Skill

登录后点击账号菜单右侧的“智能体库”，可查看常驻智能体、导师及其绑定的 Skill，并在“Skill 库”中创建或导入标准 Skill 包。每个临时 Agent、常驻 Agent 或导师最多绑定 5 个 Skill；系统会按当前问题自动调用最多 2 个，并在消息下方显示本次调用记录。Host 不调用 Skill。

导入仅支持公开 GitHub 仓库或子目录，也可上传 ZIP。Skill 中的脚本只保存、不执行，且不能获得额外工具或权限。

常驻 Agent 和导师可在“智能体库”中直接绑定或解绑 Skill；处于活跃会话时不可修改，变更仅影响之后加入的会话。

讨论输入框旁的“允许插话”默认关闭。开启后，系统会在每轮完整发言后暂停；插话可面向全体或指定一位当前参与的 Agent，不占用预设讨论轮数。

## 关键设计

### 知识与讨论

- 知识按 `sessionId + agentId` 隔离，Agent 只读取自己的资料。
- 至少一个 Agent 就绪即可提问，其余席位会被跳过。
- 独立思考具有超时兜底，不会永久停在 `THINKING`。
- 常驻知识按问题检索，并携带真实文件名和页码。
- 原始文件不入库，只保存逐页理解和结构化摘要。

### 长期记忆

- 常驻 Agent 和导师可在会话结束时总结经验。
- 心得在生成提示词时注入，不会污染原始 persona。
- 用户确认的纠错作为独立“硬前提”保存，不会被后续总结覆盖。

### 安全

- API Key 只由服务端持有，入库前使用 jasypt 加密。
- 普通列表只返回脱敏值，明文 reveal 接口验证资源所有者。
- 会话归档不保存 API Key、记忆正文或知识页正文。
- 登录数据、预设、常驻 Agent、导师和归档均按账号隔离。

## 项目结构

```text
multi-agent-discuss/
├── src/main/java/com/multiagent/discuss/
│   ├── api/            REST 接口
│   ├── orchestrator/   讨论与改稿编排
│   ├── agent/          Agent 和模型客户端
│   ├── knowledge/      私有知识库与检索
│   ├── service/        业务服务
│   ├── db/             JPA 实体与仓库
│   ├── ingest/         文档解析
│   ├── vision/         页面视觉理解
│   └── security/       登录、鉴权和密钥保护
├── src/main/resources/db/migration/  Flyway 迁移
├── frontend/src/                    React 前端
└── frontend/test/                   前端回归测试
```

## 常用配置

| 环境变量 | 用途 |
|---|---|
| `DB_URL` | MySQL JDBC 地址 |
| `DB_USER` / `DB_PASSWORD` | 数据库账号 |
| `JASYPT_ENCRYPTOR_PASSWORD` | API Key 密文主密码 |
| `OPENAI_API_KEY` / `OPENAI_BASE_URL` | 默认模型网关 |
| `HOST_MODEL` | Host 默认模型 |
| `VISION_API_KEY` / `VISION_BASE_URL` / `VISION_MODEL` | Vision 配置 |

## 开发命令

```powershell
mvn test                 # 后端测试
mvn package              # 后端打包
cd frontend
npm run lint             # 前端检查
npm run build            # 前端生产构建
```

## 常见问题

- **数据库不可达**：确认 MySQL 已启动，并检查 `DB_URL`、`DB_USER` 和 `DB_PASSWORD`。
- **API Key 无法解密**：确认 `JASYPT_ENCRYPTOR_PASSWORD` 与保存 Key 时一致。
- **Agent 没进入讨论**：检查知识状态、模型网关和后端日志；关闭自动讨论时需手动开始。
- **扫描 PDF 没有文字**：扫描件需要可用的 Vision 模型；文本型 PDF 会优先读取文字层。

## 当前限制

- 活跃会话保存在内存中，后端重启后不会恢复；重要讨论应先归档。
- 当前不适合直接横向扩容，多实例需要共享会话状态和任务协调。
- 删除知识后无法从数据库还原原始文件。
- 外部模型的速度、费用和质量取决于所选网关。

版本变化见 [CHANGELOG.md](CHANGELOG.md)。

## 版权

Copyright (c) 2026 J4Ke-029. All rights reserved.

本仓库暂未授予开源许可证。公开可见不代表允许复制、修改、分发或商用。
