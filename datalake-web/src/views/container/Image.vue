<template>
  <div class="cl-page">
    <div class="page-head">
      <div class="page-head-left">
        <span class="title-icon head-ic"><el-icon><Box /></el-icon></span>
        <div>
          <div class="page-title">镜像版本</div>
          <div class="page-sub">将数据中台打包为全栈镜像 · 构建 / 下载 / 版本管理</div>
        </div>
      </div>
      <div class="head-right">
        <span class="kpi-mini">docker <b :class="dockerOk ? 'ok' : 'ng'">{{ dockerOk ? '可用' : '不可用' }}</b></span>
        <el-button :icon="Plus" type="primary" @click="openEdit()">新增版本</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <div class="dl-card">
      <div class="toolbar">
        <el-input v-model="kw" placeholder="搜索 名称/标签" clearable style="width:220px" @keyup.enter="load" />
        <el-select v-model="statusFilter" placeholder="状态" clearable style="width:140px" @change="load">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="构建中" value="BUILT" />
          <el-option label="已保存" value="SAVED" />
          <el-option label="失败" value="FAIL" />
        </el-select>
        <el-button :icon="Search" @click="load">查询</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" stripe size="small">
        <el-table-column prop="name" label="镜像名" min-width="120" show-overflow-tooltip />
        <el-table-column prop="tag" label="Tag" width="100" show-overflow-tooltip />
        <el-table-column prop="version_label" label="版本说明" min-width="130" show-overflow-tooltip />
        <el-table-column label="镜像大小" width="100"><template #default="{ row }"><span class="mono">{{ fmtSize(row.size_bytes) }}</span></template></el-table-column>
        <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag></template></el-table-column>
        <el-table-column prop="create_time" label="创建时间" width="160" />
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="!dockerOk" @click="openBuild(row)">构建</el-button>
            <el-button link @click="openLog(row)">日志</el-button>
            <el-button link type="primary" :disabled="row.status !== 'SAVED'" @click="download(row)">下载</el-button>
            <el-button link @click="openDetail(row)">详情</el-button>
            <el-button link @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="del(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty><div class="table-empty">暂无镜像版本，点击「新增版本」创建</div></template>
      </el-table>
    </div>

    <!-- 新增/编辑 -->
    <el-dialog v-model="editDlg" :title="form.id ? '编辑版本' : '新增版本'" width="520px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="镜像名"><el-input v-model="form.name" placeholder="如 datalake" /></el-form-item>
        <el-form-item label="Tag"><el-input v-model="form.tag" placeholder="如 v1.0.0" /></el-form-item>
        <el-form-item label="版本说明"><el-input v-model="form.version_label" placeholder="本次构建说明" /></el-form-item>
        <el-form-item label="暴露端口"><el-input v-model="form.expose_port" placeholder="默认 80" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 构建日志 -->
    <el-dialog v-model="buildDlg" :title="buildDlgTitle" width="760px" :close-on-click-modal="false" :before-close="cancelBuild">
      <div class="build-head"><span>状态：</span><el-tag :type="buildStatusType" size="small">{{ buildStatusText }}</el-tag></div>
      <pre class="build-log">{{ buildLog || '等待日志...' }}</pre>
      <template #footer>
        <el-button v-if="buildStatus === 'RUNNING'" disabled>构建中...</el-button>
        <el-button v-else type="primary" @click="buildDlg = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <el-dialog v-model="detailDlg" title="版本详情" width="760px">
      <el-descriptions :column="2" border size="small" v-if="detail">
        <el-descriptions-item label="镜像名">{{ detail.name }}</el-descriptions-item>
        <el-descriptions-item label="Tag">{{ detail.tag }}</el-descriptions-item>
        <el-descriptions-item label="版本说明">{{ detail.version_label }}</el-descriptions-item>
        <el-descriptions-item label="状态"><el-tag :type="statusType(detail.status)" size="small">{{ statusText(detail.status) }}</el-tag></el-descriptions-item>
        <el-descriptions-item label="镜像ID"><span class="mono">{{ detail.image_id || '-' }}</span></el-descriptions-item>
        <el-descriptions-item label="大小">{{ fmtSize(detail.size_bytes) }}</el-descriptions-item>
        <el-descriptions-item label="tar 文件"><span class="mono">{{ detail.file_path || '-' }}</span></el-descriptions-item>
        <el-descriptions-item label="文件大小">{{ fmtSize(detail.file_size) }}</el-descriptions-item>
      </el-descriptions>
      <div class="sub-title">构建历史（点击行展开查看完整日志）</div>
      <el-table :data="detail?.buildRuns || []" size="small" stripe max-height="260">
        <el-table-column type="expand">
          <template #default="{ row }"><pre class="build-log" style="max-height:300px">{{ row.log_text || row.error_msg || '（无日志）' }}</pre></template>
        </el-table-column>
        <el-table-column prop="action" label="动作" width="80" />
        <el-table-column label="状态" width="80"><template #default="{ row }"><el-tag :type="row.status === 'SUCCESS' ? 'success' : 'danger'" size="small">{{ row.status }}</el-tag></template></el-table-column>
        <el-table-column prop="start_time" label="开始" width="150" />
        <el-table-column prop="end_time" label="结束" width="150" />
        <el-table-column prop="triggered_by" label="执行人" />
      </el-table>
      <div class="sub-title">部署记录</div>
      <el-table :data="detail?.deploys || []" size="small" stripe max-height="160">
        <el-table-column label="状态" width="80"><template #default="{ row }"><el-tag :type="row.status === 'SUCCESS' ? 'success' : 'danger'" size="small">{{ row.status }}</el-tag></template></el-table-column>
        <el-table-column prop="server_name" label="目标服务器" />
        <el-table-column prop="start_time" label="开始" width="150" />
        <el-table-column prop="end_time" label="结束" width="150" />
        <el-table-column prop="triggered_by" label="执行人" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Box, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'

