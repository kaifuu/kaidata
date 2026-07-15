<template>
  <div class="tc-page">
    <!-- 页头 -->
    <div class="page-head">
      <div class="page-head-left">
        <span class="title-icon head-ic"><el-icon><List /></el-icon></span>
        <div>
          <div class="page-title">任务中心</div>
          <div class="page-sub">跨域统一任务视图：探查 · 质量 · 工作流 · 接出 · 离线</div>
        </div>
      </div>
      <div class="head-right">
        <span class="kpi-mini">总计 <b>{{ totals.total }}</b></span>
        <span class="kpi-mini">运行中 <b class="ok">{{ totals.online }}</b></span>
        <span class="kpi-mini">任务域 <b>{{ totals.domains }}</b></span>
        <span class="role-tag">系统管理员</span>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <!-- 域概览（点击筛选） -->
    <div class="dl-card ov-card">
      <div class="card-head">
        <span class="card-head-title">任务域概览</span>
        <span class="count-badge">点击卡片可按域筛选</span>
      </div>
      <div class="domain-grid" v-loading="loading">
        <div v-for="d in domainStats" :key="d.domain" class="domain-chip"
             :class="{ active: fDomain === d.domain, [d.tag]: true }" @click="toggleDomain(d.domain)">
          <div class="dc-top"><span class="dc-name">{{ d.domain }}</span><span class="dc-dot" /></div>
          <div class="dc-num">{{ d.total }}</div>
          <div class="dc-online">运行中 <b>{{ d.online }}</b> / 停用 <b>{{ d.total - d.online }}</b></div>
        </div>
        <div v-if="!rows.length && !loading" class="muted" style="padding:12px 0">暂无任务</div>
      </div>
    </div>

    <!-- 任务列表 -->
    <div class="dl-card ov-card">
      <div class="card-head"><span class="card-head-title">任务列表</span></div>
      <div class="dl-toolbar">
        <el-select v-model="fDomain" placeholder="任务域" clearable size="small" style="width:150px" @change="resetPage">
          <el-option v-for="d in domains" :key="d" :label="d" :value="d" />
        </el-select>
        <el-select v-model="fStatus" placeholder="状态" clearable size="small" style="width:140px" @change="resetPage">
          <el-option v-for="s in STATUS_FILTERS" :key="s" :label="s" :value="s" />
        </el-select>
        <el-input v-model="fName" placeholder="搜索任务名" clearable size="small" style="width:200px" @change="resetPage">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <div class="toolbar-actions">
          <span class="count-badge">命中 <b>{{ filtered.length }}</b></span>
        </div>
      </div>
      <el-table :data="paged" size="small" stripe v-loading="loading">
        <el-table-column label="任务域" width="120">
          <template #default="{ row }"><el-tag size="small" :type="domainTag(row.domain)" effect="light">{{ row.domain }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="id" label="ID" width="130" />
        <el-table-column label="名称" min-width="220">
          <template #default="{ row }"><span class="task-name">{{ row.name }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <span class="st-pill" :class="statusInfo(row.status).type">
              <i class="dot" />{{ statusInfo(row.status).label }}
            </span>
          </template>
        </el-table-column>
        <template #empty>
          <div class="table-empty"><el-icon class="empty-ic"><FolderOpened /></el-icon><div>{{ rows.length ? '无匹配任务' : '暂无任务' }}</div></div>
        </template>
      </el-table>
      <div class="dl-pagination">
        <el-pagination :current-page="page.page" :page-size="page.size" :total="filtered.length"
          :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next, jumper"
          @size-change="onSizeChange" @current-change="onPageChange" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, List, Search, FolderOpened } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'

const rows = ref<any[]>([])
const loading = ref(false)
const fDomain = ref('')
const fStatus = ref('')
const fName = ref('')

const STATUS_FILTERS = ['运行中', '已停用', '待审核', '异常']

// 各域原始 status 五花八门（ONLINE/OFFLINE/ENABLED/通过/待审…），归一成统一语义
function statusInfo(s: string) {
  const u = (s || '').toUpperCase()
  if (['ONLINE', 'ENABLED'].includes(u) || s === '通过') return { label: '运行中', type: 'success' }
  if (['OFFLINE', 'DISABLED'].includes(u) || s === '草稿') return { label: '已停用', type: 'info' }
  if (s === '待审') return { label: '待审核', type: 'warning' }
  if (['驳回', 'FAIL', 'ERROR'].includes(u) || u.startsWith('FAIL')) return { label: '异常', type: 'danger' }
  return { label: s || '—', type: '' }
}
function isOnline(s: string) { return statusInfo(s).type === 'success' }
function domainTag(d: string): any {
  return ({ 探查: 'success', 质量任务: 'danger', 工作流: 'warning', 数据接出: 'info', 离线接入: 'primary' } as any)[d] || ''
}

