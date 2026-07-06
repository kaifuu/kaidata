<template>
  <div class="overview">
    <!-- KPI 卡片：浅色 DIFY 极简 / 暗色霓虹科技 + 数字滚动 -->
    <div class="kpi-grid">
      <div class="kpi-card" v-for="k in kpis" :key="k.label">
        <span class="corner tl" /><span class="corner br" />
        <div class="kpi-icon" :style="chipStyle(k)">
          <el-icon :size="22"><component :is="k.icon" /></el-icon>
        </div>
        <div class="kpi-info">
          <div class="kpi-val">{{ display[k.label] ?? 0 }}</div>
          <div class="kpi-lab">{{ k.label }}</div>
        </div>
      </div>
    </div>

    <!-- 图表区 -->
    <div class="chart-row">
      <div class="dl-card chart-card">
        <div class="ct"><el-icon><Connection /></el-icon> 数据源类型分布<span class="ct-sub">DATA SOURCES</span></div>
        <v-chart :option="dsTypeOption" :theme="chartTheme" autoresize class="chart" />
      </div>
      <div class="dl-card chart-card">
        <div class="ct"><el-icon><List /></el-icon> 各域任务数<span class="ct-sub">TASKS</span></div>
        <v-chart :option="taskDomainOption" :theme="chartTheme" autoresize class="chart" />
      </div>
    </div>
    <div class="chart-row">
      <div class="dl-card chart-card">
        <div class="ct"><el-icon><CircleCheck /></el-icon> 任务执行成功率<span class="ct-sub">SUCCESS RATE</span></div>
        <v-chart :option="successOption" :theme="chartTheme" autoresize class="chart" />
      </div>
      <div class="dl-card chart-card">
        <div class="ct"><el-icon><Box /></el-icon> 资产状态分布<span class="ct-sub">ASSETS</span></div>
        <v-chart :option="assetOption" :theme="chartTheme" autoresize class="chart" />
      </div>
    </div>

    <div class="flow-hint"><el-icon><InfoFilled /></el-icon> 数据中台总览 · 全域运行态势（每 15s 刷新）</div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { Connection, List, CircleCheck, Box, InfoFilled } from '@element-plus/icons-vue'
import { VChart } from '@/echarts'
import { api } from '@/api'
import { theme } from '@/theme'

const chartTheme = theme.chartTheme
const isDark = theme.isDark
const ov = ref<any>({})
const taskStats = ref<any>({})
const dsStats = ref<any[]>([])

const taskTotal = computed(() => ((ov.value.profileJobs ?? 0) + (ov.value.qualityRules ?? 0) + (ov.value.workflows ?? 0) + (ov.value.scripts ?? 0) + (ov.value.exports ?? 0)))
const dsCalls = computed(() => (dsStats.value || []).reduce((s: number, d: any) => s + (Number(d.calls) || 0), 0))

// 主题感知的图表色 token：浅色走 DIFY（靛蓝/柔和），暗色走精简霓虹
const C = computed(() => isDark.value ? {
  ink: '#d6e6ff', muted: '#7f93bf',
  split: 'rgba(0,224,255,0.08)', axis: 'rgba(0,224,255,0.30)',
  panelBorder: '#0a1430', glow: 'rgba(0,224,255,0.50)',
  primary: '#00e0ff', primary2: '#2f6bff',
  success: '#2ee6a6', warn: '#ffb020', danger: '#ff4d6d',
  palette: ['#00e0ff', '#2f6bff', '#7c5cff', '#2ee6a6', '#ffb020', '#ff4d6d']
} : {
  ink: '#101828', muted: '#667085',
  split: '#f2f4f7', axis: '#e9ebf0',
  panelBorder: '#ffffff', glow: 'rgba(21,87,239,0.16)',
  primary: '#1557ef', primary2: '#4f46e5',
  success: '#16b364', warn: '#f79009', danger: '#f04438',
  palette: ['#1557ef', '#4f46e5', '#7c5cff', '#16b364', '#f79009', '#f04438']
})

const grad = (c1: string, c2: string) => ({ type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: c1 }, { offset: 1, color: c2 }] })
// 数据填色：浅色纯色（DIFY 克制），暗色渐变 + 轻发光
const fill = (i: number) => {
  const p = C.value.palette
  return isDark.value ? grad(p[i % p.length], p[(i + 2) % p.length]) : p[i % p.length]
}

const kpis = computed(() => [
  { label: '数据源', value: ov.value.datasources ?? 0, icon: 'Connection', color: '#1557ef', grad: 'linear-gradient(135deg,#00e0ff,#2f6bff)' },
  { label: '元数据表', value: ov.value.metaTables ?? 0, icon: 'Files', color: '#7c5cff', grad: 'linear-gradient(135deg,#7c5cff,#00e0ff)' },
  { label: '数据资产', value: ov.value.assets ?? 0, icon: 'Box', color: '#16b364', grad: 'linear-gradient(135deg,#2ee6a6,#00e0ff)' },
  { label: '任务总数', value: taskTotal.value, icon: 'List', color: '#f79009', grad: 'linear-gradient(135deg,#ffb020,#ff4d6d)' },
  { label: '工作流', value: ov.value.workflows ?? 0, icon: 'Connection', color: '#4f46e5', grad: 'linear-gradient(135deg,#2f6bff,#7c5cff)' },
  { label: '服务调用', value: dsCalls.value, icon: 'Promotion', color: '#0ba5ec', grad: 'linear-gradient(135deg,#00e0ff,#2ee6a6)' }
])

