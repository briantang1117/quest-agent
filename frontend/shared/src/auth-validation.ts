export const PASSWORD_HINT = '12–64 个字符，UTF-8 不超过 72 字节；首尾空格也属于密码'
export function usernameError(value: string) {
  return /^[a-z][a-z0-9_]{2,31}$/.test(value)
    ? ''
    : '用户名须以小写字母开头，3–32 位，仅含小写字母、数字、下划线'
}
export function passwordError(value: string) {
  const length = Array.from(value).length
  return length >= 12 && length <= 64 && new TextEncoder().encode(value).length <= 72
    ? ''
    : PASSWORD_HINT
}
export function displayNameError(value: string) {
  const length = Array.from(value.trim()).length
  return length >= 1 && length <= 64 ? '' : '展示名去除首尾空格后须为 1–64 个字符'
}
