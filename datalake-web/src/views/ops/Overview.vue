<template>
  <div class="ov-page">
    <!-- 页头 -->
    <div class="page-head">
      <div class="page-head-left">
        <span class="title-icon head-ic"><el-icon><DataBoard /></el-icon></span>
        <div>
          <div class="page-title">数据概览</div>
          <div class="page-sub" v-if="lastUpd">更新于 {{ lastUpd }}</div>
        </div>
      </div>
      <div class="head-right">
        <span class="role-tag">系统管理员</span>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <!-- KPI -->
    <div class="dl-card ov-card">
      <div class="card-head">
        <span class="card-head-title">核心指标</span>
        <span class="count-badge">共 <b>{{ kpiMeta.length }}</b> 项</span>
      </div>
      <div class="kpi-grid" v-loading="loading">
        <div v-for="k in kpiMeta" :key="k.key" class="kpi" :style="{ '--accent': k.color }">
          <span class="kpi-ic"><el-icon><component :is="k.icon" /></el-icon></span>
          <div class="kpi-num">{{ data[k.key] ?? 0 }}</div>
          <div class="kpi-label">{{ k.label }}</div>
        </div>
      </div>
    </div>

    <!-- 分布 + 通过率 -->
    <el-row :gutter="14">
      <el-col :span="8">
        <div class="dl-card ov-card chart-card">
          <div class="card-head"><span class="card-head-title">数据源类型分布</span></div>
          <v-chart class="chart" :option="dsTypeOption" :theme="chartTheme" autoresize />
        </div>
      </el-col>
      <el-col :span="8">
        <div class="dl-card ov-card chart-card">
          <div class="card-head"><span class="card-head-title">资产状态分布</span></div>
          <v-chart class="chart" :option="assetStatusOption" :theme="chartTheme" autoresize />
        </div>
      </el-col>
      <el-col :span="8">
        <div class="dl-card ov-card chart-card rate-card">
          <div class="card-head"><span class="card-head-title">资产审核通过率</span></div>
          <div class="rate-body" v-loading="loading">
            <el-progress type="dashboard" :percentage="approvalRate" :width="148" :color="approvalColors" :stroke-width="12">
              <template #default="{ percentage }">
                <div class="rate-pct">{{ percentage }}<span class="rate-unit">%</span></div>
                <div class="rate-sub">已通过</div>
              </template>
            </el-progress>
            <div class="rate-meta">
              <span>已通过 <b class="ok">{{ data.assetsApproved ?? 0 }}</b></span>
              <span>未通过 <b>{{ Math.max(0, (data.assets ?? 0) - (data.assetsApproved ?? 0)) }}</b></span>
              <span>合计 <b>{{ data.assets ?? 0 }}</b></span>
            </div>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- 模块规模 -->
    <div class="dl-card ov-card chart-card">
      <div class="card-head">
        <span class="card-head-title">平台模块规模</span>
        <span class="count-badge">开发与治理产出对比</span>
      </div>
      <v-chart class="chart tall" :option="moduleScaleOption" :theme="chartTheme" autoresize />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  Coin, FolderOpened, Grid, Files, CircleCheck, Search, SetUp,
  Share, Document, Promotion, Refresh, DataBoard
} from '@element-plus/icons-vue'
import { VChart } from '@/echarts'
import { theme } from '@/theme'
import { api, errMsg } from '@/api'

const chartTheme = theme.chartTheme
const data = ref<any>({})
const loading = ref(false)
const lastUpd = ref('')

// KPI 元信息：图标 + 强调色（补齐后端返回的 filestores / exports）
const kpiMeta = [
  { key: 'datasources', label: '数据源', icon: Coin, color: 'var(--tech-primary)' },
  { key: 'filestores', label: '文件存储', icon: FolderOpened, color: 'var(--tech-primary-2)' },
  { key: 'metaTables', label: '元数据表', icon: Grid, color: 'var(--tech-accent)' },
  { key: 'assets', label: '资产总数', icon: Files, color: 'var(--tech-primary)' },
  { key: 'assetsApproved', label: '资产已通过', icon: CircleCheck, color: 'var(--tech-success)' },
  { key: 'profileJobs', label: '探查任务', icon: Search, color: 'var(--tech-warn)' },
  { key: 'qualityRules', label: '质量规则', icon: SetUp, color: 'var(--tech-danger)' },
  { key: 'workflows', label: '工作流', icon: Share, color: 'var(--tech-primary-2)' },
  { key: 'scripts', label: '开发脚本', icon: Document, color: 'var(--tech-accent)' },
  { key: 'exports', label: '数据接出', icon: Promotion, color: 'var(--tech-success)' },
]

const ASSET_STATUS_LABEL: Record<string, string> = { '通过': '已通过', '待审': '待审核', '草稿': '草稿', '驳回': '已驳回' }

function num(v: any) { return Number(v) || 0 }

