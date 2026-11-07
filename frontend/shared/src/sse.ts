export interface SSEEvent {
  event: string
  data: string
  id?: string
}

/** Stateful SSE framing. Incomplete events at EOF are intentionally discarded. */
export function createSSEParser(onEvent: (event: SSEEvent) => void) {
  let line = ''
  let skipLF = false
  let first = true
  let event = ''
  let id: string | undefined
  let data: string[] = []
  function processLine() {
    if (line === '') {
      if (data.length)
        onEvent({
          event: event || 'message',
          data: data.join('\n'),
          ...(id === undefined ? {} : { id }),
        })
      data = []
      event = ''
    } else if (!line.startsWith(':')) {
      const colon = line.indexOf(':')
      const field = colon < 0 ? line : line.slice(0, colon)
      let value = colon < 0 ? '' : line.slice(colon + 1)
      if (value.startsWith(' ')) value = value.slice(1)
      if (field === 'data') data.push(value)
      if (field === 'event') event = value
      if (field === 'id' && !value.includes('\0')) id = value
    }
    line = ''
  }
  return {
    feed(chunk: string) {
      for (const char of chunk) {
        if (first) {
          first = false
          if (char === '﻿') continue
        }
        if (skipLF) {
          skipLF = false
          if (char === '\n') continue
        }
        if (char === '\r') {
          processLine()
          skipLF = true
        } else if (char === '\n') processLine()
        else line += char
      }
    },
    end() {
      line = ''
      data = []
      event = ''
    },
  }
}

export interface StreamOptions {
  token?: string
  signal?: AbortSignal
  fetcher?: typeof fetch
  onEvent: (event: SSEEvent) => void
}

export class IncompleteStreamError extends Error {
  readonly name = 'IncompleteStreamError'
  constructor() {
    super('SSE connection ended before a done event')
  }
}

/** No retries: replaying a POST can duplicate a paid operation. Errors reach the caller. */
export async function streamPost(
  url: string,
  body: unknown,
  options: StreamOptions,
): Promise<void> {
  const { signal } = options
  signal?.throwIfAborted()
  const headers = new Headers({ 'Content-Type': 'application/json', Accept: 'text/event-stream' })
  if (options.token) headers.set('Authorization', `Bearer ${options.token}`)
  const res = await (options.fetcher ?? fetch)(url, {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
    signal,
  })
  if (!res.ok) {
    await res.body?.cancel()
    throw new Error(`SSE HTTP ${res.status}`)
  }
  if (
    res.headers.get('content-type')?.split(';')[0]?.trim().toLowerCase() !== 'text/event-stream'
  ) {
    await res.body?.cancel()
    throw new Error('Expected text/event-stream')
  }
  if (!res.body) throw new Error('SSE response body is missing')
  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8', { fatal: true })
  let complete = false
  const parser = createSSEParser((event) => {
    if (complete) return
    if (event.event === 'error') throw new Error(`SSE error: ${event.data}`)
    if (event.event === 'done') complete = true
    options.onEvent(event)
  })
  const abort = () => {
    void reader.cancel().catch(() => {})
  }
  signal?.addEventListener('abort', abort, { once: true })
  try {
    signal?.throwIfAborted()
    while (true) {
      const { done, value } = await reader.read()
      signal?.throwIfAborted()
      if (done) break
      parser.feed(decoder.decode(value, { stream: true }))
      if (complete) return
    }
    parser.feed(decoder.decode())
    parser.end()
    if (!complete) throw new IncompleteStreamError()
  } finally {
    signal?.removeEventListener('abort', abort)
    try {
      await reader.cancel()
    } catch {
      /* Preserve the original stream/callback error. */
    }
    reader.releaseLock()
  }
}
