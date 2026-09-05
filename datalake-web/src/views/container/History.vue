<template>
  <div class="cl-page">
    <div class="page-head">
      <div class="page-head-left">
        <span class="title-icon head-ic"><el-icon><Clock /></el-icon></span>
        <div>
          <div class="page-title">打包历史</div>
          <div class="page-sub">全部镜像构建执行记录 · 状态统计 · 日志回溯</div>
        </div>
      </div>
      <div class="head-right">
        <span class="kpi-mini">总计 <b>{{ all.length }}</b></span>
        <span class="kpi-mini">成功 <b class="ok">{{ count('SUCCESS') }}</b></span>
        <span class="kpi-mini">失败 <b class="ng">{{ count('FAIL') }}</b></span>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <div class="dl-card">
      <div class="st-grid">
        <div v-for="s in chips" :key="s.key" class="st-chip" :class="[s.cls, { active: statusFilter === s.key }]" @click="toggleStatus(s.key)">
          <div class="dc-top"><span class="dc-name">{{ s.label }}</span><span class="dc-dot" /></div>
          <div class="dc-num">{{ s.key === '' ? all.length : count(s.key) }}</div>
          <div class="dc-online">点击{{ statusFilter === s.key ? '取消' : '' }}筛选</div>
        </div>
      </div>
    </div>

    <div class="dl-card">
      <div class="toolbar">
        <el-input v-model="kw" placeholder="搜索 镜像名/Tag/版本号" clearable style="width:220px" @keyup.enter="load" />
        <el-select v-model="statusFilter" clearable placeholder="状态" style="width:130px" @change="load">
          <el-option label="成功" value="SUCCESS" /><el-option label="失败" value="FAIL" />
        </el-select>
        <el-button :icon="Search" type="primary" @click="load">查询</el-button>
        <el-button @click="reset">重置</el-button>
        <div class="toolbar-actions"><span class="count-badge">命中 {{ rows.length }}</span></div>
      </div>
      <el-table :data="paged" v-loading="loading" stripe size="small">
        <el-table-column prop="id" label="执行ID" width="150"><template #default="{ row }"><span class="mono">{{ row.id }}</span></template></el-table-column>
        <el-table-column label="镜像" min-width="170" show-overflow-tooltip>
          <template #default="{ row }"><span class="mono">{{ row.image_name || ('#' + row.version_id) }}{{ row.tag ? ':' + row.tag : '' }}</span></template>
        </el-table-column>
        <el-table-column label="动作" width="80"><template #default="{ row }"><el-tag size="small" type="info">{{ row.action === 'BUILD' ? '构建' : row.action }}</el-tag></template></el-table-column>
        <el-table-column label="状态" width="80"><template #default="{ row }"><el-tag :type="row.status === 'SUCCESS' ? 'success' : 'danger'" size="small">{{ row.status === 'SUCCESS' ? '成功' : '失败' }}</el-tag></template></el-table-column>
        <el-table-column prop="start_time" label="开始时间" width="160" />
        <el-table-column label="耗时" width="90"><template #default="{ row }">{{ duration(row) }}</template></el-table-column>
        <el-table-column prop="triggered_by" label="执行人" width="100" />
        <el-table-column label="错误" min-width="140" show-overflow-tooltip><template #default="{ row }"><span class="err-text">{{ row.error_msg || '-' }}</span></template></el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openLog(row)">查看日志</el-button>
          </template>
        </el-table-column>
        <template #empty><div class="table-empty">暂无打包记录，去「打包发布」发起一次构建</div></template>
      </el-table>
      <div class="dl-pagination">
        <el-pagination :current-page="page.page" :page-size="page.size" :total="rows.length"
          :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next, jumper" size="small" background
          @size-change="onSizeChange" @current-change="onPageChange" />
      </div>
    </div>

    <el-drawer v-model="logDlg" :title="`构建日志 · ${logRow ? logRow.id : ''}`" size="860px" @closed="stopLive">
      <div class="build-head">
        <span>镜像：</span><span class="mono">{{ logRow?.image_name }}{{ logRow?.tag ? ':' + logRow.tag : '' }}</span>
        <span style="margin-left:12px">状态：</span>
        <el-tag :type="logRow?.status === 'SUCCESS' ? 'success' : 'danger'" size="small">{{ logRow?.status === 'SUCCESS' ? '成功' : '失败' }}</el-tag>
        <el-tag v-if="liveRun" type="warning" size="small" class="live-tag"><i class="live-dot" />该版本重新构建中 · 实时接续</el-tag>
      </div>
      <LogStream :log="logText || '（无日志）'" :running="liveRun" />
      <template #footer><el-button type="primary" @click="logDlg = false">关闭</el-button></template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Clock, Refresh, Search } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'
import LogStream from '@/components/LogStream.vue'

const all = ref<any[]>([])
const rows = ref<any[]>([])
const loading = ref(false)
const page = reactive({ page: 1, size: 10 })
const paged = computed(() => rows.value.slice((page.page - 1) * page.size, page.page * page.size))
function onSizeChange(s: number) { page.size = s; page.page = 1 }
function onPageChange(p: number) { page.page = p }
const kw = ref('')
const statusFilter = ref('')

const logDlg = ref(false)
const logRow = ref<any>(null)
const logText = ref('')
const liveRun = ref(false)
let liveTimer: any = null

