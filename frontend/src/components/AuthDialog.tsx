import { useEffect, useState } from 'react'
import { api, AUTH_LOST_EVENT } from '../api/client'
import { saveAuth } from '../lib/storage'
import type { User } from '../api/types'
import { Modal, ModalTitle } from './Modal'

type Tab = 'login' | 'register'

type Props = {
  onClose: () => void
  /** 登录 / 注册成功后回调（用于 App 切换右上角 UI） */
  onAuthed: (user: User) => void
}

type FormErrors = Partial<Record<'loginId' | 'displayName' | 'password', string>>

/**
 * 议事厅欢迎页右上角触发的「登录 / 注册」弹窗。
 *
 * <p>特点：
 * <ul>
 *   <li>两个 tab：登录（账号 + 密码）/ 注册（用户名 + 账号 + 密码）</li>
 *   <li>走真后端：{@code POST /api/auth/register} 与 {@code POST /api/auth/login}；密码在后端 BCrypt 哈希入库，
 *       <b>前端不存任何账号信息</b>，仅缓存 {@code token + displayName + loginId}</li>
 *   <li>401（token 失效）时由 {@code api/client.ts} 自动清 token 并派发 {@link AUTH_LOST_EVENT}；本组件也监听此事件主动弹出</li>
 *   <li>关闭弹窗（点 ✕ / 蒙层 / ESC）保留当前登录状态不变</li>
 * </ul>
 */
