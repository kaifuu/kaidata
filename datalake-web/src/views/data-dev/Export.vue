<template>
  <div class="dl-card">
    <div class="card-title"><span>数据接出</span><span class="role-tag">系统管理员</span></div>
    <el-button type="primary" size="small" @click="open()" style="margin-bottom:10px"><el-icon><Plus /></el-icon> 新增接出</el-button>
    <el-table :data="rows" size="small" stripe border v-loading="loading">
      <el-table-column prop="name" label="名称" min-width="130" />
      <el-table-column label="源" min-width="180"><template #default="{ row }">ds{{ row.source_ds_id }} · {{ brief(row.source_query) }}</template></el-table-column>
      <el-table-column prop="target_type" label="目标" width="80"><template #default="{ row }"><el-tag size="small" :type="row.target_type === 'db' ? 'success' : 'warning'">{{ row.target_type }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="220"><template #default="{ row }"><el-button link size="small" type="success" :loading="running === row.id" @click="run(row)">执行</el-button><el-button link size="small" type="primary" @click="openRuns(row)">历史</el-button><el-button link size="small" type="primary" @click="open(row)">编辑</el-button><el-button link size="small" type="danger" @click="del(row)">删除</el-button></template></el-table-column>
    </el-table>

    <el-dialog v-model="dlg" :title="form.id ? '编辑接出' : '新增接出'" width="620px">
      <el-form :model="form" label-width="90px" size="small">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="源数据源"><el-select v-model="form.source_ds_id" style="width:100%"><el-option v-for="d in dsList" :key="d.id" :label="`${d.name}(${d.type})`" :value="d.id" /></el-select></el-form-item>
        <el-form-item label="源查询"><el-input v-model="form.source_query" type="textarea" :rows="3" placeholder="SELECT * FROM ods.ods_batch WHERE status='DONE'" /></el-form-item>
        <el-form-item label="目标类型"><el-radio-group v-model="form.target_type"><el-radio value="db">写入数据库</el-radio><el-radio value="api">REST推送</el-radio></el-radio-group></el-form-item>
        <el-form-item v-if="form.target_type === 'db'" label="目标配置">
          <el-input v-model="form.target_config" type="textarea" :rows="2" placeholder='{"datasource_id":123,"table":"ads.target_table"}' />
        </el-form-item>
        <el-form-item v-else label="目标配置">
          <el-input v-model="form.target_config" type="textarea" :rows="2" placeholder='{"url":"http://host:port/api/receive"}' />
        </el-form-item>
        <el-form-item label="格式"><el-select v-model="form.format" style="width:120px"><el-option value="json" /><el-option value="csv" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="dlg = false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="runsDlg" :title="`执行历史 - ${cur?.name || ''}`" width="720px">
      <el-table :data="runRows" size="small" border max-height="380">
        <el-table-column prop="run_time" label="时间" width="160" />
        <el-table-column label="状态" width="80"><template #default="{ row }"><el-tag size="small" :type="row.status === 'SUCCESS' ? 'success' : 'danger'">{{ row.status }}</el-tag></template></el-table-column>
        <el-table-column prop="rows_out" label="输出行" width="90" />
        <el-table-column prop="error_msg" label="错误" min-width="220" show-overflow-tooltip />
      </el-table>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'
const rows = ref<any[]>([]); const loading = ref(false); const dsList = ref<any[]>([])
const dlg = ref(false); const form = reactive<any>({ id: null, name: '', source_ds_id: null, source_query: '', target_type: 'db', target_config: '', format: 'json' })
const running = ref<number | null>(null); const runsDlg = ref(false); const cur = ref<any>(null); const runRows = ref<any[]>([])
function brief(q: string) { return (q || '').replace(/\s+/g, ' ').slice(0, 40) }
async function load() { loading.value = true; try { rows.value = await api.devExports(); dsList.value = await api.daSources() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
function open(row?: any) { Object.assign(form, { id: null, name: '', source_ds_id: dsList.value[0]?.id || null, source_query: '', target_type: 'db', target_config: '', format: 'json' }, row || {}); dlg.value = true }
async function save() { if (!form.name) return ElMessage.warning('填名称'); try { await api.devSaveExport({ ...form }); ElMessage.success('保存成功'); dlg.value = false; await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function del(row: any) { await ElMessageBox.confirm(`删除 ${row.name}？`, '提示', { type: 'warning' }); try { await api.devDeleteExport(row.id); ElMessage.success('已删除'); await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function run(row: any) { running.value = row.id; try { const r:any = await api.devRunExport(row.id); r.status === 'SUCCESS' ? ElMessage.success(`输出 ${r.rowsOut} 行`) : ElMessage.error(r.msg) } catch (e:any) { ElMessage.error(errMsg(e)) } finally { running.value = null } }
async function openRuns(row: any) { cur.value = row; runsDlg.value = true; try { runRows.value = await api.devExportRuns(row.id) } catch { runRows.value = [] } }
onMounted(load)
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
</style>
