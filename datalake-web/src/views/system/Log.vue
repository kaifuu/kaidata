<template>
  <div class="dl-card">
    <div class="card-title">
      <span class="ct-left"><el-icon class="title-icon"><Document /></el-icon>日志管理（操作审计）</span>
      <span class="role-tag">安全审计员</span>
    </div>

    <!-- 检索工具条 -->
    <div class="dl-toolbar">
      <el-input v-model="f.username" placeholder="账号" size="small" clearable style="width:130px" @keyup.enter="search" />
      <el-select v-model="f.result" placeholder="结果" size="small" clearable style="width:130px">
        <el-option label="成功 OK" value="OK" />
        <el-option label="未授权" value="UNAUTHORIZED" />
        <el-option label="越权" value="FORBIDDEN" />
      </el-select>
      <el-input v-model="f.keyword" placeholder="接口/参数关键字" size="small" clearable style="width:210px" @keyup.enter="search" />
      <div class="toolbar-actions">
        <el-button size="small" type="primary" @click="search"><el-icon><Search /></el-icon>查询</el-button>
        <el-button size="small" @click="reset">重置</el-button>
      </div>
    </div>

    <el-table :data="rows" size="small" stripe border max-height="560" v-loading="loading">
      <el-table-column prop="ts" label="时间" width="170" />
      <el-table-column prop="username" label="账号" width="110" />
      <el-table-column prop="method" label="方法" width="70" />
      <el-table-column prop="uri" label="接口" min-width="200" />
      <el-table-column prop="params" label="参数" width="160" />
      <el-table-column label="结果" width="120">
        <template #default="{ row }">
          <el-tag size="small" :type="row.result === 'OK' ? 'success' : 'danger'">{{ row.result }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="ip" label="IP" width="130" />
    </el-table>

    <div class="dl-pagination">
      <el-pagination :current-page="page.page" :page-size="page.size" :total="total"
        :page-sizes="[20, 50, 100]" layout="total, sizes, prev, pager, next, jumper"
        @size-change="onSizeChange" @current-change="onPageChange" />
    </div>

    <div class="hint"><el-icon><InfoFilled /></el-icon> 审计日志由 AuthFilter 对每次 /api/** 请求自动落库（含三员越权 403 与未授权 401），安全审计员只读。</div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, InfoFilled, Document } from '@element-plus/icons-vue'
import { api, errMsg, type LogRow } from '@/api'

const rows = ref<LogRow[]>([])
const total = ref(0)
const loading = ref(false)
const page = reactive({ page: 1, size: 20 })
const f = reactive({ username: '', result: '', keyword: '' })

async function load() {
  loading.value = true
  try {
    const res = await api.sysLogs({
      page: page.page, size: page.size,
      username: f.username || undefined,
      result: f.result || undefined,
      keyword: f.keyword || undefined,
    })
    rows.value = res.records
    total.value = res.total
  } catch (e) { ElMessage.error(errMsg(e)) } finally { loading.value = false }
}
function search() { page.page = 1; load() }
function onPageChange(p: number) { page.page = p; load() }
function onSizeChange(s: number) { page.size = s; page.page = 1; load() }
function reset() { Object.assign(f, { username: '', result: '', keyword: '' }); page.page = 1; load() }
onMounted(load)
</script>

<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.ct-left { display: inline-flex; align-items: center; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.hint { margin-top: 12px; color: var(--tech-text-muted); font-size: 13px; display: flex; align-items: center; gap: 6px; }
</style>
