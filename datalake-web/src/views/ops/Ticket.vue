<template>
  <div class="tk-page">
    <!-- 页头 -->
    <div class="page-head">
      <div class="page-head-left">
        <span class="title-icon head-ic"><el-icon><Tickets /></el-icon></span>
        <div>
          <div class="page-title">工单中心</div>
          <div class="page-sub">质量检测 FAIL 自动建单 → 派单 → 处理 → 解决 → 验收关闭，全程流转留痕 · SLA 超期预警</div>
        </div>
      </div>
      <div class="head-right">
        <span class="kpi-mini">待派单 <b>{{ stats.open || 0 }}</b></span>
        <span class="kpi-mini">处理中 <b class="warn">{{ (stats.assigned || 0) + (stats.processing || 0) }}</b></span>
        <span class="kpi-mini">超期 <b class="danger">{{ stats.overdue || 0 }}</b></span>
        <span class="role-tag">系统管理员</span>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <!-- 状态概览（点击筛选） -->
    <div class="dl-card ov-card">
      <div class="card-head">
        <span class="card-head-title">状态概览</span>
        <span class="count-badge">点击卡片按状态筛选 · 总计 {{ stats.total || 0 }}</span>
      </div>
      <div class="st-grid" v-loading="loading">
        <div v-for="s in ST_CHIPS" :key="s.key" class="st-chip" :class="[s.cls, { active: fStatus === s.key }]" @click="toggleStatus(s.key)">
          <div class="dc-top"><span class="dc-name">{{ s.label }}</span><span class="dc-dot" /></div>
          <div class="dc-num">{{ stats[s.count] ?? 0 }}</div>
          <div class="dc-online">{{ s.hint }}</div>
        </div>
      </div>
    </div>

    <!-- 工单列表 -->
    <div class="dl-card ov-card">
      <div class="card-head"><span class="card-head-title">工单列表</span></div>
      <div class="dl-toolbar">
        <el-select v-model="fStatus" placeholder="状态" clearable size="small" style="width:130px">
          <el-option v-for="(v, k) in ST" :key="k" :label="v.label" :value="k" />
        </el-select>
        <el-select v-model="fSeverity" placeholder="严重度" clearable size="small" style="width:120px">
          <el-option v-for="s in ['BLOCKER', 'CRITICAL', 'MAJOR', 'MINOR']" :key="s" :label="sevText(s)" :value="s" />
        </el-select>
        <el-switch v-model="fOverdue" active-text="只看超期" size="small" />
        <el-input v-model="fKeyword" placeholder="搜索 表/规则/处理人" clearable size="small" style="width:220px">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button size="small" type="primary" :disabled="!selection.length" @click="openAssign(null)">
          批量派单{{ selection.length ? `（${selection.length}）` : '' }}
        </el-button>
        <div class="toolbar-actions"><span class="count-badge">命中 <b>{{ rows.length }}</b></span></div>
      </div>
      <el-table :data="rows" size="small" stripe v-loading="loading" @selection-change="onSel">
        <el-table-column type="selection" width="42" :selectable="(r: any) => r.status === 'OPEN' || r.status === 'ASSIGNED'" />
        <el-table-column label="严重度" width="90">
          <template #default="{ row }"><span class="st-pill" :class="sevMeta(row.severity).type"><i class="dot" />{{ sevText(row.severity) }}</span></template>
        </el-table-column>
        <el-table-column label="问题表" min-width="150">
          <template #default="{ row }"><span class="mono">{{ row.table_name }}</span></template>
        </el-table-column>
        <el-table-column label="维度" width="80">
          <template #default="{ row }"><el-tag size="small" effect="plain">{{ dimText(row.dimension) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="rule_name" label="规则" min-width="140" show-overflow-tooltip />
        <el-table-column label="违规" width="70" align="right">
          <template #default="{ row }"><b>{{ row.violate_count }}</b></template>
        </el-table-column>
        <el-table-column label="状态" width="88">
          <template #default="{ row }"><el-tag size="small" :type="ST[row.status]?.type ?? 'info'">{{ ST[row.status]?.label ?? row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column label="处理人" width="90">
          <template #default="{ row }">{{ row.assignee || '—' }}</template>
        </el-table-column>
        <el-table-column label="期望完成" width="150">
          <template #default="{ row }">
            <span v-if="row.deadline" :class="{ 'overdue-text': row.overdue }">{{ fmtTime(row.deadline) }}{{ row.overdue ? ' 超期' : '' }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }"><span class="muted">{{ fmtTime(row.create_time) }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'OPEN' || row.status === 'ASSIGNED'" link size="small" type="primary" @click="openAssign(row)">派单</el-button>
            <el-button v-if="row.status === 'ASSIGNED'" link size="small" type="warning" @click="act(row, 'process')">开始处理</el-button>
            <el-button v-if="row.status === 'PROCESSING' || row.status === 'ASSIGNED'" link size="small" type="success" @click="act(row, 'resolve')">解决</el-button>
            <el-button v-if="row.status === 'ASSIGNED' || row.status === 'PROCESSING'" link size="small" type="danger" @click="act(row, 'reject')">驳回</el-button>
            <el-button v-if="row.status === 'RESOLVED'" link size="small" @click="act(row, 'close')">验收关闭</el-button>
            <el-button v-if="row.status === 'RESOLVED' || row.status === 'CLOSED'" link size="small" type="danger" @click="act(row, 'reopen')">重开</el-button>
            <el-button link size="small" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="table-empty"><el-icon class="empty-ic"><Tickets /></el-icon><div>{{ rows.length ? '无匹配工单' : '暂无工单（质量检测 FAIL 会自动建单）' }}</div></div>
        </template>
      </el-table>
    </div>

    <!-- 派单弹窗（单条 / 批量） -->
    <el-dialog v-model="assignDlg" :title="assignRow ? `派单 - ${assignRow.table_name}` : `批量派单（${selection.length} 条）`" width="480px">
      <el-form label-width="90px" size="small">
        <el-form-item label="处理人" required>
          <el-select v-model="assignForm.assignee" filterable placeholder="选择处理人" style="width:100%">
            <el-option v-for="u in users" :key="u.username" :label="`${u.name}（${u.username}）`" :value="u.username" />
          </el-select>
        </el-form-item>
        <el-form-item label="期望完成">
          <el-date-picker v-model="assignForm.deadline" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="SLA 截止（超期标红）" style="width:100%" />
        </el-form-item>
        <el-form-item label="派单说明">
          <el-input v-model="assignForm.comment" type="textarea" :rows="2" placeholder="如：请优先修复空值问题，参考样例数据" />
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="assignDlg = false">取消</el-button>
        <el-button type="primary" :loading="acting" @click="doAssign">确认派单</el-button></template>
    </el-dialog>

    <!-- 工单详情抽屉 -->
    <el-drawer v-model="detailDlg" :title="`工单详情 - ${detail?.table_name || ''}`" size="640px">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="状态"><el-tag size="small" :type="ST[detail.status]?.type ?? 'info'">{{ ST[detail.status]?.label ?? detail.status }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="严重度"><span class="st-pill" :class="sevMeta(detail.severity).type"><i class="dot" />{{ sevText(detail.severity) }}</span></el-descriptions-item>
          <el-descriptions-item label="问题表" :span="2"><span class="mono">{{ detail.table_name }}</span></el-descriptions-item>
          <el-descriptions-item label="维度">{{ dimText(detail.dimension) }}</el-descriptions-item>
          <el-descriptions-item label="违规数"><b>{{ detail.violate_count }}</b> 行</el-descriptions-item>
          <el-descriptions-item label="质检任务">{{ detail.task_name || detail.task_id }}</el-descriptions-item>
          <el-descriptions-item label="规则">{{ detail.rule_name || detail.rule_id }}</el-descriptions-item>
          <el-descriptions-item label="规则表达式" :span="2"><code>{{ detail.expression || '—' }}</code></el-descriptions-item>
          <el-descriptions-item label="处理人">{{ detail.assignee || '—' }}</el-descriptions-item>
          <el-descriptions-item label="期望完成">
            <span :class="{ 'overdue-text': detail.overdue }">{{ detail.deadline ? fmtTime(detail.deadline) + (detail.overdue ? '（超期）' : '') : '—' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ fmtTime(detail.create_time) }}</el-descriptions-item>
          <el-descriptions-item label="解决时间">{{ detail.resolve_time ? fmtTime(detail.resolve_time) : '—' }}</el-descriptions-item>
          <el-descriptions-item label="解决说明" :span="2">{{ detail.resolve_comment || '—' }}</el-descriptions-item>
        </el-descriptions>

        <!-- 违规样例 -->
        <div class="sec-head">
          <span>违规行样例</span>
          <el-button v-if="detail.violate_count > 0" link size="small" type="primary" @click="downloadCsv(detail)">导出 CSV</el-button>
        </div>
        <el-table v-if="sampleRows.length" :data="sampleRows" size="small" border max-height="220">
          <el-table-column v-for="c in sampleCols" :key="c" :prop="c" :label="c" min-width="100" show-overflow-tooltip />
        </el-table>
        <div v-else class="muted">无样例数据（规则维度不采样或已降级）</div>

        <!-- 流转时间线 -->
        <div class="sec-head"><span>流转记录</span></div>
        <el-timeline v-if="logs.length">
          <el-timeline-item v-for="l in logs" :key="l.id" :timestamp="fmtTime(l.create_time)" :type="ACT_TYPE[l.action] ?? 'info'" placement="top">
            <div class="log-line">
              <b>{{ ACT_TEXT[l.action] ?? l.action }}</b>
              <span class="muted">· {{ l.operator }}</span>
            </div>
            <div class="log-comment">{{ l.comment || '—' }}</div>
          </el-timeline-item>
        </el-timeline>
        <div v-else class="muted">暂无流转记录</div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search, Tickets } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'