// ECharts 画在 canvas 上，不解析 CSS 变量 —— 颜色须给具体 hex，按主题切换。
// 饼图分片间隔色 = 卡片底色；柱状调色板 = 各模块色。
const sliceGap = computed(() => (theme.isDark.value ? '#0a1430' : '#ffffff'))
const chartColors = computed(() => theme.isDark.value
  ? ['#00e0ff', '#2f6bff', '#7c5cff', '#2ee6a6', '#ffb020', '#ff4d6d', '#22d3ee']
  : ['#1557ef', '#4f46e5', '#7c5cff', '#16b364', '#f79009', '#f04438', '#0ba5ec'])

// 饼图（环形）
function pieOption(rows: { name: string; value: number }[]) {
  return {
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    legend: { bottom: 0, type: 'scroll', icon: 'circle' },
    series: [{
      type: 'pie', radius: ['42%', '68%'], center: ['50%', '46%'], avoidLabelOverlap: true,
      itemStyle: { borderColor: sliceGap.value, borderRadius: 4, borderWidth: 2 },
      label: { formatter: '{b}\n{c}' },
      labelLine: { length: 8, length2: 6 },
      data: rows
    }]
  }
}
const dsTypeOption = computed(() => pieOption((data.value.datasourceByType || []).map((d: any) => ({ name: d.type || '未知', value: num(d.c) }))))
const assetStatusOption = computed(() => pieOption((data.value.assetByStatus || []).map((d: any) => ({ name: ASSET_STATUS_LABEL[d.status] || d.status || '未知', value: num(d.c) }))))

// 资产通过率（dashboard 环）
const approvalRate = computed(() => {
  const total = num(data.value.assets)
  return total > 0 ? Math.min(100, Math.round((num(data.value.assetsApproved) / total) * 100)) : 0
})
const approvalColors = [
  { color: '#f04438', percentage: 50 },
  { color: '#f79009', percentage: 80 },
  { color: '#16b364', percentage: 100 },
]

// 平台模块规模（横向柱状对比）
const moduleScaleOption = computed(() => {
  const keys = ['datasources', 'metaTables', 'scripts', 'workflows', 'exports', 'profileJobs', 'qualityRules']
  const palette = chartColors.value
  const items = kpiMeta
    .filter(k => keys.includes(k.key))
    .map((k, i) => ({ name: k.label, value: num(data.value[k.key]), color: palette[i % palette.length] }))
    .sort((a, b) => a.value - b.value)
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 82, right: 40, top: 8, bottom: 16 },
    xAxis: { type: 'value', splitLine: { lineStyle: { type: 'dashed' } } },
    yAxis: { type: 'category', data: items.map(i => i.name) },
    series: [{
      type: 'bar', barWidth: 14,
      data: items.map(i => ({ value: i.value, itemStyle: { color: i.color, borderRadius: [0, 5, 5, 0] } })),
      label: { show: true, position: 'right', formatter: '{c}' }
    }]
  }
})

function fmt(d: Date) {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}
async function load() {
  loading.value = true
  try { data.value = await api.opsOverview(); lastUpd.value = fmt(new Date()) }
  catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false }
}
onMounted(load)
</script>

<style scoped>
.ov-page { display: flex; flex-direction: column; gap: 14px; }

/* 页头 */
.page-head { display: flex; align-items: center; justify-content: space-between; }
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

/* KPI */
.kpi-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(158px, 1fr)); gap: 12px; }
.kpi { display: flex; flex-direction: column; align-items: flex-start; gap: 6px; padding: 16px; border-radius: 10px; border: 1px solid var(--tech-panel-border); background: var(--tech-bg-2); position: relative; overflow: hidden; transition: transform .15s, box-shadow .15s, border-color .15s; }
.kpi:hover { transform: translateY(-2px); border-color: var(--accent); box-shadow: 0 6px 18px rgba(16, 24, 40, 0.1); }
.kpi::after { content: ""; position: absolute; right: -18px; top: -18px; width: 60px; height: 60px; border-radius: 50%; background: color-mix(in srgb, var(--accent) 10%, transparent); }
.kpi-ic { width: 34px; height: 34px; border-radius: 8px; display: inline-flex; align-items: center; justify-content: center; color: var(--accent); background: color-mix(in srgb, var(--accent) 15%, transparent); font-size: 18px; z-index: 1; }
.kpi-num { font-size: 26px; font-weight: 700; color: var(--tech-text); line-height: 1; z-index: 1; }
.kpi-label { font-size: 12px; color: var(--tech-text-muted); z-index: 1; }

/* 图表卡 */
.chart-card { min-height: 0; }
.chart { height: 270px; }
.chart.tall { height: 290px; }

/* 通过率 */
.rate-body { display: flex; flex-direction: column; align-items: center; gap: 14px; padding-top: 6px; }
.rate-pct { font-size: 28px; font-weight: 700; color: var(--tech-text); line-height: 1; }
.rate-unit { font-size: 14px; color: var(--tech-text-muted); margin-left: 2px; }
.rate-sub { font-size: 12px; color: var(--tech-text-muted); margin-top: 4px; }
.rate-meta { display: flex; gap: 18px; font-size: 12px; color: var(--tech-text-muted); }
.rate-meta b { color: var(--tech-text); font-weight: 600; margin-left: 3px; }
.rate-meta b.ok { color: var(--tech-success); }
</style>
