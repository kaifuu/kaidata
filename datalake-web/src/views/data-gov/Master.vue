<template>
  <div class="dl-card">
    <div class="card-title"><span>主数据</span><span class="role-tag">系统管理员</span></div>
    <el-table :data="masters" size="small" stripe border v-loading="loading">
      <el-table-column prop="code" label="编码" width="140" />
      <el-table-column prop="name" label="名称" min-width="120" />
      <el-table-column prop="description" label="说明" min-width="160" show-overflow-tooltip />
      <el-table-column label="字段定义" min-width="200"><template #default="{ row }"><span class="muted">{{ brief(row.fields_json) }}</span></template></el-table-column>
      <el-table-column prop="record_count" label="记录数" width="80" align="center" />
      <el-table-column label="操作" width="240">
        <template #default="{ row }">
          <el-button link size="small" type="primary" @click="openRecords(row)">记录</el-button>
          <el-button link size="small" type="primary" @click="openRefs(row)">引用</el-button>
          <el-button link size="small" type="primary" @click="open(row)">编辑</el-button>
          <el-button link size="small" type="danger" @click="del(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dlg" :title="form.id ? '编辑主数据' : '新增主数据'" width="540px">
      <el-form :model="form" label-width="80px" size="small">
        <el-form-item label="编码"><el-input v-model="form.code" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="form.description" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="字段定义"><el-input v-model="form.fields_json" type="textarea" :rows="3" placeholder='[{"name":"code","type":"VARCHAR(64)","required":true},{"name":"name","type":"VARCHAR(128)","required":true}]' /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dlg = false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>

    <!-- 记录抽屉：动态列 + 动态表单 + 变更审计 -->
    <el-drawer v-model="recDlg" :title="`主数据记录 - ${cur?.name || ''}`" size="860px">
      <el-tabs v-model="recTab">
        <el-tab-pane label="记录" name="list">
          <div style="margin-bottom:8px"><el-button size="small" type="primary" @click="openRec()">新增记录</el-button></div>
          <el-table :data="records" size="small" border max-height="420">
            <el-table-column v-for="f in fields" :key="f.name" :label="f.name" :min-width="f.name.length > 8 ? 140 : 100" show-overflow-tooltip>
              <template #default="{ row }"><span :class="{ 'code-cell': f.name === codeField }">{{ recVal(row, f.name) }}</span></template>
            </el-table-column>
            <el-table-column prop="create_time" label="创建时间" width="160" />
            <el-table-column label="操作" width="110">
              <template #default="{ row }">
                <el-button link size="small" type="primary" @click="openRec(row)">编辑</el-button>
                <el-button link size="small" type="danger" @click="delRec(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane :label="`变更审计 (${audits.length})`" name="audit">
          <el-table :data="audits" size="small" border max-height="460">
            <el-table-column prop="create_time" label="时间" width="160" />
            <el-table-column prop="action" label="操作" width="80"><template #default="{ row }"><el-tag size="small" :type="row.action === 'DELETE' ? 'danger' : row.action === 'UPDATE' ? 'warning' : 'success'">{{ actionText(row.action) }}</el-tag></template></el-table-column>
            <el-table-column prop="operator" label="操作人" width="100" />
            <el-table-column prop="record_id" label="记录ID" width="140" />
            <el-table-column label="旧值 → 新值" min-width="380"><template #default="{ row }"><div class="audit-json"><div v-if="row.old_json" class="muted">旧: {{ row.old_json }}</div><div v-if="row.new_json">新: {{ row.new_json }}</div></div></template></el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-drawer>

    <!-- 动态表单：按字段定义渲染 + 编码失焦查重 -->
    <el-dialog v-model="recFormDlg" :title="recForm.id ? '编辑记录' : '新增记录'" width="520px">
      <el-form label-width="110px" size="small">
        <el-form-item v-for="f in fields" :key="f.name" :label="f.name" :required="f.required">
          <el-input v-if="isNum(f.type)" v-model="recForm.data[f.name]" :placeholder="f.type" @blur="checkCode(f.name)" />
          <el-input v-else v-model="recForm.data[f.name]" :placeholder="f.type + (f.required ? '（必填）' : '')" @blur="checkCode(f.name)" />
          <div v-if="f.name === codeField && codeMsg" :class="codeDup ? 'dup-msg' : 'ok-msg'">{{ codeMsg }}</div>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="recFormDlg = false">取消</el-button><el-button type="primary" @click="saveRec">保存</el-button></template>
    </el-dialog>

    <!-- 引用统计 -->
    <el-dialog v-model="refsDlg" :title="`引用统计 - ${cur?.name || ''}`" width="760px">
      <el-tabs>
        <el-tab-pane :label="`模型字段 (${refs.modelRefs?.length || 0})`">
          <el-table :data="refs.modelRefs || []" size="small" border max-height="320">
            <el-table-column prop="model_name" label="模型" min-width="120" />
            <el-table-column prop="table_name" label="表" min-width="120" />
            <el-table-column prop="field_name" label="字段" min-width="100" />
            <el-table-column prop="comment" label="说明" min-width="140" show-overflow-tooltip />
          </el-table>
        </el-tab-pane>
        <el-tab-pane :label="`元数据表 (${refs.metaRefs?.length || 0})`">
          <el-table :data="refs.metaRefs || []" size="small" border max-height="320">
            <el-table-column prop="schema_name" label="库" width="100" />
            <el-table-column prop="table_name" label="表名" min-width="140" />
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errMsg } from '@/api'

