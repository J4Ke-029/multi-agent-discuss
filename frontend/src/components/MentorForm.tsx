import { useState } from 'react'

export type MentorFormInput = {
  name: string
  discipline: string
  focus: string
  personality: string
  model: string
  baseUrl: string
  apiKey: string
}

/** 单批论文上限，与后端 MAX_PAPERS_PER_BATCH 对齐 */
export const MENTOR_MAX_BATCH = 20
/** 单文件上限（后端 multipart max-file-size） */
const MENTOR_MAX_FILE_MB = 50

/**
 * 「新建导师」表单。字段比普通智能体少（没有「人设」——导师的 persona 由后端按
 * 称呼 / 学科 / 指导重点 + 两份论文画像生成，手写会和画像打架）。
 *
 * <p>表单可同时选多篇论文：提交时先建导师、再逐篇串行上传（见 {@code useMentors.uploadPapers}）。
 */
export function MentorForm({
  busy,
  hasSessionLlm,
  sessionModel,
  onSubmit,
  onCancel,
}: {
  busy: boolean
  hasSessionLlm: boolean
  sessionModel: string
  onSubmit: (input: MentorFormInput, files: File[]) => Promise<void>
  onCancel: () => void
}) {
  const [useSessionLlm, setUseSessionLlm] = useState(hasSessionLlm)
  const [personalityOn, setPersonalityOn] = useState(false)
  const [files, setFiles] = useState<File[]>([])

  const totalMb = files.reduce((sum, f) => sum + f.size, 0) / 1024 / 1024
  const oversized = files.filter((f) => f.size / 1024 / 1024 > MENTOR_MAX_FILE_MB)
  const tooMany = files.length > MENTOR_MAX_BATCH
  const invalid = oversized.length > 0 || tooMany

  return (
    <form
      className="drawer"
      onSubmit={(e) => {
        e.preventDefault()
        const fd = new FormData(e.currentTarget)
        void onSubmit(
          {
            name: String(fd.get('name') || '').trim(),
            discipline: String(fd.get('discipline') || '').trim(),
            focus: String(fd.get('focus') || '').trim(),
            personality: personalityOn ? String(fd.get('personality') || '').trim() : '',
            model: useSessionLlm ? '' : String(fd.get('model') || '').trim(),
            baseUrl: useSessionLlm ? '' : String(fd.get('baseUrl') || '').trim(),
            apiKey: useSessionLlm ? '' : String(fd.get('apiKey') || '').trim(),
          },
          files,
        )
      }}
    >
      <div className="field">
        <label htmlFor="mentorName">
          导师称呼<span className="req">*</span>
        </label>
        <input id="mentorName" name="name" className="input" required placeholder="例如：张老师" />
      </div>

      <div className="field">
        <label htmlFor="mentorDiscipline">学科方向</label>
        <input
          id="mentorDiscipline"
          name="discipline"
          className="input"
          placeholder="例如：材料物理与化学"
        />
      </div>

      <div className="field">
        <label htmlFor="mentorFocus">希望获得的指导</label>
        <textarea
          id="mentorFocus"
          name="focus"
          className="textarea"
          placeholder="例如：选题、实验设计、论文论证与修改"
          style={{ minHeight: 62 }}
        />
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
            <label htmlFor="mentorModel">
              模型<span className="req">*</span>
            </label>
            <input
              id="mentorModel"
              name="model"
              className="input"
              required
              defaultValue={sessionModel || 'glm-5.1'}
            />
          </div>
          <div className="field">
            <label htmlFor="mentorApiKey">
              API Key<span className="req">*</span>
            </label>
            <input
              id="mentorApiKey"
              name="apiKey"
              className="input"
              type="password"
              required
              autoComplete="off"
              placeholder="不要带 Bearer 前缀"
            />
            <span className="muted">
              导师的 Key 会加密入库（可在导师列表里删除），这样换个会话也能直接用同一个网关。
            </span>
          </div>
          <div className="field">
            <label htmlFor="mentorBaseUrl">Base URL</label>
            <input
              id="mentorBaseUrl"
              name="baseUrl"
              className="input"
              placeholder="https://… 不要 /v1"
            />
          </div>
        </>
      )}

      {useSessionLlm && <input type="hidden" name="model" value={sessionModel || 'glm-5.1'} />}

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
          <label htmlFor="mentorPersonality">性格关键词</label>
          <input
            id="mentorPersonality"
            name="personality"
            className="input"
            placeholder="例如：严谨、直接、鼓励式"
          />
        </div>
      )}

      <div className="field">
        <label>导师论文（可多选，单批最多 {MENTOR_MAX_BATCH} 篇）</label>
        <label className="btn btn-ghost btn-sm file-btn">
          选择论文文件
          <input
            type="file"
            multiple
            accept=".pdf,.txt,.md,.docx,.png,.jpg,.jpeg"
            disabled={busy}
            onChange={(e) => {
              setFiles(Array.from(e.target.files || []))
              e.target.value = ''
            }}
          />
        </label>
        {files.length > 0 && (
          <div className="mentor-file-list">
            <strong>
              已选 {files.length} 篇 · {totalMb.toFixed(1)} MB
            </strong>
            {files.map((f, i) => (
              <span key={`${f.name}-${f.size}-${i}`}>{f.name}</span>
            ))}
          </div>
        )}
        {invalid && (
          <span className="field-error">
            {tooMany
              ? `单批最多 ${MENTOR_MAX_BATCH} 篇，请分批上传。`
              : `单个文件不得超过 ${MENTOR_MAX_FILE_MB} MB：${oversized.map((f) => f.name).join('、')}`}
          </span>
        )}
        <span className="muted">
          论文会逐篇分析：先做逐页理解，再生成结构化摘要，最后汇总出学术风格与知识版图。
          请只上传公开或已获授权的论文。
        </span>
      </div>

      <div className="stack-actions">
        <button className="btn btn-primary btn-sm" type="submit" disabled={busy || invalid}>
          {busy ? '处理中…' : files.length > 0 ? `创建导师并分析 ${files.length} 篇论文` : '创建导师'}
        </button>
        <button className="btn btn-ghost btn-sm" type="button" onClick={onCancel}>
          取消
        </button>
      </div>
    </form>
  )
}
