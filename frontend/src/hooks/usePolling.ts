import { useEffect, useRef } from 'react'

/** 当 enabled 为 true 时按 interval 调用 callback；callback 变化不会重置计时器。 */
export function usePolling(
  callback: () => void | Promise<void>,
  intervalMs: number,
  enabled: boolean,
) {
  const cbRef = useRef(callback)
  cbRef.current = callback

  useEffect(() => {
    if (!enabled) return

    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | undefined

    const tick = async () => {
      try {
        await cbRef.current()
      } catch {
        // 轮询错误由调用方 toast；此处吞掉以免打断循环
      }
      if (!cancelled) {
        timer = setTimeout(tick, intervalMs)
      }
    }

    void tick()

    return () => {
      cancelled = true
      if (timer) clearTimeout(timer)
    }
  }, [intervalMs, enabled])
}
