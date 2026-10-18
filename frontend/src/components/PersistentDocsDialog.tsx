import { useCallback, useEffect, useMemo, useState } from 'react'
import { api } from '../api/client'
import type { PersistentDoc, PersistentDocDetail } from '../api/types'
import type { PersistentAgentView } from '../hooks/usePersistentAgents'
import { ConfirmDialog } from './ConfirmDialog'
import { Modal, ModalTitle } from './Modal'

type Props = {
  agent: PersistentAgentView
  /** 重跑摘要时借用的会话网关。这个常驻自己没有网关、又不在活会话里时可以是 null。 */
  sessionId: string | null
  onClose: () => void
  /** 库里条数变了（删了一篇）时通知上层刷新常驻列表 —— 卡片上的「N 个文件 · M 页」要跟着变 */
  onChanged: () => void
}

/**
 * 常驻智能体的**知识库**：它读过的资料，跨会话攒在账号下。
 *
 * <p>形制照 {@code MentorPapersDialog}（同为大尺寸弹窗、左列表 + 右九字段详情，类名全部复用
 * {@code mentor-*} 那一套），但**刻意补上导师那边缺的三件事**：
 * <ol>
 *   <li><b>删除入口</b>。这是个只增不减的累积库，没有删除就等于没有出路。
 *       导师那边 {@code api.deleteMentorPaper} 至今是死代码，不该照抄那个疏漏。</li>
 *   <li><b>摘要失败可重试</b>。页知识早就在库里了、检索与讨论照常可用，失败的只是九字段那一步；
 *       重跑不会产生第二条记录。</li>
 *   <li><b>空态说明来历与限制</b>，而不是一片空白。</li>
 * </ol>
 *
 * <p><b>不做上传入口</b>：知识的唯一入口是「结束会话时自动带上」。想给它补资料，就在新会话里
 * 加入它、给它传文件、结束会话。
 */
