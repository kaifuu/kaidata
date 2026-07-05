<template>
  <div class="dl-card">
    <div class="card-title">
      <span>日志管理（操作审计）</span>
      <span class="role-tag">安全审计员</span>
    </div>

    <!-- 筛选 -->
    <div class="filters">
      <el-input v-model="f.username" placeholder="账号" size="small" clearable style="width:130px" />
      <el-select v-model="f.result" placeholder="结果" size="small" clearable style="width:120px">
        <el-option label="成功 OK" value="OK" />
        <el-option label="未授权" value="UNAUTHORIZED" />
      </el-select>
      <el-input v-model="f.keyword" placeholder="接口/参数关键字" size="small" clearable style="width:200px" @keyup.enter="load" />
      <el-select v-model="f.limit" size="small" style="width:110px">
        <el-option :value="200" label="最近 200 条" />
        <el-option :value="500" label="最近 500 条" />
        <el-option :value="1000" label="最近 1000 条" />
      </el-select>
      <el-button size="small" type="primary" @click="load">查询</el-button>
      <el-button size="small" @click="reset">重置</el-button>
    </div>

    <el-table :data="rows" size="small" stripe border max-height="600" v-loading="loading">
      <el-table-column prop="ts" label="时间" width="170" />
      <el-table-column prop="username" label="账号" width="110" />
      <el-table-column prop="method" label="方法" width="70" />
      <el-table-column prop="uri" label="接口" min-width="200" />
      <el-table-column prop="params" label="参数" width="160" />
      <el-table-column label="结果" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="row.result === 'OK' ? 'success' : 'danger'">{{ row.result }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="ip" label="IP" width="130" />
    </el-table>
    <div class="hint">审计日志由 AuthFilter 对每次 /api/** 请求自动落库（含三员越权 403 与未授权 401），安全审计员只读。</div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errMsg, type LogRow } from '@/api'

const rows = ref<LogRow[]>([])
const loading = ref(false)
const f = reactive({ username: '', result: '', keyword: '', limit: 200 })

async function load() {
  loading.value = true
  try {
    rows.value = await api.sysLogs({
      username: f.username || undefined,
      result: f.result || undefined,
      keyword: f.keyword || undefined,
      limit: f.limit
    })
  } catch (e) { ElMessage.error(errMsg(e)) } finally { loading.value = false }
}
function reset() { Object.assign(f, { username: '', result: '', keyword: '', limit: 200 }); load() }
onMounted(load)
</script>

<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.filters { display: flex; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
.hint { margin-top: 12px; color: var(--tech-text-muted); font-size: 13px; }
</style>