const ST: Record<string, { label: string; type: any }> = {
  OPEN: { label: '待派单', type: 'info' },
  ASSIGNED: { label: '已派单', type: 'primary' },
  PROCESSING: { label: '处理中', type: 'warning' },
  RESOLVED: { label: '已解决', type: 'success' },
  CLOSED: { label: '已关闭', type: 'info' },
}
const ST_CHIPS = [
  { key: 'OPEN', label: '待派单', count: 'open', hint: '等待指派处理人', cls: 'info' },
  { key: 'ASSIGNED', label: '已派单', count: 'assigned', hint: '已指派待开始', cls: 'primary' },
  { key: 'PROCESSING', label: '处理中', count: 'processing', hint: '处理人跟进中', cls: 'warning' },
  { key: 'RESOLVED', label: '已解决', count: 'resolved', hint: '待验收关闭', cls: 'success' },
  { key: 'CLOSED', label: '已关闭', count: 'closed', hint: '处理完成归档', cls: 'closed' },
]
const ACT_TEXT: Record<string, string> = { CREATE: '自动建单', ASSIGN: '派单', PROCESS: '开始处理', RESOLVE: '解决', CLOSE: '验收关闭', REJECT: '驳回', REOPEN: '重开' }
const ACT_TYPE: Record<string, string> = { CREATE: 'info', ASSIGN: 'primary', PROCESS: 'warning', RESOLVE: 'success', CLOSE: 'success', REJECT: 'danger', REOPEN: 'danger' }
const DIM: Record<string, string> = { COMPLETENESS: '完整性', UNIQUENESS: '唯一性', VALIDITY: '有效性', TIMELINESS: '及时性', ACCURACY: '准确性', CONSISTENCY: '一致性' }
const SEV: Record<string, { label: string; type: string }> = {
  BLOCKER: { label: '阻塞', type: 'danger' }, CRITICAL: { label: '严重', type: 'danger' },
  MAJOR: { label: '主要', type: 'warning' }, MINOR: { label: '次要', type: 'info' },
}

