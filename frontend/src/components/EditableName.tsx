import { useEffect, useRef, useState } from 'react'

/**
 * 「点一下改名」组件：默认显示为标题文字，点击 → 进入编辑态；
 * blur 或 Enter 提交；Escape 取消（恢复原值）；空值 / 与原值相同 → 不提交。
 *
 * <p>之所以用本地草稿而不是受控 value：
 * <ol>
 *   <li>每次 keystroke 不打后端 PUT（之前的 bug：边输入边触发重命名，浪费请求且会闪烁）</li>
 *   <li>外部 value 变化（PUT 返回 / 列表刷新）能在非编辑态同步过来</li>
 * </ol>
 */
export function EditableName({
  value,
  onSubmit,
}: {
  value: string
  onSubmit: (newName: string) => void
}) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(value)
  const inputRef = useRef<HTMLInputElement>(null)

  // 非编辑态下，外部 value 变了（例如 PUT 成功 / 列表刷新）→ 同步草稿
  useEffect(() => {
    if (!editing) setDraft(value)
  }, [value, editing])

  // 进入编辑态时 focus 并全选，方便直接覆盖
  useEffect(() => {
    if (editing && inputRef.current) {
      inputRef.current.focus()
      inputRef.current.select()
    }
  }, [editing])

  const commit = () => {
    const next = draft.trim()
    if (next.length > 0 && next !== value) {
      onSubmit(next)
    } else {
      // 空值 / 未变化 → 还原，不打后端
      setDraft(value)
    }
    setEditing(false)
  }

  const cancel = () => {
    setDraft(value)
    setEditing(false)
  }

  if (editing) {
    return (
      <input
        ref={inputRef}
        type="text"
        className="agent-name-input editable-name-input"
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            e.preventDefault()
            commit()
          } else if (e.key === 'Escape') {
            e.preventDefault()
            cancel()
          }
        }}
      />
    )
  }

  return (
    <h3
      className="agent-name editable-name"
      title="点击改名"
      onClick={() => setEditing(true)}
    >
      {value}
    </h3>
  )
}
