import type { ArchivedSummary, QuestionProgress, SessionSummary } from '../api/types'
import { formatRelativeDay } from '../lib/time'
import { StatusChip } from './StatusChip'

type Props = {
  /** 用户的会话列表（GET /api/sessions，由 App 轮询刷新） */
  sessions: SessionSummary[]
  activeSessionId: string | null
  onSelectSession: (sid: string) => void
  /** 下面几项只作用于「当前展开的那一个对话」的议题子列表 */
  questionIds: string[]
  activeQid: string | null
  previews: Record<string, string>
  progresses: Record<string, QuestionProgress | undefined>
  onSelectQuestion: (qid: string) => void
  /** 已归档（MySQL，只读） */
  archived: ArchivedSummary[]
  activeArchivedId: number | null
  onSelectArchived: (id: number) => void
  onDeleteArchived: (id: number) => void
}

/**
 * 右侧「对话」工作台。
 *
 * <p>当前对话展开显示它自己的议题（原来是 QuestionRail 的职责，现挂在对应对话下面）；
 * 其它对话收起。切换对话**不影响服务端讨论**——独立思考是丢到后台线程池跑的
 * （{@code DiscussionPipeline} 的 thinkExecutor），前端轮询只是读状态。
 *
 * <p>「已归档」分段属于 Phase 3，这里还只有进行中的对话。
 */
export function ConversationRail({
  sessions,
  activeSessionId,
  onSelectSession,
  questionIds,
  activeQid,
  previews,
  progresses,
  onSelectQuestion,
  archived,
  activeArchivedId,
  onSelectArchived,
  onDeleteArchived,
}: Props) {
  const runningCount = sessions.filter((s) => s.activeCount > 0).length

  return (
    <aside className="panel panel-right">
      <div className="panel-head">
        <h2>对话</h2>
        <span className="hint">
          {runningCount > 0 ? `${runningCount} 个进行中` : `${sessions.length} 个`}
        </span>
      </div>
      <div className="panel-body">
        {sessions.length === 0 ? (
          <p className="empty-inline">还没有对话。开厅后会出现在这里。</p>
        ) : (
          sessions.map((s) => {
            const isActive = s.sessionId === activeSessionId
            return (
              <div key={s.sessionId} className={`conv-item${isActive ? ' active' : ''}`}>
                <button
                  type="button"
                  className="conv-head"
                  onClick={() => onSelectSession(s.sessionId)}
                  title={s.title}
                >
                  <span className="conv-line">
                    <span className="conv-title">{s.title}</span>
                    {s.status ? (
                      <StatusChip kind="question" value={s.status} />
                    ) : (
                      <span className="conv-dot" aria-hidden />
                    )}
                  </span>
                  <span className="conv-meta">
                    {s.questionCount} 议题 · {s.agentCount} 智能体 · {formatRelativeDay(s.updatedAt)}
                    {s.activeCount > 0 ? ` · ${s.activeCount} 进行中` : ''}
                  </span>
                </button>

                {isActive && questionIds.length > 0 && (
                  <div className="conv-questions">
                    {[...questionIds].reverse().map((qid) => {
                      const p = progresses[qid]
                      return (
                        <button
                          key={qid}
                          type="button"
                          className={`conv-q-item${activeQid === qid ? ' active' : ''}`}
                          onClick={() => onSelectQuestion(qid)}
                        >
                          <span className="conv-q-text">{previews[qid] || '（加载中）'}</span>
                          {p?.status && <StatusChip kind="question" value={p.status} />}
                        </button>
                      )
                    })}
                  </div>
                )}
              </div>
            )
          })
        )}

        {archived.length > 0 && (
          <div className="conv-archived">
            <div className="conv-section-head">
              <span>已归档</span>
              <span className="hint">{archived.length}</span>
            </div>
            {archived.map((a) => (
              <div
                key={a.id}
                className={`conv-item archived${activeArchivedId === a.id ? ' active' : ''}`}
              >
                <div className="conv-arch-row">
                  <button
                    type="button"
                    className="conv-head"
                    onClick={() => onSelectArchived(a.id)}
                    title={`${a.title}（只读）`}
                  >
                    <span className="conv-line">
                      <span className="conv-title">{a.title}</span>
                      <span className="conv-badge">只读</span>
                    </span>
                    <span className="conv-meta">
                      {a.questionCount} 议题 · {a.agentCount} 智能体
                    </span>
                  </button>
                  <button
                    type="button"
                    className="conv-del"
                    title="删除这条归档（不可恢复）"
                    onClick={() => onDeleteArchived(a.id)}
                  >
                    ×
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </aside>
  )
}
