<template>
  <div class="dl-card">
    <div class="card-title"><span>数据质量</span><span class="role-tag">系统管理员</span></div>
    <el-tabs v-model="tab">
      <!-- ============ 质量规则 ============ -->
      <el-tab-pane label="质量规则" name="rule">
        <el-button type="primary" size="small" @click="openRule()" style="margin-bottom:10px"><el-icon><Plus /></el-icon> 新增规则</el-button>
        <el-table :data="rules" size="small" stripe border v-loading="loadingRule">
          <el-table-column prop="name" label="规则名" min-width="130" />
          <el-table-column label="维度" width="90"><template #default="{ row }"><el-tag size="small" type="info">{{ dimLabel(row.dimension) }}</el-tag></template></el-table-column>
          <el-table-column label="严重度" width="80"><template #default="{ row }"><el-tag size="small" :type="sevType(row.severity)">{{ sevLabel(row.severity) }}</el-tag></template></el-table-column>
          <el-table-column label="检查对象" min-width="180"><template #default="{ row }">{{ row.table_name }}{{ row.column_name ? '.' + row.column_name : '' }}{{ row.expression ? '  [' + row.expression + ']' : '' }}</template></el-table-column>
          <el-table-column prop="threshold" label="阈值" width="80" />
          <el-table-column label="操作" width="140"><template #default="{ row }"><el-button link size="small" type="primary" @click="openRule(row)">编辑</el-button><el-button link size="small" type="danger" @click="delRule(row)">删除</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ============ 检查任务 ============ -->
      <el-tab-pane label="检查任务" name="task">
        <el-button type="primary" size="small" @click="openTask()" style="margin-bottom:10px"><el-icon><Plus /></el-icon> 新增任务</el-button>
        <el-table :data="tasks" size="small" stripe border v-loading="loadingTask">
          <el-table-column prop="name" label="任务名" min-width="130" />
          <el-table-column prop="rule_ids" label="规则数" width="90"><template #default="{ row }">{{ (row.rule_ids || '').split(',').filter(Boolean).length }}</template></el-table-column>
          <el-table-column prop="cron" label="周期" width="90" />
          <el-table-column label="操作" width="260"><template #default="{ row }">
            <el-button link size="small" type="success" :loading="running === row.id" @click="run(row)">执行</el-button>
            <el-button link size="small" type="primary" @click="openResult(row)">结果</el-button>
            <el-button link size="small" type="warning" @click="goReport(row)">报告</el-button>
            <el-button link size="small" type="danger" @click="delTask(row)">删除</el-button>
          </template></el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ============ 质量报告 ============ -->
      <el-tab-pane label="质量报告" name="report">
        <div class="dl-toolbar">
          <el-select v-model="reportTaskId" placeholder="选择检查任务" size="small" style="width:240px" @change="loadReport">
            <el-option v-for="t in tasks" :key="t.id" :label="t.name" :value="t.id" />
          </el-select>
          <el-button type="success" size="small" :loading="runningReport" :disabled="!reportTaskId" @click="runAndReport"><el-icon><VideoPlay /></el-icon> 执行并生成报告</el-button>
          <el-button type="primary" size="small" plain :disabled="!report || !report.latest" @click="exportWord"><el-icon><Document /></el-icon> 导出 Word 报告</el-button>
          <el-button size="small" :disabled="!reportTaskId" @click="loadReport">刷新</el-button>
        </div>

        <div v-if="!reportTaskId" class="hint">请先选择一个检查任务。</div>
        <div v-else-if="!report || !report.latest" class="hint">该任务暂无报告，点击「执行并生成报告」。</div>
        <div v-else class="report-body">
          <!-- 头部横幅 -->
          <div class="rep-banner">
            <div class="rep-banner-left">
              <div class="rep-title">{{ report.taskName }}</div>
              <div class="rep-sub">报告时间 {{ fmtTime(report.latest.run_time) }}　·　历史 {{ report.history.length }} 次</div>
              <div class="rep-kpi">
                <span class="rk"><b>{{ report.latest.total_rules }}</b>规则</span>
                <span class="rk pass"><b>{{ report.latest.pass_count }}</b>通过</span>
                <span class="rk fail"><b>{{ report.latest.fail_count }}</b>失败</span>
                <span v-if="report.latest.error_count" class="rk err"><b>{{ report.latest.error_count }}</b>异常</span>
              </div>
            </div>
            <div class="rep-banner-right">
              <el-progress type="dashboard" :percentage="report.latest.overall_score" :width="150" :stroke-width="13" :color="scoreColor">
                <template #default="{ percentage }">
                  <div class="gauge-pct">{{ percentage }}<span class="gauge-unit">分</span></div>
                </template>
              </el-progress>
              <div class="grade-badge" :class="gradeClass(report.latest.grade)">{{ report.latest.grade }}<span class="grade-cap">等级</span></div>
            </div>
          </div>

          <!-- 雷达图 + 维度列表 -->
          <div class="rep-grid2">
            <div class="rep-card">
              <div class="rep-card-title">维度质量雷达</div>
              <v-chart v-if="dimRows.length" class="chart" :option="radarOption" :theme="chartTheme" autoresize />
              <div v-else class="hint">无维度数据</div>
            </div>
            <div class="rep-card">
              <div class="rep-card-title">维度得分明细</div>
              <div class="dim-list">
                <div v-for="d in dimRows" :key="d.code" class="dim-row">
                  <span class="dim-name">{{ d.label }}</span>
                  <el-progress class="dim-bar" :percentage="d.score" :color="scoreColor(d.score)" :stroke-width="8" :show-text="false" />
                  <span class="dim-score" :style="{ color: scoreColor(d.score) }">{{ d.score }}</span>
                  <span class="dim-meta">{{ d.pass }}/{{ d.rules }} 通过</span>
                </div>
                <div v-if="!dimRows.length" class="hint">无维度数据</div>
              </div>
            </div>
          </div>

          <!-- 各表得分 -->
          <div class="rep-card">
            <div class="rep-card-title">各表得分</div>
            <div class="tab-grid">
              <div v-for="t in tableRows" :key="t.name" class="tab-card">
                <div class="tab-name">{{ t.name }}</div>
                <div class="tab-score" :style="{ color: scoreColor(t.score) }">{{ t.score }}</div>
                <div class="dim-meta">{{ t.pass }}/{{ t.rules }} 通过</div>
              </div>
              <div v-if="!tableRows.length" class="hint">无数据表</div>
            </div>
          </div>

          <!-- 趋势 -->
          <div class="rep-card">
            <div class="rep-card-title">质量分趋势</div>
            <v-chart v-if="(report.history || []).length > 1" class="chart tall" :option="trendOption" :theme="chartTheme" autoresize />
            <div v-else class="hint">至少执行 2 次后展示趋势</div>
          </div>

          <!-- 规则明细 -->
          <div class="rep-card">
            <div class="rep-card-title">规则明细</div>
            <el-table :data="report.ruleResults" size="small" border max-height="420">
              <el-table-column prop="rule_name" label="规则" min-width="130" />
              <el-table-column label="维度" width="80"><template #default="{ row }">{{ dimLabel(row.dimension) }}</template></el-table-column>
              <el-table-column label="严重度" width="70"><template #default="{ row }"><el-tag size="small" :type="sevType(row.severity)">{{ sevLabel(row.severity) }}</el-tag></template></el-table-column>
              <el-table-column label="状态" width="70"><template #default="{ row }"><el-tag size="small" :type="row.status === 'PASS' ? 'success' : 'danger'">{{ row.status }}</el-tag></template></el-table-column>
              <el-table-column label="实际值" width="90"><template #default="{ row }">{{ fmtNum(row.value) }}</template></el-table-column>
              <el-table-column label="阈值" width="80"><template #default="{ row }">{{ fmtNum(row.threshold) }}</template></el-table-column>
              <el-table-column label="违规/总数" width="100"><template #default="{ row }">{{ row.violate_count }} / {{ row.total_count }}</template></el-table-column>
              <el-table-column label="得分" width="70"><template #default="{ row }"><span :style="{ color: scoreColor(row.score), fontWeight: 600 }">{{ row.score }}</span></template></el-table-column>
              <el-table-column prop="error_msg" label="错误" min-width="120" show-overflow-tooltip />
            </el-table>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- ============ 规则弹窗 ============ -->
    <el-dialog v-model="ruleDlg" :title="ruleForm.id ? '编辑规则' : '新增规则'" width="560px">
      <el-form :model="ruleForm" label-width="80px" size="small">
        <el-form-item label="名称"><el-input v-model="ruleForm.name" /></el-form-item>
        <el-form-item label="维度">
          <el-select v-model="ruleForm.dimension" style="width:100%">
            <el-option v-for="d in dimensions" :key="d.code" :label="d.label" :value="d.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="严重度">
          <el-select v-model="ruleForm.severity" style="width:100%">
            <el-option v-for="s in severityOptions" :key="s.v" :label="s.l" :value="s.v" />
          </el-select>
        </el-form-item>
        <el-form-item label="数据源"><el-select v-model="ruleForm.ds_id" style="width:100%"><el-option v-for="d in dsList" :key="d.id" :label="`${d.name}(${d.type})`" :value="d.id" /></el-select></el-form-item>
        <el-form-item label="表"><el-input v-model="ruleForm.table_name" placeholder="ods.your_table" /></el-form-item>
        <el-form-item v-if="!isExpr(ruleForm.dimension)" label="字段"><el-input v-model="ruleForm.column_name" :placeholder="ruleForm.dimension === 'TIMELINESS' ? '时间戳列，如 create_time' : '完整性/唯一性填字段'" /></el-form-item>
        <el-form-item v-if="isExpr(ruleForm.dimension)" label="表达式"><el-input v-model="ruleForm.expression" placeholder="SQL where 条件，如 quantity < 0 或 status NOT IN ('A','B')" /></el-form-item>
        <el-form-item label="阈值">
          <el-input-number v-model="ruleForm.threshold" :min="0" :step="isRate(ruleForm.dimension) ? 0.05 : 1" controls-position="right" />
          <span class="muted" style="margin-left:8px">{{ thresholdHint(ruleForm.dimension) }}</span>
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="ruleForm.description" type="textarea" :rows="2" placeholder="规则描述（可选）" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="ruleDlg = false">取消</el-button><el-button type="primary" @click="saveRule">保存</el-button></template>
    </el-dialog>

    <!-- ============ 任务弹窗 ============ -->
    <el-dialog v-model="taskDlg" title="新增检查任务" width="540px">
      <el-form :model="taskForm" label-width="80px" size="small">
        <el-form-item label="名称"><el-input v-model="taskForm.name" /></el-form-item>
        <el-form-item label="规则"><el-select v-model="taskForm.ruleIds" multiple style="width:100%"><el-option v-for="r in rules" :key="r.id" :label="`${r.name}（${dimLabel(r.dimension)}）`" :value="r.id" /></el-select></el-form-item>
        <el-form-item label="周期(秒)"><el-input v-model="taskForm.cron" placeholder="留空手动执行" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="taskDlg = false">取消</el-button><el-button type="primary" @click="saveTask">保存</el-button></template>
    </el-dialog>

    <!-- ============ 结果弹窗（历史） ============ -->
    <el-dialog v-model="resDlg" :title="`质量结果 - ${cur?.name || ''}`" width="940px">
      <el-table :data="results" size="small" border max-height="420">
        <el-table-column prop="rule_name" label="规则" min-width="120" />
        <el-table-column label="维度" width="80"><template #default="{ row }">{{ dimLabel(row.dimension) }}</template></el-table-column>
        <el-table-column label="状态" width="70"><template #default="{ row }"><el-tag size="small" :type="row.status === 'PASS' ? 'success' : 'danger'">{{ row.status }}</el-tag></template></el-table-column>
        <el-table-column label="实际值" width="90"><template #default="{ row }">{{ fmtNum(row.value) }}</template></el-table-column>
        <el-table-column label="阈值" width="80"><template #default="{ row }">{{ fmtNum(row.threshold) }}</template></el-table-column>
        <el-table-column label="违规/总数" width="100"><template #default="{ row }">{{ row.violate_count }} / {{ row.total_count }}</template></el-table-column>
        <el-table-column label="得分" width="70"><template #default="{ row }">{{ row.score }}</template></el-table-column>
        <el-table-column prop="error_msg" label="错误" min-width="120" show-overflow-tooltip />
        <el-table-column prop="run_time" label="执行时间" width="155" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, VideoPlay, Document } from '@element-plus/icons-vue'
