<template>
  <div class="st-page">
    <!-- 页头 -->
    <div class="page-head">
      <div class="page-head-left">
        <span class="title-icon head-ic"><el-icon><DataLine /></el-icon></span>
        <div>
          <div class="page-title">调用统计</div>
          <div class="page-sub">数据服务开放 API · 调用量 / 成功率 / 耗时</div>
        </div>
      </div>
      <div class="head-right">
        <span class="role-tag">数据服务</span>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <!-- KPI -->
    <div class="dl-card ov-card">
      <div class="card-head"><span class="card-head-title">调用总览</span><span class="count-badge">实时</span></div>
      <div class="kpi-grid" v-loading="loading">
        <div class="kpi" style="--accent: var(--tech-primary)"><span class="kpi-ic"><el-icon><DataLine /></el-icon></span><div class="kpi-num">{{ totalCalls }}</div><div class="kpi-label">总调用次数</div></div>
        <div class="kpi" style="--accent: var(--tech-success)"><span class="kpi-ic"><el-icon><CircleCheck /></el-icon></span><div class="kpi-num">{{ successRate }}<span class="u">%</span></div><div class="kpi-label">调用成功率</div></div>
        <div class="kpi" style="--accent: var(--tech-accent)"><span class="kpi-ic"><el-icon><Timer /></el-icon></span><div class="kpi-num">{{ avgCost }}<span class="u">ms</span></div><div class="kpi-label">平均耗时</div></div>
        <div class="kpi" style="--accent: var(--tech-primary-2)"><span class="kpi-ic"><el-icon><Connection /></el-icon></span><div class="kpi-num">{{ activeSvcs }}</div><div class="kpi-label">已发布服务</div></div>
        <div class="kpi" style="--accent: var(--tech-warn)"><span class="kpi-ic"><el-icon><Calendar /></el-icon></span><div class="kpi-num">{{ todayCalls }}</div><div class="kpi-label">今日调用</div></div>
      </div>
    </div>

    <!-- 图表 -->
    <div class="dl-card ov-card">
      <div class="card-head"><span class="card-head-title">调用分析</span></div>
      <div class="chart-grid">
        <div class="chart-box"><div class="chart-cap">Top 服务调用排行</div><v-chart class="chart" :option="topBarOption" :theme="chartTheme" autoresize /></div>
        <div class="chart-box"><div class="chart-cap">调用趋势（按小时）</div><v-chart class="chart" :option="trendOption" :theme="chartTheme" autoresize /></div>
        <div class="chart-box"><div class="chart-cap">成功 / 失败占比</div><v-chart class="chart" :option="pieOption" :theme="chartTheme" autoresize /></div>
        <div class="chart-box"><div class="chart-cap">平均耗时分布（ms）</div><v-chart class="chart" :option="costBarOption" :theme="chartTheme" autoresize /></div>
      </div>
    </div>

    <!-- 明细 -->
    <el-row :gutter="14">
      <el-col :span="12">
        <div class="dl-card ov-card">
          <div class="card-head"><span class="card-head-title">服务调用统计</span><span class="count-badge">共 <b>{{ stats.length }}</b></span></div>
          <el-table :data="stats" size="small" stripe max-height="380" v-loading="loading">
            <el-table-column prop="code" label="服务" min-width="120" show-overflow-tooltip />
            <el-table-column prop="calls" label="调用数" width="80" align="center" />
            <el-table-column label="平均(ms)" width="90" align="center"><template #default="{ row }">{{ Math.round(row.avg_cost) }}</template></el-table-column>
            <el-table-column label="成功率" width="84" align="center"><template #default="{ row }">{{ row.calls > 0 ? Math.round(row.success / row.calls * 100) + '%' : '—' }}</template></el-table-column>
            <el-table-column label="状态" width="84"><template #default="{ row }"><el-tag size="small" :type="row.status === 'PUBLISHED' ? 'success' : 'info'" effect="light">{{ row.status }}</el-tag></template></el-table-column>
          </el-table>
        </div>
      </el-col>
      <el-col :span="12">
        <div class="dl-card ov-card">
          <div class="card-head"><span class="card-head-title">最近调用日志</span><span class="count-badge">共 <b>{{ logs.length }}</b></span></div>
          <el-table :data="logs" size="small" stripe max-height="380" v-loading="loading">
            <el-table-column prop="call_time" label="时间" width="155" />
            <el-table-column prop="caller" label="调用方" width="100" show-overflow-tooltip />
            <el-table-column prop="cost_ms" label="耗时(ms)" width="86" align="center" />
            <el-table-column label="状态" width="74"><template #default="{ row }"><el-tag size="small" :type="row.status === 'SUCCESS' ? 'success' : 'danger'" effect="light">{{ row.status }}</el-tag></template></el-table-column>
            <el-table-column prop="ip" label="IP" width="110" />
          </el-table>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { DataLine, CircleCheck, Timer, Connection, Calendar, Refresh } from '@element-plus/icons-vue'
