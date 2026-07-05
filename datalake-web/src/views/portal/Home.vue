<template>
  <div class="overview">
    <!-- KPI 卡片 -->
    <div class="kpi-grid">
      <div class="kpi-card" v-for="k in kpis" :key="k.label">
        <div class="kpi-icon" :style="{ background: k.color }"><el-icon :size="22"><component :is="k.icon" /></el-icon></div>
        <div class="kpi-info">
          <div class="kpi-val">{{ k.value }}</div>
          <div class="kpi-lab">{{ k.label }}</div>
        </div>
        <div class="kpi-spark" :style="{ background: k.color }" />
      </div>
    </div>

    <!-- 图表区 -->
    <div class="chart-row">
      <div class="dl-card chart-card">
        <div class="ct"><el-icon><Connection /></el-icon> 数据源类型分布<span class="ct-sub">DATA SOURCES</span></div>
        <v-chart :option="dsTypeOption" :theme="chartTheme" autoresize class="chart" />
      </div>
      <div class="dl-card chart-card">
        <div class="ct"><el-icon><List /></el-icon> 各域任务数<span class="ct-sub">TASKS BY DOMAIN</span></div>
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

    <div class="flow-hint"><el-icon><InfoFilled /></el-icon> 数据中台总览 · 数据接入 / 治理 / 开发 / 资产 / 服务 全域运行态势（每 15s 刷新）</div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { Connection, List, CircleCheck, Box, InfoFilled } from '@element-plus/icons-vue'
import { api } from '@/api'
import { theme } from '@/theme'

const chartTheme = computed(() => theme.chartTheme.value)
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

// 数据源类型分布（环图）
const dsTypeOption = computed(() => ({
  tooltip: { trigger: 'item' },
  legend: { bottom: 4, textStyle: { color: '#aebfe2' } },
  series: [{
    type: 'pie', radius: ['42%', '68%'], center: ['50%', '44%'],
    avoidLabelOverlap: true,
    itemStyle: { borderColor: 'rgba(6,12,28,.6)', borderWidth: 2, shadowBlur: 12, shadowColor: 'rgba(0,224,255,.35)' },
    label: { color: '#aebfe2' },
    data: (ov.value.datasourceByType || []).map((d: any, i: number) => ({ name: d.type, value: d.c, itemStyle: { color: grad(PALETTE[i % 6], PALETTE[(i + 2) % 6]) } }))
  }]
}))

// 各域任务数（柱状渐变）
const taskDomainOption = computed(() => {
  const ts = taskStats.value || {}
  const domains = ['探查', '质量', '工作流', '接出', '开发', '离线']
  const keyMap = ['profile', 'quality', 'workflow', 'export', 'script', 'offline']
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 42, right: 24, top: 24, bottom: 36 },
    xAxis: { type: 'category', data: domains, axisLine: { lineStyle: { color: 'rgba(0,224,255,.3)' } }, axisLabel: { color: '#aebfe2' } },
    yAxis: { type: 'value', axisLabel: { color: '#aebfe2' }, splitLine: { lineStyle: { color: 'rgba(0,224,255,.1)' } } },
    series: [{
      type: 'bar', barWidth: '46%',
      data: keyMap.map((k, i) => ({ value: ts[k]?.total ?? 0, itemStyle: { color: grad(PALETTE[i % 6], PALETTE[(i + 3) % 6]), borderRadius: [6, 6, 0, 0], shadowBlur: 14, shadowColor: 'rgba(0,224,255,.45)' } })),
      animationDelay: (i: number) => i * 80
    }]
  }
})