import { VChart } from '@/echarts'
import { theme } from '@/theme'
import { api, errMsg } from '@/api'

const chartTheme = theme.chartTheme
const tab = ref('rule')
const rules = ref<any[]>([]); const loadingRule = ref(false)
const tasks = ref<any[]>([]); const loadingTask = ref(false)
const dsList = ref<any[]>([])
const dimensions = ref<any[]>([{ code: 'COMPLETENESS', label: '完整性' }, { code: 'UNIQUENESS', label: '唯一性' }, { code: 'VALIDITY', label: '有效性' }, { code: 'TIMELINESS', label: '及时性' }, { code: 'ACCURACY', label: '准确性' }, { code: 'CONSISTENCY', label: '一致性' }])
const severityOptions = [
  { v: 'BLOCKER', l: '阻断(Blocker)' }, { v: 'CRITICAL', l: '严重(Critical)' }, { v: 'MAJOR', l: '主要(Major)' }, { v: 'MINOR', l: '次要(Minor)' }
]
const ruleDlg = ref(false); const ruleForm = reactive<any>({ id: null, name: '', dimension: 'COMPLETENESS', ds_id: null, table_name: '', column_name: '', expression: '', threshold: 0.1, severity: 'MAJOR', description: '' })
const taskDlg = ref(false); const taskForm = reactive<any>({ name: '', ruleIds: [] as number[], cron: '' })
const running = ref<number | null>(null)
const resDlg = ref(false); const cur = ref<any>(null); const results = ref<any[]>([])

