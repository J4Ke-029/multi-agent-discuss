# 议事厅 · 前端

多智能体讨论系统的 Web UI，对接后端 `http://localhost:18080`。

## 开发

先启动后端，再：

```powershell
npm install
npm run dev
```

访问 http://localhost:5173 。`vite.config.ts` 已将 `/api` 代理到后端。

## 能力

- 创建 / 恢复 / 销毁会话
- 注册领域智能体、上传文献、知识状态轮询
- 提问、独立思考席、讨论笔录、专业共识、终稿
- 手动讨论（`autoDiscuss=false`）
- 改稿模式：修改建议勾选 → 确认改写 → 变更记录

## 构建

```powershell
npm run build
npm run preview
```
