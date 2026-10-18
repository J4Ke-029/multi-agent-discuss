import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { SkillBindingOverview, SkillSummary, SkillTargetType } from '../api/types'
import { Modal, ModalTitle } from './Modal'
import { LibraryAgentSkillDialog } from './LibraryAgentSkillDialog'
import { SkillLibraryPanel } from './SkillLibraryDialog'

type Section = 'persistent' | 'mentors' | 'skills'

type LibraryAgent = {
  uuid: string
  type: SkillTargetType
  name: string
  detail: string
  skills: Array<Pick<SkillSummary, 'skillUuid' | 'name' | 'description'>>
  activeInSession: boolean
  bindingError: boolean
}

export function AgentLibraryDialog({ onClose }: { onClose: () => void }) {
  const [section, setSection] = useState<Section>('persistent')
  const [skillBusy, setSkillBusy] = useState(false)
  return (
    <Modal className="save-dialog agent-library-dialog" onClose={onClose}
      closeOnEscape={!skillBusy} closeOnOutside={!skillBusy}>
      <AgentLibraryContent section={section} onSectionChange={setSection} onClose={onClose}
        onSkillBusyChange={setSkillBusy} inDialog />
    </Modal>
  )
}

export function AgentLibraryContent({ section, onSectionChange, onClose, onSkillBusyChange, inDialog = false }: {
  section: Section
  onSectionChange: (section: Section) => void
  onClose?: () => void
  onSkillBusyChange?: (busy: boolean) => void
  inDialog?: boolean
}) {
  const [agents, setAgents] = useState<LibraryAgent[]>([])
  const [busy, setBusy] = useState(true)
  const [skillBusy, setSkillBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [editingAgent, setEditingAgent] = useState<LibraryAgent | null>(null)
  const showingSkills = section === 'skills'

  useEffect(() => {
    if (showingSkills) return
    let active = true
    setBusy(true)
    setError(null)
    void Promise.all([api.listPersistentAgents(), api.listMentors(), api.getSkillBindingOverview()])
      .then(([persistent, mentors, overview]) => {
        const bindingByTarget = new Map(overview.map((item: SkillBindingOverview) =>
          [`${item.targetType}:${item.targetUuid}`, item],
        ))
        const items: LibraryAgent[] = [
          ...persistent.map((agent) => ({
            uuid: agent.agentId,
            type: 'PERSISTENT' as const,
            name: agent.customName || agent.name,
            detail: agent.model || '常驻智能体',
            skills: bindingByTarget.get(`PERSISTENT:${agent.agentId}`)?.skills || [],
            activeInSession: bindingByTarget.get(`PERSISTENT:${agent.agentId}`)?.activeInSession || false,
            bindingError: false,
          })),
          ...mentors.map((mentor) => ({
            uuid: mentor.mentorUuid,
            type: 'MENTOR' as const,
            name: mentor.name,
            detail: [mentor.discipline, `${mentor.paperCount} 篇论文`].filter(Boolean).join(' · '),
            skills: bindingByTarget.get(`MENTOR:${mentor.mentorUuid}`)?.skills || [],
            activeInSession: bindingByTarget.get(`MENTOR:${mentor.mentorUuid}`)?.activeInSession || false,
            bindingError: false,
          })),
        ]
        if (!active) return
        setAgents(items)
      })
      .catch((cause: unknown) => {
        if (active) setError(cause instanceof Error ? cause.message : '加载智能体失败')
      })
      .finally(() => { if (active) setBusy(false) })
    return () => { active = false }
  }, [showingSkills])

  const visibleAgents = agents.filter((agent) => agent.type === (section === 'mentors' ? 'MENTOR' : 'PERSISTENT'))

  return (
    <div className="agent-library-content">
      <div className="panel-head agent-library-head">
        <div>
          {inDialog ? <ModalTitle asChild><h3>智能体库</h3></ModalTitle> : <h1>智能体库</h1>}
          <p>查看账号中的智能体、绑定的 Skill，以及已安装的 Skill。</p>
        </div>
      </div>
      <div className="agent-library-tabs" role="tablist" aria-label="智能体库分类">
        {([
          ['persistent', '常驻智能体'],
          ['mentors', '导师智能体'],
          ['skills', 'Skill 库'],
        ] as const).map(([key, label]) => (
          <button type="button" role="tab" aria-selected={section === key}
            className={`agent-library-tab${section === key ? ' is-active' : ''}`}
            key={key} disabled={skillBusy} onClick={() => onSectionChange(key)}>{label}</button>
        ))}
      </div>
      {section === 'skills' ? <SkillLibraryPanel onBusyChange={(nextBusy) => {
        setSkillBusy(nextBusy)
        onSkillBusyChange?.(nextBusy)
      }} /> : (
        <div className="agent-library-list" role="tabpanel">
          {error && <p className="field-error">{error}</p>}
          {busy && <p className="empty-inline">正在加载智能体…</p>}
          {!busy && !error && visibleAgents.length === 0 && (
            <p className="empty-inline">{section === 'mentors' ? '还没有导师智能体。' : '还没有常驻智能体。'}</p>
          )}
          {!busy && visibleAgents.map((agent) => (
            <div className="agent-library-item" key={`${agent.type}:${agent.uuid}`}>
              <div className="agent-library-item-head">
                <div><strong>{agent.name}</strong><small>{agent.detail}</small></div>
                <div className="skill-row-actions">
                  <span className="agent-library-count">{agent.bindingError ? '读取失败' : `${agent.skills.length}/5 Skill`}</span>
                  <button type="button" className="btn btn-ghost btn-sm" disabled={agent.activeInSession}
                    title={agent.activeInSession ? '该智能体正在活跃会话中，结束或移出后可修改' : undefined}
                    onClick={() => setEditingAgent(agent)}>管理 Skill</button>
                </div>
              </div>
              <div className="agent-library-bindings">
                {agent.bindingError ? <span className="field-error">绑定的 Skill 读取失败</span>
                  : agent.skills.length === 0 ? <span className="muted">未绑定 Skill</span>
                    : agent.skills.map((skill) => <span className="agent-library-skill" key={skill.skillUuid}>{skill.name}</span>)}
              </div>
              {agent.activeInSession && <p className="agent-library-lock-note">该智能体正在活跃会话中，结束或移出后可修改</p>}
            </div>
          ))}
        </div>
      )}
      {onClose && <div className="save-dialog-actions"><button type="button" className="btn btn-ghost" onClick={onClose} disabled={skillBusy}>关闭</button></div>}
      {editingAgent && <LibraryAgentSkillDialog agentName={editingAgent.name}
        targetType={editingAgent.type as Exclude<SkillTargetType, 'TEMPORARY'>}
        targetUuid={editingAgent.uuid} initialSkills={editingAgent.skills}
        onChange={(skills) => {
          setAgents((current) => current.map((agent) => agent.type === editingAgent.type && agent.uuid === editingAgent.uuid
            ? { ...agent, skills }
            : agent))
          setEditingAgent((current) => current ? { ...current, skills } : null)
        }}
        onClose={() => setEditingAgent(null)} />}
    </div>
  )
}