const domains = computed(() => [...new Set(rows.value.map((r: any) => r.domain))])
const domainStats = computed(() => domains.value.map((d) => {
  const list = rows.value.filter((r: any) => r.domain === d)
  return { domain: d, total: list.length, online: list.filter((r: any) => isOnline(r.status)).length, tag: domainTag(d) }
}))
const totals = computed(() => ({
  total: rows.value.length,
  online: rows.value.filter((r: any) => isOnline(r.status)).length,
  domains: domains.value.length,
}))

const filtered = computed(() => rows.value.filter((r: any) =>
  (!fDomain.value || r.domain === fDomain.value) &&
  (!fStatus.value || statusInfo(r.status).label === fStatus.value) &&
  (!fName.value || (r.name || '').toLowerCase().includes(fName.value.trim().toLowerCase()))))

const page = reactive({ page: 1, size: 10 })
const paged = computed(() => filtered.value.slice((page.page - 1) * page.size, page.page * page.size))
function onSizeChange(s: number) { page.size = s; page.page = 1 }
function onPageChange(p: number) { page.page = p }
function resetPage() { page.page = 1 }
function toggleDomain(d: string) { fDomain.value = fDomain.value === d ? '' : d; resetPage() }

async function load() {
  loading.value = true; resetPage()
  try { rows.value = await api.opsTasks() } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false }
}
onMounted(load)
</script>

<style scoped>
.tc-page { display: flex; flex-direction: column; gap: 14px; }

/* 页头 */
.page-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.page-head-left { display: flex; align-items: center; gap: 10px; }
.head-ic { font-size: 22px; display: inline-flex; }
.page-title { font-size: 18px; font-weight: 700; color: var(--tech-text); }
.page-sub { font-size: 12px; color: var(--tech-text-muted); margin-top: 2px; }
.head-right { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 3px 10px; border-radius: 12px; background: var(--el-fill-color-light); }
.kpi-mini { font-size: 12px; color: var(--tech-text-muted); }
.kpi-mini b { color: var(--tech-text); font-size: 14px; margin-left: 2px; }
.kpi-mini b.ok { color: var(--tech-success); }

/* 卡片头 */
.ov-card { padding: 14px; }
.card-head { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
.card-head-title { display: flex; align-items: center; gap: 7px; font-size: 14px; font-weight: 700; color: var(--tech-text); }
.card-head .count-badge { margin-left: auto; }

/* 域概览 chips */
.domain-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; }
.domain-chip { border: 1px solid var(--tech-panel-border); border-radius: 10px; padding: 14px; cursor: pointer; background: var(--tech-bg-2); transition: transform .15s, border-color .15s, box-shadow .15s; }
.domain-chip:hover { transform: translateY(-2px); box-shadow: 0 6px 18px rgba(16, 24, 40, 0.1); }
.domain-chip.active { border-color: var(--tech-primary); box-shadow: 0 0 0 1px var(--tech-primary) inset; }
.dc-top { display: flex; align-items: center; justify-content: space-between; }
.dc-name { font-size: 13px; color: var(--tech-text-muted); }
.dc-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--tech-primary); }
.domain-chip.success .dc-dot { background: var(--tech-success); }
.domain-chip.danger .dc-dot { background: var(--tech-danger); }
.domain-chip.warning .dc-dot { background: var(--tech-warn); }
.domain-chip.info .dc-dot { background: var(--tech-text-muted); }
.dc-num { font-size: 26px; font-weight: 700; color: var(--tech-text); margin: 4px 0; }
.dc-online { font-size: 11px; color: var(--tech-text-muted); }
.dc-online b { color: var(--tech-text); }

/* 列表 */
.task-name { font-weight: 600; color: var(--tech-text); }
.st-pill { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; padding: 2px 9px; border-radius: 10px; background: var(--el-fill-color-light); color: var(--tech-text-muted); }
.st-pill .dot { width: 6px; height: 6px; border-radius: 50%; background: var(--tech-text-muted); }
.st-pill.success { color: var(--tech-success); background: color-mix(in srgb, var(--tech-success) 14%, transparent); }
.st-pill.success .dot { background: var(--tech-success); }
.st-pill.info { color: var(--tech-text-muted); }
.st-pill.warning { color: var(--tech-warn); background: color-mix(in srgb, var(--tech-warn) 14%, transparent); }
.st-pill.warning .dot { background: var(--tech-warn); }
.st-pill.danger { color: var(--tech-danger); background: color-mix(in srgb, var(--tech-danger) 14%, transparent); }
.st-pill.danger .dot { background: var(--tech-danger); }
.table-empty { padding: 36px 0; color: var(--tech-text-muted); text-align: center; }
.empty-ic { font-size: 30px; margin-bottom: 8px; opacity: .6; }
.muted { color: var(--tech-text-muted); font-size: 13px; }
</style>
