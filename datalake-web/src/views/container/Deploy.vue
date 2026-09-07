<template>
  <div class="cl-page">
    <div class="page-head">
      <div class="page-head-left">
        <span class="title-icon head-ic"><el-icon><Promotion /></el-icon></span>
        <div>
          <div class="page-title">部署记录</div>
          <div class="page-sub">将镜像发布到远端目标 · SFTP 上传 tar + docker load · 密码/秘钥双认证</div>
        </div>
      </div>
      <div class="head-right">
        <el-button :icon="Promotion" type="primary" @click="openDeploy">发起部署</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <div class="dl-card">
      <el-table :data="paged" v-loading="loading" stripe size="small" row-key="id" @expand-change="onExpand">
        <el-table-column type="expand">
          <template #default="{ row }">
            <LogStream :log="row.log_text || (row.status === 'RUNNING' ? '（连接部署日志…）' : '（展开加载日志…）')" :running="row.status === 'RUNNING'" height="300px" style="padding:0 12px" />
          </template>
        </el-table-column>
        <el-table-column label="镜像" min-width="170"><template #default="{ row }"><span class="mono">{{ row.image_name }}:{{ row.tag }}</span></template></el-table-column>
        <el-table-column prop="server_name" label="目标服务器" width="150" />
        <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag></template></el-table-column>
        <el-table-column label="栈" width="60" align="center"><template #default="{ row }"><el-tag v-if="row.with_stack === 'ON'" type="success" size="small">栈</el-tag><span v-else class="dim">—</span></template></el-table-column>
        <el-table-column label="数据" width="64" align="center"><template #default="{ row }"><el-tag v-if="row.with_data === 'ON'" type="success" size="small">数据</el-tag><span v-else class="dim">—</span></template></el-table-column>
        <el-table-column prop="start_time" label="开始" width="160" />
        <el-table-column prop="end_time" label="结束" width="160" />
        <el-table-column prop="triggered_by" label="执行人" width="100" />
        <el-table-column label="操作" width="70" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openLog(row)">日志</el-button>
          </template>
        </el-table-column>
        <template #empty><div class="table-empty">暂无部署记录，点击「发起部署」</div></template>
      </el-table>
      <div class="dl-pagination">
        <el-pagination :current-page="page.page" :page-size="page.size" :total="rows.length"
          :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next, jumper" size="small" background
          @size-change="onSizeChange" @current-change="onPageChange" />
      </div>
    </div>

    <!-- 发起部署 -->
    <el-drawer v-model="deployDlg" title="发起部署" size="620px">
      <el-form label-width="90px">
        <el-form-item label="镜像版本">
          <el-select v-model="deployForm.versionId" placeholder="选择已保存的镜像" style="width:100%">
            <el-option v-for="v in versions" :key="v.id" :label="`${v.name}:${v.tag || ''}`" :value="v.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标服务器">
          <el-select v-model="deployForm.serverId" placeholder="选择服务器" style="width:100%">
            <el-option v-for="s in servers" :key="s.id" :label="`${s.name} (${s.host})`" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="附带大数据栈">
          <div class="opt-line">
            <el-switch v-model="deployForm.withStack" @change="onStackChange" />
            <span class="opt-tip">编排文件随包上传，远端 docker compose 拉取镜像自建（需 ~6GB 内存与外网）</span>
          </div>
        </el-form-item>
        <el-form-item label="附带基础数据">
          <div class="opt-line">
            <el-switch v-model="deployForm.withData" :disabled="!deployForm.withStack" />
            <span class="opt-tip">meta 库与本地一致（账号/菜单/基础配置，部署后同步）</span>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="deployDlg = false">取消</el-button>
        <el-button type="primary" :loading="deploying" @click="doDeploy">部署</el-button>
      </template>
    </el-drawer>

    <!-- 部署进度 -->
    <el-dialog v-model="progressDlg" title="部署进度" width="720px" :close-on-click-modal="false" :before-close="cancelProgress">
      <div class="build-head"><span>状态：</span><el-tag :type="statusType(depStatus)" size="small">{{ depStatusText }}</el-tag></div>
      <LogStream :log="depLog" :running="depStatus === 'RUNNING'" />
      <template #footer>
        <el-button v-if="depStatus === 'RUNNING'" disabled>后台执行中，可关闭窗口（远端不受影响）</el-button>
        <el-button v-else type="primary" @click="progressDlg = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Promotion, Refresh } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'
