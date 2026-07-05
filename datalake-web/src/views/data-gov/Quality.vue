<template>
  <div class="dl-card">
    <div class="card-title"><span>数据质量</span><span class="role-tag">系统管理员</span></div>
    <el-tabs v-model="tab">
      <el-tab-pane label="质量规则" name="rule">
        <el-button type="primary" size="small" @click="openRule()" style="margin-bottom:10px"><el-icon><Plus /></el-icon> 新增规则</el-button>
        <el-table :data="rules" size="small" stripe border v-loading="loadingRule">
          <el-table-column prop="name" label="规则名" min-width="130" />
          <el-table-column prop="dimension" label="维度" width="90"><template #default="{ row }"><el-tag size="small">{{ row.dimension }}</el-tag></template></el-table-column>
          <el-table-column label="检查对象" min-width="180"><template #default="{ row }">ds{{ row.ds_id }}/{{ row.table_name }}{{ row.column_name ? '.' + row.column_name : '' }}</template></el-table-column>
          <el-table-column prop="threshold" label="阈值" width="80" />
          <el-table-column label="操作" width="140"><template #default="{ row }"><el-button link size="small" type="primary" @click="openRule(row)">编辑</el-button><el-button link size="small" type="danger" @click="delRule(row)">删除</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="检查任务" name="task">
        <el-button type="primary" size="small" @click="openTask()" style="margin-bottom:10px"><el-icon><Plus /></el-icon> 新增任务</el-button>
        <el-table :data="tasks" size="small" stripe border v-loading="loadingTask">
          <el-table-column prop="name" label="任务名" min-width="130" />
          <el-table-column prop="rule_ids" label="规则数" width="90"><template #default="{ row }">{{ (row.rule_ids || '').split(',').filter(Boolean).length }}</template></el-table-column>
          <el-table-column prop="cron" label="周期" width="90" />
          <el-table-column label="操作" width="220"><template #default="{ row }"><el-button link size="small" type="success" :loading="running === row.id" @click="run(row)">执行</el-button><el-button link size="small" type="primary" @click="openResult(row)">结果</el-button><el-button link size="small" type="danger" @click="delTask(row)">删除</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="ruleDlg" :title="ruleForm.id ? '编辑规则' : '新增规则'" width="540px">
      <el-form :model="ruleForm" label-width="80px" size="small">
        <el-form-item label="名称"><el-input v-model="ruleForm.name" /></el-form-item>
        <el-form-item label="维度"><el-select v-model="ruleForm.dimension" style="width:100%"><el-option v-for="d in ['完整性','唯一性','自定义']" :key="d" :label="d" :value="d" /></el-select></el-form-item>
        <el-form-item label="数据源"><el-select v-model="ruleForm.ds_id" style="width:100%"><el-option v-for="d in dsList" :key="d.id" :label="`${d.name}(${d.type})`" :value="d.id" /></el-select></el-form-item>
        <el-form-item label="表"><el-input v-model="ruleForm.table_name" placeholder="ods.ods_batch" /></el-form-item>
        <el-form-item label="字段"><el-input v-model="ruleForm.column_name" placeholder="完整性/唯一性填字段" /></el-form-item>
        <el-form-item v-if="ruleForm.dimension === '自定义'" label="表达式"><el-input v-model="ruleForm.expression" placeholder="SQL where，如 quantity < 0" /></el-form-item>
        <el-form-item label="阈值"><el-input-number v-model="ruleForm.threshold" :min="0" :step="0.05" controls-position="right" /><span class="muted" style="margin-left:8px">完整性=最大空率 / 唯一性=最低唯一率 / 自定义=最大违规数</span></el-form-item>
      </el-form>
      <template #footer><el-button @click="ruleDlg = false">取消</el-button><el-button type="primary" @click="saveRule">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="taskDlg" title="新增检查任务" width="540px">
      <el-form :model="taskForm" label-width="80px" size="small">
        <el-form-item label="名称"><el-input v-model="taskForm.name" /></el-form-item>
        <el-form-item label="规则"><el-select v-model="taskForm.ruleIds" multiple style="width:100%"><el-option v-for="r in rules" :key="r.id" :label="r.name" :value="r.id" /></el-select></el-form-item>
        <el-form-item label="周期(秒)"><el-input v-model="taskForm.cron" placeholder="留空手动执行" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="taskDlg = false">取消</el-button><el-button type="primary" @click="saveTask">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="resDlg" :title="`质量结果 - ${cur?.name || ''}`" width="900px">
      <el-table :data="results" size="small" border max-height="420">
        <el-table-column prop="rule_name" label="规则" min-width="130" />
        <el-table-column prop="dimension" label="维度" width="80" />
        <el-table-column label="状态" width="70"><template #default="{ row }"><el-tag size="small" :type="row.status === 'PASS' ? 'success' : 'danger'">{{ row.status }}</el-tag></template></el-table-column>
        <el-table-column prop="value" label="实际值" width="90" />
        <el-table-column prop="threshold" label="阈值" width="80" />
        <el-table-column label="违规/总数" width="100"><template #default="{ row }">{{ row.violate_count }} / {{ row.total_count }}</template></el-table-column>
        <el-table-column prop="error_msg" label="错误" min-width="140" show-overflow-tooltip />
        <el-table-column prop="run_time" label="执行时间" width="155" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'