const rows = ref<any[]>([])
const loading = ref(false)
const kw = ref('')
const statusFilter = ref('')
const dockerOk = ref(false)

const editDlg = ref(false)
const form = ref<any>({ id: null, name: 'datalake', tag: '', version_label: '', expose_port: '80', remark: '' })
const saving = ref(false)

const buildDlg = ref(false)
const buildDlgTitle = ref('构建日志')
const buildLog = ref('')
const buildStatus = ref('RUNNING')
const buildStatusText = computed(() => ({ RUNNING: '构建中', SUCCESS: '成功', FAIL: '失败', NONE: '无' } as any)[buildStatus.value] || buildStatus.value)
const buildStatusType = computed<any>(() => buildStatus.value === 'SUCCESS' ? 'success' : buildStatus.value === 'FAIL' ? 'danger' : 'warning')
let buildTimer: any = null

const detailDlg = ref(false)
const detail = ref<any>(null)

async function load() {
  loading.value = true
  try {
    const [info, list] = await Promise.all([api.containerDockerInfo(), api.containerVersionList({ kw: kw.value, status: statusFilter.value })])
    dockerOk.value = !!info.available
    rows.value = list
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false }
}

function openEdit(row?: any) {
  form.value = row ? { ...row } : { id: null, name: 'datalake', tag: '', version_label: '', expose_port: '80', remark: '' }
  editDlg.value = true
}
async function save() {
  if (!form.value.name) { ElMessage.warning('请填镜像名'); return }
  saving.value = true
  try { await api.containerSaveVersion(form.value); ElMessage.success('保存成功'); editDlg.value = false; load() }
  catch (e: any) { ElMessage.error(errMsg(e)) } finally { saving.value = false }
}
async function del(row: any) {
  try {
    await ElMessageBox.confirm(`确认删除版本 ${row.name}:${row.tag || ''} 及其 tar/历史？`, '提示', { type: 'warning' })
    await api.containerDeleteVersion(row.id); ElMessage.success('已删除'); load()
  } catch (e: any) { if (e !== 'cancel') ElMessage.error(errMsg(e)) }
}

