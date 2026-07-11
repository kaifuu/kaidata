<template>
  <div>
    <!-- KPI -->
    <div class="dl-card">
      <div class="card-title"><span>调用统计总览</span><span class="role-tag">数据服务</span><el-button link size="small" @click="load">刷新</el-button></div>
      <div class="kpi-grid" v-loading="loading">
        <div class="kpi c1"><div class="kv">{{ totalCalls }}</div><div class="kl">总调用次数</div></div>
        <div class="kpi c2"><div class="kv">{{ successRate }}<span class="u">%</span></div><div class="kl">调用成功率</div></div>
        <div class="kpi c3"><div class="kv">{{ avgCost }}<span class="u">ms</span></div><div class="kl">平均耗时</div></div>
        <div class="kpi c4"><div class="kv">{{ activeSvcs }}</div><div class="kl">已发布服务</div></div>
        <div class="kpi c5"><div class="kv">{{ todayCalls }}</div><div class="kl">今日调用</div></div>
      </div>
    </div>

    <!-- 图表 -->
    <div class="dl-card" style="margin-top:14px">
      <div class="card-title"><span>调用分析</span></div>
      <div class="chart-grid">
        <div class="chart-box">
          <div class="muted mb">Top 服务调用排行</div>
          <v-chart class="chart" :option="topBarOption" autoresize />
        </div>
        <div class="chart-box">
          <div class="muted mb">调用趋势（按小时）</div>
          <v-chart class="chart" :option="trendOption" autoresize />
        </div>
        <div class="chart-box">
          <div class="muted mb">成功 / 失败占比</div>
          <v-chart class="chart" :option="pieOption" autoresize />
        </div>
        <div class="chart-box">
          <div class="muted mb">平均耗时分布（ms）</div>
          <v-chart class="chart" :option="costBarOption" autoresize />
        </div>
      </div>
    </div>

    <!-- 明细 -->
    <div class="dl-card" style="margin-top:14px">
      <div class="card-title"><span>明细</span></div>
      <div style="display:grid;grid-template-columns:1fr 1fr;gap:16px">
        <div>
          <div class="muted mb">服务调用统计</div>
          <el-table :data="stats" size="small" border max-height="380">
            <el-table-column prop="code" label="服务" min-width="120" show-overflow-tooltip />
            <el-table-column prop="calls" label="调用数" width="80" align="center" />
            <el-table-column label="平均(ms)" width="90" align="center"><template #default="{ row }">{{ Math.round(row.avg_cost) }}</template></el-table-column>
            <el-table-column label="成功率" width="84" align="center"><template #default="{ row }">{{ row.calls > 0 ? Math.round(row.success / row.calls * 100) + '%' : '—' }}</template></el-table-column>
            <el-table-column label="状态" width="84"><template #default="{ row }"><el-tag size="small" :type="row.status === 'PUBLISHED' ? 'success' : 'info'">{{ row.status }}</el-tag></template></el-table-column>
          </el-table>
        </div>
        <div>
          <div class="muted mb">最近调用日志</div>
          <el-table :data="logs" size="small" border max-height="380">
            <el-table-column prop="call_time" label="时间" width="155" />
            <el-table-column prop="caller" label="调用方" width="100" show-overflow-tooltip />
            <el-table-column prop="cost_ms" label="耗时(ms)" width="86" align="center" />
            <el-table-column label="状态" width="74"><template #default="{ row }"><el-tag size="small" :type="row.status === 'SUCCESS' ? 'success' : 'danger'">{{ row.status }}</el-tag></template></el-table-column>
            <el-table-column prop="ip" label="IP" width="110" />
          </el-table>
        </div>
      </div>
    </div>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errMsg } from '@/api'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
use([CanvasRenderer, BarChart, LineChart, PieChart, GridComponent, TooltipComponent, LegendComponent])

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

