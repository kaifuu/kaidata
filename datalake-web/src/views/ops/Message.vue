<template>
  <div class="msg-page">
    <!-- 页头 -->
    <div class="page-head">
      <div class="page-head-left">
        <span class="title-icon head-ic"><el-icon><Bell /></el-icon></span>
        <div>
          <div class="page-title">消息管理</div>
          <div class="page-sub">待办消息统一视图：告警 · 资产审核 · 质量异常 —— 业务侧处理完成后自动从列表消失</div>
        </div>
      </div>
      <div class="head-right">
        <span class="kpi-mini">总计 <b>{{ rows.length }}</b></span>
        <span class="kpi-mini">告警 <b class="danger">{{ bySource.ALERT }}</b></span>
        <span class="kpi-mini">资产 <b class="warn">{{ bySource.ASSET }}</b></span>
        <span class="kpi-mini">质量 <b class="primary">{{ bySource.QUALITY }}</b></span>
        <span class="role-tag">系统管理员</span>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <!-- 来源概览（点击筛选） -->
    <div class="dl-card ov-card">
      <div class="card-head">
        <span class="card-head-title">来源概览</span>
        <span class="count-badge">点击卡片按来源筛选</span>
      </div>
      <div class="domain-grid" v-loading="loading">
        <div v-for="s in SOURCES" :key="s.key" class="domain-chip"
             :class="{ active: fSource === s.key, [s.tag]: true }" @click="toggleSource(s.key)">
          <div class="dc-top"><span class="dc-name">{{ s.label }}</span><span class="dc-dot" /></div>
          <div class="dc-num">{{ bySource[s.key] }}</div>
          <div class="dc-online">{{ s.hint }}</div>
        </div>
        <div v-if="!rows.length && !loading" class="muted" style="padding:12px 0">暂无待办消息 🎉</div>
      </div>
    </div>

    <!-- 消息列表 -->
    <div class="dl-card ov-card">
      <div class="card-head"><span class="card-head-title">消息列表</span></div>
      <div class="dl-toolbar">
        <el-select v-model="fSource" placeholder="来源" clearable size="small" style="width:140px" @change="resetPage">
          <el-option v-for="s in SOURCES" :key="s.key" :label="s.label" :value="s.key" />
        </el-select>
        <el-select v-model="fSeverity" placeholder="级别" clearable size="small" style="width:120px" @change="resetPage">
          <el-option label="高" value="HIGH" />
          <el-option label="中" value="MEDIUM" />
          <el-option label="低" value="LOW" />
        </el-select>
        <el-input v-model="fKeyword" placeholder="搜索标题/摘要" clearable size="small" style="width:220px" @change="resetPage">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <div class="toolbar-actions">
          <span class="count-badge">命中 <b>{{ filtered.length }}</b></span>
        </div>
      </div>
      <el-table :data="paged" size="small" stripe v-loading="loading">
        <el-table-column label="来源" width="100">
          <template #default="{ row }"><el-tag size="small" :type="sourceInfo(row.source).tag" effect="light">{{ sourceInfo(row.source).label }}</el-tag></template>
        </el-table-column>
        <el-table-column label="标题" min-width="200">
          <template #default="{ row }"><span class="msg-title">{{ row.title || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="摘要" min-width="240">
          <template #default="{ row }"><span class="msg-summary">{{ row.summary || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="级别" width="90">
          <template #default="{ row }">
            <span class="st-pill" :class="sevInfo(row.severity).type"><i class="dot" />{{ sevInfo(row.severity).label }}</span>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="160">
          <template #default="{ row }"><span class="muted">{{ fmtTime(row.createTime) }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" plain @click="go(row)">去处理</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="table-empty"><el-icon class="empty-ic"><BellFilled /></el-icon><div>{{ rows.length ? '无匹配消息' : '暂无待办消息' }}</div></div>
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
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Bell, BellFilled, Refresh, Search } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'

const router = useRouter()
const rows = ref<any[]>([])
const loading = ref(false)
const fSource = ref('')
const fSeverity = ref('')
const fKeyword = ref('')

const SOURCES = [
  { key: 'ALERT', label: '告警', tag: 'danger', hint: '未处理告警' },
  { key: 'ASSET', label: '资产', tag: 'warning', hint: '待审资产' },
  { key: 'QUALITY', label: '质量', tag: 'primary', hint: '质量异常' },
]

