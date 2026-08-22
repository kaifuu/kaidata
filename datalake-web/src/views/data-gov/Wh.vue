<template>
  <div class="dl-card">
    <div class="card-title"><span>数据仓库 · 分层管理</span><span class="role-tag">系统管理员</span></div>
    <el-tabs v-model="tab">
      <el-tab-pane label="分层管理" name="layer">
        <el-table :data="layers" size="small" stripe border v-loading="loading">
          <el-table-column prop="code" label="层级编码" width="110" />
          <el-table-column prop="name" label="名称" min-width="120" />
          <el-table-column prop="sort" label="排序" width="70" />
          <el-table-column label="命名规范" min-width="130"><template #default="{ row }"><code v-if="row.naming_pattern">{{ row.naming_pattern }}</code><span v-else class="muted">未配置</span></template></el-table-column>
          <el-table-column label="绑定数据源" min-width="200">
            <template #default="{ row }">
              <el-tag v-for="d in (bindMap[row.code]||[])" :key="d.id" size="small" closable @close="unbind(d.id, row.code)" style="margin-right:4px">ds{{ d.datasource_id }}</el-tag>
              <el-button link size="small" type="primary" @click="openBind(row)">+ 绑定</el-button>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="140"><template #default="{ row }"><el-button link size="small" type="primary" @click="openLayer(row)">编辑</el-button><el-button link size="small" type="danger" @click="delLayer(row)">删除</el-button></template></el-table-column>
        </el-table>
        <div class="hint">分层（ODS/DWD/DWS/ADS/DIM）绑定数据源后，数据探查/接入的"所属层级"即从此选取目标数据源；命名规范用于巡检存量表名。</div>
      </el-tab-pane>
      <el-tab-pane label="分层画像" name="stats">
        <el-row :gutter="10" v-loading="loadingStats">
          <el-col v-for="s in stats" :key="s.code" :span="6" style="margin-bottom:10px">
            <div class="stat-card">
              <div class="stat-head"><b>{{ s.code }}</b><span class="muted">{{ s.name }}</span></div>
              <div class="stat-row"><span>物理表</span><b>{{ s.tables }}</b></div>
              <div class="stat-row"><span>行数合计</span><b>{{ fmtNum(s.rows) }}</b></div>
              <div class="stat-src muted">{{ s.source === 'physical' ? '来源：information_schema 实测' : '来源：元数据登记' }}</div>
            </div>
          </el-col>
        </el-row>
      </el-tab-pane>
      <el-tab-pane label="命名巡检" name="naming">
        <div style="margin-bottom:10px"><el-button size="small" type="primary" :loading="loadingNaming" @click="runNamingCheck">立即巡检</el-button></div>
        <template v-if="naming">
          <el-alert v-if="naming.checked === 0" title="没有可巡检的表（需先在元数据采集登记表且分层配置命名规范）" type="info" :closable="false" />
          <el-alert v-else-if="naming.violate === 0" :title="`巡检通过：${naming.checked} 张表全部符合分层命名规范`" type="success" :closable="false" style="margin-bottom:10px" />
          <el-alert v-else :title="`发现 ${naming.violate}/${naming.checked} 张表命名不规范`" type="warning" :closable="false" style="margin-bottom:10px" />
          <el-table v-if="naming.violations?.length" :data="naming.violations" size="small" border max-height="420">
            <el-table-column prop="layer" label="层" width="90" />
            <el-table-column prop="table" label="表名" min-width="200"><template #default="{ row }"><code>{{ row.table }}</code></template></el-table-column>
            <el-table-column prop="pattern" label="命名规范" width="140" />
            <el-table-column prop="suggest" label="建议表名" min-width="200"><template #default="{ row }"><span class="suggest">{{ row.suggest }}</span></template></el-table-column>
          </el-table>
        </template>
      </el-tab-pane>
      <el-tab-pane label="主题域" name="subject">
        <div style="margin-bottom:10px"><el-button type="primary" size="small" @click="openSubject()"><el-icon><Plus /></el-icon> 新增主题域</el-button></div>
        <el-table :data="subjects" row-key="id" size="small" border default-expand-all>
          <el-table-column prop="code" label="编码" width="140" />
          <el-table-column prop="name" label="名称" min-width="160" />
          <el-table-column prop="sort" label="排序" width="80" />
          <el-table-column label="操作" width="150">
            <template #default="{ row }">
              <el-button link size="small" type="primary" @click="openSubject(row, null)">编辑</el-button>
              <el-button link size="small" type="primary" @click="openSubject(null, row)">加子域</el-button>
              <el-button link size="small" type="danger" @click="delSubject(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="layerDlg" :title="layerForm.code ? '编辑层级' : '新增层级'" width="440px">
      <el-form :model="layerForm" label-width="80px" size="small">
        <el-form-item label="编码"><el-input v-model="layerForm.code" :disabled="!!layerForm.code" placeholder="如 dwd" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="layerForm.name" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="layerForm.sort" :min="0" /></el-form-item>
        <el-form-item label="命名规范"><el-input v-model="layerForm.naming_pattern" placeholder="^dwd_" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="layerDlg = false">取消</el-button><el-button type="primary" @click="saveLayer">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="bindDlg" title="绑定数据源" width="420px">
      <el-select v-model="bindDs" placeholder="选择数据源" style="width:100%">
        <el-option v-for="d in dsList" :key="d.id" :label="`${d.name} (${d.type})`" :value="d.id" />
      </el-select>
      <template #footer><el-button @click="bindDlg = false">取消</el-button><el-button type="primary" @click="doBind">绑定</el-button></template>
    </el-dialog>

    <el-dialog v-model="subjectDlg" :title="subjectForm.id ? '编辑主题域' : '新增主题域'" width="440px">
      <el-form :model="subjectForm" label-width="80px" size="small">
        <el-form-item label="编码"><el-input v-model="subjectForm.code" placeholder="trade" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="subjectForm.name" placeholder="交易域" /></el-form-item>
        <el-form-item label="父节点"><el-tree-select v-model="subjectForm.parent_id" :data="subjectTreeData" node-key="id" check-strictly :render-after-expand="false" style="width:100%" placeholder="无（根节点）" clearable /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="subjectForm.sort" :min="0" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="subjectDlg = false">取消</el-button><el-button type="primary" @click="saveSubject">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'

