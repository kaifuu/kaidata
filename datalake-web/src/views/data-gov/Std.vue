<template>
  <div class="dl-card">
    <div class="card-title"><span>数据标准</span><span class="role-tag">系统管理员</span></div>
    <el-tabs v-model="tab">
      <!-- 数据元 -->
      <el-tab-pane label="数据元" name="element">
        <div class="filter-bar">
          <el-select v-model="f.category" placeholder="业务分类" clearable size="small" style="width:130px">
            <el-option v-for="c in CATEGORIES" :key="c" :label="c" :value="c" />
          </el-select>
          <el-select v-model="f.status" placeholder="状态" clearable size="small" style="width:100px">
            <el-option label="正常" value="NORMAL" />
            <el-option label="停用" value="DISABLED" />
          </el-select>
          <el-input v-model="f.keyword" placeholder="编码/名称/英文名" clearable size="small" style="width:200px" @keyup.enter="loadEl" />
          <el-button size="small" type="primary" @click="loadEl">搜索</el-button>
          <el-button size="small" type="primary" @click="openEl()" style="margin-left:auto">
            <el-icon><Plus /></el-icon> 新增数据元
          </el-button>
        </div>
        <el-table :data="elements" size="small" stripe border v-loading="loading">
          <el-table-column prop="code" label="编码" width="130" />
          <el-table-column prop="name" label="名称" min-width="100" />
          <el-table-column prop="en_name" label="英文名" width="110" />
          <el-table-column label="分类" width="80">
            <template #default="{ row }"><el-tag size="small" v-if="row.category">{{ row.category }}</el-tag></template>
          </el-table-column>
          <el-table-column label="类型" width="110">
            <template #default="{ row }">{{ row.data_type }}<span class="muted" v-if="row.length">({{ row.length }}{{ row.scale_ ? ',' + row.scale_ : '' }})</span></template>
          </el-table-column>
          <el-table-column prop="unit" label="单位" width="60" />
          <el-table-column label="引用代码集" width="110">
            <template #default="{ row }"><span v-if="row.code_set_name">{{ row.code_set_name }}</span><span v-else class="muted">-</span></template>
          </el-table-column>
          <el-table-column label="引用" width="70">
            <template #default="{ row }"><el-link v-if="row.ref_cnt > 0" type="primary" @click="openElRefs(row)">{{ row.ref_cnt }}</el-link><span v-else class="muted">0</span></template>
          </el-table-column>
          <el-table-column label="操作" width="130">
            <template #default="{ row }"><el-button link size="small" type="primary" @click="openEl(row)">编辑</el-button><el-button link size="small" type="danger" @click="delEl(row)">删除</el-button></template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 代码集 -->
      <el-tab-pane label="代码集" name="code">
        <div class="filter-bar">
          <el-select v-model="cf.category" placeholder="分类" clearable size="small" style="width:130px">
            <el-option v-for="c in CODE_CATEGORIES" :key="c" :label="c" :value="c" />
          </el-select>
          <el-input v-model="cf.keyword" placeholder="编码/名称" clearable size="small" style="width:180px" @keyup.enter="loadCs" />
          <el-button size="small" type="primary" @click="loadCs">搜索</el-button>
          <el-button size="small" type="primary" @click="openCs()" style="margin-left:auto">
            <el-icon><Plus /></el-icon> 新增代码集
          </el-button>
        </div>
        <el-table :data="codeSets" size="small" stripe border v-loading="loadingCs">
          <el-table-column prop="code" label="编码" width="130" />
          <el-table-column prop="name" label="名称" min-width="120" />
          <el-table-column label="分类" width="80">
            <template #default="{ row }"><el-tag size="small" v-if="row.category">{{ row.category }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="item_cnt" label="代码项" width="70" />
          <el-table-column label="被引用" width="70">
            <template #default="{ row }"><el-link v-if="row.ref_cnt > 0" type="primary" @click="openCsRefs(row)">{{ row.ref_cnt }}</el-link><span v-else class="muted">0</span></template>
          </el-table-column>
          <el-table-column prop="description" label="说明" min-width="140" show-overflow-tooltip />
          <el-table-column label="操作" width="210">
            <template #default="{ row }"><el-button link size="small" type="success" @click="openItems(row)">代码项</el-button><el-button link size="small" type="primary" @click="openCs(row)">编辑</el-button><el-button link size="small" type="danger" @click="delCs(row)">删除</el-button></template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 落标概况 -->
      <el-tab-pane label="落标概况" name="landing">
        <div v-loading="landingLoading">
          <el-row :gutter="12" v-if="landing">
            <el-col :span="6"><div class="ring-box"><v-chart :option="rateOption" :theme="theme" autoresize style="height:150px" /></div></el-col>
            <el-col :span="18">
              <div class="muted" style="margin-bottom:6px">已落标 {{ landing.landed }} / {{ landing.total }} 字段 · 引用最多的数据元 Top5</div>
              <el-table :data="landing.topElements || []" size="small" border max-height="140">
                <el-table-column prop="name" label="数据元" min-width="140" /><el-table-column prop="code" label="编码" width="120" /><el-table-column prop="refs" label="引用数" width="80" />
              </el-table>
            </el-col>
          </el-row>
          <div style="margin:12px 0 6px" class="muted">未落标字段（未关联数据元）</div>
          <el-table :data="landing?.unlanded || []" size="small" border max-height="200">
            <el-table-column prop="field" label="字段" min-width="120" /><el-table-column prop="data_type" label="类型" width="110" /><el-table-column prop="table_name" label="模型表" min-width="120" /><el-table-column prop="model_name" label="模型" min-width="120" />
          </el-table>
          <div style="margin:12px 0 6px"><span class="muted">合规扫描（字段类型 vs 数据元类型基名）</span><el-button link size="small" type="primary" @click="loadCompliance" style="margin-left:8px">开始扫描</el-button></div>
          <div v-if="compliance" class="muted">共 {{ compliance.total }} 个已落标字段，类型一致 {{ compliance.pass }}，不一致 {{ compliance.fail }}</div>
          <el-table :data="compliance?.failList || []" size="small" border max-height="200" style="margin-top:6px">
            <el-table-column prop="field" label="字段" min-width="120" /><el-table-column prop="field_type" label="字段类型" width="110" /><el-table-column prop="element" label="数据元" min-width="120" /><el-table-column prop="element_type" label="数据元类型" width="110" /><el-table-column prop="table_name" label="模型表" min-width="120" />
          </el-table>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 数据元编辑 -->
    <el-dialog v-model="elDlg" :title="deForm.id ? '编辑数据元' : '新增数据元'" width="580px">
      <el-form :model="deForm" label-width="80px">
        <el-form-item label="编码">
          <el-input v-model="deForm.code" placeholder="如 DE_SEX" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="deForm.name" />
        </el-form-item>
        <el-form-item label="英文名">
          <el-input v-model="deForm.en_name" placeholder="如 sex_code" />
        </el-form-item>
        <el-form-item label="业务分类">
          <el-select v-model="deForm.category" clearable placeholder="选择分类" style="width:100%">
            <el-option v-for="c in CATEGORIES" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="数据类型">
          <el-input v-model="deForm.data_type" placeholder="VARCHAR / INT / DECIMAL" style="width:220px" />
        </el-form-item>
        <el-form-item label="长度精度">
          <el-input-number v-model="deForm.length" :min="0" controls-position="right" style="width:110px" />
          <el-input-number v-model="deForm.precision_" :min="0" controls-position="right" style="width:100px" />
          <el-input-number v-model="deForm.scale_" :min="0" controls-position="right" style="width:100px" />
        </el-form-item>
        <el-form-item label="取值域">
          <el-select v-model="deForm.code_set_id" clearable placeholder="引用代码集（自动生成取值域）" style="width:100%">
            <el-option v-for="cs in codeSets" :key="cs.id" :label="cs.code + ' - ' + cs.name" :value="cs.id" />
          </el-select>
          <el-input v-model="deForm.value_domain" type="textarea" :rows="2" style="margin-top:6px" :disabled="!!deForm.code_set_id" :placeholder="deForm.code_set_id ? '已关联代码集，保存后自动生成' : '或手填取值域说明'" />
        </el-form-item>
        <el-form-item label="计量单位">
          <el-input v-model="deForm.unit" style="width:140px" />
        </el-form-item>
        <el-form-item label="数据格式">
          <el-input v-model="deForm.data_format" placeholder="如 yyyy-MM-dd" />
        </el-form-item>
        <el-form-item label="安全分级">
          <el-select v-model="deForm.security_level" clearable style="width:100%">
            <el-option v-for="s in SECURITY_LEVELS" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="负责人">
          <el-input v-model="deForm.owner" />
        </el-form-item>
        <el-form-item label="定义">
          <el-input v-model="deForm.definition" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="deForm.status">
            <el-radio value="NORMAL">正常</el-radio>
            <el-radio value="DISABLED">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="elDlg = false">取消</el-button>
        <el-button type="primary" @click="saveEl">保存</el-button>
      </template>
    </el-dialog>

    <!-- 数据元引用明细 -->
    <el-dialog v-model="elRefsDlg" :title="'引用明细 - ' + (curEl?.name || '')" width="620px">
      <div class="muted" style="margin-bottom:8px">共被 {{ elRefs.length }} 处模型字段引用</div>
      <el-table :data="elRefs" size="small" border max-height="360">
        <el-table-column prop="model_name" label="模型" width="140" />
        <el-table-column prop="table_name" label="表" width="160" />
        <el-table-column prop="field_name" label="字段" min-width="120" />
        <el-table-column prop="data_type" label="类型" width="110" />
      </el-table>
    </el-dialog>

    <!-- 代码集编辑 -->
    <el-dialog v-model="csDlg" :title="csForm.id ? '编辑代码集' : '新增代码集'" width="460px">
      <el-form :model="csForm" label-width="60px">
        <el-form-item label="编码">
          <el-input v-model="csForm.code" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="csForm.name" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="csForm.category" clearable style="width:100%">
            <el-option v-for="c in CODE_CATEGORIES" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="csForm.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="csDlg = false">取消</el-button>
        <el-button type="primary" @click="saveCs">保存</el-button>
      </template>
    </el-dialog>

    <!-- 代码集被引用 -->
    <el-dialog v-model="csRefsDlg" :title="'被引用 - ' + (curCs?.name || '')" width="560px">
      <div class="muted" style="margin-bottom:8px">共被 {{ csRefs.length }} 个数据元引用</div>
      <el-table :data="csRefs" size="small" border max-height="360">
        <el-table-column prop="code" label="编码" width="130" />
        <el-table-column prop="name" label="名称" min-width="120" />
        <el-table-column prop="en_name" label="英文名" width="120" />
        <el-table-column prop="category" label="分类" width="80" />
      </el-table>
    </el-dialog>

    <!-- 代码项 -->
    <el-dialog v-model="itemDlg" :title="'代码项 - ' + (curSet?.name || '')" width="640px">
      <div style="margin-bottom:8px;display:flex;gap:6px;flex-wrap:wrap">
        <el-input v-model="newItem.code" size="small" placeholder="编码" style="width:110px" />
        <el-input v-model="newItem.name" size="small" placeholder="名称" style="width:140px" />
        <el-input-number v-model="newItem.sort" :min="0" size="small" style="width:100px" />
        <el-input v-model="newItem.remark" size="small" placeholder="备注" style="width:140px" />
        <el-button size="small" type="primary" @click="addItem">添加</el-button>
      </div>
      <el-table :data="codeItems" size="small" border max-height="320">
        <el-table-column prop="code" label="编码" width="120" />
        <el-table-column prop="name" label="名称" min-width="120" />
        <el-table-column prop="sort" label="排序" width="70" />
        <el-table-column label="启用" width="70">
          <template #default="{ row }"><el-switch v-model="row.is_enabled" @change="toggleItem(row)" /></template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip />
        <el-table-column label="操作" width="120">
          <template #default="{ row }"><el-button link size="small" type="primary" @click="editItem(row)">改备注</el-button><el-button link size="small" type="danger" @click="delItem(row)">删除</el-button></template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { VChart } from '@/echarts'
