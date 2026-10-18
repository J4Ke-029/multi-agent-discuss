export type LibrarySection = 'agents' | 'mentors' | 'skills'

export type AppRoute =
  | { kind: 'home' }
  | { kind: 'workbench' }
  | { kind: 'session'; sessionId: string; questionId?: string }
  | { kind: 'archive'; archiveId: number }
  | { kind: 'library'; section: LibrarySection }
  | { kind: 'not-found' }

const encode = (value: string) => encodeURIComponent(value)

export const appPaths = {
  home: () => '/',
  workbench: () => '/workbench',
  session: (sessionId: string) => `/sessions/${encode(sessionId)}`,
  question: (sessionId: string, questionId: string) =>
    `/sessions/${encode(sessionId)}/questions/${encode(questionId)}`,
  archive: (archiveId: number) => `/archives/${archiveId}`,
  library: (section: LibrarySection = 'agents') => `/library/${section}`,
}

function decoded(value: string): string | null {
  try {
    return decodeURIComponent(value)
  } catch {
    return null
  }
}

export function parseAppRoute(pathname: string): AppRoute {
  if (pathname === '/') return { kind: 'home' }
  if (pathname === '/workbench') return { kind: 'workbench' }
  if (pathname === '/library') return { kind: 'library', section: 'agents' }

  const library = pathname.match(/^\/library\/(agents|mentors|skills)\/?$/)
  if (library) return { kind: 'library', section: library[1] as LibrarySection }

  const question = pathname.match(/^\/sessions\/([^/]+)\/questions\/([^/]+)\/?$/)
  if (question) {
    const sessionId = decoded(question[1])
    const questionId = decoded(question[2])
    if (sessionId && questionId) return { kind: 'session', sessionId, questionId }
  }

  const session = pathname.match(/^\/sessions\/([^/]+)\/?$/)
  if (session) {
    const sessionId = decoded(session[1])
    if (sessionId) return { kind: 'session', sessionId }
  }

  const archive = pathname.match(/^\/archives\/(\d+)\/?$/)
  if (archive) {
    const archiveId = Number(archive[1])
    if (Number.isSafeInteger(archiveId) && archiveId > 0) return { kind: 'archive', archiveId }
  }

  return { kind: 'not-found' }
}
