<template>
  <div class="ts-page">
    <!-- 页头 -->
    <div class="page-head">
      <div class="page-head-left">
        <span class="title-icon head-ic"><el-icon><TrendCharts /></el-icon></span>
        <div>
          <div class="page-title">任务概览</div>
          <div class="page-sub">各域执行历史统计 · 成功 / 失败 / 成功率</div>
        </div>
      </div>
      <div class="head-right">
        <span class="role-tag">系统管理员</span>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <el-row :gutter="14">
      <!-- 平台整体成功率 -->
      <el-col :span="8">
        <div class="dl-card ov-card rate-card">
          <div class="card-head"><span class="card-head-title">平台整体成功率</span></div>
          <div class="rate-body" v-loading="loading">
            <el-progress type="dashboard" :percentage="overallRate" :width="156" :color="rateColors" :stroke-width="12">
              <template #default="{ percentage }">
                <div class="rate-pct">{{ percentage }}<span class="rate-unit">%</span></div>
                <div class="rate-sub">整体成功</div>
              </template>
            </el-progress>
            <div class="rate-meta">
              <span>总执行 <b>{{ overall.total }}</b></span>
              <span class="ok">成功 <b>{{ overall.success }}</b></span>
              <span class="fail">失败 <b>{{ overall.fail }}</b></span>
            </div>
          </div>
        </div>
      </el-col>
      <!-- 各域成功/失败对比 -->
      <el-col :span="16">
        <div class="dl-card ov-card chart-card">
          <div class="card-head">
            <span class="card-head-title">各域执行构成</span>
            <span class="count-badge">堆叠 = 总执行量</span>
          </div>
          <v-chart class="chart" :option="domainBarOption" :theme="chartTheme" autoresize />
        </div>
      </el-col>
    </el-row>

    <!-- 各域明细 -->
    <div class="dl-card ov-card">
      <div class="card-head"><span class="card-head-title">各域执行明细</span></div>
      <el-row :gutter="14" v-loading="loading">
        <el-col :span="8" v-for="(s, k) in stats" :key="k" style="margin-bottom: 14px">
          <div class="stat-card" :style="{ '--accent': domainMeta[k]?.color || 'var(--tech-primary)' }">
            <div class="st-head">
              <span class="st-ic"><el-icon><component :is="domainMeta[k]?.icon || DataLine" /></el-icon></span>
              <span class="st-title">{{ label(k) }}</span>
              <span class="st-rate" :class="rateClass(s)">{{ rate(s) }}%</span>
            </div>
            <div class="st-nums">
              <div><span class="n suc">{{ s.success }}</span><span class="lab">成功</span></div>
              <div><span class="n fail">{{ s.fail }}</span><span class="lab">失败</span></div>
              <div><span class="n tot">{{ s.total }}</span><span class="lab">总计</span></div>
            </div>
            <el-progress :percentage="rate(s)" :color="rateHex(s)" :stroke-width="8" :show-text="false" />
          </div>
        </el-col>
      </el-row>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, TrendCharts, Search, CircleCheck, Share, Promotion, Document, Download, DataLine } from '@element-plus/icons-vue'
import { VChart } from '@/echarts'
import { theme } from '@/theme'
import { api, errMsg } from '@/api'

const chartTheme = theme.chartTheme
const stats = ref<any>({})
const loading = ref(false)

const labels: any = { profile: '数据探查', quality: '数据质量', workflow: '工作流', export: '数据接出', script: '数据开发', offline: '离线接入' }
const label = (k: string | number) => labels[k] || k

const domainMeta: Record<string, { icon: any; color: string }> = {
  profile: { icon: Search, color: 'var(--tech-warn)' },
  quality: { icon: CircleCheck, color: 'var(--tech-danger)' },
  workflow: { icon: Share, color: 'var(--tech-primary-2)' },
  export: { icon: Promotion, color: 'var(--tech-success)' },
  script: { icon: Document, color: 'var(--tech-accent)' },
  offline: { icon: Download, color: 'var(--tech-primary)' },
}

function num(v: any) { return Number(v) || 0 }
const rate = (s: any) => num(s.total) > 0 ? Math.round((num(s.success) / num(s.total)) * 100) : 0
function rateClass(s: any) { const r = rate(s); return r > 90 ? 'ok' : r > 60 ? 'warn' : 'bad' }
function rateHex(s: any) { const r = rate(s); return r > 90 ? '#16b364' : r > 60 ? '#f79009' : '#f04438' }

