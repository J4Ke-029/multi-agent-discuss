import type { MemoryTargetType } from '../api/types'
import { Modal, ModalTitle } from './Modal'

/**
 * 结束会话第三步里的一格进度。**两轮共用这一格**，各自记各的状态。
 *
 * <p>拆成两组而不是两个数组：一位智能体在两轮里是同一个对象，分成两个列表会让
 * 「第 2 格失败了」在两轮里指向不同的人，进度条也就没法并排显示。
 */
export interface MemorySyncItem {
  /** 席位 id —— 重试时靠它定位是哪一个 */
  agentId: string
  name: string
  type: MemoryTargetType
  uuid: string

  /**
   * **知识轮**：把本场读过的文件存进它的常驻知识库。
   *
   * <p>{@code skipped} = 这个席位本场没有新文件（或它不是常驻席位），不参与这一轮。
   */
  docState: 'skipped' | 'pending' | 'running' | 'ok' | 'failed'
  /** 存进去的页数 */
  docPages?: number
  /** 说明性后缀，例如「摘要未生成」—— 摘要失败**不算这一格失败**，页知识已经在库里了 */
  docNote?: string
  docMessage?: string

  /** **心得轮**：让它回顾本场，重写自己的经验心得。{@code skipped} = 本场没发过言，没什么可总结。 */
  memState: 'skipped' | 'pending' | 'running' | 'ok' | 'failed'
  /** 心得轮失败原因（标红那格显示） */
  message?: string
  /** 成功后的心得字数（给用户一个「确实写了东西」的锚点） */
  chars?: number
}

type Round = 'docs' | 'memory'

type Props = {
  items: MemorySyncItem[]
  /** 当前跑的是哪一轮。文案与计数基数都跟着它走。 */
  phase: Round
  /** 有请求在途：此时禁用「重试」「跳过」和关闭 */
  running: boolean
  /** 重试某一格的某一轮 —— 两轮各可能失败，所以按钮要指明是哪一轮 */
  onRetry: (index: number, round: Round) => void
  /** 放弃剩下的格子，继续走「保存讨论总结」——不是直接归档 */
  onSkip: () => void
  /** 取消：保留会话，对齐总结弹窗的取消语义 */
  onCancel: () => void
}

/**
 * 结束会话第三步，**两轮**：
 *
 * <ol>
 *   <li><b>知识轮</b>「正在保存知识，阅读文件中…」—— 把本场读过的文件存进常驻知识库。</li>
 *   <li><b>心得轮</b>「正在总结经验，思考中…」—— 让在场者回顾本场，重写经验心得。</li>
 * </ol>
 *
 * <p><b>两轮无条件串行</b>：知识轮无论成败都立刻进心得轮。早先的判据是「全部成功才推进」，
 * 那会让用户攒了几场的心得因为**一个文件摘要失败**而整场作废 —— 知识是文件库、心得是经验，
 * 两者互不负责。同理，推进的判据是「没有 pending 也没有 running」（全部落定为终态），
 * 而不是「全部成功」：标红但用户不打算重试的格子不该把弹窗永远卡住。
 *
 * <p><b>为什么每轮都串行</b>：并发发出去做不出干净的 N/M —— 完成顺序与列表顺序对不上，
 * 「第 2 格失败了」这句话就没有意义；重试语义也会糊（哪个请求对应哪一格？）。
 */