const masters = ref<any[]>([]); const loading = ref(false)
const dlg = ref(false); const form = reactive<any>({ id: null, code: '', name: '', description: '', fields_json: '' })
const recDlg = ref(false); const recTab = ref('list'); const cur = ref<any>(null)
const records = ref<any[]>([]); const audits = ref<any[]>([])
const recFormDlg = ref(false); const recForm = reactive<any>({ id: null, data: {} })
const codeMsg = ref(''); const codeDup = ref(false)
const refsDlg = ref(false); const refs = ref<any>({})

const fields = computed<any[]>(() => { try { return JSON.parse(cur.value?.fields_json || '[]') } catch { return [] } })
const codeField = computed(() => {
  const fs = fields.value
  const c = fs.find((f: any) => (f.name || '').toLowerCase() === 'code')
  return (c || fs[0] || {}).name || ''
})

function brief(j: string) { try { const a = JSON.parse(j || '[]'); return a.map((x:any) => x.name + ':' + x.type).join(', ') } catch { return j || '' } }
function recVal(row: any, name: string) { try { return JSON.parse(row.data_json)[name] ?? '' } catch { return '' } }
function isNum(t: string) { return /^(INT|BIGINT|LONG|DECIMAL|DOUBLE|FLOAT)/i.test(t || '') }
function actionText(a: string) { return a === 'CREATE' ? '新增' : a === 'UPDATE' ? '修改' : '删除' }

async function load() { loading.value = true; try { masters.value = await api.govMasters() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
function open(row?: any) { Object.assign(form, { id: null, code: '', name: '', description: '', fields_json: '' }, row || {}); dlg.value = true }
async function save() { try { await api.govSaveMaster({ ...form }); ElMessage.success('保存成功'); dlg.value = false; await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function del(row: any) { await ElMessageBox.confirm(`删除主数据 ${row.code}？`, '提示', { type: 'warning' }); try { await api.govDeleteMaster(row.id); ElMessage.success('已删除'); await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }

async function openRecords(row: any) {
  cur.value = row; recTab.value = 'list'; recDlg.value = true
  try { records.value = await api.govMasterRecords(row.id) } catch { records.value = [] }
  try { audits.value = await api.govMasterAudit(row.id) } catch { audits.value = [] }
}
function openRec(row?: any) {
  const data: any = {}
  for (const f of fields.value) data[f.name] = row ? recVal(row, f.name) : ''
  Object.assign(recForm, { id: row?.id || null, data })
  codeMsg.value = ''; codeDup.value = false; recFormDlg.value = true
}
async function checkCode(fieldName: string) {
  if (fieldName !== codeField.value || !recForm.data[fieldName]) { codeMsg.value = ''; codeDup.value = false; return }
  try {
    const r = await api.govMasterCheckDuplicate(cur.value.id, recForm.id || 0, recForm.data[fieldName])
    codeDup.value = !!r.dup; codeMsg.value = r.dup ? `编码已存在（记录 ${r.conflictId}）` : '编码可用'
  } catch { codeMsg.value = ''; codeDup.value = false }
}
async function saveRec() {
  try {
    await api.govSaveMasterRecord(recForm.id
      ? { id: recForm.id, master_id: cur.value.id, data_json: JSON.stringify(recForm.data) }
      : { master_id: cur.value.id, data_json: JSON.stringify(recForm.data) })
    ElMessage.success('保存成功'); recFormDlg.value = false
    records.value = await api.govMasterRecords(cur.value.id)
    audits.value = await api.govMasterAudit(cur.value.id)
    await load()
  } catch (e:any) { ElMessage.error(errMsg(e)) }
}
async function delRec(row: any) {
  await ElMessageBox.confirm('删除该记录？', '提示', { type: 'warning' })
  try {
    await api.govDeleteMasterRecord(row.id)
    records.value = await api.govMasterRecords(cur.value.id)
    audits.value = await api.govMasterAudit(cur.value.id)
    await load()
  } catch (e:any) { ElMessage.error(errMsg(e)) }
}
async function openRefs(row: any) {
  cur.value = row; refsDlg.value = true
  try { refs.value = await api.govMasterRefs(row.id) } catch { refs.value = {} }
}

onMounted(load)
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.muted { color: var(--tech-text-muted); font-size: 12px; }
.code-cell { font-family: monospace; font-weight: 600; }
.audit-json { font-size: 12px; word-break: break-all; }
.audit-json .muted { text-decoration: line-through; }
.dup-msg { color: var(--el-color-danger); font-size: 12px; margin-top: 2px; }
.ok-msg { color: var(--el-color-success); font-size: 12px; margin-top: 2px; }
</style>
