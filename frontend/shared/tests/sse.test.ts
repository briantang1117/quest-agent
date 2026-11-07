import { describe, expect, it, vi } from 'vitest'
import { createSSEParser, streamPost } from '../src/sse'

const text = '：中文 😀'
const wire = `: ping\r\nid: 7\r\nevent: token\r\ndata: ${text}\r\ndata: second\r\n\r\nevent: done\ndata: {}\n\n`

describe('SSE parser', () => {
  it('handles arbitrary partitions, CRLF, comments and multiline data', () => {
    for (let split = 0; split <= wire.length; split++) {
      const events: unknown[] = []
      const parser = createSSEParser(e => events.push(e))
      parser.feed(wire.slice(0, split)); parser.feed(wire.slice(split)); parser.end()
      expect(events).toEqual([{ event: 'token', data: `${text}\nsecond`, id: '7' }, { event: 'done', data: '{}', id: '7' }])
    }
  })
  it('supports bare CR, default events, empty data and ignores incomplete final events', () => {
    const events: unknown[] = []
    const parser = createSSEParser(e => events.push(e))
    parser.feed('retry: 1\r: comment\rdata:\r\rdata: incomplete')
    parser.end()
    expect(events).toEqual([{ event: 'message', data: '' }])
  })
  it('resets event type, ignores NUL ids and strips only one space', () => {
    const events: unknown[] = []
    const parser = createSSEParser(e => events.push(e))
    parser.feed('id: a\ndata: x\nevent: custom\n\nid: bad\0id\ndata:  y\n\n')
    expect(events).toEqual([{ id: 'a', event: 'custom', data: 'x' }, { id: 'a', event: 'message', data: ' y' }])
  })
})

function response(bytes: Uint8Array[], headers = { 'Content-Type': 'text/event-stream; charset=utf-8' }) {
  return new Response(new ReadableStream({ start(controller) { bytes.forEach(b => controller.enqueue(b)); controller.close() } }), { headers })
}

describe('fetch POST stream', () => {
  it('decodes UTF8 across individual bytes and sends JSON plus bearer token', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(response([...new TextEncoder().encode(wire)].map(n => new Uint8Array([n]))))
    const events: unknown[] = []
    await streamPost('/messages', { text: '你好' }, { token: 'secret', fetcher, onEvent: e => events.push(e) })
    expect(events).toHaveLength(2)
    expect(events[0]).toEqual({ id: '7', event: 'token', data: `${text}\nsecond` })
    const init = fetcher.mock.calls[0]![1]!
    expect(init.method).toBe('POST')
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer secret')
    expect(JSON.parse(init.body as string)).toEqual({ text: '你好' })
  })
  it('resolves and cancels immediately on done even when socket remains open', async () => {
    const cancel = vi.fn()
    await streamPost('/x', {}, { fetcher: async () => new Response(new ReadableStream({ start(c) { c.enqueue(new TextEncoder().encode('event: done\ndata: {}\n\n')) }, cancel }), { headers: { 'Content-Type': 'text/event-stream' } }), onEvent() {} })
    expect(cancel).toHaveBeenCalledOnce()
  })
  it('rejects error and never dispatches later token packets', async () => {
    const events: unknown[] = []
    await expect(streamPost('/x', {}, { fetcher: async () => response([new TextEncoder().encode('event: error\ndata: {"message":"failed"}\n\n'), new TextEncoder().encode('event: token\ndata: late\n\n')]), onEvent: e => events.push(e) })).rejects.toThrow('failed')
    expect(events).toEqual([])
  })
  it('rejects EOF without a done event as incomplete', async () => {
    await expect(streamPost('/x', {}, { fetcher: async () => response([new TextEncoder().encode('data: partial\n\n')]), onEvent() {} })).rejects.toMatchObject({ name: 'IncompleteStreamError' })
  })
  it('propagates network errors without retry', async () => {
    const fetcher = vi.fn<typeof fetch>().mockRejectedValue(new TypeError('offline'))
    await expect(streamPost('/x', {}, { fetcher, onEvent() {} })).rejects.toThrow('offline')
    expect(fetcher).toHaveBeenCalledTimes(1)
  })
  it('rejects non-success HTTP without retry', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response('no', { status: 401 }))
    await expect(streamPost('/x', {}, { fetcher, onEvent() {} })).rejects.toThrow('401')
    expect(fetcher).toHaveBeenCalledTimes(1)
  })
  it('rejects non-stream content and missing bodies', async () => {
    await expect(streamPost('/x', {}, { fetcher: async () => new Response('{}'), onEvent() {} })).rejects.toThrow('text/event-stream')
    await expect(streamPost('/x', {}, { fetcher: async () => new Response(null, { headers: { 'Content-Type': 'text/event-stream' } }), onEvent() {} })).rejects.toThrow('body')
  })
  it('rejects pre-aborted requests before fetch', async () => {
    const controller = new AbortController(); controller.abort()
    const fetcher = vi.fn<typeof fetch>()
    await expect(streamPost('/x', {}, { signal: controller.signal, fetcher, onEvent() {} })).rejects.toMatchObject({ name: 'AbortError' })
    expect(fetcher).not.toHaveBeenCalled()
  })
  it('aborts a pending read and cancels the reader', async () => {
    const controller = new AbortController()
    const cancel = vi.fn()
    const promise = streamPost('/x', {}, { signal: controller.signal, fetcher: async () => new Response(new ReadableStream({ cancel }), { headers: { 'Content-Type': 'text/event-stream' } }), onEvent() {} })
    await Promise.resolve(); await Promise.resolve(); controller.abort()
    await expect(promise).rejects.toMatchObject({ name: 'AbortError' })
    expect(cancel).toHaveBeenCalledOnce()
  })
  it('propagates reader errors and callback failures', async () => {
    const broken = new ReadableStream({ start(c) { c.error(new Error('connection lost')) } })
    await expect(streamPost('/x', {}, { fetcher: async () => new Response(broken, { headers: { 'Content-Type': 'text/event-stream' } }), onEvent() {} })).rejects.toThrow('connection lost')
    await expect(streamPost('/x', {}, { fetcher: async () => response([new TextEncoder().encode('data: x\n\n')]), onEvent() { throw new Error('handler failed') } })).rejects.toThrow('handler failed')
  })
})
