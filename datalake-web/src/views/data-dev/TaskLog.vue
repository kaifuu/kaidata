<template>
  <div class="dl-card">
    <div class="card-title"><span>任务日志</span><span class="role-tag">系统管理员</span></div>
    <div class="bar">
      <el-checkbox-group v-model="types" size="small">
        <el-checkbox-button label="SCRIPT">脚本</el-checkbox-button>
        <el-checkbox-button label="OFFLINE">离线</el-checkbox-button>
        <el-checkbox-button label="EXPORT">接出</el-checkbox-button>
        <el-checkbox-button label="WORKFLOW">工作流</el-checkbox-button>
      </el-checkbox-group>
      <el-select v-model="status" placeholder="状态" size="small" clearable style="width:120px">
        <el-option label="成功" value="SUCCESS" /><el-option label="失败" value="FAIL" />
      </el-select>
      <el-input v-model="kw" placeholder="任务名" size="small" style="width:160px" />
      <el-button size="small" type="primary" @click="load">查询</el-button>
      <el-button size="small" @click="clear('all')">清全部</el-button>
      <el-button size="small" @click="clear('failed')">清失败</el-button>
    </div>
    <el-table :data="rows" size="small" stripe border v-loading="loading">
      <el-table-column label="类型" width="80"><template #default="{ row }"><el-tag size="small" :type="tagType(row.log_type)">{{ typeLabel(row.log_type) }}</el-tag></template></el-table-column>
      <el-table-column prop="source_name" label="任务" min-width="140" />
      <el-table-column prop="start_time" label="时间" width="160" />
      <el-table-column label="结果" width="70"><template #default="{ row }"><el-tag size="small" :type="row.status === 'SUCCESS' ? 'success' : 'danger'">{{ row.status }}</el-tag></template></el-table-column>
      <el-table-column prop="num" label="行/节点" width="80" />
      <el-table-column prop="error_msg" label="说明" show-overflow-tooltip />
      <el-table-column label="操作" width="130">
        <template #default="{ row }"><el-button link size="small" @click="show(row)">查看</el-button><el-button link size="small" type="danger" @click="del(row)">删</el-button></template>
      </el-table-column>
    </el-table>
    <el-dialog v-model="dlg" title="日志详情" width="700px"><pre class="log-box">{{ detail }}</pre></el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errMsg } from '@/api'

const types = ref<string[]>([])
const status = ref('')
const kw = ref('')
const rows = ref<any[]>([])
const loading = ref(false)
const dlg = ref(false)
const detail = ref('')

async function load() {
  loading.value = true
  try { rows.value = await api.devTaskLogs({ logType: types.value.join(','), status: status.value || undefined, kw: kw.value || undefined }) }
  catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false }
}
async function show(row: any) {
  try { const d: any = await api.devTaskLogDetail(row.log_type, row.run_id); detail.value = JSON.stringify(d, null, 2); dlg.value = true }
  catch (e: any) { ElMessage.error(errMsg(e)) }
}
async function del(row: any) {
  try { await ElMessageBox.confirm('删除该日志?') } catch { return }
  try { await api.devTaskLogDelete(row.log_type, row.run_id); await load() } catch (e: any) { ElMessage.error(errMsg(e)) }
}
async function clear(rule: string) {
  try { await ElMessageBox.confirm(rule === 'all' ? '清空所有任务日志?' : '清除失败日志?', '提示', { type: 'warning' }) } catch { return }
  try { const r: any = await api.devTaskLogClear(undefined, rule); ElMessage.success(`已清 ${r.deleted}`); await load() }
  catch (e: any) { ElMessage.error(errMsg(e)) }
}
function tagType(t: string) { return ({ SCRIPT: '', OFFLINE: 'success', EXPORT: 'warning', WORKFLOW: 'info' } as any)[t] || '' }
function typeLabel(t: string) { return ({ SCRIPT: '脚本', OFFLINE: '离线', EXPORT: '接出', WORKFLOW: '工作流' } as any)[t] || t }

onMounted(load)
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 10px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.bar { display: flex; gap: 8px; margin-bottom: 10px; align-items: center; flex-wrap: wrap; }
.log-box { background: rgba(0, 0, 0, 0.3); color: #9fe; padding: 10px; font-size: 12px; white-space: pre-wrap; max-height: 400px; overflow: auto; border-radius: 4px; }
</style>
