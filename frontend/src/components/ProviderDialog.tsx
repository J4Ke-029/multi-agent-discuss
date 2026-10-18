import { useEffect, useRef, useState } from 'react'
import type { LlmPresetDto, Session } from '../api/types'
import { api } from '../api/client'
import { ConfirmDialog } from './ConfirmDialog'
import { Modal, ModalTitle } from './Modal'

type Props = {
  session: Session
  busy: boolean
  onClose: () => void
  onUpdate: (data: {
    llmApiKey?: string
    llmBaseUrl: string
    llmModel: string
    llmLabel: string
  }) => Promise<void>
}

/**
 * 「切换供应商」弹窗 —— 原 {@code LlmSettingsPanel} 的展开内容搬进模态框。
 *
 * <p>左栏不再常驻一整块网关设置（它长期占地方，而大多数会话配一次就不动），
 * 只留一行摘要 + 一个按钮，点开才是这个框。
 */
export function ProviderDialog({ session, busy, onClose, onUpdate }: Props) {
  const llm = session.llm
  const [presets, setPresets] = useState<LlmPresetDto[]>([])
  const [presetId, setPresetId] = useState<number | null>(null)
  const [label, setLabel] = useState(llm?.label || '')
  const [apiKey, setApiKey] = useState('')
  const [baseUrl, setBaseUrl] = useState(llm?.baseUrl || '')
  const [model, setModel] = useState(llm?.model || 'glm-5.1')
  const [saveLocal, setSaveLocal] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [pendingDelete, setPendingDelete] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  /** 正在从后端取所选预设的明文 Key */
  const [loadingKey, setLoadingKey] = useState(false)
  /** 预设切换的请求序号：只认最后一次，避免快速连切时旧响应把 Key 覆盖回去 */
  const presetReqRef = useRef(0)

  useEffect(() => {
    let cancelled = false
    void (async () => {
      try {
        const list = await api.listPresets()
        if (!cancelled) setPresets(list)
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : '加载预设失败')
      }
    })()
    return () => {
      cancelled = true
    }
  }, [])

  /**
   * 选用预设：填入名称 / Base URL / 模型，并从后端取回**明文 Key** 一并填入。
   *
   * <p>list 接口只回掩码 Key，所以这里显式调 `reveal`（仅 owner 可访问）。
   * 早期实现是选完把 Key 清空让人重敲，但预设里存的本就是完整凭据 —— 每次都重输
   * 等于预设没起作用，所以改回自动回填。
   */
  const applyPreset = async (id: number) => {
    const reqId = ++presetReqRef.current
    setPresetId(id)
    setError(null)
    const p = presets.find((x) => x.id === id)
    if (!p) return
    setLabel(p.label)
    setBaseUrl(p.baseUrl || '')
    setModel(p.model || 'glm-5.1')
    setApiKey('')
    setLoadingKey(true)
    try {
      const full = await api.revealPreset(id)
      if (presetReqRef.current !== reqId) return
      setApiKey(full.apiKey || '')
    } catch (e) {
      if (presetReqRef.current !== reqId) return
      setError(e instanceof Error ? e.message : '读取预设 Key 失败，请手动填写')
    } finally {
      if (presetReqRef.current === reqId) setLoadingKey(false)
    }
  }

  const deletePreset = async () => {
    if (presetId == null) return
    setError(null)
    try {
      await api.deletePreset(presetId)
      setPresets(await api.listPresets())
      setPresetId(null)
      setLabel('')
      setApiKey('')
      setBaseUrl('')
      setModel('glm-5.1')
    } catch (e) {
      setError(e instanceof Error ? e.message : '删除失败')
    }
  }

  const submit = async () => {
    setError(null)
    setSubmitting(true)
    try {
      if (saveLocal && apiKey) {
        if (presetId == null) {
          await api.createPreset({ label: label || '会话供应商', apiKey, baseUrl, model })
        } else {
          await api.updatePreset(presetId, { label: label || '会话供应商', apiKey, baseUrl, model })
        }
        setPresets(await api.listPresets())
      }
      await onUpdate({
        llmApiKey: apiKey || undefined,
        llmBaseUrl: baseUrl,
        llmModel: model,
        llmLabel: label,
      })
      onClose()
    } catch (e) {
      setError(e instanceof Error ? e.message : '更新失败')
    } finally {
      setSubmitting(false)
    }
  }

  const working = busy || submitting || loadingKey

  return (
    <Modal
      className="save-dialog provider-dialog"
      onClose={onClose}
      closeOnEscape={!working}
      closeOnOutside={!working}
    >
        <ModalTitle asChild><h3>切换供应商</h3></ModalTitle>
        <p className="muted provider-sub">
          当前：
          {llm?.configured
            ? `${llm.label || '已配置'} · ${llm.model || '—'}`
            : '未配置（Host 用服务器默认网关）'}
        </p>

        {error && (
          <div className="auth-top-error" role="alert" style={{ marginBottom: '0.6rem' }}>
            {error}
          </div>
        )}

        {presets.length > 0 && (
          <div className="field">
            <label>选用预设</label>
            <div style={{ display: 'flex', gap: '0.4rem' }}>
              <select
                className="input"
                style={{ flex: 1 }}
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
              {presetId != null && (
                <button
                  type="button"
                  className="btn btn-ghost btn-sm"
                  style={{ flexShrink: 0 }}
                  onClick={() => setPendingDelete(true)}
                >
                  删除
                </button>
              )}
            </div>
          </div>
        )}

        <div className="field">
          <label>名称</label>
          <input className="input" value={label} onChange={(e) => setLabel(e.target.value)} />
        </div>
        <div className="field">
          <label>API Key（留空则保留原 Key）</label>
          <input
            className="input"
            type="password"
            autoComplete="off"
            value={apiKey}
            onChange={(e) => setApiKey(e.target.value)}
            placeholder={loadingKey ? '读取预设中…' : llm?.configured ? '********' : '必填（首次）'}
          />
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
            checked={saveLocal}
            onChange={(e) => setSaveLocal(e.target.checked)}
          />
          同步保存到预设（入库）
        </label>

        <div className="save-dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose} disabled={working}>
            取消
          </button>
          <button
            type="button"
            className="btn btn-accent"
            disabled={working || (!apiKey && !llm?.configured)}
            onClick={() => void submit()}
          >
            {loadingKey ? '读取预设…' : submitting ? '应用中…' : '应用到本会话'}
          </button>
        </div>

        <p className="muted provider-foot">
          Host 立即切换。已注册智能体仍用创建时的 Key；新注册且勾选「使用会话网关」的会跟新配置。
        </p>
      {pendingDelete && (
        <ConfirmDialog
          title="删除预设"
          danger
          confirmText="删除"
          message={`确定要删除预设「${presets.find((p) => p.id === presetId)?.label || presetId}」吗？`}
          onConfirm={() => {
            setPendingDelete(false)
            void deletePreset()
          }}
          onCancel={() => setPendingDelete(false)}
        />
      )}
    </Modal>
  )
}
