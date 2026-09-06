import { describe, expect, it } from 'vitest'
import { SseParser } from '../sse'

describe('SseParser', () => {
  it('parses a complete event', () => {
    const p = new SseParser()
    expect(p.push('event: token\ndata: hello\n\n')).toEqual([{ event: 'token', data: 'hello' }])
  })

  it('buffers partial lines across chunks', () => {
    const p = new SseParser()
    expect(p.push('event: to')).toEqual([])
    expect(p.push('ken\ndata: he')).toEqual([])
    expect(p.push('llo\n\n')).toEqual([{ event: 'token', data: 'hello' }])
  })

  it('joins multi-line data with newline per SSE spec', () => {
    const p = new SseParser()
    const events = p.push('data: line1\ndata: line2\n\n')
    expect(events).toEqual([{ event: 'message', data: 'line1\nline2' }])
  })

  it('handles CRLF line endings', () => {
    const p = new SseParser()
    expect(p.push('event: done\r\ndata: {"ok":true}\r\n\r\n')).toEqual([
      { event: 'done', data: '{"ok":true}' },
    ])
  })

  it('resets event name after dispatch', () => {
    const p = new SseParser()
    p.push('event: token\ndata: a\n\n')
    expect(p.push('data: b\n\n')).toEqual([{ event: 'message', data: 'b' }])
  })

  it('ignores comment lines and keeps buffer for empty events', () => {
    const p = new SseParser()
    expect(p.push(': keep-alive\n\n')).toEqual([])
    expect(p.push('event: trace\ndata: {"tool":"x"}\n\n')).toEqual([
      { event: 'trace', data: '{"tool":"x"}' },
    ])
  })

  it('emits no event for data without terminating blank line', () => {
    const p = new SseParser()
    expect(p.push('data: incomplete')).toEqual([])
    expect(p.push('\n\n')).toEqual([{ event: 'message', data: 'incomplete' }])
  })
})
