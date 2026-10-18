import { useEffect } from 'react'

export type ToastItem = {
  id: string
  message: string
  kind?: 'ok' | 'error' | 'info'
}

type Props = {
  items: ToastItem[]
  onDismiss: (id: string) => void
}

export function ToastStack({ items, onDismiss }: Props) {
  return (
    <div className="toast-stack" aria-live="polite">
      {items.map((t) => (
        <Toast key={t.id} item={t} onDismiss={onDismiss} />
      ))}
    </div>
  )
}

function Toast({
  item,
  onDismiss,
}: {
  item: ToastItem
  onDismiss: (id: string) => void
}) {
  useEffect(() => {
    const t = setTimeout(() => onDismiss(item.id), 4200)
    return () => clearTimeout(t)
  }, [item.id, onDismiss])

  return (
    <div className={`toast ${item.kind === 'error' ? 'error' : item.kind === 'ok' ? 'ok' : ''}`}>
      {item.message}
    </div>
  )
}
