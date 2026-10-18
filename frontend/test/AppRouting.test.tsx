import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../src/App'
import type { Session } from '../src/api/types'

const apiMock = vi.hoisted(() => ({
  getSession: vi.fn(),
  listSessions: vi.fn().mockResolvedValue([]),
  getArchived: vi.fn(),
  listPersistentAgents: vi.fn().mockResolvedValue([]),
  listMentors: vi.fn().mockResolvedValue([]),
  questionStatus: vi.fn(),
  transcript: vi.fn(),
}))

vi.mock('../src/api/client', () => ({
  AUTH_LOST_EVENT: 'mad:auth:lost',
  api: apiMock,
}))

function LocationProbe() {
  const location = useLocation()
  return <output aria-label="current-path">{location.pathname}</output>
}

function session(questionIds: string[] = []): Session {
  return {
    sessionId: 'session-1', userStyle: '', autoDiscuss: true, docEditMode: false,
    historyLimit: 20, maxRounds: 2, createdAt: '', agents: [], questionIds,
  }
}

describe('App routing', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    apiMock.listSessions.mockResolvedValue([])
  })

  it('loads a session directly from its URL without relying on local storage', async () => {
    apiMock.getSession.mockResolvedValue(session())

    render(<MemoryRouter initialEntries={['/sessions/session-1']}><App /><LocationProbe /></MemoryRouter>)

    expect(await screen.findByText('等待第一道议题')).toBeInTheDocument()
    expect(apiMock.getSession).toHaveBeenCalledWith('session-1')
    expect(screen.getByLabelText('current-path')).toHaveTextContent('/sessions/session-1')
  })

  it('keeps refresh and home actions available on an idle workbench', async () => {
    const user = userEvent.setup()

    render(<MemoryRouter initialEntries={['/workbench']}><App /><LocationProbe /></MemoryRouter>)

    const refresh = await screen.findByRole('button', { name: '刷新' })
    await waitFor(() => expect(apiMock.listSessions).toHaveBeenCalled())
    const callsBeforeRefresh = apiMock.listSessions.mock.calls.length
    await user.click(refresh)
    expect(apiMock.listSessions).toHaveBeenCalledTimes(callsBeforeRefresh + 1)

    await user.click(screen.getByRole('button', { name: '返回首页' }))
    expect(screen.getByLabelText('current-path')).toHaveTextContent('/')
  })

  it('keeps a failed deep link in place and offers a retry', async () => {
    apiMock.getSession.mockRejectedValue(new Error('会话不存在或无权限'))

    render(<MemoryRouter initialEntries={['/sessions/missing']}><App /><LocationProbe /></MemoryRouter>)

    expect(await screen.findByRole('heading', { name: '页面加载失败' })).toBeInTheDocument()
    expect(screen.getByText('会话不存在或无权限')).toBeInTheDocument()
    expect(screen.getByLabelText('current-path')).toHaveTextContent('/sessions/missing')
  })

  it('replaces an invalid question URL with the latest question in that session', async () => {
    apiMock.getSession.mockResolvedValue(session(['question-1']))
    apiMock.questionStatus.mockResolvedValue({
      questionId: 'question-1', status: 'COMPLETED', thoughts: {}, skippedAgents: [],
      transcriptTurns: 0, hasRevisionPlan: false, hasRevisedDocument: false, updatedAt: '',
    })
    apiMock.transcript.mockResolvedValue({
      questionId: 'question-1', content: 'Q', status: 'COMPLETED', thoughts: {}, turns: [],
    })

    render(<MemoryRouter initialEntries={['/sessions/session-1/questions/missing']}>
      <App /><LocationProbe />
    </MemoryRouter>)

    await waitFor(() => expect(screen.getByLabelText('current-path'))
      .toHaveTextContent('/sessions/session-1/questions/question-1'))
  })

  it('loads an archived discussion directly from its URL', async () => {
    const user = userEvent.setup()
    apiMock.getArchived.mockResolvedValue({
      id: 42, title: '已归档讨论', questionCount: 0, agentCount: 0, tokenTotal: 0,
      sourceSessionId: 'session-old', archivedAt: '',
      payload: {
        session: { id: 'session-old', createdAt: '', autoDiscuss: true, docEditMode: false,
          maxRounds: 2, llm: { configured: false }, agents: [] },
        questions: [],
      },
    })

    render(<MemoryRouter initialEntries={['/archives/42']}><App /><LocationProbe /></MemoryRouter>)

    expect(await screen.findByRole('heading', { name: '已归档讨论' })).toBeInTheDocument()
    expect(apiMock.getArchived).toHaveBeenCalledWith(42)
    expect(screen.getByLabelText('current-path')).toHaveTextContent('/archives/42')

    const archiveLoadsBeforeRefresh = apiMock.getArchived.mock.calls.length
    await user.click(screen.getByRole('button', { name: '刷新' }))
    await waitFor(() => expect(apiMock.getArchived).toHaveBeenCalledTimes(archiveLoadsBeforeRefresh + 1))

    await user.click(screen.getByRole('button', { name: '返回首页' }))
    expect(screen.getByLabelText('current-path')).toHaveTextContent('/')
  })

  it('keeps the account-library URL while asking an anonymous user to log in', async () => {
    render(<MemoryRouter initialEntries={['/library/skills']}><App /><LocationProbe /></MemoryRouter>)

    expect(await screen.findByRole('dialog')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '登录议事厅' })).toBeInTheDocument()
    expect(screen.getByLabelText('current-path')).toHaveTextContent('/library/skills')
  })
})
