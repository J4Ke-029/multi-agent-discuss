import type { ReactNode } from 'react'
import { Modal, ModalTitle } from './Modal'

type Props = {
  title: string
  message: ReactNode
  confirmText?: string
  /** 破坏性操作：确认按钮走红色 */
  danger?: boolean
  /** 确认中：两个按钮禁用，确认按钮文案让位给「处理中…」 */
  busy?: boolean
  onConfirm: () => void
  onCancel: () => void
}

/**
 * 通用确认弹窗，替换原生 {@code window.confirm()}——原生弹窗样式跟议事厅的
 * 暗色主题完全脱节，且无法显示多段说明 / 危险态配色。
 *
 * <p>复用现有的 {@code .save-dialog-overlay} / {@code .save-dialog} 样式，
 * 与「保存智能体」「添加智能体」保持一致。Esc 取消；点遮罩取消（busy 时不可）。
 * 初始焦点给「取消」——破坏性操作不该一个回车就执行。
 */
export function ConfirmDialog({
  title,
  message,
  confirmText = '确认',
  danger = false,
  busy = false,
  onConfirm,
  onCancel,
}: Props) {
  return (
    <Modal
      className="save-dialog confirm-dialog"
      onClose={onCancel}
      closeOnEscape={!busy}
      closeOnOutside={!busy}
    >
        <ModalTitle asChild><h3>{title}</h3></ModalTitle>
        <div className="confirm-body">{message}</div>
        <div className="save-dialog-actions">
          <button
            type="button"
            className="btn btn-ghost"
            onClick={onCancel}
            disabled={busy}
            autoFocus
          >
            取消
          </button>
          <button
            type="button"
            className={`btn ${danger ? 'btn-danger' : 'btn-accent'}`}
            onClick={onConfirm}
            disabled={busy}
          >
            {busy ? '处理中…' : confirmText}
          </button>
        </div>
    </Modal>
  )
}
