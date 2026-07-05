<template>
  <div class="dl-card">
    <div class="card-title"><span>连接器管理 · 数据源适配器</span><el-button link size="small" @click="load">刷新</el-button></div>
    <el-table :data="rows" size="small" stripe border v-loading="loading">
      <el-table-column prop="type" label="类型" min-width="130"><template #default="{ row }"><el-tag size="small">{{ row.type }}</el-tag></template></el-table-column>
      <el-table-column label="驱动可用" width="100"><template #default="{ row }"><el-tag size="small" :type="row.driverAvailable ? 'success' : 'danger'">{{ row.driverAvailable ? '是' : '否' }}</el-tag></template></el-table-column>
      <el-table-column prop="registered" label="已登记数据源" width="130" />
      <el-table-column prop="jarHint" label="未就绪提示" min-width="280" show-overflow-tooltip>
        <template #default="{ row }">{{ row.jarHint || '—' }}</template>
      </el-table-column>
    </el-table>
    <div class="hint"><el-icon><InfoFilled /></el-icon> 共 {{ rows.length }} 种适配器；国产库驱动需手动放 jar（未就绪前可登记数据源但连通测试会提示）。</div>
  </div>
</template>
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { InfoFilled } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'
const rows = ref<any[]>([]); const loading = ref(false)
async function load() { loading.value = true; try { rows.value = await api.opsConnectors() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
onMounted(load)
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.hint { margin-top: 12px; color: var(--tech-text-muted); font-size: 13px; display: flex; align-items: center; gap: 6px; }
</style>
