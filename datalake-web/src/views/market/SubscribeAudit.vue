<template>
  <div class="dl-card">
    <div class="card-title"><span>订阅审核</span><span class="role-tag">系统管理员</span></div>
    <el-radio-group v-model="status" size="small" style="margin-bottom:10px" @change="loadAudit">
      <el-radio-button value="待审">待审</el-radio-button>
      <el-radio-button value="">全部</el-radio-button>
    </el-radio-group>
    <el-table :data="rows" size="small" border v-loading="loading">
      <el-table-column prop="username" label="订阅人" width="90" />
      <el-table-column prop="table_name" label="库表" min-width="140" show-overflow-tooltip />
      <el-table-column prop="open_type" label="方式" width="62" />
      <el-table-column prop="purpose" label="用途" min-width="140" show-overflow-tooltip />
      <el-table-column label="限流" width="120"><template #default="{ row }">{{ row.limit_count > 0 ? row.limit_count + '次' : '不限' }} / {{ row.limit_qps > 0 ? row.limit_qps + '/s' : '不限' }}</template></el-table-column>
      <el-table-column label="状态" width="74"><template #default="{ row }"><el-tag size="small" :type="stType(row.status)">{{ row.status }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button v-if="row.status === '待审'" link size="small" type="success" @click="doApprove(row)">通过</el-button>
          <el-button v-if="row.status === '待审'" link size="small" type="danger" @click="doReject(row)">驳回</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="cmtDlg" :title="cmtTitle" width="420px">
      <el-input v-model="cmt" type="textarea" :rows="3" placeholder="审核意见（可选）" />
      <template #footer><el-button @click="cmtDlg = false">取消</el-button><el-button type="primary" @click="confirmCmt">确定</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errMsg } from '@/api'

const status = ref('待审')
const rows = ref<any[]>([])
const loading = ref(false)
const cmtDlg = ref(false)
const cmtTitle = ref('')
const cmt = ref('')
const pendingAct = ref<{ id: number; act: 'approve' | 'reject' } | null>(null)

const stType = (s: string): any => ({ 通过: 'success', 待审: 'warning', 驳回: 'danger', 草稿: 'info' } as any)[s] || ''
async function loadAudit() { loading.value = true; try { rows.value = await api.subAuditList(status.value || undefined) } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
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
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
</style>