const chips = [
  { key: '', label: '全部', cls: 'c-all' },
  { key: 'SUCCESS', label: '成功', cls: 'c-ok' },
  { key: 'FAIL', label: '失败', cls: 'c-ng' }
]

async function load() {
  page.page = 1
  loading.value = true
  try { all.value = await api.containerBuildRunAll({ kw: kw.value, status: statusFilter.value }) } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false }
  rows.value = all.value
}
function reset() { kw.value = ''; statusFilter.value = ''; load() }
function toggleStatus(k: string) { statusFilter.value = statusFilter.value === k ? '' : k; load() }
function count(status: string) { return all.value.filter((r) => r.status === status).length }

async function openLog(row: any) {
  logRow.value = row; logText.value = ''; liveRun.value = false; logDlg.value = true
  try { const d: any = await api.containerBuildRunDetail(row.id); logText.value = d.log_text || d.error_msg || '' }
  catch (e: any) { ElMessage.error(errMsg(e)) }
  // 探测该版本是否有正在进行的构建（内存 live 态）：有则切到实时日志接续滚屏
  try {
    const st: any = await api.containerBuildStatus(row.version_id)
    if (st.status === 'RUNNING') { liveRun.value = true; pollLive(row.version_id) }
  } catch { /* 探测失败不影响历史日志查看 */ }
}
function pollLive(versionId: number) {
  stopLive()
  liveTimer = setInterval(async () => {
    try {
      const st = await api.containerBuildStatus(versionId)
      if (st.status === 'RUNNING') { logText.value = st.log || '' }
      else {
        stopLive()
        logText.value = st.log || logText.value   // 终态日志落定
        load()                                     // 历史列表同步出新记录
      }
    } catch (e: any) { stopLive() }
  }, 2000)
}
function stopLive() { if (liveTimer) { clearInterval(liveTimer); liveTimer = null } liveRun.value = false }

function duration(row: any): string {
  if (!row.start_time || !row.end_time) return '-'
  const ms = new Date(row.end_time).getTime() - new Date(row.start_time).getTime()
  if (isNaN(ms) || ms < 0) return '-'
  if (ms < 1000) return ms + 'ms'
  if (ms < 60000) return (ms / 1000).toFixed(1) + 's'
  if (ms < 3600000) return Math.floor(ms / 60000) + 'm' + Math.round((ms % 60000) / 1000) + 's'
  return (ms / 3600000).toFixed(1) + 'h'
}

onMounted(load)
onBeforeUnmount(stopLive)
</script>

<style scoped>
.cl-page { display: flex; flex-direction: column; gap: 14px; }
.page-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.page-head-left { display: flex; align-items: center; gap: 10px; }
.head-ic { font-size: 22px; display: inline-flex; color: var(--tech-primary); }
.page-title { font-size: 18px; font-weight: 700; color: var(--tech-text); }
.page-sub { font-size: 12px; color: var(--tech-text-muted); margin-top: 2px; }
.head-right { display: flex; align-items: center; gap: 10px; }
.kpi-mini { font-size: 12px; color: var(--tech-text-muted); }
.kpi-mini b.ok { color: var(--tech-success); }
.kpi-mini b.ng { color: var(--tech-danger); }
.dl-card { background: var(--tech-bg-2, var(--el-bg-color)); border: 1px solid var(--tech-panel-border, var(--el-border-color)); border-radius: 12px; padding: 14px; }
.st-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 10px; }
.st-chip { border: 1px solid var(--tech-panel-border, var(--el-border-color)); border-radius: 10px; padding: 10px 12px; cursor: pointer; transition: transform .15s ease, box-shadow .15s ease; }
.st-chip:hover { transform: translateY(-2px); }
.st-chip.active { box-shadow: 0 0 0 1px var(--tech-primary) inset; }
.dc-top { display: flex; justify-content: space-between; align-items: center; }
.dc-name { font-size: 12px; color: var(--tech-text-muted); }
.dc-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--tech-primary); }
.c-ok .dc-dot { background: var(--tech-success); }
.c-ng .dc-dot { background: var(--tech-danger); }
.dc-num { font-size: 26px; font-weight: 700; color: var(--tech-text); margin-top: 4px; }
.c-ok .dc-num { color: var(--tech-success); }
.c-ng .dc-num { color: var(--tech-danger); }
.dc-online { font-size: 11px; color: var(--tech-text-muted); margin-top: 2px; }
.toolbar { display: flex; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; align-items: center; }
.toolbar-actions { margin-left: auto; }
.count-badge { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border, var(--el-border-color)); border-radius: 10px; padding: 2px 10px; }
.mono { font-family: ui-monospace, Menlo, monospace; font-size: 12px; }
.err-text { color: var(--tech-danger, #f56c6c); font-size: 12px; }
.table-empty { padding: 32px 0; color: var(--tech-text-muted); text-align: center; }
.build-head { margin-bottom: 8px; font-size: 13px; color: var(--tech-text); display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.live-tag { display: inline-flex; align-items: center; gap: 5px; margin-left: 6px; }
.live-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--tech-warn); animation: live-blink 1s ease-in-out infinite; }
@keyframes live-blink { 0%, 100% { opacity: .25 } 50% { opacity: 1 } }
</style>
