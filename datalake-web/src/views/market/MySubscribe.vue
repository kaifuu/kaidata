<template>
  <div class="dl-card">
    <div class="card-title"><span>我的订阅</span><el-button link size="small" @click="loadMine">刷新</el-button></div>
    <el-table :data="mine" size="small" border v-loading="loading">
      <el-table-column prop="table_name" label="库表" min-width="160" show-overflow-tooltip />
      <el-table-column prop="open_type" label="方式" width="64" />
      <el-table-column prop="purpose" label="用途" min-width="140" show-overflow-tooltip />
      <el-table-column label="状态" width="74"><template #default="{ row }"><el-tag size="small" :type="stType(row.status)">{{ row.status }}</el-tag></template></el-table-column>
      <el-table-column prop="audit_comment" label="审核意见" min-width="120" show-overflow-tooltip />
      <el-table-column label="appKey" width="160" show-overflow-tooltip><template #default="{ row }"><span class="mono">{{ row.app_key || '—' }}</span></template></el-table-column>
      <el-table-column label="操作" width="84"><template #default="{ row }"><el-button v-if="row.status === '通过'" link size="small" type="primary" @click="openCredential(row)">凭证/cURL</el-button></template></el-table-column>
    </el-table>

    <el-dialog v-model="credDlg" :title="`对接凭证 · ${credRow?.table_name || ''}`" width="640px">
      <el-alert type="success" :closable="false" show-icon style="margin-bottom:12px">审核已通过，使用以下凭证调用开放 API。</el-alert>
      <div class="kv"><span class="k">appKey</span><el-input :model-value="credRow?.app_key" readonly class="mono" /><el-button size="small" @click="copy(credRow?.app_key)">复制</el-button></div>
      <div class="kv"><span class="k">appSecret</span><el-input :model-value="credRow?.app_secret" readonly class="mono" /><el-button size="small" @click="copy(credRow?.app_secret)">复制</el-button></div>
      <div class="kv"><span class="k">调用地址</span><el-input :model-value="`${API_BASE}/openapi/${credRow?.app_key}`" readonly class="mono" /></div>
      <div class="sec-title">cURL · Windows CMD</div>
      <el-input :model-value="credCurl" type="textarea" :rows="3" readonly class="mono" />
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errMsg } from '@/api'

const API_BASE = `${location.protocol}//${location.hostname}:8090`
const mine = ref<any[]>([])
const loading = ref(false)
const credDlg = ref(false)
const credRow = ref<any>(null)

const stType = (s: string): any => ({ 通过: 'success', 待审: 'warning', 驳回: 'danger', 草稿: 'info' } as any)[s] || ''
async function loadMine() { loading.value = true; try { mine.value = await api.subMine() } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
function openCredential(row: any) { credRow.value = row; credDlg.value = true }
const credCurl = computed(() => credRow.value ? `curl -H "X-App-Key: ${credRow.value.app_key}" -H "X-App-Secret: ${credRow.value.app_secret}" "${API_BASE}/openapi/${credRow.value.app_key}"` : '')
function copy(t?: string) { if (!t) return; try { navigator.clipboard.writeText(t); ElMessage.success('已复制') } catch { ElMessage.warning('复制失败') } }

onMounted(loadMine)
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.mono { font-family: ui-monospace, Menlo, Consolas, monospace; }
.mono :deep(input), .mono :deep(textarea) { font-family: ui-monospace, Menlo, Consolas, monospace; }
.kv { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.kv .k { width: 72px; flex-shrink: 0; font-size: 12px; color: var(--tech-text-muted); }
.sec-title { font-size: 13px; font-weight: 600; margin: 10px 0 4px; }
</style>
