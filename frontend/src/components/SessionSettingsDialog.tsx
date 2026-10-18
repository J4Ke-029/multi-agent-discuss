import { useState } from 'react'
import type { Session } from '../api/types'
import { Modal, ModalTitle } from './Modal'

type Props = {
  session: Session
  busy: boolean
  onClose: () => void
  /** 返回是否成功；失败时保持弹窗打开，方便改完重试 */
  onSubmit: (data: { userStyle: string; maxRounds: number }) => Promise<boolean>
}

/** 文风 / 最高轮次的合法区间，与后端 SessionService.updateSettings 的校验保持一致 */
const MIN_ROUNDS = 1
const MAX_ROUNDS = 20

/**
 * 「会话配置」弹窗：改文风与最高讨论轮次。
 *
 * <p>只放这两项 —— 它们改完只影响**后续**（文风在生成终稿时读，轮次是讨论轮数的上界），
 * 不会让已经产生的发言失效。改稿模式 / 继续讨论是建会话时定死的，这里只做展示。
 */
export function SessionSettingsDialog({ session, busy, onClose, onSubmit }: Props) {
  const [userStyle, setUserStyle] = useState(session.userStyle || '')
  const [maxRounds, setMaxRounds] = useState(session.maxRounds)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const submit = async () => {
    if (!Number.isFinite(maxRounds) || maxRounds < MIN_ROUNDS || maxRounds > MAX_ROUNDS) {
      setError(`最高讨论轮次需在 ${MIN_ROUNDS} ~ ${MAX_ROUNDS} 之间`)
      return
    }
    setError(null)
    setSubmitting(true)
    try {
      if (await onSubmit({ userStyle, maxRounds })) onClose()
    } finally {
      setSubmitting(false)
    }
  }

  const working = busy || submitting

  return (
    <Modal
      className="save-dialog provider-dialog"
      onClose={onClose}
      closeOnEscape={!working}
      closeOnOutside={!working}
    >
        <ModalTitle asChild><h3>会话配置</h3></ModalTitle>
        <p className="muted provider-sub">改完只影响后续生成的终稿与讨论轮数上界</p>

        {error && (
          <div className="auth-top-error" role="alert" style={{ marginBottom: '0.6rem' }}>
            {error}
          </div>
        )}

        <div className="field">
          <label htmlFor="dlgUserStyle">终稿文风</label>
          <input
            id="dlgUserStyle"
            className="input"
            value={userStyle}
            onChange={(e) => setUserStyle(e.target.value)}
            placeholder="例如：简洁书面语 / 学术严谨 / 科普口语"
          />
          <span className="muted">留空则用默认文风</span>
        </div>

        <div className="field">
          <label htmlFor="dlgMaxRounds">最高讨论轮次</label>
          <input
            id="dlgMaxRounds"
            className="input"
            type="number"
            min={MIN_ROUNDS}
            max={MAX_ROUNDS}
            value={maxRounds}
            onChange={(e) => setMaxRounds(Number(e.target.value))}
          />
          <span className="muted">
            智能体自行决定实际轮数，不超过此上限（{MIN_ROUNDS} ~ {MAX_ROUNDS}）
          </span>
        </div>

        <div className="save-dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose} disabled={working}>
            取消
          </button>
          <button
            type="button"
            className="btn btn-accent"
            onClick={() => void submit()}
            disabled={working}
          >
            {working ? '保存中…' : '保存'}
          </button>
        </div>
    </Modal>
  )
}
