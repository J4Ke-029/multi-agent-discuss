import type { KnowledgeStatus, QuestionStatus } from '../api/types'
import { knowledgeLabels, questionLabels } from '../lib/labels'

type Kind = 'knowledge' | 'question'

const knowledgeTone: Record<KnowledgeStatus, string> = {
  AGENT_CREATED: 'chip-idle',
  DOCUMENT_UPLOADING: 'chip-busy',
  VISION_PROCESSING: 'chip-busy',
  STYLE_ANALYZING: 'chip-busy',
  KNOWLEDGE_READY: 'chip-ready',
  VISION_FAILED: 'chip-fail',
}

const questionTone: Record<QuestionStatus, string> = {
  THINKING: 'chip-busy',
  ALL_THOUGHTS_READY: 'chip-busy',
  DISCUSSING: 'chip-busy',
  WAITING_FOR_INTERJECTION: 'chip-idle',
  PROFESSIONAL_CONSENSUS_READY: 'chip-busy',
  STYLE_FINAL_PASS: 'chip-busy',
  REVISION_PLAN_READY: 'chip-busy',
  AWAITING_CONFIRM: 'chip-busy',
  REVISING: 'chip-busy',
  REVISED_COMPLETED: 'chip-ready',
  COMPLETED: 'chip-ready',
  FAILED: 'chip-fail',
}

export function StatusChip({
  kind,
  value,
}: {
  kind: Kind
  value: string
}) {
  let label = value
  let tone = 'chip-idle'
  if (kind === 'knowledge') {
    const v = value as KnowledgeStatus
    label = knowledgeLabels[v] ?? value
    tone = knowledgeTone[v] ?? tone
  } else {
    const v = value as QuestionStatus
    label = questionLabels[v] ?? value
    tone = questionTone[v] ?? tone
  }
  return <span className={`chip ${tone}`}>{label}</span>
}
