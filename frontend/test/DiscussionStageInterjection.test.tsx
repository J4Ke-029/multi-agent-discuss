import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { DiscussionStage } from '../src/components/DiscussionStage'
import type { QuestionProgress, Session } from '../src/api/types'

describe('DiscussionStage interjections', () => {
  it('lets the user target an agent or continue from a round boundary', async () => {
    const user = userEvent.setup()
    const onInterject = vi.fn().mockResolvedValue(undefined)
    const onContinueDiscussion = vi.fn().mockResolvedValue(undefined)
    const onToggleInterjections = vi.fn().mockResolvedValue(undefined)
    const session = {
      sessionId: 'session-1', autoDiscuss: true, allowInterjections: true, docEditMode: false,
      historyLimit: 20, maxRounds: 3, createdAt: '', questionIds: ['question-1'],
      agents: [{ agentId: 'agent-1', name: '研究员', knowledgeStatus: 'KNOWLEDGE_READY' }],
    } as Session
    const progress = {
      questionId: 'question-1', status: 'WAITING_FOR_INTERJECTION', thoughts: { 'agent-1': 'DONE' }, skippedAgents: [],
      transcriptTurns: 2, hasRevisionPlan: false, hasRevisedDocument: false, updatedAt: '',
    } as QuestionProgress

    render(<DiscussionStage session={session} activeQid="question-1" progress={progress}
      transcript={null} revisionPlan={null} revisedDocument={null} busy={false}
      onAsk={vi.fn()} onDiscuss={vi.fn()} onToggleInterjections={onToggleInterjections}
      onInterject={onInterject} onContinueDiscussion={onContinueDiscussion}
      onConfirmRevision={vi.fn()} onContinueRevision={vi.fn()} onGenerateSummary={vi.fn()}
      questionPreviews={{}} />)

    await user.selectOptions(screen.getByLabelText('插话对象'), 'agent-1')
    await user.type(screen.getByPlaceholderText(/补充信息/), '请说明证据强度')
    await user.click(screen.getByRole('button', { name: '发送插话' }))
    expect(onInterject).toHaveBeenCalledWith('请说明证据强度', 'agent-1')

    await user.click(screen.getByRole('button', { name: '不插话，继续讨论' }))
    expect(onContinueDiscussion).toHaveBeenCalledOnce()
    await user.click(screen.getByRole('checkbox', { name: '允许插话' }))
    expect(onToggleInterjections).toHaveBeenCalledWith(false)
  })
})
