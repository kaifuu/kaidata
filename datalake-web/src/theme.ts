// 全局主题管理：浅色(DIFY 风格) / 暗色(霓虹科技) 可切换，持久化到 localStorage。
// 切换通过给 <html> 加/去 `dark` 类驱动：Element Plus 暗色变量 + 本项目 --tech-* 变量
// 均以 html.dark 为开关。图表主题随之切换 tech-light / tech-dark。
import { computed, ref, watch } from 'vue'

export type ThemeMode = 'light' | 'dark'

const KEY = 'pharma_theme'

function load(): ThemeMode {
  const s = localStorage.getItem(KEY)
  return s === 'light' || s === 'dark' ? s : 'dark' // 默认暗色，保留既有科技感
}

function apply(m: ThemeMode) {
  document.documentElement.classList.toggle('dark', m === 'dark')
}

const mode = ref<ThemeMode>(load())
apply(mode.value) // 模块加载即应用，避免首屏闪烁

watch(mode, (m) => {
  localStorage.setItem(KEY, m)
  apply(m)
})

export const theme = {
  /** 当前主题模式（响应式） */
  mode,
  /** 是否暗色（响应式） */
  isDark: computed(() => mode.value === 'dark'),
  /** ECharts 主题名（响应式，供 v-chart :theme 绑定） */
  chartTheme: computed(() => (mode.value === 'dark' ? 'tech-dark' : 'tech-light')),
  toggle() {
    mode.value = mode.value === 'dark' ? 'light' : 'dark'
  },
  set(m: ThemeMode) {
    mode.value = m
  }
}
