import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { SkillCandidate, SkillDetail, SkillSummary } from '../api/types'
import { ConfirmDialog } from './ConfirmDialog'

type View = 'library' | 'editor' | 'github' | 'zip'

export function SkillLibraryPanel({ onBusyChange }: { onBusyChange?: (busy: boolean) => void }) {
  const [view, setView] = useState<View>('library')
  const [skills, setSkills] = useState<SkillSummary[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [editing, setEditing] = useState<SkillDetail | null>(null)
  const [markdown, setMarkdown] = useState('---\nname: my-skill\ndescription: 描述这个 Skill 适合在什么任务中使用\n---\n\n# 使用规则\n')
  const [conflictAction, setConflictAction] = useState('ERROR')
  const [renameTo, setRenameTo] = useState('')
  const [pendingDelete, setPendingDelete] = useState<{ skill: SkillSummary; count: number; names: string[] } | null>(null)
  const [githubUrl, setGithubUrl] = useState('')
  const [zipFile, setZipFile] = useState<File | null>(null)
  const [candidates, setCandidates] = useState<SkillCandidate[]>([])
  const [selectedPaths, setSelectedPaths] = useState<Set<string>>(new Set())
  const [renames, setRenames] = useState<Record<string, string>>({})

  useEffect(() => { onBusyChange?.(busy) }, [busy, onBusyChange])

  const load = async () => {
    setBusy(true)
    try { setSkills(await api.listSkills()) }
    catch (e) { setError(e instanceof Error ? e.message : '加载失败') }
    finally { setBusy(false) }
  }

  useEffect(() => { void load() }, [])

  const clearNotice = () => { setError(null); setMessage(null) }

  const openEdit = async (skill: SkillSummary) => {
    clearNotice(); setBusy(true)
    try {
      const detail = await api.getSkill(skill.skillUuid)
      setEditing(detail); setMarkdown(detail.skillMd); setView('editor')
    } catch (e) { setError(e instanceof Error ? e.message : '读取失败') }
    finally { setBusy(false) }
  }

  const saveEditor = async () => {
    clearNotice(); setBusy(true)
    try {
      if (editing) await api.updateSkill(editing.skill.skillUuid, markdown)
      else await api.createSkill({ skillMd: markdown, conflictAction, renameTo: renameTo || undefined })
      setMessage(editing ? 'Skill 已更新，下次生成起生效。' : 'Skill 已创建。')
      setEditing(null); setView('library'); await load()
    } catch (e) { setError(e instanceof Error ? e.message : '保存失败') }
    finally { setBusy(false) }
  }

  const askDelete = async (skill: SkillSummary) => {
    clearNotice(); setBusy(true)
    try {
      const impact = await api.skillDeleteImpact(skill.skillUuid)
      setPendingDelete({ skill, count: impact.bindingCount, names: impact.agents.map((agent) => agent.name) })
    } catch (e) { setError(e instanceof Error ? e.message : '读取删除影响失败') }
    finally { setBusy(false) }
  }

  const deleteSkill = async () => {
    if (!pendingDelete) return
    setBusy(true)
    try {
      await api.deleteSkill(pendingDelete.skill.skillUuid)
      setPendingDelete(null); setMessage('Skill 已删除并从相关智能体解绑。'); await load()
    } catch (e) { setError(e instanceof Error ? e.message : '删除失败') }
    finally { setBusy(false) }
  }

  const scanGitHub = async () => {
    clearNotice(); setBusy(true)
    try {
      const result = await api.scanGitHubSkills(githubUrl.trim())
      setCandidates(result.skills); setSelectedPaths(new Set(result.skills.map((item) => item.path)))
    } catch (e) { setError(e instanceof Error ? e.message : '扫描失败') }
    finally { setBusy(false) }
  }

  const scanZip = async (file: File) => {
    clearNotice(); setBusy(true); setZipFile(file)
    try {
      const result = await api.scanSkillZip(file)
      setCandidates(result); setSelectedPaths(new Set(result.map((item) => item.path)))
    } catch (e) { setError(e instanceof Error ? e.message : '扫描失败') }
    finally { setBusy(false) }
  }

  const importCandidates = async () => {
    clearNotice(); setBusy(true)
    try {
      const paths = [...selectedPaths]
      if (view === 'github') await api.importGitHubSkills(githubUrl.trim(), paths, conflictAction, renames)
      else if (zipFile) await api.importSkillZip(zipFile, paths, conflictAction, renames)
      setCandidates([]); setSelectedPaths(new Set()); setMessage(`已安装 ${paths.length} 个 Skill。`)
      setView('library'); await load()
    } catch (e) { setError(e instanceof Error ? e.message : '安装失败') }
    finally { setBusy(false) }
  }

  const switchView = (next: View) => {
    clearNotice(); setCandidates([]); setSelectedPaths(new Set()); setRenames({}); setEditing(null); setView(next)
  }

  return (
    <>
      <div className="skill-library-panel">
        <div className="panel-head" style={{ padding: 0 }}>
          <h3>Skill 库</h3>
          <span className="hint">账号级 · {skills.length} 个</span>
        </div>
        <div className="skill-tabs" role="tablist" aria-label="Skill 管理方式">
          {([['library', '已安装'], ['editor', '网页创建'], ['github', 'GitHub'], ['zip', 'ZIP']] as const).map(([key, label]) => (
            <button type="button" className={`btn btn-sm ${view === key ? 'btn-primary' : 'btn-ghost'}`}
              key={key} onClick={() => switchView(key)}>{label}</button>
          ))}
        </div>
        {error && <p className="field-error">{error}</p>}
        {message && <p className="skill-success">{message}</p>}

        {view === 'library' && <div className="skill-library-list">
          {!busy && skills.length === 0 && <p className="empty-inline">还没有 Skill，可从网页、GitHub 或 ZIP 添加。</p>}
          {skills.map((skill) => <div className="skill-library-item" key={skill.skillUuid}>
            <div><strong>{skill.name}</strong><p>{skill.description}</p><small>{skill.sourceType}</small></div>
            <div className="skill-row-actions">
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => void openEdit(skill)}>编辑</button>
              <button type="button" className="btn btn-ghost btn-sm agent-remove-btn" onClick={() => void askDelete(skill)}>删除</button>
            </div>
          </div>)}
        </div>}

        {view === 'editor' && <>
          <div className="field"><label htmlFor="skill-markdown">SKILL.md</label>
            <textarea id="skill-markdown" className="textarea skill-editor" value={markdown} onChange={(e) => setMarkdown(e.target.value)} spellCheck={false} />
          </div>
          {!editing && <ConflictFields action={conflictAction} setAction={setConflictAction} renameTo={renameTo} setRenameTo={setRenameTo} />}
          <button type="button" className="btn btn-accent" disabled={busy} onClick={() => void saveEditor()}>{editing ? '保存修改' : '创建 Skill'}</button>
        </>}

        {view === 'github' && <>
          <div className="field"><label htmlFor="skill-github-url">公开 GitHub 仓库或子目录</label>
            <input id="skill-github-url" className="input" value={githubUrl} onChange={(e) => setGithubUrl(e.target.value)} placeholder="https://github.com/owner/repo/tree/main/skills" />
          </div>
          <button type="button" className="btn btn-primary" disabled={busy || !githubUrl.trim()} onClick={() => void scanGitHub()}>扫描</button>
          <CandidatePicker candidates={candidates} selected={selectedPaths} onChange={setSelectedPaths} />
          {candidates.length > 0 && <><ConflictFields action={conflictAction} setAction={setConflictAction} renameTo="" setRenameTo={() => {}} hideRename />
            {conflictAction === 'RENAME' && <RenameCandidates candidates={candidates} selected={selectedPaths} renames={renames} onChange={setRenames} />}
            <button type="button" className="btn btn-accent" disabled={busy || selectedPaths.size === 0} onClick={() => void importCandidates()}>安装所选</button></>}
        </>}

        {view === 'zip' && <>
          <label className="btn btn-primary file-btn">选择 ZIP<input type="file" accept=".zip,application/zip" disabled={busy} onChange={(e) => { const f = e.target.files?.[0]; if (f) void scanZip(f) }} /></label>
          {zipFile && <p className="muted">{zipFile.name}</p>}
          <CandidatePicker candidates={candidates} selected={selectedPaths} onChange={setSelectedPaths} />
          {candidates.length > 0 && <><ConflictFields action={conflictAction} setAction={setConflictAction} renameTo="" setRenameTo={() => {}} hideRename />
            {conflictAction === 'RENAME' && <RenameCandidates candidates={candidates} selected={selectedPaths} renames={renames} onChange={setRenames} />}
            <button type="button" className="btn btn-accent" disabled={busy || selectedPaths.size === 0} onClick={() => void importCandidates()}>安装所选</button></>}
        </>}

      </div>
      {pendingDelete && <ConfirmDialog
        title="删除 Skill" danger busy={busy} confirmText="解绑并删除"
        message={<>确定删除「{pendingDelete.skill.name}」吗？{pendingDelete.count > 0 ? <>它正被 {pendingDelete.count} 个智能体使用（{pendingDelete.names.join('、')}），确认后会统一解绑。</> : '它目前没有绑定智能体。'} 历史调用记录仍会保留。</>}
        onConfirm={() => void deleteSkill()} onCancel={() => setPendingDelete(null)}
      />}
    </>
  )
}

