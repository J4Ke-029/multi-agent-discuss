import { StrictMode, useState } from 'react'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { Session } from '../src/api/types'
import { ConfirmDialog } from '../src/components/ConfirmDialog'
import { Modal, ModalTitle } from '../src/components/Modal'
import { SessionSettingsDialog } from '../src/components/SessionSettingsDialog'

function BasicDialog() {
  const [open, setOpen] = useState(false)
  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>打开设置</button>
      {open && (
        <Modal className="save-dialog" onClose={() => setOpen(false)}>
          <ModalTitle asChild><h3>会话设置</h3></ModalTitle>
          <button type="button">第一项</button>
          <button type="button" onClick={() => setOpen(false)}>完成</button>
        </Modal>
      )}
    </>
  )
}

function NestedDialogs() {
  const [parentOpen, setParentOpen] = useState(false)
  const [childOpen, setChildOpen] = useState(false)
  return (
    <>
      <button type="button" onClick={() => setParentOpen(true)}>添加智能体</button>
      {parentOpen && (
        <Modal className="save-dialog" onClose={() => setParentOpen(false)}>
          <ModalTitle asChild><h3>添加智能体</h3></ModalTitle>
          <button type="button" onClick={() => setChildOpen(true)}>查看论文</button>
          {childOpen && (
            <ConfirmDialog
              title="删除论文"
              message="确认删除这篇论文吗？"
              onConfirm={() => setChildOpen(false)}
              onCancel={() => setChildOpen(false)}
            />
          )}
        </Modal>
      )}
    </>
  )
}

describe('Modal accessibility contract', () => {
  it('moves focus inside, traps Tab, and restores focus to the trigger', async () => {
    const user = userEvent.setup()
    render(<StrictMode><BasicDialog /></StrictMode>)

    const trigger = screen.getByRole('button', { name: '打开设置' })
    await user.click(trigger)

    const dialog = screen.getByRole('dialog', { name: '会话设置' })
    expect(dialog).toContainElement(document.activeElement as HTMLElement)

    const buttons = within(dialog).getAllByRole('button')
    buttons.at(-1)?.focus()
    await user.tab()
    expect(dialog).toContainElement(document.activeElement as HTMLElement)

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog', { name: '会话设置' })).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
  })

  it('closes only the topmost dialog when Escape is pressed', async () => {
    const user = userEvent.setup()
    render(<NestedDialogs />)

    await user.click(screen.getByRole('button', { name: '添加智能体' }))
    await user.click(screen.getByRole('button', { name: '查看论文' }))
    expect(screen.getAllByRole('dialog', { hidden: true })).toHaveLength(2)

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog', { name: '删除论文' })).not.toBeInTheDocument()
    expect(screen.getByRole('dialog', { name: '添加智能体' })).toBeInTheDocument()
  })

  it('keeps a real busy settings dialog open on Escape and outside click', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    render(
      <SessionSettingsDialog
        session={{ userStyle: '简洁书面语', maxRounds: 2 } as Session}
        busy
        onClose={onClose}
        onSubmit={vi.fn().mockResolvedValue(true)}
      />,
    )

    const dialog = screen.getByRole('dialog', { name: '会话配置' })

    await user.keyboard('{Escape}')
    expect(dialog).toBeInTheDocument()
    await user.click(dialog.parentElement as HTMLElement)
    expect(dialog).toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: '取消' })).toBeDisabled()
  })
})
