import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../api/client'
import type { MentorDto, MentorInput, MentorStatusView } from '../api/types'

/** 一篇论文的上传结果，供 UI 汇总「成功 N 篇 / 失败 M 篇」 */
export interface MentorUploadOutcome {
  fileName: string
  ok: boolean
  message?: string
}

/**
 * 「导师常驻库」的状态缓存（账号级，存在后端 MySQL）。
 *
 * <p>与 {@link usePersistentAgents} 同一套路（乐观增删 + 失败回滚），额外多两件事：
 * ① 论文是**逐篇串行上传**的 —— 批量跑几分钟，单个请求必然超时，而且逐篇落库
 *    保证中断时已完成的不白费；
 * ② 上传期间按 1.5s 轮询 {@code /mentors/{id}/status}，把「阅读中 3 / 25 页」显示出来。
 */
export function useMentors() {
  const [mentors, setMentors] = useState<MentorDto[]>([])
  const [loading, setLoading] = useState(false)
  const [activeProgress, setActiveProgress] = useState<MentorStatusView | null>(null)
  const pollRef = useRef<number | null>(null)

  const stopProgressPolling = useCallback(() => {
    if (pollRef.current !== null) {
      window.clearInterval(pollRef.current)
      pollRef.current = null
    }
    setActiveProgress(null)
  }, [])

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const list = await api.listMentors()
      setMentors(list)
      return list
    } catch (e) {
      // 与常驻智能体一致：拉不到就当空列表，不打扰用户
      console.warn('加载导师列表失败', e)
      return []
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  // 组件卸载时别把定时器留下
  useEffect(() => () => {
    if (pollRef.current !== null) window.clearInterval(pollRef.current)
  }, [])

  const patchMentor = useCallback((dto: MentorDto) => {
    setMentors((prev) => prev.map((m) => (m.id === dto.id ? { ...m, ...dto } : m)))
  }, [])

  const createMentor = useCallback(async (input: MentorInput) => {
    const dto = await api.createMentor(input)
    setMentors((prev) => [dto, ...prev])
    return dto
  }, [])

  const removeMentor = useCallback(
    async (id: number) => {
      // 乐观移除：只回滚被删的那一个，避免把并发期间已成功删除的其它项一起复活
      const removed = mentors.find((m) => m.id === id)
      setMentors((prev) => prev.filter((m) => m.id !== id))
      try {
        await api.deleteMentor(id)
      } catch (e) {
        if (removed) setMentors((prev) => [...prev, removed])
        throw e
      }
    },
    [mentors],
  )

  /**
   * 逐篇串行上传。单篇失败不中断整批，收集起来一并回报 ——
   * 用户一次选了 8 篇，不该因为第 2 篇格式不对就丢掉后面 6 篇。
   */
  const uploadPapers = useCallback(
    async (mentorId: number, files: File[], sessionId?: string): Promise<MentorUploadOutcome[]> => {
      const outcomes: MentorUploadOutcome[] = []
      pollRef.current = window.setInterval(() => {
        void api.mentorStatus(mentorId).then(setActiveProgress).catch(() => null)
      }, 1500)
      try {
        for (const file of files) {
          try {
            await api.uploadMentorPaper(mentorId, file, sessionId)
            outcomes.push({ fileName: file.name, ok: true })
          } catch (e) {
            outcomes.push({
              fileName: file.name,
              ok: false,
              message: e instanceof Error ? e.message : '上传失败',
            })
          }
        }
      } finally {
        stopProgressPolling()
      }
      const dto = await api.getMentor(mentorId)
      patchMentor(dto)
      return outcomes
    },
    [patchMentor, stopProgressPolling],
  )

  return {
    mentors,
    loading,
    activeProgress,
    refresh,
    createMentor,
    removeMentor,
    uploadPapers,
    patchMentor,
    /** 登出 / 切换账号时清空本账号的导师缓存 */
    clearMentors: () => setMentors([]),
  }
}