import { api, errMsg } from '@/api'

const CATEGORIES = ['人员', '产品', '事件', '财务', '组织', '地理位置', '其他']
const CODE_CATEGORIES = ['枚举', '状态码', '字典', '行业代码', '其他']
const SECURITY_LEVELS = [
  { label: '公开', value: 'PUBLIC' },
  { label: '内部', value: 'INTERNAL' },
  { label: '敏感', value: 'SENSITIVE' },
]
const DEFAULT_EL = {
  id: null as number | null, code: '', name: '', en_name: '', category: '',
  data_type: 'VARCHAR', length: 64, precision_: 0, scale_: 0,
  unit: '', data_format: '', security_level: '', owner: '',
  code_set_id: 0, value_domain: '', definition: '', status: 'NORMAL',
}

const tab = ref('element')
const elements = ref<any[]>([])
const loading = ref(false)
const codeSets = ref<any[]>([])
const loadingCs = ref(false)
const f = reactive<any>({ category: '', status: '', keyword: '' })
const cf = reactive<any>({ category: '', keyword: '' })

const elDlg = ref(false)
const deForm = reactive<any>({ ...DEFAULT_EL })
const csDlg = ref(false)
const csForm = reactive<any>({ id: null, code: '', name: '', category: '', description: '' })

const elRefsDlg = ref(false)
const curEl = ref<any>(null)
const elRefs = ref<any[]>([])
const csRefsDlg = ref(false)
const curCs = ref<any>(null)
const csRefs = ref<any[]>([])
const itemDlg = ref(false)
const curSet = ref<any>(null)
const codeItems = ref<any[]>([])
const newItem = reactive<any>({ code: '', name: '', sort: 1, is_enabled: true, remark: '' })

