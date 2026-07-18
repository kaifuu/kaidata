<template>
  <div class="dl-card">
    <div class="page-head">
      <div>
        <h2><el-icon><DataBoard /></el-icon> 治理驾驶舱</h2>
        <p>数据治理全景：标准覆盖 · 质量评分 · 资产 · 元数据补录 · 血缘覆盖 · 模型落标</p>
      </div>
      <div class="head-stats" v-if="s">
        <span>质量<b>{{ score }}/{{ s.quality.grade }}</b></span>
        <span>落标<b>{{ s.model.landingRate }}%</b></span>
        <span>补录<b>{{ s.meta.fillOverall }}%</b></span>
      </div>
    </div>

    <div class="kpi-grid" v-if="s">
      <div class="kpi-card" v-for="k in kpis" :key="k.lab" :style="{ '--chip': k.color }">
        <div class="kpi-icon"><el-icon :size="20"><component :is="k.icon" /></el-icon></div>
        <div class="kpi-info">
          <div class="kpi-val">{{ k.val }}</div>
          <div class="kpi-lab">{{ k.lab }}</div>
          <div class="kpi-sub">{{ k.sub }}</div>
        </div>
      </div>
    </div>

    <div class="chart-row" v-if="s">
      <div class="panel"><div class="ct"><el-icon><CircleCheck /></el-icon> 质量综合分<span class="ct-sub">QUALITY</span></div><v-chart :option="qualityOption" :theme="chartTheme" autoresize class="ch" /></div>
      <div class="panel"><div class="ct"><el-icon><Connection /></el-icon> 模型落标率<span class="ct-sub">STANDARD</span></div><v-chart :option="landingOption" :theme="chartTheme" autoresize class="ch" /></div>
      <div class="panel"><div class="ct"><el-icon><Box /></el-icon> 资产状态分布<span class="ct-sub">ASSETS</span></div><v-chart :option="assetOption" :theme="chartTheme" autoresize class="ch" /></div>
    </div>

    <div class="panel detail" v-if="s">
      <div class="ct"><el-icon><DataLine /></el-icon> 明细指标<span class="ct-sub">DETAIL</span></div>
      <div class="mini-kpis">
        <span>模型 <b>{{ s.model.models }}</b></span>
        <span>模型表 <b>{{ s.model.tables }}</b></span>
        <span>已落标字段 <b>{{ s.model.landed }}</b></span>
        <span>标签 <b>{{ s.tag.tags }}</b></span>
        <span>打标关系 <b>{{ s.tag.relations }}</b></span>
        <span>采集任务 <b>{{ s.collect }}</b></span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { DataBoard, CircleCheck, Connection, Box, Coin, EditPen, Share, DataLine } from '@element-plus/icons-vue'
import { VChart } from '@/echarts'
import { api } from '@/api'
import { theme } from '@/theme'

const chartTheme = theme.chartTheme
const isDark = theme.isDark
const C = computed(() => isDark.value ? {
  ink: '#d6e6ff', muted: '#7f93bf', track: '#1a2640',
  success: '#2ee6a6', palette: ['#00e0ff', '#2f6bff', '#7c5cff', '#2ee6a6', '#ffb020', '#ff4d6d']
} : {
  ink: '#101828', muted: '#667085', track: '#f2f4f7',
  success: '#16b364', palette: ['#1557ef', '#4f46e5', '#7c5cff', '#16b364', '#f79009', '#f04438']
})

const s = ref<any>(null)
const score = computed(() => Math.round(Number(s.value?.quality?.score) || 0))
const passAsset = computed(() => (s.value?.asset?.byStatus || []).filter((x: any) => /通过|pass|approv/i.test(x.status || '')).reduce((a: number, x: any) => a + Number(x.c || 0), 0))
const pendingAsset = computed(() => (s.value?.asset?.byStatus || []).filter((x: any) => /待审|草稿|pending|draft|review/i.test(x.status || '')).reduce((a: number, x: any) => a + Number(x.c || 0), 0))

const kpis = computed(() => [
  { icon: Coin, color: '#1557ef', val: s.value?.standard?.elements ?? 0, lab: '数据元', sub: '代码集 ' + (s.value?.standard?.codeSets ?? 0) },
  { icon: CircleCheck, color: '#16b364', val: score.value, lab: '质量评分', sub: (s.value?.quality?.grade || '-') + ' · ' + (s.value?.quality?.rules || 0) + ' 规则' },
  { icon: Box, color: '#7c5cff', val: s.value?.asset?.total ?? 0, lab: '数据资产', sub: '通过 ' + passAsset.value + ' · 待审 ' + pendingAsset.value },
  { icon: EditPen, color: '#f79009', val: (s.value?.meta?.fillOverall ?? 0) + '%', lab: '元数据补录', sub: '血缘覆盖 ' + (s.value?.meta?.lineageCoverage ?? 0) + '%' },
  { icon: Share, color: '#0ba5ec', val: s.value?.meta?.lineageEdges ?? 0, lab: '血缘边', sub: '采集任务 ' + (s.value?.collect ?? 0) },
  { icon: Connection, color: '#4f46e5', val: (s.value?.model?.landingRate ?? 0) + '%', lab: '模型落标', sub: (s.value?.model?.landed ?? 0) + '/' + (s.value?.model?.fields ?? 0) + ' 字段' },
])

