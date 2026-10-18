import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client'
import type {
  AgentMemoryView,
  MemoryChatMessage,
  MemoryProposal,
  MemoryTarget,
} from '../api/types'

/** 纠错对话回传给后端的历史条数。前端截一道，后端 MAX_HISTORY_LINES 还有第二道闸。 */
const HISTORY_WINDOW = 12

function message(e: unknown, fallback: string): string {
  return e instanceof Error && e.message ? e.message : fallback
}

/**
 * 一个常驻对象（常驻智能体 / 导师）的经验心得：查看、纠错、应用方案、拔钉子。
 *
 * <p>纯 fetch + loading/error —— 心得没有服务端异步任务，不需要轮询，
 * 与 {@link useMentors} 那套「上传中按秒查进度」是两个场景。
 *
 * <p>{@code target} 传 null 时一切归零（弹窗关掉后 hook 就闲着）。
 * 切换 target 会自动重新拉取 —— 两个弹窗各自持有一个实例，互不干扰。
 *
 * @param sessionId 可选。纠错对话借这场会话的网关用：普通常驻智能体没有自己的 Key
 *                  （产品决策：不入库 API Key），不带就只能落到服务端默认网关。
 */
export function useAgentMemory(target: MemoryTarget | null, sessionId?: string | null) {
  const [view, setView] = useState<AgentMemoryView | null>(null)
  const [loading, setLoading] = useState(false)
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const type = target?.type ?? null
  const uuid = target?.uuid ?? null

  const reload = useCallback(async () => {
    if (!type || !uuid) {
      setView(null)
      setError(null)
      return null
    }
    setLoading(true)
    setError(null)
    try {
      const v = await api.getAgentMemory(type, uuid)
      setView(v)
      return v
    } catch (e) {
      // 不往外抛：调用点多在 useEffect / 事件回调里，抛出去就是未处理的 rejection
      setError(message(e, '读取经验心得失败'))
      return null
    } finally {
      setLoading(false)
    }
  }, [type, uuid])

  useEffect(() => {
    void reload()
  }, [reload])

  /**
   * 纠错对话的一轮。带最近 {@link HISTORY_WINDOW} 条历史过去 ——
   * 后端负责把 role 翻成「用户：/你：」的前缀，前端不拼 prompt 文本。
   */
  const send = useCallback(
    async (text: string, history: MemoryChatMessage[]) => {
      if (!type || !uuid) throw new Error('没有可纠错的对象')
      setSending(true)
      try {
        return await api.correctAgentMemory(type, uuid, {
          message: text,
          sessionId: sessionId || undefined,
          history: history.slice(-HISTORY_WINDOW).map((m) => ({ role: m.role, text: m.text })),
        })
      } finally {
        setSending(false)
      }
    },
    [type, uuid, sessionId],
  )

  /** 落地一张方案卡片。用户点了「确认」才会走到这里 —— 调用方负责这个前置条件。 */
  const applyProposal = useCallback(
    async (proposal: MemoryProposal) => {
      if (!type || !uuid) throw new Error('没有可纠错的对象')
      const after = proposal.after?.trim()
      if (!after) throw new Error('修改方案内容为空，无法应用')
      setSending(true)
      try {
        const v = await api.applyMemoryProposal(type, uuid, {
          before: proposal.before,
          after,
          rationale: proposal.rationale,
        })
        setView(v)
        return v
      } finally {
        setSending(false)
      }
    },
    [type, uuid],
  )

  /** 拔钉子。失败往外抛，让确认弹窗保持打开、用户能重试。 */
  const unpin = useCallback(
    async (pinId: number) => {
      if (!type || !uuid) return
      await api.deleteMemoryPin(pinId)
      await reload()
    },
    [type, uuid, reload],
  )

  return { view, loading, sending, error, reload, send, applyProposal, unpin }
}