const rows = ref<any[]>([]); const loading = ref(false); const stats = ref<any>({})
const users = ref<any[]>([]); const selection = ref<any[]>([])
const fStatus = ref(''); const fSeverity = ref(''); const fKeyword = ref(''); const fOverdue = ref(false)
const assignDlg = ref(false); const assignRow = ref<any>(null); const acting = ref(false)
const assignForm = reactive<any>({ assignee: '', deadline: '', comment: '' })
const detailDlg = ref(false); const detail = ref<any>(null); const logs = ref<any[]>([])

const sampleRows = computed(() => { try { return JSON.parse(detail.value?.sample_json || '[]') } catch { return [] } })
const sampleCols = computed(() => sampleRows.value.length ? Object.keys(sampleRows.value[0]) : [])

function sevText(s: string) { return SEV[s]?.label ?? s }
function sevMeta(s: string) { return { type: SEV[s]?.type ?? 'info' } }
function dimText(d: string) { return DIM[d] ?? d }
function fmtTime(t: any) { return String(t || '').replace('T', ' ').slice(0, 19) }
function toggleStatus(k: string) { fStatus.value = fStatus.value === k ? '' : k }
function onSel(s: any[]) { selection.value = s }

async function load() {
  loading.value = true
  try {
    rows.value = await api.govQualityIssues({
      status: fStatus.value || undefined, severity: fSeverity.value || undefined,
      keyword: fKeyword.value || undefined, overdue: fOverdue.value ? 1 : undefined,
    })
    stats.value = await api.govQualityIssueStats()
  } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false }
}
async function loadUsers() { try { users.value = await api.govQualityIssueUsers() } catch { users.value = [] } }

