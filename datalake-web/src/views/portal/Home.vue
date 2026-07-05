<template>
  <div class="overview">
    <!-- KPI 卡片：流光边框 + 角标 + 数字滚动 -->
    <div class="kpi-grid">
      <div class="kpi-card" v-for="k in kpis" :key="k.label">
        <span class="corner tl" /><span class="corner br" />
        <div class="kpi-icon" :style="{ background: k.color }"><el-icon :size="22"><component :is="k.icon" /></el-icon></div>
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
        <div class="ct"><el-icon><List /></el-icon> 各域任务数<span class="ct-sub">3D TASKS</span></div>
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

    <div class="flow-hint"><el-icon><InfoFilled /></el-icon> 数据中台总览 · 全域运行态势（每 15s 刷新 · 3D 可视化）</div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { Connection, List, CircleCheck, Box, InfoFilled } from '@element-plus/icons-vue'
import { VChart } from '@/echarts'
import { api } from '@/api'
import { theme } from '@/theme'

const chartTheme = theme.chartTheme
const ov = ref<any>({})
const taskStats = ref<any>({})
const dsStats = ref<any[]>([])

const PALETTE = ['#00e0ff', '#7c5cff', '#2ee6a6', '#ffb020', '#2f6bff', '#ff4d6d']
const grad = (c1: string, c2: string) => ({ type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: c1 }, { offset: 1, color: c2 }] })

const taskTotal = computed(() => ((ov.value.profileJobs ?? 0) + (ov.value.qualityRules ?? 0) + (ov.value.workflows ?? 0) + (ov.value.scripts ?? 0) + (ov.value.exports ?? 0)))
const dsCalls = computed(() => (dsStats.value || []).reduce((s: number, d: any) => s + (Number(d.calls) || 0), 0))

const kpis = computed(() => [
  { label: '数据源', value: ov.value.datasources ?? 0, icon: 'Connection', color: 'linear-gradient(135deg,#00e0ff,#2f6bff)' },
  { label: '元数据表', value: ov.value.metaTables ?? 0, icon: 'Files', color: 'linear-gradient(135deg,#7c5cff,#00e0ff)' },
  { label: '数据资产', value: ov.value.assets ?? 0, icon: 'Box', color: 'linear-gradient(135deg,#2ee6a6,#00e0ff)' },
  { label: '任务总数', value: taskTotal.value, icon: 'List', color: 'linear-gradient(135deg,#ffb020,#ff4d6d)' },
  { label: '工作流', value: ov.value.workflows ?? 0, icon: 'Connection', color: 'linear-gradient(135deg,#2f6bff,#7c5cff)' },
  { label: '服务调用', value: dsCalls.value, icon: 'Promotion', color: 'linear-gradient(135deg,#00e0ff,#2ee6a6)' }
])

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
  const total = (ov.value.datasourceByType || []).reduce((s: number, d: any) => s + Number(d.c || 0), 0)
  return {
    tooltip: { trigger: 'item' },
    legend: { bottom: 4, textStyle: { color: '#aebfe2' } },
    graphic: [
      { type: 'text', left: 'center', top: '40%', style: { text: String(total), fill: '#00e0ff', font: 'bold 30px sans-serif', textAlign: 'center' } },
      { type: 'text', left: 'center', top: '52%', style: { text: '数据源', fill: '#7f93bf', font: '12px sans-serif', textAlign: 'center' } }
    ],
    series: [{
      type: 'pie', radius: ['52%', '72%'], center: ['50%', '46%'],
      itemStyle: { borderColor: '#0a1430', borderWidth: 3, shadowBlur: 18, shadowColor: 'rgba(0,224,255,.5)' },
      label: { color: '#aebfe2', formatter: '{b} {c}' }, labelLine: { lineStyle: { color: 'rgba(0,224,255,.4)' } },
      emphasis: { itemStyle: { shadowBlur: 30, shadowColor: 'rgba(0,224,255,.8)' } },
      data: (ov.value.datasourceByType || []).map((d: any, i: number) => ({ name: d.type, value: d.c, itemStyle: { color: grad(PALETTE[i % 6], PALETTE[(i + 2) % 6]) } }))
    }]
  }
})

// 各域任务数（3D 柱状 bar3D + 自动旋转 + 光照）
const taskDomainOption = computed(() => {
  const ts = taskStats.value || {}
  const domains = ['探查', '质量', '工作流', '接出', '开发', '离线']
  const keyMap = ['profile', 'quality', 'workflow', 'export', 'script', 'offline']
  return {
    tooltip: {},
    xAxis3D: { type: 'category', data: domains, axisLabel: { color: '#aebfe2' } },
    yAxis3D: { type: 'category', data: [''] },
    zAxis3D: { type: 'value', axisLabel: { color: '#7f93bf' } },
    grid3D: {
      boxWidth: 150, boxDepth: 36, boxHeight: 80,
      viewControl: { autoRotate: true, autoRotateSpeed: 6, distance: 175, alpha: 16, beta: 32 },
      light: { main: { intensity: 1.4, shadow: true, alpha: 30 }, ambient: { intensity: 0.45 } },
      environment: 'auto',
      axisLine: { lineStyle: { color: 'rgba(0,224,255,.25)' } },
      splitLine: { lineStyle: { color: 'rgba(0,224,255,.08)' } },
      axisPointer: { lineStyle: { color: 'rgba(0,224,255,.3)' } }
    },
    series: [{
      type: 'bar3D', shading: 'lambert', barSize: 14, itemStyle: { opacity: 0.92 },
      label: { show: true, formatter: (p: any) => p.value[2], color: '#fff', fontSize: 11 },
      data: domains.map((_, i) => ({ value: [i, 0, ts[keyMap[i]]?.total ?? 0], itemStyle: { color: PALETTE[i % 6] } }))
    }]
  }
})

