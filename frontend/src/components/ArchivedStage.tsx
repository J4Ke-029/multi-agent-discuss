import { useState } from 'react'
import type { ArchivedDetail, ArchivedQuestion, SummaryResult } from '../api/types'
import { agentColorIndex, formatTokens, phaseLabels } from '../lib/labels'
import { saveMarkdownAs } from '../lib/save'
import { formatClock, formatDateTime } from '../lib/time'
import { StatusChip } from './StatusChip'
import { Modal, ModalTitle } from './Modal'

type Props = {
  detail: ArchivedDetail
  busy: boolean
  onClose: () => void
  /** 用归档快照重新生成议题总结；失败返回 null（错误已由上层 toast） */
  onGenerateSummary: (
    qid: string,
    scope: 'CURRENT_QUESTION' | 'ALL_QUESTIONS',
  ) => Promise<SummaryResult | null>
}

/**
 * 归档会话的只读查看器。
 *
 * <p>和 {@code DiscussionStage} 的关键区别：这里没有任何写操作——没有提问框、
 * 没有「开始讨论」、没有改稿确认。归档是一份历史快照，能不能改是设计上的分界线。
 * 唯一允许的动作是「生成总结」（读快照产出 Markdown，不改快照本身）。
 */
export function ArchivedStage({ detail, busy, onClose, onGenerateSummary }: Props) {
  const questions = detail.payload.questions
  const [idx, setIdx] = useState(0)
  const [summary, setSummary] = useState<SummaryResult | null>(null)
  const [genBusy, setGenBusy] = useState(false)
  const [saved, setSaved] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const q: ArchivedQuestion | undefined = questions[idx]

  const handleGenerate = async (qid: string, scope: 'CURRENT_QUESTION' | 'ALL_QUESTIONS') => {
    setGenBusy(true)
    try {
      const r = await onGenerateSummary(qid, scope)
      if (r) setSummary(r)
    } finally {
      setGenBusy(false)
    }
  }

  const handleSaveSummary = () => {
    if (!summary) return
    setSaveError(null)
    void saveMarkdownAs(summary.fileName, summary.markdown)
      .then((ok) => {
        if (!ok) return // 用户取消了另存为对话框，不算失败
        setSaved(true)
        setTimeout(() => setSaved(false), 1500)
      })
      .catch((e: unknown) => {
        setSaveError(e instanceof Error ? e.message : '保存失败')
      })
  }

  return (
    <section className="panel panel-center archived-stage">
      <div className="archived-head">
        <div>
          <h2 className="archived-title">{detail.title}</h2>
          <div className="archived-meta muted">
            {[
              // 只显示讨论真正发生的时间（快照里的 session.createdAt）。
              // ⚠️ 归档时刻 archivedAt 刻意不显示：它由 MySQL 的 CURRENT_TIMESTAMP 生成
              // （实体标了 insertable=false），而 Hibernate 按 UTC 读回，稳定偏 8 小时。
              // 详见 CHANGELOG「已知问题 / 待办」里的时区条目。
              formatDateTime(detail.payload.session.createdAt)
                ? `讨论于 ${formatDateTime(detail.payload.session.createdAt)}`
                : null,
              `${detail.questionCount} 议题`,
              `${detail.agentCount} 智能体`,
              `${formatTokens(detail.tokenTotal)} tokens`,
              detail.payload.session.llm?.model || null,
            ]
              .filter(Boolean)
              .join(' · ')}
          </div>
        </div>
        <button type="button" className="btn btn-ghost btn-sm" onClick={onClose}>
          返回当前对话
        </button>
      </div>

      <div className="archived-body">
        <div className="card">
          <div className="muted">参与智能体</div>
          <div className="archived-agents">
            {detail.payload.session.agents.map((a) => (
              <div
                key={a.agentId}
                className="archived-agent"
                style={{
                  ['--agent-color' as string]: `var(--agent-${agentColorIndex(a.agentId)})`,
                }}
              >
                <span className="archived-agent-dot" aria-hidden />
                <span className="archived-agent-name">{a.name}</span>
                {a.personality && <span className="agent-personality">性格：{a.personality}</span>}
                {a.documentFileName && (
                  <span className="agent-meta">{a.documentFileName}</span>
                )}
              </div>
            ))}
          </div>
        </div>

        {questions.length === 0 ? (
          <p className="empty-inline">这份归档里没有议题。</p>
        ) : (
          <>
            <div className="archived-q-tabs">
              {questions.map((item, i) => (
                <button
                  key={item.id}
                  type="button"
                  className={`archived-q-tab${i === idx ? ' active' : ''}`}
                  onClick={() => setIdx(i)}
                  title={item.content}
                >
                  <span className="archived-q-tab-no">{i + 1}</span>
                  <span className="archived-q-tab-text">{item.content}</span>
                </button>
              ))}
            </div>

            {q && (
              <>
                <div className="card">
                  <div className="archived-q-head">
                    <h3>议题</h3>
                    <StatusChip kind="question" value={q.status} />
                  </div>
                  <p className="archived-q-content">{q.content}</p>
                  {q.stageHint && <div className="muted">{q.stageHint}</div>}
                  {q.tokenUsage && (
                    <div className="muted archived-q-tokens">
                      {formatTokens(q.tokenUsage.totalTokens)} tokens ·{' '}
                      {q.tokenUsage.inputTokens ?? 0} in / {q.tokenUsage.outputTokens ?? 0} out
                    </div>
                  )}
                  <div className="stack-actions">
                    <button
                      type="button"
                      className="btn btn-ghost btn-sm"
                      disabled={busy || genBusy}
                      onClick={() => void handleGenerate(q.id, 'CURRENT_QUESTION')}
                    >
                      {genBusy ? '生成中…' : '生成本议题总结'}
                    </button>
                    {questions.length > 1 && (
                      <button
                        type="button"
                        className="btn btn-ghost btn-sm"
                        disabled={busy || genBusy}
                        onClick={() => void handleGenerate(q.id, 'ALL_QUESTIONS')}
                      >
                        生成整个会话总结
                      </button>
                    )}
                  </div>
                </div>

                <ThoughtsSection question={q} />
                <TranscriptSection question={q} />
                <ConsensusSection question={q} />
                <FinalSection question={q} />
              </>
            )}
          </>
        )}
      </div>

      {summary && (
        <Modal
          className="save-dialog summary-dialog"
          onClose={() => setSummary(null)}
          closeOnEscape={false}
        >
            <ModalTitle asChild><h3>讨论总结</h3></ModalTitle>
            <p className="muted">{summary.fileName}</p>
            {saveError && <p className="field-error">{saveError}</p>}
            <pre className="archived-pre archived-summary-pre">{summary.markdown}</pre>
            <div className="save-dialog-actions">
              <button type="button" className="btn btn-ghost" onClick={handleSaveSummary}>
                {saved ? '已保存' : '保存到…'}
              </button>
              <button type="button" className="btn btn-accent" onClick={() => setSummary(null)}>
                关闭
              </button>
            </div>
        </Modal>
      )}
    </section>
  )
}

