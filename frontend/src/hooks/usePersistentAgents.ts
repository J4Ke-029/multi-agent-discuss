import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client'
import type { PersistentAgentDto, UpdatePersistentGatewayRequest } from '../api/types'

export interface PersistentAgentView {
  /** 后端行 ID（数据库主键），用来 PUT/DELETE */
  id: number
  /** 前端 Agent.agentId 兼容字段（UUID 字符串，由后端生成并返回） */
  agentId: string
  /** 展示用名：customName 优先，否则回退后端 agent_name */
  name: string
  customName?: string | null
  persona?: string | null
  personality?: string | null
  model?: string | null
  baseUrl?: string | null
  /** 掩码（前 4 + `****` + 后 4）；没有自带网关时为 null。明文只在网关弹窗里临时取 */
  apiKey: string | null
  /** 它自己存了网关没有。有 = 加入本场时默认用它自己那份 */
  hasOwnGateway: boolean
  /** 知识库里攒了几份资料 / 共几页（卡片 meta 上的「N 个文件 · M 页」）。没有知识时都是 0。 */
  docCount: number
  pageCount: number
}

/** 新建的返回值：除了常驻对象本身，还带一条「网关到底存下来没有」的结果 */
export interface AddPersistentAgentResult {
  view: PersistentAgentView
  /** false = 源席位已不存在（或加密失败），这次只存下了名片，没存网关 */
  gatewaySaved: boolean
}

/**
 * 「常驻智能体」已迁到后端 MySQL（{@code /api/agents/persistent}）。这里只负责状态缓存 + fetch 同步。
 *
 * <p>乐观更新：UI 先反映用户操作；fetch 失败时回滚并抛出错误由调用方提示。
 */
export function usePersistentAgents() {
  const [persistentAgents, setPersistentAgents] = useState<PersistentAgentView[]>([])

  // 首次 mount 拉一次。失败只记 console —— 调用方（App）没有可展示错误的位置，
  // 而且列表为空本来就是个合理的降级表现。
  const refresh = useCallback(async () => {
    try {
      const list = await api.listPersistentAgents()
      setPersistentAgents(list.map(toView))
    } catch (e) {
      console.warn('加载常驻智能体失败', e)
    }
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const addPersistentAgent = useCallback(
    async (input: {
      name: string
      customName?: string
      baseUrl?: string
      model?: string
      persona?: string
      personality?: string
      /** 源会话 + 源席位：给了它俩，后端才会把该席位当前在用的网关加密存进来 */
      sessionId?: string
      agentId?: string
    }): Promise<AddPersistentAgentResult> => {
      const dto = await api.createPersistentAgent(input)
      const view = toView(dto)
      setPersistentAgents((prev) => [...prev, view])
      return { view, gatewaySaved: dto.gatewaySaved !== false }
    },
    [],
  )

  const removePersistentAgent = useCallback(async (dbId: number) => {
    // 乐观移除：记录「移除前被删的是哪一个」，失败时只回补这一个，
    // 避免把并发期间已成功删除的其它项一起回滚复活。
    const removed = persistentAgents.find((a) => a.id === dbId)
    setPersistentAgents((prev) => prev.filter((a) => a.id !== dbId))
    try {
      await api.deletePersistentAgent(dbId)
    } catch (e) {
      if (removed) setPersistentAgents((prev) => [...prev, removed])
      throw e
    }
  }, [persistentAgents])

  const updatePersistentAgentName = useCallback(async (dbId: number, newName: string) => {
    const dto = await api.updatePersistentAgentCustomName(dbId, newName)
    const view = toView(dto)
    setPersistentAgents((prev) => prev.map((a) => (a.id === dbId ? view : a)))
    return view
  }, [])

  /** 改它自己的网关。沿用改名的「先请求后更新」，不做乐观 —— 凭据写没写进去以服务端为准。 */
  const updatePersistentAgentGateway = useCallback(
    async (dbId: number, body: UpdatePersistentGatewayRequest) => {
      const dto = await api.updatePersistentAgentGateway(dbId, body)
      const view = toView(dto)
      setPersistentAgents((prev) => prev.map((a) => (a.id === dbId ? view : a)))
      return view
    },
    [],
  )

  return {
    persistentAgents,
    refresh,
    addPersistentAgent,
    removePersistentAgent,
    updatePersistentAgentName,
    updatePersistentAgentGateway,
    /** 登出 / 切换账号时清空本账号常驻列表缓存 */
    clearPersistentAgents: () => setPersistentAgents([]),
  }
}

function toView(dto: PersistentAgentDto): PersistentAgentView {
  return {
    id: dto.id,
    agentId: dto.agentId,
    name: dto.customName || dto.name,
    customName: dto.customName,
    persona: dto.persona,
    personality: dto.personality,
    model: dto.model,
    baseUrl: dto.baseUrl,
    apiKey: dto.apiKey ?? null,
    hasOwnGateway: dto.hasOwnGateway ?? Boolean(dto.apiKey),
    docCount: dto.docCount ?? 0,
    pageCount: dto.pageCount ?? 0,
  }
}