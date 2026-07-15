<template>
  <div class="mo-page">
    <!-- 页头 -->
    <div class="page-head">
      <div class="page-head-left">
        <span class="title-icon head-ic"><el-icon><DataAnalysis /></el-icon></span>
        <div>
          <div class="page-title">资源概览</div>
          <div class="page-sub">数据集市 · 开放接口 / 库表 / 数据源</div>
        </div>
      </div>
      <div class="head-right">
        <span class="role-tag">数据集市</span>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <!-- KPI -->
    <div class="dl-card ov-card">
      <div class="card-head"><span class="card-head-title">资源总览</span><span class="count-badge">实时</span></div>
      <div class="kpi-grid" v-loading="loading">
        <div class="kpi" style="--accent: var(--tech-primary)"><span class="kpi-ic"><el-icon><Connection /></el-icon></span><div class="kpi-num">{{ data.serviceCount ?? 0 }}</div><div class="kpi-label">开放接口</div></div>
        <div class="kpi" style="--accent: var(--tech-accent)"><span class="kpi-ic"><el-icon><Grid /></el-icon></span><div class="kpi-num">{{ data.tableCount ?? 0 }}</div><div class="kpi-label">库表资源</div></div>
        <div class="kpi" style="--accent: var(--tech-primary-2)"><span class="kpi-ic"><el-icon><Coin /></el-icon></span><div class="kpi-num">{{ data.datasourceCount ?? 0 }}</div><div class="kpi-label">数据源</div></div>
        <div class="kpi" style="--accent: var(--tech-warn)"><span class="kpi-ic"><el-icon><ShoppingCart /></el-icon></span><div class="kpi-num">{{ data.cartCount ?? 0 }}</div><div class="kpi-label">我的购物车</div></div>
      </div>
    </div>

    <!-- 库表分布 -->
    <div class="dl-card ov-card chart-card">
      <div class="card-head">
        <span class="card-head-title">库表资源 · 按数据源分布</span>
        <span class="count-badge">共 <b>{{ (data.byDs || []).length }}</b> 个数据源</span>
      </div>
      <v-chart class="chart" :option="byDsOption" :theme="chartTheme" autoresize v-loading="loading" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { DataAnalysis, Connection, Grid, Coin, ShoppingCart, Refresh } from '@element-plus/icons-vue'
import { VChart } from '@/echarts'
import { theme } from '@/theme'
import { api, errMsg } from '@/api'

const chartTheme = theme.chartTheme
const data = ref<any>({})
const loading = ref(false)

function num(v: any) { return Number(v) || 0 }
const chartColors = computed(() => theme.isDark.value
  ? ['#00e0ff', '#2f6bff', '#7c5cff', '#2ee6a6', '#ffb020', '#ff4d6d']
  : ['#1557ef', '#4f46e5', '#7c5cff', '#16b364', '#f79009', '#f04438'])

const byDsOption = computed(() => {
  const palette = chartColors.value
  const total = num(data.value.tableCount)
  const rows = ((data.value.byDs || []) as any[]).map((d, i) => ({ name: `数据源 #${d.ds_id}`, value: num(d.c), pct: total > 0 ? Math.round(num(d.c) / total * 100) : 0, color: palette[i % palette.length] })).sort((a, b) => a.value - b.value)
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, formatter: (p: any) => `${p[0].name}<br/>表数量 <b>${p[0].value}</b>（占比 ${rows[p[0].dataIndex]?.pct ?? 0}%）` },
    grid: { left: 96, right: 48, top: 8, bottom: 24 },
    xAxis: { type: 'value', minInterval: 1 },
    yAxis: { type: 'category', data: rows.map(r => r.name) },
    series: [{
      type: 'bar', barWidth: 14,
      data: rows.map(r => ({ value: r.value, itemStyle: { color: r.color, borderRadius: [0, 5, 5, 0] } })),
      label: { show: true, position: 'right', formatter: '{c}' }
    }]
  }
})

async function load() { loading.value = true; try { data.value = await api.marketOverview() } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
onMounted(load)
</script>

<style scoped>
.mo-page { display: flex; flex-direction: column; gap: 14px; }
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
.kpi-label { font-size: 12px; color: var(--tech-text-muted); z-index: 1; }
.chart-card { min-height: 0; }
.chart { height: 320px; }
</style>