async function loadEl() {
  loading.value = true
  try { elements.value = await api.govElements(f.category, f.status, f.keyword) }
  catch (e:any) { ElMessage.error(errMsg(e)) }
  finally { loading.value = false }
}
async function loadCs() {
  loadingCs.value = true
  try { codeSets.value = await api.govCodeSets(cf.category, cf.keyword) }
  catch (e:any) { ElMessage.error(errMsg(e)) }
  finally { loadingCs.value = false }
}

function openEl(row?: any) {
  Object.assign(deForm, { ...DEFAULT_EL }, row || {})
  if (!deForm.code_set_id) deForm.code_set_id = 0
  elDlg.value = true
}
async function saveEl() {
  if (!deForm.code || !deForm.name) return ElMessage.warning('请填编码与名称')
  try { await api.govSaveElement({ ...deForm }); ElMessage.success('保存成功'); elDlg.value = false; await loadEl() }
  catch (e:any) { ElMessage.error(errMsg(e)) }
}
async function delEl(row: any) {
  await ElMessageBox.confirm('删除数据元 ' + row.code + '？', '提示', { type: 'warning' })
  try { await api.govDeleteElement(row.id); ElMessage.success('已删除'); await loadEl() }
  catch (e:any) { ElMessage.error(errMsg(e)) }
}
async function openElRefs(row: any) {
  curEl.value = row; elRefsDlg.value = true
  try { const r:any = await api.govElementRefs(row.id); elRefs.value = r.refs || [] }
  catch { elRefs.value = [] }
}

