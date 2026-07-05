<template>
  <div class="dl-card">
    <div class="card-title"><span>主数据</span><span class="role-tag">系统管理员</span></div>
    <el-table :data="masters" size="small" stripe border v-loading="loading">
      <el-table-column prop="code" label="编码" width="140" />
      <el-table-column prop="name" label="名称" min-width="120" />
      <el-table-column prop="description" label="说明" min-width="160" show-overflow-tooltip />
      <el-table-column label="字段定义" min-width="200"><template #default="{ row }"><span class="muted">{{ brief(row.fields_json) }}</span></template></el-table-column>
      <el-table-column label="操作" width="220"><template #default="{ row }"><el-button link size="small" type="primary" @click="openRecords(row)">记录</el-button><el-button link size="small" type="primary" @click="open(row)">编辑</el-button><el-button link size="small" type="danger" @click="del(row)">删除</el-button></template></el-table-column>
    </el-table>

    <el-dialog v-model="dlg" :title="form.id ? '编辑主数据' : '新增主数据'" width="540px">
      <el-form :model="form" label-width="80px" size="small">
        <el-form-item label="编码"><el-input v-model="form.code" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="form.description" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="字段定义"><el-input v-model="form.fields_json" type="textarea" :rows="3" placeholder='[{"name":"code","type":"VARCHAR(64)"},{"name":"name","type":"VARCHAR(128)"}]' /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dlg = false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="recDlg" :title="`主数据记录 - ${cur?.name || ''}`" width="720px">
      <div style="margin-bottom:8px"><el-button size="small" type="primary" @click="newRecDlg = true">新增记录</el-button></div>
      <el-table :data="records" size="small" border max-height="360">
        <el-table-column label="数据" min-width="400"><template #default="{ row }"><code>{{ row.data_json }}</code></template></el-table-column>
        <el-table-column prop="create_time" label="创建时间" width="160" />
        <el-table-column label="操作" width="80"><template #default="{ row }"><el-button link size="small" type="danger" @click="delRec(row)">删除</el-button></template></el-table-column>
      </el-table>
    </el-dialog>

    <el-dialog v-model="newRecDlg" title="新增记录" width="500px">
      <el-input v-model="newRecData" type="textarea" :rows="6" placeholder='{"code":"M001","name":"..."}' />
      <template #footer><el-button @click="newRecDlg = false">取消</el-button><el-button type="primary" @click="addRec">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errMsg } from '@/api'

const masters = ref<any[]>([]); const loading = ref(false)
const dlg = ref(false); const form = reactive<any>({ id: null, code: '', name: '', description: '', fields_json: '' })
const recDlg = ref(false); const cur = ref<any>(null); const records = ref<any[]>([])
const newRecDlg = ref(false); const newRecData = ref('')

function brief(j: string) { try { const a = JSON.parse(j || '[]'); return a.map((x:any) => x.name + ':' + x.type).join(', ') } catch { return j || '' } }
async function load() { loading.value = true; try { masters.value = await api.govMasters() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
function open(row?: any) { Object.assign(form, { id: null, code: '', name: '', description: '', fields_json: '' }, row || {}); dlg.value = true }
async function save() { try { await api.govSaveMaster({ ...form }); ElMessage.success('保存成功'); dlg.value = false; await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function del(row: any) { await ElMessageBox.confirm(`删除主数据 ${row.code}？`, '提示', { type: 'warning' }); try { await api.govDeleteMaster(row.id); ElMessage.success('已删除'); await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function openRecords(row: any) { cur.value = row; recDlg.value = true; try { records.value = await api.govMasterRecords(row.id) } catch { records.value = [] } }
async function addRec() { try { await api.govSaveMasterRecord({ master_id: cur.value.id, data_json: newRecData.value }); newRecDlg.value = false; newRecData.value = ''; records.value = await api.govMasterRecords(cur.value.id) } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function delRec(row: any) { try { await api.govDeleteMasterRecord(row.id); records.value = await api.govMasterRecords(cur.value.id) } catch (e:any) { ElMessage.error(errMsg(e)) } }

onMounted(load)
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.muted { color: var(--tech-text-muted); font-size: 12px; }
</style>