// 报告
const reportTaskId = ref<number | null>(null)
const report = ref<any>(null)
const runningReport = ref(false)

const dimMap = computed(() => { const m: Record<string, string> = {}; dimensions.value.forEach((d: any) => (m[d.code] = d.label)); return m })
function dimLabel(code: string) { return dimMap.value[code] || code }
function sevLabel(s: string) { return (severityOptions.find((x) => x.v === s) || { l: s }).l.replace(/\(.*\)/, '') }
function sevType(s: string) { return s === 'BLOCKER' ? 'danger' : s === 'CRITICAL' ? 'warning' : s === 'MINOR' ? 'info' : 'primary' }
function isRate(d: string) { return d === 'COMPLETENESS' || d === 'UNIQUENESS' }
function isExpr(d: string) { return d === 'VALIDITY' || d === 'ACCURACY' || d === 'CONSISTENCY' }
function thresholdHint(d: string) {
  return ({ COMPLETENESS: '最大允许空率（0~1，0.1=最多 10% 为空）', UNIQUENESS: '最低唯一率（0~1，0.95=至少 95% 唯一）', VALIDITY: '最大违规行数（整数）', ACCURACY: '最大违规行数（整数）', CONSISTENCY: '最大违规行数（整数）', TIMELINESS: '最大允许延迟（小时）' } as any)[d] || ''
}
function scoreColor(p: number) { return p >= 90 ? '#18b566' : p >= 80 ? '#2f6bff' : p >= 60 ? '#f5a623' : '#e54d4d' }
function gradeClass(g: string) { return g === 'A' ? 'gd-a' : g === 'B' ? 'gd-b' : g === 'C' ? 'gd-c' : 'gd-d' }
function fmtNum(v: any) { if (v === null || v === undefined || v === '') return ''; const n = Number(v); return Number.isFinite(n) ? (n % 1 === 0 ? String(n) : String(Math.round(n * 1000) / 1000)) : v }
function fmtTime(t: any) { if (!t) return ''; return String(t).replace('T', ' ').replace(/\.\d+$/, '') }