const tab = ref('rule')
const rules = ref<any[]>([]); const loadingRule = ref(false)
const tasks = ref<any[]>([]); const loadingTask = ref(false)
const dsList = ref<any[]>([])
const ruleDlg = ref(false); const ruleForm = reactive<any>({ id: null, name: '', dimension: '完整性', ds_id: null, table_name: '', column_name: '', expression: '', threshold: 0.1 })
const taskDlg = ref(false); const taskForm = reactive<any>({ name: '', ruleIds: [] as number[], cron: '' })
const running = ref<number | null>(null)
const resDlg = ref(false); const cur = ref<any>(null); const results = ref<any[]>([])

async function loadRules() { loadingRule.value = true; try { rules.value = await api.govRules() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loadingRule.value = false } }
async function loadTasks() { loadingTask.value = true; try { tasks.value = await api.govTasks() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loadingTask.value = false } }
async function loadDs() { try { dsList.value = await api.daSources() } catch { dsList.value = [] } }
function openRule(row?: any) { Object.assign(ruleForm, { id: null, name: '', dimension: '完整性', ds_id: dsList.value[0]?.id || null, table_name: '', column_name: '', expression: '', threshold: 0.1 }, row || {}); ruleDlg.value = true }
async function saveRule() { try { await api.govSaveRule({ ...ruleForm }); ElMessage.success('保存成功'); ruleDlg.value = false; await loadRules() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function delRule(row: any) { await ElMessageBox.confirm(`删除规则 ${row.name}？`, '提示', { type: 'warning' }); try { await api.govDeleteRule(row.id); await loadRules() } catch (e:any) { ElMessage.error(errMsg(e)) } }
function openTask() { Object.assign(taskForm, { name: '', ruleIds: [], cron: '' }); taskDlg.value = true }
async function saveTask() { if (!taskForm.name || !taskForm.ruleIds.length) return ElMessage.warning('填名称与规则'); try { await api.govSaveTask({ name: taskForm.name, rule_ids: taskForm.ruleIds.join(','), cron: taskForm.cron }); ElMessage.success('保存成功'); taskDlg.value = false; await loadTasks() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function delTask(row: any) { try { await api.govDeleteTask(row.id); await loadTasks() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function run(row: any) { running.value = row.id; try { const r:any = await api.govRunQuality(row.id); ElMessage.success(`执行完成：通过 ${r.pass}/${r.total}，失败 ${r.fail}`) } catch (e:any) { ElMessage.error(errMsg(e)) } finally { running.value = null } }
async function openResult(row: any) { cur.value = row; resDlg.value = true; try { results.value = await api.govQualityResult(row.id) } catch { results.value = [] } }

onMounted(() => { loadRules(); loadTasks(); loadDs() })
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.muted { color: var(--tech-text-muted); font-size: 12px; }
</style>
