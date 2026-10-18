import { useState } from 'react'
import { Modal, ModalTitle } from './Modal'

type Mode = 'roundtable' | 'docEdit'

type Props = {
  busy: boolean
  /** 取消：留在议事厅空态，不开新对话 */
  onCancel: () => void
  onCreate: (opts: { docEditMode: boolean }) => void
}

/**
 * 「开新对话」弹窗 —— 原 Landing 页里**只能建会话时定**的那几个选项搬到这里。
 *
 * <p>网关配置不在这里：它搬进了左栏的「切换供应商」弹窗（会话建好后随时能改）。
 * 文风 / 轮次走服务端默认值（简洁书面语 / 2 轮），需要露出再加。
 *
 * <p>模式的差别是真实的、建完就改不了，所以必须让用户在开厅前选一次：
 * 改稿模式决定能不能上传待改文档。
 */
export function NewConversationDialog({ busy, onCancel, onCreate }: Props) {
  const [mode, setMode] = useState<Mode>('roundtable')

  const submit = () => {
    onCreate({ docEditMode: mode === 'docEdit' })
  }

  return (
    <Modal
      className="save-dialog new-conv-dialog"
      onClose={onCancel}
      closeOnEscape={false}
      closeOnOutside={!busy}
    >
        <ModalTitle asChild><h3>开新对话</h3></ModalTitle>
        <p className="muted provider-sub">选择本次的讨论模式（建好后不可更改）</p>

        <div className="save-options-list">
          <ModeOption
            checked={mode === 'roundtable'}
            onPick={() => setMode('roundtable')}
            title="圆桌会议"
            desc="多位领域智能体独立思考 → 多轮圆桌 → 专业共识 → 终稿"
          />
          <ModeOption
            checked={mode === 'docEdit'}
            onPick={() => setMode('docEdit')}
            title="改稿模式"
            desc="上传你的文档 → 专业共识出修改建议 → 风格智能体拍板 → 阶段二改写"
          />
        </div>

        <div className="save-dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onCancel} disabled={busy}>
            取消
          </button>
          <button type="button" className="btn btn-accent" onClick={submit} disabled={busy}>
            {busy ? '开启中…' : '开启议事厅'}
          </button>
        </div>
    </Modal>
  )
}

function ModeOption({
  checked,
  onPick,
  title,
  desc,
}: {
  checked: boolean
  onPick: () => void
  title: string
  desc: string
}) {
  return (
    <label className="save-option-item">
      <input type="radio" name="newConvMode" checked={checked} onChange={onPick} />
      <div className="save-option-body">
        <div className="save-option-title">{title}</div>
        <div className="save-option-desc">{desc}</div>
      </div>
    </label>
  )
}
