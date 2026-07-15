<template>
  <div class="ms-page">
    <!-- 页头 -->
    <div class="page-head">
      <div class="page-head-left">
        <span class="title-icon head-ic"><el-icon><ShoppingCart /></el-icon></span>
        <div>
          <div class="page-title">我的订阅</div>
          <div class="page-sub">已订阅的库表资源 · 审核状态与对接凭证</div>
        </div>
      </div>
      <div class="head-right">
        <span class="role-tag">数据集市</span>
        <el-button :icon="Refresh" :loading="loading" @click="loadMine">刷新</el-button>
      </div>
    </div>

    <!-- 状态概览（点击筛选） -->
    <div class="dl-card ov-card">
      <div class="card-head"><span class="card-head-title">状态概览</span></div>
      <div class="stat-grid">
        <div v-for="s in statCards" :key="s.key" class="stat-chip" :class="[s.cls, { active: fStatus === s.key }]" @click="toggleStatus(s.key)">
          <span class="sc-num">{{ s.n }}</span><span class="sc-lab">{{ s.label }}</span>
        </div>
      </div>
    </div>

    <!-- 列表 -->
    <div class="dl-card ov-card">
      <div class="card-head">
        <span class="card-head-title">订阅列表</span>
        <span class="count-badge">命中 <b>{{ filtered.length }}</b></span>
      </div>
      <el-table :data="paged" size="small" stripe v-loading="loading">
        <el-table-column prop="table_name" label="库表" min-width="160" show-overflow-tooltip />
        <el-table-column label="方式" width="74"><template #default="{ row }"><el-tag size="small" effect="light">{{ row.open_type }}</el-tag></template></el-table-column>
        <el-table-column prop="purpose" label="用途" min-width="140" show-overflow-tooltip />
        <el-table-column label="状态" width="86">
          <template #default="{ row }"><span class="st-pill" :class="stType(row.status)"><i class="dot" />{{ row.status }}</span></template>
        </el-table-column>
        <el-table-column prop="audit_comment" label="审核意见" min-width="120" show-overflow-tooltip />
        <el-table-column label="appKey" width="170" show-overflow-tooltip><template #default="{ row }"><span class="mono">{{ row.app_key || '—' }}</span></template></el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button v-if="row.status === '通过'" link size="small" type="primary" @click="openCredential(row)">凭证/cURL</el-button>
          </template>
        </el-table-column>
        <template #empty><div class="table-empty"><el-icon class="empty-ic"><FolderOpened /></el-icon><div>{{ mine.length ? '无匹配订阅' : '暂无订阅，去数据集市浏览订阅' }}</div></div></template>
      </el-table>
      <div class="dl-pagination">
        <el-pagination :current-page="page.page" :page-size="page.size" :total="filtered.length"
          :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next, jumper"
          @size-change="onSizeChange" @current-change="onPageChange" />
      </div>
    </div>

    <!-- 凭证 dialog -->
    <el-dialog v-model="credDlg" :title="`对接凭证 · ${credRow?.table_name || ''}`" width="640px">
      <el-alert type="success" :closable="false" show-icon style="margin-bottom:12px">审核已通过，使用以下凭证调用开放 API。</el-alert>
      <div class="kv"><span class="k">appKey</span><el-input :model-value="credRow?.app_key" readonly class="mono" /><el-button size="small" @click="copy(credRow?.app_key)">复制</el-button></div>
      <div class="kv"><span class="k">appSecret</span><el-input :model-value="credRow?.app_secret" readonly class="mono" /><el-button size="small" @click="copy(credRow?.app_secret)">复制</el-button></div>
      <div class="kv"><span class="k">调用地址</span><el-input :model-value="credRow ? `${API_BASE}/openapi/${credRow.app_key}` : ''" readonly class="mono" /></div>
      <div class="sec-title">cURL · Windows CMD</div>
      <el-input :model-value="credCurl" type="textarea" :rows="3" readonly class="mono" />
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ShoppingCart, Refresh, FolderOpened } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'

const API_BASE = `${location.protocol}//${location.hostname}:8090`
const mine = ref<any[]>([])
const loading = ref(false)
const credDlg = ref(false)
const credRow = ref<any>(null)
const fStatus = ref('')

