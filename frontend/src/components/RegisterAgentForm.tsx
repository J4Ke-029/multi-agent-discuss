import { useState } from 'react'

export type RegisterAgentInput = {
  name: string
  apiKey: string
  baseUrl: string
  model: string
  persona: string
  personality: string
  useSessionLlm: boolean
}

/**
 * 「注册领域智能体」表单。原先内联在 {@code AgentPanel} 里，
 * 「添加智能体」弹窗出现后移到独立文件，由 {@code AddAgentDialog} 渲染。
 */
export function RegisterAgentForm({
  busy,
  hasSessionLlm,
  sessionModel,
  onCancel,
  onSubmit,
}: {
  busy: boolean
  hasSessionLlm: boolean
  sessionModel: string
  onCancel: () => void
  onSubmit: (data: RegisterAgentInput) => Promise<void>
}) {
  const [useSessionLlm, setUseSessionLlm] = useState(hasSessionLlm)
  const [personalityOn, setPersonalityOn] = useState(false)

  return (
    <form
      className="drawer"
      onSubmit={(e) => {
        e.preventDefault()
        const fd = new FormData(e.currentTarget)
        void onSubmit({
          name: String(fd.get('name') || '').trim(),
          apiKey: String(fd.get('apiKey') || '').trim(),
          baseUrl: String(fd.get('baseUrl') || '').trim(),
          model: String(fd.get('model') || '').trim(),
          persona: String(fd.get('persona') || '').trim(),
          personality: personalityOn ? String(fd.get('personality') || '').trim() : '',
          useSessionLlm,
        })
      }}
    >
      <div className="field">
        <label htmlFor="name">
          名称<span className="req">*</span>
        </label>
        <input id="name" name="name" className="input" required placeholder="材料学专家" />
      </div>

      <label className="check-row">
        <input
          type="checkbox"
          checked={useSessionLlm}
          disabled={!hasSessionLlm}
          onChange={(e) => setUseSessionLlm(e.target.checked)}
        />
        使用会话网关
        {!hasSessionLlm && '（请先在上方配置）'}
      </label>

      {!useSessionLlm && (
        <>
          <div className="field">
            <label htmlFor="model">
              模型<span className="req">*</span>
            </label>
            <input
              id="model"
              name="model"
              className="input"
              required
              defaultValue={sessionModel || 'glm-5.1'}
            />
          </div>
          <div className="field">
            <label htmlFor="apiKey">
              API Key<span className="req">*</span>
            </label>
            <input
              id="apiKey"
              name="apiKey"
              className="input"
              type="password"
              required
              autoComplete="off"
              placeholder="不要带 Bearer 前缀"
            />
          </div>
          <div className="field">
            <label htmlFor="baseUrl">Base URL</label>
            <input
              id="baseUrl"
              name="baseUrl"
              className="input"
              placeholder="https://… 不要 /v1"
            />
          </div>
        </>
      )}

      {useSessionLlm && (
        <input type="hidden" name="model" value={sessionModel || 'glm-5.1'} />
      )}

      <div className="field">
        <label htmlFor="persona">人设 / 领域</label>
        <textarea
          id="persona"
          name="persona"
          className="textarea"
          placeholder="你是…领域专家，擅长…"
          style={{ minHeight: 64 }}
        />
      </div>

      <label className="check-row">
        <input
          type="checkbox"
          checked={personalityOn}
          onChange={(e) => setPersonalityOn(e.target.checked)}
        />
        启用性格（影响讨论发言）
      </label>
      {personalityOn && (
        <div className="field">
          <label htmlFor="personality">性格关键词</label>
          <input
            id="personality"
            name="personality"
            className="input"
            placeholder="例如：严谨、毒舌、幽默、温柔、固执、激进、保守、怀疑"
          />
          <span className="muted">
            该智能体的所有发言都会体现这些性格。
            常用关键词会被后端展开为具体行为清单（语气、用词、自我校验）。
            可输入多个，用空格 /顿号分隔。
          </span>
        </div>
      )}

      <div className="stack-actions">
        <button className="btn btn-primary btn-sm" type="submit" disabled={busy}>
          确认注册
        </button>
        <button className="btn btn-ghost btn-sm" type="button" onClick={onCancel}>
          取消
        </button>
      </div>
    </form>
  )
}
