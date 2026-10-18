import type { TokenUsage } from '../api/types'
import { formatTokens } from '../lib/labels'

type Props = {
  /** 本议题累计 token 用量 */
  usage?: TokenUsage | null
  /** 是否工作中（工作中显示脉冲点） */
  active?: boolean
}

/**
 * 议事厅顶部状态条的 token 统计徽标。
 *
 * <p>显示格式：
 * <ul>
 *   <li>有数据时：「● 3.2k tok」</li>
 *   <li>无数据 / total=0：隐藏</li>
 *   <li>悬停 tooltip 显示 input / output 拆分</li>
 * </ul>
 */
export function TokenUsageChip({ usage, active }: Props) {
  if (!usage) return null
  const total = usage.total ?? 0
  if (total <= 0) return null

  const inN = usage.input ?? 0
  const outN = usage.output ?? 0
  const label = `${formatTokens(total)} tok`
  const title = `输入 ${inN.toLocaleString()} · 输出 ${outN.toLocaleString()} · 合计 ${total.toLocaleString()} tokens`

  return (
    <span
      className={`chip token-chip${active ? ' is-active' : ''}`}
      title={title}
      aria-label={title}
    >
      {active && <span className="token-chip-dot" aria-hidden />}
      <span>{label}</span>
    </span>
  )
}