export function PersistentDocsDialog({ agent, sessionId, onClose, onChanged }: Props) {
  const [docs, setDocs] = useState<PersistentDoc[]>([])
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [detail, setDetail] = useState<PersistentDocDetail | null>(null)
  const [query, setQuery] = useState('')
  const [loading, setLoading] = useState(true)
  const [detailLoading, setDetailLoading] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [pendingDelete, setPendingDelete] = useState<PersistentDoc | null>(null)

  /** 重新拉列表。`keepSelection=false` 时（删完一篇）回落到第一篇。 */
  const reload = useCallback(
    async (keepSelection = true) => {
      try {
        const list = await api.listPersistentDocs(agent.agentId)
        setDocs(list)
        setSelectedId((prev) => {
          if (keepSelection && prev != null && list.some((d) => d.id === prev)) return prev
          return list[0]?.id ?? null
        })
      } catch (e) {
        setError(e instanceof Error ? e.message : '加载知识库失败')
      }
    },
    [agent.agentId],
  )

  useEffect(() => {
    let cancelled = false
    void (async () => {
      setLoading(true)
      try {
        const list = await api.listPersistentDocs(agent.agentId)
        if (cancelled) return
        setDocs(list)
        setSelectedId(list[0]?.id ?? null)
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : '加载知识库失败')
      } finally {
        if (!cancelled) setLoading(false)
      }
    })()
    return () => {
      cancelled = true
    }
  }, [agent.agentId])

  // 选中项变化才拉详情，用 cancelled 兜住「快速连点两篇、先发的后到」的覆盖
  useEffect(() => {
    if (selectedId == null) {
      setDetail(null)
      return
    }
    let cancelled = false
    void (async () => {
      setDetailLoading(true)
      try {
        const d = await api.getPersistentDoc(agent.agentId, selectedId)
        if (!cancelled) setDetail(d)
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : '加载摘要失败')
      } finally {
        if (!cancelled) setDetailLoading(false)
      }
    })()
    return () => {
      cancelled = true
    }
  }, [agent.agentId, selectedId])

  const visible = useMemo(() => {
    const kw = query.trim().toLowerCase()
    if (!kw) return docs
    return docs.filter((d) =>
      [d.title, d.fileName, d.authors, d.keywords]
        .filter(Boolean)
        .some((v) => String(v).toLowerCase().includes(kw)),
    )
  }, [docs, query])

  const pageTotal = docs.reduce((n, d) => n + (d.pageCount || 0), 0)
  const readyCount = docs.filter((d) => d.status === 'READY').length

  const doDelete = async (doc: PersistentDoc) => {
    setBusy(true)
    setError(null)
    try {
      await api.deletePersistentDoc(agent.agentId, doc.id)
      await reload(false)
      onChanged()
    } catch (e) {
      setError(e instanceof Error ? e.message : '删除失败')
    } finally {
      setBusy(false)
    }
  }

  const retrySummary = async (doc: PersistentDoc) => {
    setBusy(true)
    setError(null)
    try {
      const updated = await api.summarizePersistentDoc(doc.id, sessionId)
      setDetail((prev) => (prev && prev.id === updated.id ? updated : prev))
      await reload(true)
    } catch (e) {
      setError(e instanceof Error ? e.message : '重新生成摘要失败')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      className="save-dialog mentor-dialog"
      onClose={onClose}
      closeOnEscape={!pendingDelete}
      closeOnOutside={!pendingDelete}
    >
        <div className="mentor-head">
          <div>
            <ModalTitle asChild><h3>{agent.name} · 知识库</h3></ModalTitle>
            <p className="muted">
              {docs.length} 个文件{pageTotal > 0 ? ` · ${pageTotal} 页` : ''}
              {docs.length > 0 ? `（${readyCount} 个已生成摘要）` : ''}
            </p>
          </div>
          <button type="button" className="btn btn-ghost btn-sm" onClick={onClose}>
            关闭
          </button>
        </div>

        {error && (
          <div className="auth-top-error" role="alert" style={{ marginBottom: '0.6rem' }}>
            {error}
          </div>
        )}

        {loading ? (
          <p className="muted">正在读取知识库…</p>
        ) : docs.length === 0 ? (
          <p className="muted">
            这个智能体还没有攒下知识。在会话里给它传资料，结束会话时勾选「保存智能体」，
            它当场读过的内容就会存进这里，以后加入任何会话自动带上，<strong>不用重传</strong>。
            <br />
            存的是逐页理解的结果，<strong>原始文件不入库</strong> —— 所以换模型后摘要可以重跑，
            页理解不能。
          </p>
        ) : (
          <div className="mentor-body">
            <div className="mentor-paper-list">
              <input
                className="input mentor-search"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="搜索标题、作者或关键词"
              />
              <div className="mentor-paper-items">
                {visible.length === 0 ? (
                  <p className="muted">没有匹配的资料。</p>
                ) : (
                  visible.map((d) => (
                    <button
                      key={d.id}
                      type="button"
                      className={`mentor-paper-item ${selectedId === d.id ? 'is-active' : ''}`}
                      onClick={() => setSelectedId(d.id)}
                    >
                      <strong>{d.title || d.fileName}</strong>
                      <span className="muted">{d.fileName}</span>
                      <small>
                        {d.status === 'READY'
                          ? `${d.pageCount} 页 · 已生成摘要`
                          : d.status === 'PENDING'
                            ? `${d.pageCount} 页 · 摘要未生成`
                            : `${d.pageCount} 页 · 摘要失败`}
                      </small>
                    </button>
                  ))
                )}
              </div>
            </div>

            <div className="mentor-paper-detail">
              {/* 只认「当前选中那份」的详情：请求在途时旧详情仍在 state 里，
                  不比对 id 就会先闪一下上一篇的内容 */}
              {detailLoading && (!detail || detail.id !== selectedId) ? (
                <p className="muted">读取中…</p>
              ) : !detail || detail.id !== selectedId ? (
                <p className="muted">选择左侧一份资料查看完整摘要。</p>
              ) : (
                <>
                  <h4>{detail.title || detail.fileName}</h4>
                  <p className="muted">
                    {(detail.authors || '作者未识别')} · {detail.pubYear || '年份未识别'} ·{' '}
                    {detail.pageCount} 页
                  </p>
                  <p className="muted">来源文件：{detail.fileName}</p>

                  {detail.status === 'FAILED' ? (
                    // 摘要失败**不影响这份知识可用** —— 页已经在库里，讨论照常检索得到，
                    // 少的只是这里这九个字段。所以是「重试」而不是一个红叉。
                    <>
                      <p className="auth-top-error">
                        {detail.errorMessage || '这份资料的摘要没有生成成功'}
                      </p>
                      <p className="muted">
                        它的页知识已经存好了，讨论时照样能用；重跑摘要只会补上这九个字段，
                        <strong>不会重复存一份</strong>。
                      </p>
                      <button
                        type="button"
                        className="btn btn-accent btn-sm"
                        disabled={busy}
                        onClick={() => void retrySummary(detail)}
                      >
                        {busy ? '生成中…' : '重新生成摘要'}
                      </button>
                    </>
                  ) : (
                    <div className="mentor-sections">
                      <Section title="具体摘要" value={detail.abstractText} featured />
                      <Section title="研究问题" value={detail.researchQuestion} />
                      <Section title="方法与数据" value={detail.methodology} />
                      <Section title="主要发现" value={detail.findings} />
                      <Section title="局限与证据边界" value={detail.limitations} />
                      <Section title="关键词" value={detail.keywords} />
                    </div>
                  )}

                  <div className="save-dialog-actions">
                    <button
                      type="button"
                      className="btn btn-ghost btn-sm"
                      disabled={busy}
                      onClick={() => setPendingDelete(detail)}
                    >
                      从知识库删除
                    </button>
                  </div>
                </>
              )}
            </div>
          </div>
        )}
      {pendingDelete && (
        <ConfirmDialog
          title="从知识库删除"
          danger
          confirmText="删除"
          busy={busy}
          message={
            <>
              确定要删掉「{pendingDelete.title || pendingDelete.fileName}」吗？它的{' '}
              {pendingDelete.pageCount} 页知识会一并删除，<strong>无法恢复</strong>。
              原始文件当初没有入库，所以这份知识<strong>重建不了</strong> —— 想补回来，
              只能在会话里重新传一次这个文件、结束会话时再存一遍。
            </>
          }
          onConfirm={() => {
            const d = pendingDelete
            setPendingDelete(null)
            void doDelete(d)
          }}
          onCancel={() => setPendingDelete(null)}
        />
      )}
    </Modal>
  )
}

function Section({
  title,
  value,
  featured = false,
}: {
  title: string
  value?: string | null
  featured?: boolean
}) {
  return (
    <section className={`mentor-section ${featured ? 'featured' : ''}`}>
      <h5>{title}</h5>
      <p>{value || '资料中未识别到该项信息。'}</p>
    </section>
  )
}
