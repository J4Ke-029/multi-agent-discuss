import { useEffect, useMemo, useState } from 'react'
import { diffWordsWithSpace } from 'diff'
import type {
  Agent,
  AgentThought,
  ChangeLogEntry,
  ProfessionalConsensus,
  QuestionProgress,
  QuestionStatus,
  RevisedDocument,
  RevisionPlan,
  Session,
  Transcript,
  Turn,
} from '../api/types'
import type { SummaryResult } from '../api/client'
import { agentColorIndex, isQuestionActive, phaseLabels, questionLabels, thoughtLabels } from '../lib/labels'
import { saveMarkdownAs } from '../lib/save'
import { formatClock } from '../lib/time'
import { StatusChip } from './StatusChip'
import { Modal, ModalTitle } from './Modal'
import { TokenUsageChip } from './TokenUsageChip'

type Props = {
  session: Session
  activeQid: string | null
  progress: QuestionProgress | null
  transcript: Transcript | null
  revisionPlan: RevisionPlan | null
  revisedDocument: RevisedDocument | null
  busy: boolean
  onAsk: (content: string) => Promise<void>
  onDiscuss: () => Promise<void>
  onToggleInterjections: (allow: boolean) => Promise<void>
  onInterject: (content: string, targetAgentId?: string) => Promise<void>
  onContinueDiscussion: () => Promise<void>
  onConfirmRevision: (ids: string[], note: string) => Promise<void>
  /** 「继续改稿」：在当前改写结果上按用户要求再改一轮 */
  onContinueRevision: (requirement: string) => Promise<void>
  onGenerateSummary: (qid: string, scope: string) => Promise<SummaryResult>
  questionPreviews: Record<string, string>
}

