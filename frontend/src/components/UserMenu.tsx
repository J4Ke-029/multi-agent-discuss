import { useEffect, useRef, useState } from 'react'
import type { User } from '../api/types'

type Props = {
  user: User
  onLogout: () => void
  /** 忙时（提交中 / 重新登录等）禁用触发按钮，避免误点 */
  disabled?: boolean
}

/**
 * 顶栏账号菜单：点击用户名 chip 后，在下方浮出菜单，退出登录收在菜单里
 * （不再单独占一个右上角按钮）。
 *
 * 关闭时机：点菜单外部 / Esc / 触发按钮再点一次 / 外部把 disabled 置真。
 * 面板整体右对齐（两处调用都在页面右上角，靠右不会溢出视口）。
 */
export function UserMenu({ user, onLogout, disabled = false }: Props) {
  const [open, setOpen] = useState(false)
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

  // 外部进入 busy 时收起，避免面板悬在已失效的界面上
  useEffect(() => {
    if (disabled) setOpen(false)
  }, [disabled])

  return (
    <div className="user-menu" ref={rootRef}>
      <button
        type="button"
        className={`auth-user-chip user-menu-trigger${open ? ' is-open' : ''}`}
        onClick={() => setOpen((v) => !v)}
        disabled={disabled}
        aria-haspopup="menu"
        aria-expanded={open}
        title={`账号：${user.loginId}`}
      >
        <span className="auth-user-dot" aria-hidden />
        <span className="auth-user-name">{user.displayName}</span>
        <svg className="user-menu-caret" viewBox="0 0 10 6" aria-hidden>
          <path
            d="M1 1l4 4 4-4"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.5"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      </button>

      {open && (
        <div className="user-menu-panel" role="menu">
          <div className="user-menu-head">
            <div className="user-menu-name">{user.displayName}</div>
            <div className="user-menu-id">{user.loginId}</div>
          </div>
          <button
            type="button"
            className="user-menu-item is-danger"
            role="menuitem"
            onClick={() => {
              setOpen(false)
              onLogout()
            }}
          >
            <svg viewBox="0 0 16 16" aria-hidden>
              <path
                d="M6 2H3.5A1.5 1.5 0 0 0 2 3.5v9A1.5 1.5 0 0 0 3.5 14H6M10.5 11l3-3-3-3M13 8H6"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.4"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
            退出登录
          </button>
        </div>
      )}
    </div>
  )
}
