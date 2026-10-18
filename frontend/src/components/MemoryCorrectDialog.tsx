import { useEffect, useRef, useState } from 'react'
import type { MemoryChatMessage, MemoryProposal, MemoryTarget } from '../api/types'
import { useAgentMemory } from '../hooks/useAgentMemory'
import { Modal, ModalTitle } from './Modal'

type Props = {
  target: MemoryTarget
  /** 借这场会话的网关（普通常驻智能体自己没有 Key）；为空则落服务端默认网关 */
  sessionId?: string | null
  /** 某条方案确认落库后通知上层：只读弹窗要重拉，席位快照也变了 */
  onApplied: () => void
  onClose: () => void
}

/**
 * 纠错对话：用户与这位智能体单独聊，把它跑偏的心得掰过来。
 *
 * <p>与主讨论区完全隔离 —— 这里说的话不进任何会话记录，关掉就没了。
 * 唯一的持久产物是用户点过「确认」的那条钉子。
 *
 * <p><b>它看不到当年的讨论记录</b>（会话可能早已归档销毁），只能听用户说。
 * 界面顶部把这条限制明说了，免得用户以为它能自己查证。
 */
export function MemoryCorrectDialog({ target, sessionId, onApplied, onClose }: Props) {
  const { view, send, applyProposal, sending } = useAgentMemory(target, sessionId)

  const [messages, setMessages] = useState<MemoryChatMessage[]>([])
  const [draft, setDraft] = useState('')
  const streamRef = useRef<HTMLDivElement | null>(null)

  // 新消息 / 正在思考时把滚动条压到底
  useEffect(() => {
    const el = streamRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [messages, sending])

  const push = (m: MemoryChatMessage) => setMessages((prev) => [...prev, m])

  const submit = async () => {
    const text = draft.trim()
    if (!text || sending) return
    // 历史不含这一句：后端会把它单独放进【用户这一轮说】
    const history = messages
    setMessages([...messages, { role: 'user', text }])
    setDraft('')
    try {
      const r = await send(text, history)
      push({ role: 'agent', text: r.reply || '（没有回复内容）', proposal: r.proposal })
    } catch (e) {
      push({ role: 'agent', text: `（这一轮没能送到：${errText(e)}）` })
    }
  }

  /** 用户点「确认」—— 这是方案落库的唯一入口 */
  const confirmProposal = async (index: number, proposal: MemoryProposal) => {
    if (sending) return
    try {
      await applyProposal(proposal)
      // 卡片收起、留一个「已保存」的痕迹：用户得知道这一条真的生效了
      setMessages((prev) =>
        prev.map((m, i) => (i === index ? { ...m, applied: true, proposal: null } : m)),
      )
      onApplied()
    } catch (e) {
      push({ role: 'agent', text: `（这次纠正没能保存：${errText(e)}）` })
    }
  }

  /** 这次不改了：只把卡片收起来，心得一个字没动 */
  const dismissProposal = (index: number) => {
    setMessages((prev) => prev.map((m, i) => (i === index ? { ...m, proposal: null } : m)))
  }

  return (
    <Modal
      className="save-dialog memory-chat-dialog"
      onClose={onClose}
      closeOnEscape={!sending}
      closeOnOutside={!sending}
    >
        <div className="add-agent-head">
          <ModalTitle asChild><h3>纠错 · {target.name}</h3></ModalTitle>
          <button
            type="button"
            className="auth-close"
            onClick={onClose}
            disabled={sending}
            aria-label="关闭"
            title="关闭"
          >
            ✕
          </button>
        </div>

        <p className="muted memory-chat-hint">
          在这里说清楚哪里不对、为什么。它看不到当时那场讨论的记录，只能依据你说的判断；
          只有你点「确认」的修改才会真正保存下来，并成为它以后推翻不了的硬前提。
        </p>

        <div className="memory-chat-stream" ref={streamRef}>
          {messages.length === 0 && (
            <p className="muted memory-empty">
              说说它哪儿做得不对吧。比如「遇到定量结论时，应该先把单位与统计口径对齐再下判断」。
            </p>
          )}

          {messages.map((m, i) => (
            <div key={i} className={`memory-msg is-${m.role}`}>
              <div className="memory-msg-bubble">{m.text}</div>

              {m.applied && <div className="memory-msg-applied">✓ 已保存为硬前提</div>}

              {m.proposal && (
                <div className="memory-proposal">
                  <div className="memory-proposal-head">修改方案</div>
                  {m.proposal.before && (
                    <div className="memory-proposal-row">
                      <span className="memory-proposal-label">原文</span>
                      <span className="memory-proposal-before">{m.proposal.before}</span>
                    </div>
                  )}
                  <div className="memory-proposal-row">
                    <span className="memory-proposal-label">
                      {m.proposal.before ? '改为' : '新增'}
                    </span>
                    <span className="memory-proposal-after">{m.proposal.after}</span>
                  </div>
                  {m.proposal.rationale && (
                    <div className="memory-proposal-why muted">理由：{m.proposal.rationale}</div>
                  )}
                  <div className="memory-proposal-actions">
                    <button
                      type="button"
                      className="btn btn-ghost btn-sm"
                      disabled={sending}
                      onClick={() => dismissProposal(i)}
                    >
                      暂不修改
                    </button>
                    <button
                      type="button"
                      className="btn btn-accent btn-sm"
                      disabled={sending}
                      onClick={() => void confirmProposal(i, m.proposal!)}
                    >
                      确认
                    </button>
                  </div>
                </div>
              )}
            </div>
          ))}

          {sending && <div className="memory-msg is-agent memory-thinking">思考中…</div>}
        </div>

        <div className="memory-chat-input">
          <textarea
            className="input memory-chat-textarea"
            rows={2}
            value={draft}
            placeholder="哪里不对？直接说。回车发送，Shift+回车换行"
            disabled={sending}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault()
                void submit()
              }
            }}
          />
          <button
            type="button"
            className="btn btn-accent"
            disabled={sending || !draft.trim()}
            onClick={() => void submit()}
          >
            发送
          </button>
        </div>

        <div className="save-dialog-actions memory-chat-actions">
          <span className="muted">
            {view?.updatedAt ? '每轮都会把当前心得全文交给它' : '它还没有心得，这次的修改会成为第一条'}
          </span>
          <button type="button" className="btn btn-ghost" onClick={onClose} disabled={sending}>
            关闭
          </button>
        </div>
    </Modal>
  )
}

function errText(e: unknown): string {
  return e instanceof Error && e.message ? e.message : '未知错误'
}