import { VChart } from '@/echarts'
import { theme } from '@/theme'
import { api, errMsg } from '@/api'

const chartTheme = theme.chartTheme
const stats = ref<any[]>([])
const logs = ref<any[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try { [stats.value, logs.value] = await Promise.all([api.dsStats(), api.dsLogs()]) }
  catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false }
}
onMounted(load)

// ===== KPI =====
const totalCalls = computed(() => stats.value.reduce((s: number, r: any) => s + (r.calls || 0), 0))
const totalSucc = computed(() => stats.value.reduce((s: number, r: any) => s + (r.success || 0), 0))
const successRate = computed(() => totalCalls.value > 0 ? Math.round(totalSucc.value / totalCalls.value * 1000) / 10 : 0)
const avgCost = computed(() => {
  const tot = totalCalls.value
  if (tot <= 0) return 0
  const w = stats.value.reduce((s: number, r: any) => s + (r.avg_cost || 0) * (r.calls || 0), 0)
  return Math.round(w / tot)
})
const activeSvcs = computed(() => stats.value.filter((r: any) => r.status === 'PUBLISHED').length)
const todayCalls = computed(() => {
  const d = new Date()
  const ymd = d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0')
  return logs.value.filter((l: any) => (l.call_time || '').startsWith(ymd)).length
})

// 主题相关 hex（canvas 不认 CSS 变量）
const C = computed(() => theme.isDark.value
  ? { primary: '#00e0ff', primary2: '#2f6bff', success: '#2ee6a6', danger: '#ff4d6d', accent: '#7c5cff' }
  : { primary: '#1557ef', primary2: '#4f46e5', success: '#16b364', danger: '#f04438', accent: '#7c5cff' })

