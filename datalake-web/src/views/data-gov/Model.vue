<template>
  <div class="dl-card">
    <div class="card-title"><span>数据模型</span><span class="role-tag">系统管理员</span></div>
    <el-button type="primary" size="small" @click="open()" style="margin-bottom:10px"><el-icon><Plus /></el-icon> 新增模型</el-button>
    <el-table :data="models" size="small" stripe border v-loading="loading">
      <el-table-column prop="name" label="模型名称" min-width="140" />
      <el-table-column prop="domain" label="主题域" width="120" />
      <el-table-column prop="model_type" label="类型" width="100"><template #default="{ row }"><el-tag size="small">{{ row.model_type }}</el-tag></template></el-table-column>
      <el-table-column prop="description" label="说明" min-width="180" show-overflow-tooltip />
      <el-table-column label="操作" width="240"><template #default="{ row }"><el-button link size="small" type="primary" @click="openTables(row)">表</el-button><el-button link size="small" type="primary" @click="open(row)">编辑</el-button><el-button link size="small" type="danger" @click="del(row)">删除</el-button></template></el-table-column>
    </el-table>

    <el-dialog v-model="dlg" :title="form.id ? '编辑模型' : '新增模型'" width="480px">
      <el-form :model="form" label-width="70px" size="small">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="主题域"><el-input v-model="form.domain" placeholder="如 生产/质量" /></el-form-item>
        <el-form-item label="类型"><el-select v-model="form.model_type" style="width:100%"><el-option v-for="t in ['概念模型','逻辑模型','物理模型']" :key="t" :label="t" :value="t" /></el-select></el-form-item>
        <el-form-item label="说明"><el-input v-model="form.description" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dlg = false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="tableDlg" :title="`模型表 - ${cur?.name || ''}`" width="720px">
      <div style="margin-bottom:8px"><el-button size="small" type="primary" @click="newTableDlg = true">新增表</el-button></div>
      <el-table :data="mTables" size="small" border max-height="320">
        <el-table-column prop="name" label="表名" min-width="160" />
        <el-table-column prop="layer" label="层级" width="80" />
        <el-table-column prop="description" label="说明" min-width="160" />
        <el-table-column label="操作" width="160"><template #default="{ row }"><el-button link size="small" type="primary" @click="openFields(row)">字段</el-button><el-button link size="small" type="danger" @click="delTable(row)">删除</el-button></template></el-table-column>
      </el-table>
    </el-dialog>

    <el-dialog v-model="newTableDlg" title="新增模型表" width="440px">
      <el-form :model="tForm" label-width="60px" size="small">
        <el-form-item label="表名"><el-input v-model="tForm.name" /></el-form-item>
        <el-form-item label="层级"><el-select v-model="tForm.layer" style="width:100%"><el-option v-for="l in ['ods','dwd','dws','ads','dim']" :key="l" :label="l" :value="l" /></el-select></el-form-item>
        <el-form-item label="说明"><el-input v-model="tForm.description" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="newTableDlg = false">取消</el-button><el-button type="primary" @click="addTable">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="fieldDlg" :title="`模型字段 - ${curTable?.name || ''}`" width="760px">
      <div style="margin-bottom:8px"><el-button size="small" type="primary" @click="addFieldDlg = true">新增字段</el-button></div>
      <el-table :data="mFields" size="small" border max-height="340">
        <el-table-column prop="name" label="字段" min-width="120" />
        <el-table-column prop="data_type" label="类型" width="110" />
        <el-table-column label="主键" width="60"><template #default="{ row }">{{ row.is_pk ? '是' : '' }}</template></el-table-column>
        <el-table-column label="可空" width="60"><template #default="{ row }">{{ row.nullable ? '是' : '否' }}</template></el-table-column>
        <el-table-column prop="comment" label="备注" min-width="140" />
        <el-table-column label="操作" width="80"><template #default="{ row }"><el-button link size="small" type="danger" @click="delField(row)">删除</el-button></template></el-table-column>
      </el-table>
    </el-dialog>

    <el-dialog v-model="addFieldDlg" title="新增字段" width="460px">
      <el-form :model="fForm" label-width="60px" size="small">
        <el-form-item label="字段"><el-input v-model="fForm.name" /></el-form-item>
        <el-form-item label="类型"><el-input v-model="fForm.data_type" placeholder="VARCHAR(64)" /></el-form-item>
        <el-form-item label="主键"><el-switch v-model="fForm.is_pk" /></el-form-item>
        <el-form-item label="可空"><el-switch v-model="fForm.nullable" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="fForm.comment" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="addFieldDlg = false">取消</el-button><el-button type="primary" @click="addField">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'

const models = ref<any[]>([]); const loading = ref(false)
const dlg = ref(false); const form = reactive<any>({ id: null, name: '', domain: '', model_type: '逻辑模型', description: '' })
const tableDlg = ref(false); const cur = ref<any>(null); const mTables = ref<any[]>([])
const newTableDlg = ref(false); const tForm = reactive<any>({ name: '', layer: 'dwd', description: '' })
const fieldDlg = ref(false); const curTable = ref<any>(null); const mFields = ref<any[]>([])
const addFieldDlg = ref(false); const fForm = reactive<any>({ name: '', data_type: 'VARCHAR(64)', is_pk: false, nullable: true, comment: '' })

async function load() { loading.value = true; try { models.value = await api.govModels() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
function open(row?: any) { Object.assign(form, { id: null, name: '', domain: '', model_type: '逻辑模型', description: '' }, row || {}); dlg.value = true }
async function save() { try { await api.govSaveModel({ ...form }); ElMessage.success('保存成功'); dlg.value = false; await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function del(row: any) { await ElMessageBox.confirm(`删除模型 ${row.name}？`, '提示', { type: 'warning' }); try { await api.govDeleteModel(row.id); ElMessage.success('已删除'); await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function openTables(row: any) { cur.value = row; tableDlg.value = true; try { mTables.value = await api.govModelTables(row.id) } catch { mTables.value = [] } }
async function addTable() { try { await api.govSaveModelTable({ model_id: cur.value.id, ...tForm }); newTableDlg.value = false; Object.assign(tForm, { name: '', layer: 'dwd', description: '' }); mTables.value = await api.govModelTables(cur.value.id) } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function delTable(row: any) { try { await api.govDeleteModelTable(row.id); mTables.value = await api.govModelTables(cur.value.id) } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function openFields(row: any) { curTable.value = row; fieldDlg.value = true; try { mFields.value = await api.govModelFields(row.id) } catch { mFields.value = [] } }
async function addField() { try { await api.govSaveModelField({ table_id: curTable.value.id, ...fForm }); addFieldDlg.value = false; Object.assign(fForm, { name: '', data_type: 'VARCHAR(64)', is_pk: false, nullable: true, comment: '' }); mFields.value = await api.govModelFields(curTable.value.id) } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function delField(row: any) { try { await api.govDeleteModelField(row.id); mFields.value = await api.govModelFields(curTable.value.id) } catch (e:any) { ElMessage.error(errMsg(e)) } }

onMounted(load)
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
</style>
