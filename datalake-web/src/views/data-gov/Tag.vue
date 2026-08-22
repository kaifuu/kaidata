<template>
  <div class="dl-card">
    <div class="card-title"><span>数据标签</span><span class="role-tag">系统管理员</span></div>

    <!-- 概览统计 -->
    <el-row :gutter="10" class="tag-kpis">
      <el-col :span="6"><div class="stat-card"><div class="stat-head"><b>{{ tags.length }}</b><span class="muted">标签总数</span></div><div class="stat-sub muted">分类 {{ categories.length }} 组 · 已被使用 {{ usedTagCount }} 个</div></div></el-col>
      <el-col :span="6"><div class="stat-card"><div class="stat-head"><b>{{ coveredTables }}</b><span class="muted">覆盖表</span></div><div class="stat-sub muted">字段级 {{ rels.filter(r => r.target_type === 'column').length }} 处</div></div></el-col>
      <el-col :span="6"><div class="stat-card"><div class="stat-head"><b>{{ rels.length }}</b><span class="muted">打标关系</span></div><div class="stat-sub muted">规则自动 + 手动 + 血缘继承</div></div></el-col>
      <el-col :span="6"><div class="stat-card"><div class="stat-head"><b>{{ rules.length }}</b><span class="muted">打标规则</span></div><div class="stat-sub muted">正则扫描元数据自动打标</div></div></el-col>
    </el-row>

    <el-tabs v-model="tab">
      <!-- 标签定义：按分类分组卡片 -->
      <el-tab-pane name="def">
        <template #label>标签定义<span class="tab-badge">{{ tags.length }}</span></template>
        <div class="tab-head">
          <el-button type="primary" size="small" @click="open()"><el-icon><Plus /></el-icon> 新增标签</el-button>
          <span class="muted">卡片按分类分组；角标为该标签已打标对象数</span>
        </div>
        <div v-for="cat in categories" :key="cat" class="tag-group">
          <div class="group-head"><el-tag size="small" effect="dark" type="info">{{ cat }}</el-tag><span class="muted">{{ tagsByCategory[cat].length }} 个</span></div>
          <div class="tag-grid">
            <div v-for="t in tagsByCategory[cat]" :key="t.id" class="tag-card" :style="{ '--tag': t.color || '#00e0ff' }">
              <div class="tag-card-head">
                <span class="tag-dot" />
                <b>{{ t.name }}</b>
                <el-tag v-if="relCountByTag[t.id]" size="small" class="tag-usage" effect="plain">{{ relCountByTag[t.id] }}</el-tag>
              </div>
              <div class="tag-desc" :title="t.description">{{ t.description || '—' }}</div>
              <div class="tag-card-ops">
                <span class="muted mono">{{ t.color }}</span>
                <span>
                  <el-button link size="small" type="primary" @click="open(t)">编辑</el-button>
                  <el-button link size="small" type="danger" @click="del(t)">删除</el-button>
                </span>
              </div>
            </div>
          </div>
        </div>
        <el-empty v-if="!tags.length" description="还没有标签，点「新增标签」创建" :image-size="80" />
      </el-tab-pane>

      <!-- 打标关系：可筛选 -->
      <el-tab-pane name="rel">
        <template #label>打标关系<span class="tab-badge">{{ rels.length }}</span></template>
        <div class="tab-head">
          <el-button type="primary" size="small" @click="relDlg = true"><el-icon><Plus /></el-icon> 手动打标</el-button>
          <el-button type="success" size="small" :loading="inhLoading" @click="inherit" title="源表标签沿血缘边向下游表传播"><el-icon><Share /></el-icon> 血缘继承</el-button>
          <span class="rel-filter">
            <el-select v-model="relFilter.tagId" placeholder="全部标签" clearable size="small" style="width:140px">
              <el-option v-for="t in tags" :key="t.id" :label="t.name" :value="t.id" />
            </el-select>
            <el-radio-group v-model="relFilter.type" size="small">
              <el-radio-button value="">全部</el-radio-button>
              <el-radio-button value="table">表</el-radio-button>
              <el-radio-button value="column">字段</el-radio-button>
            </el-radio-group>
            <el-input v-model="relFilter.kw" placeholder="搜索 库.表.字段" clearable size="small" style="width:200px" prefix-icon="Search" />
          </span>
        </div>
        <el-table :data="filteredRels" size="small" stripe border v-loading="loadingRel" max-height="560">
          <el-table-column prop="tag_name" label="标签" width="130">
            <template #default="{ row }"><el-tag size="small" :color="row.color" effect="dark" style="border:none">{{ row.tag_name }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="target_type" label="对象" width="80">
            <template #default="{ row }"><el-tag size="small" :type="row.target_type === 'table' ? 'info' : 'warning'" effect="plain">{{ row.target_type === 'table' ? '表' : '字段' }}</el-tag></template>
          </el-table-column>
          <el-table-column label="目标" min-width="260">
            <template #default="{ row }"><span class="mono">{{ row.target_db }}.{{ row.target_table }}{{ row.target_column ? '.' + row.target_column : '' }}</span></template>
          </el-table-column>
          <el-table-column label="操作" width="80"><template #default="{ row }"><el-button link size="small" type="danger" @click="unband(row)">移除</el-button></template></el-table-column>
        </el-table>
        <div v-if="filteredRels.length !== rels.length" class="muted" style="margin-top:6px">筛选出 {{ filteredRels.length }} / {{ rels.length }} 条</div>
      </el-tab-pane>

      <!-- 打标规则 -->
      <el-tab-pane name="rule">
        <template #label>打标规则<span class="tab-badge">{{ rules.length }}</span></template>
        <div class="tab-head">
          <el-button type="primary" size="small" @click="openRule()"><el-icon><Plus /></el-icon> 新增规则</el-button>
          <el-button type="warning" size="small" :loading="applyLoading" @click="applyRules"><el-icon><MagicStick /></el-icon> 执行打标</el-button>
          <span class="muted">按正则扫描元数据（表名/列名/列注释/类型）命中即自动打标，幂等可重复执行</span>
        </div>
        <el-table :data="rules" size="small" stripe border v-loading="loadingRule">
          <el-table-column prop="tag_name" label="标签" width="130">
            <template #default="{ row }"><el-tag size="small" :color="row.color" effect="dark" style="border:none">{{ row.tag_name || row.tag_id }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="match_type" label="匹配对象" width="130">
            <template #default="{ row }"><el-tag size="small" effect="plain" :type="({ TABLE_NAME: 'info', COLUMN_NAME: '', COLUMN_COMMENT: 'warning', DATA_TYPE: 'success' } as any)[row.match_type] ?? 'info'">{{ matchText(row.match_type) }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="pattern" label="正则" min-width="180"><template #default="{ row }"><code>{{ row.pattern }}</code></template></el-table-column>
          <el-table-column prop="remark" label="说明" min-width="140" show-overflow-tooltip />
          <el-table-column prop="create_time" label="创建时间" width="160" />
          <el-table-column label="操作" width="80"><template #default="{ row }"><el-button link size="small" type="danger" @click="delRule(row)">删除</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="dlg" :title="form.id ? '编辑标签' : '新增标签'" width="440px">
      <el-form :model="form" label-width="60px" size="small">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="分类"><el-select v-model="form.category" style="width:100%"><el-option v-for="c in ['分类','级别','安全','业务']" :key="c" :label="c" :value="c" /></el-select></el-form-item>
        <el-form-item label="颜色"><el-color-picker v-model="form.color" />&nbsp;<el-input v-model="form.color" placeholder="#00e0ff" style="width:140px" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="form.description" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dlg = false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="relDlg" title="打标" width="460px">
      <el-form :model="relForm" label-width="70px" size="small">
        <el-form-item label="标签"><el-select v-model="relForm.tag_id" style="width:100%"><el-option v-for="t in tags" :key="t.id" :label="t.name" :value="t.id" /></el-select></el-form-item>
        <el-form-item label="对象"><el-radio-group v-model="relForm.target_type"><el-radio value="table">表</el-radio><el-radio value="column">字段</el-radio></el-radio-group></el-form-item>
        <el-form-item label="库"><el-input v-model="relForm.target_db" placeholder="ods" /></el-form-item>
        <el-form-item label="表"><el-input v-model="relForm.target_table" placeholder="your_table" /></el-form-item>
        <el-form-item v-if="relForm.target_type === 'column'" label="字段"><el-input v-model="relForm.target_column" placeholder="batch_no" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="relDlg = false">取消</el-button><el-button type="primary" @click="bind">打标</el-button></template>
    </el-dialog>

    <el-dialog v-model="ruleDlg" title="打标规则" width="480px">
      <el-form :model="ruleForm" label-width="70px" size="small">
        <el-form-item label="标签" required><el-select v-model="ruleForm.tag_id" style="width:100%"><el-option v-for="t in tags" :key="t.id" :label="t.name" :value="t.id" /></el-select></el-form-item>
        <el-form-item label="匹配对象">
          <el-select v-model="ruleForm.match_type" style="width:100%">
            <el-option value="TABLE_NAME" label="表名" /><el-option value="COLUMN_NAME" label="列名" />
            <el-option value="COLUMN_COMMENT" label="列注释" /><el-option value="DATA_TYPE" label="数据类型" />
          </el-select>
        </el-form-item>
        <el-form-item label="正则" required><el-input v-model="ruleForm.pattern" placeholder="^ods_|phone|身份证" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="ruleForm.remark" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="ruleDlg = false">取消</el-button><el-button type="primary" @click="saveRule">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MagicStick, Plus, Share } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'

