<template>
  <el-dialog v-model="visible" title="个人中心" width="640px">
    <el-tabs v-model="tab">
      <el-tab-pane label="个人资料" name="profile">
        <el-descriptions :column="2" border v-loading="loading">
          <el-descriptions-item label="账号">{{ info.username || '-' }}</el-descriptions-item>
          <el-descriptions-item label="姓名">{{ info.name || '-' }}</el-descriptions-item>
          <el-descriptions-item label="主角色">{{ info.role || '-' }}</el-descriptions-item>
          <el-descriptions-item label="全部角色">{{ (info.roles || []).join('，') || '-' }}</el-descriptions-item>
          <el-descriptions-item label="租户">{{ info.tenant_name || '-' }}</el-descriptions-item>
          <el-descriptions-item label="组织">{{ info.org_name || '-' }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ info.status || '-' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ info.create_time || '-' }}</el-descriptions-item>
        </el-descriptions>
      </el-tab-pane>
      <el-tab-pane :label="`我的操作${logs.length ? ' (' + logs.length + ')' : ''}`" name="logs">
        <el-table :data="logs" size="small" max-height="360" v-loading="logLoading" empty-text="暂无操作记录">
          <el-table-column prop="ts" label="时间" width="160" />
          <el-table-column prop="action" label="操作" min-width="120" show-overflow-tooltip />
          <el-table-column prop="method" label="方法" width="70" />
          <el-table-column prop="uri" label="接口" min-width="160" show-overflow-tooltip />
          <el-table-column prop="result" label="结果" width="82">
            <template #default="{ row }">
              <el-tag size="small" :type="row.result === 'SUCCESS' ? 'success' : 'danger'" effect="plain">{{ row.result }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { api } from '@/api'

const visible = ref(false)
const tab = ref('profile')
const loading = ref(false)
const logLoading = ref(false)
const info = ref<any>({})
const logs = ref<any[]>([])

async function open(initialTab: 'profile' | 'logs' = 'profile') {
  visible.value = true
  tab.value = initialTab
  loading.value = true; logLoading.value = true
  try { info.value = await api.authInfo() } catch { /* ignore */ } finally { loading.value = false }
  try { logs.value = await api.authLogs(20) } catch { logs.value = [] } finally { logLoading.value = false }
}
defineExpose({ open })
</script>