const dimRows = computed(() => {
  const s = report.value?.latest?.dim_summary
  if (!s) return []
  try { return Object.entries(JSON.parse(s)).map(([code, v]: any) => ({ code, label: dimLabel(code), score: v.score, rules: v.rules, pass: v.pass, fail: v.fail })) }
  catch { return [] }
})
const tableRows = computed(() => {
  const s = report.value?.latest?.table_summary
  if (!s) return []
  try { return Object.entries(JSON.parse(s)).map(([name, v]: any) => ({ name, score: v.score, rules: v.rules, pass: v.pass, fail: v.fail })) }
  catch { return [] }
})
const radarOption = computed(() => {
  const rows = dimRows.value
  if (!rows.length) return {}
  return {
    tooltip: {},
    radar: {
      indicator: rows.map((d: any) => ({ name: d.label, max: 100 })),
      splitArea: { areaStyle: { color: ['rgba(47,107,255,.05)', 'rgba(47,107,255,.10)'] } },
      axisName: { color: '#7a8699', fontSize: 12 },
      splitLine: { lineStyle: { color: 'rgba(122,134,153,.22)' } },
      axisLine: { lineStyle: { color: 'rgba(122,134,153,.22)' } }
    },
    series: [{ type: 'radar', symbolSize: 5, data: [{ value: rows.map((d: any) => d.score), name: '维度得分', areaStyle: { color: 'rgba(47,107,255,.28)' }, lineStyle: { color: '#2f6bff', width: 2 }, itemStyle: { color: '#2f6bff' } }] }]
  }
})
const trendOption = computed(() => {
  const h = (report.value?.history || []).slice().reverse()
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 44, right: 24, top: 24, bottom: 40 },
    xAxis: { type: 'category', data: h.map((x: any) => fmtTime(x.run_time).slice(5, 16)), axisLabel: { color: '#7a8699', fontSize: 11 } },
    yAxis: { type: 'value', min: 0, max: 100, axisLabel: { color: '#7a8699' }, splitLine: { lineStyle: { color: 'rgba(122,134,153,.18)' } } },
    series: [{ type: 'line', smooth: true, data: h.map((x: any) => x.overall_score), areaStyle: { opacity: 0.15 }, itemStyle: { color: '#2f6bff' }, lineStyle: { color: '#2f6bff' }, markLine: { silent: true, data: [{ yAxis: 60, lineStyle: { color: '#e54d4d', type: 'dashed' } }] } }]
  }
})

