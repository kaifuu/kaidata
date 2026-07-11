<template>
  <el-dialog v-model="visible" :title="$t('profile.title')" width="640px">
    <el-tabs v-model="tab">
      <el-tab-pane :label="$t('profile.tabProfile')" name="profile">
        <el-descriptions :column="2" border v-loading="loading">
          <el-descriptions-item :label="$t('profile.account')">{{ info.username || '-' }}</el-descriptions-item>
          <el-descriptions-item :label="$t('profile.name')">{{ info.name || '-' }}</el-descriptions-item>
          <el-descriptions-item :label="$t('profile.mainRole')">{{ info.role || '-' }}</el-descriptions-item>
          <el-descriptions-item :label="$t('profile.allRoles')">{{ (info.roles || []).join(', ') || '-' }}</el-descriptions-item>
          <el-descriptions-item :label="$t('profile.tenant')">{{ info.tenant_name || '-' }}</el-descriptions-item>
          <el-descriptions-item :label="$t('profile.org')">{{ info.org_name || '-' }}</el-descriptions-item>
          <el-descriptions-item :label="$t('profile.status')">{{ info.status || '-' }}</el-descriptions-item>
          <el-descriptions-item :label="$t('profile.createTime')">{{ info.create_time || '-' }}</el-descriptions-item>
        </el-descriptions>
      </el-tab-pane>
      <el-tab-pane :label="logsLabel" name="logs">
        <el-table :data="logs" size="small" max-height="360" v-loading="logLoading" :empty-text="$t('profile.noLogs')">
          <el-table-column prop="ts" :label="$t('profile.time')" width="160" />
          <el-table-column prop="action" :label="$t('profile.action')" min-width="120" show-overflow-tooltip />
          <el-table-column prop="method" :label="$t('profile.method')" width="70" />
          <el-table-column prop="uri" :label="$t('profile.uri')" min-width="160" show-overflow-tooltip />
          <el-table-column prop="result" :label="$t('profile.result')" width="82">
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
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { api } from '@/api'

const { t } = useI18n()
const visible = ref(false)
const tab = ref('profile')
const loading = ref(false)
const logLoading = ref(false)
const info = ref<any>({})
const logs = ref<any[]>([])
// “我的操作 (N)” —— 计数随语言文案组合
const logsLabel = computed(() => t('profile.tabLogs') + (logs.value.length ? ` (${logs.value.length})` : ''))

async function open(initialTab: 'profile' | 'logs' = 'profile') {
  visible.value = true
  tab.value = initialTab
  loading.value = true; logLoading.value = true
  try { info.value = await api.authInfo() } catch { /* ignore */ } finally { loading.value = false }
  try { logs.value = await api.authLogs(20) } catch { logs.value = [] } finally { logLoading.value = false }
}
defineExpose({ open })
</script>
