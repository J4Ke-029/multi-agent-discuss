import type { User } from '../api/types'
import type { LibrarySection } from '../router/paths'
import { AgentLibraryContent } from './AgentLibraryDialog'
import { UserMenu } from './UserMenu'

const contentSection = (section: LibrarySection) => section === 'agents' ? 'persistent' : section
const routeSection = (section: 'persistent' | 'mentors' | 'skills'): LibrarySection =>
  section === 'persistent' ? 'agents' : section

export function AgentLibraryPage({ section, currentUser, onSectionChange, onBack, onLogin, onLogout }: {
  section: LibrarySection
  currentUser: User | null
  onSectionChange: (section: LibrarySection) => void
  onBack: () => void
  onLogin: () => void
  onLogout: () => void | Promise<void>
}) {
  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="brand"><div className="brand-text"><div className="brand-title">智能体库</div>
          <div className="brand-sub">Agent & Skill library</div></div></div>
        <div className="header-meta">
          {currentUser
            ? <UserMenu user={currentUser} onLogout={() => void onLogout()} />
            : <button type="button" className="btn btn-ghost btn-sm" onClick={onLogin}>登录</button>}
          <button type="button" className="btn btn-ghost btn-sm" onClick={onBack}>返回议事厅</button>
        </div>
      </header>
      <main className="library-page">
        {currentUser ? (
          <section className="panel library-page-panel">
            <AgentLibraryContent section={contentSection(section)}
              onSectionChange={(next) => onSectionChange(routeSection(next))} />
          </section>
        ) : (
          <section className="panel library-page-panel route-message">
            <h1>需要登录</h1>
            <p className="muted">智能体库是账号级功能，登录后会自动回到当前地址。</p>
            <button type="button" className="btn btn-accent" onClick={onLogin}>登录</button>
          </section>
        )}
      </main>
    </div>
  )
}