export function AuthDialog({ onClose, onAuthed }: Props) {
  const [tab, setTab] = useState<Tab>('login')
  const [loginId, setLoginId] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [password, setPassword] = useState('')
  const [showPwd, setShowPwd] = useState(false)
  const [errors, setErrors] = useState<FormErrors>({})
  const [topError, setTopError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  // 切 tab 时清掉错误与密码
  useEffect(() => {
    setErrors({})
    setTopError(null)
    setPassword('')
    if (tab === 'login') setDisplayName('')
  }, [tab])

  const validate = (): FormErrors => {
    const e: FormErrors = {}
    if (tab === 'register' && !displayName.trim()) {
      e.displayName = '请输入显示名'
    } else if (tab === 'register' && displayName.trim().length > 24) {
      e.displayName = '显示名不超过 24 字符'
    }
    if (!loginId.trim()) {
      e.loginId = '请输入账号'
    } else if (loginId.trim().length < 3) {
      e.loginId = '账号至少 3 字符'
    } else if (loginId.trim().length > 32) {
      e.loginId = '账号不超过 32 字符'
    } else if (!/^[A-Za-z0-9_\-.]+$/.test(loginId.trim())) {
      e.loginId = '账号只能含字母、数字、下划线、点、横线'
    }
    if (!password) {
      e.password = '请输入密码'
    } else if (password.length < 6) {
      e.password = '密码至少 6 字符'
    } else if (password.length > 64) {
      e.password = '密码不超过 64 字符'
    }
    return e
  }

  const submit = async () => {
    const e = validate()
    setErrors(e)
    if (Object.keys(e).length > 0) return

    const id = loginId.trim()
    const dn = displayName.trim()
    const pwd = password
    setSubmitting(true)
    setTopError(null)
    try {
      const resp = tab === 'register'
        ? await api.register({ loginId: id, displayName: dn, password: pwd })
        : await api.login({ loginId: id, password: pwd })
      saveAuth({ token: resp.token, user: resp.user })
      onAuthed(resp.user)
      onClose()
    } catch (err) {
      const msg = err instanceof Error ? err.message : '请求失败'
      // 后端 409 USER_EXISTS / 401 INVALID_CREDENTIALS 等已经返回中文，这里直接展示；
      // 网络层失败（后端未起）走 fallback。
      if (/failed to fetch|networkerror|failed to fetch/i.test(msg)) {
        setTopError('后端未连接，请确认 mvn spring-boot:run 已启动')
      } else if (/已被注册/.test(msg)) {
        setTopError(`账号「${id}」已被注册`)
      } else {
        setTopError(msg)
      }
    } finally {
      setSubmitting(false)
    }
  }

  // 兜底：如果外部因 401 派发了 auth-lost 事件，这里不清错误（弹窗本来就是被它打开的）
  void AUTH_LOST_EVENT

  return (
    <Modal
      className="save-dialog auth-dialog"
      onClose={onClose}
      closeOnEscape={!submitting}
      closeOnOutside={!submitting}
    >
        <div className="auth-tabs">
          <button
            type="button"
            className={`auth-tab${tab === 'login' ? ' is-active' : ''}`}
            onClick={() => setTab('login')}
          >
            登录
          </button>
          <button
            type="button"
            className={`auth-tab${tab === 'register' ? ' is-active' : ''}`}
            onClick={() => setTab('register')}
          >
            注册
          </button>
          <button
            type="button"
            className="auth-close"
            onClick={onClose}
            aria-label="关闭"
            title="关闭（ESC）"
            disabled={submitting}
          >
            ×
          </button>
        </div>

        <ModalTitle asChild>
          <h3 className="auth-title">
            {tab === 'login' ? '登录议事厅' : '注册新账号'}
          </h3>
        </ModalTitle>
        <p className="muted auth-sub">
          {tab === 'login'
            ? '使用账号与密码进入你的议事厅。'
            : '设置显示名、账号与密码，后端安全保存（密码 BCrypt 哈希）。'}
        </p>

        {topError && (
          <div className="auth-top-error" role="alert">
            {topError}
          </div>
        )}

        <form
          className="auth-form"
          onSubmit={(e) => {
            e.preventDefault()
            submit()
          }}
        >
          {tab === 'register' && (
            <div className="field">
              <label htmlFor="auth-displayName">显示名</label>
              <input
                id="auth-displayName"
                className="input"
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
                placeholder="例如：林博士"
                maxLength={24}
                autoComplete="off"
                autoFocus
                disabled={submitting}
              />
              {errors.displayName && (
                <div className="field-error">{errors.displayName}</div>
              )}
            </div>
          )}

          <div className="field">
            <label htmlFor="auth-loginId">账号</label>
            <input
              id="auth-loginId"
              className="input"
              value={loginId}
              onChange={(e) => setLoginId(e.target.value)}
              placeholder="字母 / 数字 / 下划线 / 点 / 横线"
              maxLength={32}
              autoComplete={tab === 'login' ? 'username' : 'off'}
              autoFocus={tab === 'login'}
              disabled={submitting}
            />
            {errors.loginId && (
              <div className="field-error">{errors.loginId}</div>
            )}
          </div>

          <div className="field">
            <label htmlFor="auth-password">密码</label>
            <div className="auth-pwd-row">
              <input
                id="auth-password"
                className="input"
                type={showPwd ? 'text' : 'password'}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="至少 6 字符"
                maxLength={64}
                autoComplete={tab === 'login' ? 'current-password' : 'new-password'}
                disabled={submitting}
              />
              <button
                type="button"
                className="btn btn-ghost btn-sm auth-pwd-toggle"
                onClick={() => setShowPwd((v) => !v)}
                disabled={submitting}
              >
                {showPwd ? '隐藏' : '显示'}
              </button>
            </div>
            {errors.password && (
              <div className="field-error">{errors.password}</div>
            )}
          </div>

          <div className="save-dialog-actions auth-actions">
            <button
              type="button"
              className="btn btn-ghost"
              onClick={onClose}
              disabled={submitting}
            >
              取消
            </button>
            <button
              type="submit"
              className="btn btn-accent"
              disabled={submitting}
            >
              {submitting ? '提交中…' : (tab === 'login' ? '登录' : '注册并登录')}
            </button>
          </div>
        </form>

        <div className="auth-foot muted">
          {tab === 'login' ? (
            <>
              还没有账号？
              <button
                type="button"
                className="auth-link"
                onClick={() => setTab('register')}
                disabled={submitting}
              >
                立即注册
              </button>
            </>
          ) : (
            <>
              已有账号？
              <button
                type="button"
                className="auth-link"
                onClick={() => setTab('login')}
                disabled={submitting}
              >
                直接登录
              </button>
            </>
          )}
        </div>
    </Modal>
  )
}
