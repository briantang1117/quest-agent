import { describe, expect, it } from 'vitest'
import { authRedirect, safeNext } from '../src/auth-policy'
import { passwordError, usernameError, displayNameError } from '../src/auth-validation'
import type { UserProfile } from '../src/auth'
const student: UserProfile = {
  id: '1',
  username: 'student',
  displayName: '学员',
  role: 'student',
  status: 'active',
  mustChangePassword: false,
  createdAt: '2026-11-07T00:00:00Z',
  lastLoginAt: null,
}
const admin = { ...student, role: 'admin' as const }
describe('授权路由策略', () => {
  it('未登录去login并保留站内next', () => {
    expect(authRedirect(null, '/practice/agent')).toEqual({
      path: '/login',
      query: { next: '/practice/agent' },
    })
    expect(authRedirect(null, '/login')).toBeNull()
  })
  it('必须改密只允许改密页面，不开放诊断页', () => {
    const user = { ...student, mustChangePassword: true }
    for (const path of ['/login', '/practice', '/admin/users', '/dev/connection', '/missing'])
      expect(authRedirect(user, path)).toBe('/change-password')
    expect(authRedirect(user, '/change-password')).toBeNull()
  })
  it('Admin与student互斥，旧admin Agent不再可达', () => {
    expect(authRedirect(admin, '/practice')).toBe('/admin/users')
    expect(authRedirect(admin, '/practice/agent')).toBe('/admin/users')
    expect(authRedirect(admin, '/admin/agent')).toBe('/admin/users')
    expect(authRedirect(student, '/admin/users')).toBe('/practice')
    expect(authRedirect(student, '/admin/agent')).toBe('/practice')
    expect(authRedirect(admin, '/admin/users')).toBeNull()
    expect(authRedirect(student, '/practice/agent')).toBeNull()
    expect(authRedirect(admin, '/login')).toBe('/admin/users')
    expect(authRedirect(student, '/')).toBe('/practice')
    expect(authRedirect({ ...student, status: 'disabled' }, '/practice')).toEqual({
      path: '/login',
      query: { next: '/practice' },
    })
  })
  it('公共库及审核仅对Admin开放，仍拒绝任意后台路径和编码绕过', () => {
    for (const path of [
      '/admin/banks',
      '/admin/banks/12345678-1234-1234-1234-123456789abc',
      '/admin/questions/12345678-1234-1234-1234-123456789abc',
      // 系统日志与用户管理并列，Admin 必须可达，普通用户必须被挡回学习空间。
      '/admin/logs',
    ]) {
      expect(authRedirect(admin, path)).toBeNull()
      expect(authRedirect(student, path)).toBe('/practice')
    }
    // 日志只开放精确路径，不能借前缀顺带放开其他后台端点。
    for (const path of ['/admin/logs/1', '/admin/logs-export', '/admin/log'])
      expect(authRedirect(admin, path)).toBe('/admin/users')
    for (const path of [
      '/admin/questions/../users',
      '/admin/questions/%2fsecret',
      '/admin/private-banks',
    ])
      expect(authRedirect(admin, path)).toBe('/admin/users')
  })
  it('next只允许显式授权站内路由，拒绝开放重定向与编码绕过', () => {
    for (const path of [
      'https://evil.test',
      '//evil.test',
      '/\\evil.test',
      '/%2f%2fevil.test',
      '/admin/users',
      '/practice/../admin/users',
      '/practice/not-real',
      '/login',
      ['/practice'],
      '/practice?next=https://evil.test',
      '/practice#x',
    ])
      expect(safeNext(student, path)).toBe('/practice')
    expect(safeNext(student, '/practice/agent')).toBe('/practice/agent')
    expect(safeNext(admin, '/practice/agent')).toBe('/admin/users')
    expect(safeNext({ ...student, mustChangePassword: true }, '/practice/agent')).toBe(
      '/change-password',
    )
  })
})
describe('账号输入边界', () => {
  it('用户名严格小写ASCII且3到32位', () => {
    expect(usernameError('a_1')).toBe('')
    expect(usernameError('a'.repeat(32))).toBe('')
    for (const value of ['ab', 'Aaa', '_abc', '1abc', '中文名', ' abc', 'a'.repeat(33)])
      expect(usernameError(value)).not.toBe('')
  })
  it('口令12到64字符并且UTF8不超过72字节，不trim', () => {
    for (const value of ['a'.repeat(12), 'a'.repeat(64), '密'.repeat(24), ' '.repeat(12)])
      expect(passwordError(value)).toBe('')
    for (const value of ['a'.repeat(11), 'a'.repeat(65), '密'.repeat(25), '😀'.repeat(19)])
      expect(passwordError(value)).not.toBe('')
  })
  it('展示名按去首尾空格后1到64字符验证', () => {
    expect(displayNameError(' 学员 ')).toBe('')
    expect(displayNameError(' '.repeat(3))).not.toBe('')
    expect(displayNameError('a'.repeat(65))).not.toBe('')
  })
})
