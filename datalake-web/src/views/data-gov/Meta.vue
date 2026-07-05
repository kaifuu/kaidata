<template>
  <div class="dl-card">
    <div class="card-title"><span>元数据</span><span class="role-tag">系统管理员</span></div>
    <div style="margin-bottom:10px;display:flex;gap:8px;align-items:center">
      <el-select v-model="dsFilter" placeholder="按数据源筛选" size="small" clearable style="width:200px" @change="load">
        <el-option v-for="d in dsList" :key="d.id" :label="`${d.name}(${d.type})`" :value="d.id" />
      </el-select>
      <el-input v-model="kw" placeholder="表名/注释模糊" size="small" style="width:220px" clearable @change="load" />
      <el-button size="small" @click="syncDlg = true">从数据源同步</el-button>
      <el-button size="small" @click="load">刷新</el-button>
    </div>
    <el-table :data="rows" size="small" stripe border v-loading="loading">
      <el-table-column prop="schema_name" label="Schema" width="100" />
      <el-table-column prop="table_name" label="表名" min-width="180" />
      <el-table-column prop="comment" label="注释" min-width="160" show-overflow-tooltip />
      <el-table-column prop="row_count" label="行数" width="90" />
      <el-table-column prop="synced_time" label="同步时间" width="160" />
      <el-table-column label="操作" width="90"><template #default="{ row }"><el-button link size="small" type="primary" @click="openDetail(row)">字段</el-button></template></el-table-column>
    </el-table>
    <div class="hint">技术元数据由<b>数据探查</b>任务执行时自动同步，或手动从数据源同步。</div>

    <el-dialog v-model="syncDlg" title="从数据源同步元数据" width="420px">
      <el-select v-model="syncDs" placeholder="选择数据源" style="width:100%">
        <el-option v-for="d in dsList" :key="d.id" :label="`${d.name}(${d.type})`" :value="d.id" />
      </el-select>
      <template #footer><el-button @click="syncDlg = false">取消</el-button><el-button type="primary" :loading="syncing" @click="doSync">同步</el-button></template>
    </el-dialog>

    <el-dialog v-model="detailDlg" :title="`字段 - ${cur?.table_name || ''}`" width="640px">
      <el-table :data="cols" size="small" border max-height="420">
        <el-table-column prop="name" label="字段" min-width="150" />
        <el-table-column prop="type" label="类型" width="130" />
        <el-table-column prop="comment" label="注释" min-width="200" show-overflow-tooltip />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errMsg } from '@/api'

const rows = ref<any[]>([]); const loading = ref(false)
const dsList = ref<any[]>([]); const dsFilter = ref<number | null>(null); const kw = ref('')
const syncDlg = ref(false); const syncDs = ref<number | null>(null); const syncing = ref(false)
const detailDlg = ref(false); const cur = ref<any>(null); const cols = ref<any[]>([])

async function load() { loading.value = true; try { rows.value = await api.govMetaList({ dsId: dsFilter.value || undefined, kw: kw.value || undefined }) } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
async function doSync() { if (!syncDs.value) return ElMessage.warning('选数据源'); syncing.value = true; try { const r:any = await api.govMetaSync(syncDs.value); ElMessage.success(`已同步 ${r.synced} 张表`); syncDlg.value = false; await load() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { syncing.value = false } }
async function openDetail(row: any) { cur.value = row; detailDlg.value = true; try { const d = await api.govMetaDetail(row.id); cols.value = JSON.parse(d.columns_json || '[]') } catch (e:any) { cols.value = []; ElMessage.error(errMsg(e)) } }

onMounted(async () => { try { dsList.value = await api.daSources() } catch { /* */ } await load() })
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.hint { margin-top: 12px; color: var(--tech-text-muted); font-size: 13px; }
.hint b { color: var(--tech-primary); }
</style>
