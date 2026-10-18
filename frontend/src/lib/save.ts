/**
 * 把 Markdown 存到**用户自己选的位置**。
 *
 * 优先用浏览器原生的「另存为」对话框（File System Access API）——用户可选任意目录、
 * 改文件名。该 API 要求安全上下文（localhost 与 HTTPS 都算），目前 Chrome / Edge 支持；
 * Firefox / Safari 没有，那种情况回退成 Blob 下载（落进浏览器下载目录）。
 */

/**
 * File System Access API 的最小类型声明。
 *
 * 它不在 TypeScript 的标准 DOM lib 里（是 WICG 规范），这里只按用到的部分声明，
 * 免得往全局 Window 上挂一堆 declare。
 */
type SaveFilePicker = (options?: {
  suggestedName?: string
  types?: { description?: string; accept: Record<string, string[]> }[]
}) => Promise<{
  createWritable: () => Promise<{
    write: (data: BlobPart) => Promise<void>
    close: () => Promise<void>
  }>
}>

function saveFilePicker(): SaveFilePicker | undefined {
  return (window as unknown as { showSaveFilePicker?: SaveFilePicker }).showSaveFilePicker
}

/**
 * 弹出「另存为」让用户决定存哪儿。
 *
 * @returns true = 已写入用户选的位置（或已触发下载）；false = 用户取消了对对话框。
 *          取消是正常操作、不是失败，调用方不用报错。
 * @throws 磁盘写入失败等真实错误照常抛出，由调用方展示。
 */
export async function saveMarkdownAs(fileName: string, markdown: string): Promise<boolean> {
  const pick = saveFilePicker()
  if (!pick) {
    downloadMarkdown(fileName, markdown)
    return true
  }
  try {
    const handle = await pick({
      suggestedName: fileName,
      types: [{ description: 'Markdown', accept: { 'text/markdown': ['.md'] } }],
    })
    const writable = await handle.createWritable()
    await writable.write(markdown)
    await writable.close()
    return true
  } catch (err) {
    // 用户点了「取消」——按规范抛 AbortError，不当错误处理
    if (err instanceof DOMException && err.name === 'AbortError') {
      return false
    }
    throw err
  }
}

/** 回退路径：Blob 下载，落到浏览器下载目录 */
function downloadMarkdown(fileName: string, markdown: string) {
  const blob = new Blob([markdown], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}
