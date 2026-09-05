import { reactive } from 'vue'
import { http } from './api'

/**
 * 系统品牌配置（meta.sys_config，运维中心·配置管理维护）。
 * 响应式对象：接口到位后登录页/侧栏自动更新；空值回退内置默认（i18n / 内置图形）。
 */
export const brand = reactive<Record<string, string>>({})

/** 取品牌值：未配置或空串返回 fallback（fallback 常用 i18n 默认）。 */
export function brandText(key: string, fallback = ''): string {
  const v = brand[key]
  return v && v.trim() ? v : fallback
}

/** 拉取公开品牌信息（后端白名单免鉴权）并应用到文档层（标签标题/favicon）。失败静默回退默认。 */
export async function loadBrand() {
  try {
    const d = await http.get<Record<string, string>>('/system/brand', { timeout: 6000 }).then(r => r.data)
    Object.keys(brand).forEach(k => delete brand[k])
    Object.assign(brand, d || {})
  } catch { /* 静默：页面照常用内置默认 */ }
  applyBrand()
}

function applyBrand() {
  const name = brandText('sys.name')
  if (name) document.title = name
  const icon = brandText('sys.icon')
  if (icon) {
    let link = document.querySelector<HTMLLinkElement>("link[rel='icon']")
    if (!link) { link = document.createElement('link'); link.rel = 'icon'; document.head.appendChild(link) }
    link.href = icon
  }
}
