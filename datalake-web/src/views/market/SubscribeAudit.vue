<template>
  <div class="sa-page">
    <!-- 页头 -->
    <div class="page-head">
      <div class="page-head-left">
        <span class="title-icon head-ic"><el-icon><Checked /></el-icon></span>
        <div>
          <div class="page-title">订阅审核</div>
          <div class="page-sub">审核数据订阅申请 · 通过自动签发 appKey</div>
        </div>
      </div>
      <div class="head-right">
        <span class="role-tag">系统管理员</span>
        <el-button :icon="Refresh" :loading="loading" @click="loadAudit">刷新</el-button>
      </div>
    </div>

    <!-- 状态概览（点击切换筛选；仅"待审/全部"触发后端，其余客户端筛） -->
    <div class="dl-card ov-card">
      <div class="card-head"><span class="card-head-title">审核概览</span><span v-if="pendingN" class="count-badge">待处理 <b class="warn">{{ pendingN }}</b></span></div>
      <div class="stat-grid">
        <div v-for="s in statCards" :key="s.key" class="stat-chip" :class="[s.cls, { active: activeKey === s.key }]" @click="onPickStatus(s.key)">
          <span class="sc-num">{{ s.n }}</span><span class="sc-lab">{{ s.label }}</span>
        </div>
      </div>
    </div>

    <!-- 列表 -->
    <div class="dl-card ov-card">
      <div class="card-head"><span class="card-head-title">申请列表</span><span class="count-badge">命中 <b>{{ shown.length }}</b></span></div>
      <el-table :data="paged" size="small" stripe v-loading="loading">
        <el-table-column prop="username" label="订阅人" width="92" />
        <el-table-column prop="table_name" label="库表" min-width="140" show-overflow-tooltip />
        <el-table-column label="方式" width="70"><template #default="{ row }"><el-tag size="small" effect="light">{{ row.open_type }}</el-tag></template></el-table-column>
        <el-table-column prop="purpose" label="用途" min-width="140" show-overflow-tooltip />
        <el-table-column label="限流" width="128">
          <template #default="{ row }"><span class="muted">{{ row.limit_count > 0 ? row.limit_count + '次' : '不限' }} / {{ row.limit_qps > 0 ? row.limit_qps + '/s' : '不限' }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="84">
          <template #default="{ row }"><span class="st-pill" :class="stType(row.status)"><i class="dot" />{{ row.status }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <el-button v-if="row.status === '待审'" link size="small" type="success" @click="doApprove(row)">通过</el-button>
            <el-button v-if="row.status === '待审'" link size="small" type="danger" @click="doReject(row)">驳回</el-button>
          </template>
        </el-table-column>
        <template #empty><div class="table-empty"><el-icon class="empty-ic"><FolderOpened /></el-icon><div>{{ rows.length ? '无匹配申请' : '暂无申请' }}</div></div></template>
      </el-table>
      <div class="dl-pagination">
        <el-pagination :current-page="page.page" :page-size="page.size" :total="shown.length"
          :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next, jumper"
          @size-change="onSizeChange" @current-change="onPageChange" />
      </div>
    </div>

    <!-- 审核意见 dialog -->
    <el-dialog v-model="cmtDlg" :title="cmtTitle" width="420px">
      <el-input v-model="cmt" type="textarea" :rows="3" placeholder="审核意见（可选）" />
      <template #footer><el-button @click="cmtDlg = false">取消</el-button><el-button type="primary" @click="confirmCmt">确定</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Checked, Refresh, FolderOpened } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'

// 一次性拉取全部申请、状态筛选在前端做（量不大；这样待审/通过/驳回计数才都准确）
const activeKey = ref('')
const rows = ref<any[]>([])
const loading = ref(false)
const cmtDlg = ref(false)
const cmtTitle = ref('')
const cmt = ref('')
const pendingAct = ref<{ id: number; act: 'approve' | 'reject' } | null>(null)

