<template>
  <div class="dl-card">
    <div class="card-title"><span>数据模型</span><span class="role-tag">系统管理员</span></div>
    <el-button type="primary" size="small" @click="open()" style="margin-bottom:10px"><el-icon><Plus /></el-icon> 新增模型</el-button>
    <el-table :data="models" size="small" stripe border v-loading="loading">
      <el-table-column prop="name" label="模型名称" min-width="140" />
      <el-table-column prop="domain" label="主题域" width="120" />
      <el-table-column prop="model_type" label="类型" width="100"><template #default="{ row }"><el-tag size="small">{{ row.model_type }}</el-tag></template></el-table-column>
      <el-table-column prop="description" label="说明" min-width="180" show-overflow-tooltip />
      <el-table-column label="操作" width="290"><template #default="{ row }"><el-button link size="small" type="primary" @click="openTables(row)">表</el-button><el-button link size="small" type="success" @click="openEr(row)">ER图</el-button><el-button link size="small" type="primary" @click="open(row)">编辑</el-button><el-button link size="small" type="danger" @click="del(row)">删除</el-button></template></el-table-column>
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

    <!-- 模型表抽屉：表 / 表间关系 / 版本 -->
    <el-drawer v-model="tableDlg" :title="`模型表 - ${cur?.name || ''}`" size="920px">
      <el-tabs v-model="mTab">
        <el-tab-pane label="表" name="table">
          <div style="margin-bottom:8px">
            <el-button size="small" type="primary" @click="newTableDlg = true">新增表</el-button>
            <el-button size="small" @click="openReverse">逆向导入（物理表·自动匹配数据元）</el-button>
          </div>
          <el-table :data="mTables" size="small" border max-height="420">
            <el-table-column prop="name" label="表名" min-width="140" />
            <el-table-column prop="layer" label="层级" width="70" />
            <el-table-column prop="description" label="说明" min-width="120" show-overflow-tooltip />
            <el-table-column label="操作" width="290"><template #default="{ row }"><el-button link size="small" type="primary" @click="openFields(row)">字段</el-button><el-button link size="small" type="success" @click="showDdl(row)">生成DDL</el-button><el-button link size="small" type="warning" @click="openCreate(row)">建物理表</el-button><el-button link size="small" type="danger" @click="delTable(row)">删除</el-button></template></el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="表间关系" name="relation">
          <div style="margin-bottom:8px"><el-button size="small" type="primary" @click="openRelation()">新增关系</el-button></div>
          <el-table :data="relations" size="small" border max-height="420">
            <el-table-column label="表 A" min-width="140"><template #default="{ row }"><code>{{ row.table_a_name }}</code> · {{ row.field_a }}</template></el-table-column>
            <el-table-column prop="relation_type" label="关系" width="70" align="center"><template #default="{ row }"><el-tag size="small" type="info">{{ row.relation_type }}</el-tag></template></el-table-column>
            <el-table-column label="表 B" min-width="140"><template #default="{ row }"><code>{{ row.table_b_name }}</code> · {{ row.field_b }}</template></el-table-column>
            <el-table-column prop="create_time" label="创建时间" width="160" />
            <el-table-column label="操作" width="70"><template #default="{ row }"><el-button link size="small" type="danger" @click="delRelation(row)">删除</el-button></template></el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="版本快照" name="version">
          <div style="display:flex; gap:8px; align-items:center; margin-bottom:8px">
            <el-button size="small" type="primary" :loading="savingVer" @click="saveVersion()">保存当前版本快照</el-button>
            <span class="muted">结构变更前存档，可任意两版对比（表/字段增删与类型变化）</span>
          </div>
          <el-table :data="versions" size="small" border max-height="200">
            <el-table-column prop="version_n" label="版本" width="70"><template #default="{ row }">v{{ row.version_n }}</template></el-table-column>
            <el-table-column prop="change_detail" label="说明" min-width="160" />
            <el-table-column prop="create_time" label="时间" width="160" />
            <el-table-column label="对比" width="200">
              <template #default="{ row }">
                <el-checkbox-group v-model="verPick" size="small" :max="2" @change="() => {}">
                  <el-checkbox :value="row.version_n">选</el-checkbox>
                </el-checkbox-group>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="verPick.length === 2" style="margin-top:8px">
            <el-button size="small" type="warning" @click="compareVersions">对比 v{{ verPick[0] }} ↔ v{{ verPick[1] }}</el-button>
          </div>
          <template v-if="verDiff">
            <div class="muted" style="margin:10px 0 4px">v{{ verDiff.v1 }} → v{{ verDiff.v2 }} 差异（{{ verDiff.diff.length }} 项）</div>
            <el-table :data="verDiff.diff" size="small" border max-height="260">
              <el-table-column label="类型" width="80"><template #default="{ row }"><el-tag size="small" :type="row.type === 'ADDED' ? 'success' : row.type === 'REMOVED' ? 'danger' : 'warning'">{{ diffText(row.type) }}</el-tag></template></el-table-column>
              <el-table-column prop="target" label="对象" min-width="160" />
              <el-table-column prop="old" label="旧" min-width="130"><template #default="{ row }"><span class="muted">{{ row.old || '—' }}</span></template></el-table-column>
              <el-table-column prop="new" label="新" min-width="130" />
            </el-table>
          </template>
        </el-tab-pane>
      </el-tabs>
    </el-drawer>

    <!-- ER 关系图（全屏）：滚轮缩放 · 空白拖拽平移 · 拖表头移位 · 悬停高亮邻接 · 双击表头维护字段 · 导出 -->
    <el-dialog v-model="erDlg" :title="`ER 关系图 - ${cur?.name || ''}`" fullscreen @opened="erFit">
      <div v-if="erData && erData.tables.length" class="er-wrap">
        <div class="er-toolbar">
          <span class="muted">实体 {{ erData.tables.length }} · 关系 {{ erData.relations.length }} · 金色=主键 蓝色=外键 · 双击表头维护字段</span>
          <span class="er-tools">
            <el-button size="small" @click="erZoomBy(1 / 1.2)">缩小</el-button>
            <el-button size="small" @click="erZoomBy(1.2)">放大</el-button>
            <el-button size="small" @click="erFit">适应画布</el-button>
            <el-button size="small" @click="erResetLayout">重置布局</el-button>
            <el-button size="small" type="primary" @click="erExport('svg')">导出SVG</el-button>
            <el-button size="small" type="success" @click="erExport('png')">导出PNG</el-button>
            <span class="muted er-zoom">{{ Math.round(erView.k * 100) }}%</span>
          </span>
        </div>
        <div class="er-scroll" ref="erScrollEl" @wheel.prevent="erWheel" @mousedown="erMouseDown"
          @mousemove="erMouseMove" @mouseup="erMouseUp" @mouseleave="erMouseUp">
          <svg class="er-svg" width="100%" height="100%">
            <defs>
              <marker id="er-arrow" markerWidth="10" markerHeight="8" refX="9" refY="4" orient="auto"><path d="M0,0 L10,4 L0,8 z" fill="#2f6bff" /></marker>
            </defs>
            <g :transform="`translate(${erView.tx},${erView.ty}) scale(${erView.k})`">
              <!-- 关系边：悬停实体时高亮其邻接边，其余淡化 -->
              <g v-for="rel in erEdges" :key="'e' + rel.id" :opacity="erHover == null || erEdgeHot(rel) ? 1 : 0.15">
                <path :d="rel.path" :stroke="erEdgeHot(rel) ? '#4f9dff' : '#2f6bff'" :stroke-width="erEdgeHot(rel) ? 2.5 : 1.5"
                  fill="none" marker-end="url(#er-arrow)" />
                <text :x="rel.lx" :y="rel.ly" fill="#2f6bff" font-size="10" text-anchor="middle">{{ rel.relation_type }}</text>
              </g>
              <!-- 实体框：样式内联（导出 SVG/PNG 不丢颜色） -->
              <g v-for="t in erBoxes" :key="t.id" :opacity="erBoxOpacity(t)"
                @mouseenter="erHover = t.id" @mouseleave="erHover = null" @mousedown.stop>
                <rect :x="t.x" :y="t.y" :width="t.w" :height="t.h" rx="6" fill="#131f38" stroke="#26314f" stroke-width="1" />
                <rect :x="t.x" :y="t.y" :width="t.w" :height="t.headerH" rx="6" fill="rgba(47,107,255,.18)"
                  class="er-head" @mousedown.stop="erDragStart($event, t)" @dblclick.stop="openFields(t)" />
                <text :x="t.x + 10" :y="t.y + 17" fill="#e6ecff" font-size="13" font-weight="600" class="er-head"
                  @mousedown.stop="erDragStart($event, t)" @dblclick.stop="openFields(t)">{{ t.name }}</text>
                <text :x="t.x + t.w - 10" :y="t.y + 17" fill="#7f93bf" font-size="10" text-anchor="end">{{ t.layer }}</text>
                <text v-for="(f, i) in t.fields" :key="f.id" :x="t.x + 10" :y="t.y + t.headerH + 14 + i * t.rowH"
                  :fill="f.is_pk ? '#f5c542' : f.fk ? '#4f9dff' : '#9fb0d0'" :font-weight="f.is_pk ? 600 : 400"
                  font-size="11" class="er-field-t" @click="f.element_name && openFieldDetail(f)">
                  {{ (f.is_pk ? 'PK ' : '') + f.name }}{{ f.element_name ? ' · ' + f.element_name : '' }}
                </text>
              </g>
            </g>
          </svg>
        </div>
      </div>
      <el-empty v-else description="模型下还没有表，先到「表」页签建表或逆向导入" />
    </el-dialog>

    <el-dialog v-model="newTableDlg" title="新增模型表" width="440px">
      <el-form :model="tForm" label-width="60px" size="small">
        <el-form-item label="表名"><el-input v-model="tForm.name" /></el-form-item>
        <el-form-item label="层级"><el-select v-model="tForm.layer" style="width:100%"><el-option v-for="l in layerCodes" :key="l" :label="l" :value="l" /></el-select></el-form-item>
        <el-form-item label="说明"><el-input v-model="tForm.description" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="newTableDlg = false">取消</el-button><el-button type="primary" @click="addTable">保存</el-button></template>
    </el-dialog>

    <!-- 表间关系编辑 -->
    <el-dialog v-model="relDlg" title="新增表间关系" width="560px">
      <el-form :model="relForm" label-width="70px" size="small">
        <el-form-item label="表 A">
          <el-select v-model="relForm.table_a" style="width:100%" @change="relForm.field_a = ''"><el-option v-for="t in mTables" :key="t.id" :label="t.name" :value="t.id" /></el-select>
        </el-form-item>
        <el-form-item label="字段 A">
          <el-select v-model="relForm.field_a" style="width:100%" filterable><el-option v-for="f in fieldsOf(relForm.table_a)" :key="f.id" :label="f.name" :value="f.name" /></el-select>
        </el-form-item>
        <el-form-item label="关系">
          <el-radio-group v-model="relForm.relation_type"><el-radio value="1:1">1:1</el-radio><el-radio value="1:N">1:N</el-radio><el-radio value="N:N">N:N</el-radio></el-radio-group>
        </el-form-item>
        <el-form-item label="表 B">
          <el-select v-model="relForm.table_b" style="width:100%" @change="relForm.field_b = ''"><el-option v-for="t in mTables" :key="t.id" :label="t.name" :value="t.id" /></el-select>
        </el-form-item>
        <el-form-item label="字段 B">
          <el-select v-model="relForm.field_b" style="width:100%" filterable><el-option v-for="f in fieldsOf(relForm.table_b)" :key="f.id" :label="f.name" :value="f.name" /></el-select>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="relDlg = false">取消</el-button><el-button type="primary" @click="saveRelation">保存</el-button></template>
    </el-dialog>

    <!-- 字段详情（ER 图点击带数据元的字段） -->
    <el-dialog v-model="fdDlg" title="字段详情" width="420px">
      <el-descriptions :column="1" border size="small" v-if="fdRow">
        <el-descriptions-item label="字段">{{ fdRow.name }}</el-descriptions-item>
        <el-descriptions-item label="类型">{{ fdRow.data_type }}</el-descriptions-item>
        <el-descriptions-item label="主键">{{ fdRow.is_pk ? '是' : '否' }}</el-descriptions-item>
        <el-descriptions-item label="数据元">{{ fdRow.element_name || '-' }}</el-descriptions-item>
        <el-descriptions-item label="备注">{{ fdRow.comment || '-' }}</el-descriptions-item>
      </el-descriptions>
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
          <el-select v-model="reverseLayer" style="width:100%"><el-option v-for="l in layerCodes" :key="l" :label="l" :value="l" /></el-select>
        </el-form-item>
        <div class="muted" style="margin:-6px 0 8px 70px">导入自动识别主键并按列名/注释匹配数据元（相似度≥80 自动绑定）</div>
      </el-form>
      <template #footer><el-button @click="reverseDlg = false">取消</el-button><el-button type="primary" :loading="reversing" @click="doReverse">导入</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'