async function loadRules() { loadingRule.value = true; try { rules.value = await api.govRules() } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loadingRule.value = false } }
async function loadTasks() { loadingTask.value = true; try { tasks.value = await api.govTasks() } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loadingTask.value = false } }
async function loadDs() { try { dsList.value = await api.daSources() } catch { dsList.value = [] } }
async function loadDimensions() { try { dimensions.value = await api.govQualityDimensions() } catch { /* 用本地兜底 */ } }
function openRule(row?: any) { Object.assign(ruleForm, { id: null, name: '', dimension: 'COMPLETENESS', ds_id: dsList.value[0]?.id || null, table_name: '', column_name: '', expression: '', threshold: 0.1, severity: 'MAJOR', description: '' }, row || {}); ruleDlg.value = true }
async function saveRule() { if (!ruleForm.name || !ruleForm.table_name) return ElMessage.warning('填名称与表'); try { await api.govSaveRule({ ...ruleForm }); ElMessage.success('保存成功'); ruleDlg.value = false; await loadRules() } catch (e: any) { ElMessage.error(errMsg(e)) } }
async function delRule(row: any) { await ElMessageBox.confirm(`删除规则 ${row.name}？`, '提示', { type: 'warning' }); try { await api.govDeleteRule(row.id); await loadRules() } catch (e: any) { ElMessage.error(errMsg(e)) } }
function openTask() { Object.assign(taskForm, { name: '', ruleIds: [], cron: '' }); taskDlg.value = true }
async function saveTask() { if (!taskForm.name || !taskForm.ruleIds.length) return ElMessage.warning('填名称与规则'); try { await api.govSaveTask({ name: taskForm.name, rule_ids: taskForm.ruleIds.join(','), cron: taskForm.cron }); ElMessage.success('保存成功'); taskDlg.value = false; await loadTasks() } catch (e: any) { ElMessage.error(errMsg(e)) } }
async function delTask(row: any) { try { await api.govDeleteTask(row.id); await loadTasks() } catch (e: any) { ElMessage.error(errMsg(e)) } }
async function run(row: any) { running.value = row.id; try { const r: any = await api.govRunQuality(row.id); ElMessage.success(`执行完成：通过 ${r.pass}/${r.total}，失败 ${r.fail}，得分 ${r.overallScore}`) } catch (e: any) { ElMessage.error(errMsg(e)) } finally { running.value = null } }
async function openResult(row: any) { cur.value = row; resDlg.value = true; try { results.value = await api.govQualityResult(row.id) } catch { results.value = [] } }

// 报告
async function loadReport() { if (!reportTaskId.value) { report.value = null; return } try { report.value = await api.govQualityReport(reportTaskId.value) } catch (e: any) { ElMessage.error(errMsg(e)); report.value = null } }
async function runAndReport() { if (!reportTaskId.value) return; runningReport.value = true; try { const r: any = await api.govRunQuality(reportTaskId.value); await loadReport(); ElMessage.success(`执行完成：得分 ${r.overallScore} / 等级 ${r.grade}`) } catch (e: any) { ElMessage.error(errMsg(e)) } finally { runningReport.value = false } }
function goReport(row: any) { reportTaskId.value = row.id; tab.value = 'report'; loadReport() }
async function exportWord() { if (!report.value) return; try { const blob: Blob = await api.govQualityReportWord(reportTaskId.value!); const url = URL.createObjectURL(blob); const a = document.createElement('a'); a.href = url; a.download = `质量报告_${report.value.taskName || reportTaskId.value}.docx`; document.body.appendChild(a); a.click(); document.body.removeChild(a); URL.revokeObjectURL(url); ElMessage.success('Word 报告已下载') } catch (e: any) { ElMessage.error(errMsg(e)) } }

