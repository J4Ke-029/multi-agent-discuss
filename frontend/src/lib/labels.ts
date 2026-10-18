import type { KnowledgeStatus, MentorStatus, QuestionStatus, ThoughtState, TurnPhase } from '../api/types'

export const knowledgeLabels: Record<KnowledgeStatus, string> = {
  AGENT_CREATED: '已创建',
  DOCUMENT_UPLOADING: '上传中',
  VISION_PROCESSING: '分析中',
  STYLE_ANALYZING: '分析文风',
  KNOWLEDGE_READY: '就绪',
  VISION_FAILED: '失败',
}

export const questionLabels: Record<QuestionStatus, string> = {
  THINKING: '独立思考',
  ALL_THOUGHTS_READY: '思考完成',
  DISCUSSING: '圆桌讨论',
  WAITING_FOR_INTERJECTION: '等待用户插话',
  PROFESSIONAL_CONSENSUS_READY: '专业共识',
  STYLE_FINAL_PASS: '风格定稿',
  REVISION_PLAN_READY: '修改建议',
  AWAITING_CONFIRM: '等待确认',
  REVISING: '改写中',
  REVISED_COMPLETED: '改稿完成',
  COMPLETED: '已完成',
  FAILED: '失败',
}

export const thoughtLabels: Record<ThoughtState, string> = {
  PENDING: '排队',
  RUNNING: '思考中',
  DONE: '完成',
  FAILED: '失败',
  SKIPPED: '跳过',
}

export const phaseLabels: Record<TurnPhase, string> = {
  INDEPENDENT_THINK: '独立思考',
  DISCUSSION: '讨论',
  USER_INTERJECTION: '用户插话',
  CONSENSUS: '共识',
  STYLE_FINAL: '风格终稿',
  REVISION_PLAN: '改稿建议',
  REVISION: '改写',
}

/** 导师库状态。刻意独立于 KnowledgeStatus —— 两者含义不同，混用会让文案串味。 */
export const mentorStatusLabels: Record<MentorStatus, string> = {
  WAITING_PAPERS: '待论文',
  READY: '已就绪',
  FAILED: '失败',
}

const TERMINAL_QUESTION: QuestionStatus[] = [
  'COMPLETED',
  'REVISED_COMPLETED',
  'FAILED',
]

export function isQuestionActive(status: QuestionStatus) {
  return !TERMINAL_QUESTION.includes(status)
}

/** token 数：>1000 用 k，>10000 用 k 取整，否则原样。例 280 / 1.2k / 12k */
export function formatTokens(n: number | null | undefined): string {
  if (n == null || !Number.isFinite(n) || n <= 0) return '0'
  if (n < 1000) return String(Math.round(n))
  if (n < 10000) return `${(n / 1000).toFixed(1).replace(/\.0$/, '')}k`
  return `${Math.round(n / 1000)}k`
}

/** 为智能体分配稳定色相索引 */
export function agentColorIndex(id: string, count = 6) {
  let h = 0
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) >>> 0
  return h % count
}
