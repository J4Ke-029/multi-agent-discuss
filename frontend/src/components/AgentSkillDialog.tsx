import { useEffect, useMemo, useState } from 'react'
import { api } from '../api/client'
import type { Agent, SkillSummary, SkillTargetType } from '../api/types'
import { Modal, ModalTitle } from './Modal'

export function AgentSkillDialog({
  agent,
  sessionId,
  onClose,
  onSaved,
}: {
  agent: Agent
  sessionId: string
  onClose: () => void
  onSaved: () => void
}) {
  const target = useMemo((): { type: SkillTargetType; uuid: string } => {
    if (agent.mentor && agent.mentorUuid) return { type: 'MENTOR', uuid: agent.mentorUuid }
    if (agent.agentUuid) return { type: 'PERSISTENT', uuid: agent.agentUuid }
    return { type: 'TEMPORARY', uuid: agent.agentId }
  }, [agent])
  const [skills, setSkills] = useState<SkillSummary[]>([])
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [busy, setBusy] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    void Promise.all([
      api.listSkills(),
      api.getSkillBindings(target.type, target.uuid, sessionId),
    ]).then(([all, bound]) => {
      if (!alive) return
      setSkills(all)
      setSelected(new Set(bound.map((skill) => skill.skillUuid)))
    }).catch((e: unknown) => {
      if (alive) setError(e instanceof Error ? e.message : '加载 Skill 失败')
    }).finally(() => {
      if (alive) setBusy(false)
    })
    return () => { alive = false }
  }, [sessionId, target.type, target.uuid])

  const save = async () => {
    setBusy(true)
    setError(null)
    try {
      await api.replaceSkillBindings({
        targetType: target.type,
        targetUuid: target.uuid,
        sessionId,
        skillUuids: [...selected],
      })
      onSaved()
      onClose()
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal className="save-dialog skill-dialog" onClose={onClose} closeOnOutside={!busy}>
      <ModalTitle asChild><h3>为「{agent.name}」选择 Skill</h3></ModalTitle>
      <p className="muted">最多选择 5 个。智能体只会在任务相关时自动调用，单次最多 2 个。</p>
      {error && <p className="field-error">{error}</p>}
      <div className="skill-choice-list">
        {!busy && skills.length === 0 && <p className="empty-inline">Skill 库为空，请先从智能体库安装。</p>}
        {skills.map((skill) => {
          const checked = selected.has(skill.skillUuid)
          const blocked = !checked && selected.size >= 5
          return (
            <label className={`skill-choice${checked ? ' is-selected' : ''}`} key={skill.skillUuid}>
              <input
                type="checkbox"
                checked={checked}
                disabled={busy || blocked}
                onChange={(event) => setSelected((previous) => {
                  const next = new Set(previous)
                  if (event.target.checked) next.add(skill.skillUuid)
                  else next.delete(skill.skillUuid)
                  return next
                })}
              />
              <span>
                <strong>{skill.name}</strong>
                <small>{skill.description}</small>
              </span>
            </label>
          )
        })}
      </div>
      <div className="save-dialog-actions">
        <span className="muted">已选择 {selected.size}/5</span>
        <button type="button" className="btn btn-ghost" onClick={onClose} disabled={busy}>取消</button>
        <button type="button" className="btn btn-accent" onClick={() => void save()} disabled={busy}>
          {busy ? '处理中…' : '保存'}
        </button>
      </div>
    </Modal>
  )
}
