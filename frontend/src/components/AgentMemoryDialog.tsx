import { useEffect } from 'react'
import type { AgentMemoryPin, MemoryTarget } from '../api/types'
import { useAgentMemory } from '../hooks/useAgentMemory'
import { formatDateTimeMs } from '../lib/time'
import { Modal, ModalTitle } from './Modal'

type Props = {
  target: MemoryTarget
  /** 纠错对话借哪场会话的网关（普通常驻智能体自己没有 Key） */
  sessionId?: string | null
  /** 外部要求重新拉取（纠错确认后由上层 +1） */
  refreshToken?: number
  /** 打开纠错对话 */
  onCorrect: () => void
  /** 点某条已锁定项的「解除」——确认弹窗由顶层统一渲染 */
  onRequestUnpin: (pin: AgentMemoryPin) => void
  onClose: () => void
}

/**
 * 经验心得只读弹窗。两个入口共用同一个组件：
 * ① 常驻列表每条上的「经验」；② 左栏智能体卡片上的「带经验上场 · 更新于 X」。
 *
 * <p>只读是有意的：正文由智能体每场自己重写，用户改它没有意义（下一场就被覆盖）。
 * 用户想施加影响，走「纠错」——那里确认过的条目会变成钉子，是模型推翻不了的硬前提。
 */
export function AgentMemoryDialog({
  target,
  sessionId,
  refreshToken = 0,
  onCorrect,
  onRequestUnpin,
  onClose,
}: Props) {
  const { view, loading, error, reload } = useAgentMemory(target, sessionId)

  // 纠错确认后重拉一次：钉子与正文都变了，别让用户对着旧内容
  useEffect(() => {
    if (refreshToken > 0) void reload()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [refreshToken])

  const pins = view?.pins ?? []
  const content = view?.content?.trim() || ''

  return (
    <Modal className="save-dialog memory-dialog" onClose={onClose}>
        <div className="add-agent-head">
          <ModalTitle asChild><h3>{target.name} 的经验心得</h3></ModalTitle>
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

        <div className="memory-meta muted">
          {loading
            ? '读取中…'
            : view?.updatedAt
              ? `最后一次总结：${formatDateTimeMs(view.updatedAt)}`
              : '还没有总结过'}
        </div>

        {error && <div className="memory-error">{error}</div>}

        <div className="memory-body">
          {!loading && !content && !error && (
            <p className="muted memory-empty">
              这位智能体还没有攒下经验。
              <br />
              结束一场有它参与的讨论后，它会自己回顾这次表现，把心得写在这里，
              下次上场时带着。
            </p>
          )}
          {content && <MemoryBody content={content} />}
        </div>

        {pins.length > 0 && (
          <div className="memory-pins">
            <div className="memory-pins-head">
              已锁定 <span className="muted">（你确认过的纠正，智能体推翻不了）</span>
            </div>
            {pins.map((p) => (
              <div key={p.id} className="memory-pin">
                <span className="memory-pin-text">{p.content}</span>
                <button
                  type="button"
                  className="btn btn-ghost btn-sm"
                  onClick={() => onRequestUnpin(p)}
                >
                  解除
                </button>
              </div>
            ))}
          </div>
        )}

        <div className="save-dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onCorrect}>
            纠错
          </button>
          <button type="button" className="btn btn-accent" onClick={onClose}>
            关闭
          </button>
        </div>
    </Modal>
  )
}

/**
 * 正文渲染。心得由 {@code AgentMemoryService.parseMemory} 写成
 * `【做法】\n…\n\n【结论】\n…` 的固定骨架，这里把两个标签提升成小标题。
 *
 * <p>没命中标签也照样渲染 —— 用户确认过的纠错会被追加到末尾（格式自由），
 * 用 `white-space: pre-wrap` 原样显示，比按 Markdown 解析更不容易出错。
 *
 * <p>空行丢掉：`pre-wrap` 下空 `<p>` 会撑出一整行高度，而段间距交给 CSS 更可控。
 */
function MemoryBody({ content }: { content: string }) {
  const lines = content.split('\n').filter((l) => l.trim() !== '')
  return (
    <>
      {lines.map((line, i) => {
        const t = line.trim()
        if (t === '【做法】' || t === '【结论】') {
          return (
            <h4 key={i} className="memory-section">
              {t}
            </h4>
          )
        }
        return (
          <p key={i} className="memory-line">
            {line}
          </p>
        )
      })}
    </>
  )
}
