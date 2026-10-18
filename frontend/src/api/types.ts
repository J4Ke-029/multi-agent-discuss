export type KnowledgeStatus =
  | 'AGENT_CREATED'
  | 'DOCUMENT_UPLOADING'
  | 'VISION_PROCESSING'
  | 'STYLE_ANALYZING'
  | 'KNOWLEDGE_READY'
  | 'VISION_FAILED'

export type QuestionStatus =
  | 'THINKING'
  | 'ALL_THOUGHTS_READY'
  | 'DISCUSSING'
  | 'WAITING_FOR_INTERJECTION'
  | 'PROFESSIONAL_CONSENSUS_READY'
  | 'STYLE_FINAL_PASS'
  | 'REVISION_PLAN_READY'
  | 'AWAITING_CONFIRM'
  | 'REVISING'
  | 'REVISED_COMPLETED'
  | 'COMPLETED'
  | 'FAILED'

export type ThoughtState = 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED' | 'SKIPPED'

/** token 用量；与后端 QuestionProgress.progressView.tokenUsage / Transcript.tokenUsage 字段对齐 */
export interface TokenUsage {
  input: number
  output: number
  total: number
  /** 估算成本（人民币），按粗略单价 0.002 元/1k tokens */
  cost: number
}

/** 单次发言的 token 用量（与后端 Turn.usage / TokenUsage 字段对齐） */
export interface TurnUsage {
  inputTokens?: number | null
  outputTokens?: number | null
  totalTokens?: number | null
  costEstimate?: number | null
  model?: string | null
}

export type TurnPhase =
  | 'INDEPENDENT_THINK'
  | 'DISCUSSION'
  | 'USER_INTERJECTION'
  | 'CONSENSUS'
  | 'STYLE_FINAL'
  | 'REVISION_PLAN'
  | 'REVISION'

export interface LlmEndpointView {
  configured: boolean
  apiKey?: string
  baseUrl?: string | null
  model?: string | null
  label?: string | null
}

export interface Agent {
  agentId: string
  name: string
  model: string
  baseUrl: string
  apiKey: string
  persona: string
  /** 性格关键词（可选）：影响该智能体所有发言 */
  personality?: string
  knowledgeStatus: KnowledgeStatus
  statusMessage?: string
  documentFileName?: string
  /** Vision 页级进度 */
  progress?: {
    current: number
    total: number
    ratio: number
  }
  /** 是否为导师模式席位（后端对所有席位都返回；false = 普通智能体） */
  mentor?: boolean
  /** 仅导师席位有：账号级导师的稳定标识 */
  mentorUuid?: string | null
  mentorDiscipline?: string | null
  /** 加入本会话那一刻导师库里的论文篇数 */
  mentorPaperCount?: number
  /**
   * 仅「从常驻库加入」的席位有：账号级常驻智能体的稳定标识。
   * 后端据此把跨会话心得挂到这个席位上；普通注册的临时席位没有这个键。
   */
  agentUuid?: string | null
  /**
   * 本场读过、但还没存进常驻知识库的文件 —— 后端**只在为真时**给这个键。
   * 结束会话时据此决定知识轮要覆盖哪些席位。
   */
  hasNewUpload?: boolean
  /** 本场入场时是否带着既往经验（决定左栏卡片要不要显示「带经验上场」） */
  hasMemory?: boolean
  /** 心得最后一次总结的时间（epoch millis）；hasMemory 为真时才有 */
  memoryUpdatedAt?: number | null
  /** 当前席位下一次生成时可自动选择的 Skill UUID（最多 5 个） */
  skillUuids?: string[]
}

