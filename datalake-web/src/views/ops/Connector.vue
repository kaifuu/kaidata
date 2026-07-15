<template>
  <div class="cn-page">
    <!-- 页头 -->
    <div class="page-head">
      <div class="page-head-left">
        <span class="title-icon head-ic"><el-icon><Connection /></el-icon></span>
        <div>
          <div class="page-title">连接器管理</div>
          <div class="page-sub">数据源适配器 · 驱动可用性 · 已登记数</div>
        </div>
      </div>
      <div class="head-right">
        <span class="role-tag">系统管理员</span>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <el-row :gutter="14">
      <!-- 就绪率 -->
      <el-col :span="8">
        <div class="dl-card ov-card gauge-card">
          <div class="card-head"><span class="card-head-title">驱动就绪率</span></div>
          <div class="gauge-body" v-loading="loading">
            <el-progress type="dashboard" :percentage="readyRate" :width="150" :color="readyColors" :stroke-width="12">
              <template #default="{ percentage }">
                <div class="gauge-pct">{{ percentage }}<span class="gauge-unit">%</span></div>
                <div class="gauge-sub">{{ readyCount }} / {{ rows.length }} 就绪</div>
              </template>
            </el-progress>
          </div>
        </div>
      </el-col>
      <!-- KPI -->
      <el-col :span="16">
        <div class="dl-card ov-card">
          <div class="card-head"><span class="card-head-title">适配器概览</span></div>
          <div class="kpi-grid" v-loading="loading">
            <div class="kpi" style="--accent: var(--tech-primary)"><span class="kpi-ic"><el-icon><Connection /></el-icon></span><div class="kpi-num">{{ rows.length }}</div><div class="kpi-label">适配器种类</div></div>
            <div class="kpi" style="--accent: var(--tech-success)"><span class="kpi-ic"><el-icon><CircleCheck /></el-icon></span><div class="kpi-num">{{ readyCount }}</div><div class="kpi-label">驱动就绪</div></div>
            <div class="kpi" style="--accent: var(--tech-danger)"><span class="kpi-ic"><el-icon><CircleClose /></el-icon></span><div class="kpi-num">{{ rows.length - readyCount }}</div><div class="kpi-label">驱动未就绪</div></div>
            <div class="kpi" style="--accent: var(--tech-accent)"><span class="kpi-ic"><el-icon><DataLine /></el-icon></span><div class="kpi-num">{{ totalRegistered }}</div><div class="kpi-label">已登记数据源</div></div>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- 适配器表 -->
    <div class="dl-card ov-card">
      <div class="card-head"><span class="card-head-title">适配器列表</span></div>
      <div class="dl-toolbar">
        <el-input v-model="kw" placeholder="搜索类型（如 mysql / oracle）" clearable size="small" style="width: 220px" @change="resetPage">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-select v-model="fReady" placeholder="驱动状态" clearable size="small" style="width: 140px" @change="resetPage">
          <el-option label="就绪" value="ready" />
          <el-option label="未就绪" value="notready" />
        </el-select>
        <div class="toolbar-actions"><span class="count-badge">命中 <b>{{ filtered.length }}</b></span></div>
      </div>
      <el-table :data="paged" size="small" stripe v-loading="loading">
        <el-table-column label="类型" min-width="140">
          <template #default="{ row }"><el-tag size="small" effect="light">{{ row.type }}</el-tag></template>
        </el-table-column>
        <el-table-column label="驱动可用" width="120">
          <template #default="{ row }">
            <span class="st-pill" :class="row.driverAvailable ? 'success' : 'danger'"><i class="dot" />{{ row.driverAvailable ? '就绪' : '未就绪' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="已登记数据源" width="130">
          <template #default="{ row }"><b>{{ row.registered ?? 0 }}</b></template>
        </el-table-column>
        <el-table-column prop="jarHint" label="未就绪提示" min-width="280" show-overflow-tooltip>
          <template #default="{ row }"><span class="muted">{{ row.jarHint || '—' }}</span></template>
        </el-table-column>
        <template #empty><div class="table-empty"><el-icon class="empty-ic"><FolderOpened /></el-icon><div>{{ rows.length ? '无匹配适配器' : '暂无适配器' }}</div></div></template>
      </el-table>
      <div class="dl-pagination">
        <el-pagination :current-page="page.page" :page-size="page.size" :total="filtered.length"
          :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next, jumper"
          @size-change="onSizeChange" @current-change="onPageChange" />
      </div>
      <div class="hint"><el-icon><InfoFilled /></el-icon> 国产库驱动需手动放 jar；未就绪前可登记数据源，但连通测试会提示。</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Connection, CircleCheck, CircleClose, DataLine, Search, InfoFilled, FolderOpened } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'

const rows = ref<any[]>([])
const loading = ref(false)
const kw = ref('')
const fReady = ref('')

function num(v: any) { return Number(v) || 0 }
const readyCount = computed(() => rows.value.filter((r: any) => r.driverAvailable).length)
const totalRegistered = computed(() => rows.value.reduce((a, r: any) => a + num(r.registered), 0))
const readyRate = computed(() => rows.value.length ? Math.round((readyCount.value / rows.value.length) * 100) : 0)
const readyColors = [
  { color: '#f04438', percentage: 50 },
  { color: '#f79009', percentage: 80 },
  { color: '#16b364', percentage: 100 },
]

const filtered = computed(() => rows.value.filter((r: any) => {
  const okKw = !kw.value || (r.type || '').toLowerCase().includes(kw.value.trim().toLowerCase())
  const okReady = !fReady.value || (fReady.value === 'ready' ? r.driverAvailable : !r.driverAvailable)
  return okKw && okReady
}))
const page = reactive({ page: 1, size: 10 })
const paged = computed(() => filtered.value.slice((page.page - 1) * page.size, page.page * page.size))
function onSizeChange(s: number) { page.size = s; page.page = 1 }
function onPageChange(p: number) { page.page = p }
function resetPage() { page.page = 1 }

async function load() { loading.value = true; resetPage(); try { rows.value = await api.opsConnectors() } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
onMounted(load)
</script>

<style scoped>
.cn-page { display: flex; flex-direction: column; gap: 14px; }
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
.st-pill { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; padding: 2px 9px; border-radius: 10px; }
.st-pill .dot { width: 6px; height: 6px; border-radius: 50%; }
.st-pill.success { color: var(--tech-success); background: color-mix(in srgb, var(--tech-success) 14%, transparent); }
.st-pill.success .dot { background: var(--tech-success); }
.st-pill.danger { color: var(--tech-danger); background: color-mix(in srgb, var(--tech-danger) 14%, transparent); }
.st-pill.danger .dot { background: var(--tech-danger); }
.muted { color: var(--tech-text-muted); font-size: 12px; }
.table-empty { padding: 36px 0; color: var(--tech-text-muted); text-align: center; }
.empty-ic { font-size: 30px; margin-bottom: 8px; opacity: .6; }
.hint { margin-top: 12px; color: var(--tech-text-muted); font-size: 12px; display: flex; align-items: center; gap: 6px; }
</style>
