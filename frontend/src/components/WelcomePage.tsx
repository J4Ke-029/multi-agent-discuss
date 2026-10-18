import type { User } from '../api/types'
import { UserMenu } from './UserMenu'

type Props = {
  onStartSetup: () => void
  /** 有当前会话时显示「回到当前会话」 */
  onResume?: () => void
  /** 已登录用户：右上角显示用户名（点击展开菜单，退出在其中）；未登录：显示「登录 / 注册」按钮 */
  currentUser: User | null
  onLoginClick: () => void
  onLogout: () => void
  onAgentLibrary: () => void
}

export function WelcomePage({
  onStartSetup,
  onResume,
  currentUser,
  onLoginClick,
  onLogout,
  onAgentLibrary,
}: Props) {
  return (
    <div className="welcome-container">
      <div className="welcome-topbar">
        {currentUser ? (
          <>
            <UserMenu user={currentUser} onLogout={onLogout} />
            <button type="button" className="btn btn-ghost btn-sm agent-library-trigger" onClick={onAgentLibrary}>智能体库</button>
          </>
        ) : (
          <button
            type="button"
            className="btn btn-ghost btn-sm auth-login-btn"
            onClick={onLoginClick}
          >
            登录 / 注册
          </button>
        )}
      </div>

      <div className="welcome-content">
        <div className="welcome-brand">
          <div className="brand-mark">
            <BrandMark />
          </div>
          <div className="brand-text">
            <div className="brand-title">议事厅</div>
            <div className="brand-sub">Multi-agent discuss</div>
          </div>
        </div>

        <div className="welcome-main">
          <div className="welcome-hero">
            <h1>把多位专家请上同一张圆桌</h1>
            <p className="lead">
              在网页中配置AI网关即可开启专家讨论，支持多套供应商预设，
              支持文档改写、智能体知识库等专业功能。
            </p>
          </div>

          <div className="welcome-features">
            <div className="feature-item">
              <div className="feature-icon">🧠</div>
              <div className="feature-content">
                <h3>多智能体讨论</h3>
                <p>不同领域的专家从各自角度分析问题，互相补充</p>
              </div>
            </div>
            <div className="feature-item">
              <div className="feature-icon">📚</div>
              <div className="feature-content">
                <h3>知识库支持</h3>
                <p>上传专业文献，让专家基于最新研究进行讨论</p>
              </div>
            </div>
            <div className="feature-item">
              <div className="feature-icon">✍️</div>
              <div className="feature-content">
                <h3>文档改写</h3>
                <p>基于讨论结果自动生成修改建议，优化文本质量</p>
              </div>
            </div>
          </div>

          <div className="welcome-actions">
            <button
              className="btn btn-accent btn-lg"
              onClick={onStartSetup}
            >
              开启议事厅
            </button>
            {onResume && (
              <button
                className="btn btn-ghost btn-lg"
                onClick={onResume}
              >
                回到当前会话
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

function BrandMark() {
  return (
    <svg viewBox="0 0 32 32" fill="none" aria-hidden>
      <circle cx="10" cy="14" r="3" fill="#D4A84B" />
      <circle cx="22" cy="14" r="3" fill="#7B8CDE" />
      <circle cx="16" cy="22" r="3" fill="#3DBE9A" />
      <path
        d="M10 14h6M22 14h-6M16 14v8"
        stroke="#F4EFE6"
        strokeWidth="1.2"
        opacity="0.45"
      />
    </svg>
  )
}
