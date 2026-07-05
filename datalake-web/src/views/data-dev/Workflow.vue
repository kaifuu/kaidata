<template>
  <div class="dl-card">
    <div class="card-title"><span>工作流 · 任务链调度</span><span class="role-tag">系统管理员</span></div>
    <el-button type="primary" size="small" @click="open()" style="margin-bottom:10px"><el-icon><Plus /></el-icon> 新建工作流</el-button>
    <el-table :data="rows" size="small" stripe border v-loading="loading">
      <el-table-column prop="name" label="名称" min-width="140" />
      <el-table-column prop="cron" label="周期(秒)" width="90" />
      <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag size="small" :type="row.status === 'ONLINE' ? 'success' : 'info'">{{ row.status }}</el-tag></template></el-table-column>
      <el-table-column prop="create_time" label="创建" width="160" />
      <el-table-column label="操作" width="290"><template #default="{ row }">
        <el-button link size="small" type="success" :loading="running === row.id" @click="run(row)">执行</el-button>
        <el-button v-if="row.status !== 'ONLINE'" link size="small" type="primary" @click="online(row)">上线</el-button>
        <el-button v-else link size="small" type="warning" @click="offline(row)">下线</el-button>
        <el-button link size="small" type="primary" @click="openRuns(row)">历史</el-button>
        <el-button link size="small" type="primary" :disabled="row.status === 'ONLINE'" @click="open(row)">编辑</el-button>
        <el-button link size="small" type="danger" :disabled="row.status === 'ONLINE'" @click="del(row)">删除</el-button>
      </template></el-table-column>
    </el-table>

    <el-dialog v-model="dlg" :title="form.id ? '编辑工作流' : '新建工作流'" width="720px">
      <el-form :model="form" label-width="80px" size="small">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="周期(秒)"><el-input v-model="form.cron" placeholder="上线后按此间隔执行；留空手动执行" /></el-form-item>
        <el-form-item label="任务链">
          <div style="width:100%">
            <div v-for="(n, i) in form.nodes" :key="i" style="display:flex;gap:6px;margin-bottom:6px;align-items:center">
              <span class="step">{{ i + 1 }}</span>
              <el-select v-model="n.node_type" size="small" style="width:120px" @change="onType(n)"><el-option value="script" label="数据开发" /><el-option value="export" label="数据接出" /></el-select>
              <el-select v-model="n.node_id" size="small" style="flex:1" :placeholder="n.node_type === 'script' ? '选脚本' : '选接出任务'">
                <el-option v-for="t in (n.node_type === 'script' ? scripts : exportList)" :key="t.id" :label="t.name" :value="t.id" />
              </el-select>
              <el-button size="small" link type="danger" @click="form.nodes.splice(i,1)">移除</el-button>
            </div>
            <el-button size="small" @click="addNode">+ 添加节点</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="dlg = false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="runsDlg" :title="`执行历史 - ${cur?.name || ''}`" width="820px">
      <el-table :data="runRows" size="small" border max-height="340">
        <el-table-column prop="run_time" label="时间" width="160" />
        <el-table-column label="状态" width="80"><template #default="{ row }"><el-tag size="small" :type="row.status === 'SUCCESS' ? 'success' : 'danger'">{{ row.status }}</el-tag></template></el-table-column>
        <el-table-column label="节点" width="90"><template #default="{ row }">{{ row.nodes_pass }} / {{ row.nodes_total }}</template></el-table-column>
        <el-table-column label="操作" width="80"><template #default="{ row }"><el-button link size="small" type="primary" @click="openLog(row)">日志</el-button></template></el-table-column>
      </el-table>
    </el-dialog>
    <el-dialog v-model="logDlg" title="执行日志" width="700px"><pre class="log-box">{{ logText }}</pre></el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { api, errMsg, http } from '@/api'

const rows = ref<any[]>([])
const loading = ref(false)
const scripts = ref<any[]>([])
const exportList = ref<any[]>([])
const running = ref<number | null>(null)
const dlg = ref(false)
const form = reactive<any>({ id: null, name: '', cron: '', status: 'OFFLINE', nodes: [] as any[] })
const runsDlg = ref(false)
const cur = ref<any>(null)
const runRows = ref<any[]>([])
const logDlg = ref(false)
const logText = ref('')

async function load() {
  loading.value = true
  try { rows.value = await api.devWorkflows(); scripts.value = await api.devScripts(); exportList.value = await api.devExports() }
  catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false }
}
function addNode() { form.nodes.push({ node_type: 'script', node_id: null }) }
function onType(n: any) { n.node_id = null }
function open(row?: any) {
  const nodes = row && row.nodes ? row.nodes.map((n: any) => ({ node_type: n.node_type, node_id: n.node_id })) : []
  Object.assign(form, { id: row?.id || null, name: row?.name || '', cron: row?.cron || '', status: row?.status || 'OFFLINE', nodes })
  dlg.value = true
}
async function save() {
  if (!form.name) return ElMessage.warning('填名称')
  try { await api.devSaveWorkflow({ ...form }); ElMessage.success('保存成功'); dlg.value = false; await load() }
  catch (e: any) { ElMessage.error(errMsg(e)) }
}
async function del(row: any) { await ElMessageBox.confirm(`删除 ${row.name}？`, '提示', { type: 'warning' }); try { await api.devDeleteWorkflow(row.id); ElMessage.success('已删除'); await load() } catch (e: any) { ElMessage.error(errMsg(e)) } }
async function run(row: any) { running.value = row.id; try { const r: any = await api.devRunWorkflow(row.id); ElMessage.success(`执行完成：${r.nodesPass}/${r.nodesTotal} 节点成功`) } catch (e: any) { ElMessage.error(errMsg(e)) } finally { running.value = null } }
async function online(row: any) { try { await api.devOnlineWorkflow(row.id); ElMessage.success('已上线'); await load() } catch (e: any) { ElMessage.error(errMsg(e)) } }
async function offline(row: any) { try { await api.devOfflineWorkflow(row.id); ElMessage.success('已下线'); await load() } catch (e: any) { ElMessage.error(errMsg(e)) } }
async function openRuns(row: any) { cur.value = row; runsDlg.value = true; try { runRows.value = await api.devWorkflowRuns(row.id) } catch { runRows.value = [] } }
async function openLog(r: any) { try { const d: any = await http.get('/data-dev/workflow/run-detail', { params: { id: r.id } }); logText.value = d.log_text || '(无日志)'; logDlg.value = true } catch (e: any) { ElMessage.error(errMsg(e)) } }

onMounted(load)
</script>

<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.step { width: 22px; height: 22px; border-radius: 50%; background: var(--tech-primary); color: #fff; display: inline-flex; align-items: center; justify-content: center; font-size: 12px; }
.log-box { background: var(--el-fill-color-light); padding: 10px; border-radius: 6px; max-height: 420px; overflow: auto; font-size: 12px; white-space: pre-wrap; }
</style>