import LogStream from '@/components/LogStream.vue'

const rows = ref<any[]>([])
const loading = ref(false)
const versions = ref<any[]>([])
const servers = ref<any[]>([])
const page = reactive({ page: 1, size: 10 })
const paged = computed(() => rows.value.slice((page.page - 1) * page.size, page.page * page.size))
function onSizeChange(s: number) { page.size = s; page.page = 1 }
function onPageChange(p: number) { page.page = p }

const deployDlg = ref(false)
const deployForm = ref<any>({ versionId: null, serverId: null, withStack: false, withData: false })
const deploying = ref(false)

const progressDlg = ref(false)
const depStatus = ref('RUNNING')
const depLog = ref('')
let timer: any = null

async function load() {
  page.page = 1
  loading.value = true
  try { rows.value = await api.containerDeployList() } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false }
  watchRunning()
}

// ---- 部署可见性：RUNNING 记录原位自刷（列表 5s + 展开行日志 5s），关窗/刷新后可重连 ----
const expandedIds = new Set<number>()
async function onExpand(row: any, expanded: any[]) {
  if (expanded.includes(row)) { expandedIds.add(row.id); await hydrate(row) }
  else expandedIds.delete(row.id)
}
/** 拉单条详情（含完整日志；RUNNING 时后端合并内存 live 态）并原位写回行。 */
async function hydrate(row: any) {
  try {
    const d: any = await api.containerDeployDetail(row.id)
    row.status = d.status
    row.log_text = d.log_text || d.error_msg || ''
  } catch (e: any) { row.log_text = '（日志加载失败：' + errMsg(e) + '）' }
}
/** 行级「日志」：重连进度弹窗——RUNNING 继续滚屏轮询，已结束直接看完整日志。 */
async function openLog(row: any) {
  progressDlg.value = true
  depStatus.value = row.status || 'RUNNING'
  depLog.value = ''
  try {
    const d: any = await api.containerDeployDetail(row.id)
    depStatus.value = d.status
    depLog.value = d.log_text || d.error_msg || '（无日志）'
    if (d.status === 'RUNNING') poll(row.id)
  } catch (e: any) { ElMessage.error(errMsg(e)) }
}
let listTimer: any = null
/** 存在 RUNNING 部署时每 5s 原位刷新列表（不整表替换，保住展开状态）；全部结束后自停。 */
function watchRunning() {
  if (listTimer) { clearInterval(listTimer); listTimer = null }
  if (!rows.value.some((r: any) => r.status === 'RUNNING')) return
  listTimer = setInterval(async () => {
    let fresh: any[] = []
    try { fresh = await api.containerDeployList() } catch (e: any) { return }
    const byId = new Map(rows.value.map((r: any) => [r.id, r]))
    for (const f of fresh) { const old = byId.get(f.id); if (old) Object.assign(old, f); else rows.value.unshift(f) }
    const ids = new Set(fresh.map((f: any) => f.id))
    rows.value = rows.value.filter((r: any) => ids.has(r.id))
    // 展开行刷新：RUNNING 滚屏；翻到终态后再补拉一次完整日志（此前快照缺结尾）
    for (const r of rows.value) if (expandedIds.has(r.id) && (r.status === 'RUNNING' || r.log_text !== undefined)) hydrate(r)
    if (!rows.value.some((r: any) => r.status === 'RUNNING') && listTimer) { clearInterval(listTimer); listTimer = null }
  }, 5000)
}
async function openDeploy() {
  deployForm.value = { versionId: null, serverId: null, withStack: false, withData: false }
  try {
    const [all, srvs] = await Promise.all([api.containerVersionList({ status: 'SAVED' }), api.containerServerList()])
    versions.value = all
    servers.value = srvs.filter((s: any) => s.status === 'NORMAL')
  } catch (e: any) { ElMessage.error(errMsg(e)); return }
  if (!versions.value.length) { ElMessage.warning('暂无已保存的镜像，请先构建'); return }
  if (!servers.value.length) { ElMessage.warning('暂无可用服务器，请先添加'); return }
  deployDlg.value = true
}
function onStackChange(v: any) { if (!v) deployForm.value.withData = false }
async function doDeploy() {
  if (!deployForm.value.versionId || !deployForm.value.serverId) { ElMessage.warning('请选择镜像和服务器'); return }
  deploying.value = true
  try {
    const r: any = await api.containerDeploy(deployForm.value.versionId, deployForm.value.serverId, deployForm.value.withStack, deployForm.value.withData)
    deployDlg.value = false
    depStatus.value = 'RUNNING'; depLog.value = ''; progressDlg.value = true
    poll(r.deployId)
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { deploying.value = false }
}
function poll(deployId: number) {
  if (timer) clearInterval(timer)
  let noneTicks = 0
  timer = setInterval(async () => {
    try {
      const st = await api.containerDeployStatus(deployId)
      depLog.value = st.log || ''; depStatus.value = st.status
      if (st.status !== 'RUNNING' && st.status !== 'NONE') { if (timer) clearInterval(timer); timer = null; load() }
      // NONE 持续 30s：live 态大概率因后端重启丢失，停止轮询避免"部署中"假死
      else if (st.status === 'NONE' && ++noneTicks > 15) {
        if (timer) clearInterval(timer); timer = null
        depStatus.value = 'LOST'
        depLog.value = '部署任务不在运行中（服务可能重启过），请重新发起部署。'
      }
    } catch (e: any) { if (timer) clearInterval(timer); timer = null }
  }, 2000)
}
function cancelProgress(done: any) { if (timer) { clearInterval(timer); timer = null } done() }
const depStatusText = computed(() => ({ RUNNING: '部署中', SUCCESS: '成功', FAIL: '失败', LOST: '任务丢失' } as any)[depStatus.value] || depStatus.value)
function statusType(s: string): any { return s === 'SUCCESS' ? 'success' : s === 'FAIL' || s === 'LOST' ? 'danger' : s === 'RUNNING' ? 'warning' : 'info' }

onMounted(load)
onBeforeUnmount(() => { if (timer) clearInterval(timer); if (listTimer) clearInterval(listTimer) })
</script>

<style scoped>
.cl-page { display: flex; flex-direction: column; gap: 14px; }
.page-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.page-head-left { display: flex; align-items: center; gap: 10px; }
.head-ic { font-size: 22px; display: inline-flex; color: var(--tech-primary); }
.page-title { font-size: 18px; font-weight: 700; color: var(--tech-text); }
.page-sub { font-size: 12px; color: var(--tech-text-muted); margin-top: 2px; }
.head-right { display: flex; align-items: center; gap: 10px; }
.dl-card { background: var(--tech-bg-2, var(--el-bg-color)); border: 1px solid var(--tech-panel-border, var(--el-border-color)); border-radius: 12px; padding: 14px; }
.mono { font-family: ui-monospace, Menlo, monospace; font-size: 12px; }
.opt-line { display: flex; align-items: center; gap: 10px; }
.opt-tip { font-size: 12px; color: var(--tech-text-muted); line-height: 1.5; }
.dim { color: var(--tech-text-muted); }
.table-empty { padding: 32px 0; color: var(--tech-text-muted); text-align: center; }
.build-head { margin-bottom: 8px; font-size: 13px; color: var(--tech-text); }
</style>
