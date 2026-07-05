<template>
  <div class="dl-card">
    <div class="card-title"><span>任务中心 · 全部任务</span><span class="role-tag">系统管理员</span></div>
    <div style="margin-bottom:10px;display:flex;gap:8px;align-items:center">
      <el-select v-model="fDomain" placeholder="任务域" clearable size="small" style="width:160px" @change="filter"><el-option v-for="d in domains" :key="d" :label="d" :value="d" /></el-select>
      <el-select v-model="fStatus" placeholder="状态" clearable size="small" style="width:140px" @change="filter"><el-option v-for="s in ['ONLINE','OFFLINE','ENABLED','DISABLED','待审','通过','草稿']" :key="s" :label="s" :value="s" /></el-select>
    </div>
    <el-table :data="filtered" size="small" stripe border v-loading="loading">
      <el-table-column prop="domain" label="任务域" width="120"><template #default="{ row }"><el-tag size="small">{{ row.domain }}</el-tag></template></el-table-column>
      <el-table-column prop="id" label="ID" width="140" />
      <el-table-column prop="name" label="名称" min-width="200" />
      <el-table-column prop="status" label="状态" width="100"><template #default="{ row }"><el-tag size="small" :type="row.status === 'ONLINE' || row.status === 'ENABLED' || row.status === '通过' ? 'success' : 'info'">{{ row.status }}</el-tag></template></el-table-column>
    </el-table>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errMsg } from '@/api'
const rows = ref<any[]>([]); const loading = ref(false)
const fDomain = ref(''); const fStatus = ref('')
const domains = computed(() => [...new Set(rows.value.map((r:any) => r.domain))])
const filtered = computed(() => rows.value.filter((r:any) => (!fDomain.value || r.domain === fDomain.value) && (!fStatus.value || r.status === fStatus.value)))
async function load() { loading.value = true; try { rows.value = await api.opsTasks() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
function filter() {}
onMounted(load)
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
</style>