export interface SkillSummary {
  skillUuid: string
  name: string
  description: string
  sourceType: 'WEB' | 'ZIP' | 'GITHUB'
  sourceUrl?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface SkillDetail {
  skill: SkillSummary
  skillMd: string
  files: Array<{ path: string; size: number; executable: false }>
  bindingCount: number
}

export interface SkillCandidate {
  path: string
  name: string
  description: string
}

export interface SkillInvocation {
  skillUuid: string
  name: string
  reason: string
  at: string
}

export type SkillTargetType = 'TEMPORARY' | 'PERSISTENT' | 'MENTOR'

export interface SkillBindingOverview {
  targetType: SkillTargetType
  targetUuid: string
  skills: Array<Pick<SkillSummary, 'skillUuid' | 'name' | 'description'>>
  activeInSession: boolean
}

export interface Session {
  sessionId: string
  userStyle: string
  autoDiscuss: boolean
  allowInterjections?: boolean
  docEditMode: boolean
  historyLimit: number
  maxRounds: number
  createdAt: string
  agents: Agent[]
  questionIds: string[]
  llm?: LlmEndpointView
  /** 仅 docEditMode=true 时透出 */
  userDocument?: UserDocStatus
}

/**
 * 「对话」侧栏用的会话摘要（GET /api/sessions）。
 * 只够渲染列表 + 显示实时状态；议题详细内容仍走 GET /api/sessions/{sid}。
 */
export interface SessionSummary {
  sessionId: string
  /** 首个议题前 24 字；还没提问时为「新对话」 */
  title: string
  createdAt: string
  updatedAt: string
  questionCount: number
  agentCount: number
  /** 最后一个议题的 id（点开会话时默认选中它） */
  latestQuestionId: string | null
  /** 最后一个议题的状态；无议题时为 null */
  status: QuestionStatus | null
  stageHint: string | null
  /** 仍在思考 / 讨论中的议题数（终态之外都算） */
  activeCount: number
  docEditMode: boolean
}

export interface CreateSessionRequest {
  userStyle?: string
  autoDiscuss?: boolean
  docEditMode?: boolean
  historyLimit?: number
  maxRounds?: number
  llmApiKey?: string
  llmBaseUrl?: string
  llmModel?: string
  llmLabel?: string
}

export interface UpdateLlmRequest {
  llmApiKey?: string
  llmBaseUrl?: string
  llmModel?: string
  llmLabel?: string
}

export interface RegisterAgentRequest {
  name: string
  apiKey?: string
  baseUrl?: string
  model?: string
  persona?: string
  /** 性格关键词（可选）：影响该智能体所有发言 */
  personality?: string
  useSessionLlm?: boolean
  /**
   * 从常驻库加入时带上账号级常驻智能体的 uuid，后端据此把跨会话心得挂上这个席位。
   * 普通注册不传 —— 此时后端行为与本改动前完全一致。
   */
  agentUuid?: string
}

export interface QuestionProgress {
  questionId: string
  status: QuestionStatus
  /** 当前阶段的人话描述（后端驱动），驱动顶部状态条提示 */
  stageHint?: string | null
  errorMessage?: string | null
  thoughts: Record<string, ThoughtState>
  skippedAgents: string[]
  transcriptTurns: number
  hasRevisionPlan: boolean
  hasRevisedDocument: boolean
  updatedAt: string
  finalAnswer?: string | null
  explanation?: string | null
  confirmedItemIds?: string[]
  revisedDocument?: RevisedDocument | null
  /** 本议题累计 token 用量（驱动顶部 token 统计 chip） */
  tokenUsage?: TokenUsage
}

export interface AgentThought {
  agentId: string
  agentName: string
  state: ThoughtState
  viewpoint?: string
  selectedEvidence?: string[]
  doubtsOnVision?: string[]
  confidence?: number
  requestVisionRefine?: boolean
  rawText?: string
  errorMessage?: string
  finishedAt?: string
}

export interface Turn {
  speakerId: string
  speakerName: string
  role: string
  phase: TurnPhase
  round: number
  content: string
  at: string
  /** 本次发言的 token 用量（Host 的开场 / 总结等未取到 usage 的发言可能为 null） */
  usage?: TurnUsage | null
  /** 空数组表示已绑定 Skill 中没有与本次任务相关的项。 */
  skillInvocations?: SkillInvocation[]
  skillEvaluated?: boolean
}

export interface Contribution {
  agentId: string
  agentName: string
  point: string
  evidenceRef?: string
}

export interface ProfessionalConsensus {
  summary?: string
  conclusions?: string[]
  corrections?: string[]
  additions?: string[]
  contributions?: Contribution[]
  rawText?: string
}

export interface RevisionPlanItem {
  id: string
  location?: string
  type?: string
  reason?: string
  professionalSuggestion?: string
  styleAlignedSuggestion?: string
  sourceAgents?: string[]
}

export interface RevisionPlan {
  summary?: string
  items?: RevisionPlanItem[]
}

export interface ChangeLogEntry {
  planItemId?: string
  originalSnippet?: string
  newSnippet?: string
  note?: string
}

export interface RevisedDocument {
  revisedFullText?: string
  changeLog?: ChangeLogEntry[]
}

export interface Transcript {
  questionId: string
  status: QuestionStatus
  content: string
  thoughts: Record<string, AgentThought>
  skippedAgents: string[]
  transcript: Turn[]
  professionalConsensus?: ProfessionalConsensus | null
  finalAnswer?: string | null
  explanation?: string | null
  /** 本议题累计 token 用量 */
  tokenUsage?: TokenUsage
}

// ── 归档会话（「结束会话」后落库，只读查看） ──────────────

/** POST /api/sessions/{sid}/archive 的结果 */
export interface ArchiveResult {
  archived: boolean
  /** 仅 archived=true 时有 */
  id?: number
  title?: string
  questionCount?: number
  /** 仅 archived=false 时有：为什么没归档 */
  reason?: string
}

/** 归档列表项（GET /api/archived-sessions） */
export interface ArchivedSummary {
  id: number
  title: string
  questionCount: number
  agentCount: number
  tokenTotal: number
  userStyle?: string | null
  sourceSessionId: string
  archivedAt: string | null
}

/** 归档里的单个议题。Jackson 序列化 Question 的产物，字段比 Transcript 多 id/createdAt 等 */
export interface ArchivedQuestion {
  id: string
  sessionId: string
  content: string
  status: QuestionStatus
  errorMessage?: string | null
  createdAt: string
  updatedAt: string
  thoughts: Record<string, AgentThought>
  skippedAgents: string[]
  transcript: Turn[]
  professionalConsensus?: ProfessionalConsensus | null
  finalAnswer?: string | null
  explanation?: string | null
  stageHint?: string | null
  /**
   * ⚠️ 与 {@link TokenUsage} 不同：这里是后端领域对象 {@code domain.TokenUsage}
   * 的原样序列化（inputTokens/totalTokens/costEstimate），
   * 而 TokenUsage 是 progressView() 加工过的（input/total）。
   */
  tokenUsage?: {
    model?: string | null
    inputTokens?: number
    outputTokens?: number
    totalTokens?: number
    costEstimate?: number
  } | null
  revisedDocument?: { revisedFullText?: string; changeLog?: unknown[] } | null
}

/** 归档快照。⚠️ 后端刻意不含任何 API Key（见 ArchivedSessionService 的脱敏白名单） */
export interface ArchivedPayload {
  session: {
    id: string
    createdAt: string
    userStyle?: string | null
    autoDiscuss: boolean
    docEditMode: boolean
    maxRounds: number
    llm: {
      configured: boolean
      label?: string | null
      baseUrl?: string | null
      model?: string | null
    }
    agents: Array<{
      agentId: string
      name: string
      model?: string | null
      persona?: string | null
      personality?: string | null
      documentFileName?: string | null
      /** 仅导师席位有这三个键（普通席位的快照与本次改动前完全一致） */
      mentor?: boolean
      mentorUuid?: string | null
      mentorDiscipline?: string | null
    }>
  }
  questions: ArchivedQuestion[]
}

export interface ArchivedDetail extends ArchivedSummary {
  payload: ArchivedPayload
}

export interface SummaryResult {
  fileName: string
  scope: string
  questionId: string
  bytes: number
  markdown: string
}

export interface KnowledgeStatusResponse {
  agentId?: string
  knowledgeStatus?: KnowledgeStatus
  status?: KnowledgeStatus
  statusMessage?: string
  documentFileName?: string
  pageCount?: number
  progress?: {
    current: number
    total: number
    ratio: number
  }
  [key: string]: unknown
}

export interface UserDocStatus {
  status: KnowledgeStatus
  statusMessage?: string
  /** 当前阶段的人话描述 */
  stageHint?: string
  fileName?: string | null
  pageCount?: number
  progress?: {
    current: number
    total: number
    ratio: number
  }
  styleProfile?: StyleProfile | null
  [key: string]: unknown
}

export interface StyleProfile {
  tone?: string | null
  person?: string | null
  rhythm?: string | null
  vocabularyLevel?: string | null
  summary?: string | null
  signaturePhrases?: string[] | null
}

export interface ApiError {
  error?: string
  message?: string
  status?: number
}

/** 浏览器本地保存的供应商预设（可多套切换） */
export interface LlmPreset {
  id: string
  label: string
  apiKey: string
  baseUrl: string
  model: string
  updatedAt: number
}

/** 后端账号（由 /api/auth 返回） */
export interface User {
  id?: number
  loginId: string
  displayName: string
  createdAt?: number
}

/** /api/auth/register 与 /api/auth/login 的响应体 */
export interface AuthResponse {
  token: string
  user: User
}

/**
 * /api/agents/persistent 列表项基础形状（id 由后端生成）。
 *
 * <p>`apiKey` 是**掩码**（前 4 + `****` + 后 4，jasypt 密文入库），没有自带网关时为 null；
 * 明文只走 `GET /{id}/reveal`，用完即弃、别塞进列表 state。
 */
export interface PersistentAgentDto {
  id: number
  agentId: string
  name: string
  customName?: string | null
  persona?: string | null
  personality?: string | null
  model?: string | null
  baseUrl?: string | null
  apiKey: string | null
  /** 它自己存了网关没有。有 = 加入时默认用它自己那份 */
  hasOwnGateway?: boolean
  /** 仅 POST 返回：这次有没有真的把席位上的网关存下来；false = 席位已不存在，降级了 */
  gatewaySaved?: boolean
  /** 知识库里攒了几份资料 / 共几页。卡片上的「N 个文件 · M 页」用它，没有知识时为 0。 */
  docCount?: number
  pageCount?: number
  createdAt?: number
  updatedAt?: number
}

/**
 * 常驻知识库里的一份资料（列表视图，**不带摘要正文** —— 一次拉几十篇长文太重）。
 *
 * <p>知识只会在「结束会话时」入库：用户在会话里喂给这个常驻智能体的文件，
 * 结束会话保存为常驻后连同九字段摘要一起落库，之后加入任何会话自动灌回。
 */
export interface PersistentDoc {
  id: number
  fileName: string
  title?: string | null
  authors?: string | null
  pubYear?: string | null
  keywords?: string | null
  pageCount: number
  /** READY / FAILED / PENDING（PENDING 只会出现在进程半途崩掉时） */
  status: string
  errorMessage?: string | null
  createdAt?: number | null
}

/** 单份资料详情：九字段齐全 */
export interface PersistentDocDetail extends PersistentDoc {
  abstractText?: string | null
  researchQuestion?: string | null
  methodology?: string | null
  findings?: string | null
  limitations?: string | null
}

/**
 * 「存知识」那一格的结果。
 *
 * <p>`ok=false` 只表示这个席位不是常驻（没挂 uuid），前端把它标灰；
 * `docCount=0` 且 `ok=true` 是**成功**（这些文件早就存过了，幂等闸摘了个空清单），不是错误。
 * 摘要失败不在这里标红 —— 页已经落库了，那一篇的 `summaryStatus` 会是 FAILED。
 */
export interface SaveKnowledgeResult {
  ok: boolean
  agentUuid?: string | null
  docCount: number
  pageCount: number
  failedCount: number
  docs: Array<{
    docId: number | null
    fileName: string
    summaryStatus: string
    summaryMessage?: string | null
    pageCount: number
  }>
}

/** 改常驻智能体自己的网关。mode=SESSION 表示改回随本场会话网关（清空三件套）。 */
export interface UpdatePersistentGatewayRequest {
  mode: 'OWN' | 'SESSION'
  /** mode=OWN 时：非空 = 覆盖；留空 = 保留原 Key */
  apiKey?: string
  baseUrl?: string
  model?: string
}

export interface LlmPresetDto {
  id: number
  label: string
  apiKey: string
  baseUrl?: string | null
  model?: string | null
  updatedAt?: number
}

// ── 导师模式（账号级导师常驻库） ────────────────────────────

export type MentorStatus = 'WAITING_PAPERS' | 'READY' | 'FAILED'

/** GET /api/mentors 列表项。apiKey 已脱敏（前 4 + **** + 后 4） */
export interface MentorDto {
  id: number
  mentorUuid: string
  name: string
  discipline?: string | null
  focus?: string | null
  personality?: string | null
  model?: string | null
  baseUrl?: string | null
  apiKey: string | null
  status: MentorStatus
  statusMessage?: string | null
  paperCount: number
  pageCount: number
  progressCurrent: number
  progressTotal: number
  createdAt?: number | null
  updatedAt?: number | null
}

/** GET /api/mentors/{id} 详情：比列表多两份画像与 persona */
export interface MentorDetail extends MentorDto {
  styleProfile?: string | null
  knowledgeMap?: string | null
  persona?: string | null
}

/** 论文列表项。刻意不含摘要正文，正文走单篇详情 */
export interface MentorPaper {
  id: number
  fileName: string
  title?: string | null
  authors?: string | null
  pubYear?: string | null
  keywords?: string | null
  pageCount: number
  status: 'READY' | 'FAILED'
  errorMessage?: string | null
  createdAt?: number | null
}

/** 单篇论文详情：九字段齐全 */
export interface MentorPaperDetail extends MentorPaper {
  abstractText?: string | null
  researchQuestion?: string | null
  methodology?: string | null
  findings?: string | null
  limitations?: string | null
}

export interface MentorInput {
  name: string
  discipline?: string
  focus?: string
  personality?: string
  model?: string
  baseUrl?: string
  apiKey?: string
}

export interface MentorStatusView {
  id: number
  status: MentorStatus
  statusMessage?: string | null
  paperCount: number
  pageCount: number
  progressCurrent: number
  progressTotal: number
}

// ── 经验心得（常驻智能体 / 导师的跨会话记忆，账号级） ──────────
//
// 路径里的 {type} 用**后端枚举名原样**，别改成小写别名：后端 MemoryTargetType.parse
// 是严格匹配，拼错了直接 400。

export type MemoryTargetType = 'PERSISTENT_AGENT' | 'MENTOR'

/** 一个「有心得可看」的账号级对象。uuid 分别是 persistent_agent.agent_uuid / mentor.mentor_uuid */
export interface MemoryTarget {
  type: MemoryTargetType
  uuid: string
  /** 展示名（列表里拿的，不由接口回） */
  name: string
}

/** 用户确认过的纠错，被锁成硬前提。拔钉子是唯一的解除方式 */
export interface AgentMemoryPin {
  id: number
  content: string
  createdAt?: number | null
}

/** GET /api/agent-memory/{type}/{uuid} 与 summarize / apply 的返回 */
export interface AgentMemoryView {
  targetType: MemoryTargetType
  targetUuid: string
  /** 还没总结过时为 null —— 这是正常空态，不是错误 */
  content: string | null
  /** 最后一次总结时间（epoch millis） */
  updatedAt?: number | null
  pins: AgentMemoryPin[]
  /** 仅查看接口回：展示名 */
  name?: string
}

/** 纠错对话里 agent 给出的修改方案。用户点「确认」才会落库 */
export interface MemoryProposal {
  /** 心得里的原文片段；模型找不到对应措辞时为空串 */
  before: string | null
  after: string | null
  rationale: string | null
}

/** POST /api/agent-memory/{type}/{uuid}/correct 的返回 */
export interface MemoryCorrectResult {
  reply: string | null
  /** 还在讨论 / 用户在提问时为 null —— 此时不渲染「修改方案」卡片 */
  proposal: MemoryProposal | null
}

/**
 * 纠错对话里的一条消息。**只在内存里**：关掉弹窗就没了，
 * 持久产物只有用户确认过的那条钉子。
 */
export interface MemoryChatMessage {
  role: 'user' | 'agent'
  text: string
  /** 该轮 agent 附带的修改方案；已确认 / 已丢弃后清掉 */
  proposal?: MemoryProposal | null
  /** 该轮的方案是否已确认落库 */
  applied?: boolean
}
