// 必须在Arco的CommonJS模块加载前提供浏览器API，避免菜单加载无原生API的polyfill。
// jsdom不计算布局，尺寸/视觉行为另用真实浏览器验收。
if (typeof window !== 'undefined') window.scrollTo = () => {}
globalThis.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
}