function sourceInfo(s: string) {
  const found = SOURCES.find((x) => x.key === s)
  return { label: found?.label || s, tag: found?.tag || '' }
}
function sevInfo(s: string) {
  if (s === 'HIGH') return { label: '高', type: 'danger' }
  if (s === 'MEDIUM') return { label: '中', type: 'warning' }
  return { label: '低', type: 'info' }
}

const bySource = computed(() => {
  const c: Record<string, number> = { ALERT: 0, ASSET: 0, QUALITY: 0 }
  for (const r of rows.value) c[r.source] = (c[r.source] || 0) + 1
  return c
})

const filtered = computed(() => rows.value.filter((r: any) =>
  (!fSource.value || r.source === fSource.value) &&
  (!fSeverity.value || r.severity === fSeverity.value) &&
  (!fKeyword.value || ((r.title || '') + ' ' + (r.summary || '')).toLowerCase().includes(fKeyword.value.trim().toLowerCase()))))

const page = reactive({ page: 1, size: 10 })
const paged = computed(() => filtered.value.slice((page.page - 1) * page.size, page.page * page.size))
function onSizeChange(s: number) { page.size = s; page.page = 1 }
function onPageChange(p: number) { page.page = p }
function resetPage() { page.page = 1 }
function toggleSource(k: string) { fSource.value = fSource.value === k ? '' : k; resetPage() }

function go(row: any) { if (row.handlePath) router.push(row.handlePath) }

function fmtTime(s: string) {
  if (!s) return '—'
  return String(s).replace('T', ' ').slice(0, 16)
}

async function load() {
  loading.value = true; resetPage()
  try { rows.value = await api.opsMessages() } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false }
}
onMounted(load)
</script>

<style scoped>
.msg-page { display: flex; flex-direction: column; gap: 14px; }
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
.kpi-mini b.danger { color: var(--tech-danger); }
.kpi-mini b.warn { color: var(--tech-warn); }
.kpi-mini b.primary { color: var(--tech-primary); }

/* 卡片头 */
.ov-card { padding: 14px; }
.card-head { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
.card-head-title { display: flex; align-items: center; gap: 7px; font-size: 14px; font-weight: 700; color: var(--tech-text); }
.card-head .count-badge { margin-left: auto; }

/* 来源概览 chips */
.domain-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; }
.domain-chip { border: 1px solid var(--tech-panel-border); border-radius: 10px; padding: 14px; cursor: pointer; background: var(--tech-bg-2); transition: transform .15s, border-color .15s, box-shadow .15s; }
.domain-chip:hover { transform: translateY(-2px); box-shadow: 0 6px 18px rgba(16, 24, 40, 0.1); }
.domain-chip.active { border-color: var(--tech-primary); box-shadow: 0 0 0 1px var(--tech-primary) inset; }
.dc-top { display: flex; align-items: center; justify-content: space-between; }
.dc-name { font-size: 13px; color: var(--tech-text-muted); }
.dc-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--tech-primary); }
.domain-chip.danger .dc-dot { background: var(--tech-danger); }
.domain-chip.warning .dc-dot { background: var(--tech-warn); }
.domain-chip.primary .dc-dot { background: var(--tech-primary); }
.dc-num { font-size: 26px; font-weight: 700; color: var(--tech-text); margin: 4px 0; }
.dc-online { font-size: 11px; color: var(--tech-text-muted); }

/* 列表 */
.msg-title { font-weight: 600; color: var(--tech-text); }
.msg-summary { color: var(--tech-text-muted); font-size: 13px; }
.st-pill { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; padding: 2px 9px; border-radius: 10px; background: var(--el-fill-color-light); color: var(--tech-text-muted); }
.st-pill .dot { width: 6px; height: 6px; border-radius: 50%; background: var(--tech-text-muted); }
.st-pill.danger { color: var(--tech-danger); background: color-mix(in srgb, var(--tech-danger) 14%, transparent); }
.st-pill.danger .dot { background: var(--tech-danger); }
.st-pill.warning { color: var(--tech-warn); background: color-mix(in srgb, var(--tech-warn) 14%, transparent); }
.st-pill.warning .dot { background: var(--tech-warn); }
.st-pill.info { color: var(--tech-text-muted); }
.table-empty { padding: 36px 0; color: var(--tech-text-muted); text-align: center; }
.empty-ic { font-size: 30px; margin-bottom: 8px; opacity: .6; }
.muted { color: var(--tech-text-muted); font-size: 13px; }
</style>