export function DiscussionStage({
  session,
  activeQid,
  progress,
  transcript,
  revisionPlan,
  revisedDocument,
  busy,
  onAsk,
  onDiscuss,
  onToggleInterjections,
  onInterject,
  onContinueDiscussion,
  onConfirmRevision,
  onContinueRevision,
  onGenerateSummary,
  questionPreviews,
}: Props) {
  const [draft, setDraft] = useState('')
  const [interjectionTarget, setInterjectionTarget] = useState('')
  const [summary, setSummary] = useState<SummaryResult | null>(null)
  const readyAgents = session.agents.filter((a) => a.knowledgeStatus === 'KNOWLEDGE_READY')
  const canAsk = readyAgents.length > 0 && !busy

  const status = progress?.status
  const active = status ? isQuestionActive(status) : false
  const waitingForInterjection = status === 'WAITING_FOR_INTERJECTION'
  const participatingAgents = readyAgents.filter((agent) => {
    const transcriptThought = transcript?.thoughts?.[agent.agentId]
    return transcriptThought
      ? transcriptThought.state === 'DONE'
      : progress?.thoughts?.[agent.agentId] === 'DONE'
  })

  /** 完成态下用户选的下一步：还没选 / 继续讨论 / 继续改稿 */
  const [followUp, setFollowUp] = useState<'none' | 'ask' | 'revise'>('none')

  // 切议题时收起「继续…」输入态，否则会把上一条的要求带到新议题上
  useEffect(() => {
    setFollowUp('none')
  }, [activeQid])

  const needsManualDiscuss =
    status === 'ALL_THOUGHTS_READY' && !session.autoDiscuss

  /** 议题已经跑到终态（完成 / 改稿完成 / 失败）—— 此时底部改问「接下来做什么」 */
  const finished =
    status === 'COMPLETED' || status === 'REVISED_COMPLETED' || status === 'FAILED'

  // token 统计来源：transcript（精确累计）优先，progress（轮询）兜底
  const tokenUsage = transcript?.tokenUsage ?? progress?.tokenUsage
  const tokenActive = active && !finished

  const thoughts = useMemo(() => {
    if (!transcript?.thoughts) return [] as AgentThought[]
    return Object.values(transcript.thoughts)
  }, [transcript])

  const agentMap = useMemo(() => {
    const m = new Map<string, Agent>()
    session.agents.forEach((a) => m.set(a.agentId, a))
    return m
  }, [session.agents])

  return (
    <section className="panel panel-center stage">
      <div className="stage-scroll">
        {!activeQid ? (
          <EmptyStage readyCount={readyAgents.length} total={session.agents.length} />
        ) : (
          <>
            <div className="status-banner">
              <span
                className={`status-pulse ${
                  status === 'FAILED'
                    ? 'fail'
                    : status === 'COMPLETED' || status === 'REVISED_COMPLETED'
                      ? 'done'
                      : ''
                }`}
              />
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontWeight: 700, fontSize: '0.92rem' }}>
                  {status ? questionLabels[status] : '…'}
                  {progress?.errorMessage ? ` · ${progress.errorMessage}` : ''}
                </div>
                <div className="muted" style={{ marginTop: 2 }}>
                  {progress?.stageHint ||
                    transcript?.content ||
                    questionPreviews[activeQid] ||
                    activeQid}
                </div>
              </div>
              {status && <StatusChip kind="question" value={status} />}
              {tokenUsage && <TokenUsageChip usage={tokenUsage} active={tokenActive} />}
            </div>

            {status === 'THINKING' && (
              <p className="muted" style={{ marginBottom: '1rem' }}>
                须等全部智能体独立思考结束后，才会进入圆桌讨论
                {session.autoDiscuss ? '（已开启自动讨论）' : '；届时可手动开启'}。
              </p>
            )}

            {needsManualDiscuss && (
              <div className="stack-actions" style={{ marginBottom: '1rem' }}>
                <button
                  type="button"
                  className="btn btn-accent btn-sm"
                  disabled={busy}
                  onClick={() => void onDiscuss()}
                >
                  全部思考完成 · 开启圆桌讨论
                </button>
              </div>
            )}

            {finished && activeQid && (
              <div className="final-block summary-actions">
                <h3>文档总结</h3>
                <p className="muted" style={{ margin: '0 0 0.6rem' }}>
                  讨论已结束，可生成一份 Markdown 总结文件。
                </p>
                <div className="stack-actions">
                  <button
                    type="button"
                    className="btn btn-accent btn-sm"
                    disabled={busy}
                    onClick={() => {
                      void onGenerateSummary(activeQid, 'CURRENT_QUESTION')
                        .then((r) => setSummary(r))
                        .catch((e) => alert(`生成总结失败：${e instanceof Error ? e.message : String(e)}`))
                    }}
                  >
                    生成本议题总结
                  </button>
                  {session.questionIds.length > 1 && (
                    <button
                      type="button"
                      className="btn btn-ghost btn-sm"
                      disabled={busy}
                      onClick={() => {
                        void onGenerateSummary(activeQid, 'ALL_QUESTIONS')
                          .then((r) => setSummary(r))
                          .catch((e) => alert(`生成总结失败：${e instanceof Error ? e.message : String(e)}`))
                      }}
                    >
                      生成整个会话总结
                    </button>
                  )}
                </div>
              </div>
            )}

            {summary && (
              <SummaryDialog result={summary} onClose={() => setSummary(null)} />
            )}

            {(thoughts.length > 0 || (progress && Object.keys(progress.thoughts || {}).length > 0)) && (
              <ThoughtSeats
                thoughts={thoughts}
                progressThoughts={progress?.thoughts || {}}
                agentMap={agentMap}
              />
            )}

            {transcript?.transcript && transcript.transcript.length > 0 && (
              <div className="transcript-section">
                <div className="panel-head transcript-head">
                  <h2>讨论笔录</h2>
                  <span className="hint">{transcript.transcript.length} 轮发言</span>
                </div>
                <div className="transcript-list">
                  <Timeline turns={transcript.transcript} consensus={transcript.professionalConsensus} />
                </div>
              </div>
            )}

            {(transcript?.finalAnswer || progress?.finalAnswer) && (
              <div className="final-block">
                <h3>终稿</h3>
                <p className="final-answer">
                  {transcript?.finalAnswer || progress?.finalAnswer}
                </p>
                {(transcript?.explanation || progress?.explanation) && (
                  <p className="final-explain">
                    {transcript?.explanation || progress?.explanation}
                  </p>
                )}
              </div>
            )}

            {(status === 'REVISION_PLAN_READY' ||
              status === 'AWAITING_CONFIRM' ||
              revisionPlan) &&
              revisionPlan && (
                <RevisionPanel
                  plan={revisionPlan}
                  busy={busy}
                  status={status}
                  onConfirm={onConfirmRevision}
                />
              )}

            {revisedDocument?.revisedFullText && (
              <div className="final-block" style={{ marginTop: '1rem' }}>
                <h3>改写结果</h3>
                <p className="final-answer">{revisedDocument.revisedFullText}</p>
                {revisedDocument.changeLog && revisedDocument.changeLog.length > 0 && (
                  <div style={{ marginTop: '0.85rem' }}>
                    <div className="muted" style={{ marginBottom: '0.45rem' }}>
                      变更记录（{revisedDocument.changeLog.length} 项）
                    </div>
                    {revisedDocument.changeLog.map((c, i) => (
                      <DiffEntry key={i} entry={c} index={i} />
                    ))}
                  </div>
                )}
              </div>
            )}

            {active && !transcript?.transcript?.length && thoughts.length === 0 && (
              <p className="empty-inline">智能体正在独立思考，笔录将陆续出现…</p>
            )}
          </>
        )}
      </div>

      <div className="composer">
        {finished && followUp === 'none' ? (
          <div className="followup-chooser">
            <div className="followup-hint">
              {status === 'REVISED_COMPLETED' ? '改稿已完成' : '议题已完成'} · 可以继续往下走
            </div>
            <div className="followup-actions">
              <button
                type="button"
                className="btn btn-accent"
                onClick={() => setFollowUp('ask')}
                disabled={busy || readyAgents.length === 0}
              >
                继续讨论
              </button>
              {session.docEditMode && (
                <button
                  type="button"
                  className="btn btn-ghost"
                  onClick={() => setFollowUp('revise')}
                  disabled={busy}
                >
                  继续改稿
                </button>
              )}
            </div>
            <div className="followup-note muted">
              {session.docEditMode
                ? '继续改稿会在当前改写结果上再改一轮'
                : '继续讨论会新开一个议题'}
            </div>
          </div>
        ) : (
          <form
            onSubmit={(e) => {
              e.preventDefault()
              const text = draft.trim()
              if (!text) return
              if (followUp === 'revise') {
                if (busy) return
                void onContinueRevision(text).then(() => {
                  setDraft('')
                  setFollowUp('none')
                })
                return
              }
              if (waitingForInterjection) {
                if (busy) return
                void onInterject(text, interjectionTarget || undefined).then(() => setDraft(''))
                return
              }
              if (!canAsk) return
              void onAsk(text).then(() => setDraft(''))
            }}
          >
            {followUp === 'revise' && (
              <div className="followup-back">
                <button
                  type="button"
                  className="btn btn-ghost btn-sm"
                  onClick={() => setFollowUp('none')}
                >
                  ← 返回
                </button>
                <span className="muted">在当前改写结果上再改一轮</span>
              </div>
            )}
            <div className="composer-row">
              <textarea
                className="textarea"
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
                placeholder={
                  followUp === 'revise'
                    ? '说明这一次想怎么改，例如：第三段太啰嗦，压到 3 句以内'
                    : waitingForInterjection
                      ? '补充信息，或选择一位智能体进行追问…'
                    : active
                      ? '当前议题进行中；开启“允许插话”后可在轮次结束时补充…'
                    : readyAgents.length === 0
                      ? '至少一位智能体知识就绪后即可提问…'
                      : '向圆桌提出问题，例如：根据文献，第二相析出对硬度的影响是什么？'
                }
                disabled={followUp === 'revise' ? busy : waitingForInterjection ? busy : !canAsk || active}
                onKeyDown={(e) => {
                  // 中文输入法组字期间的回车是「选词」，不能当发送。
                  // isComposing 是标准属性；keyCode 229 兜底部分老浏览器/输入法。
                  if (e.nativeEvent.isComposing || e.nativeEvent.keyCode === 229) return
                  if (e.key !== 'Enter') return
                  // Shift + Enter 留给换行，其余（含 Ctrl/⌘ + Enter）都发送
                  if (e.shiftKey) return
                  e.preventDefault()
                  e.currentTarget.form?.requestSubmit()
                }}
              />
              <button
                className="btn btn-accent"
                type="submit"
                disabled={followUp === 'revise'
                  ? busy || !draft.trim()
                  : waitingForInterjection
                    ? busy || !draft.trim()
                    : !canAsk || active || !draft.trim()}
              >
                {followUp === 'revise' ? '再改一轮' : waitingForInterjection ? '发送插话' : '提问'}
              </button>
            </div>
            {followUp !== 'revise' && (
              <div className="interjection-controls">
                <label className="interjection-toggle">
                  <input type="checkbox" checked={Boolean(session.allowInterjections)} disabled={busy}
                    onChange={(event) => void onToggleInterjections(event.target.checked)} />
                  <span>允许插话</span>
                </label>
                {waitingForInterjection && <>
                  <select className="input interjection-target" aria-label="插话对象"
                    value={interjectionTarget} disabled={busy}
                    onChange={(event) => setInterjectionTarget(event.target.value)}>
                    <option value="">对全体补充</option>
                    {participatingAgents.map((agent) => <option key={agent.agentId} value={agent.agentId}>追问 · {agent.name}</option>)}
                  </select>
                  <button type="button" className="btn btn-ghost btn-sm" disabled={busy}
                    onClick={() => void onContinueDiscussion()}>不插话，继续讨论</button>
                </>}
              </div>
            )}
            <div className="muted" style={{ marginTop: '0.4rem' }}>
              {followUp === 'revise'
                ? '只改你提到的部分，其余保持不动 · Enter 发送 · Shift + Enter 换行'
                : `${readyAgents.length} 位专家就绪 · Enter 发送 · Shift + Enter 换行`}
            </div>
          </form>
        )}
      </div>
    </section>
  )
}

