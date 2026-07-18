<template>
  <div class="dl-card">
    <div class="page-head">
      <div>
        <h2><el-icon><EditPen /></el-icon> 元数据补录</h2>
        <p>补录工作台：完整度看板 + 待补录清单，行内补录业务信息（无强制必填，按需填写）</p>
      </div>
      <div class="head-stats" v-if="stats">
        <span>整体完整度<b>{{ stats.overall }}%</b></span>
        <span>待补录<b>{{ stats.table.filling + stats.api.filling + stats.file.filling }}</b></span>
      </div>
    </div>

    <div class="kpi-grid" v-if="stats">
      <div class="kpi-card" v-for="k in kpis" :key="k.lab" :style="{ '--chip': k.color }">
        <div class="kpi-icon"><el-icon :size="20"><component :is="k.icon" /></el-icon></div>
        <div class="kpi-body">
          <div class="kpi-top"><span class="kpi-lab">{{ k.lab }}</span><span class="kpi-val">{{ k.pct }}%</span></div>
          <el-progress :percentage="k.pct" :stroke-width="6" :show-text="false" :color="k.color" />
          <div class="kpi-sub">已完成 {{ k.filled }} / {{ k.total }}</div>
        </div>
      </div>
    </div>

    <div class="panel" style="margin-top:16px">
      <div class="ct"><el-icon><Document /></el-icon> 待补录清单<span class="ct-sub">{{ type === 'api' ? 'API' : type === 'file' ? 'FILE' : 'TABLE' }}</span></div>
      <div class="filter-bar">
        <el-radio-group v-model="type" size="small" @change="onType">
          <el-radio-button label="table">库表</el-radio-button>
          <el-radio-button label="api">接口</el-radio-button>
          <el-radio-button label="file">文件</el-radio-button>
        </el-radio-group>
        <el-radio-group v-model="status" size="small" @change="load">
          <el-radio-button label="filling">待补录</el-radio-button>
          <el-radio-button label="filled">已完成</el-radio-button>
        </el-radio-group>
        <el-input v-model="kw" placeholder="搜索名称" size="small" style="width:200px" clearable @keyup.enter="load" />
        <el-button size="small" @click="load">查询</el-button>
      </div>
      <el-table :data="rows" size="small" stripe border v-loading="loading">
        <el-table-column prop="name" label="名称" min-width="200" />
        <el-table-column label="填充度" width="140"><template #default="{ row }"><el-progress :percentage="row.fill_percent || 0" :stroke-width="8" :status="(row.fill_percent || 0) >= 100 ? 'success' : ''" /></template></el-table-column>
        <el-table-column label="操作" width="90"><template #default="{ row }"><el-button link size="small" type="primary" @click="open(row)">补录</el-button></template></el-table-column>
      </el-table>
      <el-pagination style="margin-top:10px;justify-content:flex-end" :current-page="page" :page-size="size" :total="total" size="small" background layout="total, prev, pager, next" @current-change="v => { page = v; load() }" />
    </div>

    <el-dialog v-model="dlg" :title="'补录 · ' + (biz.cn_name || biz.name || '')" width="560px">
      <el-form :model="biz" label-width="100px" size="small">
        <el-form-item label="中文名"><el-input v-model="biz.cn_name" /></el-form-item>
        <el-form-item label="所属部门"><el-input v-model="biz.dept" /></el-form-item>
        <el-form-item label="应用系统"><el-input v-model="biz.app_system" /></el-form-item>
        <el-form-item label="资源管理员"><el-input v-model="biz.admin_owner" /></el-form-item>
        <el-form-item label="数据分类"><el-input v-model="biz.data_category" /></el-form-item>
        <el-form-item label="安全级别"><el-select v-model="biz.security_level" clearable style="width:100%"><el-option v-for="st in standards" :key="st.code" :label="st.name" :value="st.code" /></el-select></el-form-item>
        <el-form-item label="业务描述"><el-input v-model="biz.description" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="dlg = false">取消</el-button>
        <el-button type="primary" size="small" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { EditPen, Document, Coin, Connection, Files } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'

