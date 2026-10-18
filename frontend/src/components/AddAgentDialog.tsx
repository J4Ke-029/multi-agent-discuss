import { useState } from 'react'
import type { PersistentAgentView } from '../hooks/usePersistentAgents'
import type { MemoryTargetType, MentorDto, MentorStatusView } from '../api/types'
import { agentColorIndex, mentorStatusLabels } from '../lib/labels'
import { EditableName } from './EditableName'
import { RegisterAgentForm, type RegisterAgentInput } from './RegisterAgentForm'
import { MentorForm, type MentorFormInput } from './MentorForm'
import { Modal, ModalTitle } from './Modal'

type Branch = 'register' | 'persistent'
/** 注册分支内的智能体类型。默认普通，保持与改动前一致 */
type AgentMode = 'normal' | 'mentor'
/** ② 常驻分支下的两个库。导师库原先挂在「① 注册」下面，跟「新建」混在一起 */
type PersistLib = 'normal' | 'mentor'

type Props = {
  /** 本场会话是否已配默认网关（常驻智能体若没自带网关，就只能走它） */
  hasSessionLlm: boolean
  sessionModel: string
  persistentAgents: PersistentAgentView[]
  /** 导师常驻库（账号级） */
  mentors: MentorDto[]
  /** 已在本场会话里的导师 uuid —— 用来把「加入本场讨论」置灰，而不是让用户点了吃 4xx */
  joinedMentorUuids: string[]
  /**
   * 已在本场会话里的常驻智能体 uuid。
   * 同一场里占两个席位会让两个席位写同一份心得互相覆盖，后端 {@code assertJoinable} 会拒绝，
   * 所以这里先把入口关掉，别让用户点了才吃一个报错 toast。
   */
  joinedAgentUuids: string[]
  /** 正在分析的导师进度；null 表示当前没有上传在跑 */
  mentorProgress: MentorStatusView | null
  busy: boolean
  onClose: () => void
  /** ① 注册新的领域智能体 */
  onRegister: (data: RegisterAgentInput) => Promise<void>
  /** ①-b 新建导师（可同时带若干篇论文，逐篇串行上传） */
  onMentorCreate: (input: MentorFormInput, files: File[]) => Promise<void>
  /** 把已有的导师加入本场会话 */
  onMentorJoin: (mentor: MentorDto) => Promise<void>
  onMentorRemove: (id: number) => void
  onMentorOpenPapers: (mentor: MentorDto) => void
  /**
   * ② 把常驻智能体加入本场会话。用谁的网关由后端按「自带优先、没有就随本场」定，
   * 前端不传选择 —— 要换网关走卡片上的「重新配置网关」。
   */
  onUsePersistent: (agent: PersistentAgentView) => Promise<void>
  onRemovePersistent: (agentId: string) => void
  onUpdatePersistentName: (agentId: string, newName: string) => void
  /** 打开某个常驻智能体的网关：看 / 改它自己存的那份，或改回「随本场会话网关」 */
  onOpenGateway: (agent: PersistentAgentView) => void
  /** 打开某个常驻智能体的**知识库**：它跨会话攒下的资料（只读 + 删除 + 摘要重试） */
  onOpenDocs: (agent: PersistentAgentView) => void
  /** 打开某个常驻对象（智能体 / 导师）的经验心得只读弹窗 */
  onOpenMemory: (type: MemoryTargetType, uuid: string, name: string) => void
}

/**
 * 「添加智能体」弹窗：两条路径二选一。
 *
 * <p>① 注册新的智能体 —— 先选类型：**普通**（表单同原来的内联版）或**导师**。
 * 选导师时表单换成「称呼 / 学科方向 / 指导重点 + 导师论文」。这一分支只负责**新建**。
 * <p>② 使用常驻智能体 —— 再从两个库二选一：**常驻普通智能体** 或 **常驻导师库**，
 * 都是「挑现成的加入本场」。顺带承担两边的管理：常驻智能体改名（点名字）/ 删除（×）、
 * 导师删除（×）——右侧栏那个面板已移除，这里是它们唯一的入口。
 *
 * <p>导师库刻意放在 ② 而不是 ①：① 是「新建」，② 是「用已有的」，把导师库塞进 ①
 * 会让「新建导师」和「加入已有导师」在同一屏里混着，语义不清。
 */