// ===== 图表 =====
const topBarOption = computed(() => {
  const top = [...stats.value].sort((a: any, b: any) => (b.calls || 0) - (a.calls || 0)).slice(0, 8)
  return {
    grid: { left: 110, right: 24, top: 8, bottom: 24 },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { type: 'value', splitLine: { lineStyle: { color: 'rgba(130,149,173,0.15)' } }, axisLabel: { color: '#8295ad' } },
    yAxis: { type: 'category', data: top.map((r: any) => r.code), inverse: true, axisLine: { lineStyle: { color: 'rgba(130,149,173,0.3)' } }, axisLabel: { color: '#8295ad', fontSize: 11 } },
    series: [{ type: 'bar', data: top.map((r: any) => r.calls || 0), barWidth: 12, itemStyle: { color: { type: 'linear', x: 0, y: 0, x2: 1, y2: 0, colorStops: [{ offset: 0, color: '#2f7bff' }, { offset: 1, color: '#00d4ff' }] }, borderRadius: [0, 6, 6, 0] } }]
  }
})
const trendOption = computed(() => {
  const buckets: Record<string, number> = {}
  logs.value.forEach((l: any) => { const h = (l.call_time || '').substring(0, 13); if (h) buckets[h] = (buckets[h] || 0) + 1 })
  const hours = Object.keys(buckets).sort()
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 20, top: 16, bottom: 30 },
    xAxis: { type: 'category', data: hours.map(h => h.substring(11)), axisLabel: { color: '#8295ad' }, axisLine: { lineStyle: { color: 'rgba(130,149,173,0.3)' } } },
    yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: 'rgba(130,149,173,0.15)' } }, axisLabel: { color: '#8295ad' } },
    series: [{ type: 'line', data: hours.map(h => buckets[h]), smooth: true, symbol: 'circle', symbolSize: 6, lineStyle: { width: 2, color: '#00d4ff' }, itemStyle: { color: '#00d4ff' }, areaStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: 'rgba(0,212,255,0.35)' }, { offset: 1, color: 'rgba(0,212,255,0.02)' }] } } }]
  }
})
const pieOption = computed(() => {
  const fail = Math.max(totalCalls.value - totalSucc.value, 0)
  return {
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    legend: { bottom: 2, textStyle: { color: '#8295ad' } },
    series: [{ type: 'pie', radius: ['46%', '70%'], center: ['50%', '45%'], avoidLabelOverlap: true, itemStyle: { borderColor: '#0f1c2e', borderWidth: 2 }, label: { color: '#8295ad' }, data: [{ value: totalSucc.value, name: '成功', itemStyle: { color: '#22d3aa' } }, { value: fail, name: '失败', itemStyle: { color: '#ff6b6b' } }] }]
  }
})
const costBarOption = computed(() => {
  const top = [...stats.value].filter((r: any) => r.calls > 0).sort((a: any, b: any) => (b.avg_cost || 0) - (a.avg_cost || 0)).slice(0, 8)
  return {
    grid: { left: 40, right: 20, top: 8, bottom: 44 },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { type: 'category', data: top.map((r: any) => r.code), axisLabel: { color: '#8295ad', rotate: 30, fontSize: 10 }, axisLine: { lineStyle: { color: 'rgba(130,149,173,0.3)' } } },
    yAxis: { type: 'value', splitLine: { lineStyle: { color: 'rgba(130,149,173,0.15)' } }, axisLabel: { color: '#8295ad' } },
    series: [{ type: 'bar', data: top.map((r: any) => Math.round(r.avg_cost || 0)), barWidth: 14, itemStyle: { color: '#7c5cff', borderRadius: [6, 6, 0, 0] } }]
  }
})
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.card-title span:first-child { font-size: 15px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.muted { color: var(--tech-text-muted); font-size: 13px; }
.mb { margin-bottom: 6px; }
.kpi-grid { display: grid; grid-template-columns: repeat(5, 1fr); gap: 12px; }
.kpi { position: relative; overflow: hidden; background: var(--el-fill-color-light); border: 1px solid var(--tech-panel-border); border-radius: 10px; padding: 18px 16px; }
.kpi::before { content: ''; position: absolute; left: 0; top: 0; bottom: 0; width: 3px; background: var(--accent, #00d4ff); }
.kpi.c1 { --accent: #00d4ff; }
.kpi.c2 { --accent: #22d3aa; }
.kpi.c3 { --accent: #7c5cff; }
.kpi.c4 { --accent: #2f7bff; }
.kpi.c5 { --accent: #ffb020; }
.kv { font-size: 30px; font-weight: 700; color: var(--tech-primary); line-height: 1.1; }
.kv .u { font-size: 14px; font-weight: 500; color: var(--tech-text-muted); margin-left: 3px; }
.kl { font-size: 13px; color: var(--tech-text-muted); margin-top: 6px; }
.chart-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.chart-box { background: var(--el-fill-color-light); border: 1px solid var(--tech-panel-border); border-radius: 10px; padding: 12px; }
.chart { height: 260px; width: 100%; }
@media (max-width: 1200px) { .kpi-grid { grid-template-columns: repeat(2, 1fr); } .chart-grid { grid-template-columns: 1fr; } }
</style>