export function MemorySyncDialog({ items, phase, running, onRetry, onSkip, onCancel }: Props) {
  const isDocs = phase === 'docs'
  const stateOf = (it: MemorySyncItem) => (isDocs ? it.docState : it.memState)
  // 计数基数用**本轮的目标格数**，不是全部格子：知识轮只覆盖本场有新文件的那几个，
  // 心得轮只覆盖本场发过言的那几个，两者的集合可以完全不同
  const targets = items.filter((it) => stateOf(it) !== 'skipped')
  const total = targets.length
  const done = targets.filter((it) => stateOf(it) === 'ok').length
  const failed = targets.filter((it) => stateOf(it) === 'failed').length
  const current = targets.find((it) => stateOf(it) === 'running')
  const anyFailed = items.some(
    (it) => it.docState === 'failed' || it.memState === 'failed',
  )

  return (
    <Modal
      className="save-dialog memory-sync-dialog"
      onClose={onCancel}
      closeOnEscape={false}
      closeOnOutside={false}
    >
        <ModalTitle asChild><h3>{isDocs ? '保存知识' : '总结经验'}</h3></ModalTitle>
        <p>
          {isDocs ? (
            <>
              正在把本场读过的文件存进这些常驻智能体的<strong>知识库</strong>。
              以后它们加入任何会话都会自动带上这些资料，<strong>不用重传</strong>。
            </>
          ) : (
            <>
              正在让本场参与的常驻智能体 / 导师回顾这次讨论，把新经验并进它们各自的记忆。
              下次再上场时它们会带着这些经验。
            </>
          )}
        </p>

        <div className="memory-sync-status" role="status" aria-live="polite">
          {running && current ? (
            <span className="memory-sync-spinner" aria-hidden />
          ) : failed > 0 ? (
            <span className="memory-sync-dot is-failed" aria-hidden />
          ) : (
            <span className="memory-sync-dot is-done" aria-hidden />
          )}
          <strong>
            {running
              ? isDocs
                ? `正在保存知识，阅读文件中… ${done}/${total}`
                : `正在总结经验，思考中… ${done}/${total}`
              : failed > 0
                ? `完成 ${done}/${total}，${failed} 位失败`
                : `已全部完成（${total}/${total}）`}
          </strong>
        </div>

        <div className="memory-sync-list">
          {items.map((it, i) => {
            const st = stateOf(it)
            return (
              <div key={it.agentId} className={`memory-sync-row is-${st}`}>
                <span className="memory-sync-name">
                  {it.name}
                  <em className="memory-sync-kind">{it.type === 'MENTOR' ? '导师' : '常驻'}</em>
                </span>

                <span className="memory-sync-cell">
                  {/* 知识片段：本轮不参与的格子（本场没传文件）整段不渲染 */}
                  {!isDocs && it.docState !== 'skipped' && (
                    <span className="memory-sync-cell-part">
                      {it.docState === 'pending' && <span className="muted">知识排队中</span>}
                      {it.docState === 'running' && <span className="muted">存知识…</span>}
                      {it.docState === 'ok' && (
                        <span className="memory-sync-ok">
                          ✓ 知识 {it.docPages ?? 0} 页
                          {it.docNote ? `（${it.docNote}）` : ''}
                        </span>
                      )}
                      {it.docState === 'failed' && (
                        <>
                          <span className="memory-sync-fail" title={it.docMessage}>
                            知识：{it.docMessage || '保存失败'}
                          </span>
                          <button
                            type="button"
                            className="btn btn-ghost btn-sm"
                            disabled={running}
                            onClick={() => onRetry(i, 'docs')}
                          >
                            重试
                          </button>
                        </>
                      )}
                    </span>
                  )}

                  {/* 心得片段：本轮不参与的格子（本场没发过言）整段不渲染 */}
                  {isDocs || it.memState === 'skipped' ? null : (
                    <span className="memory-sync-cell-part">
                      {it.memState === 'pending' && <span className="muted">心得排队中</span>}
                      {it.memState === 'running' && <span className="muted">思考中…</span>}
                      {it.memState === 'ok' && (
                        <span className="memory-sync-ok">
                          ✓ 心得{typeof it.chars === 'number' ? ` ${it.chars} 字` : '已更新'}
                        </span>
                      )}
                      {it.memState === 'failed' && (
                        <>
                          <span className="memory-sync-fail" title={it.message}>
                            心得：{it.message || '总结失败'}
                          </span>
                          <button
                            type="button"
                            className="btn btn-ghost btn-sm"
                            disabled={running}
                            onClick={() => onRetry(i, 'memory')}
                          >
                            重试
                          </button>
                        </>
                      )}
                    </span>
                  )}
                </span>
              </div>
            )
          })}
        </div>

        <p className="muted memory-sync-foot">
          {isDocs
            ? '知识轮结束后会继续总结经验，两者互不影响 —— 存知识失败不会让这一场的心得也作废。'
            : '失败的格子不影响结束会话 —— 跳过它，其它人的经验照样保存。'}
        </p>

        <div className="save-dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onCancel} disabled={running}>
            取消
          </button>
          <button type="button" className="btn btn-accent" onClick={onSkip} disabled={running}>
            {anyFailed ? '跳过并结束' : '继续'}
          </button>
        </div>
    </Modal>
  )
}
