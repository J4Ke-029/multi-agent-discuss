import { useEffect, useState } from 'react'
import type { Agent, Session, UserDocStatus } from '../api/types'
import { agentColorIndex } from '../lib/labels'
import { formatDateTimeMs } from '../lib/time'
import { ConfirmDialog } from './ConfirmDialog'
import { ProviderDialog } from './ProviderDialog'
import { SessionSettingsDialog } from './SessionSettingsDialog'
import { StatusChip } from './StatusChip'
import { AgentSkillDialog } from './AgentSkillDialog'

type Props = {
  session: Session
  userDocument?: UserDocStatus | null
  busy: boolean
  /** 打开「添加智能体」弹窗（注册新智能体 / 使用常驻智能体） */
  onAddAgent: () => void
  onUploadDoc: (agentId: string, file: File) => Promise<void>
  /** 导师席位：往账号级导师库追加论文（论文属于导师，不属于本场会话） */
  onUploadMentorPapers: (agentId: string, files: File[]) => Promise<void>
  /** 导师席位：打开该导师的论文摘要库 */
  onOpenMentorPapers: (agent: Agent) => void
  /** 常驻身份的席位：打开它的跨会话经验心得（只读） */
  onOpenMemory: (agent: Agent) => void
  onRemoveAgent: (agentId: string) => Promise<void>
  onUploadUserDoc: (file: File) => Promise<void>
  onRefresh: () => void
  /** 改会话配置（文风 / 最高讨论轮次）；返回是否成功 */
  onUpdateSettings: (data: { userStyle: string; maxRounds: number }) => Promise<boolean>
  onUpdateLlm: (data: {
    llmApiKey?: string
    llmBaseUrl: string
    llmModel: string
    llmLabel: string
  }) => Promise<void>
}

