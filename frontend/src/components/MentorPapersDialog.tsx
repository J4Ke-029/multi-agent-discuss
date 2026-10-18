import { useEffect, useMemo, useState } from 'react'
import { api } from '../api/client'
import type { MentorDetail, MentorPaper, MentorPaperDetail } from '../api/types'
import { Modal, ModalTitle } from './Modal'

type Props = {
  mentorId: number
  onClose: () => void
}

/**
 * 导师论文摘要库 —— 大尺寸弹窗，左侧论文索引（可搜索），右侧单篇九字段详情。
 *
 * <p>刻意不做成整屏工作台：导师在议事厅里只是一张智能体卡片，摘要属于「点开细看」的
 * 次级内容，放进弹窗才不会把三栏主界面挤掉。
 *
 * <p>列表接口不回摘要正文（一次拉几十篇长文太重），所以选中哪篇才去取哪篇的详情。
 */
export function MentorPapersDialog({ mentorId, onClose }: Props) {
  const [mentor, setMentor] = useState<MentorDetail | null>(null)
  const [papers, setPapers] = useState<MentorPaper[]>([])
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [detail, setDetail] = useState<MentorPaperDetail | null>(null)
  const [query, setQuery] = useState('')
  const [loading, setLoading] = useState(true)
  const [detailLoading, setDetailLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    void (async () => {
      setLoading(true)
      try {
        const [m, list] = await Promise.all([api.getMentor(mentorId), api.listMentorPapers(mentorId)])
        if (cancelled) return
        setMentor(m)
        setPapers(list.papers)
        setSelectedId(list.papers[0]?.id ?? null)
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : '加载导师论文失败')
      } finally {
        if (!cancelled) setLoading(false)
      }
    })()
    return () => {
      cancelled = true
    }
  }, [mentorId])

  // 选中项变化才拉详情。用 cancelled 兜住「快速连点两篇、先发的后到」的覆盖
  useEffect(() => {
    if (selectedId == null) {
      setDetail(null)
      return
    }
    let cancelled = false
    void (async () => {
      setDetailLoading(true)
      try {
        const d = await api.getMentorPaper(mentorId, selectedId)
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
  }, [mentorId, selectedId])

  const visible = useMemo(() => {
    const kw = query.trim().toLowerCase()
    if (!kw) return papers
    return papers.filter((p) =>
      [p.title, p.fileName, p.authors, p.keywords]
        .filter(Boolean)
        .some((v) => String(v).toLowerCase().includes(kw)),
    )
  }, [papers, query])

  const readyCount = papers.filter((p) => p.status === 'READY').length

  return (
    <Modal className="save-dialog mentor-dialog" onClose={onClose}>
        <div className="mentor-head">
          <div>
            <ModalTitle asChild>
              <h3>{mentor?.name ? `${mentor.name} · 论文摘要库` : '导师论文摘要库'}</h3>
            </ModalTitle>
            <p className="muted">
              {mentor?.discipline ? `${mentor.discipline} · ` : ''}
              {papers.length} 篇（{readyCount} 篇已分析）
              {mentor?.pageCount ? ` · ${mentor.pageCount} 页` : ''}
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

        {(mentor?.focus || mentor?.styleProfile || mentor?.knowledgeMap) && (
          <div className="mentor-profile-bar">
            <details>
              <summary>学术风格画像</summary>
              <p>{mentor?.styleProfile || '尚未完成风格画像（还没有上传导师论文）。'}</p>
            </details>
            {mentor?.knowledgeMap && (
              <details>
                <summary>知识版图</summary>
                <p>{mentor.knowledgeMap}</p>
              </details>
            )}
            {mentor?.focus && (
              <details>
                <summary>指导重点</summary>
                <p>{mentor.focus}</p>
              </details>
            )}
          </div>
        )}

        {loading ? (
          <p className="muted">正在读取论文摘要…</p>
        ) : papers.length === 0 ? (
          <p className="muted">这个导师还没有论文。请先在智能体卡片上上传导师论文。</p>
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
                  <p className="muted">没有匹配的论文。</p>
                ) : (
                  visible.map((p) => (
                    <button
                      key={p.id}
                      type="button"
                      className={`mentor-paper-item ${selectedId === p.id ? 'is-active' : ''}`}
                      onClick={() => setSelectedId(p.id)}
                    >
                      <strong>{p.title || p.fileName}</strong>
                      <span className="muted">{p.fileName}</span>
                      <small>
                        {p.status === 'READY' ? `${p.pageCount} 页 · 已分析` : '分析失败'}
                      </small>
                    </button>
                  ))
                )}
              </div>
            </div>

            <div className="mentor-paper-detail">
              {/* 只认「当前选中那篇」的详情：请求在途时旧详情仍在 state 里，
                  不比对 id 就会先闪一下上一篇的内容 */}
              {detailLoading && (!detail || detail.id !== selectedId) ? (
                <p className="muted">读取中…</p>
              ) : !detail || detail.id !== selectedId ? (
                <p className="muted">选择左侧一篇论文查看完整摘要。</p>
              ) : detail.status === 'FAILED' ? (
                <>
                  <h4>{detail.fileName}</h4>
                  <p className="auth-top-error">{detail.errorMessage || '这篇论文的分析失败了'}</p>
                </>
              ) : (
                <>
                  <h4>{detail.title || detail.fileName}</h4>
                  <p className="muted">
                    {(detail.authors || '作者未识别')} · {detail.pubYear || '年份未识别'} ·{' '}
                    {detail.pageCount} 页
                  </p>
                  <p className="muted">来源文件：{detail.fileName}</p>
                  <div className="mentor-sections">
                    <Section title="具体摘要" value={detail.abstractText} featured />
                    <Section title="研究问题" value={detail.researchQuestion} />
                    <Section title="方法与数据" value={detail.methodology} />
                    <Section title="主要发现" value={detail.findings} />
                    <Section title="局限与证据边界" value={detail.limitations} />
                    <Section title="关键词" value={detail.keywords} />
                  </div>
                </>
              )}
            </div>
          </div>
        )}
    </Modal>
  )
}

function Section({ title, value, featured = false }: { title: string; value?: string | null; featured?: boolean }) {
  return (
    <section className={`mentor-section ${featured ? 'featured' : ''}`}>
      <h5>{title}</h5>
      <p>{value || '论文中未识别到该项信息。'}</p>
    </section>
  )
}
