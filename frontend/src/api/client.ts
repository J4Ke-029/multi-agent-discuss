import { clearAuth, loadAuth, loadClientId, type AuthState } from '../lib/storage'
import type {
  Agent,
  AgentMemoryView,
  ApiError,
  ArchiveResult,
  ArchivedDetail,
  ArchivedSummary,
  AuthResponse,
  CreateSessionRequest,
  LlmPresetDto,
  MemoryCorrectResult,
  MemoryTargetType,
  MentorDetail,
  MentorDto,
  MentorInput,
  MentorPaper,
  MentorPaperDetail,
  MentorStatusView,
  PersistentAgentDto,
  PersistentDoc,
  PersistentDocDetail,
  QuestionProgress,
  RegisterAgentRequest,
  SaveKnowledgeResult,
  Session,
  SessionSummary,
  SkillCandidate,
  SkillDetail,
  SkillSummary,
  SkillTargetType,
  SummaryResult,
  Transcript,
  UpdateLlmRequest,
  UpdatePersistentGatewayRequest,
  User,
  UserDocStatus,
} from './types'

const BASE = '/api'

/** 后端因 token 失效 / 缺失返回 401 时触发。AuthDialog 监听后弹登录框。 */
export const AUTH_LOST_EVENT = 'mad:auth:lost'

function emitAuthLost() {
  try {
    window.dispatchEvent(new CustomEvent(AUTH_LOST_EVENT))
  } catch {
    /* ignore */
  }
}

/**
 * 每个请求都带的头：登录 token（有则带）+ 浏览器实例标识。
 * 后者让后端能把匿名会话归属到本浏览器，见 {@link loadClientId}。
 */
function baseHeaders(): Record<string, string> {
  const headers: Record<string, string> = {}
  const a = loadAuth() as AuthState | null
  if (a && a.token) headers['X-Auth-Token'] = a.token
  const clientId = loadClientId()
  if (clientId) headers['X-Client-Id'] = clientId
  return headers
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers)
  for (const [k, v] of Object.entries(baseHeaders())) {
    if (!headers.has(k)) headers.set(k, v)
  }
  const res = await fetch(`${BASE}${path}`, { ...init, headers })
  const text = await res.text()
  let data: unknown = null
  if (text) {
    try {
      data = JSON.parse(text)
    } catch {
      data = { message: text }
    }
  }
  if (res.status === 401) {
    // token 失效或缺失：清掉本地登录态，通知 UI 弹登录框
    clearAuth()
    emitAuthLost()
    const err = (data || {}) as ApiError
    throw new Error(err.message || err.error || '登录已失效，请重新登录')
  }
  if (!res.ok) {
    const err = (data || {}) as ApiError
    throw new Error(err.message || err.error || `请求失败 (${res.status})`)
  }
  return data as T
}

function jsonInit(method: string, body?: unknown): RequestInit {
  return {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  }
}

