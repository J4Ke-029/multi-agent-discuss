import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { api, AUTH_LOST_EVENT } from './api/client'
import type {
  Agent,
  AgentMemoryPin,
  ArchivedDetail,
  ArchivedSummary,
  MemoryTarget,
  MemoryTargetType,
  MentorDto,
  QuestionProgress,
  RevisedDocument,
  RevisionPlan,
  Session,
  SessionSummary,
  SummaryResult,
  Transcript,
  User,
  UserDocStatus,
} from './api/types'
import { AddAgentDialog } from './components/AddAgentDialog'
import { AgentMemoryDialog } from './components/AgentMemoryDialog'
import { AgentPanel } from './components/AgentPanel'
import { ArchivedStage } from './components/ArchivedStage'
import { AuthDialog } from './components/AuthDialog'
import { ConfirmDialog } from './components/ConfirmDialog'
import { ConversationRail } from './components/ConversationRail'
import { DiscussionStage } from './components/DiscussionStage'
import { MemoryCorrectDialog } from './components/MemoryCorrectDialog'
import { PersistentGatewayDialog } from './components/PersistentGatewayDialog'
import { PersistentDocsDialog } from './components/PersistentDocsDialog'
import { MemorySyncDialog, type MemorySyncItem } from './components/MemorySyncDialog'
import { MentorPapersDialog } from './components/MentorPapersDialog'
import type { MentorFormInput } from './components/MentorForm'
import { NewConversationDialog } from './components/NewConversationDialog'
import { Modal, ModalTitle } from './components/Modal'
import { SessionChip } from './components/SessionChip'
import { WelcomePage } from './components/WelcomePage'
import { ToastStack, type ToastItem } from './components/Toast'
import { UserMenu } from './components/UserMenu'
import { AgentLibraryPage } from './components/AgentLibraryPage'
import { usePolling } from './hooks/usePolling'
import { useMentors } from './hooks/useMentors'
import { usePersistentAgents, type PersistentAgentView } from './hooks/usePersistentAgents'
import { agentColorIndex, isQuestionActive } from './lib/labels'
import {
  clearAuth,
  clearSessionId,
  isDebugMode,
  loadAuth,
  loadSessionId,
  saveAuth,
  saveSessionId,
} from './lib/storage'
import { appPaths, parseAppRoute } from './router/paths'

function BrandMark() {
  return (
    <svg viewBox="0 0 32 32" fill="none" aria-hidden>
      <circle cx="10" cy="14" r="3" fill="#D4A84B" />
      <circle cx="22" cy="14" r="3" fill="#7B8CDE" />
      <circle cx="16" cy="22" r="3" fill="#3DBE9A" />
      <path
        d="M10 14h6M22 14h-6M16 14v8"
        stroke="#F4EFE6"
        strokeWidth="1.2"
        opacity="0.45"
      />
    </svg>
  )
}

