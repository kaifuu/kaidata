<template>
  <div class="rs-page">
    <!-- 页头 -->
    <div class="page-head">
      <div class="page-head-left">
        <span class="title-icon head-ic"><el-icon><Monitor /></el-icon></span>
        <div>
          <div class="page-title">资源监控</div>
          <div class="page-sub">服务进程 JVM 内存 · StarRocks 存储节点</div>
        </div>
      </div>
      <div class="head-right">
        <span class="role-tag">系统管理员</span>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <el-row :gutter="14">
      <!-- JVM 使用率 -->
      <el-col :span="8">
        <div class="dl-card ov-card gauge-card">
          <div class="card-head"><span class="card-head-title">JVM 内存使用率</span></div>
          <div class="gauge-body" v-loading="loading">
            <el-progress type="dashboard" :percentage="jvmRate" :width="156" :color="memColors" :stroke-width="12">
              <template #default="{ percentage }">
                <div class="gauge-pct">{{ percentage }}<span class="gauge-unit">%</span></div>
                <div class="gauge-sub">已用 / 上限</div>
              </template>
            </el-progress>
            <div class="gauge-meta">
              <span>已用 <b>{{ data.jvmUsedMB ?? 0 }}</b> MB</span>
              <span>上限 <b>{{ data.jvmMaxMB ?? 0 }}</b> MB</span>
              <span class="free">剩余 <b>{{ jvmFree }}</b> MB</span>
            </div>
          </div>
        </div>
      </el-col>
      <!-- KPI -->
      <el-col :span="16">
        <div class="dl-card ov-card">
          <div class="card-head"><span class="card-head-title">运行环境</span><span class="count-badge">实时</span></div>
          <div class="kpi-grid" v-loading="loading">
            <div class="kpi" style="--accent: var(--tech-primary)"><span class="kpi-ic"><el-icon><Coin /></el-icon></span><div class="kpi-num">{{ data.jvmUsedMB ?? 0 }}</div><div class="kpi-label">JVM 已用 (MB)</div></div>
            <div class="kpi" style="--accent: var(--tech-primary-2)"><span class="kpi-ic"><el-icon><Histogram /></el-icon></span><div class="kpi-num">{{ data.jvmMaxMB ?? 0 }}</div><div class="kpi-label">JVM 上限 (MB)</div></div>
            <div class="kpi" style="--accent: var(--tech-success)"><span class="kpi-ic"><el-icon><CircleCheck /></el-icon></span><div class="kpi-num">{{ jvmFree }}</div><div class="kpi-label">剩余 (MB)</div></div>
            <div class="kpi" style="--accent: var(--tech-accent)"><span class="kpi-ic"><el-icon><Cpu /></el-icon></span><div class="kpi-num">{{ data.processors ?? 0 }}</div><div class="kpi-label">CPU 核数</div></div>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- StarRocks BE -->
    <div class="dl-card ov-card">
      <div class="card-head">
        <span class="card-head-title">StarRocks Backends · 存储节点</span>
        <span class="count-badge">共 <b>{{ (data.starrocksBackends || []).length }}</b> 个</span>
      </div>
      <el-table :data="data.starrocksBackends || []" size="small" stripe max-height="440" v-loading="loading">
        <el-table-column v-for="c in beCols" :key="c" :prop="c" :label="c" min-width="120" show-overflow-tooltip />
        <template #empty><div class="table-empty"><el-icon class="empty-ic"><FolderOpened /></el-icon><div>暂无存储节点</div></div></template>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Monitor, Coin, Histogram, CircleCheck, Cpu, FolderOpened } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'

const data = ref<any>({})
const loading = ref(false)

const jvmFree = computed(() => Math.max(0, (num(data.value.jvmMaxMB) - num(data.value.jvmUsedMB))))
const jvmRate = computed(() => {
  const max = num(data.value.jvmMaxMB)
  return max > 0 ? Math.min(100, Math.round((num(data.value.jvmUsedMB) / max) * 100)) : 0
})
const memColors = [
  { color: '#16b364', percentage: 70 },
  { color: '#f79009', percentage: 88 },
  { color: '#f04438', percentage: 100 },
]
function num(v: any) { return Number(v) || 0 }

const beCols = computed(() => { const arr = data.value.starrocksBackends || []; return arr[0] ? Object.keys(arr[0]).slice(0, 10) : [] })

async function load() { loading.value = true; try { data.value = await api.opsResource() } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
onMounted(load)
</script>

<style scoped>
.rs-page { display: flex; flex-direction: column; gap: 14px; }
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
.kpi-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; }
.kpi { display: flex; flex-direction: column; align-items: flex-start; gap: 6px; padding: 16px; border-radius: 10px; border: 1px solid var(--tech-panel-border); background: var(--tech-bg-2); }
.kpi-ic { width: 34px; height: 34px; border-radius: 8px; display: inline-flex; align-items: center; justify-content: center; color: var(--accent); background: color-mix(in srgb, var(--accent) 15%, transparent); font-size: 18px; }
.kpi-num { font-size: 26px; font-weight: 700; color: var(--tech-text); line-height: 1; }
.kpi-label { font-size: 12px; color: var(--tech-text-muted); }
.gauge-body { display: flex; flex-direction: column; align-items: center; gap: 14px; padding-top: 6px; }
.gauge-pct { font-size: 30px; font-weight: 700; color: var(--tech-text); line-height: 1; }
.gauge-unit { font-size: 14px; color: var(--tech-text-muted); margin-left: 2px; }
.gauge-sub { font-size: 12px; color: var(--tech-text-muted); margin-top: 4px; }
.gauge-meta { display: flex; gap: 16px; font-size: 12px; color: var(--tech-text-muted); flex-wrap: wrap; justify-content: center; }
.gauge-meta b { color: var(--tech-text); font-weight: 600; margin-left: 3px; }
.gauge-meta .free b { color: var(--tech-success); }
.table-empty { padding: 36px 0; color: var(--tech-text-muted); text-align: center; }
.empty-ic { font-size: 30px; margin-bottom: 8px; opacity: .6; }
</style>