export function AgentPanel({
  session,
  userDocument,
  busy,
  onAddAgent,
  onUploadDoc,
  onUploadMentorPapers,
  onOpenMentorPapers,
  onOpenMemory,
  onRemoveAgent,
  onUploadUserDoc,
  onRefresh,
  onUpdateSettings,
  onUpdateLlm,
}: Props) {
  const readyCount = session.agents.filter(
    (a) => a.knowledgeStatus === 'KNOWLEDGE_READY',
  ).length

  // 待确认移除的智能体（null = 无弹窗）
  const [pendingRemove, setPendingRemove] = useState<Agent | null>(null)
  const [removing, setRemoving] = useState(false)
  /** 「切换供应商」弹窗 */
  const [showProvider, setShowProvider] = useState(false)
  /** 「会话配置」弹窗（文风 / 最高轮次） */
  const [showSettings, setShowSettings] = useState(false)
  const [skillAgent, setSkillAgent] = useState<Agent | null>(null)

  const confirmRemove = async () => {
    if (!pendingRemove) return
    setRemoving(true)
    try {
      // 失败已被上层 toast，这里只负责收尾关窗
      await onRemoveAgent(pendingRemove.agentId)
    } finally {
      setRemoving(false)
      setPendingRemove(null)
    }
  }

  return (
    <aside className="panel panel-side">
      <div className="panel-head">
        <h2>领域席位</h2>
        <span className="hint">
          {readyCount}/{session.agents.length} 就绪
        </span>
      </div>
      <div className="panel-body">
        {/* 网关设置不再常驻展开——配一次就不动，占地方。只留摘要 + 一个按钮开弹窗。 */}
        <div className="card" style={{ marginBottom: '0.9rem' }}>
          <div className="agent-top">
            <div>
              <p className="agent-name" style={{ fontSize: '0.9rem' }}>
                会话网关
              </p>
              <div className="agent-meta">
                {session.llm?.configured
                  ? `${session.llm.label || '已配置'} · ${session.llm.model || '—'}`
                  : '未配置（Host 用服务器默认）'}
              </div>
            </div>
            <button
              type="button"
              className="btn btn-ghost btn-sm"
              onClick={() => setShowProvider(true)}
              disabled={busy}
            >
              切换供应商
            </button>
          </div>
        </div>

        <div className="card" style={{ marginBottom: '0.9rem' }}>
          <div className="agent-top">
            <div>
              <p className="agent-name" style={{ fontSize: '0.9rem' }}>
                会话配置
              </p>
              <div className="agent-meta">文风：{session.userStyle || '默认'}</div>
              <div className="agent-meta">
                最高轮次 {session.maxRounds} ·{' '}
                {session.autoDiscuss ? '自动讨论' : '手动讨论'}
                {session.docEditMode ? ' · 改稿模式' : ''}
              </div>
            </div>
            <button
              type="button"
              className="btn btn-ghost btn-sm"
              onClick={() => setShowSettings(true)}
              disabled={busy}
            >
              编辑
            </button>
          </div>
        </div>

        {session.docEditMode && (
          <UserDocCard
            doc={userDocument ?? null}
            busy={busy}
            onUpload={onUploadUserDoc}
          />
        )}

        <button
          type="button"
          className="btn btn-primary btn-block btn-sm"
          onClick={onAddAgent}
          disabled={busy}
        >
          + 添加智能体
        </button>

        <div className="section-gap">
          {session.agents.length === 0 ? (
            <p className="empty-inline">
              还没有领域智能体。
              <br />
              可勾选「使用会话网关」，不必每个都填 Key。
            </p>
          ) : (
            session.agents.map((a) => (
              <AgentCard
                key={a.agentId}
                agent={a}
                busy={busy}
                onUpload={(file) => onUploadDoc(a.agentId, file)}
                onUploadMentorPapers={(files) => onUploadMentorPapers(a.agentId, files)}
                onOpenMentorPapers={() => onOpenMentorPapers(a)}
                onOpenMemory={() => onOpenMemory(a)}
                onManageSkills={() => setSkillAgent(a)}
                onRemove={() => setPendingRemove(a)}
              />
            ))
          )}
        </div>

        <div className="stack-actions">
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            onClick={onRefresh}
            disabled={busy}
          >
            刷新状态
          </button>
        </div>
      </div>

      {pendingRemove && (
        <ConfirmDialog
          title="移除智能体"
          danger
          confirmText="移除"
          busy={removing}
          message={
            <>
              确定要把「{pendingRemove.name}」从本场席位移除吗？
              它已上传的文献知识会一并清掉，之后的讨论不再有它参与。
            </>
          }
          onConfirm={() => void confirmRemove()}
          onCancel={() => setPendingRemove(null)}
        />
      )}

      {showProvider && (
        <ProviderDialog
          session={session}
          busy={busy}
          onClose={() => setShowProvider(false)}
          onUpdate={onUpdateLlm}
        />
      )}

      {showSettings && (
        <SessionSettingsDialog
          session={session}
          busy={busy}
          onClose={() => setShowSettings(false)}
          onSubmit={onUpdateSettings}
        />
      )}

      {skillAgent && (
        <AgentSkillDialog
          agent={skillAgent}
          sessionId={session.sessionId}
          onClose={() => setSkillAgent(null)}
          onSaved={onRefresh}
        />
      )}
    </aside>
  )
}

