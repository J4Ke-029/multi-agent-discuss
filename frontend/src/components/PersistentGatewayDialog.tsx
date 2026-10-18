import { useEffect, useRef, useState } from 'react'
import type { LlmPresetDto, UpdatePersistentGatewayRequest } from '../api/types'
import { api } from '../api/client'
import type { PersistentAgentView } from '../hooks/usePersistentAgents'
import { ConfirmDialog } from './ConfirmDialog'
import { Modal, ModalTitle } from './Modal'

type Props = {
  agent: PersistentAgentView
  busy: boolean
  onClose: () => void
  onSubmit: (body: UpdatePersistentGatewayRequest) => Promise<void>
}

/**
 * 「常驻智能体的网关」弹窗。
 *
 * <p>常驻智能体的网关**不需要用户在这里创建** —— 它是在「结束会话 → 保存为常驻」时
 * 从这个智能体当时在用的席位上一并拷下来、加密入库的。这个弹窗只干两件事：
 * 事后修改（换了 Key / 换了模型），以及**改回随本场会话网关**（把三件套清空）。
 *
 * <p>形制照 {@code ProviderDialog}：预设下拉 + 手填 + 「同步保存到预设」，
 * 类名全部复用既有的一套，不新造样式。
 */
export function PersistentGatewayDialog({ agent, busy, onClose, onSubmit }: Props) {
  // 一律从「用它自己的网关」开档 —— 这是「重新配置网关」这个入口唯一说得通的开场白：
  // 没存过网关的人点进来就是要配一份，落在「用本场会话网关」那一档会看到一段没有任何
  // 输入框的说明文字，等于告诉用户「这里没得配」。想改回随本场，右档就在旁边。
  const [mode, setMode] = useState<'OWN' | 'SESSION'>('OWN')
  const [presets, setPresets] = useState<LlmPresetDto[]>([])
  const [presetId, setPresetId] = useState<number | null>(null)
  const [apiKey, setApiKey] = useState('')
  const [baseUrl, setBaseUrl] = useState(agent.baseUrl || '')
  const [model, setModel] = useState(agent.model || '')
  const [saveToPreset, setSaveToPreset] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [loadingKey, setLoadingKey] = useState(false)
  const [pendingClear, setPendingClear] = useState(false)
  /** 与 ProviderDialog 同一套防连切竞态：只认最后一次请求的响应 */
  const reqRef = useRef(0)

  // 打开时把已存的**明文** Key 取回来回填。列表接口只回掩码（前 4 + **** + 后 4），
  // 拿那个值去「确认」会把掩码存成新 Key。
  useEffect(() => {
    if (!agent.hasOwnGateway) return
    let cancelled = false
    const reqId = ++reqRef.current
    setLoadingKey(true)
    void (async () => {
      try {
        const full = await api.revealPersistentAgent(agent.id)
        if (cancelled || reqRef.current !== reqId) return
        setApiKey(full.apiKey || '')
      } catch (e) {
        if (cancelled || reqRef.current !== reqId) return
        setError(e instanceof Error ? e.message : '读取已存网关失败，请手动填写')
      } finally {
        if (!cancelled && reqRef.current === reqId) setLoadingKey(false)
      }
    })()
    return () => {
      cancelled = true
    }
  }, [agent.id, agent.hasOwnGateway])

  useEffect(() => {
    let cancelled = false
    void (async () => {
      try {
        const list = await api.listPresets()
        if (!cancelled) setPresets(list)
      } catch {
        // 预设是可选便利，拉不到就不显示下拉，不打断主流程
      }
    })()
    return () => {
      cancelled = true
    }
  }, [])

  /** 选用预设：一并填入 Base URL / 模型，并取回**明文** Key（预设列表同样只回掩码）。 */
  const applyPreset = async (id: number) => {
    const reqId = ++reqRef.current
    setPresetId(id)
    setError(null)
    const p = presets.find((x) => x.id === id)
    if (!p) return
    setBaseUrl(p.baseUrl || '')
    setModel(p.model || '')
    setApiKey('')
    setLoadingKey(true)
    try {
      const full = await api.revealPreset(id)
      if (reqRef.current !== reqId) return
      setApiKey(full.apiKey || '')
    } catch (e) {
      if (reqRef.current !== reqId) return
      setError(e instanceof Error ? e.message : '读取预设 Key 失败，请手动填写')
    } finally {
      if (reqRef.current === reqId) setLoadingKey(false)
    }
  }

  const working = busy || submitting || loadingKey

  const submit = async () => {
    setError(null)
    setSubmitting(true)
    try {
      if (mode === 'SESSION') {
        await onSubmit({ mode: 'SESSION' })
        return
      }
      if (!apiKey.trim()) {
        setError('请填写 API Key（留空不会当作「不改」——这里就是要改它自己的网关）')
        return
      }
      if (saveToPreset) {
        await api.createPreset({
          label: agent.name,
          apiKey: apiKey.trim(),
          baseUrl,
          model,
        })
      }
      await onSubmit({ mode: 'OWN', apiKey: apiKey.trim(), baseUrl, model })
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Modal
      className="save-dialog provider-dialog"
      onClose={onClose}
      closeOnEscape={!working}
      closeOnOutside={!working}
    >
        <ModalTitle asChild><h3>{agent.name} · 网关</h3></ModalTitle>
        <p className="muted provider-sub">
          影响<strong>以后新加入</strong>的会话席位；已经在本场的席位用的是加入时的快照，不受影响。
        </p>

        {error && (
          <div className="auth-top-error" role="alert" style={{ marginBottom: '0.6rem' }}>
            {error}
          </div>
        )}

        <div className="gateway-choice">
          <label className={`gateway-choice-option ${mode === 'OWN' ? 'is-on' : ''}`}>
            <input
              type="radio"
              name="gatewayMode"
              checked={mode === 'OWN'}
              onChange={() => setMode('OWN')}
            />
            用它自己的网关
          </label>
          <label className={`gateway-choice-option ${mode === 'SESSION' ? 'is-on' : ''}`}>
            <input
              type="radio"
              name="gatewayMode"
              checked={mode === 'SESSION'}
              onChange={() => setMode('SESSION')}
            />
            用本场会话网关
          </label>
        </div>

        {mode === 'OWN' ? (
          <>
            {presets.length > 0 && (
              <div className="field">
                <label>从预设填入</label>
                <select
                  className="input"
                  value={presetId == null ? '' : String(presetId)}
                  onChange={(e) => {
                    const v = e.target.value
                    if (v) void applyPreset(Number(v))
                  }}
                >
                  <option value="">— 选择已保存预设 —</option>
                  {presets.map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.label} · {p.model}
                    </option>
                  ))}
                </select>
              </div>
            )}
            <div className="field">
              <label>
                API Key<span className="req">*</span>
              </label>
              <input
                className="input"
                type="password"
                autoComplete="off"
                value={apiKey}
                onChange={(e) => setApiKey(e.target.value)}
                placeholder={loadingKey ? '读取中…' : '不要带 Bearer 前缀'}
              />
              <span className="muted">
                Key 会<strong>加密入库</strong>（jasypt），列表只显示前后 4 位；跨设备跟着你的账号走。
              </span>
            </div>
            <div className="field">
              <label>Base URL</label>
              <input
                className="input"
                value={baseUrl}
                onChange={(e) => setBaseUrl(e.target.value)}
                placeholder="https://… 不要 /v1"
              />
            </div>
            <div className="field">
              <label>模型</label>
              <input className="input" value={model} onChange={(e) => setModel(e.target.value)} />
            </div>
            <label className="check-row">
              <input
                type="checkbox"
                checked={saveToPreset}
                onChange={(e) => setSaveToPreset(e.target.checked)}
              />
              同时保存到预设（入库，供其它会话选用）
            </label>
          </>
        ) : (
          <p className="muted">
            加入本场时改用它所在会话的网关，这个常驻智能体<strong>不再保存任何 Key</strong>
            （已有的一并清除）。
          </p>
        )}

        <div className="save-dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose} disabled={working}>
            取消
          </button>
          {mode === 'OWN' && agent.hasOwnGateway && (
            <button
              type="button"
              className="btn btn-ghost"
              disabled={working}
              onClick={() => setPendingClear(true)}
            >
              改回用本场网关
            </button>
          )}
          <button
            type="button"
            className="btn btn-accent"
            disabled={working}
            onClick={() => void submit()}
          >
            {loadingKey ? '读取中…' : submitting ? '保存中…' : '保存'}
          </button>
        </div>
      {pendingClear && (
        <ConfirmDialog
          title="改回用本场网关"
          confirmText="确认清除"
          busy={submitting}
          message={
            <>
              确定要让「{agent.name}」不再自带网关吗？它已存的 API Key、Base URL 与模型会被
              <strong>一并清除</strong>，以后加入本场时会用该会话的网关；会话没配网关时会加不进来。
            </>
          }
          onConfirm={() => {
            setPendingClear(false)
            setMode('SESSION')
            void onSubmit({ mode: 'SESSION' }).catch((e: unknown) =>
              setError(e instanceof Error ? e.message : '清除失败'),
            )
          }}
          onCancel={() => setPendingClear(false)}
        />
      )}
    </Modal>
  )
}