const qualityOption = computed(() => {
  const c = C.value
  return { series: [{ type: 'gauge', startAngle: 200, endAngle: -20, min: 0, max: 100,
    progress: { show: true, width: 14, itemStyle: { color: c.success } },
    axisLine: { lineStyle: { width: 14, color: [[1, c.track]] } },
    axisTick: { show: false }, splitLine: { show: false }, axisLabel: { show: false }, pointer: { show: false },
    detail: { valueAnimation: true, formatter: '{value}', fontSize: 28, color: c.ink, offsetCenter: [0, '12%'] },
    title: { show: true, offsetCenter: [0, '48%'], color: c.muted, fontSize: 12 },
    data: [{ value: score.value, name: '综合分 / ' + (s.value?.quality?.grade || '-') }] }] }
})
const landingOption = computed(() => {
  const c = C.value; const v = s.value?.model?.landingRate || 0
  return { title: { text: v + '%', left: 'center', top: '38%', textStyle: { fontSize: 22, color: c.ink } },
    series: [{ type: 'pie', radius: ['62%', '80%'], silent: true, label: { show: false },
      data: [{ value: v, itemStyle: { color: c.success } }, { value: 100 - v, itemStyle: { color: c.track } }] }] }
})
const assetOption = computed(() => {
  const c = C.value
  return { legend: { bottom: 0, textStyle: { color: c.muted, fontSize: 11 } },
    series: [{ type: 'pie', radius: ['40%', '68%'], label: { color: c.muted, fontSize: 11 },
      data: (s.value?.asset?.byStatus || []).map((x: any, i: number) => ({ name: x.status || '未知', value: Number(x.c || 0), itemStyle: { color: c.palette[i % c.palette.length] } })) }] }
})

async function load() { try { s.value = await api.govDashboardStats() } catch { /* */ } }
onMounted(() => { load() })
</script>
<style scoped>
.page-head { display: flex; justify-content: space-between; align-items: flex-end; margin-bottom: 18px; }
.page-head h2 { margin: 0; font-size: 18px; font-weight: 600; color: var(--tech-text); display: flex; align-items: center; gap: 8px; }
.page-head h2 .el-icon { color: var(--tech-primary); }
.page-head p { margin: 6px 0 0; color: var(--tech-text-muted); font-size: 13px; }
.head-stats { display: flex; gap: 22px; }
.head-stats span { font-size: 13px; color: var(--tech-text-muted); }
.head-stats b { color: var(--tech-text); font-size: 15px; font-weight: 600; margin-left: 4px; }
.kpi-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; }
.kpi-card { display: flex; align-items: center; gap: 12px; padding: 16px 14px; background: var(--tech-panel); border: 1px solid var(--tech-panel-border); border-radius: 10px; box-shadow: var(--tech-shadow); transition: border-color .15s; }
.kpi-card:hover { border-color: var(--tech-primary); }
.kpi-icon { width: 42px; height: 42px; border-radius: 10px; flex-shrink: 0; display: flex; align-items: center; justify-content: center; background: color-mix(in srgb, var(--chip) 13%, var(--tech-panel)); color: var(--chip); }
.kpi-val { font-size: 24px; font-weight: 700; color: var(--tech-text); line-height: 1.1; font-variant-numeric: tabular-nums; }
.kpi-lab { font-size: 12px; color: var(--tech-text-muted); margin-top: 3px; }
.kpi-sub { font-size: 11px; color: var(--tech-text-muted); opacity: .75; margin-top: 1px; }
.chart-row { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-top: 16px; }
.panel { padding: 16px; background: var(--tech-panel); border: 1px solid var(--tech-panel-border); border-radius: 10px; box-shadow: var(--tech-shadow); }
.detail { margin-top: 16px; }
.ct { display: flex; align-items: center; gap: 8px; font-weight: 600; font-size: 14px; color: var(--tech-text); margin-bottom: 10px; }
.ct .el-icon { color: var(--tech-primary); }
.ct-sub { margin-left: auto; font-size: 11px; letter-spacing: 2px; color: var(--tech-text-muted); opacity: .55; font-weight: 400; }
.ch { width: 100%; height: 200px; }
.mini-kpis { display: flex; gap: 28px; flex-wrap: wrap; padding: 6px 2px; color: var(--tech-text-muted); font-size: 13px; }
.mini-kpis b { color: var(--tech-text); font-size: 16px; font-weight: 600; margin-left: 4px; }
</style>