function AgentCard({
  agent,
  busy,
  onUpload,
  onUploadMentorPapers,
  onOpenMentorPapers,
  onOpenMemory,
  onManageSkills,
  onRemove,
}: {
  agent: Agent
  busy: boolean
  onUpload: (file: File) => Promise<void>
  /** 导师席位专用：批量上传导师论文（逐篇串行，见 useMentors.uploadPapers） */
  onUploadMentorPapers: (files: File[]) => Promise<void>
  /** 导师席位专用：打开论文摘要弹窗 */
  onOpenMentorPapers: () => void
  /** 常驻身份的席位专用：打开经验心得只读弹窗 */
  onOpenMemory: () => void
  /** 打开该席位的 Skill 绑定弹窗。 */
  onManageSkills: () => void
  /** 只负责「请求移除」——确认弹窗由 AgentPanel 统一渲染 */
  onRemove: () => void
}) {
  const color = `var(--agent-${agentColorIndex(agent.agentId)})`
  return (
    <div className="card agent-card" style={{ ['--agent-color' as string]: color }}>
      <div className="agent-top">
        <div>
          <p className="agent-name">
            {agent.name}
            {agent.mentor && <span className="mentor-badge">导师</span>}
          </p>
          <div className="agent-meta">
            {agent.model}
            {agent.documentFileName ? ` · ${agent.documentFileName}` : ''}
          </div>
          {agent.mentor && agent.mentorDiscipline && (
            <div className="agent-meta">学科：{agent.mentorDiscipline}</div>
          )}
        </div>
        <StatusChip kind="knowledge" value={agent.knowledgeStatus} />
      </div>
      {agent.persona && <p className="agent-persona">{agent.persona}</p>}
      {agent.personality && (
        <div className="agent-personality">性格：{agent.personality}</div>
      )}
      {agent.statusMessage && <div className="muted">{agent.statusMessage}</div>}
      {/* 带经验上场：给用户一个「它这次不是从零开始」的可见证据，点开能看它到底学到了什么。
          点进去之后再校验 uuid 在库里还在不在 —— 席位上挂的是加入时的快照，
          智能体可能中途被用户删了，那时点开就是 401（前端会清登录态、弹登录框），所以由上层拦。 */}
      {agent.hasMemory && (
        <button
          type="button"
          className="agent-memory-link"
          onClick={onOpenMemory}
          disabled={busy}
        >
          带经验上场 · 更新于 {formatDateTimeMs(agent.memoryUpdatedAt)}
        </button>
      )}
      {agent.knowledgeStatus === 'VISION_PROCESSING' &&
        agent.progress &&
        agent.progress.total > 0 && (
          <ProgressBar
            current={agent.progress.current}
            total={agent.progress.total}
            ratio={agent.progress.ratio}
          />
        )}
      <div className="agent-actions">
        <button type="button" className="btn btn-ghost btn-sm" disabled={busy} onClick={onManageSkills}>
          Skills{agent.skillUuids?.length ? ` · ${agent.skillUuids.length}` : ''}
        </button>
        {agent.mentor ? (
          <>
            {/* 导师席位一次可传多篇，且是逐篇串行上传（后端逐篇落库，中断不白费） */}
            <label className="btn btn-ghost btn-sm file-btn">
              上传导师论文
              <input
                type="file"
                accept=".pdf,.docx,.txt,.md,.png,.jpg,.jpeg"
                multiple
                disabled={busy}
                onChange={(e) => {
                  const fs = Array.from(e.target.files || [])
                  if (fs.length) void onUploadMentorPapers(fs)
                  e.target.value = ''
                }}
              />
            </label>
            <button type="button" className="btn btn-ghost btn-sm" disabled={busy} onClick={onOpenMentorPapers}>
              查看论文摘要
            </button>
          </>
        ) : (
          <label className="btn btn-ghost btn-sm file-btn">
            上传文献
            <input
              type="file"
              accept=".pdf,.docx,.txt,.md,.png,.jpg,.jpeg"
              disabled={busy}
              onChange={(e) => {
                const f = e.target.files?.[0]
                if (f) void onUpload(f)
                e.target.value = ''
              }}
            />
          </label>
        )}
        <button
          type="button"
          className="btn btn-ghost btn-sm agent-remove-btn"
          disabled={busy}
          title="从本场席位移除"
          onClick={onRemove}
        >
          移除
        </button>
      </div>
    </div>
  )
}

function ProgressBar({
  current,
  total,
  ratio,
}: {
  current: number
  total: number
  ratio: number
}) {
  const pct = Math.max(0, Math.min(100, Math.round(ratio * 100)))
  return (
    <div className="progress" title={`Vision 进度：${current} / ${total}`}>
      <div className="progress-track">
        <div className="progress-fill" style={{ width: `${pct}%` }} />
      </div>
      <div className="progress-label muted">
        Vision {current} / {total} 页 · {pct}%
      </div>
    </div>
  )
}

