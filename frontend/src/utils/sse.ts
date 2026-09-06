/**
 * SSE 增量解析器（纯函数式，可单测）。
 * 遵循 SSE 规范的三个此前缺失的细节：
 *  1. 多行 data: 按规范用 \n 拼接成单条事件数据（此前逐行 append 会丢换行、破坏 JSON）；
 *  2. 事件边界以空行为准，dispatch 后 event 名回退为 message；
 *  3. `:` 开头的注释行、`id:`/`retry:` 字段正确忽略。
 * 流量入口是任意切碎的文本块（fetch reader 的 Uint8Array 解码结果），解析器内部缓冲半行。
 */
export interface SseEvent {
  event: string
  data: string
}

export class SseParser {
  private buf = ''
  private eventName = 'message'
  private dataLines: string[] = []

  /** 喂入一个文本块，返回其中完整的事件（0..n 个） */
  push(chunk: string): SseEvent[] {
    this.buf += chunk
    const events: SseEvent[] = []
    // 兼容 \r\n / \r / \n 三种行尾
    const lines = this.buf.split(/\r\n|\r|\n/)
    this.buf = lines.pop() ?? ''
    for (const line of lines) {
      if (line === '') {
        // 空行 = 事件边界
        if (this.dataLines.length > 0) {
          events.push({ event: this.eventName, data: this.dataLines.join('\n') })
        }
        this.eventName = 'message'
        this.dataLines = []
        continue
      }
      if (line.startsWith(':')) continue // 注释
      const colon = line.indexOf(':')
      const field = colon === -1 ? line : line.slice(0, colon)
      let value = colon === -1 ? '' : line.slice(colon + 1)
      if (value.startsWith(' ')) value = value.slice(1)
      if (field === 'event') {
        this.eventName = value || 'message'
      } else if (field === 'data') {
        this.dataLines.push(value)
      }
      // id / retry / 未知字段：忽略（当前协议未使用）
    }
    return events
  }
}