function EmptyStage({ readyCount, total }: { readyCount: number; total: number }) {
  return (
    <div style={{ maxWidth: 480, margin: '2.5rem auto', textAlign: 'center' }}>
      <div className="landing-eyebrow">圆桌中央</div>
      <h2
        style={{
          fontFamily: 'var(--font-display)',
          fontSize: '1.6rem',
          margin: '0.4rem 0 0.7rem',
        }}
      >
        等待第一道议题
      </h2>
      <p className="muted" style={{ fontSize: '0.95rem', lineHeight: 1.6 }}>
        {total === 0
          ? '先在左侧注册领域智能体，并为每位上传文献。'
          : readyCount === 0
            ? '智能体已就位，上传并完成文献阅读后即可提问。'
            : '底部输入问题，各位专家将先独立思考，再进入圆桌讨论。'}
      </p>
    </div>
  )
}

function ThoughtSeats({
  thoughts,
  progressThoughts,
  agentMap,
}: {
  thoughts: AgentThought[]
  progressThoughts: Record<string, string>
  agentMap: Map<string, Agent>
}) {
  const ids = new Set<string>([
    ...thoughts.map((t) => t.agentId),
    ...Object.keys(progressThoughts),
  ])

  if (ids.size === 0) return null

  return (
    <div>
      <div className="panel-head" style={{ padding: '0 0 0.6rem' }}>
        <h2>独立思考席</h2>
        <span className="hint">并行 · 互不可见</span>
      </div>
      <div className="thought-ring">
        {[...ids].map((id) => {
          const full = thoughts.find((t) => t.agentId === id)
          const state = full?.state || (progressThoughts[id] as AgentThought['state']) || 'PENDING'
          const agent = agentMap.get(id)
          const name = full?.agentName || agent?.name || id
          const color = `var(--agent-${agentColorIndex(id)})`
          const snippet = full?.viewpoint || full?.rawText || full?.errorMessage || ''
          return (
            <div
              key={id}
              className="seat"
              data-state={state}
              style={{ ['--agent-color' as string]: color }}
            >
              <div className="seat-dot" />
              <p className="seat-name">{name}</p>
              <div className="seat-state">{thoughtLabels[state] || state}</div>
              {snippet && <div className="seat-snippet">{snippet}</div>}
              {full?.confidence != null && (
                <div className="muted" style={{ marginTop: 6 }}>
                  置信 {Math.round(full.confidence * 100)}%
                </div>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}

function Timeline({ turns, consensus }: { turns: Turn[]; consensus?: ProfessionalConsensus | null }) {
  return (
    <div className="timeline">
      {turns.map((t, i) => {
        const isHost = t.role === 'host' || t.speakerId === 'host' || /主持|Host/i.test(t.speakerName || '')
        const isUser = t.role === 'USER' || t.speakerId === 'user'
        const color = isHost
          ? 'var(--host)'
          : isUser
            ? 'var(--accent-success)'
          : `var(--agent-${agentColorIndex(t.speakerId || t.speakerName || String(i))})`
        return (
          <article className="turn" key={`${t.at}-${i}`}>
            <div className="turn-rail">
              <span className={`turn-node ${isHost ? 'host' : ''}`} style={{ ['--agent-color' as string]: color, background: color }} />
            </div>
            <div className="turn-body">
              <div className="turn-head">
                <span className="turn-speaker">{t.speakerName || t.speakerId}</span>
                <span className={`chip ${isHost ? 'chip-host' : isUser ? 'chip-ready' : 'chip-idle'}`}>
                  {phaseLabels[t.phase] || t.phase}
                  {t.round > 0 ? ` · R${t.round}` : ''}
                  {formatClock(t.at) ? ` · ${formatClock(t.at)}` : ''}
                </span>
              </div>
              <div className="turn-content">{t.content}</div>
              {t.skillEvaluated && (
                <details className="skill-call-record">
                  <summary>
                    {t.skillInvocations?.length
                      ? `本次调用：${t.skillInvocations.map((item) => item.name).join('、')}`
                      : '本次未调用 Skill'}
                  </summary>
                  {t.skillInvocations && t.skillInvocations.length > 0 && (
                    <ul>
                      {t.skillInvocations.map((item) => (
                        <li key={`${item.skillUuid}-${item.at}`}>
                          <strong>{item.name}</strong> · {item.reason} · {formatClock(item.at)}
                        </li>
                      ))}
                    </ul>
                  )}
                </details>
              )}
            </div>
          </article>
        )
      })}
      {consensus && (
        <article className="turn">
          <div className="turn-rail">
            <span className="turn-node host" style={{ background: 'var(--host)', boxShadow: '0 0 0 1px color-mix(in srgb, var(--host) 40%, transparent)' }} />
          </div>
          <div className="turn-body">
            <div className="turn-head">
              <span className="turn-speaker">Host</span>
              <span className="chip chip-host">共识</span>
            </div>
            <ConsensusView consensus={consensus} />
          </div>
        </article>
      )}
    </div>
  )
}

function ConsensusView({ consensus }: { consensus: ProfessionalConsensus }) {
  const hasLists =
    (consensus.conclusions && consensus.conclusions.length > 0) ||
    (consensus.corrections && consensus.corrections.length > 0) ||
    (consensus.additions && consensus.additions.length > 0)

  return (
    <div className="consensus-inline">
      <h4 style={{ margin: '0 0 0.4rem', fontFamily: 'var(--font-display)', fontSize: '0.95rem' }}>专业共识</h4>
      {consensus.summary && (
        <p style={{ margin: '0 0 0.5rem', color: 'var(--text-secondary)', fontSize: '0.9rem' }}>{consensus.summary}</p>
      )}
      {!hasLists && consensus.rawText && (
        <p className="turn-content" style={{ margin: 0 }}>{consensus.rawText}</p>
      )}
      {consensus.conclusions && consensus.conclusions.length > 0 && (
        <>
          <div className="muted">结论</div>
          <ul className="bullet-list">
            {consensus.conclusions.map((c, i) => (
              <li key={i}>{c}</li>
            ))}
          </ul>
        </>
      )}
      {consensus.corrections && consensus.corrections.length > 0 && (
        <>
          <div className="muted">应修正</div>
          <ul className="bullet-list">
            {consensus.corrections.map((c, i) => (
              <li key={i}>{c}</li>
            ))}
          </ul>
        </>
      )}
      {consensus.additions && consensus.additions.length > 0 && (
        <>
          <div className="muted">应增补</div>
          <ul className="bullet-list">
            {consensus.additions.map((c, i) => (
              <li key={i}>{c}</li>
            ))}
          </ul>
        </>
      )}
    </div>
  )
}

function SummaryDialog({
  result,
  onClose,
}: {
  result: SummaryResult
  onClose: () => void
}) {
  const [copied, setCopied] = useState(false)
  const [saved, setSaved] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)

  const saveAs = () => {
    setSaveError(null)
    void saveMarkdownAs(result.fileName, result.markdown)
      .then((ok) => {
        if (!ok) return // 用户取消了另存为对话框，不算失败
        setSaved(true)
        setTimeout(() => setSaved(false), 1500)
      })
      .catch((e: unknown) => {
        setSaveError(e instanceof Error ? e.message : '保存失败')
      })
  }

  const copy = () => {
    void navigator.clipboard.writeText(result.markdown).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    })
  }

  return (
    <Modal
      className="save-dialog summary-dialog"
      onClose={onClose}
      closeOnEscape={false}
    >
        <ModalTitle asChild><h3>文档总结已生成</h3></ModalTitle>
        <p className="muted">{result.fileName}</p>
        {saveError && <p className="field-error">{saveError}</p>}
        <pre className="summary-preview">{result.markdown}</pre>
        <div className="save-dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={copy}>
            {copied ? '已复制' : '复制全文'}
          </button>
          <button type="button" className="btn btn-accent" onClick={saveAs}>
            {saved ? '已保存' : '保存到…'}
          </button>
          <button type="button" className="btn btn-danger" onClick={onClose}>
            关闭
          </button>
        </div>
    </Modal>
  )
}

function RevisionPanel({
  plan,
  busy,
  status,
  onConfirm,
}: {
  plan: RevisionPlan
  busy: boolean
  status?: QuestionStatus
  onConfirm: (ids: string[], note: string) => Promise<void>
}) {
  const items = plan.items || []
  const itemKey = items.map((i) => i.id).join('|')
  const [selected, setSelected] = useState<Set<string>>(() => new Set(items.map((i) => i.id)))
  const [note, setNote] = useState('')

  useEffect(() => {
    setSelected(new Set(items.map((i) => i.id)))
  }, [itemKey])

  const locked = status === 'REVISING' || status === 'REVISED_COMPLETED'

  return (
    <div className="card section-gap">
      <div className="panel-head" style={{ padding: 0, marginBottom: '0.65rem' }}>
        <h2 style={{ fontFamily: 'var(--font-display)', fontSize: '1.05rem', margin: 0 }}>
          修改建议
        </h2>
        <span className="hint">{items.length} 项</span>
      </div>
      {plan.summary && (
        <p className="muted" style={{ marginTop: 0 }}>{plan.summary}</p>
      )}
      {items.map((item) => (
        <div className="revision-item" key={item.id}>
          <label>
            <input
              type="checkbox"
              disabled={locked || busy}
              checked={selected.has(item.id)}
              onChange={(e) => {
                setSelected((prev) => {
                  const n = new Set(prev)
                  if (e.target.checked) n.add(item.id)
                  else n.delete(item.id)
                  return n
                })
              }}
            />
            <div>
              <div className="revision-title">
                {item.location || item.id}
                {item.type ? ` · ${item.type}` : ''}
              </div>
              {item.reason && <div className="revision-body">{item.reason}</div>}
              {(item.styleAlignedSuggestion || item.professionalSuggestion) && (
                <div className="revision-body" style={{ marginTop: 4 }}>
                  建议：{item.styleAlignedSuggestion || item.professionalSuggestion}
                </div>
              )}
            </div>
          </label>
        </div>
      ))}
      {!locked && (
        <>
          <div className="field" style={{ marginTop: '0.5rem' }}>
            <label htmlFor="rev-note">补充说明</label>
            <input
              id="rev-note"
              className="input"
              value={note}
              onChange={(e) => setNote(e.target.value)}
              placeholder="可选"
            />
          </div>
          <button
            type="button"
            className="btn btn-accent btn-sm"
            disabled={busy || selected.size === 0}
            onClick={() => void onConfirm([...selected], note)}
          >
            确认并改写（{selected.size}）
          </button>
        </>
      )}
    </div>
  )
}

/**
 * 改稿变更条目：尝试用词级 diff 高亮 originalSnippet vs newSnippet。
 * 如果两者都为空，只显示 note。
 */
function DiffEntry({ entry, index }: { entry: ChangeLogEntry; index: number }) {
  const [open, setOpen] = useState(true)
  const parts = useMemo(() => {
    if (entry.originalSnippet && entry.newSnippet) {
      return diffWordsWithSpace(entry.originalSnippet, entry.newSnippet)
    }
    return null
  }, [entry.originalSnippet, entry.newSnippet])

  return (
    <div className="change-log" style={{ marginBottom: '0.6rem' }}>
      <div
        className="change-log-head"
        style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer' }}
        onClick={() => setOpen((v) => !v)}
      >
        <span className="muted" style={{ minWidth: 28 }}>#{index + 1}</span>
        {entry.planItemId && (
          <span className="chip chip-idle" style={{ fontSize: '0.72rem' }}>
            {entry.planItemId}
          </span>
        )}
        <span style={{ flex: 1, fontSize: '0.85rem' }}>
          {entry.note || (parts ? '词级 diff' : '条目')}
        </span>
        <span className="muted" style={{ fontSize: '0.78rem' }}>
          {open ? '收起' : '展开'}
        </span>
      </div>
      {open && (
        <div style={{ marginTop: 6 }}>
          {parts ? (
            <div className="diff-line">
              {parts.map((p, i) => {
                if (p.added) {
                  return (
                    <ins key={i} className="diff-added">
                      {p.value}
                    </ins>
                  )
                }
                if (p.removed) {
                  return (
                    <del key={i} className="diff-removed">
                      {p.value}
                    </del>
                  )
                }
                return (
                  <span key={i} className="diff-kept">
                    {p.value}
                  </span>
                )
              })}
            </div>
          ) : (
            <>
              {entry.originalSnippet && (
                <div>
                  <del className="diff-removed">{entry.originalSnippet}</del>
                </div>
              )}
              {entry.newSnippet && (
                <div>
                  <ins className="diff-added">{entry.newSnippet}</ins>
                </div>
              )}
            </>
          )}
        </div>
      )}
    </div>
  )
}