const tab = ref('layer')
const layers = ref<any[]>([]); const loading = ref(false)
const bindMap = ref<Record<string, any[]>>({})
const dsList = ref<any[]>([])
const layerDlg = ref(false); const layerForm = reactive<any>({ code: '', name: '', sort: 1, naming_pattern: '' })
const bindDlg = ref(false); const bindLayer = ref(''); const bindDs = ref<number | null>(null)
const stats = ref<any[]>([]); const loadingStats = ref(false)
const naming = ref<any>(null); const loadingNaming = ref(false)
const subjects = ref<any[]>([])
const subjectDlg = ref(false); const subjectForm = reactive<any>({ id: null, code: '', name: '', parent_id: 0, sort: 1 })

const subjectTreeData = computed(() => subjects.value.map((s: any) => ({ ...s, value: s.id, label: s.code + ' / ' + s.name })))

function fmtNum(n: any) { const v = Number(n) || 0; return v >= 10000000 ? (v / 10000000).toFixed(1) + ' 千万' : v >= 10000 ? (v / 10000).toFixed(1) + ' 万' : String(v) }

async function load() {
  loading.value = true
  try { layers.value = await api.govLayers(); dsList.value = await api.daSources(); await loadBinds() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false }
}
async function loadBinds() { for (const l of layers.value) { try { bindMap.value[l.code] = await api.govLayerDs(l.code) } catch { bindMap.value[l.code] = [] } } }
function openLayer(row?: any) { Object.assign(layerForm, { code: '', name: '', sort: 1, naming_pattern: '' }, row || {}); layerDlg.value = true }
async function saveLayer() { try { await api.govSaveLayer({ ...layerForm }); ElMessage.success('保存成功'); layerDlg.value = false; await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function delLayer(row: any) { await ElMessageBox.confirm(`删除层级 ${row.code}？`, '提示', { type: 'warning' }); try { await api.govDeleteLayer(row.code); ElMessage.success('已删除'); await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
function openBind(row: any) { bindLayer.value = row.code; bindDs.value = null; bindDlg.value = true }
async function doBind() { if (!bindDs.value) return ElMessage.warning('选数据源'); try { await api.govBindLayerDs({ layer_code: bindLayer.value, datasource_id: bindDs.value }); ElMessage.success('已绑定'); bindDlg.value = false; await loadBinds() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function unbind(id: number, code: string) { try { await api.govUnbindLayerDs(id); bindMap.value[code] = await api.govLayerDs(code) } catch (e:any) { ElMessage.error(errMsg(e)) } }

async function loadStats() { loadingStats.value = true; try { stats.value = await api.govLayerStats() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loadingStats.value = false } }
async function runNamingCheck() { loadingNaming.value = true; try { naming.value = await api.govLayerNamingCheck() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loadingNaming.value = false } }

async function loadSubjects() { try { subjects.value = await api.govSubjects() } catch { subjects.value = [] } }
function openSubject(row?: any, parent?: any) {
  Object.assign(subjectForm, { id: null, code: '', name: '', parent_id: 0, sort: 1 })
  if (row) Object.assign(subjectForm, { id: row.id, code: row.code, name: row.name, parent_id: row.parent_id || 0, sort: row.sort })
  if (parent) subjectForm.parent_id = parent.id
  subjectDlg.value = true
}
async function saveSubject() {
  if (!subjectForm.code || !subjectForm.name) return ElMessage.warning('填编码与名称')
  try { await api.govSaveSubject({ ...subjectForm }); ElMessage.success('保存成功'); subjectDlg.value = false; await loadSubjects() } catch (e:any) { ElMessage.error(errMsg(e)) }
}
async function delSubject(row: any) {
  await ElMessageBox.confirm(`删除主题域 ${row.code}？`, '提示', { type: 'warning' })
  try { await api.govDeleteSubject(row.id); ElMessage.success('已删除'); await loadSubjects() } catch (e:any) { ElMessage.error(errMsg(e)) }
}

onMounted(() => { load(); loadStats(); runNamingCheck(); loadSubjects() })
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.hint { margin-top: 12px; color: var(--tech-text-muted); font-size: 13px; }
.muted { color: var(--tech-text-muted); font-size: 12px; }
.stat-card { border: 1px solid var(--tech-panel-border); border-radius: 6px; padding: 12px 14px; }
.stat-head { display: flex; justify-content: space-between; margin-bottom: 8px; }
.stat-row { display: flex; justify-content: space-between; font-size: 13px; line-height: 22px; }
.stat-src { margin-top: 6px; font-size: 11px; }
.suggest { color: var(--el-color-success); font-family: monospace; }
</style>