const stType = (s: string): any => ({ 通过: 'success', 待审: 'warning', 驳回: 'danger', 草稿: 'info' } as any)[s] || ''
const cnt = (s: string) => rows.value.filter((r) => r.status === s).length
const pendingN = computed(() => cnt('待审'))
const statCards = computed(() => [
  { key: '待审', label: '待审核', n: cnt('待审'), cls: 'warning' },
  { key: '通过', label: '已通过', n: cnt('通过'), cls: 'success' },
  { key: '驳回', label: '已驳回', n: cnt('驳回'), cls: 'danger' },
  { key: '', label: '全部', n: rows.value.length, cls: 'all' },
])
const shown = computed(() => activeKey.value === '' ? rows.value : rows.value.filter((r) => r.status === activeKey.value))

const page = reactive({ page: 1, size: 10 })
const paged = computed(() => shown.value.slice((page.page - 1) * page.size, page.page * page.size))
function onSizeChange(s: number) { page.size = s; page.page = 1 }
function onPageChange(p: number) { page.page = p }
function onPickStatus(k: string) { activeKey.value = activeKey.value === k ? '' : k; page.page = 1 }

async function loadAudit() {
  loading.value = true
  try { rows.value = await api.subAuditList(undefined) } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false }
}
function doApprove(row: any) { pendingAct.value = { id: row.id, act: 'approve' }; cmtTitle.value = '通过订阅'; cmt.value = ''; cmtDlg.value = true }
function doReject(row: any) { pendingAct.value = { id: row.id, act: 'reject' }; cmtTitle.value = '驳回订阅'; cmt.value = ''; cmtDlg.value = true }
async function confirmCmt() {
  if (!pendingAct.value) return
  const { id, act } = pendingAct.value
  try {
    if (act === 'approve') { const r: any = await api.subApprove(id, cmt.value); ElMessage.success(r.app_key ? `已通过，appKey: ${r.app_key}` : '已通过') }
    else { await api.subReject(id, cmt.value); ElMessage.success('已驳回') }
    cmtDlg.value = false; await loadAudit()
  } catch (e: any) { ElMessage.error(errMsg(e)) }
}

onMounted(loadAudit)
</script>

<style scoped>
.sa-page { display: flex; flex-direction: column; gap: 14px; }
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
.card-head .count-badge .warn { color: var(--tech-warn); }
.stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap: 12px; }
.stat-chip { border: 1px solid var(--tech-panel-border); border-radius: 10px; padding: 14px; cursor: pointer; background: var(--tech-bg-2); transition: transform .15s, border-color .15s; display: flex; flex-direction: column; gap: 4px; }
.stat-chip:hover { transform: translateY(-2px); }
.stat-chip.active { border-color: var(--tech-primary); box-shadow: 0 0 0 1px var(--tech-primary) inset; }
.sc-num { font-size: 24px; font-weight: 700; color: var(--tech-text); }
.sc-lab { font-size: 12px; color: var(--tech-text-muted); }
.stat-chip.success .sc-num { color: var(--tech-success); }
.stat-chip.warning .sc-num { color: var(--tech-warn); }
.stat-chip.danger .sc-num { color: var(--tech-danger); }
.st-pill { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; padding: 2px 9px; border-radius: 10px; background: var(--el-fill-color-light); color: var(--tech-text-muted); }
.st-pill .dot { width: 6px; height: 6px; border-radius: 50%; background: var(--tech-text-muted); }
.st-pill.success { color: var(--tech-success); background: color-mix(in srgb, var(--tech-success) 14%, transparent); } .st-pill.success .dot { background: var(--tech-success); }
.st-pill.warning { color: var(--tech-warn); background: color-mix(in srgb, var(--tech-warn) 14%, transparent); } .st-pill.warning .dot { background: var(--tech-warn); }
.st-pill.danger { color: var(--tech-danger); background: color-mix(in srgb, var(--tech-danger) 14%, transparent); } .st-pill.danger .dot { background: var(--tech-danger); }
.muted { color: var(--tech-text-muted); font-size: 12px; }
.table-empty { padding: 36px 0; color: var(--tech-text-muted); text-align: center; }
.empty-ic { font-size: 30px; margin-bottom: 8px; opacity: .6; }
</style>
