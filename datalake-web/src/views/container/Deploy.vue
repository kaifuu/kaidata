<template>
  <div class="cl-page">
    <div class="page-head">
      <div class="page-head-left">
        <span class="title-icon head-ic"><el-icon><Promotion /></el-icon></span>
        <div>
          <div class="page-title">部署记录</div>
          <div class="page-sub">将镜像部署到远端服务器 · SFTP 上传 tar + docker load</div>
        </div>
      </div>
      <div class="head-right">
        <el-button :icon="Promotion" type="primary" @click="openDeploy">发起部署</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <div class="dl-card">
      <el-table :data="rows" v-loading="loading" stripe size="small">
        <el-table-column type="expand">
          <template #default="{ row }">
            <pre class="deploy-log">{{ row.log_text || row.error_msg || '（无日志）' }}</pre>
          </template>
        </el-table-column>
        <el-table-column label="镜像" min-width="170"><template #default="{ row }"><span class="mono">{{ row.image_name }}:{{ row.tag }}</span></template></el-table-column>
        <el-table-column prop="server_name" label="目标服务器" width="150" />
        <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag></template></el-table-column>
        <el-table-column prop="start_time" label="开始" width="160" />
        <el-table-column prop="end_time" label="结束" width="160" />
        <el-table-column prop="triggered_by" label="执行人" width="100" />
        <template #empty><div class="table-empty">暂无部署记录，点击「发起部署」</div></template>
      </el-table>
    </div>

    <!-- 发起部署 -->
    <el-dialog v-model="deployDlg" title="发起部署" width="520px">
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
      </el-form>
      <template #footer>
        <el-button @click="deployDlg = false">取消</el-button>
        <el-button type="primary" :loading="deploying" @click="doDeploy">部署</el-button>
      </template>
    </el-dialog>

    <!-- 部署进度 -->
    <el-dialog v-model="progressDlg" title="部署进度" width="720px" :close-on-click-modal="false" :before-close="cancelProgress">
      <div class="build-head"><span>状态：</span><el-tag :type="statusType(depStatus)" size="small">{{ depStatus }}</el-tag></div>
      <pre class="build-log">{{ depLog || '等待日志...' }}</pre>
      <template #footer>
        <el-button v-if="depStatus === 'RUNNING'" disabled>部署中...</el-button>
        <el-button v-else type="primary" @click="progressDlg = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Promotion, Refresh } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'

const rows = ref<any[]>([])
const loading = ref(false)
const versions = ref<any[]>([])
const servers = ref<any[]>([])

const deployDlg = ref(false)
const deployForm = ref<any>({ versionId: null, serverId: null })
const deploying = ref(false)

const progressDlg = ref(false)
const depStatus = ref('RUNNING')
const depLog = ref('')
let timer: any = null

async function load() {
  loading.value = true
  try { rows.value = await api.containerDeployList() } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false }
}
async function openDeploy() {
  deployForm.value = { versionId: null, serverId: null }
  try {
    const [all, srvs] = await Promise.all([api.containerVersionList({ status: 'SAVED' }), api.containerServerList()])
    versions.value = all
    servers.value = srvs.filter((s: any) => s.status === 'NORMAL')
  } catch (e: any) { ElMessage.error(errMsg(e)); return }
  if (!versions.value.length) { ElMessage.warning('暂无已保存的镜像，请先构建'); return }
  if (!servers.value.length) { ElMessage.warning('暂无可用服务器，请先添加'); return }
  deployDlg.value = true
}
async function doDeploy() {
  if (!deployForm.value.versionId || !deployForm.value.serverId) { ElMessage.warning('请选择镜像和服务器'); return }
  deploying.value = true
  try {
    const r: any = await api.containerDeploy(deployForm.value.versionId, deployForm.value.serverId)
    deployDlg.value = false
    depStatus.value = 'RUNNING'; depLog.value = ''; progressDlg.value = true
    poll(r.deployId)
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { deploying.value = false }
}
function poll(deployId: number) {
  if (timer) clearInterval(timer)
  timer = setInterval(async () => {
    try {
      const st = await api.containerDeployStatus(deployId)
      depLog.value = st.log || ''; depStatus.value = st.status
      if (st.status !== 'RUNNING' && st.status !== 'NONE') { if (timer) clearInterval(timer); timer = null; load() }
    } catch (e: any) { if (timer) clearInterval(timer); timer = null }
  }, 2000)
}
function cancelProgress(done: any) { if (timer) { clearInterval(timer); timer = null } done() }
function statusType(s: string): any { return s === 'SUCCESS' ? 'success' : s === 'FAIL' ? 'danger' : s === 'RUNNING' ? 'warning' : 'info' }

onMounted(load)
onBeforeUnmount(() => { if (timer) clearInterval(timer) })
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
.table-empty { padding: 32px 0; color: var(--tech-text-muted); text-align: center; }
.build-head { margin-bottom: 8px; font-size: 13px; color: var(--tech-text); }
.build-log, .deploy-log { background: var(--tech-bg-2, var(--el-bg-color)); border: 1px solid var(--tech-panel-border, var(--el-border-color)); border-radius: 8px; padding: 12px; max-height: 380px; overflow: auto; font-family: ui-monospace, Menlo, monospace; font-size: 12px; line-height: 1.6; white-space: pre-wrap; word-break: break-all; color: var(--tech-text); }
</style>