// KPI 图标 CSS 变量（浅色柔和淡底主色 / 暗色渐变，由 .kpi-icon 样式消费）
const chipStyle = (k: { color: string; grad: string }): Record<string, string> => ({
  '--chip': k.color, '--chip-grad': k.grad
})

// 数字滚动（ease-out）
const display = reactive<Record<string, number>>({})
function roll(label: string, to: number) {
  const start = display[label] ?? 0
  if (start === to) { display[label] = to; return }
  const t0 = performance.now(); const dur = 800
  const step = (t: number) => {
    const p = Math.min((t - t0) / dur, 1)
    display[label] = Math.round(start + (to - start) * (1 - Math.pow(1 - p, 3)))
    if (p < 1) requestAnimationFrame(step)
  }
  requestAnimationFrame(step)
}
watch(kpis, (ks) => ks.forEach((k) => roll(k.label, k.value)), { deep: true, immediate: true })

// 数据源类型分布（精致环图 + 中心总数）
const dsTypeOption = computed(() => {
  const c = C.value
  const total = (ov.value.datasourceByType || []).reduce((s: number, d: any) => s + Number(d.c || 0), 0)
  return {
    tooltip: { trigger: 'item' },
    legend: { bottom: 4, textStyle: { color: c.muted }, itemWidth: 10, itemHeight: 10 },
    graphic: [
      { type: 'text', left: 'center', top: '40%', style: { text: String(total), fill: c.ink, font: 'bold 30px system-ui, sans-serif', textAlign: 'center' } },
      { type: 'text', left: 'center', top: '52%', style: { text: '数据源', fill: c.muted, font: '12px system-ui, sans-serif', textAlign: 'center' } }
    ],
    series: [{
      type: 'pie', radius: ['52%', '72%'], center: ['50%', '46%'],
      itemStyle: { borderColor: c.panelBorder, borderWidth: 2, shadowBlur: isDark.value ? 16 : 0, shadowColor: c.glow },
      label: { color: c.muted, formatter: '{b} {c}' }, labelLine: { lineStyle: { color: c.axis } },
      emphasis: { itemStyle: { shadowBlur: isDark.value ? 28 : 8, shadowColor: c.glow } },
      data: (ov.value.datasourceByType || []).map((d: any, i: number) => ({ name: d.type, value: d.c, itemStyle: { color: fill(i) } }))
    }]
  }
})

// 各域任务数（圆角柱 · 浅色纯色 / 暗色渐变发光）
const taskDomainOption = computed(() => {
  const c = C.value
  const domains = ['探查', '质量', '工作流', '接出', '开发', '离线']
  const keyMap = ['profile', 'quality', 'workflow', 'export', 'script', 'offline']
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow', shadowStyle: { color: isDark.value ? 'rgba(0,224,255,0.08)' : 'rgba(21,87,239,0.06)' } } },
    grid: { left: 42, right: 24, top: 36, bottom: 36 },
    xAxis: { type: 'category', data: domains, axisTick: { show: false }, axisLine: { lineStyle: { color: c.axis } }, axisLabel: { color: c.muted } },
    yAxis: { type: 'value', axisLine: { show: false }, axisTick: { show: false }, axisLabel: { color: c.muted }, splitLine: { lineStyle: { color: c.split } } },
    series: [{
      type: 'bar', barWidth: '46%',
      label: { show: true, position: 'top', color: c.muted, fontSize: 12, fontWeight: 600 },
      itemStyle: { borderRadius: [8, 8, 0, 0] },
      emphasis: { itemStyle: { shadowBlur: isDark.value ? 18 : 0, shadowColor: c.glow } },
      animationDelay: (i: number) => i * 80,
      animationEasing: isDark.value ? 'elasticOut' : 'cubicOut',
      data: keyMap.map((k, i) => ({ value: taskStats.value[k]?.total ?? 0, itemStyle: { color: fill(i) } }))
    }]
  }
})

