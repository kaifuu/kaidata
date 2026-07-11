// 全局语言管理：中文(zh-CN,默认) / 英文(en-US) 可切换，持久化到 localStorage。
// 仿 theme.ts 范式：ref + watch + localStorage + 模块加载即应用。
// - 模板内用 $t('key')（globalInjection）；setup 内用 useI18n()；非组件模块用此处导出的 t。
// - axios 请求拦截器读取 locale.value 注入 Accept-Language 头，后端据此返回对应语言消息。
import { computed, watch } from 'vue'
import { createI18n } from 'vue-i18n'
import zhCN from './zh-CN'
import enUS from './en-US'

export type LocaleLang = 'zh-CN' | 'en-US'

const KEY = 'pharma_locale'
const ALL: LocaleLang[] = ['zh-CN', 'en-US']

function load(): LocaleLang {
  const s = localStorage.getItem(KEY)
  return s === 'en-US' ? 'en-US' : 'zh-CN' // 默认中文
}

const i18n = createI18n({
  legacy: false,
  globalInjection: true, // 模板可直接用 $t / $tc
  locale: load(),
  fallbackLocale: 'zh-CN',
  messages: {
    'zh-CN': zhCN,
    'en-US': enUS
  }
})

// 响应式 locale：get/set 双向，set 时同步写 localStorage（与 theme.ts 一致）
export const locale = computed<LocaleLang>({
  get: () => i18n.global.locale.value as LocaleLang,
  set: (v) => {
    i18n.global.locale.value = v
    localStorage.setItem(KEY, v)
  }
})

export function setLocale(v: LocaleLang) {
  locale.value = v
}

export function toggleLocale() {
  locale.value = locale.value === 'zh-CN' ? 'en-US' : 'zh-CN'
}

// 供非组件模块（api.ts / router 等）使用的 t；组件内优先用 useI18n()
export function t(key: string, args?: Record<string, any>): string {
  return args ? i18n.global.t(key, args as any) : i18n.global.t(key)
}

// 菜单 key 规范化：去掉前导 '/'，供 menu/menuGroup 字典查找
export function menuKey(p: string | undefined | null): string {
  return (p || '').replace(/^\//, '')
}

export const locales = ALL

export default i18n

// watch 仅用于可能的副作用钩子（localStorage 已在 setter 写入，这里保留与 theme.ts 结构对齐）
watch(locale, () => { /* 持久化已在 setter 完成 */ })
