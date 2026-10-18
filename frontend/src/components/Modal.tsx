import { useEffect, useRef } from 'react'
import type { PropsWithChildren } from 'react'
import * as Dialog from '@radix-ui/react-dialog'

type ModalProps = PropsWithChildren<{
  className: string
  onClose: () => void
  closeOnEscape?: boolean
  closeOnOutside?: boolean
}>

/**
 * 项目统一的模态层。Radix 负责语义、焦点圈定、背景隔离和焦点恢复；
 * 现有 className 与 DOM 包裹关系保持不变，因此不改变视觉样式。
 */
export function Modal({
  children,
  className,
  onClose,
  closeOnEscape = true,
  closeOnOutside = true,
}: ModalProps) {
  const returnFocusRef = useRef<HTMLElement | null>(
    typeof document !== 'undefined' && document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null,
  )

  useEffect(() => {
    const target = returnFocusRef.current
    return () => {
      if (target?.isConnected) queueMicrotask(() => target.focus())
    }
  }, [])

  return (
    <Dialog.Root
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
    >
      <Dialog.Portal>
        <Dialog.Overlay
          className="save-dialog-overlay"
          onClick={(event) => {
            if (closeOnOutside && event.target === event.currentTarget) onClose()
          }}
        >
          <Dialog.Content
            className={className}
            aria-describedby={undefined}
            onEscapeKeyDown={(event) => {
              if (!closeOnEscape) event.preventDefault()
            }}
            onPointerDownOutside={(event) => event.preventDefault()}
            onInteractOutside={(event) => event.preventDefault()}
          >
            {children}
          </Dialog.Content>
        </Dialog.Overlay>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

export const ModalTitle = Dialog.Title
export const ModalDescription = Dialog.Description