// 任务执行成功率（仪表盘：状态分段 · 主色进度 · 文字 token）
const successOption = computed(() => {
  const c = C.value
  const ts = taskStats.value || {}; const vals = Object.values(ts) as any[]
  let total = 0, success = 0
  vals.forEach((v) => { total += v.total ?? 0; success += v.success ?? 0 })
  const rate = total > 0 ? Math.round((success / total) * 100) : 0
  return {
    series: [{
      type: 'gauge', radius: '88%', center: ['50%', '52%'], startAngle: 200, endAngle: -20,
      progress: { show: true, width: 18, itemStyle: { color: grad(c.primary, c.primary2), shadowBlur: isDark.value ? 14 : 0, shadowColor: c.glow } },
      axisLine: { lineStyle: { width: 18, color: [[0.6, c.danger], [0.85, c.warn], [1, c.success]] } },
      axisTick: { show: false }, splitLine: { show: false }, axisLabel: { show: false },
      pointer: { length: '58%', width: 5, itemStyle: { color: c.primary, shadowBlur: isDark.value ? 12 : 0, shadowColor: c.glow } },
      anchor: { show: true, size: 14, itemStyle: { color: c.primary, shadowBlur: isDark.value ? 8 : 0 } },
      title: { show: false },
      detail: { valueAnimation: true, offsetCenter: [0, '30%'], formatter: '{value}%', color: c.ink, fontSize: 30, fontWeight: 700 },
      data: [{ value: rate }]
    }]
  }
})

// 资产状态分布（玫瑰图）
const assetOption = computed(() => {
  const c = C.value
  return {
    tooltip: { trigger: 'item' },
    legend: { bottom: 4, textStyle: { color: c.muted }, itemWidth: 10, itemHeight: 10 },
    series: [{
      type: 'pie', radius: ['20%', '72%'], center: ['50%', '46%'], roseType: 'radius',
      itemStyle: { borderColor: c.panelBorder, borderWidth: 2, shadowBlur: isDark.value ? 16 : 0, shadowColor: c.glow },
      label: { color: c.muted }, labelLine: { lineStyle: { color: c.axis } },
      emphasis: { itemStyle: { shadowBlur: isDark.value ? 26 : 6 } },
      data: (ov.value.assetByStatus || []).map((d: any, i: number) => ({ name: d.status, value: d.c, itemStyle: { color: fill(i) } }))
    }]
  }
})

async function refresh() {
  try { const [o, t, d] = await Promise.all([api.opsOverview(), api.opsTaskStats(), api.dsStats()]); ov.value = o; taskStats.value = t; dsStats.value = d }
  catch { /* 中台未就绪时静默 */ }
}
let timer: number
onMounted(() => { refresh(); timer = window.setInterval(refresh, 15000) })
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.overview { display: flex; flex-direction: column; gap: 16px; }
.kpi-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px; }
.kpi-card { position: relative; overflow: hidden; display: flex; align-items: center; gap: 12px; padding: 16px 14px; background: var(--tech-panel); border: 1px solid var(--tech-panel-border); border-radius: 10px; box-shadow: var(--tech-shadow); }

/* 流光边框 + 角标：仅暗色保留；浅色走 DIFY 极简 */
.kpi-card::before { content: none; }
.corner { display: none; }
html.dark .kpi-card::before {
  content: ''; position: absolute; top: 0; left: -100%; width: 60%; height: 1px;
  background: linear-gradient(90deg, transparent, var(--tech-primary), transparent);
  animation: sweep 4s linear infinite;
}
html.dark .corner { position: absolute; width: 10px; height: 10px; border: 1.5px solid var(--tech-primary); opacity: .6; display: block; }
html.dark .corner.tl { top: 6px; left: 6px; border-right: none; border-bottom: none; }
html.dark .corner.br { bottom: 6px; right: 6px; border-left: none; border-top: none; }
@keyframes sweep { to { left: 160%; } }

.kpi-icon { width: 44px; height: 44px; border-radius: 10px; flex-shrink: 0; display: flex; align-items: center; justify-content: center; }
/* 浅色：主色 12% 柔和淡底 + 主色图标（DIFY） */
.kpi-icon { background: color-mix(in srgb, var(--chip) 12%, var(--tech-panel)); color: var(--chip); }
/* 暗色：渐变 + 轻发光 */
html.dark .kpi-icon { background: var(--chip-grad); color: #fff; box-shadow: 0 4px 14px rgba(0, 224, 255, .35); }

.kpi-info { min-width: 0; }
.kpi-val { font-size: 26px; font-weight: 700; color: var(--tech-text); line-height: 1.1; font-variant-numeric: tabular-nums; }
.kpi-lab { font-size: 12px; color: var(--tech-text-muted); margin-top: 3px; }
html.dark .kpi-val { color: var(--tech-primary); text-shadow: var(--tech-glow); }

.chart-row { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.chart-card { padding: 16px; }
.chart-card .ct { display: flex; align-items: center; gap: 8px; font-weight: 600; font-size: 15px; color: var(--tech-text); margin-bottom: 8px; }
.chart-card .ct .el-icon { color: var(--tech-primary); }
.ct-sub { margin-left: auto; font-size: 11px; letter-spacing: 2px; color: var(--tech-text-muted); opacity: .55; }
.chart { height: 300px; }
.flow-hint { color: var(--tech-text-muted); font-size: 13px; display: flex; align-items: center; gap: 6px; padding: 6px 4px; }
.flow-hint .el-icon { color: var(--tech-primary); }
@media (max-width: 1280px) { .kpi-grid { grid-template-columns: repeat(3, 1fr); } .chart-row { grid-template-columns: 1fr; } }
</style>