function UserDocCard({
  doc,
  busy,
  onUpload,
}: {
  doc: UserDocStatus | null
  busy: boolean
  onUpload: (file: File) => Promise<void>
}) {
  /** 本地乐观推送：onChange 那一帧同步触发，不依赖父组件 state 同步 */
  const [optimistic, setOptimistic] = useState<UserDocStatus | null>(null)

  // 父组件传来新状态时,清掉乐观推送;若状态本身就是 DOCUMENT_UPLOADING 则保留文件名同步
  useEffect(() => {
    if (!doc) return
    if (doc.status === 'DOCUMENT_UPLOADING') {
      setOptimistic((prev) => (prev ? { ...prev, fileName: doc.fileName ?? prev.fileName } : prev))
      return
    }
    // 父组件已经拿到后端确认的非上传中状态,清掉本地乐观
    setOptimistic(null)
  }, [doc])

  const displayDoc: UserDocStatus | null = optimistic ?? doc
  const status = displayDoc?.status ?? 'AGENT_CREATED'
  const isWorking =
    status === 'DOCUMENT_UPLOADING' ||
    status === 'VISION_PROCESSING' ||
    status === 'STYLE_ANALYZING'
  const showProgress =
    isWorking && displayDoc?.progress && displayDoc.progress.total > 0
  const isReady = status === 'KNOWLEDGE_READY'
  const isFailed = status === 'VISION_FAILED'

  /** onChange 同步触发:本地立刻翻成「上传中」,再异步交回父组件真正上传 */
  const handlePick = (file: File) => {
    setOptimistic({
      status: 'DOCUMENT_UPLOADING',
      statusMessage: '用户文档上传中…',
      stageHint: '正在接收用户文档…',
      fileName: file.name,
      pageCount: 0,
      progress: { current: 0, total: 0, ratio: 0 },
      styleProfile: null,
    })
    void onUpload(file)
  }

  return (
    <div className="drawer">
      <div className="userdoc-head">
        <h3>待改文档</h3>
        <StatusChip kind="knowledge" value={status} />
      </div>

      {!displayDoc ? (
        <>
          <p className="muted" style={{ marginTop: 0 }}>
            上传后将在专业共识基础上生成修改建议
          </p>
          <label className="btn btn-ghost btn-sm file-btn">
            上传用户文档
            <input
              type="file"
              accept=".pdf,.docx,.txt,.md,.png,.jpg,.jpeg"
              disabled={busy}
              onChange={(e) => {
                const f = e.target.files?.[0]
                if (f) handlePick(f)
                e.target.value = ''
              }}
            />
          </label>
        </>
      ) : (
        <>
          <div className="userdoc-file muted">{displayDoc.fileName || '未命名'}</div>
          {displayDoc.stageHint && (
            <div className="userdoc-hint">{displayDoc.stageHint}</div>
          )}
          {showProgress && displayDoc.progress && (
            <ProgressBar
              current={displayDoc.progress.current}
              total={displayDoc.progress.total}
              ratio={displayDoc.progress.ratio}
            />
          )}
          {isReady && displayDoc.styleProfile?.summary && (
            <div className="userdoc-style muted">
              <span className="userdoc-style-label">文风画像</span>
              {displayDoc.styleProfile.summary}
            </div>
          )}
          {isFailed && (
            <div className="userdoc-fail">
              {displayDoc.statusMessage || '处理失败,请重试'}
            </div>
          )}
          <div className="stack-actions">
            <label
              className={`btn btn-ghost btn-sm file-btn ${
                isWorking || busy ? 'is-disabled' : ''
              }`}
            >
              {isFailed ? '重新上传' : '替换文档'}
              <input
                type="file"
                accept=".pdf,.docx,.txt,.md,.png,.jpg,.jpeg"
                disabled={busy || isWorking}
                onChange={(e) => {
                  const f = e.target.files?.[0]
                  if (f) handlePick(f)
                  e.target.value = ''
                }}
              />
            </label>
          </div>
        </>
      )}
    </div>
  )
}