// 任务执行成功率（仪表盘：红黄绿分段 + 发光指针）
const successOption = computed(() => {
  const ts = taskStats.value || {}; const vals = Object.values(ts) as any[]
  let total = 0, success = 0
  vals.forEach((v) => { total += v.total ?? 0; success += v.success ?? 0 })
  const rate = total > 0 ? Math.round((success / total) * 100) : 0
  return {
    series: [{
      type: 'gauge', radius: '88%', center: ['50%', '52%'], startAngle: 200, endAngle: -20,
      progress: { show: true, width: 18, itemStyle: { color: grad('#00e0ff', '#2ee6a6'), shadowBlur: 14, shadowColor: 'rgba(0,224,255,.6)' } },
      axisLine: { lineStyle: { width: 18, color: [[0.6, '#ff4d6d'], [0.85, '#ffb020'], [1, '#2ee6a6']] } },
      axisTick: { show: false }, splitLine: { show: false }, axisLabel: { show: false },
      pointer: { length: '58%', width: 5, itemStyle: { color: '#00e0ff', shadowBlur: 12, shadowColor: '#00e0ff' } },
      anchor: { show: true, size: 14, itemStyle: { color: '#00e0ff', shadowBlur: 8 } },
      title: { show: false },
      detail: { valueAnimation: true, offsetCenter: [0, '30%'], formatter: '{value}%', color: '#00e0ff', fontSize: 30, fontWeight: 700 },
      data: [{ value: rate }]
    }]
  }
})

// 资产状态分布（玫瑰图 + 渐变发光）
const assetOption = computed(() => ({
  tooltip: { trigger: 'item' },
  legend: { bottom: 4, textStyle: { color: '#aebfe2' } },
  series: [{
    type: 'pie', radius: ['20%', '72%'], center: ['50%', '46%'], roseType: 'radius',
    itemStyle: { borderColor: '#0a1430', borderWidth: 2, shadowBlur: 16, shadowColor: 'rgba(124,92,255,.45)' },
    label: { color: '#aebfe2' }, labelLine: { lineStyle: { color: 'rgba(124,92,255,.4)' } },
    emphasis: { itemStyle: { shadowBlur: 28 } },
    data: (ov.value.assetByStatus || []).map((d: any, i: number) => ({ name: d.status, value: d.c, itemStyle: { color: grad(PALETTE[(i + 1) % 6], PALETTE[(i + 4) % 6]) } }))
  }]
}))

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
.kpi-card::before { content: ''; position: absolute; top: 0; left: -100%; width: 60%; height: 1px; background: linear-gradient(90deg, transparent, var(--tech-primary), transparent); animation: sweep 4s linear infinite; }
@keyframes sweep { to { left: 160%; } }
.corner { position: absolute; width: 10px; height: 10px; border: 1.5px solid var(--tech-primary); opacity: .6; }
.corner.tl { top: 6px; left: 6px; border-right: none; border-bottom: none; }
.corner.br { bottom: 6px; right: 6px; border-left: none; border-top: none; }
.kpi-icon { width: 44px; height: 44px; border-radius: 10px; color: #fff; display: flex; align-items: center; justify-content: center; box-shadow: 0 4px 14px rgba(0, 224, 255, .35); }
.kpi-val { font-size: 26px; font-weight: 700; color: var(--tech-primary); text-shadow: var(--tech-glow); line-height: 1.1; font-variant-numeric: tabular-nums; }
.kpi-lab { font-size: 12px; color: var(--tech-text-muted); margin-top: 3px; }
.chart-row { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.chart-card .ct { display: flex; align-items: center; gap: 8px; font-weight: 600; color: var(--tech-text); margin-bottom: 8px; }
.chart-card .ct .el-icon { color: var(--tech-primary); }
.ct-sub { margin-left: auto; font-size: 11px; letter-spacing: 2px; color: var(--tech-text-muted); opacity: .6; }
.chart { height: 300px; }
.flow-hint { color: var(--tech-text-muted); font-size: 13px; display: flex; align-items: center; gap: 6px; padding: 6px 4px; }
.flow-hint .el-icon { color: var(--tech-primary); }
@media (max-width: 1280px) { .kpi-grid { grid-template-columns: repeat(3, 1fr); } .chart-row { grid-template-columns: 1fr; } }
</style>