const stats = ref<any>(null)
const type = ref<'table' | 'api' | 'file'>('table')
const status = ref<'filling' | 'filled'>('filling')
const kw = ref('')
const rows = ref<any[]>([])
const page = ref(1)
const size = ref(20)
const total = ref(0)
const loading = ref(false)
const standards = ref<any[]>([])
const dlg = ref(false)
const biz = ref<any>({})
const saving = ref(false)

const kpis = computed(() => {
  if (!stats.value) return []
  const t = stats.value
  return [
    { icon: Coin, color: '#1557ef', lab: '库表', pct: t.table.avg || 0, filled: t.table.filled, total: t.table.total },
    { icon: Connection, color: '#7c5cff', lab: '接口', pct: t.api.avg || 0, filled: t.api.filled, total: t.api.total },
    { icon: Files, color: '#16b364', lab: '文件', pct: t.file.avg || 0, filled: t.file.filled, total: t.file.total },
  ]
})

async function loadStats() { try { stats.value = await api.govMetaFillStats() } catch { /* */ } }
async function load() {
  loading.value = true
  try {
    const r: any = await api.govMetaFillList({ type: type.value, status: status.value, kw: kw.value || undefined, page: page.value, size: size.value })
    rows.value = r.records || []
    total.value = r.total || 0
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false }
}
function onType() { page.value = 1; load() }
function open(row: any) {
  const b: any = { ...row, cn_name: row.cn_name || row.name }
  if (type.value === 'api') b.service_id = row.id
  else b.id = row.id
  biz.value = b
  dlg.value = true
}
async function save() {
  saving.value = true
  try {
    if (type.value === 'table') await api.govMetaSave(biz.value)
    else if (type.value === 'api') await api.govMetaApiSave(biz.value)
    else await api.govMetaFileSave(biz.value)
    ElMessage.success('已保存')
    dlg.value = false
    await Promise.all([loadStats(), load()])
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { saving.value = false }
}
onMounted(async () => { try { standards.value = await api.secStandards() } catch { /* */ } await Promise.all([loadStats(), load()]) })
</script>
<style scoped>
.page-head { display: flex; justify-content: space-between; align-items: flex-end; margin-bottom: 18px; }
.page-head h2 { margin: 0; font-size: 18px; font-weight: 600; color: var(--tech-text); display: flex; align-items: center; gap: 8px; }
.page-head h2 .el-icon { color: var(--tech-primary); }
.page-head p { margin: 6px 0 0; color: var(--tech-text-muted); font-size: 13px; }
.head-stats { display: flex; gap: 22px; }
.head-stats span { font-size: 13px; color: var(--tech-text-muted); }
.head-stats b { color: var(--tech-text); font-size: 15px; font-weight: 600; margin-left: 4px; }
.kpi-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.kpi-card { display: flex; align-items: center; gap: 14px; padding: 18px; background: var(--tech-panel); border: 1px solid var(--tech-panel-border); border-radius: 10px; box-shadow: var(--tech-shadow); transition: border-color .15s; }
.kpi-card:hover { border-color: var(--tech-primary); }
.kpi-icon { width: 42px; height: 42px; border-radius: 10px; flex-shrink: 0; display: flex; align-items: center; justify-content: center; background: color-mix(in srgb, var(--chip) 13%, var(--tech-panel)); color: var(--chip); }
.kpi-body { flex: 1; min-width: 0; }
.kpi-top { display: flex; justify-content: space-between; align-items: baseline; }
.kpi-lab { font-size: 12px; color: var(--tech-text-muted); }
.kpi-val { font-size: 22px; font-weight: 700; color: var(--tech-text); font-variant-numeric: tabular-nums; }
.kpi-sub { font-size: 11px; color: var(--tech-text-muted); opacity: .75; margin-top: 6px; }
.panel { padding: 16px; background: var(--tech-panel); border: 1px solid var(--tech-panel-border); border-radius: 10px; box-shadow: var(--tech-shadow); }
.ct { display: flex; align-items: center; gap: 8px; font-weight: 600; font-size: 14px; color: var(--tech-text); margin-bottom: 10px; }
.ct .el-icon { color: var(--tech-primary); }
.ct-sub { margin-left: auto; font-size: 11px; letter-spacing: 2px; color: var(--tech-text-muted); opacity: .55; font-weight: 400; }
.filter-bar { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 10px; }
</style>
