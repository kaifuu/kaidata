<template>
  <div class="dl-card">
    <div class="card-title"><span>数据开放</span><span class="role-tag">系统管理员</span></div>
    <div class="toolbar">
      <el-button type="primary" size="small" @click="open()"><el-icon><Plus /></el-icon> 新建授权</el-button>
      <span class="muted" style="margin-left:auto">基于已审核通过的资产开放 · API / 库表两种方式 · 调用走 appkey 鉴权 + 限次/限流/限时长</span>
    </div>

    <el-table :data="rows" size="small" stripe border v-loading="loading">
      <el-table-column prop="name" label="授权名" min-width="120" show-overflow-tooltip />
      <el-table-column label="资产" min-width="150">
        <template #default="{ row }">
          <span>{{ row.asset_name || '—' }}</span>
          <el-tag size="small" :type="assetTag(row.asset_status)" style="margin-left:6px">{{ row.asset_status || '—' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="方式" width="76">
        <template #default="{ row }"><el-tag size="small" :type="row.open_type === 'API' ? '' : 'success'">{{ row.open_type }}</el-tag></template>
      </el-table-column>
      <el-table-column label="appKey" width="170" show-overflow-tooltip><template #default="{ row }"><span class="mono">{{ row.app_key }}</span></template></el-table-column>
      <el-table-column label="限次" width="74"><template #default="{ row }">{{ row.limit_count > 0 ? row.limit_count + '次' : '不限' }}</template></el-table-column>
      <el-table-column label="限流" width="74"><template #default="{ row }">{{ row.limit_qps > 0 ? row.limit_qps + '/s' : '不限' }}</template></el-table-column>
      <el-table-column label="有效期" width="152"><template #default="{ row }">{{ row.expire_time || '长期' }}</template></el-table-column>
      <el-table-column label="状态" width="74"><template #default="{ row }"><el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status === 'ACTIVE' ? '生效' : '停用' }}</el-tag></template></el-table-column>
      <el-table-column label="调用数" width="68" align="center"><template #default="{ row }">{{ row.calls }}</template></el-table-column>
      <el-table-column label="操作" width="300" fixed="right">
        <template #default="{ row }">
          <el-button link size="small" type="primary" @click="openDebug(row)">调试</el-button>
          <el-button v-if="row.status === 'ACTIVE'" link size="small" type="warning" @click="toggle(row, 'disable')">停用</el-button>
          <el-button v-else link size="small" type="success" @click="toggle(row, 'enable')">启用</el-button>
          <el-button link size="small" @click="regen(row)">重置密钥</el-button>
          <el-button link size="small" type="danger" @click="del(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新建授权 -->
    <el-dialog v-model="dlg" title="新建数据开放授权" width="680px">
      <el-form :model="form" label-width="100px" size="small">
        <el-form-item label="授权名" required><el-input v-model="form.name" placeholder="如：客户名单查询API" /></el-form-item>
        <el-form-item label="关联资产" required>
          <el-select v-model="form.asset_id" filterable placeholder="选择已审核通过的表资产" style="width:100%" @change="onAssetChange">
            <el-option v-for="a in assetOptions" :key="a.id" :label="`${a.name}（${a.schema_name || ''}.${a.table_name || ''}）`" :value="a.id" />
          </el-select>
          <div v-if="!assetOptions.length" class="warn">暂无可开放的已审核表资产，请先在「数据资产」挂载表资产并通过审核。</div>
        </el-form-item>
        <el-form-item label="开放方式">
          <el-radio-group v-model="form.open_type">
            <el-radio value="API">API（接口调用，可带参数）</el-radio>
            <el-radio value="TABLE">库表（整表 / 字段共享）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="开放字段">
          <el-checkbox-group v-model="form.fields">
            <el-checkbox v-for="f in fieldOptions" :key="f" :value="f">{{ f }}</el-checkbox>
          </el-checkbox-group>
          <span v-if="fieldOptions.length" class="muted">不勾选则开放全部字段</span>
        </el-form-item>
        <el-form-item v-if="form.open_type === 'API'" label="参数字段">
          <el-select v-model="form.param_field" clearable placeholder="选一个等值过滤字段（可空）" style="width:100%">
            <el-option v-for="f in fieldOptions" :key="f" :label="f" :value="f" />
          </el-select>
          <span class="muted">调用时传 ?字段名=值 做等值过滤</span>
        </el-form-item>
        <el-form-item label="被授权方"><el-input v-model="form.grantee" placeholder="应用 / 租户名" /></el-form-item>
        <el-form-item label="限次">
          <el-input-number v-model="form.limit_count" :min="0" controls-position="right" />
          <span class="muted" style="margin-left:8px">总调用次数上限，0=不限</span>
        </el-form-item>
        <el-form-item label="限流(QPS)">
          <el-input-number v-model="form.limit_qps" :min="0" controls-position="right" />
          <span class="muted" style="margin-left:8px">每秒最大请求数，0=不限</span>
        </el-form-item>
        <el-form-item label="有效期">
          <el-date-picker v-model="form.expire_time" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" placeholder="留空=长期有效" style="width:100%" />
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="dlg = false">取消</el-button><el-button type="primary" @click="save">创建并生成 appKey</el-button></template>
    </el-dialog>

    <!-- 创建结果 / 重置密钥 -->
    <el-dialog v-model="resultDlg" title="授权凭证" width="560px">
      <el-alert type="success" :closable="false" show-icon style="margin-bottom:12px">请妥善保存 appKey 与 appSecret，调用时需同时携带。</el-alert>
      <div class="kv"><span class="k">appKey</span><el-input :model-value="result.app_key" readonly class="mono" /><el-button size="small" @click="copy(result.app_key)">复制</el-button></div>
      <div class="kv"><span class="k">appSecret</span><el-input :model-value="result.app_secret" readonly class="mono" /><el-button size="small" @click="copy(result.app_secret)">复制</el-button></div>
      <div class="kv"><span class="k">调用地址</span><el-input :model-value="`${API_BASE}/openapi/${result.app_key}`" readonly class="mono" /></div>
      <template #footer><el-button type="primary" @click="resultDlg = false">知道了</el-button></template>
    </el-dialog>

    <!-- 调试 -->
    <el-dialog v-model="debugDlg" :title="`调试 - ${cur?.name || ''}`" width="820px" top="6vh">
      <el-tabs v-model="debugTab">
        <el-tab-pane label="在线测试" name="test">
          <div class="kv"><span class="k">appKey</span><el-input :model-value="cur?.app_key" readonly class="mono" /></div>
          <div class="kv"><span class="k">appSecret</span><el-input v-model="testSecret" class="mono" /></div>
          <div v-if="paramField" class="kv"><span class="k">{{ paramField }}</span><el-input v-model="testParam" :placeholder="`${paramField}=值`" /></div>
          <div style="margin-top:8px"><el-button size="small" type="success" :loading="testing" @click="runTest">执行</el-button></div>
          <div v-if="testResult" style="margin-top:10px">
            <span class="muted" :style="{ color: testResult.status === 'FAIL' ? '#f56c6c' : '' }">{{ testResult.status }}<template v-if="testResult.rowsRead !== undefined"> · {{ testResult.rowsRead }} 行</template> · {{ testResult.msg || 'OK' }}</span>
            <el-table :data="testResult.rows" size="small" border max-height="320" v-if="testResult.rows && testResult.rows.length" style="margin-top:6px">
              <el-table-column v-for="c in testResult.columns" :key="c" :prop="c" :label="c" min-width="120" show-overflow-tooltip />
            </el-table>
          </div>
        </el-tab-pane>
        <el-tab-pane label="调用示例" name="sample">
          <div class="sec-title">cURL · Linux / macOS（单引号 + 反斜杠续行）</div>
          <div class="sample">
            <el-input :model-value="curlLinux" type="textarea" :rows="4" readonly class="mono" />
            <el-button size="small" @click="copy(curlLinux)">复制</el-button>
          </div>
          <div class="sec-title">cURL · Windows CMD（双引号 + ^ 续行；PowerShell 把 ^ 换成反引号 `）</div>
          <div class="sample">
            <el-input :model-value="curlWindows" type="textarea" :rows="4" readonly class="mono" />
            <el-button size="small" @click="copy(curlWindows)">复制</el-button>
          </div>
          <div class="sec-title">HTML / JS（存为 .html 用浏览器打开即可调用）</div>
          <div class="sample">
            <el-input :model-value="htmlSample" type="textarea" :rows="12" readonly class="mono" />
            <el-button size="small" @click="copy(htmlSample)">复制</el-button>
          </div>
          <div class="muted" style="margin-top:8px">把 &lt;appSecret&gt; 换成实际值；API 方式在 URL 后加 ?{{ paramField || 'param' }}=值。</div>
        </el-tab-pane>
      </el-tabs>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'

// 调用方真实后端地址（dev 经 CORS 直连 8090）
const API_BASE = `${location.protocol}//${location.hostname}:8090`

const rows = ref<any[]>([])
const assetOptions = ref<any[]>([])
const loading = ref(false)

const dlg = ref(false)
const form = reactive<any>({ name: '', asset_id: null, open_type: 'API', fields: [], param_field: '', grantee: '', limit_count: 0, limit_qps: 0, expire_time: null })

const resultDlg = ref(false)
const result = reactive<any>({ app_key: '', app_secret: '' })

const debugDlg = ref(false)
const debugTab = ref('test')
const cur = ref<any>(null)
const testSecret = ref('')
const testParam = ref('')
const testResult = ref<any>(null)
const testing = ref(false)

const fieldOptions = computed(() => {
  const a = assetOptions.value.find((x: any) => x.id === form.asset_id)
  return a ? parseColumns(a.columns_json) : []
})
// 当前调试授权的 API 参数字段（从派生 data_service.params 反查）
const paramField = computed(() => {
  try { const a = JSON.parse(cur.value?.svc_params || '[]'); return Array.isArray(a) && a.length ? a[0] : '' } catch { return '' }
})

function parseColumns(cj: string): string[] {
  if (!cj) return []
  try {
    const a = JSON.parse(cj)
    if (Array.isArray(a)) return a.map((x: any) => (typeof x === 'string' ? x : (x?.name || ''))).filter(Boolean)
  } catch { /* ignore */ }
  return []
}
function assetTag(s?: string) { return s === '通过' ? 'success' : s === '驳回' ? 'danger' : s === '待审' ? 'warning' : 'info' }

async function load() {
  loading.value = true
  try {
    const [g, a] = await Promise.all([api.openGrants(), api.openGrantAssets()])
    rows.value = g || []; assetOptions.value = a || []
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false }
}

function open() {
  Object.assign(form, { name: '', asset_id: null, open_type: 'API', fields: [], param_field: '', grantee: '', limit_count: 0, limit_qps: 0, expire_time: null })
  dlg.value = true
}
function onAssetChange() { form.fields = []; form.param_field = '' }

async function save() {
  if (!form.name || !form.asset_id) return ElMessage.warning('请填授权名并选择资产')
  try {
    const r = await api.openSaveGrant({ ...form })
    Object.assign(result, { app_key: r.app_key, app_secret: r.app_secret })
    dlg.value = false; resultDlg.value = true; await load()
  } catch (e: any) { ElMessage.error(errMsg(e)) }
}
async function del(row: any) {
  await ElMessageBox.confirm(`删除授权「${row.name}」及其派生服务？`, '提示', { type: 'warning' })
  try { await api.openDeleteGrant(row.id); ElMessage.success('已删除'); await load() } catch (e: any) { ElMessage.error(errMsg(e)) }
}
async function toggle(row: any, act: 'enable' | 'disable') {
  try { await (act === 'enable' ? api.openEnable(row.id) : api.openDisable(row.id)); ElMessage.success(act === 'enable' ? '已启用' : '已停用'); await load() }
  catch (e: any) { ElMessage.error(errMsg(e)) }
}
async function regen(row: any) {
  await ElMessageBox.confirm('重置后原 appKey/Secret 立即失效，确认？', '提示', { type: 'warning' })
  try {
    const r = await api.openRegenKey(row.id)
    Object.assign(result, { app_key: r.app_key, app_secret: r.app_secret })
    resultDlg.value = true; ElMessage.success('已重置'); await load()
  } catch (e: any) { ElMessage.error(errMsg(e)) }
}

function openDebug(row: any) {
  cur.value = row; testSecret.value = row.app_secret; testParam.value = ''; testResult.value = null; debugTab.value = 'test'; debugDlg.value = true
}
async function runTest() {
  testing.value = true
  try {
    const qs = paramField.value && testParam.value ? `?${paramField.value}=${encodeURIComponent(testParam.value)}` : ''
    const res = await fetch(`${API_BASE}/openapi/${cur.value.app_key}${qs}`, { headers: { 'X-App-Key': cur.value.app_key, 'X-App-Secret': testSecret.value } })
    testResult.value = await res.json()
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { testing.value = false }
}
const qsHint = computed(() => paramField.value ? `?${paramField.value}=值` : '')
const curlLinux = computed(() => {
  if (!cur.value) return ''
  return `curl -H 'X-App-Key: ${cur.value.app_key}' \\\n     -H 'X-App-Secret: <appSecret>' \\\n     '${API_BASE}/openapi/${cur.value.app_key}${qsHint.value}'`
})
const curlWindows = computed(() => {
  if (!cur.value) return ''
  return `curl -H "X-App-Key: ${cur.value.app_key}" ^\n     -H "X-App-Secret: <appSecret>" ^\n     "${API_BASE}/openapi/${cur.value.app_key}${qsHint.value}"`
})
const htmlSample = computed(() => {
  if (!cur.value) return ''
  const url = `${API_BASE}/openapi/${cur.value.app_key}${qsHint.value}`
  return `<!DOCTYPE html>
<html lang="zh">
<head><meta charset="utf-8"><title>开放API调用</title></head>
<body>
<h3>调用结果</h3>
<pre id="out" style="background:#f5f7fa;padding:12px">加载中...</pre>
<script>
const APP_KEY = '${cur.value.app_key}';
const APP_SECRET = '<appSecret>';   // 填入实际 appSecret
fetch('${url}', { headers: { 'X-App-Key': APP_KEY, 'X-App-Secret': APP_SECRET } })
  .then(r => r.json())
  .then(d => document.getElementById('out').textContent = JSON.stringify(d, null, 2))
  .catch(e => document.getElementById('out').textContent = '调用失败：' + e);
<` + `/script>
</body>
</html>`
})
async function copy(t: string) { try { await navigator.clipboard.writeText(t); ElMessage.success('已复制') } catch { ElMessage.warning('复制失败') } }

onMounted(load)
</script>
<style scoped>
.card-title { display:flex; align-items:center; justify-content:space-between; font-weight:600; margin-bottom:12px }
.role-tag { font-size:12px; color:var(--tech-text-muted); border:1px solid var(--tech-panel-border); padding:2px 8px; border-radius:4px }
.toolbar { display:flex; align-items:center; gap:8px; margin-bottom:12px }
.muted { color:var(--tech-text-muted); font-size:12px }
.warn { color:#e6a23c; font-size:12px }
.mono :deep(input), .mono :deep(textarea) { font-family: ui-monospace, Menlo, Consolas, monospace; }
.kv { display:flex; align-items:center; gap:8px; margin-bottom:8px }
.kv .k { width:72px; flex-shrink:0; font-size:12px; color:var(--tech-text-muted) }
.sample { display:flex; flex-direction:column; gap:6px }
.sec-title { font-size:13px; font-weight:600; margin:10px 0 4px }
.sec-title:first-child { margin-top:0 }
</style>
