import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AgentLibraryDialog } from '../src/components/AgentLibraryDialog'

const apiMock = vi.hoisted(() => ({
  listPersistentAgents: vi.fn(),
  listMentors: vi.fn(),
  getSkillBindingOverview: vi.fn(),
  listSkills: vi.fn(),
  replaceSkillBindings: vi.fn(),
}))

vi.mock('../src/api/client', () => ({ api: apiMock }))

describe('AgentLibraryDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMock.listPersistentAgents.mockResolvedValue([{ id: 1, agentId: 'persistent-1', name: '研究员', apiKey: null }])
    apiMock.listMentors.mockResolvedValue([{ id: 2, mentorUuid: 'mentor-1', name: '李导师', status: 'READY', paperCount: 2, pageCount: 10, apiKey: null }])
    apiMock.getSkillBindingOverview.mockResolvedValue([
      { targetType: 'PERSISTENT', targetUuid: 'persistent-1', skills: [{ skillUuid: 'skill-1', name: '文献审查', description: '审查文献' }], activeInSession: false },
      { targetType: 'MENTOR', targetUuid: 'mentor-1', skills: [], activeInSession: false },
    ])
    apiMock.listSkills.mockResolvedValue([
      { skillUuid: 'skill-1', name: '文献审查', description: '审查文献', sourceType: 'WEB' },
      { skillUuid: 'skill-2', name: '结构化写作', description: '整理结构', sourceType: 'WEB' },
    ])
    apiMock.replaceSkillBindings.mockResolvedValue([
      { skillUuid: 'skill-1', name: '文献审查', description: '审查文献' },
      { skillUuid: 'skill-2', name: '结构化写作', description: '整理结构' },
    ])
  })

  it('shows persistent agents and mentors with their bound Skills, plus the existing Skill library', async () => {
    const user = userEvent.setup()
    render(<AgentLibraryDialog onClose={() => {}} />)

    expect(await screen.findByText('研究员')).toBeInTheDocument()
    expect(await screen.findByText('文献审查')).toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: '导师智能体' }))
    expect(await screen.findByText('李导师')).toBeInTheDocument()
    expect(screen.getByText('未绑定 Skill')).toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: 'Skill 库' }))
    expect(await screen.findByText('审查文献')).toBeInTheDocument()
  })

  it('saves library bindings immediately and refreshes the card', async () => {
    const user = userEvent.setup()
    render(<AgentLibraryDialog onClose={() => {}} />)

    await screen.findByText('研究员')
    await user.click(screen.getByRole('button', { name: '管理 Skill' }))
    await user.click(await screen.findByRole('checkbox', { name: /结构化写作/ }))

    expect(apiMock.replaceSkillBindings).toHaveBeenCalledWith({
      targetType: 'PERSISTENT',
      targetUuid: 'persistent-1',
      skillUuids: ['skill-1', 'skill-2'],
    })
    await user.click(screen.getByRole('button', { name: '完成' }))
    expect(await screen.findByText('结构化写作')).toBeInTheDocument()
  })
})