const stType = (s: string): any => ({ 通过: 'success', 待审: 'warning', 驳回: 'danger', 草稿: 'info' } as any)[s] || ''
const cnt = (s: string) => mine.value.filter((r) => r.status === s).length
const statCards = computed(() => [
  { key: '', label: '全部', n: mine.value.length, cls: 'all' },
  { key: '通过', label: '已通过', n: cnt('通过'), cls: 'success' },
  { key: '待审', label: '待审核', n: cnt('待审'), cls: 'warning' },
  { key: '驳回', label: '已驳回', n: cnt('驳回'), cls: 'danger' },
])

const filtered = computed(() => mine.value.filter((r) => !fStatus.value || r.status === fStatus.value))
const page = reactive({ page: 1, size: 10 })
const paged = computed(() => filtered.value.slice((page.page - 1) * page.size, page.page * page.size))
function onSizeChange(s: number) { page.size = s; page.page = 1 }
function onPageChange(p: number) { page.page = p }
function toggleStatus(k: string) { fStatus.value = fStatus.value === k ? '' : k; page.page = 1 }

async function loadMine() { loading.value = true; page.page = 1; try { mine.value = await api.subMine() } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
function openCredential(row: any) { credRow.value = row; credDlg.value = true }
const credCurl = computed(() => credRow.value ? `curl -H "X-App-Key: ${credRow.value.app_key}" -H "X-App-Secret: ${credRow.value.app_secret}" "${API_BASE}/openapi/${credRow.value.app_key}"` : '')
function copy(t?: string) { if (!t) return; try { navigator.clipboard.writeText(t); ElMessage.success('已复制') } catch { ElMessage.warning('复制失败') } }

onMounted(loadMine)
</script>

<style scoped>
.ms-page { display: flex; flex-direction: column; gap: 14px; }
.page-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.page-head-left { display: flex; align-items: center; gap: 10px; }
.head-ic { font-size: 22px; display: inline-flex; }
.page-title { font-size: 18px; font-weight: 700; color: var(--tech-text); }
.page-sub { font-size: 12px; color: var(--tech-text-muted); margin-top: 2px; }
.head-right { display: flex; align-items: center; gap: 10px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 3px 10px; border-radius: 12px; background: var(--el-fill-color-light); }
.ov-card { padding: 14px; }
.card-head { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
.card-head-title { display: flex; align-items: center; gap: 7px; font-size: 14px; font-weight: 700; color: var(--tech-text); }
.card-head .count-badge { margin-left: auto; }
.stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap: 12px; }
.stat-chip { border: 1px solid var(--tech-panel-border); border-radius: 10px; padding: 14px; cursor: pointer; background: var(--tech-bg-2); transition: transform .15s, border-color .15s; display: flex; flex-direction: column; gap: 4px; }
.stat-chip:hover { transform: translateY(-2px); }
.stat-chip.active { border-color: var(--tech-primary); box-shadow: 0 0 0 1px var(--tech-primary) inset; }
.sc-num { font-size: 24px; font-weight: 700; color: var(--tech-text); }
.sc-lab { font-size: 12px; color: var(--tech-text-muted); }
.stat-chip.success .sc-num { color: var(--tech-success); }
.stat-chip.warning .sc-num { color: var(--tech-warn); }
.stat-chip.danger .sc-num { color: var(--tech-danger); }
.st-pill { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; padding: 2px 9px; border-radius: 10px; background: var(--el-fill-color-light); color: var(--tech-text-muted); }
.st-pill .dot { width: 6px; height: 6px; border-radius: 50%; background: var(--tech-text-muted); }
.st-pill.success { color: var(--tech-success); background: color-mix(in srgb, var(--tech-success) 14%, transparent); } .st-pill.success .dot { background: var(--tech-success); }
.st-pill.warning { color: var(--tech-warn); background: color-mix(in srgb, var(--tech-warn) 14%, transparent); } .st-pill.warning .dot { background: var(--tech-warn); }
.st-pill.danger { color: var(--tech-danger); background: color-mix(in srgb, var(--tech-danger) 14%, transparent); } .st-pill.danger .dot { background: var(--tech-danger); }
.st-pill.info { color: var(--tech-text-muted); }
.mono { font-family: ui-monospace, Menlo, Consolas, monospace; }
.mono :deep(input), .mono :deep(textarea) { font-family: ui-monospace, Menlo, Consolas, monospace; }
.kv { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.kv .k { width: 72px; flex-shrink: 0; font-size: 12px; color: var(--tech-text-muted); }
.sec-title { font-size: 13px; font-weight: 600; margin: 10px 0 4px; }
.table-empty { padding: 36px 0; color: var(--tech-text-muted); text-align: center; }
.empty-ic { font-size: 30px; margin-bottom: 8px; opacity: .6; }
</style>