function ConflictFields({ action, setAction, renameTo, setRenameTo, hideRename = false }: {
  action: string; setAction: (value: string) => void; renameTo: string; setRenameTo: (value: string) => void; hideRename?: boolean
}) {
  return <div className="skill-conflict-row"><label>遇到同名 Skill<select className="input" value={action} onChange={(e) => setAction(e.target.value)}>
    <option value="ERROR">停止并提示</option><option value="OVERWRITE">覆盖现有 Skill</option><option value="RENAME">重命名安装</option>
  </select></label>{action === 'RENAME' && !hideRename && <label>新名称<input className="input" value={renameTo} onChange={(e) => setRenameTo(e.target.value)} /></label>}</div>
}

function RenameCandidates({ candidates, selected, renames, onChange }: {
  candidates: SkillCandidate[]; selected: Set<string>; renames: Record<string, string>; onChange: (next: Record<string, string>) => void
}) {
  return <div className="skill-choice-list">{candidates.filter((item) => selected.has(item.path)).map((item) => <label key={item.path}>
    将「{item.name}」重命名为
    <input className="input" value={renames[item.path] || ''} placeholder={`${item.name}-copy`}
      onChange={(e) => onChange({ ...renames, [item.path]: e.target.value })} />
  </label>)}</div>
}

function CandidatePicker({ candidates, selected, onChange }: { candidates: SkillCandidate[]; selected: Set<string>; onChange: (next: Set<string>) => void }) {
  if (candidates.length === 0) return null
  return <div className="skill-choice-list">{candidates.map((candidate) => <label className="skill-choice" key={candidate.path}>
    <input type="checkbox" checked={selected.has(candidate.path)} onChange={(e) => { const next = new Set(selected); if (e.target.checked) next.add(candidate.path); else next.delete(candidate.path); onChange(next) }} />
    <span><strong>{candidate.name}</strong><small>{candidate.description}</small><small>{candidate.path || '仓库根目录'}</small></span>
  </label>)}</div>
}