const tab = ref('def')
const tags = ref<any[]>([]); const loading = ref(false)
const rels = ref<any[]>([]); const loadingRel = ref(false)
const rules = ref<any[]>([]); const loadingRule = ref(false)
const applyLoading = ref(false); const inhLoading = ref(false)
const dlg = ref(false); const form = reactive<any>({ id: null, name: '', category: '分类', color: '#00e0ff', description: '' })
const relDlg = ref(false); const relForm = reactive<any>({ tag_id: null, target_type: 'table', target_db: 'ods', target_table: '', target_column: '' })
const ruleDlg = ref(false); const ruleForm = reactive<any>({ tag_id: null, match_type: 'TABLE_NAME', pattern: '', remark: '' })
const relFilter = reactive<any>({ tagId: null, type: '', kw: '' })

function matchText(t: string) { return ({ TABLE_NAME: '表名', COLUMN_NAME: '列名', COLUMN_COMMENT: '列注释', DATA_TYPE: '数据类型' } as any)[t] || t }

// ===== 概览统计（前端聚合已加载数据） =====
const relCountByTag = computed(() => {
  const m: Record<string, number> = {}
  for (const r of rels.value) m[r.tag_id] = (m[r.tag_id] || 0) + 1
  return m
})
const usedTagCount = computed(() => Object.keys(relCountByTag.value).length)
const coveredTables = computed(() => new Set(rels.value.map(r => r.target_table)).size)
const categories = computed(() => {
  const set = new Set<string>()
  for (const t of tags.value) set.add(t.category || '其他')
  // 固定分类排在前面，自定义分类排后
  const fixed = ['分类', '级别', '安全', '业务'].filter(c => set.has(c))
  const rest = [...set].filter(c => !fixed.includes(c))
  return [...fixed, ...rest]
})
const tagsByCategory = computed(() => {
  const m: Record<string, any[]> = {}
  for (const c of categories.value) m[c] = []
  for (const t of tags.value) (m[t.category || '其他'] || (m[t.category || '其他'] = [])).push(t)
  return m
})
const filteredRels = computed(() => rels.value.filter(r =>
  (!relFilter.tagId || r.tag_id === relFilter.tagId)
  && (!relFilter.type || r.target_type === relFilter.type)
  && (!relFilter.kw || `${r.target_db}.${r.target_table}${r.target_column ? '.' + r.target_column : ''}`.toLowerCase().includes(relFilter.kw.toLowerCase()))
))

