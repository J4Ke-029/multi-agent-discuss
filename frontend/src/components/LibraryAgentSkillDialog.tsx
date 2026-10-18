import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { SkillSummary, SkillTargetType } from '../api/types'
import { Modal, ModalTitle } from './Modal'

type BoundSkill = Pick<SkillSummary, 'skillUuid' | 'name' | 'description'>

export function LibraryAgentSkillDialog({ agentName, targetType, targetUuid, initialSkills, onChange, onClose }: {
  agentName: string
  targetType: Exclude<SkillTargetType, 'TEMPORARY'>
  targetUuid: string
  initialSkills: BoundSkill[]
  onChange: (skills: BoundSkill[]) => void
  onClose: () => void
}) {
  const [skills, setSkills] = useState<SkillSummary[]>([])
  const [selected, setSelected] = useState(new Set(initialSkills.map((skill) => skill.skillUuid)))
  const [busy, setBusy] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    void api.listSkills().then((items) => {
      if (active) setSkills(items)
    }).catch((cause: unknown) => {
      if (active) setError(cause instanceof Error ? cause.message : '加载 Skill 失败')
    }).finally(() => { if (active) setBusy(false) })
    return () => { active = false }
  }, [])

  const toggle = async (skillUuid: string, checked: boolean) => {
    const previous = new Set(selected)
    const next = new Set(selected)
    if (checked) next.add(skillUuid)
    else next.delete(skillUuid)
    if (next.size > 5) return
    setSelected(next)
    setBusy(true)
    setError(null)
    try {
      const saved = await api.replaceSkillBindings({
        targetType,
        targetUuid,
        skillUuids: [...next],
      })
      setSelected(new Set(saved.map((skill) => skill.skillUuid)))
      onChange(saved)
    } catch (cause) {
      setSelected(previous)
      setError(cause instanceof Error ? cause.message : '保存失败，已恢复上一次绑定')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal className="save-dialog skill-dialog" onClose={onClose} closeOnOutside={!busy} closeOnEscape={!busy}>
      <ModalTitle asChild><h3>管理「{agentName}」的 Skill</h3></ModalTitle>
      <p className="muted">修改会立即保存，最多选择 5 个；仅影响以后新加入的会话。</p>
      {error && <p className="field-error">{error}</p>}
      <div className="skill-choice-list">
        {!busy && skills.length === 0 && <p className="empty-inline">Skill 库为空，请先从智能体库安装。</p>}
        {skills.map((skill) => {
          const checked = selected.has(skill.skillUuid)
          return <label className={`skill-choice${checked ? ' is-selected' : ''}`} key={skill.skillUuid}>
            <input type="checkbox" checked={checked}
              disabled={busy || (!checked && selected.size >= 5)}
              onChange={(event) => void toggle(skill.skillUuid, event.target.checked)} />
            <span><strong>{skill.name}</strong><small>{skill.description}</small></span>
          </label>
        })}
      </div>
      <div className="save-dialog-actions">
        <span className="muted">已选择 {selected.size}/5</span>
        <button type="button" className="btn btn-ghost" onClick={onClose} disabled={busy}>完成</button>
      </div>
    </Modal>
  )
}