export default function App() {
  const location = useLocation()
  const navigate = useNavigate()
  const route = useMemo(() => parseAppRoute(location.pathname), [location.pathname])
  const [session, setSession] = useState<Session | null>(null)
  const [booting, setBooting] = useState(true)
  const [busy, setBusy] = useState(false)
  const [toasts, setToasts] = useState<ToastItem[]>([])
  const [routeError, setRouteError] = useState<string | null>(null)
  const [routeReload, setRouteReload] = useState(0)

  // 调试模式（?debug=1）：只影响内部标识是否露出来，启动时读一次即可
  const [debugMode] = useState(isDebugMode)

  // 「添加智能体」弹窗（注册新智能体 / 使用常驻智能体）
  const [showAddAgentDialog, setShowAddAgentDialog] = useState(false)

  // 「开新对话」弹窗：选圆桌会议 / 改稿模式（原 Landing 页的建会话选项）
  const [showNewConversation, setShowNewConversation] = useState(false)

  // 当前登录用户（来自后端 /api/auth）
  const [currentUser, setCurrentUser] = useState<User | null>(null)
  const [showAuthDialog, setShowAuthDialog] = useState(false)
  // 401 触发自动弹登录框（不阻塞用户主动登录的关闭）
  const [authDialogAutoOpened, setAuthDialogAutoOpened] = useState(false)

  // 启动时：先校验 token；通过后再恢复会话
  useEffect(() => {
    void (async () => {
      const cached = loadAuth()
      if (cached) {
        try {
          const me = await api.me()
          setCurrentUser(me)
          // token 仍有效；用最新 user 信息更新本地缓存
          saveAuth({ token: cached.token, user: me })
        } catch {
          clearAuth()
          setCurrentUser(null)
        }
      }
      setBooting(false)
    })()
  }, [])

  // 监听 401 → 清登录态 + 弹登录框
  useEffect(() => {
    const onAuthLost = () => {
      setCurrentUser(null)
      setShowAuthDialog(true)
      setAuthDialogAutoOpened(true)
    }
    window.addEventListener(AUTH_LOST_EVENT, onAuthLost)
    return () => window.removeEventListener(AUTH_LOST_EVENT, onAuthLost)
  }, [])

  // 常驻智能体状态
  const {
    persistentAgents,
    addPersistentAgent,
    removePersistentAgent,
    updatePersistentAgentName,
    updatePersistentAgentGateway,
    refresh: refreshPersistentAgents,
    clearPersistentAgents,
  } = usePersistentAgents()

  // 登录态变化时刷新常驻列表（不同账号看到的是各自的）
  useEffect(() => {
    if (currentUser) void refreshPersistentAgents()
  }, [currentUser, refreshPersistentAgents])

  // 导师常驻库（账号级）。与常驻智能体一样按登录态刷新
  const {
    mentors,
    activeProgress: mentorProgress,
    createMentor,
    removeMentor,
    uploadPapers: uploadMentorPapers,
    refresh: refreshMentors,
    clearMentors,
  } = useMentors()

  useEffect(() => {
    if (currentUser) void refreshMentors()
  }, [currentUser, refreshMentors])

  /** 正在查看论文摘要的导师（null = 无弹窗） */
  const [mentorPapersOf, setMentorPapersOf] = useState<MentorDto | null>(null)
  /** 待确认删除的导师 */
  const [pendingDeleteMentor, setPendingDeleteMentor] = useState<MentorDto | null>(null)

  /** 会话里的导师席位 → 账号级导师记录（靠 uuid 对上；导师被删掉时返回 undefined） */
  const mentorOfAgent = useCallback(
    (agent: Agent) => mentors.find((m) => m.mentorUuid === agent.mentorUuid),
    [mentors],
  )

  // 结束对话 → 第一步：保存智能体
  const [showSaveDialog, setShowSaveDialog] = useState(false)
  const [saveSelection, setSaveSelection] = useState<Set<string>>(new Set())
  const [saveNote, setSaveNote] = useState('')

  // 结束对话 → 第二步：保存讨论总结（在智能体步骤完成后再弹出）
  const [showSummaryDialog, setShowSummaryDialog] = useState(false)
  const [summarySaveCurrent, setSummarySaveCurrent] = useState(true)
  const [summaryNote, setSummaryNote] = useState('')
  const [summaryScope, setSummaryScope] = useState<
    'CURRENT_QUESTION' | 'ALL_QUESTIONS'
  >('ALL_QUESTIONS')

  // 结束对话 → 中间夹的一步：先存知识（本场读过的文件入库），再让在场的常驻智能体 / 导师总结本场经验
  const [showMemorySync, setShowMemorySync] = useState(false)
  const [memorySyncItems, setMemorySyncItems] = useState<MemorySyncItem[]>([])
  const [memorySyncRunning, setMemorySyncRunning] = useState(false)
  /** 进度弹窗当前跑的是哪一轮。两轮共用同一个弹窗、同一份 items，只有顶部文案与计数基数不同。 */
  const [memorySyncPhase, setMemorySyncPhase] = useState<'docs' | 'memory'>('docs')

  /** 正在只读查看的经验心得（null = 无弹窗）。两个入口共用 */
  const [memoryOf, setMemoryOf] = useState<MemoryTarget | null>(null)
  /** 正在纠错的常驻对象（null = 无弹窗）。二级弹窗，必须排在只读弹窗之后 */
  const [correcting, setCorrecting] = useState<MemoryTarget | null>(null)
  /** 纠错确认后 +1：让底层只读弹窗重拉一次 */
  const [memoryRefreshToken, setMemoryRefreshToken] = useState(0)
  /** 待确认解除的锁定项 */
  const [pendingUnpin, setPendingUnpin] = useState<AgentMemoryPin | null>(null)
  const [unpinning, setUnpinning] = useState(false)

  /** 正在改网关的常驻智能体（null = 无弹窗）。从常驻库卡片上的「网关」点开 */
  const [gatewayOf, setGatewayOf] = useState<PersistentAgentView | null>(null)

  /** 正在查看知识库的常驻智能体（null = 无弹窗）。从常驻库卡片上的「知识」点开 */
  const [docsOf, setDocsOf] = useState<PersistentAgentView | null>(null)
  /**
   * 待确认删除的常驻智能体（null = 无弹窗）。
   *
   * <p>删它会**连带删掉它攒下的知识与经验**，而原始文件当初没有入库 ——
   * 知识一旦删掉就重建不了。所以那个 × 必须先过一道确认，不能像改动前那样直接删。
   */
  const [pendingDeletePersistent, setPendingDeletePersistent] = useState<PersistentAgentView | null>(null)

  const routeSessionId = route.kind === 'session' ? route.sessionId : null
  const routeArchiveId = route.kind === 'archive' ? route.archiveId : null
  const activeQid = route.kind === 'session' ? route.questionId ?? null : null
  const [progress, setProgress] = useState<QuestionProgress | null>(null)
  const [transcript, setTranscript] = useState<Transcript | null>(null)
  const [revisionPlan, setRevisionPlan] = useState<RevisionPlan | null>(null)
  const [revisedDocument, setRevisedDocument] = useState<RevisedDocument | null>(null)
  const [questionPreviews, setQuestionPreviews] = useState<Record<string, string>>({})
  const [progressMap, setProgressMap] = useState<Record<string, QuestionProgress>>({})
  /** 改稿模式下的用户文档视图：来源 = session.userDocument（sessionView 透出）或 /user-document/status 轮询 */
  const [userDoc, setUserDoc] = useState<UserDocStatus | null>(null)

  /** 「对话」侧栏的会话列表（GET /api/sessions）。轮询刷新，用于显示各对话的实时状态。 */
  const [sessionList, setSessionList] = useState<SessionSummary[]>([])

  /**
   * 身份护栏：记录「当前正在展示的会话 + 议题」，供所有异步写状态前校验。
   * 防止结束会话 / 新建会话 / 快速切题后，在途的迟到响应把旧状态写回 UI（串台）。
   */
  const latestRef = useRef<{ sid: string | null; qid: string | null }>({ sid: null, qid: null })
  const renderedSessionIdRef = useRef<string | null>(null)
  renderedSessionIdRef.current = session?.sessionId ?? null
  const stillCurrent = (sid: string | null, qid?: string | null) => {
    const cur = latestRef.current
    if (qid !== undefined) return cur.sid === sid && cur.qid === qid
    return cur.sid === sid
  }
  const setLatest = (sid: string | null, qid: string | null) => {
    latestRef.current = { sid, qid }
  }
  // 会话或议题变化时同步身份 ref
  useEffect(() => {
    setLatest(session?.sessionId ?? null, activeQid)
  }, [session?.sessionId, activeQid])

  // 地址栏切换议题后先清空上一题的派生数据，避免新请求返回前短暂展示旧答案。
  useEffect(() => {
    setProgress(null)
    setTranscript(null)
    setRevisionPlan(null)
    setRevisedDocument(null)
  }, [routeSessionId, activeQid])

  const toast = useCallback((message: string, kind: ToastItem['kind'] = 'info') => {
    const id = `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
    setToasts((prev) => [...prev, { id, message, kind }])
  }, [])

  const dismissToast = useCallback((id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id))
  }, [])

  const refreshSession = useCallback(async (sid: string) => {
    const s = await api.getSession(sid)
    // 若期间已切走（结束会话/返回首页/新开），不把旧会话写回
    if (!stillCurrent(sid)) return s
    setSession(s)
    if (s.docEditMode) {
      setUserDoc(s.userDocument || null)
    }
    return s
  }, [])

  /**
   * 拉「对话」列表。刻意不弹 toast：这是 2.5s 一次的高频轮询，
   * 偶发失败只记 console，否则网络抖一下会刷屏。
   */
  const refreshSessionList = useCallback(async () => {
    try {
      setSessionList(await api.listSessions())
    } catch (e) {
      console.warn('刷新对话列表失败', e)
    }
  }, [])

  /**
   * 切换到另一个对话。
   *
   * <p>只换前端视图状态——服务端的独立思考是丢进 thinkExecutor 后台跑的，
   * 跟这个请求无关，所以切走不会打断它；切回来轮询会读到最新进度。
   */
  const switchSession = async (sid: string) => {
    if (session && sid === session.sessionId) {
      // 点的就是当前对话：当作一次手动刷新，不重置视图（否则正在看的议题会被踢到最后一条）。
      // 但要先退出归档查看态 —— 否则从「已归档」点回来时中间舞台还是那份只读快照，看着像没反应。
      void refreshSession(sid)
      navigate(activeQid ? appPaths.question(sid, activeQid) : appPaths.session(sid))
      return
    }
    navigate(appPaths.session(sid))
  }

  /** 「已归档」列表（GET /api/archived-sessions） */
  const [archivedList, setArchivedList] = useState<ArchivedSummary[]>([])
  /** 正在只读查看的归档快照；非 null 时中间舞台换成 ArchivedStage */
  const [viewingArchived, setViewingArchived] = useState<ArchivedDetail | null>(null)
  /** 待确认删除的归档（删除不可恢复，走 ConfirmDialog） */
  const [pendingDeleteArchived, setPendingDeleteArchived] = useState<ArchivedSummary | null>(null)

  /** 拉归档列表。同 refreshSessionList：高频轮询，失败只记 console。 */
  const refreshArchivedList = useCallback(async () => {
    try {
      setArchivedList(await api.listArchived())
    } catch (e) {
      console.warn('刷新归档列表失败', e)
    }
  }, [])

  /** 打开一条归档 → 中间舞台只读渲染。同一时刻只能看一个，所以先退出进行中的查看态。 */
  const openArchived = async (id: number) => {
    navigate(appPaths.archive(id))
  }

  const closeArchived = () => {
    navigate(session ? appPaths.session(session.sessionId) : appPaths.workbench())
  }

  /** 删除一条归档（不可恢复，先过确认弹窗） */
  const deleteArchived = async (id: number) => {
    setBusy(true)
    try {
      await api.deleteArchived(id)
      if (viewingArchived?.id === id) setViewingArchived(null)
      if (route.kind === 'archive' && route.archiveId === id) navigate(appPaths.workbench(), { replace: true })
      toast('归档已删除', 'ok')
      void refreshArchivedList()
    } catch (e) {
      toast(e instanceof Error ? e.message : '删除归档失败', 'error')
    } finally {
      setBusy(false)
    }
  }

  // URL 是当前资源的唯一来源：直接打开、刷新、前进后退都走同一条加载路径。
  useEffect(() => {
    if (booting || !routeSessionId) return
    let active = true
    const requestedSid = routeSessionId
    setRouteError(null)
    setBusy(true)
    setLatest(requestedSid, latestRef.current.qid)
    if (renderedSessionIdRef.current !== requestedSid) {
      setSession(null)
      setProgress(null)
      setTranscript(null)
      setRevisionPlan(null)
      setRevisedDocument(null)
      setQuestionPreviews({})
      setProgressMap({})
      setMemoryOf(null)
      setCorrecting(null)
      setPendingUnpin(null)
    }
    void api.getSession(requestedSid).then((loaded) => {
      if (!active) return
      saveSessionId(requestedSid)
      setSession(loaded)
      setUserDoc(loaded.docEditMode ? loaded.userDocument || null : null)
      setViewingArchived(null)
      void refreshSessionList()
    }).catch((cause: unknown) => {
      if (!active) return
      setSession(null)
      setRouteError(cause instanceof Error ? cause.message : '会话加载失败')
    }).finally(() => {
      if (active) setBusy(false)
    })
    return () => { active = false }
    // routeReload 是错误页的显式重试开关；切议题不重拉整个会话。
  }, [booting, routeSessionId, routeReload, refreshSessionList])

  useEffect(() => {
    if (!session || route.kind !== 'session' || session.sessionId !== route.sessionId) return
    if (!route.questionId) {
      const latest = session.questionIds[session.questionIds.length - 1]
      if (latest) navigate(appPaths.question(session.sessionId, latest), { replace: true })
      return
    }
    if (!session.questionIds.includes(route.questionId)) {
      const latest = session.questionIds[session.questionIds.length - 1]
      toast('该议题不存在或不属于当前会话', 'error')
      navigate(latest ? appPaths.question(session.sessionId, latest) : appPaths.session(session.sessionId),
        { replace: true })
    }
  }, [session, route, navigate, toast])

  useEffect(() => {
    if (booting || routeArchiveId == null) return
    let active = true
    setRouteError(null)
    setBusy(true)
    void api.getArchived(routeArchiveId).then((detail) => {
      if (active) setViewingArchived(detail)
    }).catch((cause: unknown) => {
      if (!active) return
      setViewingArchived(null)
      setRouteError(cause instanceof Error ? cause.message : '归档加载失败')
    }).finally(() => {
      if (active) setBusy(false)
    })
    return () => { active = false }
  }, [booting, routeArchiveId, routeReload])

  useEffect(() => {
    if (route.kind !== 'archive') setViewingArchived(null)
    if (route.kind !== 'session' && route.kind !== 'archive') {
      setRouteError(null)
      setBusy(false)
    }
    if (!booting && route.kind === 'library' && !currentUser) {
      setAuthDialogAutoOpened(true)
      setShowAuthDialog(true)
    }
  }, [booting, route.kind, currentUser])

  /**
   * 用归档快照生成议题总结。
   *
   * <p>快照里不含 API Key，所以后端走的是**服务端默认网关**，不是原会话的第三方网关。
   */
  const generateArchivedSummary = async (
    archiveId: number,
    qid: string,
    scope: 'CURRENT_QUESTION' | 'ALL_QUESTIONS',
  ): Promise<SummaryResult | null> => {
    setBusy(true)
    try {
      return await api.summarizeArchived(archiveId, { scope, questionId: qid })
    } catch (e) {
      toast(e instanceof Error ? e.message : '生成总结失败', 'error')
      return null
    } finally {
      setBusy(false)
    }
  }

  const createSession = async (opts: {
    userStyle: string
    autoDiscuss: boolean
    docEditMode: boolean
    maxRounds: number
    llmApiKey: string
    llmBaseUrl: string
    llmModel: string
    llmLabel: string
  }): Promise<boolean> => {
    setBusy(true)
    try {
      const s = await api.createSession({
        userStyle: opts.userStyle,
        autoDiscuss: opts.autoDiscuss,
        docEditMode: opts.docEditMode,
        maxRounds: opts.maxRounds,
        llmApiKey: opts.llmApiKey,
        llmBaseUrl: opts.llmBaseUrl || undefined,
        llmModel: opts.llmModel || undefined,
        llmLabel: opts.llmLabel || undefined,
      })
      saveSessionId(s.sessionId)
      setLatest(s.sessionId, null)
      setSession(s)
      setProgress(null)
      setTranscript(null)
      setRevisionPlan(null)
      setRevisedDocument(null)
      setUserDoc(null)
      setQuestionPreviews({})
      setProgressMap({})
      void refreshSessionList()
      toast('议事厅已开启', 'ok')
      navigate(appPaths.session(s.sessionId), { replace: true })
      return true
    } catch (e) {
      toast(e instanceof Error ? e.message : '创建会话失败', 'error')
      return false
    } finally {
      setBusy(false)
    }
  }

  /**
   * 从「开新对话」弹窗建会话。
   *
   * <p>网关留空（Host 先用服务器默认），开厅后在左栏「切换供应商」里配；
   * 文风 / 轮次走服务端默认值。
   */
  const createFromDialog = async (opts: { docEditMode: boolean }) => {
    const ok = await createSession({
      userStyle: '简洁书面语',
      autoDiscuss: true,
      docEditMode: opts.docEditMode,
      maxRounds: 2,
      llmApiKey: '',
      llmBaseUrl: '',
      llmModel: '',
      llmLabel: '',
    })
    if (!ok) return
    setViewingArchived(null)
    setShowNewConversation(false)
  }

  const updateLlm = async (data: {
    llmApiKey?: string
    llmBaseUrl: string
    llmModel: string
    llmLabel: string
  }) => {
    if (!session) return
    setBusy(true)
    try {
      const s = await api.updateLlm(session.sessionId, data)
      setSession(s)
      toast('会话网关已更新', 'ok')
    } catch (e) {
      toast(e instanceof Error ? e.message : '更新网关失败', 'error')
    } finally {
      setBusy(false)
    }
  }

  const endSession = async () => {
    if (!session) return

    // 检查是否有可保存的智能体（只有「还没在常驻列表里的」才需要询问）。
    // ⚠️ 必须比 agentUuid（账号级常驻 id）而不是 agentId（本会话的席位 id，
    // 形如 agent-3f2a91bb，每次注册都新生成）—— 比 agentId 会永远不等，
    // 于是每结束一次会话都列出全部席位、每保存一次就在库里新建一行新 uuid，
    // 同一个智能体攒出两份互不相干的心得。
    const newAgentsCount = session.agents.filter(
      (agent) => !persistentAgents.some((p) => p.agentId === agent.agentUuid),
    ).length

    if (newAgentsCount > 0) {
      setShowSaveDialog(true)
      return
    }

    // 没有可保存的智能体 → 直接进「总结经验」
    proceedToMemoryDialog()
  }

  // 真正的结束：归档进库 + 销毁内存会话（被智能体弹窗、总结弹窗共用）
  const actuallyDestroy = async () => {
    if (!session) return
    setBusy(true)
    try {
      const r = await api.archiveSession(session.sessionId)
      clearSessionId()
      setLatest(null, null)
      setSession(null)
      setProgress(null)
      setTranscript(null)
      setRevisionPlan(null)
      setRevisedDocument(null)
      setUserDoc(null)
      toast(
        r.archived
          ? `会话已结束，存入「已归档」（${r.questionCount ?? 0} 个议题）`
          : `会话已结束（${r.reason ?? '未归档'}）`,
        'ok',
      )
      void refreshSessionList()
      void refreshArchivedList()
      // 结束后立刻问要不要开新的一轮（继续留在议事厅，只是中间变成空态）
      setShowNewConversation(true)
      navigate(appPaths.workbench(), { replace: true })
    } catch (e) {
      toast(e instanceof Error ? e.message : '结束会话失败', 'error')
    } finally {
      setBusy(false)
    }
  }

  // 从「保存智能体」对话框完成 → 进入「保存知识 / 总结经验」两轮对话框（再往后才是「保存总结」）
  const proceedToMemoryDialog = (sess: Session | null = session) => {
    setShowSaveDialog(false)
    setSaveSelection(new Set())
    setSaveNote('')
    if (!sess) return

    // 两轮的目标各自现算，不维护「本次新保存了谁」那层只活在 state 里的对应 ——
    // 用户在弹窗点取消再点一次「结束会话」就丢了（续跑洞）。身份已经由
    // saveSessionAndEnd / 加入时落回席位，这里每次现算都自洽。
    //
    //   知识轮：本场读了**新文件**、且已经是常驻（挂了 uuid）的席位。
    //           它**不依赖议题** —— 传完文件没提问就结束会话，知识照样该入库：
    //           它是文件库，不是讨论产物。
    //   心得轮：挂着常驻身份（agentUuid / mentorUuid）的席位，且本场至少有一个议题
    //           （一个议题都没有就没人发过言，总结不出任何经验）。
    const docsTargets = sess.agents.filter((a) => a.agentUuid && a.hasNewUpload)
    const memTargets =
      sess.questionIds.length > 0
        ? sess.agents.filter((a) => a.agentUuid || a.mentorUuid)
        : []
    if (!currentUser || (docsTargets.length === 0 && memTargets.length === 0)) {
      // 匿名会话没有 user 行，调任何一格都必 401；两轮都没目标就整个跳过
      proceedToSummaryDialog()
      return
    }

    const docsIds = new Set(docsTargets.map((a) => a.agentId))
    const memIds = new Set(memTargets.map((a) => a.agentId))
    // 两轮的**并集**当格子列表：同一个智能体在两轮里是同一格，只是子状态不同。
    // 拆成两个列表的话，「第 2 格失败了」在两轮里会指向不同的人。
    const union = [...docsTargets, ...memTargets.filter((a) => !docsIds.has(a.agentId))]
    const list: MemorySyncItem[] = union.map((a) => ({
      agentId: a.agentId,
      name: a.name,
      type: (a.mentorUuid ? 'MENTOR' : 'PERSISTENT_AGENT') as MemoryTargetType,
      uuid: (a.mentorUuid || a.agentUuid) as string,
      docState: docsIds.has(a.agentId) ? 'pending' : 'skipped',
      memState: memIds.has(a.agentId) ? 'pending' : 'skipped',
    }))
    const docsIdx: number[] = []
    union.forEach((a, i) => {
      if (docsIds.has(a.agentId)) docsIdx.push(i)
    })
    setMemorySyncItems(list)
    setShowMemorySync(true)
    void runDocSaves(sess.sessionId, list, docsIdx, true)
  }

  /** 一格是否已经落定为终态（不会再自己变）。决定要不要自动推进靠它。 */
  const isSettledState = (s: string) => s === 'ok' || s === 'failed' || s === 'skipped'

  /**
   * 两轮都跑完（无论成败）后，判定要不要自动进「保存讨论总结」。
   *
   * <p>判据是「**没有 pending、也没有 running**」而不是原来的「全部成功」：
   * 后者会把「标红了但用户不打算重试」的格子永远卡在弹窗里。
   * 有失败格时不自动推进，由用户自己决定是重试还是点「跳过并结束」。
   */
  const finishMemorySync = (sid: string, next: MemorySyncItem[]) => {
    const settled = next.every((it) => isSettledState(it.docState) && isSettledState(it.memState))
    const anyFailed = next.some((it) => it.docState === 'failed' || it.memState === 'failed')
    if (!settled || anyFailed) return
    // 席位上的心得快照已经在后端换新了，顺手把 session 拉一遍，
    // 这样用户若在下一步点「取消」（会话保留），左栏卡片显示的时间也是对的
    void refreshSession(sid)
    // 知识已经入库，常驻卡片上的「N 个文件 · M 页」得跟着变（这个 hook 不做轮询）
    void refreshPersistentAgents()
    window.setTimeout(() => proceedToSummaryDialog(), 900)
  }

  /**
   * **知识轮**：串行把每个席位本场读过的文件存进它的常驻知识库。
   *
   * <p>跑完**无条件**接着跑心得轮（{@code thenMemory=true} 时），无论成败：
   * 知识是文件库、心得是经验，两者互不负责。早先「全部成功才推进」的判据会让用户
   * 攒了几场的心得因为**一个文件摘要失败**而整场作废。
   *
   * @param thenMemory false = 这是用户点「重试」触发的单格重跑，不该把整轮心得也拉起来
   */
  const runDocSaves = async (
    sid: string,
    list: MemorySyncItem[],
    indexes: number[],
    thenMemory: boolean,
  ) => {
    setMemorySyncRunning(true)
    setMemorySyncPhase('docs')
    const next = list.map((it) => ({ ...it }))
    for (const i of indexes) {
      next[i] = { ...next[i], docState: 'running', docMessage: undefined }
      setMemorySyncItems([...next])
      try {
        const r = await api.saveSeatKnowledge(sid, next[i].agentId)
        if (!r.ok) {
          next[i] = {
            ...next[i],
            docState: 'failed',
            docMessage: '这个席位不是常驻智能体，知识无处归属',
          }
        } else {
          // 摘要没生成**不算这一格失败**：页知识已经落库了，检索与讨论照常可用，
          // 少的只是九字段那一步 —— 用后缀说明，不用红字，也不阻断流程
          next[i] = {
            ...next[i],
            docState: 'ok',
            docPages: r.pageCount,
            docNote: r.failedCount > 0 ? '摘要未生成' : undefined,
          }
        }
      } catch (e) {
        next[i] = {
          ...next[i],
          docState: 'failed',
          docMessage: e instanceof Error ? e.message : '保存知识失败',
        }
      }
      setMemorySyncItems([...next])
    }
    setMemorySyncRunning(false)

    const memIdx: number[] = []
    if (thenMemory) {
      next.forEach((it, i) => {
        if (it.memState === 'pending') memIdx.push(i)
      })
    }
    if (memIdx.length > 0) {
      await runMemorySummaries(sid, next, memIdx)
      return
    }
    setMemorySyncItems([...next])
    finishMemorySync(sid, next)
  }

  /** 重试某一轮的某一格。两轮各可能失败，所以必须指明是哪一轮。 */
  const retryMemorySync = (index: number, round: 'docs' | 'memory') => {
    if (!session) return
    const sid = session.sessionId
    if (round === 'docs') {
      void runDocSaves(sid, memorySyncItems, [index], false)
    } else {
      void runMemorySummaries(sid, memorySyncItems, [index])
    }
  }

  /**
   * **心得轮**：串行跑总结。一次只有一个请求在途 —— 并发做不出干净的 N/M（完成顺序与列表顺序
   * 对不上，「第 2 格失败了」就没意义了），重试的边界也会糊。
   *
   * <p>单格失败不中断：标红留给用户「重试」或「跳过并结束」。
   * 全部落定为终态且无失败时，{@link #finishMemorySync} 会稍等一下（让用户看见满格绿）
   * 自动进「保存讨论总结」。
   */
  const runMemorySummaries = async (
    sid: string,
    list: MemorySyncItem[],
    indexes: number[],
  ) => {
    setMemorySyncRunning(true)
    setMemorySyncPhase('memory')
    const next = list.map((it) => ({ ...it }))
    for (const i of indexes) {
      next[i] = { ...next[i], memState: 'running', message: undefined }
      setMemorySyncItems([...next])
      try {
        const v = await api.summarizeAgentMemory(sid, next[i].agentId)
        next[i] = { ...next[i], memState: 'ok', chars: v.content?.length ?? 0 }
      } catch (e) {
        next[i] = {
          ...next[i],
          memState: 'failed',
          message: e instanceof Error ? e.message : '总结失败',
        }
      }
      setMemorySyncItems([...next])
    }
    setMemorySyncRunning(false)
    setMemorySyncItems([...next])
    finishMemorySync(sid, next)
  }

  // 从「保存智能体」对话框完成 → 进入「保存总结」对话框
  const proceedToSummaryDialog = () => {
    if (!session) return
    const hasQuestions = session.questionIds.length > 0
    setSummarySaveCurrent(hasQuestions)
    setSummaryNote('')
    setSummaryScope('ALL_QUESTIONS')
    setShowSaveDialog(false)
    setSaveSelection(new Set())
    setSaveNote('')
    setShowMemorySync(false)
    setMemorySyncItems([])
    setShowSummaryDialog(true)
  }

  const saveSessionAndEnd = async () => {
    if (!session || saveSelection.size === 0) return

    const sid = session.sessionId
    setBusy(true)
    const savedIds: number[] = [] // 用于失败回滚
    try {
      // 保存选择的智能体到常驻列表；记录已入库的 id，失败时回滚
      const selectedAgents = session.agents.filter((agent) =>
        saveSelection.has(agent.agentId),
      )
      // 「本次新保存的智能体 ↔ 席位」这层对应，保存循环里顺手收集
      const links: Array<{ agentId: string; agentUuid: string }> = []
      /** 没能把网关一并存下来的（席位在弹窗开着时被移除之类的边角） */
      const noGateway: string[] = []
      for (const agent of selectedAgents) {
        // 带上源会话 + 源席位：后端从席位取它**当前在用**的网关（含 apiKey）加密入库。
        // 不能由前端传 Key —— 席位回给前端的 apiKey 是掩码，传回来存进去的就是一串星号。
        const { view, gatewaySaved } = await addPersistentAgent({
          name: agent.name,
          baseUrl: agent.baseUrl,
          model: agent.model,
          persona: agent.persona,
          personality: agent.personality,
          sessionId: sid,
          agentId: agent.agentId,
        })
        savedIds.push(view.id)
        if (!gatewaySaved) noGateway.push(agent.name)
        links.push({ agentId: agent.agentId, agentUuid: view.agentId })
      }
      toast(`已保存 ${saveSelection.size} 个智能体到常驻列表`, 'ok')
      if (noGateway.length > 0) {
        // 降级不是失败：这些智能体照样入库了，只是没带上网关（加入时得选「用本场网关」）。
        // 所以只提示、不回滚。
        toast(`${noGateway.join('、')} 没能存下网关（席位已不存在），加入时请选「用本场网关」`, 'error')
      }

      // 把常驻身份落回席位 —— 必须在保存循环**全部成功之后**：
      // 中途失败会回滚已入库的行，先挂上的链接就成了指向已删对象的悬空 uuid。
      // 这一步失败不回滚已保存的智能体（它们本身是好的），只是这一场不参与总结经验。
      for (const l of links) {
        try {
          await api.linkPersistentSeat(sid, l.agentId, l.agentUuid)
        } catch (e) {
          // eslint-disable-next-line no-console
          console.warn('补挂常驻身份失败，这一场不总结它的经验', l, e)
        }
      }

      // 席位上的 uuid 变了，拿刷新后的 session 去算「待总结目标」——
      // 直接用闭包里的旧 session 会漏掉刚挂上的那两个
      const fresh = await refreshSession(sid).catch(() => null)
      proceedToMemoryDialog(fresh ?? null)
    } catch (e) {
      toast(`${e instanceof Error ? e.message : '保存失败'}，正在回滚已入库的 ${savedIds.length} 个…`, 'error')
      // 回滚：把刚入库的全部删掉
      for (const id of savedIds) {
        try { await removePersistentAgent(id) } catch { /* ignore */ }
      }
    } finally {
      setBusy(false)
    }
  }

  const skipAgentSaveAndOpenSummary = () => {
    // 不保存任何智能体，但继续走「总结经验」→「保存总结」
    proceedToMemoryDialog()
  }

  const cancelMemorySync = () => {
    // 关闭弹窗，保留当前会话；已经总结完的那几个也已经落库了
    setShowMemorySync(false)
    setMemorySyncItems([])
  }

  const cancelSave = () => {
    setShowSaveDialog(false)
    setSaveSelection(new Set())
    setSaveNote('')
  }

  /**
   * 确认删除一个常驻智能体。
   *
   * <p>后端在 DB 层用外键 CASCADE 连带清掉它的知识与页理解，心得按 target_uuid 显式清 ——
   * 前端这边只管把可能开着的知识库弹窗一并关掉：对象已经不在了，留着会显示一片过期数据。
   */
  const confirmDeletePersistent = async () => {
    const p = pendingDeletePersistent
    if (!p) return
    setPendingDeletePersistent(null)
    if (docsOf && docsOf.agentId === p.agentId) setDocsOf(null)
    try {
      await removePersistentAgent(p.id)
      toast('已从常驻列表移除', 'ok')
    } catch (e) {
      toast(e instanceof Error ? e.message : '移除失败', 'error')
    }
  }

  // ── 总结保存对话框 handlers ──────────────────────────────
  const pickSummaryQid = (): string | null => {
    if (!session) return null
    return (
      activeQid ||
      session.questionIds[session.questionIds.length - 1] ||
      null
    )
  }

  const finishWithSummary = async () => {
    if (!session) return
    const qid = pickSummaryQid()

    if (!summarySaveCurrent || !qid) {
      // 没勾选，或没有问题可总结 → 直接销毁
      await skipSummaryAndDestroy()
      return
    }

    setBusy(true)
    let allOk = true
    try {
      if (summarySaveCurrent) {
        try {
          const r = await api.generateSummary(session.sessionId, qid, {
            scope: summaryScope,
          })
          toast(`本场总结已生成：${r.fileName}`, 'ok')
        } catch (e) {
          allOk = false
          toast(`本场总结保存失败：${e instanceof Error ? e.message : '未知错误'}。会话已保留，请稍后重试。`, 'error')
        }
      }
    } finally {
      setBusy(false)
      resetSummaryDialog()
      // 关键：任一总结失败就不销毁会话，让用户有机会重试
      if (allOk) {
        await actuallyDestroy()
      }
    }
  }

  const skipSummaryAndDestroy = async () => {
    resetSummaryDialog()
    await actuallyDestroy()
  }

  const cancelSummaryDialog = () => {
    // 关闭弹窗，保留当前会话；之前已保存的智能体也已落库
    resetSummaryDialog()
  }

  const resetSummaryDialog = () => {
    setShowSummaryDialog(false)
    setSummarySaveCurrent(true)
    setSummaryNote('')
    setSummaryScope('ALL_QUESTIONS')
  }

  const registerAgent = async (data: {
    name: string
    apiKey: string
    baseUrl: string
    model: string
    persona: string
    personality: string
    useSessionLlm: boolean
    /** 从常驻库加入时带上：后端据此挂身份、取心得、并用它自己存的网关 */
    agentUuid?: string
  }) => {
    if (!session) return
    setBusy(true)
    try {
      await api.registerAgent(session.sessionId, {
        name: data.name,
        // 带 agentUuid 时这几个网关字段后端一律忽略（取自常驻行），
        // 掩码值传过去也没有意义 —— 这里索性不传
        apiKey: data.useSessionLlm || data.agentUuid ? undefined : data.apiKey || undefined,
        baseUrl: data.useSessionLlm || data.agentUuid ? undefined : data.baseUrl || undefined,
        model: data.useSessionLlm || data.agentUuid ? undefined : data.model || undefined,
        persona: data.persona || undefined,
        personality: data.personality || undefined,
        useSessionLlm: data.useSessionLlm,
        agentUuid: data.agentUuid,
      })
      await refreshSession(session.sessionId)
      toast(`已注册「${data.name}」`, 'ok')
    } catch (e) {
      toast(e instanceof Error ? e.message : '注册失败', 'error')
      throw e
    } finally {
      setBusy(false)
    }
  }

  /**
   * 新建导师 → 逐篇分析论文 → 加入本场。
   *
   * <p>论文**逐篇串行上传**（一篇一个请求）：批量跑几分钟，塞进单个请求必然超时；
   * 后端逐篇落库，中断时已完成的也不会白费。单篇失败不中断整批。
   */
  const createMentorWithPapers = async (input: MentorFormInput, files: File[]) => {
    if (!session) return
    const sid = session.sessionId
    setBusy(true)
    try {
      const mentor = await createMentor({
        name: input.name,
        discipline: input.discipline || undefined,
        focus: input.focus || undefined,
        personality: input.personality || undefined,
        model: input.model || undefined,
        baseUrl: input.baseUrl || undefined,
        apiKey: input.apiKey || undefined,
      })
      if (files.length > 0) {
        const outcomes = await uploadMentorPapers(mentor.id, files, sid)
        const failed = outcomes.filter((o) => !o.ok)
        if (failed.length > 0) {
          toast(
            `导师已创建，但有 ${failed.length} 篇论文分析失败：${failed.map((f) => f.fileName).join('、')}`,
            'error',
          )
        }
      }
      await api.joinMentor(sid, mentor.id)
      await refreshSession(sid)
      setShowAddAgentDialog(false)
      toast(`导师「${mentor.name}」已加入本场讨论`, 'ok')
    } catch (e) {
      toast(e instanceof Error ? e.message : '创建导师失败', 'error')
      throw e
    } finally {
      setBusy(false)
    }
  }

  /** 把账号级已有的导师加入本场（论文已在库里，不重传、不重跑 Vision） */
  const joinMentor = async (m: MentorDto) => {
    if (!session) return
    setBusy(true)
    try {
      await api.joinMentor(session.sessionId, m.id)
      await refreshSession(session.sessionId)
      setShowAddAgentDialog(false)
      toast(`导师「${m.name}」已加入本场讨论`, 'ok')
    } catch (e) {
      toast(e instanceof Error ? e.message : '加入导师失败', 'error')
      throw e
    } finally {
      setBusy(false)
    }
  }

  /**
   * 往账号级导师库追加论文。论文属于导师、不属于本场会话 ——
   * 本场席位用的是「加入那一刻」的快照，新论文在下一个新会话里才生效（用户已确认的语义）。
   */
  const uploadMentorPapersForSeat = async (agentId: string, files: File[]) => {
    if (!session) return
    const seat = session.agents.find((a) => a.agentId === agentId)
    const mentor = seat ? mentorOfAgent(seat) : undefined
    if (!mentor) {
      toast('这个导师已从导师库删除，无法再追加论文', 'error')
      return
    }
    setBusy(true)
    try {
      const outcomes = await uploadMentorPapers(mentor.id, files, session.sessionId)
      const failed = outcomes.filter((o) => !o.ok)
      if (failed.length > 0) {
        toast(
          `${outcomes.length - failed.length} 篇成功、${failed.length} 篇失败：` +
            failed.map((f) => `${f.fileName}（${f.message}）`).join('；'),
          'error',
        )
      } else {
        toast(
          `已分析 ${outcomes.length} 篇导师论文。本场讨论仍用加入时的论文快照，新论文在下一个新会话生效。`,
          'ok',
        )
      }
    } catch (e) {
      toast(e instanceof Error ? e.message : '上传导师论文失败', 'error')
    } finally {
      setBusy(false)
    }
  }

  /** 打开某导师席位的论文摘要库 */
  const openMentorPapers = (agent: Agent) => {
    const mentor = mentorOfAgent(agent)
    if (!mentor) {
      toast('这个导师已从导师库删除，论文摘要不可用', 'error')
      return
    }
    setMentorPapersOf(mentor)
  }

  /** 席位上挂的账号级身份（导师优先，其次是常驻智能体）；都没有则是临时席位 */
  const memoryTargetOf = (agent: Agent): MemoryTarget | null => {
    if (agent.mentorUuid) {
      return { type: 'MENTOR', uuid: agent.mentorUuid, name: agent.name }
    }
    if (agent.agentUuid) {
      return { type: 'PERSISTENT_AGENT', uuid: agent.agentUuid, name: agent.name }
    }
    return null
  }

  /**
   * 打开经验心得只读弹窗。**必须先核对 uuid 在库里还在不在**：
   * 席位上带的是「加入那一刻」的快照，智能体/导师可能中途被用户删掉了，
   * 而后端对不存在的 uuid 一律抛 AuthException → HTTP 401 →
   * 前端会清登录态并弹登录框。为了看一眼已删对象的经验把用户踢下线，太糟了。
   */
  const openMemory = (type: MemoryTargetType, uuid: string, name: string) => {
    const exists =
      type === 'MENTOR'
        ? mentors.some((m) => m.mentorUuid === uuid)
        : persistentAgents.some((p) => p.agentId === uuid)
    if (!exists) {
      toast(`「${name}」已从${type === 'MENTOR' ? '导师库' : '常驻列表'}删除，经验记录不可用`, 'error')
      return
    }
    setMemoryOf({ type, uuid, name })
  }

  const openAgentMemory = (agent: Agent) => {
    const target = memoryTargetOf(agent)
    if (!target) {
      toast('这位智能体不是常驻身份，没有可积累的经验', 'info')
      return
    }
    openMemory(target.type, target.uuid, target.name)
  }

  /** 拔钉子：解除后它不再进硬前提段，但用户当初确认过的历史仍在 */
  const unpinMemory = async (pin: AgentMemoryPin) => {
    if (!memoryOf) return
    setUnpinning(true)
    try {
      await api.deleteMemoryPin(pin.id)
      setMemoryRefreshToken((n) => n + 1)
      toast('已解除锁定', 'ok')
      setPendingUnpin(null)
    } catch (e) {
      toast(e instanceof Error ? e.message : '解除锁定失败', 'error')
    } finally {
      setUnpinning(false)
    }
  }

  const deleteMentor = async (m: MentorDto) => {
    setBusy(true)
    try {
      await removeMentor(m.id)
      toast(`已删除导师「${m.name}」`, 'ok')
    } catch (e) {
      toast(e instanceof Error ? e.message : '删除导师失败', 'error')
    } finally {
      setBusy(false)
    }
  }

  /** 从本场席位移除智能体（连带后端清掉它的文献知识），失败只提示不回滚 UI */
  const removeAgent = async (agentId: string) => {
    if (!session) return
    setBusy(true)
    try {
      await api.removeAgent(session.sessionId, agentId)
      await refreshSession(session.sessionId)
      toast('已移除智能体', 'ok')
    } catch (e) {
      toast(e instanceof Error ? e.message : '移除失败', 'error')
    } finally {
      setBusy(false)
    }
  }

  /** 改会话配置（文风 / 最高讨论轮次）。后端会回整份 session，直接替换。 */
  const updateSessionSettings = async (data: {
    userStyle: string
    maxRounds: number
  }): Promise<boolean> => {
    if (!session) return false
    setBusy(true)
    try {
      const s = await api.updateSessionSettings(session.sessionId, {
        userStyle: data.userStyle,
        maxRounds: data.maxRounds,
      })
      if (stillCurrent(s.sessionId)) setSession(s)
      toast('会话配置已更新', 'ok')
      return true
    } catch (e) {
      toast(e instanceof Error ? e.message : '更新会话配置失败', 'error')
      return false
    } finally {
      setBusy(false)
    }
  }

  /** 继续改稿：在当前改写结果上按用户要求再改一轮（覆盖上一版） */
  const continueRevision = async (requirement: string) => {
    if (!session || !activeQid) return
    const sid = session.sessionId
    const qid = activeQid
    setBusy(true)
    try {
      await api.continueRevision(sid, qid, requirement)
      await loadQuestionBundle(sid, qid)
      toast('已按你的要求再改一轮', 'ok')
    } catch (e) {
      toast(e instanceof Error ? e.message : '继续改稿失败', 'error')
      throw e
    } finally {
      setBusy(false)
    }
  }

  const uploadAgentDoc = async (agentId: string, file: File) => {
    if (!session) return
    setBusy(true)
    toast(`正在处理文献 ${file.name}…`)
    try {
      await api.uploadAgentDocument(session.sessionId, agentId, file)
      await refreshSession(session.sessionId)
      toast('文献处理完成', 'ok')
    } catch (e) {
      await refreshSession(session.sessionId).catch(() => null)
      toast(e instanceof Error ? e.message : '上传失败', 'error')
    } finally {
      setBusy(false)
    }
  }

  const uploadUserDoc = async (file: File) => {
    if (!session) return
    setBusy(true)
    setUserDoc({
      status: 'DOCUMENT_UPLOADING',
      statusMessage: '用户文档上传中…',
      stageHint: '正在接收用户文档…',
      fileName: file.name,
      pageCount: 0,
      progress: { current: 0, total: 0, ratio: 0 },
      styleProfile: null,
    })
    try {
      await api.uploadUserDocument(session.sessionId, file)
      const next = await api.userDocumentStatus(session.sessionId)
      setUserDoc(next)
      toast(`已上传待改文档 ${file.name}`, 'ok')
    } catch (e) {
      await api
        .userDocumentStatus(session.sessionId)
        .then(setUserDoc)
        .catch(() => null)
      toast(e instanceof Error ? e.message : '上传失败', 'error')
    } finally {
      setBusy(false)
    }
  }

  const loadQuestionBundle = useCallback(async (sid: string, qid: string) => {
    const [p, tr] = await Promise.all([
      api.questionStatus(sid, qid),
      api.transcript(sid, qid),
    ])
    if (!stillCurrent(sid, qid)) return p
    setProgress(p)
    setTranscript(tr)
    setProgressMap((m) => ({ ...m, [qid]: p }))
    setQuestionPreviews((prev) => ({
      ...prev,
      [qid]: tr.content || prev[qid] || '',
    }))

    if (
      p.hasRevisionPlan ||
      tr.status === 'REVISION_PLAN_READY' ||
      tr.status === 'AWAITING_CONFIRM'
    ) {
      try {
        const rp = await api.revisionPlan(sid, qid)
        if (stillCurrent(sid, qid)) setRevisionPlan((rp.revisionPlan as RevisionPlan) || null)
      } catch {
        /* plan may not exist yet */
      }
    } else if (stillCurrent(sid, qid)) {
      setRevisionPlan(null)
    }

    if (p.hasRevisedDocument || tr.status === 'REVISED_COMPLETED') {
      try {
        const rd = await api.revisedDocument(sid, qid)
        if (stillCurrent(sid, qid)) setRevisedDocument((rd.revisedDocument as RevisedDocument) || null)
      } catch {
        if (stillCurrent(sid, qid)) setRevisedDocument(null)
      }
    } else if (p.revisedDocument) {
      if (stillCurrent(sid, qid)) setRevisedDocument(p.revisedDocument)
    } else if (stillCurrent(sid, qid)) {
      setRevisedDocument(null)
    }

    return p
  }, [])

  useEffect(() => {
    if (!session || !activeQid) return
    void loadQuestionBundle(session.sessionId, activeQid).catch((e) => {
      toast(e instanceof Error ? e.message : '加载问题失败', 'error')
    })
    // 依赖只要 sessionId + activeQid，避免每次 refreshSession 换新 session 对象导致重复拉取
  }, [session?.sessionId, activeQid, loadQuestionBundle, toast])

  const pollingEnabled = Boolean(
    session && activeQid && progress && isQuestionActive(progress.status),
  )

  usePolling(
    async () => {
      if (!session || !activeQid) return
      const sid = session.sessionId
      const qid = activeQid
      try {
        await refreshSession(sid)
        await loadQuestionBundle(sid, qid)
      } catch (e) {
        // 轮询里出错只打 warn，不阻塞下次轮询
        // eslint-disable-next-line no-console
        console.warn('[polling] refresh failed', e)
      }
    },
    1800,
    pollingEnabled,
  )

  const knowledgeBusy = useMemo(
    () =>
      Boolean(
        session?.agents.some(
          (a) =>
            a.knowledgeStatus === 'DOCUMENT_UPLOADING' ||
            a.knowledgeStatus === 'VISION_PROCESSING',
        ),
      ),
    [session],
  )

  /** 改稿模式下,userDocument 还在 Vision / 文风分析中 → 拉 /user-document/status */
  const userDocBusy = useMemo(
    () =>
      Boolean(
        session?.docEditMode &&
          userDoc &&
          (userDoc.status === 'DOCUMENT_UPLOADING' ||
            userDoc.status === 'VISION_PROCESSING' ||
            userDoc.status === 'STYLE_ANALYZING'),
      ),
    [session?.docEditMode, userDoc],
  )

  usePolling(
    async () => {
      if (!session || !session.docEditMode) return
      const sid = session.sessionId
      try {
        const next = await api.userDocumentStatus(sid)
        if (stillCurrent(sid)) setUserDoc(next)
      } catch (e) {
        // eslint-disable-next-line no-console
        console.warn('[polling:userDoc] status failed', e)
      }
    },
    2000,
    userDocBusy,
  )

  usePolling(
    async () => {
      if (!session) return
      const sid = session.sessionId
      try {
        await refreshSession(sid)
      } catch (e) {
        // eslint-disable-next-line no-console
        console.warn('[polling:knowledge] refresh failed', e)
      }
    },
    2500,
    Boolean(session && knowledgeBusy && !pollingEnabled),
  )

  // 「对话」列表：只要议事厅开着就刷新，这样切走的对话也能看到它还在思考/讨论，
  // 而不是切回去才发现跑完了。
  // 门槛不能是 `session`——结束会话后中间是空态，但右栏仍要能列出其它进行中的对话。
  usePolling(
    () => void refreshSessionList(),
    2500,
    route.kind === 'workbench' || route.kind === 'session' || route.kind === 'archive',
  )

  // 「已归档」：归档只会在「结束会话」时新增，变化很慢，5s 一次足够。
  // 门槛是「已登录」而不是「有会话」——查看归档时可能一个进行中的对话都没有。
  usePolling(() => void refreshArchivedList(), 5000, Boolean(currentUser))

  const askQuestion = async (content: string) => {
    if (!session) return
    const sid = session.sessionId
    setBusy(true)
    try {
      const p = await api.askQuestion(sid, content)
      // 预览与进度都按 questionId 索引，与「当前在看哪个对话」无关，切走了也照记
      setQuestionPreviews((prev) => ({ ...prev, [p.questionId]: content }))
      setProgressMap((m) => ({ ...m, [p.questionId]: p }))
      toast('问题已提交，专家开始独立思考', 'ok')
      // 提问在途时用户可能已从右栏切到别的对话（那个切换不受 busy 限制）。
      // 此时不能再碰「当前展示」相关状态，否则 session 与 activeQid 会串台。
      if (!stillCurrent(sid)) return
      const s = await refreshSession(sid)
      if (!stillCurrent(sid)) return
      setLatest(sid, p.questionId)
      navigate(appPaths.question(sid, p.questionId))
      setProgress(p)
      setTranscript(null)
      setRevisionPlan(null)
      setRevisedDocument(null)
      if (!s.questionIds.includes(p.questionId)) {
        setSession({ ...s, questionIds: [...s.questionIds, p.questionId] })
      }
    } catch (e) {
      toast(e instanceof Error ? e.message : '提问失败', 'error')
    } finally {
      setBusy(false)
    }
  }

  const startDiscuss = async () => {
    if (!session || !activeQid) return
    setBusy(true)
    try {
      await api.startDiscuss(session.sessionId, activeQid)
      await loadQuestionBundle(session.sessionId, activeQid)
      toast('圆桌讨论已开始', 'ok')
    } catch (e) {
      toast(e instanceof Error ? e.message : '无法开启讨论', 'error')
    } finally {
      setBusy(false)
    }
  }

  const updateInterjectionSetting = async (allow: boolean) => {
    if (!session) return
    const sid = session.sessionId
    setBusy(true)
    try {
      const updated = await api.updateInterjectionSetting(sid, allow)
      if (stillCurrent(sid)) setSession(updated)
      if (!allow && activeQid && stillCurrent(sid)) await loadQuestionBundle(sid, activeQid)
    } catch (e) {
      toast(e instanceof Error ? e.message : '更新插话设置失败', 'error')
      throw e
    } finally {
      setBusy(false)
    }
  }

  const addInterjection = async (content: string, targetAgentId?: string) => {
    if (!session || !activeQid) return
    const sid = session.sessionId
    const qid = activeQid
    setBusy(true)
    try {
      await api.addInterjection(sid, qid, content, targetAgentId)
      await loadQuestionBundle(sid, qid)
    } catch (e) {
      toast(e instanceof Error ? e.message : '插话失败', 'error')
      throw e
    } finally {
      setBusy(false)
    }
  }

  const continueDiscussion = async () => {
    if (!session || !activeQid) return
    const sid = session.sessionId
    const qid = activeQid
    setBusy(true)
    try {
      await api.continueDiscussion(sid, qid)
      await loadQuestionBundle(sid, qid)
    } catch (e) {
      toast(e instanceof Error ? e.message : '继续讨论失败', 'error')
    } finally {
      setBusy(false)
    }
  }

  const confirmRevision = async (ids: string[], note: string) => {
    if (!session || !activeQid) return
    setBusy(true)
    try {
      await api.confirmRevision(session.sessionId, activeQid, {
        acceptedItemIds: ids,
        note: note || undefined,
        autoRevise: true,
      })
      await loadQuestionBundle(session.sessionId, activeQid)
      toast('已确认修改项，开始改写', 'ok')
    } catch (e) {
      toast(e instanceof Error ? e.message : '确认失败', 'error')
    } finally {
      setBusy(false)
    }
  }

  const generateSummary = async (qid: string, scope: string) => {
    if (!session) throw new Error('无会话')
    setBusy(true)
    try {
      const r = await api.generateSummary(session.sessionId, qid, {
        scope,
        questionId: qid,
      })
      toast(`总结已生成：${r.fileName}`, 'ok')
      return r
    } catch (e) {
      toast(e instanceof Error ? e.message : '生成总结失败', 'error')
      throw e
    } finally {
      setBusy(false)
    }
  }

  const handleLogout = async () => {
    try {
      await api.logout()
    } catch {
      /* 即使后端登出失败也清前端 */
    }
    clearAuth()
    setCurrentUser(null)
    // 清空上一账号的常驻智能体缓存，避免跨账号残留可见
    clearPersistentAgents()
    // 导师库同为账号级数据，一并清掉
    clearMentors()
    setMentorPapersOf(null)
    setPendingDeleteMentor(null)
    // 心得也是账号级数据：留着会让下一个账号看到上一个账号的心得
    setMemoryOf(null)
    setCorrecting(null)
    setPendingUnpin(null)
    setShowMemorySync(false)
    setMemorySyncItems([])
    // 会话态一并复位：登录用户的会话对匿名不可见（后端一律 401），留着只会让界面停在
    // 上一个账号的议事厅、并让 2.5s 的对话列表轮询反复撞 401 弹登录框。
    // 复位清单与 switchSession / actuallyDestroy 保持一致。
    setLatest(null, null)
    clearSessionId()
    setSession(null)
    setProgress(null)
    setTranscript(null)
    setRevisionPlan(null)
    setRevisedDocument(null)
    setUserDoc(null)
    setQuestionPreviews({})
    setProgressMap({})
    setViewingArchived(null)
    setSessionList([])
    setArchivedList([])
    navigate(appPaths.home(), { replace: true })
    toast('已退出登录', 'ok')
  }

  if (booting) {
    return (
      <div className="app-shell">
        <div className="landing">
          <p className="muted">正在恢复议事厅…</p>
        </div>
      </div>
    )
  }

  if (route.kind === 'home') {
    return (
      <>
        <WelcomePage
          onStartSetup={() => {
            navigate(appPaths.workbench())
            setShowNewConversation(true)
          }}
          onResume={loadSessionId() ? () => navigate(appPaths.session(loadSessionId()!)) : undefined}
          currentUser={currentUser}
          onLoginClick={() => {
            setAuthDialogAutoOpened(false)
            setShowAuthDialog(true)
          }}
          onLogout={handleLogout}
          onAgentLibrary={() => navigate(appPaths.library())}
        />
        {showAuthDialog && (
          <AuthDialog
            onClose={() => setShowAuthDialog(false)}
            onAuthed={(u) => {
              setCurrentUser(u)
              setAuthDialogAutoOpened(false)
              toast(`登录成功，欢迎回来 ${u.displayName}`, 'ok')
            }}
          />
        )}
      </>
    )
  }

  if (route.kind === 'library') {
    return (
      <>
        <AgentLibraryPage section={route.section} currentUser={currentUser}
          onSectionChange={(section) => navigate(appPaths.library(section))}
          onBack={() => navigate(session ? appPaths.session(session.sessionId) : appPaths.workbench())}
          onLogin={() => { setAuthDialogAutoOpened(false); setShowAuthDialog(true) }}
          onLogout={handleLogout} />
        {showAuthDialog && (
          <AuthDialog
            onClose={() => setShowAuthDialog(false)}
            onAuthed={(u) => {
              setCurrentUser(u)
              setAuthDialogAutoOpened(false)
              toast(`登录成功，欢迎回来 ${u.displayName}`, 'ok')
            }}
          />
        )}
      </>
    )
  }

  const visibleSession = route.kind === 'session' && session?.sessionId === route.sessionId ? session : null
  const visibleArchive = route.kind === 'archive' && viewingArchived?.id === route.archiveId
    ? viewingArchived
    : null

  const refreshCurrentView = () => {
    if (route.kind === 'session') {
      if (visibleSession) void refreshSession(visibleSession.sessionId)
      else setRouteReload((value) => value + 1)
      return
    }
    if (route.kind === 'archive') {
      setRouteReload((value) => value + 1)
      return
    }
    void refreshSessionList()
  }

  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="brand">
          <div className="brand-mark">
            <BrandMark />
          </div>
          <div className="brand-text">
            <div className="brand-title">议事厅</div>
            <div className="brand-sub">Multi-agent discuss</div>
          </div>
        </div>
        <div className="header-meta">
          {/* 会话 ID 是纯内部标识（前端靠它恢复会话、后端日志靠它对账），
              对用户没意义 → 默认藏起来，地址栏加 ?debug=1 才显示。 */}
          {debugMode && visibleSession && (
            <SessionChip
              sessionId={visibleSession.sessionId}
              onCopied={(ok) =>
                toast(ok ? '会话 ID 已复制' : '复制失败，请展开后手动选中', ok ? 'ok' : 'error')
              }
            />
          )}
          {/* 用户名停在「刷新」左侧；无会话时（开厅表单）也保留，否则没法退出登录 */}
          {currentUser && (
            <>
              <UserMenu user={currentUser} onLogout={() => void handleLogout()} disabled={busy} />
              <button type="button" className="btn btn-ghost btn-sm agent-library-trigger"
                onClick={() => navigate(appPaths.library())} disabled={busy}>智能体库</button>
            </>
          )}
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            onClick={refreshCurrentView}
            disabled={busy}
          >
            刷新
          </button>
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            onClick={() => navigate(appPaths.home())}
            disabled={busy}
          >
            返回首页
          </button>
          {visibleSession && (
            <button
              type="button"
              className="btn btn-danger btn-sm"
              onClick={() => void endSession()}
              disabled={busy}
            >
              结束会话
            </button>
          )}
        </div>
      </header>

      {/* 议事厅共享三栏布局，中间舞台由当前 URL 决定。 */}
      <div className="app-main">
        {/* 左栏：归档查看态和「无会话」空态下都没有实时席位可言，留空栏保持三栅格 */}
        {visibleSession ? (
          <AgentPanel
            session={visibleSession}
            userDocument={userDoc}
            busy={busy}
            onAddAgent={() => setShowAddAgentDialog(true)}
            onUploadDoc={uploadAgentDoc}
            onUploadMentorPapers={uploadMentorPapersForSeat}
            onOpenMentorPapers={openMentorPapers}
            onOpenMemory={openAgentMemory}
            onRemoveAgent={removeAgent}
            onUploadUserDoc={uploadUserDoc}
            onRefresh={() => void refreshSession(visibleSession.sessionId)}
            onUpdateSettings={updateSessionSettings}
            onUpdateLlm={updateLlm}
          />
        ) : (
          <aside className="panel panel-side" />
        )}

        {/* 中间：归档只读 > 进行中的讨论 > 空态 */}
        {routeError ? (
          <section className="panel panel-center route-message">
            <h2>页面加载失败</h2>
            <p className="muted">{routeError}</p>
            <div className="route-message-actions">
              <button type="button" className="btn btn-accent" onClick={() => setRouteReload((value) => value + 1)}>重试</button>
              <button type="button" className="btn btn-ghost" onClick={() => navigate(appPaths.workbench(), { replace: true })}>返回议事厅</button>
            </div>
          </section>
        ) : route.kind === 'not-found' ? (
          <section className="panel panel-center route-message">
            <h2>页面不存在</h2>
            <p className="muted">这个地址无法识别，可以返回首页或进入议事厅。</p>
            <div className="route-message-actions">
              <button type="button" className="btn btn-accent" onClick={() => navigate(appPaths.workbench(), { replace: true })}>进入议事厅</button>
              <button type="button" className="btn btn-ghost" onClick={() => navigate(appPaths.home())}>返回首页</button>
            </div>
          </section>
        ) : visibleArchive ? (
          <ArchivedStage
            detail={visibleArchive}
            busy={busy}
            onClose={closeArchived}
            onGenerateSummary={(qid, scope) =>
              generateArchivedSummary(visibleArchive.id, qid, scope)
            }
          />
        ) : visibleSession ? (
          <DiscussionStage
            session={visibleSession}
            activeQid={activeQid}
            progress={progress}
            transcript={transcript}
            revisionPlan={revisionPlan}
            revisedDocument={revisedDocument}
            busy={busy}
            onAsk={askQuestion}
            onDiscuss={startDiscuss}
            onToggleInterjections={updateInterjectionSetting}
            onInterject={addInterjection}
            onContinueDiscussion={continueDiscussion}
            onConfirmRevision={confirmRevision}
            onContinueRevision={continueRevision}
            onGenerateSummary={generateSummary}
            questionPreviews={questionPreviews}
          />
        ) : (
          <section className="panel panel-center empty-stage">
            <div className="empty-stage-inner">
              <h2>没有进行中的对话</h2>
              <p className="muted">
                开一个新对话开始讨论，或者从右侧「已归档」里挑一个只读查看。
              </p>
              <button
                type="button"
                className="btn btn-accent"
                onClick={() => setShowNewConversation(true)}
                disabled={busy}
              >
                开启新对话
              </button>
            </div>
          </section>
        )}

        <ConversationRail
          sessions={sessionList}
          /* 高亮跟着中间舞台走：在看归档时，进行中的那条不再高亮，
             否则两个条目同时高亮，分不清「现在在看哪个」。 */
          activeSessionId={visibleArchive ? null : visibleSession?.sessionId ?? null}
          onSelectSession={(sid) => void switchSession(sid)}
          questionIds={visibleSession?.questionIds ?? []}
          activeQid={activeQid}
          previews={questionPreviews}
          progresses={progressMap}
          onSelectQuestion={(qid) => visibleSession && navigate(appPaths.question(visibleSession.sessionId, qid))}
          archived={archivedList}
          activeArchivedId={visibleArchive?.id ?? null}
          onSelectArchived={(id) => void openArchived(id)}
          onDeleteArchived={(id) => {
            const found = archivedList.find((a) => a.id === id)
            if (found) setPendingDeleteArchived(found)
          }}
        />
      </div>

      {/* 开新对话：选圆桌会议 / 改稿模式 */}
      {showNewConversation && (
        <NewConversationDialog
          busy={busy}
          onCancel={() => setShowNewConversation(false)}
          onCreate={(opts) => void createFromDialog(opts)}
        />
      )}

      {/* 删除归档：不可恢复，走确认弹窗 */}
      {pendingDeleteArchived && (
        <ConfirmDialog
          title="删除归档"
          danger
          confirmText="删除"
          busy={busy}
          message={
            <>
              确定要删除归档「{pendingDeleteArchived.title}」吗？
              这条记录会从数据库里彻底移除，无法恢复。
            </>
          }
          onConfirm={() => {
            const target = pendingDeleteArchived
            setPendingDeleteArchived(null)
            void deleteArchived(target.id)
          }}
          onCancel={() => setPendingDeleteArchived(null)}
        />
      )}

      {/* 添加智能体：① 注册新的领域智能体 ② 使用常驻智能体 */}
      {showAddAgentDialog && session && (
        <AddAgentDialog
          hasSessionLlm={Boolean(session.llm?.configured)}
          sessionModel={session.llm?.model || ''}
          persistentAgents={persistentAgents}
          mentors={mentors}
          joinedMentorUuids={session.agents
            .map((a) => a.mentorUuid)
            .filter((u): u is string => Boolean(u))}
          joinedAgentUuids={session.agents
            .map((a) => a.agentUuid)
            .filter((u): u is string => Boolean(u))}
          mentorProgress={mentorProgress}
          busy={busy}
          onClose={() => setShowAddAgentDialog(false)}
          onMentorCreate={createMentorWithPapers}
          onMentorJoin={joinMentor}
          onMentorRemove={(id) => {
            const found = mentors.find((m) => m.id === id)
            if (found) setPendingDeleteMentor(found)
          }}
          onMentorOpenPapers={(m) => setMentorPapersOf(m)}
          onOpenMemory={openMemory}
          onOpenGateway={(p) => setGatewayOf(p)}
          onOpenDocs={(p) => setDocsOf(p)}
          onRegister={async (data) => {
            try {
              await registerAgent(data)
              setShowAddAgentDialog(false)
            } catch {
              // 错误已由 registerAgent 内部 toast；弹窗保持打开，方便改完重试
            }
          }}
          onUsePersistent={async (agent) => {
            try {
              await registerAgent({
                name: agent.name,
                apiKey: '',
                baseUrl: '',
                model: '',
                persona: agent.persona ?? '',
                personality: agent.personality ?? '',
                // 用谁的网关不再问用户：**有自带的就用自带的，没有才随本场**。
                // 要换网关去点卡片上的「重新配置网关」改它存的那份 —— 那才是真正的配置入口，
                // 卡片上再放一组二选一只是把同一个决定问两遍。
                useSessionLlm: !agent.hasOwnGateway,
                // ⚠️ 必须带上：不带的话后端认不出这个席位是哪个常驻对象，
                // 心得不进 prompt、「带经验上场」不显示、结束会话时也不会总结它。
                agentUuid: agent.agentId,
              })
              // 加入成功即关窗
              setShowAddAgentDialog(false)
            } catch {
              /* 同上：保持打开 */
            }
          }}
          onRemovePersistent={(agentId) => {
            const found = persistentAgents.find((p) => p.agentId === agentId)
            if (!found) return
            // ⚠️ 只打开确认框，**不直接删**：删它会连带删掉它攒下的知识与经验，
            // 而原始文件当初没有入库 —— 知识一旦删掉就重建不了。
            // 改动前那个 × 是静默删除的（对齐导师那边的确认框）。
            setPendingDeletePersistent(found)
          }}
          onUpdatePersistentName={(agentId, newName) => {
            const found = persistentAgents.find((p) => p.agentId === agentId)
            if (!found) return
            void updatePersistentAgentName(found.id, newName).catch(
              (e) => toast(e instanceof Error ? e.message : '改名失败', 'error'),
            )
          }}
        />
      )}

      {/* ⚠️ 这两个弹窗必须排在 AddAgentDialog **之后**：它们都是从「添加智能体」弹窗里
          点出来的（导师列表的「查看论文摘要」/ × 删除），而所有弹窗共用 `.save-dialog-overlay`
          的 z-index: 1000 —— 同层级下 DOM 靠后的赢。写在前面的话，点下去会渲染在添加智能体
          弹窗**底下**，用户看到的是「点了没反应」。 */}
      {mentorPapersOf && (
        <MentorPapersDialog
          mentorId={mentorPapersOf.id}
          onClose={() => setMentorPapersOf(null)}
        />
      )}

      {/* 删除导师：连带其全部论文，不可恢复 */}
      {pendingDeleteMentor && (
        <ConfirmDialog
          title="删除导师"
          danger
          confirmText="删除"
          busy={busy}
          message={
            <>
              确定要删除导师「{pendingDeleteMentor.name}」吗？它名下的{' '}
              {pendingDeleteMentor.paperCount} 篇论文与全部页知识会一并删除，无法恢复。
              已加入其它会话的席位不受影响（那场讨论用的是加入时的论文快照）。
            </>
          }
          onConfirm={() => {
            const m = pendingDeleteMentor
            setPendingDeleteMentor(null)
            void deleteMentor(m)
          }}
          onCancel={() => setPendingDeleteMentor(null)}
        />
      )}

      {/* ⚠️ 以下三个弹窗必须排在 AddAgentDialog **之后**（同一条理由见上面的注释）：
          经验心得的两个入口之一就在「添加智能体」弹窗里，而左栏卡片的 .app-main
          DOM 上早于所有 App 级弹窗 —— 所以它们不能写在 AgentPanel 内部，只能提到这里。

          层序：保存智能体 → 保存总结（结束会话的两步）
                → 总结经验（夹在中间那一步）
                → 心得只读 → 纠错（二级，必须排在只读之后）→ 拔钉子确认（三级） */}

      {/* 结束会话「保存智能体」与「保存总结」中间的一步：先存知识，再让在场者总结本场经验 */}
      {showMemorySync && (
        <MemorySyncDialog
          items={memorySyncItems}
          phase={memorySyncPhase}
          running={memorySyncRunning}
          onRetry={retryMemorySync}
          onSkip={proceedToSummaryDialog}
          onCancel={cancelMemorySync}
        />
      )}

      {/* 经验心得只读弹窗。两个入口：常驻列表的「经验」、左栏卡片的「带经验上场」 */}
      {memoryOf && (
        <AgentMemoryDialog
          target={memoryOf}
          sessionId={session?.sessionId ?? null}
          refreshToken={memoryRefreshToken}
          onCorrect={() => setCorrecting(memoryOf)}
          onRequestUnpin={(pin) => setPendingUnpin(pin)}
          onClose={() => setMemoryOf(null)}
        />
      )}

      {/* 纠错对话：二级弹窗，必须写在只读弹窗之后 */}
      {correcting && (
        <MemoryCorrectDialog
          target={correcting}
          sessionId={session?.sessionId ?? null}
          onApplied={() => {
            setMemoryRefreshToken((n) => n + 1)
            // 确认过的纠正会同步刷新活会话里的席位快照，拉一遍让左栏卡片跟着变
            if (session) void refreshSession(session.sessionId)
          }}
          onClose={() => setCorrecting(null)}
        />
      )}

      {/* 改常驻智能体自己的网关。入口是「添加智能体 → ② 常驻库」卡片上的「网关」按钮，
          所以同样必须排在 AddAgentDialog 之后（同一条 z-index 理由）。 */}
      {gatewayOf && (
        <PersistentGatewayDialog
          agent={gatewayOf}
          busy={busy}
          onClose={() => setGatewayOf(null)}
          onSubmit={async (body) => {
            const view = await updatePersistentAgentGateway(gatewayOf.id, body)
            toast(
              body.mode === 'SESSION'
                ? `「${view.name}」已改为用本场网关`
                : `「${view.name}」的网关已保存（加密入库）`,
              'ok',
            )
            setGatewayOf(null)
          }}
        />
      )}

      {/* 常驻智能体的知识库（它读过的资料，跨会话累积）。入口是常驻库卡片上的「知识」按钮，
          同样必须排在 AddAgentDialog 之后（同一条 z-index 理由）。 */}
      {docsOf && (
        <PersistentDocsDialog
          agent={docsOf}
          sessionId={session?.sessionId ?? null}
          onClose={() => setDocsOf(null)}
          onChanged={() => void refreshPersistentAgents()}
        />
      )}

      {/* 删除常驻智能体：连带它攒下的知识与经验，不可恢复。**必须排在知识库弹窗之后** ——
          它可以从知识库那层点出来（删除常驻时顺手关掉知识库弹窗）。 */}
      {pendingDeletePersistent && (
        <ConfirmDialog
          title="移出常驻列表"
          danger
          confirmText="删除"
          busy={busy}
          message={
            <>
              确定要删除常驻智能体「{pendingDeletePersistent.name}」吗？
              {pendingDeletePersistent.docCount > 0 ? (
                <>
                  它攒下的{' '}
                  <strong>
                    {pendingDeletePersistent.docCount} 个文件（
                    {pendingDeletePersistent.pageCount} 页）知识
                  </strong>
                  与全部经验心得会一并删除，且<strong>无法恢复</strong> —— 原始文件当初没有入库，
                  删掉就重建不了。
                </>
              ) : (
                <>它的全部经验心得会一并删除，无法恢复。</>
              )}{' '}
              已经加入其它会话的席位不受影响（那场讨论用的是加入时的快照）。
            </>
          }
          onConfirm={() => void confirmDeletePersistent()}
          onCancel={() => setPendingDeletePersistent(null)}
        />
      )}

      {/* 拔钉子：三级弹窗。解除后它不再进硬前提段 */}
      {pendingUnpin && (
        <ConfirmDialog
          title="解除锁定"
          confirmText="解除"
          busy={unpinning}
          message={
            <>
              确定要解除这条锁定吗？
              <div className="confirm-quote">{pendingUnpin.content}</div>
              解除后它不再作为硬前提约束这位智能体 —— 以后的总结可以推翻它。
              你当初确认过的这次纠正仍留在它的心得正文里。
            </>
          }
          onConfirm={() => void unpinMemory(pendingUnpin)}
          onCancel={() => setPendingUnpin(null)}
        />
      )}

      {/* 保存对话框 */}
      {showSaveDialog && (
        <Modal
          className="save-dialog"
          onClose={cancelSave}
          closeOnEscape={false}
          closeOnOutside={false}
        >
            <ModalTitle asChild><h3>保存智能体</h3></ModalTitle>
            <p>选择要保存到常驻列表的智能体：</p>

            <div className="save-agents-list">
              {session!.agents.map(agent => (
                <label key={agent.agentId} className="save-agent-item">
                  <input
                    type="checkbox"
                    checked={saveSelection.has(agent.agentId)}
                    onChange={(e) => {
                      const newSelection = new Set(saveSelection)
                      if (e.target.checked) {
                        newSelection.add(agent.agentId)
                      } else {
                        newSelection.delete(agent.agentId)
                      }
                      setSaveSelection(newSelection)
                    }}
                  />
                  <span style={{
                    display: 'inline-block',
                    width: '8px',
                    height: '8px',
                    borderRadius: '50%',
                    background: `var(--agent-${agentColorIndex(agent.agentId)})`,
                    marginRight: '8px'
                  }}></span>
                  {agent.name}
                  <span className="agent-model">{agent.model}</span>
                </label>
              ))}
            </div>

            <div className="save-note-section">
              <label htmlFor="saveNote">备注（可选）</label>
              <input
                id="saveNote"
                type="text"
                value={saveNote}
                onChange={(e) => setSaveNote(e.target.value)}
                placeholder="为这次保存添加备注..."
              />
            </div>

            <div className="save-dialog-actions">
              <button className="btn btn-ghost" onClick={cancelSave}>
                取消
              </button>
              <button
                className="btn btn-ghost"
                onClick={skipAgentSaveAndOpenSummary}
              >
                不保存智能体，继续
              </button>
              <button
                className="btn btn-accent"
                onClick={saveSessionAndEnd}
                disabled={saveSelection.size === 0}
              >
                保存并继续（{saveSelection.size}个）
              </button>
            </div>
        </Modal>
      )}

      {/* 结束会话第二步：保存讨论总结 */}
      {showSummaryDialog && session && (
        <Modal
          className="save-dialog summary-dialog"
          onClose={cancelSummaryDialog}
          closeOnEscape={false}
          closeOnOutside={false}
        >
            <ModalTitle asChild><h3>保存讨论总结</h3></ModalTitle>
            <p>
              是否保存本次讨论的总结？不勾选也能直接结束。
            </p>

            {(() => {
              const canPickQid = session.questionIds.length > 0
              return (
                <div className="save-options-list">
                  <label
                    className="save-option-item"
                    data-disabled={!canPickQid || undefined}
                  >
                    <input
                      type="checkbox"
                      checked={summarySaveCurrent && canPickQid}
                      disabled={!canPickQid}
                      onChange={(e) => setSummarySaveCurrent(e.target.checked)}
                    />
                    <div className="save-option-body">
                      <div className="save-option-title">保存本场讨论总结（Markdown）</div>
                      <div className="muted save-option-desc">
                        生成一份 Markdown 总结文件（含各专家观点、共识与终稿）。
                      </div>
                    </div>
                  </label>

                  {!canPickQid && (
                    <p className="muted" style={{ margin: '0.25rem 0 0' }}>
                      当前会话还没有议题，无法生成讨论总结。
                    </p>
                  )}
                </div>
              )
            })()}

            {summarySaveCurrent && session.questionIds.length > 1 && (
              <div className="field" style={{ marginBottom: '1rem' }}>
                <label htmlFor="summaryScope">总结范围</label>
                <select
                  id="summaryScope"
                  className="select"
                  value={summaryScope}
                  onChange={(e) =>
                    setSummaryScope(
                      e.target.value as 'CURRENT_QUESTION' | 'ALL_QUESTIONS',
                    )
                  }
                >
                  <option value="ALL_QUESTIONS">整个会话</option>
                  <option value="CURRENT_QUESTION">仅当前议题</option>
                </select>
              </div>
            )}

            {summarySaveCurrent && (
              <div className="save-note-section">
                <label htmlFor="summaryNote">备注（可选）</label>
                <input
                  id="summaryNote"
                  type="text"
                  value={summaryNote}
                  onChange={(e) => setSummaryNote(e.target.value)}
                  placeholder="为这次总结添加备注…"
                />
              </div>
            )}

            <div className="save-dialog-actions">
              <button
                className="btn btn-ghost"
                onClick={cancelSummaryDialog}
                disabled={busy}
              >
                取消
              </button>
              <button
                className="btn btn-danger"
                onClick={() => void skipSummaryAndDestroy()}
                disabled={busy}
              >
                直接结束（都不保存）
              </button>
              <button
                className="btn btn-accent"
                onClick={() => void finishWithSummary()}
                disabled={busy}
              >
                保存并结束
              </button>
            </div>
        </Modal>
      )}

      <ToastStack items={toasts} onDismiss={dismissToast} />

      {showAuthDialog && (
        <AuthDialog
          onClose={() => {
            setShowAuthDialog(false)
            setAuthDialogAutoOpened(false)
          }}
          onAuthed={(u) => {
            setCurrentUser(u)
            setAuthDialogAutoOpened(false)
            toast(`登录成功，欢迎回来 ${u.displayName}`, 'ok')
          }}
        />
      )}

      {/* 自动弹登录框时给个轻提示，告诉用户为什么弹出来 */}
      {authDialogAutoOpened && showAuthDialog && (
        <div className="auth-auto-toast" role="status">
          登录已失效，请重新登录
        </div>
      )}
    </div>
  )
}