async function loadTags() { loading.value = true; try { tags.value = await api.govTags() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
async function loadRels() { loadingRel.value = true; try { rels.value = await api.govTagRelations() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loadingRel.value = false } }
async function loadRules() { loadingRule.value = true; try { rules.value = await api.govTagRules() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loadingRule.value = false } }
function open(row?: any) { Object.assign(form, { id: null, name: '', category: '分类', color: '#00e0ff', description: '' }, row || {}); dlg.value = true }
async function save() { try { await api.govSaveTag({ ...form }); ElMessage.success('保存成功'); dlg.value = false; await loadTags() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function del(row: any) {
  await ElMessageBox.confirm(`删除标签 ${row.name}？${relCountByTag.value[row.id] ? `（其 ${relCountByTag.value[row.id]} 条打标关系将保留但失去标签名）` : ''}`, '提示', { type: 'warning' })
  try { await api.govDeleteTag(row.id); ElMessage.success('已删除'); await loadTags() } catch (e:any) { ElMessage.error(errMsg(e)) }
}
async function bind() { if (!relForm.tag_id || !relForm.target_table) return ElMessage.warning('填标签与表'); try { await api.govBindTag({ ...relForm }); ElMessage.success('已打标'); relDlg.value = false; await loadRels() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function unband(row: any) { try { await api.govUnbindTag(row.id); await loadRels() } catch (e:any) { ElMessage.error(errMsg(e)) } }

function openRule() { Object.assign(ruleForm, { tag_id: null, match_type: 'TABLE_NAME', pattern: '', remark: '' }); ruleDlg.value = true }
async function saveRule() {
  if (!ruleForm.tag_id || !ruleForm.pattern) return ElMessage.warning('选标签并填正则')
  try { await api.govSaveTagRule({ ...ruleForm }); ElMessage.success('保存成功'); ruleDlg.value = false; await loadRules() }
  catch (e:any) { ElMessage.error(errMsg(e)) }
}
async function delRule(row: any) { try { await api.govDeleteTagRule(row.id); ElMessage.success('已删除'); await loadRules() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function applyRules() {
  if (!rules.value.length) return ElMessage.warning('先配置打标规则')
  applyLoading.value = true
  try {
    const r = await api.govTagRuleApply()
    ElMessage.success(`执行完成：${r.rules} 条规则命中，新建打标 ${r.created} 条`)
    await loadRels()
  } catch (e:any) { ElMessage.error(errMsg(e)) } finally { applyLoading.value = false }
}
async function inherit() {
  inhLoading.value = true
  try {
    const r = await api.govTagInherit()
    r.created > 0 ? ElMessage.success(`血缘继承完成：新建 ${r.created} 条（传播 ${r.hops} 跳）`) : ElMessage.info('无新增继承关系')
    await loadRels()
  } catch (e:any) { ElMessage.error(errMsg(e)) } finally { inhLoading.value = false }
}

onMounted(() => { loadTags(); loadRels(); loadRules() })
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.muted { color: var(--tech-text-muted); font-size: 12px; }
.mono { font-family: ui-monospace, Consolas, monospace; font-size: 12px; }

/* 概览统计 */
.tag-kpis { margin-bottom: 12px; }
.stat-card { border: 1px solid var(--tech-panel-border); border-radius: 6px; padding: 10px 14px; }
.stat-head { display: flex; align-items: baseline; gap: 8px; }
.stat-head b { font-size: 22px; }
.stat-sub { margin-top: 4px; }

/* Tab 徽标与工具行 */
.tab-badge { margin-left: 4px; font-size: 11px; color: var(--tech-text-muted); }
.tab-head { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.rel-filter { display: inline-flex; align-items: center; gap: 8px; margin-left: auto; }

/* 标签分组卡片 */
.tag-group { margin-bottom: 16px; }
.group-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.tag-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: 10px; }
.tag-card { border: 1px solid var(--tech-panel-border); border-left: 3px solid var(--tag, #00e0ff); border-radius: 6px; padding: 10px 12px; display: flex; flex-direction: column; gap: 6px; transition: box-shadow .15s; }
.tag-card:hover { box-shadow: 0 0 0 1px var(--tag, #00e0ff) inset; }
.tag-card-head { display: flex; align-items: center; gap: 8px; }
.tag-card-head b { font-size: 14px; }
.tag-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--tag, #00e0ff); box-shadow: 0 0 6px var(--tag, #00e0ff); }
.tag-usage { margin-left: auto; }
.tag-desc { color: var(--tech-text-muted); font-size: 12px; line-height: 18px; min-height: 18px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tag-card-ops { display: flex; justify-content: space-between; align-items: center; }
</style>