onMounted(() => { loadRules(); loadTasks(); loadDs(); loadDimensions() })
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.muted { color: var(--tech-text-muted); font-size: 12px; }
.hint { color: var(--tech-text-muted); font-size: 13px; padding: 24px 0; text-align: center; }
.dl-toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 14px; flex-wrap: wrap; }

.report-body { display: flex; flex-direction: column; gap: 14px; }
/* 头部横幅 */
.rep-banner { display: flex; justify-content: space-between; align-items: center; gap: 20px; background: linear-gradient(135deg, rgba(47,107,255,.14), rgba(124,92,255,.10)); border: 1px solid var(--tech-panel-border); border-radius: 14px; padding: 18px 24px; }
.rep-banner-left { flex: 1; min-width: 0; }
.rep-title { font-size: 19px; font-weight: 700; color: var(--tech-text); }
.rep-sub { font-size: 12px; color: var(--tech-text-muted); margin: 6px 0 14px; }
.rep-kpi { display: flex; gap: 18px; flex-wrap: wrap; }
.rep-kpi .rk { font-size: 13px; color: var(--tech-text-muted); }
.rep-kpi .rk b { font-size: 20px; font-weight: 700; color: var(--tech-text); margin-right: 4px; }
.rep-kpi .rk.pass b { color: #18b566; } .rep-kpi .rk.fail b { color: #e54d4d; } .rep-kpi .rk.err b { color: #f5a623; }
.rep-banner-right { display: flex; align-items: center; gap: 18px; }
.gauge-pct { font-size: 30px; font-weight: 700; color: var(--tech-text); line-height: 1; }
.gauge-unit { font-size: 13px; color: var(--tech-text-muted); margin-left: 2px; }
.grade-badge { width: 64px; height: 64px; border-radius: 50%; display: flex; flex-direction: column; align-items: center; justify-content: center; font-size: 28px; font-weight: 700; color: #fff; }
.grade-badge .grade-cap { font-size: 10px; font-weight: 400; opacity: .92; margin-top: -2px; }
.gd-a { background: linear-gradient(135deg, #18b566, #0fa05a); } .gd-b { background: linear-gradient(135deg, #2f6bff, #1f4ed8); } .gd-c { background: linear-gradient(135deg, #f5a623, #e08a00); } .gd-d { background: linear-gradient(135deg, #e54d4d, #c62828); }

/* 卡片 */
.rep-card { background: var(--tech-panel); border: 1px solid var(--tech-panel-border); border-radius: 12px; padding: 14px 16px; }
.rep-card-title { font-size: 13px; font-weight: 600; color: var(--tech-text); border-left: 3px solid #2f6bff; padding-left: 8px; margin-bottom: 12px; }
.rep-grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
@media (max-width: 960px) { .rep-grid2 { grid-template-columns: 1fr; } .rep-banner { flex-direction: column; align-items: flex-start; } }

/* 维度列表 */
.dim-list { display: flex; flex-direction: column; gap: 12px; padding-top: 4px; }
.dim-row { display: grid; grid-template-columns: 64px 1fr 40px 64px; align-items: center; gap: 10px; }
.dim-name { font-size: 13px; color: var(--tech-text); }
.dim-bar { min-width: 0; }
.dim-score { font-size: 16px; font-weight: 700; text-align: right; }
.dim-meta { font-size: 11px; color: var(--tech-text-muted); text-align: right; }

/* 各表得分卡 */
.tab-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 10px; }
.tab-card { background: var(--tech-bg, rgba(255,255,255,.02)); border: 1px solid var(--tech-panel-border); border-radius: 10px; padding: 12px; text-align: center; }
.tab-name { font-size: 12px; color: var(--tech-text-muted); word-break: break-all; min-height: 16px; }
.tab-score { font-size: 28px; font-weight: 700; margin: 4px 0; }

.chart { width: 100%; height: 280px; }
.chart.tall { height: 240px; }
</style>