function openAssign(row: any) {
  if (row) {
    assignRow.value = row
    Object.assign(assignForm, { assignee: row.assignee || '', deadline: row.deadline ? String(row.deadline).replace(' ', 'T').slice(0, 19) : '', comment: '' })
  } else {
    assignRow.value = null
    Object.assign(assignForm, { assignee: '', deadline: '', comment: '' })
  }
  if (!users.value.length) loadUsers()
  assignDlg.value = true
}
async function doAssign() {
  if (!assignForm.assignee) return ElMessage.warning('选择处理人')
  acting.value = true
  try {
    const r: any = assignRow.value
      ? await api.govQualityIssueAssign(assignRow.value.id, assignForm.assignee, assignForm.deadline, assignForm.comment)
      : await api.govQualityIssueBatchAssign(selection.value.map((x: any) => x.id).join(','), assignForm.assignee, assignForm.deadline, assignForm.comment)
    if (r.success === false) ElMessage.warning(r.msg || '操作失败')
    else { ElMessage.success(r.count != null ? `已批量派单 ${r.count} 条` : '派单成功'); assignDlg.value = false; await load() }
  } catch (e:any) { ElMessage.error(errMsg(e)) } finally { acting.value = false }
}

/** 带确认+说明的动作：process/resolve/close/reject/reopen。 */
async function act(row: any, kind: 'process' | 'resolve' | 'close' | 'reject' | 'reopen') {
  const tip: Record<string, string> = { process: '开始处理说明（可空）', resolve: '解决说明', close: '验收说明（可空）', reject: '驳回原因', reopen: '重开原因' }
  const need: Record<string, boolean> = { process: false, resolve: true, close: false, reject: true, reopen: true }
  let comment = ''
  try {
    const r = await ElMessageBox.prompt(`工单【${row.table_name} · ${dimText(row.dimension)}】${tip[kind]}`, {
      title: ({ process: '开始处理', resolve: '标记解决', close: '验收关闭', reject: '驳回重派', reopen: '重开工单' } as any)[kind],
      inputPlaceholder: need[kind] ? '必填' : '可空', inputValidator: (v: string) => !need[kind] || !!v?.trim() || '请填写说明',
    })
    comment = r.value || ''
  } catch { return }
  try {
    const fn: any = { process: api.govQualityIssueProcess, resolve: api.govQualityIssueResolve, close: api.govQualityIssueClose, reject: api.govQualityIssueReject, reopen: api.govQualityIssueReopen }
    const resp = await fn[kind](row.id, comment)
    if (resp.success === false) ElMessage.warning(resp.msg || '状态不允许该操作')
    else { ElMessage.success('操作成功'); await load(); if (detailDlg.value && detail.value?.id === row.id) openDetail(row) }
  } catch (e:any) { ElMessage.error(errMsg(e)) }
}