function openCs(row?: any) {
  Object.assign(csForm, { id: null, code: '', name: '', category: '', description: '' }, row || {})
  csDlg.value = true
}
async function saveCs() {
  try { await api.govSaveCodeSet({ ...csForm }); ElMessage.success('保存成功'); csDlg.value = false; await loadCs() }
  catch (e:any) { ElMessage.error(errMsg(e)) }
}
async function delCs(row: any) {
  await ElMessageBox.confirm('删除代码集 ' + row.code + '？引用它的数据元将解除关联', '提示', { type: 'warning' })
  try { await api.govDeleteCodeSet(row.id); ElMessage.success('已删除'); await loadCs() }
  catch (e:any) { ElMessage.error(errMsg(e)) }
}
async function openCsRefs(row: any) {
  curCs.value = row; csRefsDlg.value = true
  try { const r:any = await api.govCodeSetRefs(row.id); csRefs.value = r.refs || [] }
  catch { csRefs.value = [] }
}

async function openItems(row: any) {
  curSet.value = row; itemDlg.value = true
  Object.assign(newItem, { code: '', name: '', sort: 1, is_enabled: true, remark: '' })
  try { codeItems.value = await api.govCodeItems(row.id) }
  catch { codeItems.value = [] }
}
async function addItem() {
  if (!newItem.code || !newItem.name) return ElMessage.warning('填编码与名称')
  try {
    await api.govSaveCodeItem({ set_id: curSet.value.id, ...newItem })
    Object.assign(newItem, { code: '', name: '', sort: 1, is_enabled: true, remark: '' })
    codeItems.value = await api.govCodeItems(curSet.value.id)
    await loadEl()
  } catch (e:any) { ElMessage.error(errMsg(e)) }
}
async function toggleItem(row: any) {
  try { await api.govSaveCodeItem({ id: row.id, set_id: row.set_id, code: row.code, name: row.name, sort: row.sort, is_enabled: row.is_enabled, remark: row.remark }); await loadEl() }
  catch (e:any) { ElMessage.error(errMsg(e)); codeItems.value = await api.govCodeItems(curSet.value.id) }
}
async function editItem(row: any) {
  try {
    const res: any = await ElMessageBox.prompt('备注', '编辑代码项', { inputValue: row.remark || '' })
    await api.govSaveCodeItem({ id: row.id, set_id: row.set_id, code: row.code, name: row.name, sort: row.sort, is_enabled: row.is_enabled, remark: res.value })
    codeItems.value = await api.govCodeItems(curSet.value.id)
    await loadEl()
  } catch (e:any) { if (e !== 'cancel') ElMessage.error(errMsg(e)) }
}
async function delItem(row: any) {
  try { await api.govDeleteCodeItem(row.id); codeItems.value = await api.govCodeItems(curSet.value.id); await loadEl() }
  catch (e:any) { ElMessage.error(errMsg(e)) }
}