export function AddAgentDialog({
  hasSessionLlm,
  sessionModel,
  persistentAgents,
  mentors,
  joinedMentorUuids,
  joinedAgentUuids,
  mentorProgress,
  busy,
  onClose,
  onRegister,
  onMentorCreate,
  onMentorJoin,
  onMentorRemove,
  onMentorOpenPapers,
  onUsePersistent,
  onRemovePersistent,
  onUpdatePersistentName,
  onOpenGateway,
  onOpenDocs,
  onOpenMemory,
}: Props) {
  const [branch, setBranch] = useState<Branch>('register')
  const [agentMode, setAgentMode] = useState<AgentMode>('normal')
  const [persistLib, setPersistLib] = useState<PersistLib>('normal')
  const [addingId, setAddingId] = useState<string | null>(null)
  const [joiningMentorId, setJoiningMentorId] = useState<number | null>(null)
  /** 一个都没有自带网关、本场也没配 —— 这些卡片怎么点都进不来，用来决定黄条显不显示 */
  const anyUnjoinable = !hasSessionLlm && persistentAgents.some((p) => !p.hasOwnGateway)

  const handleUse = async (p: PersistentAgentView) => {
    setAddingId(p.agentId)
    try {
      await onUsePersistent(p)
    } finally {
      setAddingId(null)
    }
  }

  const handleJoinMentor = async (m: MentorDto) => {
    setJoiningMentorId(m.id)
    try {
      await onMentorJoin(m)
    } finally {
      setJoiningMentorId(null)
    }
  }

  return (
    <Modal className="save-dialog add-agent-dialog" onClose={onClose}>
        <div className="add-agent-head">
          <ModalTitle asChild><h3>添加智能体</h3></ModalTitle>
          <button
            type="button"
            className="auth-close"
            onClick={onClose}
            aria-label="关闭"
            title="关闭"
          >
            ✕
          </button>
        </div>

        <div className="add-agent-branches">
          <button
            type="button"
            className={`add-agent-branch${branch === 'register' ? ' is-active' : ''}`}
            onClick={() => setBranch('register')}
          >
            <span className="add-agent-branch-no">①</span>
            <span className="add-agent-branch-text">
              <strong>注册新的领域智能体</strong>
              <em>填名称 / 人设，可选性格关键词</em>
            </span>
          </button>
          <button
            type="button"
            className={`add-agent-branch${branch === 'persistent' ? ' is-active' : ''}`}
            onClick={() => setBranch('persistent')}
          >
            <span className="add-agent-branch-no">②</span>
            <span className="add-agent-branch-text">
              <strong>使用常驻智能体</strong>
              <em>
                {persistentAgents.length === 0 && mentors.length === 0
                  ? '暂无常驻智能体'
                  : `普通 ${persistentAgents.length} · 导师 ${mentors.length}`}
              </em>
            </span>
          </button>
        </div>

        <div className="add-agent-body">
          {branch === 'register' ? (
            <>
              {/* 智能体类型：普通（原有表单一行未改）或导师（论文语料模拟指导） */}
              <div className="agent-mode-row" role="radiogroup" aria-label="智能体类型">
                <button
                  type="button"
                  role="radio"
                  aria-checked={agentMode === 'normal'}
                  className={`agent-mode-option${agentMode === 'normal' ? ' is-active' : ''}`}
                  onClick={() => setAgentMode('normal')}
                >
                  <strong>普通智能体</strong>
                  <em>上传私有文献，按领域发言</em>
                </button>
                <button
                  type="button"
                  role="radio"
                  aria-checked={agentMode === 'mentor'}
                  className={`agent-mode-option${agentMode === 'mentor' ? ' is-active' : ''}`}
                  onClick={() => setAgentMode('mentor')}
                >
                  <strong>导师智能体</strong>
                  <em>上传导师论文，按学术风格给指导</em>
                </button>
              </div>

              {agentMode === 'normal' ? (
                <RegisterAgentForm
                  busy={busy}
                  hasSessionLlm={hasSessionLlm}
                  sessionModel={sessionModel}
                  onCancel={onClose}
                  onSubmit={onRegister}
                />
              ) : (
                <>
                  <MentorForm
                    busy={busy}
                    hasSessionLlm={hasSessionLlm}
                    sessionModel={sessionModel}
                    onSubmit={onMentorCreate}
                    onCancel={onClose}
                  />

                  <MentorProgress progress={mentorProgress} />

                  <p className="muted add-agent-foot">
                    已有导师不用在这里重建：到「② 使用常驻智能体 → 常驻导师库」里直接加入本场。
                  </p>
                </>
              )}
            </>
          ) : (
            <>
              {/* 两个库并列。样式复用 ① 的类型单选行（.agent-mode-row）——同一套「两选一卡片」，
                  没必要再定义一份长得一模一样的类。 */}
              <div className="agent-mode-row" role="radiogroup" aria-label="常驻库">
                <button
                  type="button"
                  role="radio"
                  aria-checked={persistLib === 'normal'}
                  className={`agent-mode-option${persistLib === 'normal' ? ' is-active' : ''}`}
                  onClick={() => setPersistLib('normal')}
                >
                  <strong>常驻普通智能体</strong>
                  <em>
                    {persistentAgents.length > 0
                      ? `${persistentAgents.length} 个可复用`
                      : '暂无'}
                  </em>
                </button>
                <button
                  type="button"
                  role="radio"
                  aria-checked={persistLib === 'mentor'}
                  className={`agent-mode-option${persistLib === 'mentor' ? ' is-active' : ''}`}
                  onClick={() => setPersistLib('mentor')}
                >
                  <strong>常驻导师库</strong>
                  <em>{mentors.length > 0 ? `${mentors.length} 位导师` : '暂无'}</em>
                </button>
              </div>

              {persistLib === 'mentor' ? (
                <>
                  {/* 上传可能是在智能体卡片上发起的（给某个席位追加论文），此时这里也看得到进度 */}
                  <MentorProgress progress={mentorProgress} />

                  {mentors.length === 0 ? (
                    <div className="empty-state">
                      <p>还没有导师</p>
                      <p className="muted" style={{ fontSize: '0.85rem' }}>
                        到「① 注册新的领域智能体」里选「导师智能体」，上传导师论文后就会出现在这里。
                      </p>
                    </div>
                  ) : (
                    <>
                      <div className="persistent-agents-list">
                        {mentors.map((m) => {
                          // 同一个导师在一场里只该有一个席位：后端 joinSession 会拒绝重复加入，
                          // 所以这里先把入口关掉，别让用户点了才吃一个报错 toast。
                          const joined = joinedMentorUuids.includes(m.mentorUuid)
                          return (
                            <div
                              key={m.id}
                              className="agent-card persistent-agent-card"
                              style={{
                                ['--agent-color' as string]: `var(--agent-${agentColorIndex(m.mentorUuid)})`,
                              }}
                            >
                              <div className="agent-top">
                                <span className="agent-name">{m.name}</span>
                                <button
                                  className="remove-agent-btn"
                                  onClick={() => onMentorRemove(m.id)}
                                  title="删除导师（连带其论文）"
                                  disabled={busy}
                                >
                                  ×
                                </button>
                              </div>
                              <div className="agent-meta">
                                {m.discipline || '未填学科'} · {m.paperCount} 篇论文 · {m.pageCount} 页
                              </div>
                              <div className="mentor-status-line">
                                <span className={`chip ${m.status === 'READY' ? 'chip-ready' : m.status === 'FAILED' ? 'chip-fail' : 'chip-idle'}`}>
                                  {mentorStatusLabels[m.status]}
                                </span>
                                {m.statusMessage && <span className="muted">{m.statusMessage}</span>}
                              </div>
                              <div className="agent-actions">
                                <button
                                  type="button"
                                  className="btn btn-accent btn-sm"
                                  onClick={() => void handleJoinMentor(m)}
                                  disabled={busy || joined || joiningMentorId === m.id || m.paperCount === 0}
                                >
                                  {joined
                                    ? '已在本场'
                                    : joiningMentorId === m.id
                                      ? '加入中…'
                                      : '加入本场讨论'}
                                </button>
                                <button
                                  type="button"
                                  className="btn btn-ghost btn-sm"
                                  onClick={() => onMentorOpenPapers(m)}
                                  disabled={busy}
                                >
                                  查看论文摘要
                                </button>
                                {/* 跨会话心得：导师用得越久越贴合它的领域 */}
                                <button
                                  type="button"
                                  className="btn btn-ghost btn-sm"
                                  onClick={() => onOpenMemory('MENTOR', m.mentorUuid, m.name)}
                                  disabled={busy}
                                >
                                  经验
                                </button>
                              </div>
                              {m.paperCount === 0 && (
                                <p className="muted">
                                  还没有论文，加入后用智能体卡片上的「上传导师论文」补充。
                                </p>
                              )}
                            </div>
                          )
                        })}
                      </div>

                      <p className="muted add-agent-foot">
                        导师跨会话复用：论文只分析一次，换个会话可直接加入，不用重传。× 删除导师会
                        连带删掉它名下的论文与页知识（已加入的会话不受影响）。导师用自己存的网关 Key，
                        不依赖本场会话网关。
                      </p>
                    </>
                  )}
                </>
              ) : persistentAgents.length === 0 ? (
                <div className="empty-state">
                  <p>还没有常驻智能体</p>
                  <p className="muted" style={{ fontSize: '0.85rem' }}>
                    结束会话时可以勾选「保存智能体」把它们存进常驻列表，下次就能一键复用。
                  </p>
                </div>
              ) : (
                <>
                  {anyUnjoinable && (
                    <div className="add-agent-warn">
                      本场会话还没配默认网关，而下面有常驻智能体<strong>没存过自己的网关</strong>：
                      它们只能走会话网关，加入不了。请先在左侧「会话网关」里配好，或者点卡片上的
                      「网关」给它们各配一份。
                    </div>
                  )}

                  <div className="persistent-agents-list">
                    {persistentAgents.map((p) => {
                      // 同一场里一个常驻智能体只该占一个席位（两个席位会写同一份心得互相覆盖），
                      // 后端会拒绝重复加入 —— 这里先关掉入口，别让用户点了才吃报错
                      const joined = joinedAgentUuids.includes(p.agentId)
                      // 用谁的网关不再让用户在卡片上选：**自带优先**，没有自带就随本场。
                      // 要换就点「重新配置网关」去改它自己存的那份。所以只有一种情况点不动 ——
                      // 它没存过网关，而本场也没配。
                      const cannotJoin = !p.hasOwnGateway && !hasSessionLlm
                      return (
                        <div
                          key={p.agentId}
                          className="agent-card persistent-agent-card"
                          style={{
                            ['--agent-color' as string]: `var(--agent-${agentColorIndex(p.agentId)})`,
                          }}
                        >
                          <div className="agent-top">
                            <EditableName
                              value={p.name}
                              onSubmit={(v) => onUpdatePersistentName(p.agentId, v)}
                            />
                            <button
                              className="remove-agent-btn"
                              onClick={() => onRemovePersistent(p.agentId)}
                              title="从常驻列表移除"
                            >
                              ×
                            </button>
                          </div>
                          <div className="agent-meta">
                            {p.hasOwnGateway
                              ? `自带网关 · ${p.model || '默认模型'} · ${p.apiKey}`
                              : '无自带网关 · 加入时随本场会话网关'}
                            {p.docCount > 0 && (
                              <span className="agent-meta-doc">
                                {' '}
                                · {p.docCount} 个文件 · {p.pageCount} 页
                              </span>
                            )}
                          </div>
                          {p.persona && <p className="agent-persona">{p.persona}</p>}
                          {p.personality && (
                            <div className="agent-personality">性格：{p.personality}</div>
                          )}
                          <div className="agent-actions">
                            <button
                              type="button"
                              className="btn btn-accent btn-sm"
                              onClick={() => void handleUse(p)}
                              disabled={busy || joined || addingId === p.agentId || cannotJoin}
                            >
                              {joined
                                ? '已在本场'
                                : addingId === p.agentId
                                  ? '加入中…'
                                  : '加入本场讨论'}
                            </button>
                            {/* 跨会话心得：用它用得越久，它越知道该怎么在这个圆桌里把话说清楚 */}
                            <button
                              type="button"
                              className="btn btn-ghost btn-sm"
                              onClick={() => onOpenMemory('PERSISTENT_AGENT', p.agentId, p.name)}
                              disabled={busy}
                            >
                              经验
                            </button>
                            {/* 跨会话知识库：它在会话里读过的资料，结束会话时存进来，下次不用重传。
                                没有知识时按钮不禁用 —— 点开是一段说明来历与其限制的空态文案，
                                比一个点不动的灰按钮有用。 */}
                            <button
                              type="button"
                              className="btn btn-ghost btn-sm"
                              onClick={() => onOpenDocs(p)}
                              disabled={busy}
                            >
                              知识{p.docCount > 0 ? ` ${p.docCount}` : ''}
                            </button>
                            {/* 改它自己存的网关（加密入库），或改回「随本场会话网关」。
                                新加入的席位据此跑；已经在本场的席位用的是加入时的快照。 */}
                            <button
                              type="button"
                              className="btn btn-ghost btn-sm"
                              onClick={() => onOpenGateway(p)}
                              disabled={busy}
                            >
                              重新配置网关
                            </button>
                          </div>
                        </div>
                      )
                    })}
                  </div>

                  <p className="muted add-agent-foot">
                    点名字可改名，× 从常驻列表移除（会连带删掉它攒下的知识与经验，需确认）。
                    加入后按它自己的网关跑（没存过就随本场会话网关），要换网关点「重新配置网关」
                    ——那里只改常驻里存的那份，不动人设。
                  </p>
                </>
              )}
            </>
          )}
        </div>
    </Modal>
  )
}

/**
 * 论文分析进度条。两处会同时用上它：
 * ① 新建导师带论文时（上传就在这个弹窗里跑）；
 * ② 常驻导师库页签（用户可能在智能体卡片上给某个席位追加论文，切过来也看得到进度）。
 */
function MentorProgress({ progress }: { progress: MentorStatusView | null }) {
  if (!progress) return null
  return (
    <div className="mentor-progress">
      <strong>{progress.statusMessage || '正在分析导师论文…'}</strong>
      {progress.progressTotal > 0 && (
        <span className="muted">
          {progress.progressCurrent} / {progress.progressTotal} 页
        </span>
      )}
    </div>
  )
}