async function openDetail(row: any) {
  detail.value = row
  detailDlg.value = true
  try { logs.value = await api.govQualityIssueLog(row.id) } catch { logs.value = [] }
  // 刷新为最新状态（列表可能滞后）
  try {
    const one = (await api.govQualityIssues({})).find((x: any) => x.id === row.id)
    if (one) detail.value = one
  } catch { /* 保持原值 */ }
}

function downloadCsv(row: any) {
  api.govQualityIssueSampleCsv(row.id).then((blob: any) => {
    const a = document.createElement('a'); a.href = URL.createObjectURL(blob)
    a.download = `问题数据_${row.table_name}_${row.id}.csv`; a.click(); URL.revokeObjectURL(a.href)
  })
}

onMounted(() => { load(); loadUsers() })
</script>

<style scoped>
.tk-page { display: flex; flex-direction: column; gap: 14px; }
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

/* 卡片 */
.ov-card { padding: 14px; }
.card-head { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
.card-head-title { display: flex; align-items: center; gap: 7px; font-size: 14px; font-weight: 700; color: var(--tech-text); }
.count-badge { font-size: 12px; color: var(--tech-text-muted); margin-left: auto; }
.dl-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.toolbar-actions { margin-left: auto; }

/* 状态概览 chips */
.st-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; }
.st-chip { border: 1px solid var(--tech-panel-border); border-radius: 10px; padding: 14px; cursor: pointer; background: var(--tech-bg-2); transition: transform .15s, border-color .15s, box-shadow .15s; }
.st-chip:hover { transform: translateY(-2px); box-shadow: 0 6px 18px rgba(16, 24, 40, .1); }
.st-chip.active { border-color: var(--tech-primary); box-shadow: 0 0 0 1px var(--tech-primary) inset; }
.dc-top { display: flex; align-items: center; justify-content: space-between; }
.dc-name { font-size: 13px; color: var(--tech-text-muted); }
.dc-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--tech-primary); }
.st-chip.info .dc-dot { background: var(--tech-text-muted); }
.st-chip.primary .dc-dot { background: var(--tech-primary); }
.st-chip.warning .dc-dot { background: var(--tech-warn); }
.st-chip.success .dc-dot, .st-chip.closed .dc-dot { background: var(--tech-success); }
.dc-num { font-size: 26px; font-weight: 700; color: var(--tech-text); margin: 4px 0; }
.dc-online { font-size: 11px; color: var(--tech-text-muted); }

/* 列表 */
.mono { font-family: ui-monospace, Consolas, monospace; font-size: 12px; }
.muted { color: var(--tech-text-muted); font-size: 13px; }
.st-pill { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; padding: 2px 9px; border-radius: 10px; background: var(--el-fill-color-light); color: var(--tech-text-muted); }
.st-pill .dot { width: 6px; height: 6px; border-radius: 50%; background: var(--tech-text-muted); }
.st-pill.danger { color: var(--tech-danger); background: color-mix(in srgb, var(--tech-danger) 14%, transparent); }
.st-pill.danger .dot { background: var(--tech-danger); }
.st-pill.warning { color: var(--tech-warn); background: color-mix(in srgb, var(--tech-warn) 14%, transparent); }
.st-pill.warning .dot { background: var(--tech-warn); }
.st-pill.info { color: var(--tech-text-muted); }
.overdue-text { color: var(--tech-danger); font-weight: 600; }
.table-empty { padding: 36px 0; color: var(--tech-text-muted); text-align: center; }
.empty-ic { font-size: 30px; margin-bottom: 8px; opacity: .6; }

/* 详情抽屉 */
.sec-head { display: flex; align-items: center; justify-content: space-between; font-weight: 700; font-size: 14px; color: var(--tech-text); margin: 18px 0 10px; }
.log-line { display: flex; align-items: center; gap: 6px; }
.log-comment { font-size: 12px; color: var(--tech-text-muted); margin-top: 2px; }
</style>
