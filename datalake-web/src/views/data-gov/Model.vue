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

    <el-dialog v-model="tableDlg" :title="`模型表 - ${cur?.name || ''}`" width="820px">
      <div style="margin-bottom:8px"><el-button size="small" type="primary" @click="newTableDlg = true">新增表</el-button><el-button size="small" @click="openReverse">逆向导入（物理表）</el-button></div>
      <el-table :data="mTables" size="small" border max-height="320">
        <el-table-column prop="name" label="表名" min-width="140" />
        <el-table-column prop="layer" label="层级" width="70" />
        <el-table-column prop="description" label="说明" min-width="120" show-overflow-tooltip />
        <el-table-column label="操作" width="290"><template #default="{ row }"><el-button link size="small" type="primary" @click="openFields(row)">字段</el-button><el-button link size="small" type="success" @click="showDdl(row)">生成DDL</el-button><el-button link size="small" type="warning" @click="openCreate(row)">建物理表</el-button><el-button link size="small" type="danger" @click="delTable(row)">删除</el-button></template></el-table-column>
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

    <el-dialog v-model="fieldDlg" :title="`模型字段 - ${curTable?.name || ''}`" width="820px">
      <div style="margin-bottom:8px"><el-button size="small" type="primary" @click="openField()">新增字段</el-button></div>
      <el-table :data="mFields" size="small" border max-height="340">
        <el-table-column prop="name" label="字段" min-width="110" />
        <el-table-column prop="data_type" label="类型" width="110" />
        <el-table-column label="数据元" min-width="120"><template #default="{ row }"><span v-if="row.element_name">{{ row.element_name }}</span><span v-else class="muted">-</span></template></el-table-column>
        <el-table-column label="主键" width="60"><template #default="{ row }">{{ row.is_pk ? '是' : '' }}</template></el-table-column>
        <el-table-column label="可空" width="60"><template #default="{ row }">{{ row.nullable ? '是' : '否' }}</template></el-table-column>
        <el-table-column prop="comment" label="备注" min-width="120" />
        <el-table-column label="操作" width="120"><template #default="{ row }"><el-button link size="small" type="primary" @click="openField(row)">编辑</el-button><el-button link size="small" type="danger" @click="delField(row)">删除</el-button></template></el-table-column>
      </el-table>
    </el-dialog>

    <el-dialog v-model="addFieldDlg" :title="fForm.id ? '编辑字段' : '新增字段'" width="480px">
      <el-form :model="fForm" label-width="80px" size="small">
        <el-form-item label="字段名"><el-input v-model="fForm.name" /></el-form-item>
        <el-form-item label="关联数据元">
          <el-select v-model="fForm.element_id" clearable filterable placeholder="选择数据元自动带出类型" style="width:100%" @change="onPickElement">
            <el-option v-for="el in elements" :key="el.id" :label="`${el.code} - ${el.name}`" :value="el.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="类型"><el-input v-model="fForm.data_type" placeholder="留空则按数据元带出" /></el-form-item>
        <el-form-item label="主键"><el-switch v-model="fForm.is_pk" /></el-form-item>
        <el-form-item label="可空"><el-switch v-model="fForm.nullable" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="fForm.comment" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="addFieldDlg = false">取消</el-button><el-button type="primary" @click="saveField">保存</el-button></template>
    </el-dialog>

    <!-- 生成 DDL -->
    <el-dialog v-model="ddlDlg" :title="`建表 DDL - ${ddlInfo.db}.${ddlInfo.table}`" width="680px">
      <el-input type="textarea" :rows="12" readonly :model-value="ddlInfo.ddl" />
      <div class="muted" style="margin-top:6px">目标库 {{ ddlInfo.db }}（数仓分层库，需已存在）。可复制后手动执行，或关闭后用「建物理表」一键执行。</div>
      <template #footer><el-button @click="ddlDlg = false">关闭</el-button></template>
    </el-dialog>

    <!-- 建物理表 -->
    <el-dialog v-model="createDlg" title="一键建物理表" width="440px">
      <el-form label-width="80px" size="small">
        <el-form-item label="目标表">{{ createInfo.table }}（{{ createInfo.db }}）</el-form-item>
        <el-form-item label="数据源">
          <el-select v-model="createDs" placeholder="选择执行数据源（StarRocks）" style="width:100%" filterable>
            <el-option v-for="d in sources" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="createDlg = false">取消</el-button><el-button type="primary" :loading="creating" @click="doCreate">建表</el-button></template>
    </el-dialog>

    <!-- 逆向导入 -->
    <el-dialog v-model="reverseDlg" title="物理表逆向导入模型" width="480px">
      <el-form label-width="80px" size="small">
        <el-form-item label="目标模型">{{ cur?.name }}</el-form-item>
        <el-form-item label="物理表">
          <el-select v-model="reverseMeta" filterable remote :remote-method="searchMeta" :loading="metaSearching" placeholder="搜索已采集的物理表" style="width:100%">
            <el-option v-for="m in metaOptions" :key="m.id" :label="m.table_name" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="归入分层">
          <el-select v-model="reverseLayer" style="width:100%"><el-option v-for="l in ['ods','dwd','dws','ads','dim']" :key="l" :label="l" :value="l" /></el-select>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="reverseDlg = false">取消</el-button><el-button type="primary" :loading="reversing" @click="doReverse">导入</el-button></template>
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
const addFieldDlg = ref(false); const fForm = reactive<any>({ id: null, name: '', data_type: '', element_id: 0, is_pk: false, nullable: true, comment: '' })
const elements = ref<any[]>([])

