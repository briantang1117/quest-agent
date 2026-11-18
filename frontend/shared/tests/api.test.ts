import { describe, expect, it } from 'vitest'
import { createApiClient, ApiError } from '../src/api'
import type { AxiosAdapter } from 'axios'

function adapter(data: unknown, status = 200): AxiosAdapter {
  return async (config) => ({ data, status, statusText: 'OK', headers: {}, config })
}

describe('API client', () => {
  it.each([
    'text/markdown; charset=utf-8',
    'application/pdf',
    'text/plain',
    'image/png',
    'image/jpeg',
    'image/webp',
  ])('鉴权下载接受约定正文 %s', async (contentType) => {
    const blob = new Blob(['# 合成资料'], { type: contentType })
    const client = createApiClient({ baseURL: '/gateway', getToken: () => 'token' })
    let authorization: unknown
    client.defaults.adapter = async (config) => {
      authorization = config.headers.get('Authorization')
      return {
        data: blob,
        status: 200,
        statusText: 'OK',
        headers: { 'content-type': contentType },
        config,
      }
    }
    expect(await client.get('/download', { responseType: 'blob' })).toBe(blob)
    expect(authorization).toBe('Bearer token')
  })
  it('Markdown下载错误envelope保留业务错误而非另存成文件', async () => {
    const client = createApiClient({ baseURL: '/gateway' })
    client.defaults.adapter = adapter(
      new Blob(
        [JSON.stringify({ code: 40300, message: '拒绝', data: null, traceId: 'download-trace' })],
        { type: 'application/json' },
      ),
    )
    await expect(client.get('/download', { responseType: 'blob' })).rejects.toMatchObject({
      code: 40300,
      traceId: 'download-trace',
    })
  })
  it('拒绝任意HTML blob伪装资料', async () => {
    const client = createApiClient({ baseURL: '/gateway' })
    client.defaults.adapter = adapter(new Blob(['<html>错误页</html>'], { type: 'text/html' }))
    await expect(client.get('/download', { responseType: 'blob' })).rejects.toBeInstanceOf(ApiError)
  })
  it('unwraps code 0 and reads the current in-memory token per request', async () => {
    let token = 'first'
    const client = createApiClient({ baseURL: 'http://localhost:8080', getToken: () => token })
    client.defaults.adapter = async (config) => ({
      data: {
        code: 0,
        message: 'ok',
        data: { auth: config.headers.get('Authorization') },
        traceId: 't',
      },
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    })
    expect(await client.get('/api/v1/system/info')).toEqual({ auth: 'Bearer first' })
    token = 'second'
    expect(await client.get('/api/v1/system/info')).toEqual({ auth: 'Bearer second' })
  })
  it('preserves business code, message and traceId', async () => {
    const client = createApiClient({ baseURL: 'http://localhost:8080' })
    client.defaults.adapter = adapter({
      code: 60001,
      message: '尚未配置',
      data: null,
      traceId: 'trace-7',
    })
    await expect(client.get('/x')).rejects.toMatchObject({
      name: 'ApiError',
      code: 60001,
      message: '尚未配置',
      traceId: 'trace-7',
    })
  })
  it('normalizes HTTP authentication errors', async () => {
    const client = createApiClient({ baseURL: 'http://localhost:8080' })
    client.defaults.adapter = async (config) => {
      throw {
        isAxiosError: true,
        config,
        response: {
          status: 401,
          data: { code: 40100, message: '登录已失效', traceId: 'auth-trace' },
        },
      }
    }
    await expect(client.get('/x')).rejects.toMatchObject({
      status: 401,
      code: 40100,
      traceId: 'auth-trace',
      message: '登录已失效',
    })
  })
  it('rejects malformed envelopes', async () => {
    const client = createApiClient({ baseURL: 'http://localhost:8080' })
    client.defaults.adapter = adapter({ hello: true })
    await expect(client.get('/x')).rejects.toBeInstanceOf(ApiError)
  })
  it('reports network failures without retry', async () => {
    const client = createApiClient({ baseURL: 'http://localhost:8080' })
    let calls = 0
    client.defaults.adapter = async () => {
      calls++
      throw new Error('offline')
    }
    await expect(client.get('/x')).rejects.toThrow('网络连接失败')
    expect(calls).toBe(1)
  })
  it('refuses missing configuration before issuing any request', () => {
    expect(() => createApiClient({ baseURL: '' })).toThrow('VITE_API_BASE_URL')
  })
})
