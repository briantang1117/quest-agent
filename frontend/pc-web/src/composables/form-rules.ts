import type { FieldRule } from '@arco-design/web-vue'
import { displayNameError, passwordError, usernameError } from '@quest/shared'
export function validationRule(validate: (value: string) => string): FieldRule {
  return {
    validator: (value, callback) => {
      const error = validate(String(value ?? ''))
      callback(error || undefined)
    },
  }
}
export const usernameRule = validationRule(usernameError)
export const passwordRule = validationRule(passwordError)
export const displayNameRule = validationRule(displayNameError)