async function openLog(row: any) {
  buildDlgTitle.value = '构建日志'
  buildLog.value = ''; buildStatus.value = 'RUNNING'; buildDlg.value = true
  try {
    const st: any = await api.containerBuildStatus(row.id)
    if (st.status === 'RUNNING') {
      buildLog.value = st.log || ''
      pollBuild(row.id)                 // 正在构建：接续实时轮询
    } else if (st.status === 'NONE') {
      const runs: any = await api.containerBuildRuns(row.id)   // 无进行中：取最近一次历史日志
      if (runs && runs.length) {
        const latest = runs[0]
        buildLog.value = latest.log_text || latest.error_msg || '（无日志）'
        buildStatus.value = latest.status === 'SUCCESS' ? 'SUCCESS' : 'FAIL'
      } else {
        buildLog.value = '该版本尚无构建记录'
        buildStatus.value = 'NONE'
      }
    } else {
      buildLog.value = st.log || ''     // 刚结束（live 还在内存）
      buildStatus.value = st.status
    }
  } catch (e: any) { ElMessage.error(errMsg(e)); buildDlg.value = false }
}
function openBuild(row: any) {
  buildDlgTitle.value = '构建镜像'
  buildLog.value = ''; buildStatus.value = 'RUNNING'; buildDlg.value = true
  api.containerBuild(row.id).then(() => pollBuild(row.id)).catch((e: any) => { ElMessage.error(errMsg(e)); buildDlg.value = false })
}
function pollBuild(versionId: number) {
  if (buildTimer) clearInterval(buildTimer)
  buildTimer = setInterval(async () => {
    try {
      const st = await api.containerBuildStatus(versionId)
      buildLog.value = st.log || ''; buildStatus.value = st.status
      if (st.status !== 'RUNNING' && st.status !== 'NONE') { if (buildTimer) clearInterval(buildTimer); buildTimer = null; load() }
    } catch (e: any) { if (buildTimer) clearInterval(buildTimer); buildTimer = null }
  }, 2000)
}
function cancelBuild(done: any) { if (buildTimer) { clearInterval(buildTimer); buildTimer = null } done() }

async function download(row: any) {
  try {
    const r: any = await api.containerDownloadTicket(row.id)
    window.open(`/api/container/version/download?id=${row.id}&ticket=${r.ticket}`, '_blank')
  } catch (e: any) { ElMessage.error(errMsg(e)) }
}
async function openDetail(row: any) {
  try { detail.value = await api.containerVersionDetail(row.id); detailDlg.value = true }
  catch (e: any) { ElMessage.error(errMsg(e)) }
}

function fmtSize(b: any): string {
  const n = Number(b) || 0
  if (n < 1024) return n + ' B'
  if (n < 1048576) return (n / 1024).toFixed(1) + ' KB'
  if (n < 1073741824) return (n / 1048576).toFixed(1) + ' MB'
  return (n / 1073741824).toFixed(2) + ' GB'
}
function statusText(s: string) { return ({ DRAFT: '草稿', BUILT: '构建中', SAVED: '已保存', FAIL: '失败' } as any)[s] || s }
function statusType(s: string): any { return ({ DRAFT: 'info', BUILT: 'warning', SAVED: 'success', FAIL: 'danger' } as any)[s] || 'info' }

onMounted(load)
onBeforeUnmount(() => { if (buildTimer) clearInterval(buildTimer) })
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
.toolbar { display: flex; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.mono { font-family: ui-monospace, Menlo, monospace; font-size: 12px; }
.table-empty { padding: 32px 0; color: var(--tech-text-muted); text-align: center; }
.build-head { margin-bottom: 8px; font-size: 13px; color: var(--tech-text); }
.build-log { background: var(--tech-bg-2, var(--el-bg-color)); border: 1px solid var(--tech-panel-border, var(--el-border-color)); border-radius: 8px; padding: 12px; max-height: 380px; overflow: auto; font-family: ui-monospace, Menlo, monospace; font-size: 12px; line-height: 1.6; white-space: pre-wrap; word-break: break-all; color: var(--tech-text); }
.sub-title { margin: 16px 0 8px; font-size: 13px; font-weight: 700; color: var(--tech-text); }
</style>
