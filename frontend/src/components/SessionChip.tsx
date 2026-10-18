import { useEffect, useRef, useState } from 'react'

type Props = {
  sessionId: string
  /** 复制结果回调，用于顶部 toast 提示 */
  onCopied?: (ok: boolean) => void
}

/**
 * 顶栏会话 ID：默认收起成一个「角标」（只有 `#` 图标，不占文字宽度），
 * 点击展开显示完整 ID 并给出「复制」按钮。
 *
 * 收起时机：再点一次 / 点外部 / Esc。收起态靠 title + aria-label 兜住语义，
 * 展开只是原地变宽（header-meta 是 flex-end，往左伸长不挤动右边按钮）。
 */
export function SessionChip({ sessionId, onCopied }: Props) {
  const [open, setOpen] = useState(false)
  const [copied, setCopied] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onPointerDown = (e: MouseEvent) => {
      if (!rootRef.current?.contains(e.target as Node)) setOpen(false)
    }
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('mousedown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [open])

  // 「已复制」1.6s 后自动复原
  useEffect(() => {
    if (!copied) return
    const t = window.setTimeout(() => setCopied(false), 1600)
    return () => window.clearTimeout(t)
  }, [copied])

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(sessionId)
      setCopied(true)
      onCopied?.(true)
    } catch {
      // 非 https / 无权限时 clipboard 会 reject，提示手动复制
      onCopied?.(false)
    }
  }

  return (
    <div className={`session-chip${open ? ' is-open' : ' is-collapsed'}`} ref={rootRef}>
      <button
        type="button"
        className="session-chip-toggle"
        onClick={() => setOpen((v) => !v)}
        title={`会话 ID：${sessionId}`}
        aria-label={open ? `收起会话 ID（${sessionId}）` : `展开会话 ID（${sessionId}）`}
        aria-expanded={open}
      >
        <svg className="session-chip-icon" viewBox="0 0 16 16" aria-hidden>
          <path
            d="M6.5 2.5 5 13.5M11 2.5 9.5 13.5M2.8 5.5h11.4M2.2 10.5h11.4"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.4"
            strokeLinecap="round"
          />
        </svg>
        {open && <code className="session-chip-full">{sessionId}</code>}
      </button>

      {open && (
        <button type="button" className="session-chip-copy" onClick={() => void copy()}>
          {copied ? '已复制' : '复制'}
        </button>
      )}
    </div>
  )
}