// ===== 落标概况 =====
const theme = 'tech-dark'
const landing = ref<any>(null); const landingLoading = ref(false); const compliance = ref<any>(null)
async function loadLanding() { landingLoading.value = true; try { landing.value = await api.govStdLandingStats() } catch (e: any) { ElMessage.error(errMsg(e)) } finally { landingLoading.value = false } }
async function loadCompliance() { try { compliance.value = await api.govStdComplianceScan() } catch (e: any) { ElMessage.error(errMsg(e)) } }
const rateOption = computed(() => {
  const v = landing.value?.rate || 0
  return { title: { text: v + '%', left: 'center', top: '34%', textStyle: { fontSize: 20, color: '#e6ecff' } },
    series: [{ type: 'pie', radius: ['60%', '78%'], silent: true, label: { show: false }, data: [{ value: v, itemStyle: { color: '#2ee6a6' } }, { value: 100 - v, itemStyle: { color: '#26314f' } }] }] }
})
watch(tab, (t) => { if (t === 'landing' && !landing.value) loadLanding() })

onMounted(() => { loadEl(); loadCs() })
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.muted { color: var(--tech-text-muted); font-size: 12px; }
.filter-bar { display: flex; gap: 8px; margin-bottom: 10px; align-items: center; flex-wrap: wrap; }
</style>