// ===== 图表（轴/提示/图例样式交由 :theme，仅给数据 + 语义色） =====
const topBarOption = computed(() => {
  const top = [...stats.value].sort((a: any, b: any) => (b.calls || 0) - (a.calls || 0)).slice(0, 8)
  const g = C.value
  return {
    grid: { left: 110, right: 28, top: 8, bottom: 24 },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { type: 'value' },
    yAxis: { type: 'category', data: top.map((r: any) => r.code), inverse: true },
    series: [{ type: 'bar', barWidth: 12, data: top.map((r: any) => r.calls || 0), itemStyle: { borderRadius: [0, 6, 6, 0], color: { type: 'linear', x: 0, y: 0, x2: 1, y2: 0, colorStops: [{ offset: 0, color: g.primary2 }, { offset: 1, color: g.primary }] } } }]
  }
})
const trendOption = computed(() => {
  const buckets: Record<string, number> = {}
  logs.value.forEach((l: any) => { const h = (l.call_time || '').substring(0, 13); if (h) buckets[h] = (buckets[h] || 0) + 1 })
  const hours = Object.keys(buckets).sort()
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 20, top: 16, bottom: 30 },
    xAxis: { type: 'category', data: hours.map(h => h.substring(11)) },
    yAxis: { type: 'value', minInterval: 1 },
    series: [{ type: 'line', data: hours.map(h => buckets[h]), smooth: true, symbol: 'circle', symbolSize: 6,
      lineStyle: { width: 2, color: C.value.primary }, itemStyle: { color: C.value.primary },
      areaStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: C.value.primary + '59' }, { offset: 1, color: C.value.primary + '05' }] } } }]
  }
})
const pieOption = computed(() => {
  const fail = Math.max(totalCalls.value - totalSucc.value, 0)
  return {
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    legend: { bottom: 2 },
    series: [{ type: 'pie', radius: ['46%', '70%'], center: ['50%', '45%'], avoidLabelOverlap: true,
      itemStyle: { borderRadius: 4, borderWidth: 2, borderColor: theme.isDark.value ? '#0a1430' : '#ffffff' },
      data: [{ value: totalSucc.value, name: '成功', itemStyle: { color: C.value.success } }, { value: fail, name: '失败', itemStyle: { color: C.value.danger } }] }]
  }
})
const costBarOption = computed(() => {
  const top = [...stats.value].filter((r: any) => r.calls > 0).sort((a: any, b: any) => (b.avg_cost || 0) - (a.avg_cost || 0)).slice(0, 8)
  return {
    grid: { left: 40, right: 20, top: 8, bottom: 44 },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { type: 'category', data: top.map((r: any) => r.code), axisLabel: { rotate: 30, fontSize: 10 } },
    yAxis: { type: 'value' },
    series: [{ type: 'bar', barWidth: 14, data: top.map((r: any) => Math.round(r.avg_cost || 0)), itemStyle: { color: C.value.accent, borderRadius: [6, 6, 0, 0] } }]
  }
})
</script>

<style scoped>
.st-page { display: flex; flex-direction: column; gap: 14px; }
.page-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.page-head-left { display: flex; align-items: center; gap: 10px; }
.head-ic { font-size: 22px; display: inline-flex; }
.page-title { font-size: 18px; font-weight: 700; color: var(--tech-text); }
.page-sub { font-size: 12px; color: var(--tech-text-muted); margin-top: 2px; }
.head-right { display: flex; align-items: center; gap: 10px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 3px 10px; border-radius: 12px; background: var(--el-fill-color-light); }
.ov-card { padding: 14px; }
.card-head { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
.card-head-title { display: flex; align-items: center; gap: 7px; font-size: 14px; font-weight: 700; color: var(--tech-text); }
.card-head .count-badge { margin-left: auto; }
.kpi-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px; }
.kpi { display: flex; flex-direction: column; align-items: flex-start; gap: 6px; padding: 16px; border-radius: 10px; border: 1px solid var(--tech-panel-border); background: var(--tech-bg-2); position: relative; overflow: hidden; }
.kpi::after { content: ""; position: absolute; right: -18px; top: -18px; width: 56px; height: 56px; border-radius: 50%; background: color-mix(in srgb, var(--accent) 10%, transparent); }
.kpi-ic { width: 34px; height: 34px; border-radius: 8px; display: inline-flex; align-items: center; justify-content: center; color: var(--accent); background: color-mix(in srgb, var(--accent) 15%, transparent); font-size: 18px; z-index: 1; }
.kpi-num { font-size: 26px; font-weight: 700; color: var(--tech-text); line-height: 1; z-index: 1; }
.kpi-num .u { font-size: 13px; font-weight: 500; color: var(--tech-text-muted); margin-left: 3px; }
.kpi-label { font-size: 12px; color: var(--tech-text-muted); z-index: 1; }
.chart-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.chart-box { background: var(--el-fill-color-light); border: 1px solid var(--tech-panel-border); border-radius: 10px; padding: 12px; }
.chart-cap { font-size: 12px; color: var(--tech-text-muted); margin-bottom: 6px; font-weight: 600; }
.chart { height: 250px; width: 100%; }
@media (max-width: 1200px) { .chart-grid { grid-template-columns: 1fr; } }
</style>
