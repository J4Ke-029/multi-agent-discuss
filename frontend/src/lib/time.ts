/**
 * 时间展示工具。
 *
 * 后端所有时间都是 `Instant.toString()` 的 ISO-8601 UTC 形态（带 `Z` 后缀），
 * `new Date()` 会按浏览器本地时区换算，所以这里只决定「显示到什么精度」。
 * 传 null / undefined / 非法字符串一律返回空串，调用方不必自己判空。
 */

/** 完整时间：`2026-09-15 20:20`。归档页头部、讨论笔录用 */
export function formatDateTime(iso: string | null | undefined): string {
  const d = parse(iso)
  if (!d) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/**
 * 同上，但入参是 **epoch 毫秒**。
 *
 * 少数接口回的是毫秒时间戳而不是 ISO 串（如心得更新时间的 `memoryUpdatedAt`），
 * 调用方不该为了用它自己去 `new Date(ms).toISOString()` 绕一圈。
 */
export function formatDateTimeMs(ms: number | null | undefined): string {
  if (ms == null || !Number.isFinite(ms)) return ''
  return formatDateTime(new Date(ms).toISOString())
}

/**
 * 相对日期：今天只给 `HH:mm`，今年内的更早日期给 `MM-DD`，跨年补上年份。
 *
 * 侧栏对话列表用它——列表要紧凑，但跨年时只写 `09-13` 会让两年的同日分不清，
 * 所以超过今年一律带上年份。
 */export function formatRelativeDay(iso: string | null | undefined): string {
  const d = parse(iso)
  if (!d) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  const now = new Date()
  if (d.toDateString() === now.toDateString()) {
    return `${pad(d.getHours())}:${pad(d.getMinutes())}`
  }
  if (d.getFullYear() === now.getFullYear()) {
    return `${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
  }
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/**
 * 只要时刻：`20:20`。用于同一次讨论内逐条发言的时间戳——
 * 一次讨论内的日期都一样，逐条重复「2026-09-15」只是噪声。
 */
export function formatClock(iso: string | null | undefined): string {
  const d = parse(iso)
  if (!d) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function parse(iso: string | null | undefined): Date | null {
  if (!iso) return null
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? null : d
}
