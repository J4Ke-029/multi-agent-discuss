import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AgentSkillDialog } from '../src/components/AgentSkillDialog'
import type { Agent, SkillSummary } from '../src/api/types'

const apiMock = vi.hoisted(() => ({
  listSkills: vi.fn(),
  getSkillBindings: vi.fn(),
  replaceSkillBindings: vi.fn(),
}))

vi.mock('../src/api/client', () => ({ api: apiMock }))

describe('AgentSkillDialog', () => {
  beforeEach(() => vi.clearAllMocks())

  it('enforces five selections and saves the current seat binding', async () => {
    const skills = Array.from({ length: 6 }, (_, index): SkillSummary => ({
      skillUuid: `skill-${index + 1}`,
      name: `Skill ${index + 1}`,
      description: `Description ${index + 1}`,
      sourceType: 'WEB',
    }))
    apiMock.listSkills.mockResolvedValue(skills)
    apiMock.getSkillBindings.mockResolvedValue(skills.slice(0, 5))
    apiMock.replaceSkillBindings.mockResolvedValue([])
    const user = userEvent.setup()

    render(<AgentSkillDialog
      agent={{ agentId: 'agent-1', name: 'Analyst' } as Agent}
      sessionId="session-1"
      onClose={() => {}}
      onSaved={() => {}}
    />)

    const sixth = await screen.findByRole('checkbox', { name: /Skill 6/i })
    expect(sixth).toBeDisabled()
    await user.click(screen.getByRole('checkbox', { name: /Skill 1/i }))
    expect(sixth).toBeEnabled()
    await user.click(sixth)
    await user.click(screen.getByRole('button', { name: '保存' }))

    await waitFor(() => expect(apiMock.replaceSkillBindings).toHaveBeenCalledWith({
      targetType: 'TEMPORARY',
      targetUuid: 'agent-1',
      sessionId: 'session-1',
      skillUuids: ['skill-2', 'skill-3', 'skill-4', 'skill-5', 'skill-6'],
    }))
  })
})