const models = ref<any[]>([]); const loading = ref(false)
const dlg = ref(false); const form = reactive<any>({ id: null, name: '', domain: '', model_type: '逻辑模型', description: '' })
const tableDlg = ref(false); const mTab = ref('table'); const cur = ref<any>(null); const mTables = ref<any[]>([])
const newTableDlg = ref(false); const tForm = reactive<any>({ name: '', layer: 'dwd', description: '' })
const fieldDlg = ref(false); const curTable = ref<any>(null); const mFields = ref<any[]>([])
const addFieldDlg = ref(false); const fForm = reactive<any>({ id: null, name: '', data_type: '', element_id: 0, is_pk: false, nullable: true, comment: '' })
const elements = ref<any[]>([])
const layerCodes = ref<string[]>(['ods', 'dwd', 'dws', 'ads', 'dim'])

async function load() { loading.value = true; try { models.value = await api.govModels() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
async function loadElements() { try { elements.value = await api.govElements() } catch { elements.value = [] } }
async function loadLayers() { try { const ls = await api.govLayers(); if (ls.length) layerCodes.value = ls.map((l: any) => l.code) } catch { /* 本地兜底 */ } }
function open(row?: any) { Object.assign(form, { id: null, name: '', domain: '', model_type: '逻辑模型', description: '' }, row || {}); dlg.value = true }
async function save() { try { await api.govSaveModel({ ...form }); ElMessage.success('保存成功'); dlg.value = false; await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function del(row: any) { await ElMessageBox.confirm(`删除模型 ${row.name}？`, '提示', { type: 'warning' }); try { await api.govDeleteModel(row.id); ElMessage.success('已删除'); await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function openTables(row: any) {
  cur.value = row; mTab.value = 'table'; tableDlg.value = true
  try { mTables.value = await api.govModelTables(row.id) } catch { mTables.value = [] }
  await loadRelations(); await loadVersions()
}
async function addTable() { try { await api.govSaveModelTable({ model_id: cur.value.id, ...tForm }); newTableDlg.value = false; Object.assign(tForm, { name: '', layer: 'dwd', description: '' }); mTables.value = await api.govModelTables(cur.value.id) } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function delTable(row: any) { try { await api.govDeleteModelTable(row.id); mTables.value = await api.govModelTables(cur.value.id); await loadRelations() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function openFields(row: any) { curTable.value = row; fieldDlg.value = true; try { mFields.value = await api.govModelFields(row.id) } catch { mFields.value = [] } }

// ===== P1：表间关系 + ER 图 + 版本 =====
const relations = ref<any[]>([])
const relDlg = ref(false); const relForm = reactive<any>({ table_a: null, field_a: '', table_b: null, field_b: '', relation_type: '1:N' })
const erDlg = ref(false); const erData = ref<any>(null)
const versions = ref<any[]>([]); const verPick = ref<number[]>([]); const verDiff = ref<any>(null); const savingVer = ref(false)
const fdDlg = ref(false); const fdRow = ref<any>(null)
const fieldsCache = ref<Record<number, any[]>>({})

async function loadRelations() { try { relations.value = await api.govModelRelations(cur.value.id) } catch { relations.value = [] } }
function fieldsOf(tableId: any): any[] { return fieldsCache.value[tableId] || [] }
async function openRelation() {
  fieldsCache.value = {}
  for (const t of mTables.value) { try { fieldsCache.value[t.id] = await api.govModelFields(t.id) } catch { fieldsCache.value[t.id] = [] } }
  Object.assign(relForm, { table_a: null, field_a: '', table_b: null, field_b: '', relation_type: '1:N' })
  relDlg.value = true
}
async function saveRelation() {
  if (!relForm.table_a || !relForm.table_b || !relForm.field_a || !relForm.field_b) return ElMessage.warning('选表与字段')
  try { await api.govSaveModelRelation({ model_id: cur.value.id, ...relForm }); ElMessage.success('已保存'); relDlg.value = false; await loadRelations() }
  catch (e:any) { ElMessage.error(errMsg(e)) }
}
async function delRelation(row: any) { try { await api.govDeleteModelRelation(row.id); await loadRelations() } catch (e:any) { ElMessage.error(errMsg(e)) } }

async function openEr(row: any) {
  cur.value = row; erDlg.value = true
  erView.k = 1; erView.tx = 20; erView.ty = 20; erPos.value = {}; erHover.value = null
  try {
    erData.value = await api.govModelEr(row.id)
    nextTick(() => erFit())
  } catch (e:any) { erData.value = null; ElMessage.error(errMsg(e)) }
}

// ER 布局：实体网格排布（每行 4 个，框高按字段数生长）；erPos 覆盖坐标支持拖拽改位
const ER_W = 240, ER_HEADER = 26, ER_ROW = 18, ER_GAP_X = 60, ER_GAP_Y = 40, ER_PER_ROW = 4
const erBoxes = computed(() => {
  const tables = erData.value?.tables || []
  const rels = erData.value?.relations || []
  const fkFields = new Set<string>()
  for (const r of rels) {
    const ta = tables.find((t: any) => t.id === r.table_a), tb = tables.find((t: any) => t.id === r.table_b)
    if (ta) fkFields.add(ta.name + '.' + r.field_a)
    if (tb) fkFields.add(tb.name + '.' + r.field_b)
  }
  const out: any[] = []
  const colH: number[] = new Array(ER_PER_ROW).fill(ER_GAP_Y)
  tables.forEach((t: any) => {
    const fields = (t.fields || []).map((f: any) => ({ ...f, fk: fkFields.has(t.name + '.' + f.name) }))
    const h = ER_HEADER + Math.max(fields.length, 1) * ER_ROW + 8
    let col = colH.reduce((mi, h2, i) => (h2 < colH[mi] ? i : mi), 0)
    const gx = 20 + col * (ER_W + ER_GAP_X), gy = colH[col]
    colH[col] = gy + h + ER_GAP_Y
    const p = erPos.value[t.id]
    out.push({ id: t.id, name: t.name, layer: t.layer, fields, x: p?.x ?? gx, y: p?.y ?? gy, w: ER_W, h, headerH: ER_HEADER, rowH: ER_ROW })
  })
  return out
})
const erLayout = computed(() => ({
  w: Math.max(40 + ER_PER_ROW * (ER_W + ER_GAP_X), ...erBoxes.value.map((b: any) => b.x + b.w + 40)),
  h: Math.max(400, ...erBoxes.value.map((b: any) => b.y + b.h + 40)),
}))
const erEdges = computed(() => {
  const boxes = erBoxes.value
  const rels = erData.value?.relations || []
  const byId = Object.fromEntries(boxes.map((b: any) => [b.id, b]))
  const edges: any[] = []
  for (const r of rels) {
    const a = byId[r.table_a], b = byId[r.table_b]
    if (!a || !b) continue
    const ai = Math.max(0, (a.fields || []).findIndex((f: any) => f.name === r.field_a))
    const bi = Math.max(0, (b.fields || []).findIndex((f: any) => f.name === r.field_b))
    const ay = a.y + a.headerH + 8 + ai * a.rowH
    const by = b.y + b.headerH + 8 + bi * b.rowH
    // 从两实体边缘连线（水平方向取侧边，纵向取字段行），直角折线
    const aRight = a.x + a.w / 2 > b.x + b.w / 2
    const sx = aRight ? a.x : a.x + a.w, sy = ay
    const tx = aRight ? b.x + b.w : b.x, ty = by
    const midX = (sx + tx) / 2
    edges.push({ id: r.id, table_a: r.table_a, table_b: r.table_b, relation_type: r.relation_type,
      lx: midX, ly: (sy + ty) / 2 - 6, path: `M ${sx} ${sy} L ${midX} ${sy} L ${midX} ${ty} L ${tx} ${ty}` })
  }
  return edges
})

// ===== ER 交互：滚轮缩放 / 空白拖拽平移 / 拖表头移位 / 悬停高亮 / 导出 =====
const erScrollEl = ref<HTMLElement | null>(null)
const erView = reactive({ k: 1, tx: 20, ty: 20 })
const erPos = ref<Record<number, { x: number, y: number }>>({})
const erHover = ref<number | null>(null)
let erDragState: any = null

/** 以 (mx,my) 为锚点缩放 factor 倍（鼠标下的内容点不动）。 */
function erZoomAt(mx: number, my: number, factor: number) {
  const oldK = erView.k
  const k = Math.min(3, Math.max(0.15, oldK * factor))
  erView.tx = mx - (k / oldK) * (mx - erView.tx)
  erView.ty = my - (k / oldK) * (my - erView.ty)
  erView.k = k
}
function erWheel(e: WheelEvent) {
  const el = erScrollEl.value; if (!el) return
  const rect = el.getBoundingClientRect()
  erZoomAt(e.clientX - rect.left, e.clientY - rect.top, e.deltaY < 0 ? 1.12 : 1 / 1.12)
}
function erZoomBy(f: number) {
  erZoomAt((erScrollEl.value?.clientWidth || 800) / 2, (erScrollEl.value?.clientHeight || 600) / 2, f)
}
/** 适应画布：缩放到全部实体可见并居中。 */
function erFit() {
  const el = erScrollEl.value; if (!el) return
  const { w, h } = erLayout.value
  erView.k = Math.min(el.clientWidth / w, el.clientHeight / h, 1.2) || 1
  erView.tx = (el.clientWidth - w * erView.k) / 2
  erView.ty = (el.clientHeight - h * erView.k) / 2
}
function erResetLayout() { erPos.value = {}; nextTick(() => erFit()) }
function erMouseDown(e: MouseEvent) {
  erDragState = { type: 'pan', sx: e.clientX, sy: e.clientY, tx: erView.tx, ty: erView.ty }
}
function erDragStart(e: MouseEvent, t: any) {
  erDragState = { type: 'entity', id: t.id, sx: e.clientX, sy: e.clientY, bx: t.x, by: t.y }
}
function erMouseMove(e: MouseEvent) {
  if (!erDragState) return
  const dx = e.clientX - erDragState.sx, dy = e.clientY - erDragState.sy
  if (erDragState.type === 'pan') { erView.tx = erDragState.tx + dx; erView.ty = erDragState.ty + dy }
  else erPos.value = { ...erPos.value, [erDragState.id]: { x: erDragState.bx + dx / erView.k, y: erDragState.by + dy / erView.k } }
}
function erMouseUp() { erDragState = null }
/** 实体自身 + 邻接实体保持高亮，其余淡化。 */
function erNeighbors(id: number): Set<number> {
  const s = new Set<number>([id])
  for (const r of erData.value?.relations || []) {
    if (r.table_a === id) s.add(r.table_b)
    if (r.table_b === id) s.add(r.table_a)
  }
  return s
}
function erBoxOpacity(t: any) { return erHover.value == null || erNeighbors(erHover.value).has(t.id) ? 1 : 0.25 }
function erEdgeHot(rel: any) { return erHover.value != null && (rel.table_a === erHover.value || rel.table_b === erHover.value) }
function downloadBlob(b: Blob, filename: string) {
  const a = document.createElement('a'); a.href = URL.createObjectURL(b); a.download = filename; a.click(); URL.revokeObjectURL(a.href)
}
/** 导出：克隆 SVG、还原 1:1 视角、补背景色后序列化；PNG 走 canvas 2 倍采样。 */
function erExport(fmt: 'svg' | 'png') {
  const svg = erScrollEl.value?.querySelector('svg'); if (!svg) return
  const { w, h } = erLayout.value
  const clone = svg.cloneNode(true) as SVGSVGElement
  clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg')
  clone.setAttribute('width', String(w)); clone.setAttribute('height', String(h))
  clone.querySelector(':scope > g')?.setAttribute('transform', 'translate(0,0) scale(1)')
  const bg = document.createElementNS('http://www.w3.org/2000/svg', 'rect')
  bg.setAttribute('width', String(w)); bg.setAttribute('height', String(h)); bg.setAttribute('fill', '#0d1526')
  clone.insertBefore(bg, clone.firstChild)
  const data = new XMLSerializer().serializeToString(clone)
  const name = `${cur.value?.name || 'ER'}-关系图`
  if (fmt === 'svg') { downloadBlob(new Blob([data], { type: 'image/svg+xml;charset=utf-8' }), `${name}.svg`); return }
  const img = new Image()
  img.onload = () => {
    const canvas = document.createElement('canvas')
    canvas.width = w * 2; canvas.height = h * 2
    const ctx = canvas.getContext('2d')!
    ctx.fillStyle = '#0d1526'; ctx.fillRect(0, 0, canvas.width, canvas.height)
    ctx.drawImage(img, 0, 0, canvas.width, canvas.height)
    canvas.toBlob(b2 => b2 && downloadBlob(b2, `${name}.png`), 'image/png')
  }
  img.onerror = () => ElMessage.error('PNG 导出失败，可改用导出 SVG')
  img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(data)
}
function openFieldDetail(f: any) { fdRow.value = f; fdDlg.value = true }
function diffText(t: string) { return ({ ADDED: '新增', REMOVED: '删除', CHANGED: '变更' } as any)[t] || t }

async function loadVersions() { verPick.value = []; verDiff.value = null; try { versions.value = await api.govModelVersionList(cur.value.id) } catch { versions.value = [] } }
async function saveVersion() {
  savingVer.value = true
  try { const r: any = await api.govModelVersionCreate(cur.value.id, ''); ElMessage.success(`已保存 v${r.version}`); await loadVersions() }
  catch (e:any) { ElMessage.error(errMsg(e)) } finally { savingVer.value = false }
}
async function compareVersions() {
  const [v1, v2] = [...verPick.value].sort((a, b) => a - b)
  try { verDiff.value = await api.govModelVersionCompare(cur.value.id, v1, v2) }
  catch (e:any) { ElMessage.error(errMsg(e)) }
}

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
  try {
    const r: any = await api.govModelReverse(reverseMeta.value, cur.value.id, reverseLayer.value)
    ElMessage.success(`已导入：新增 ${r.fields} 个字段，自动绑定数据元 ${r.stdMatched || 0} 个`)
    reverseDlg.value = false; mTables.value = await api.govModelTables(cur.value.id)
  }
  catch (e: any) { ElMessage.error(errMsg(e)) } finally { reversing.value = false }
}

onMounted(() => { load(); loadElements(); loadSources(); loadLayers() })
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.muted { color: var(--tech-text-muted); font-size: 12px; }
.er-wrap { display: flex; flex-direction: column; height: calc(100vh - 120px); }
.er-toolbar { margin-bottom: 8px; display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; }
.er-tools { display: inline-flex; align-items: center; gap: 6px; }
.er-zoom { min-width: 44px; text-align: right; }
.er-scroll { flex: 1; overflow: hidden; border: 1px solid var(--tech-panel-border); border-radius: 8px; background: var(--tech-bg, #0d1526);
  user-select: none; cursor: grab; }
.er-scroll:active { cursor: grabbing; }
.er-svg { display: block; }
/* 实体颜色内联在元素属性上（导出 SVG/PNG 不丢样式），这里只管光标与悬停反馈 */
.er-head { cursor: move; }
.er-field-t { cursor: pointer; }
.er-field-t:hover { fill: #2f6bff; }
</style>