async function load() { loading.value = true; try { models.value = await api.govModels() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
async function loadElements() { try { elements.value = await api.govElements() } catch { elements.value = [] } }
function open(row?: any) { Object.assign(form, { id: null, name: '', domain: '', model_type: '逻辑模型', description: '' }, row || {}); dlg.value = true }
async function save() { try { await api.govSaveModel({ ...form }); ElMessage.success('保存成功'); dlg.value = false; await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function del(row: any) { await ElMessageBox.confirm(`删除模型 ${row.name}？`, '提示', { type: 'warning' }); try { await api.govDeleteModel(row.id); ElMessage.success('已删除'); await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function openTables(row: any) { cur.value = row; tableDlg.value = true; try { mTables.value = await api.govModelTables(row.id) } catch { mTables.value = [] } }
async function addTable() { try { await api.govSaveModelTable({ model_id: cur.value.id, ...tForm }); newTableDlg.value = false; Object.assign(tForm, { name: '', layer: 'dwd', description: '' }); mTables.value = await api.govModelTables(cur.value.id) } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function delTable(row: any) { try { await api.govDeleteModelTable(row.id); mTables.value = await api.govModelTables(cur.value.id) } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function openFields(row: any) { curTable.value = row; fieldDlg.value = true; try { mFields.value = await api.govModelFields(row.id) } catch { mFields.value = [] } }

function openField(row?: any) {
  Object.assign(fForm, { id: null, name: '', data_type: '', element_id: 0, is_pk: false, nullable: true, comment: '' }, row ? { ...row } : {})
  addFieldDlg.value = true
}
function onPickElement(elId: number) {
  const el = elements.value.find((e:any) => e.id === elId)
  if (el) {
    fForm.data_type = buildTypeStr(el.data_type, el.length, el.precision_, el.scale_)
    if (el.definition) fForm.comment = el.definition
  }
}
function buildTypeStr(t: any, len: any, prec: any, scale: any) {
  if (!t) return ''
  const u = String(t).toUpperCase()
  if (u === 'VARCHAR' || u === 'CHAR' || u === 'STRING') return len > 0 ? `${u}(${len})` : u
  if (u === 'DECIMAL' || u === 'NUMERIC') return `${u}(${prec > 0 ? prec : 10},${scale})`
  return u
}
async function saveField() {
  if (!fForm.name) return ElMessage.warning('请填字段名')
  try { await api.govSaveModelField({ table_id: curTable.value.id, ...fForm }); addFieldDlg.value = false; mFields.value = await api.govModelFields(curTable.value.id) } catch (e:any) { ElMessage.error(errMsg(e)) }
}
async function delField(row: any) { try { await api.govDeleteModelField(row.id); mFields.value = await api.govModelFields(curTable.value.id) } catch (e:any) { ElMessage.error(errMsg(e)) } }

// ===== 模型落地：DDL / 建物理表 / 逆向导入 =====
const sources = ref<any[]>([])
const ddlDlg = ref(false); const ddlInfo = reactive<any>({ ddl: '', db: '', table: '' })
const createDlg = ref(false); const createInfo = reactive<any>({ tableId: 0, table: '', db: '' }); const createDs = ref<number>(0); const creating = ref(false)
const reverseDlg = ref(false); const reverseMeta = ref<number>(0); const reverseLayer = ref('ods'); const reversing = ref(false); const metaOptions = ref<any[]>([]); const metaSearching = ref(false)

async function loadSources() { try { sources.value = await api.daSources() } catch { sources.value = [] } }
async function showDdl(row: any) {
  try { const r: any = await api.govModelDdl(row.id); Object.assign(ddlInfo, { ddl: r.ddl, db: r.db, table: r.table }); ddlDlg.value = true }
  catch (e: any) { ElMessage.error(errMsg(e)) }
}
function openCreate(row: any) { Object.assign(createInfo, { tableId: row.id, table: row.name, db: row.layer || 'ods' }); createDs.value = 0; createDlg.value = true }
async function doCreate() {
  if (!createDs.value) return ElMessage.warning('请选择数据源')
  creating.value = true
  try { const r: any = await api.govModelCreatePhysical(createInfo.tableId, createDs.value); ElMessage[r.success ? 'success' : 'error'](r.msg || (r.success ? '建表成功' : '建表失败')); if (r.success) createDlg.value = false }
  catch (e: any) { ElMessage.error(errMsg(e)) } finally { creating.value = false }
}
function openReverse() { reverseMeta.value = 0; reverseLayer.value = 'ods'; metaOptions.value = []; reverseDlg.value = true }
async function searchMeta(q: string) {
  if (!q) { metaOptions.value = []; return }
  metaSearching.value = true
  try { metaOptions.value = await api.govMetaList({ kw: q }) } catch { metaOptions.value = [] } finally { metaSearching.value = false }
}
async function doReverse() {
  if (!reverseMeta.value) return ElMessage.warning('请选择物理表')
  reversing.value = true
  try { const r: any = await api.govModelReverse(reverseMeta.value, cur.value.id, reverseLayer.value); ElMessage.success(`已导入，新增 ${r.fields} 个字段`); reverseDlg.value = false; mTables.value = await api.govModelTables(cur.value.id) }
  catch (e: any) { ElMessage.error(errMsg(e)) } finally { reversing.value = false }
}

onMounted(() => { load(); loadElements(); loadSources() })
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.muted { color: var(--tech-text-muted); font-size: 12px; }
</style>