export const api = {
  // ── 鉴权 ────────────────────────────────────────────────
  register(body: { loginId: string; displayName: string; password: string }) {
    return request<AuthResponse>('/auth/register', jsonInit('POST', body))
  },
  login(body: { loginId: string; password: string }) {
    return request<AuthResponse>('/auth/login', jsonInit('POST', body))
  },
  logout() {
    return request<{ loggedOut: boolean }>('/auth/logout', { method: 'POST' })
  },
  me() {
    return request<{ id?: number; loginId: string; displayName: string; createdAt?: number }>(
      '/auth/me',
    )
  },

  // ── LLM 预设（走 /api/llm-presets） ─────────────────────
  listPresets() {
    return request<LlmPresetDto[]>('/llm-presets')
  },
  /**
   * 取单条预设的**明文** apiKey（仅 owner 可访问）。
   * list 只回掩码 `sk-12****abcd`，所以「选用预设」要用这个把 Key 填回表单。
   */
  revealPreset(id: number) {
    return request<LlmPresetDto>(`/llm-presets/${id}/reveal`)
  },
  createPreset(body: { label: string; apiKey: string; baseUrl?: string; model?: string }) {
    return request<LlmPresetDto>('/llm-presets', jsonInit('POST', body))
  },
  updatePreset(id: number, body: { label?: string; apiKey?: string; baseUrl?: string; model?: string }) {
    return request<LlmPresetDto>(`/llm-presets/${id}`, jsonInit('PUT', body))
  },
  deletePreset(id: number) {
    return request<{ deleted: boolean; id: number }>(`/llm-presets/${id}`, { method: 'DELETE' })
  },

  // ── 常驻智能体（走 /api/agents/persistent） ───────────────
  listPersistentAgents() {
    return request<PersistentAgentDto[]>('/agents/persistent')
  },
  /**
   * 新建常驻智能体。
   *
   * <p>网关不在这里传：给了 `sessionId + agentId`（源会话 + 源席位）时，后端**自己**从那个席位
   * 取它当前生效的网关并加密入库。前端拿到的席位 apiKey 是掩码，传回来只会存进一串星号。
   */
  createPersistentAgent(body: {
    name: string
    customName?: string
    baseUrl?: string
    model?: string
    persona?: string
    personality?: string
    /** 源会话 id：与 agentId 一起给，才会把网关一并存下来 */
    sessionId?: string
    /** 源席位 id */
    agentId?: string
  }) {
    return request<PersistentAgentDto>('/agents/persistent', jsonInit('POST', body))
  },
  updatePersistentAgentCustomName(id: number, customName: string) {
    return request<PersistentAgentDto>(`/agents/persistent/${id}`, jsonInit('PUT', { customName }))
  },
  /**
   * 改它自己的网关。与改名刻意分成两个端点：改名是高频窄操作（点一下名字就提交），
   * 混进凭据字段会让「空 = 不改」和「空 = 清空」永远歧义。
   */
  updatePersistentAgentGateway(id: number, body: UpdatePersistentGatewayRequest) {
    return request<PersistentAgentDto>(`/agents/persistent/${id}/gateway`, jsonInit('PUT', body))
  },
  /** **明文** apiKey，仅本人。供「编辑网关」弹窗回填；列表接口回的是掩码。 */
  revealPersistentAgent(id: number) {
    return request<PersistentAgentDto>(`/agents/persistent/${id}/reveal`)
  },
  deletePersistentAgent(id: number) {
    return request<{ deleted: boolean; id: number }>(`/agents/persistent/${id}`, { method: 'DELETE' })
  },

  // ── 常驻知识库（它读过的资料，跨会话累积、跟随账号） ──────────
  //
  // ⚠️ 下面几个的路径参数一律是 **agentUuid**（那个 CHAR(36) 的稳定标识），
  // 不是上面那几个用 **DB 主键** 的 `{id}` —— 这两种 id 在这个项目里已经弄混过一次，
  // 传错不会报错，只会静静地打到另一个对象上。

  /**
   * 结束会话时，把这个席位本场读过、还没入库的文件存进它的常驻知识库。
   *
   * <p>空 body：存给哪个常驻由后端从席位的 `agentUuid` 推导，前端传不了也传不错。
   * **幂等** —— 清单取走即清空，取消后重来 / 失败重试都只会存一次；
   * 没有新文件时返回 `docCount=0` 且 `ok=true`（成功，不是错误）。
   */
  saveSeatKnowledge(sid: string, aid: string) {
    return request<SaveKnowledgeResult>(`/sessions/${sid}/agents/${aid}/knowledge/save`, {
      method: 'POST',
    })
  },

  /** 某个常驻智能体攒下的资料列表（轻，不带摘要正文） */
  listPersistentDocs(uuid: string) {
    return request<PersistentDoc[]>(`/agents/persistent/${encodeURIComponent(uuid)}/docs`)
  },

  getPersistentDoc(uuid: string, docId: number) {
    return request<PersistentDocDetail>(
      `/agents/persistent/${encodeURIComponent(uuid)}/docs/${docId}`,
    )
  },

  /**
   * 重跑某一篇的摘要。页知识早就在库里了（检索照常可用），失败的只是摘要这一步，
   * 所以重跑**不会产生第二条记录**。摘要再失败时返回的 status 仍是 FAILED、HTTP 仍是 200。
   *
   * @param sessionId 可选：这个常驻自己没有网关时借该会话的网关跑
   */
  summarizePersistentDoc(docId: number, sessionId?: string | null) {
    return request<PersistentDocDetail>(`/agents/persistent/docs/${docId}/summarize`,
      jsonInit('POST', { sessionId: sessionId ?? null }))
  },

  /** 从知识库删掉一份资料（页知识一并删除，**不可恢复**：原始文件不入库，重建不了） */
  deletePersistentDoc(uuid: string, docId: number) {
    return request<{ deleted: boolean; docId: number }>(
      `/agents/persistent/${encodeURIComponent(uuid)}/docs/${docId}`,
      { method: 'DELETE' },
    )
  },

  // ── 会话 / 议题 ─────────────────────────────────────────
  /** 当前用户的会话列表（「对话」侧栏；高频轮询，只返回摘要） */
  listSessions() {
    return request<SessionSummary[]>('/sessions')
  },

  createSession(body: CreateSessionRequest = {}) {
    return request<Session>('/sessions', jsonInit('POST', body))
  },

  getSession(sid: string) {
    return request<Session>(`/sessions/${sid}`)
  },

  updateLlm(sid: string, body: UpdateLlmRequest) {
    return request<Session>(`/sessions/${sid}/llm`, jsonInit('PUT', body))
  },

  /** 改会话配置（文风 / 最高讨论轮次）。改稿模式建会话时定，不可改。 */
  updateSessionSettings(sid: string, body: { userStyle?: string; maxRounds?: number }) {
    return request<Session>(`/sessions/${sid}/settings`, jsonInit('PUT', body))
  },
  updateInterjectionSetting(sid: string, allowInterjections: boolean) {
    return request<Session>(`/sessions/${sid}/interjections`, jsonInit('PUT', { allowInterjections }))
  },

  /** 结束会话 = 归档进库 + 销毁内存会话。没有议题 / 未登录时只销毁不归档。 */
  archiveSession(sid: string) {
    return request<ArchiveResult>(`/sessions/${sid}/archive`, { method: 'POST' })
  },

  // ── 已归档会话（只读） ───────────────────────────────────
  listArchived() {
    return request<ArchivedSummary[]>('/archived-sessions')
  },
  getArchived(id: number) {
    return request<ArchivedDetail>(`/archived-sessions/${id}`)
  },
  deleteArchived(id: number) {
    return request<{ deleted: boolean; id: number }>(`/archived-sessions/${id}`, {
      method: 'DELETE',
    })
  },
  /**
   * 用归档快照生成总结。后端走服务端默认网关（快照里不存 Key），
   * 不是原会话的第三方网关。
   */
  summarizeArchived(
    id: number,
    body: { scope?: string; questionId?: string } = {},
  ) {
    return request<SummaryResult>(`/archived-sessions/${id}/summary`, jsonInit('POST', body))
  },

  registerAgent(sid: string, body: RegisterAgentRequest) {
    return request<Agent>(`/sessions/${sid}/agents`, jsonInit('POST', body))
  },

  /**
   * 把「本次新保存为常驻」的智能体与它的席位对上号。
   *
   * <p>只在「结束会话」链路里调，用于堵续跑洞：用户可以在总结弹窗点取消（会话保留），
   * 而「哪个席位对应刚保存的哪个常驻对象」原本只活在前端 state 里，第二次点「结束会话」
   * 就丢了，那几个席位便永远总结不了。落回席位之后，「待总结目标」恒等于「所有挂着 uuid 的席位」。
   */
  linkPersistentSeat(sid: string, aid: string, agentUuid: string) {
    return request<Agent>(`/sessions/${sid}/agents/${aid}/persistent-link`, jsonInit('PUT', { agentUuid }))
  },

  /** 让某个席位回顾本场讨论，重写它自己的经验心得（阻塞式，一席位一次请求，前端串行调用） */
  summarizeAgentMemory(sid: string, aid: string) {
    return request<AgentMemoryView>(`/sessions/${sid}/agents/${aid}/memory/summarize`, {
      method: 'POST',
    })
  },

  // ── 经验心得（账号级，按 owner_user_id 隔离） ─────────────
  // ⚠️ uuid 必须是本账号名下真实存在的常驻智能体 / 导师：后端拿不到就直接 AuthException
  // → 401 → 前端会清登录态并弹登录框。所以调用方务必先在本地列表里核对过再进来。

  /** 查看某对象的经验心得（只读）。没总结过时 content 为 null、pins 为空数组。 */
  getAgentMemory(type: MemoryTargetType, uuid: string) {
    return request<AgentMemoryView>(`/agent-memory/${type}/${encodeURIComponent(uuid)}`)
  },

  /**
   * 纠错对话的一轮。刻意不落库：关掉弹窗这段对话就没了，持久产物是用户确认过的那条钉子。
   *
   * @param body.sessionId 强烈建议带上 —— 普通常驻智能体没有自己的 API Key，
   *                       不带就只能落到服务端默认网关（mock 下是 mock，线上是真网络）。
   */
  correctAgentMemory(
    type: MemoryTargetType,
    uuid: string,
    body: { message: string; sessionId?: string; history?: Array<{ role: string; text: string }> },
  ) {
    return request<MemoryCorrectResult>(
      `/agent-memory/${type}/${encodeURIComponent(uuid)}/correct`,
      jsonInit('POST', body),
    )
  },

  /** 应用一张修改方案卡片 —— **用户点了「确认」才会走到这里**。返回刷新后的心得全文 */
  applyMemoryProposal(
    type: MemoryTargetType,
    uuid: string,
    body: { before?: string | null; after: string; rationale?: string | null },
  ) {
    return request<AgentMemoryView>(
      `/agent-memory/${type}/${encodeURIComponent(uuid)}/correct/apply`,
      jsonInit('POST', body),
    )
  },

  /** 拔钉子：解除后它不再进硬前提段，但用户确认过的历史仍留在心得正文里 */
  deleteMemoryPin(pinId: number) {
    return request<{ deleted: boolean; id: number }>(`/agent-memory/pins/${pinId}`, {
      method: 'DELETE',
    })
  },

  // ── 导师模式（账号级导师常驻库） ──────────────────────────
  // 导师跨会话复用：论文只传一次，加入任意会话时由服务端把页知识灌进该会话。

  listMentors() {
    return request<MentorDto[]>('/mentors')
  },

  getMentor(id: number) {
    return request<MentorDetail>(`/mentors/${id}`)
  },

  /** 明文 apiKey，仅本人。列表接口返回的是脱敏值。 */
  revealMentor(id: number) {
    return request<MentorDetail>(`/mentors/${id}/reveal`)
  },

  createMentor(body: MentorInput) {
    return request<MentorDetail>('/mentors', jsonInit('POST', body))
  },

  updateMentor(id: number, body: Partial<MentorInput>) {
    return request<MentorDetail>(`/mentors/${id}`, jsonInit('PUT', body))
  },

  /** 删除导师（连带其全部论文与页知识） */
  deleteMentor(id: number) {
    return request<{ deleted: boolean; id: number }>(`/mentors/${id}`, { method: 'DELETE' })
  },

  mentorStatus(id: number) {
    return request<MentorStatusView>(`/mentors/${id}/status`)
  },

  /**
   * 上传**一篇**导师论文（后端逐篇落库，前端逐篇串行调用）。
   * sessionId 用于「导师没配自己的网关时退而用该会话的网关」。
   */
  uploadMentorPaper(id: number, file: File, sessionId?: string) {
    const form = new FormData()
    form.append('file', file)
    const query = sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : ''
    return request<MentorPaper>(`/mentors/${id}/papers${query}`, { method: 'POST', body: form })
  },

  listMentorPapers(id: number) {
    return request<{ papers: MentorPaper[] }>(`/mentors/${id}/papers`)
  },

  getMentorPaper(id: number, pid: number) {
    return request<MentorPaperDetail>(`/mentors/${id}/papers/${pid}`)
  },

  deleteMentorPaper(id: number, pid: number) {
    return request<{ deleted: boolean; id: number }>(`/mentors/${id}/papers/${pid}`, { method: 'DELETE' })
  },

  /** 把账号级导师加入本会话，返回新建的席位 agentId */
  joinMentor(sid: string, mentorId: number) {
    return request<{
      agentId: string
      mentorId: number
      mentorUuid: string
      name: string
      paperCount: number
      pageCount: number
      knowledgeStatus: string
    }>(`/sessions/${sid}/mentors`, jsonInit('POST', { mentorId }))
  },

  /** 从本场会话移除一个领域智能体（连带清掉它的文献知识） */
  removeAgent(sid: string, aid: string) {
    return request<{ removed: boolean; sessionId: string; agentId: string }>(
      `/sessions/${sid}/agents/${aid}`,
      { method: 'DELETE' },
    )
  },

  uploadAgentDocument(sid: string, aid: string, file: File) {
    const form = new FormData()
    form.append('file', file)
    return request<Record<string, unknown>>(
      `/sessions/${sid}/agents/${aid}/documents`,
      { method: 'POST', body: form },
    )
  },

  uploadUserDocument(sid: string, file: File) {
    const form = new FormData()
    form.append('file', file)
    return request<Record<string, unknown>>(
      `/sessions/${sid}/user-document`,
      { method: 'POST', body: form },
    )
  },

  userDocumentStatus(sid: string) {
    return request<UserDocStatus>(`/sessions/${sid}/user-document/status`)
  },

  askQuestion(sid: string, content: string) {
    return request<QuestionProgress>(
      `/sessions/${sid}/questions`,
      jsonInit('POST', { content }),
    )
  },

  questionStatus(sid: string, qid: string) {
    return request<QuestionProgress>(`/sessions/${sid}/questions/${qid}`)
  },

  startDiscuss(sid: string, qid: string) {
    return request<QuestionProgress>(
      `/sessions/${sid}/questions/${qid}/discuss`,
      { method: 'POST' },
    )
  },
  addInterjection(sid: string, qid: string, content: string, targetAgentId?: string) {
    return request<QuestionProgress>(`/sessions/${sid}/questions/${qid}/interjections`,
      jsonInit('POST', { content, targetAgentId }))
  },
  continueDiscussion(sid: string, qid: string) {
    return request<QuestionProgress>(`/sessions/${sid}/questions/${qid}/continue`, { method: 'POST' })
  },

  transcript(sid: string, qid: string) {
    return request<Transcript>(`/sessions/${sid}/questions/${qid}/transcript`)
  },

  revisionPlan(sid: string, qid: string) {
    return request<{ status: string; revisionPlan: unknown }>(
      `/sessions/${sid}/questions/${qid}/revision-plan`,
    )
  },

  confirmRevision(
    sid: string,
    qid: string,
    body: {
      acceptedItemIds: string[]
      note?: string
      autoRevise?: boolean
    },
  ) {
    return request<QuestionProgress>(
      `/sessions/${sid}/questions/${qid}/revision-confirm`,
      jsonInit('POST', body),
    )
  },

  /**
   * 继续改稿：在当前改写结果上，按用户临时提的要求再改一轮。
   * 与 revise 不同，不需要已确认的修改项。
   */
  continueRevision(sid: string, qid: string, requirement: string) {
    return request<QuestionProgress>(
      `/sessions/${sid}/questions/${qid}/continue-revision`,
      jsonInit('POST', { requirement }),
    )
  },

  revisedDocument(sid: string, qid: string) {
    return request<{ status: string; revisedDocument: unknown }>(
      `/sessions/${sid}/questions/${qid}/revised-document`,
    )
  },

  /** 生成总结。scope: CURRENT_QUESTION（单个议题）/ ALL_QUESTIONS（整个会话）。 */
  generateSummary(
    sid: string,
    qid: string,
    body?: { scope?: string; questionId?: string },
  ) {
    return request<SummaryResult>(
      `/sessions/${sid}/questions/${qid}/summary`,
      jsonInit('POST', body || {}),
    )
  },

  // ── 账号级 Skill 库 ─────────────────────────────────────
  listSkills() {
    return request<SkillSummary[]>('/skills')
  },
  getSkill(uuid: string) {
    return request<SkillDetail>(`/skills/${encodeURIComponent(uuid)}`)
  },
  createSkill(body: { skillMd: string; conflictAction?: string; renameTo?: string }) {
    return request<SkillSummary>('/skills/web', jsonInit('POST', body))
  },
  updateSkill(uuid: string, skillMd: string) {
    return request<SkillSummary>(`/skills/${encodeURIComponent(uuid)}`, jsonInit('PUT', { skillMd }))
  },
  skillDeleteImpact(uuid: string) {
    return request<{ bindingCount: number; agents: Array<{ targetType: SkillTargetType; targetUuid: string; name: string }> }>(`/skills/${encodeURIComponent(uuid)}/delete-impact`)
  },
  deleteSkill(uuid: string) {
    return request<{ deleted: boolean; skillUuid: string }>(`/skills/${encodeURIComponent(uuid)}`, { method: 'DELETE' })
  },
  scanSkillZip(file: File) {
    const body = new FormData()
    body.append('file', file)
    return request<SkillCandidate[]>('/skills/zip/scan', { method: 'POST', body })
  },
  importSkillZip(file: File, selectedPaths: string[], conflictAction: string, renames: Record<string, string>) {
    const body = new FormData()
    body.append('file', file)
    for (const path of selectedPaths) body.append('selectedPaths', path)
    body.append('conflictAction', conflictAction)
    body.append('renames', JSON.stringify(renames))
    return request<SkillSummary[]>('/skills/zip/import', { method: 'POST', body })
  },
  scanGitHubSkills(url: string) {
    return request<{ url: string; ref: string; skills: SkillCandidate[] }>(
      '/skills/github/scan', jsonInit('POST', { url }),
    )
  },
  importGitHubSkills(url: string, selectedPaths: string[], conflictAction: string, renames: Record<string, string>) {
    return request<SkillSummary[]>('/skills/github/import', jsonInit('POST', {
      url, selectedPaths, conflictAction, renames,
    }))
  },
  getSkillBindings(targetType: SkillTargetType, targetUuid: string, sessionId?: string) {
    const q = new URLSearchParams({ targetType, targetUuid })
    if (sessionId) q.set('sessionId', sessionId)
    return request<Array<Pick<SkillSummary, 'skillUuid' | 'name' | 'description'>>>(`/skills/bindings?${q}`)
  },
  getSkillBindingOverview() {
    return request<import('./types').SkillBindingOverview[]>('/skills/bindings/overview')
  },
  replaceSkillBindings(body: {
    targetType: SkillTargetType
    targetUuid: string
    sessionId?: string
    skillUuids: string[]
  }) {
    return request<Array<Pick<SkillSummary, 'skillUuid' | 'name' | 'description'>>>(
      '/skills/bindings', jsonInit('PUT', body),
    )
  },
}

export type { SummaryResult }
export type { User }