const overall = computed(() => {
  const entries = Object.values(stats.value) as any[]
  return {
    total: entries.reduce((a, s) => a + num(s.total), 0),
    success: entries.reduce((a, s) => a + num(s.success), 0),
    fail: entries.reduce((a, s) => a + num(s.fail), 0),
  }
})
const overallRate = computed(() => overall.value.total > 0 ? Math.min(100, Math.round((overall.value.success / overall.value.total) * 100)) : 0)
const rateColors = [
  { color: '#f04438', percentage: 60 },
  { color: '#f79009', percentage: 85 },
  { color: '#16b364', percentage: 100 },
]

// ECharts 画在 canvas，颜色用具体 hex（按主题切换）
const chartColors = computed(() => theme.isDark.value
  ? ['#00e0ff', '#2f6bff', '#7c5cff', '#2ee6a6', '#ffb020', '#ff4d6d']
  : ['#1557ef', '#4f46e5', '#7c5cff', '#16b364', '#f79009', '#f04438'])
const domainBarOption = computed(() => {
  const colors = chartColors.value
  const entries = Object.entries(stats.value).map(([k, s]: [string, any]) => ({ name: label(k), success: num(s.success), fail: num(s.fail) }))
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: { bottom: 0, data: ['成功', '失败'] },
    grid: { left: 44, right: 20, top: 16, bottom: 38 },
    xAxis: { type: 'category', data: entries.map(e => e.name), axisLabel: { interval: 0, rotate: entries.length > 5 ? 18 : 0 } },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      { name: '成功', type: 'bar', stack: 'exec', barWidth: 18, data: entries.map(e => e.success), itemStyle: { color: colors[3], borderRadius: [4, 4, 0, 0] } },
      { name: '失败', type: 'bar', stack: 'exec', barWidth: 18, data: entries.map(e => e.fail), itemStyle: { color: colors[5] } }
    ]
  }
})

async function load() { loading.value = true; try { stats.value = await api.opsTaskStats() } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
onMounted(load)
</script>

<style scoped>
.ts-page { display: flex; flex-direction: column; gap: 14px; }

/* 页头 */
.page-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.page-head-left { display: flex; align-items: center; gap: 10px; }
.head-ic { font-size: 22px; display: inline-flex; }
.page-title { font-size: 18px; font-weight: 700; color: var(--tech-text); }
.page-sub { font-size: 12px; color: var(--tech-text-muted); margin-top: 2px; }
.head-right { display: flex; align-items: center; gap: 10px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 3px 10px; border-radius: 12px; background: var(--el-fill-color-light); }

/* 卡片头 */
.ov-card { padding: 14px; }
.card-head { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
.card-head-title { display: flex; align-items: center; gap: 7px; font-size: 14px; font-weight: 700; color: var(--tech-text); }
.card-head .count-badge { margin-left: auto; }

/* 图表 */
.chart-card { min-height: 0; }
.chart { height: 290px; }

/* 通过率仪表 */
.rate-body { display: flex; flex-direction: column; align-items: center; gap: 14px; padding-top: 6px; }
.rate-pct { font-size: 30px; font-weight: 700; color: var(--tech-text); line-height: 1; }
.rate-unit { font-size: 14px; color: var(--tech-text-muted); margin-left: 2px; }
.rate-sub { font-size: 12px; color: var(--tech-text-muted); margin-top: 4px; }
.rate-meta { display: flex; gap: 18px; font-size: 12px; color: var(--tech-text-muted); }
.rate-meta b { color: var(--tech-text); font-weight: 600; margin-left: 3px; }
.rate-meta .ok b { color: var(--tech-success); }
.rate-meta .fail b { color: var(--tech-danger); }

/* 明细卡 */
.stat-card { border: 1px solid var(--tech-panel-border); border-radius: 10px; padding: 14px; background: var(--tech-bg-2); position: relative; overflow: hidden; }
.stat-card::after { content: ""; position: absolute; right: -18px; top: -18px; width: 56px; height: 56px; border-radius: 50%; background: color-mix(in srgb, var(--accent) 10%, transparent); }
.st-head { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.st-ic { width: 30px; height: 30px; border-radius: 7px; display: inline-flex; align-items: center; justify-content: center; color: var(--accent); background: color-mix(in srgb, var(--accent) 15%, transparent); font-size: 16px; }
.st-title { font-weight: 700; color: var(--tech-text); flex: 1; }
.st-rate { font-size: 16px; font-weight: 700; }
.st-rate.ok { color: var(--tech-success); }
.st-rate.warn { color: var(--tech-warn); }
.st-rate.bad { color: var(--tech-danger); }
.st-nums { display: flex; gap: 18px; margin-bottom: 10px; }
.st-nums .n { font-size: 22px; font-weight: 700; display: block; }
.st-nums .lab { font-size: 12px; color: var(--tech-text-muted); }
.n.suc { color: var(--tech-success); } .n.fail { color: var(--tech-danger); } .n.tot { color: var(--tech-primary); }
</style>