// 任务执行成功率（仪表盘）
const successOption = computed(() => {
  const ts = taskStats.value || {}
  const vals = Object.values(ts) as any[]
  let total = 0, success = 0
  vals.forEach((v) => { total += v.total ?? 0; success += v.success ?? 0 })
  const rate = total > 0 ? Math.round((success / total) * 100) : 0
  return {
    series: [{
      type: 'gauge', radius: '88%', center: ['50%', '52%'],
      startAngle: 200, endAngle: -20,
      progress: { show: true, width: 16, itemStyle: { color: grad('#00e0ff', '#2ee6a6') } },
      axisLine: { lineStyle: { width: 16, color: [[1, 'rgba(0,224,255,.12)']] } },
      axisTick: { show: false }, splitLine: { show: false }, axisLabel: { show: false },
      pointer: { length: '60%', width: 4, itemStyle: { color: '#00e0ff', shadowBlur: 10, shadowColor: '#00e0ff' } },
      anchor: { show: true, size: 12, itemStyle: { color: '#00e0ff' } },
      title: { show: false },
      detail: { valueAnimation: true, offsetCenter: [0, '28%'], formatter: '{value}%', color: '#00e0ff', fontSize: 28, fontWeight: 700 },
      data: [{ value: rate }]
    }]
  }
})

// 资产状态分布（环图）
const assetOption = computed(() => ({
  tooltip: { trigger: 'item' },
  legend: { bottom: 4, textStyle: { color: '#aebfe2' } },
  series: [{
    type: 'pie', radius: ['42%', '68%'], center: ['50%', '44%'], roseType: 'radius',
    itemStyle: { borderColor: 'rgba(6,12,28,.6)', borderWidth: 2, shadowBlur: 12, shadowColor: 'rgba(124,92,255,.35)' },
    label: { color: '#aebfe2' },
    data: (ov.value.assetByStatus || []).map((d: any, i: number) => ({ name: d.status, value: d.c, itemStyle: { color: grad(PALETTE[(i + 1) % 6], PALETTE[(i + 4) % 6]) } }))
  }]
}))

async function refresh() {
  try {
    const [o, t, d] = await Promise.all([api.opsOverview(), api.opsTaskStats(), api.dsStats()])
    ov.value = o; taskStats.value = t; dsStats.value = d
  } catch { /* 中台未就绪时静默 */ }
}
let timer: number
onMounted(() => { refresh(); timer = window.setInterval(refresh, 15000) })
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.overview { display: flex; flex-direction: column; gap: 16px; }
.kpi-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px; }
.kpi-card { position: relative; overflow: hidden; display: flex; align-items: center; gap: 12px; padding: 16px 14px; background: var(--tech-panel); border: 1px solid var(--tech-panel-border); border-radius: 10px; box-shadow: var(--tech-shadow); }
.kpi-icon { width: 44px; height: 44px; border-radius: 10px; color: #fff; display: flex; align-items: center; justify-content: center; box-shadow: 0 4px 14px rgba(0, 224, 255, .35); }
.kpi-val { font-size: 26px; font-weight: 700; color: var(--tech-primary); text-shadow: var(--tech-glow); line-height: 1.1; }
.kpi-lab { font-size: 12px; color: var(--tech-text-muted); margin-top: 3px; }
.kpi-spark { position: absolute; right: -20px; bottom: -20px; width: 64px; height: 64px; border-radius: 50%; opacity: .12; filter: blur(8px); }
.chart-row { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.chart-card .ct { display: flex; align-items: center; gap: 8px; font-weight: 600; color: var(--tech-text); margin-bottom: 8px; }
.chart-card .ct .el-icon { color: var(--tech-primary); }
.ct-sub { margin-left: auto; font-size: 11px; letter-spacing: 2px; color: var(--tech-text-muted); opacity: .6; }
.chart { height: 280px; }
.flow-hint { color: var(--tech-text-muted); font-size: 13px; display: flex; align-items: center; gap: 6px; padding: 6px 4px; }
.flow-hint .el-icon { color: var(--tech-primary); }
@media (max-width: 1280px) { .kpi-grid { grid-template-columns: repeat(3, 1fr); } .chart-row { grid-template-columns: 1fr; } }
</style>