function ThoughtsSection({ question }: { question: ArchivedQuestion }) {
  const thoughts = Object.values(question.thoughts || {})
  if (thoughts.length === 0) return null
  if (thoughts.every((t) => !t.viewpoint)) return null
  return (
    <div className="card">
      <h3>独立思考</h3>
      {thoughts.map((t) => (
        <div
          key={t.agentId}
          className="archived-thought"
          style={{ ['--agent-color' as string]: `var(--agent-${agentColorIndex(t.agentId)})` }}
        >
          <div className="archived-thought-head">
            <span className="archived-agent-dot" aria-hidden />
            <strong>{t.agentName}</strong>
            <span className="muted">{thoughtStateLabel(t.state)}</span>
          </div>
          {t.viewpoint && <p className="archived-text">{t.viewpoint}</p>}
          {t.errorMessage && <p className="archived-error">{t.errorMessage}</p>}
        </div>
      ))}
    </div>
  )
}

function TranscriptSection({ question }: { question: ArchivedQuestion }) {
  const turns = question.transcript || []
  if (turns.length === 0) return null
  return (
    <div className="card">
      <h3>讨论笔录</h3>
      {turns.map((t, i) => (
        <div key={`${t.at}-${i}`} className="archived-turn">
          <div className="archived-turn-head">
            <span className="archived-turn-speaker">{t.speakerName}</span>
            <span className="archived-turn-phase">
              {phaseLabels[t.phase] ?? t.phase}
              {t.round > 0 ? ` · 第 ${t.round} 轮` : ''}
              {formatClock(t.at) ? ` · ${formatClock(t.at)}` : ''}
            </span>
          </div>
          <p className="archived-text">{t.content}</p>
        </div>
      ))}
    </div>
  )
}

function ConsensusSection({ question }: { question: ArchivedQuestion }) {
  const c = question.professionalConsensus
  if (!c) return null
  return (
    <div className="card">
      <h3>专业共识</h3>
      {c.summary && <p className="archived-text">{c.summary}</p>}
      <Bullets title="结论" items={c.conclusions} />
      <Bullets title="修正" items={c.corrections} />
      <Bullets title="补充" items={c.additions} />
    </div>
  )
}

function Bullets({ title, items }: { title: string; items?: string[] }) {
  if (!items || items.length === 0) return null
  return (
    <div className="archived-subsection">
      <div className="muted">{title}</div>
      <ul className="bullet-list">
        {items.map((x, i) => (
          <li key={i}>{x}</li>
        ))}
      </ul>
    </div>
  )
}

function FinalSection({ question }: { question: ArchivedQuestion }) {
  if (!question.finalAnswer && !question.explanation && !question.revisedDocument?.revisedFullText) {
    return null
  }
  return (
    <div className="card">
      <h3>终稿</h3>
      {question.finalAnswer && <p className="archived-text">{question.finalAnswer}</p>}
      {question.explanation && (
        <div className="archived-subsection">
          <div className="muted">解释</div>
          <p className="archived-text">{question.explanation}</p>
        </div>
      )}
      {question.revisedDocument?.revisedFullText && (
        <div className="archived-subsection">
          <div className="muted">改写后的文档</div>
          <pre className="archived-pre">{question.revisedDocument.revisedFullText}</pre>
        </div>
      )}
    </div>
  )
}

function thoughtStateLabel(s: string): string {
  const map: Record<string, string> = {
    PENDING: '排队',
    RUNNING: '思考中',
    DONE: '完成',
    FAILED: '失败',
    SKIPPED: '跳过',
  }
  return map[s] ?? s
}
