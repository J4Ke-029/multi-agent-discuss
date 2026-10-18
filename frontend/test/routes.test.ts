import { describe, expect, it } from 'vitest'
import { appPaths, parseAppRoute } from '../src/router/paths'

describe('application routes', () => {
  it('builds encoded deep links for sessions and questions', () => {
    expect(appPaths.session('session/one')).toBe('/sessions/session%2Fone')
    expect(appPaths.question('session one', 'question/one')).toBe(
      '/sessions/session%20one/questions/question%2Fone',
    )
  })

  it('recognizes the public route shapes and rejects malformed resource ids', () => {
    expect(parseAppRoute('/')).toEqual({ kind: 'home' })
    expect(parseAppRoute('/workbench')).toEqual({ kind: 'workbench' })
    expect(parseAppRoute('/sessions/s-1/questions/q-1')).toEqual({
      kind: 'session', sessionId: 's-1', questionId: 'q-1',
    })
    expect(parseAppRoute('/archives/42')).toEqual({ kind: 'archive', archiveId: 42 })
    expect(parseAppRoute('/archives/not-a-number')).toEqual({ kind: 'not-found' })
    expect(parseAppRoute('/library/skills')).toEqual({ kind: 'library', section: 'skills' })
  })
})
