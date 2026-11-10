import 'vue-router'

declare module 'vue-router' {
  interface RouteMeta {
    pageDeepth?: number
    transientQueryKeys?: string[]
  }
}
