<template>
  <div class="dl-card">
    <div class="card-title">
      <span class="ct-left"><el-icon class="title-icon"><Document /></el-icon>日志管理</span>
      <span class="role-tag">安全审计员</span>
    </div>

    <el-tabs v-model="tab" @tab-change="onTabChange">
      <!-- ============ 登录日志 ============ -->
      <el-tab-pane label="登录日志" name="login">
        <div class="dl-toolbar">
          <el-input v-model="lf.username" placeholder="账号" size="small" clearable style="width:130px" @keyup.enter="search" />
          <el-select v-model="lf.result" placeholder="结果" size="small" clearable style="width:130px">
            <el-option label="登录成功" value="SUCCESS" />
            <el-option label="登录失败" value="FAIL" />
            <el-option label="登出" value="LOGOUT" />
          </el-select>
          <el-date-picker v-model="range" type="datetimerange" size="small" range-separator="→"
                          start-placeholder="开始时间" end-placeholder="结束时间" style="width:340px" value-format="YYYY-MM-DD HH:mm:ss" />
          <div class="toolbar-actions">
            <el-button size="small" type="primary" @click="search"><el-icon><Search /></el-icon>查询</el-button>
            <el-button size="small" @click="reset">重置</el-button>
          </div>
        </div>
        <el-table :data="rows" size="small" stripe border max-height="540" v-loading="loading">
          <el-table-column prop="ts" label="时间" width="180" />
          <el-table-column prop="username" label="账号" width="130" />
          <el-table-column label="结果" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.result === 'SUCCESS' ? 'success' : row.result === 'LOGOUT' ? 'info' : 'danger'">
                {{ row.result === 'SUCCESS' ? '登录成功' : row.result === 'LOGOUT' ? '登出' : '登录失败' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="msg" label="说明" min-width="160" />
          <el-table-column prop="ip" label="IP" width="140" />
          <template #empty><div class="tab-empty">暂无登录日志（登录 / 登出后自动记录）</div></template>
        </el-table>
        <div class="dl-pagination">
          <el-pagination :current-page="page.page" :page-size="page.size" :total="total"
            :page-sizes="[20, 50, 100]" layout="total, sizes, prev, pager, next, jumper"
            @size-change="onSizeChange" @current-change="onPageChange" />
        </div>
      </el-tab-pane>

      <!-- ============ 操作日志（写操作审计） ============ -->
      <el-tab-pane label="操作日志" name="op">
        <div class="dl-toolbar">
          <el-input v-model="f.username" placeholder="账号" size="small" clearable style="width:130px" @keyup.enter="search" />
          <el-date-picker v-model="range" type="datetimerange" size="small" range-separator="→"
                          start-placeholder="开始时间" end-placeholder="结束时间" style="width:340px" value-format="YYYY-MM-DD HH:mm:ss" />
          <el-input v-model="f.keyword" placeholder="接口/参数关键字" size="small" clearable style="width:200px" @keyup.enter="search" />
          <div class="toolbar-actions">
            <el-button size="small" type="primary" @click="search"><el-icon><Search /></el-icon>查询</el-button>
            <el-button size="small" @click="reset">重置</el-button>
          </div>
        </div>
        <el-table :data="rows" size="small" stripe border max-height="540" v-loading="loading" @row-click="showDetail">
          <el-table-column prop="ts" label="时间" width="180" />
          <el-table-column prop="username" label="账号" width="110" />
          <el-table-column label="方法" width="76">
            <template #default="{ row }">
              <el-tag size="small" :type="methodTag(row.method)">{{ row.method }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="uri" label="操作接口" min-width="220" />
          <el-table-column prop="params" label="参数" width="150" show-overflow-tooltip />
          <el-table-column label="结果" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.result === 'OK' ? 'success' : 'danger'">{{ row.result }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="ip" label="IP" width="130" />
          <template #empty><div class="tab-empty">暂无写操作（POST / PUT / DELETE）审计记录</div></template>
        </el-table>
        <div class="dl-pagination">
          <el-pagination :current-page="page.page" :page-size="page.size" :total="total"
            :page-sizes="[20, 50, 100]" layout="total, sizes, prev, pager, next, jumper"
            @size-change="onSizeChange" @current-change="onPageChange" />
        </div>
      </el-tab-pane>

      <!-- ============ 接口日志（全量请求审计） ============ -->
      <el-tab-pane label="接口日志" name="api">
        <div class="dl-toolbar">
          <el-input v-model="f.username" placeholder="账号" size="small" clearable style="width:130px" @keyup.enter="search" />
          <el-select v-model="f.result" placeholder="结果" size="small" clearable style="width:130px">
            <el-option label="成功 OK" value="OK" />
            <el-option label="未授权" value="UNAUTHORIZED" />
          </el-select>
          <el-date-picker v-model="range" type="datetimerange" size="small" range-separator="→"
                          start-placeholder="开始时间" end-placeholder="结束时间" style="width:340px" value-format="YYYY-MM-DD HH:mm:ss" />
          <el-input v-model="f.keyword" placeholder="接口/参数关键字" size="small" clearable style="width:200px" @keyup.enter="search" />
          <div class="toolbar-actions">
            <el-button size="small" type="primary" @click="search"><el-icon><Search /></el-icon>查询</el-button>
            <el-button size="small" @click="reset">重置</el-button>
          </div>
        </div>
        <el-table :data="rows" size="small" stripe border max-height="540" v-loading="loading" @row-click="showDetail">
          <el-table-column prop="ts" label="时间" width="180" />
          <el-table-column prop="username" label="账号" width="110" />
          <el-table-column label="方法" width="76">
            <template #default="{ row }">
              <el-tag size="small" :type="methodTag(row.method)">{{ row.method }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="uri" label="接口" min-width="220" />
          <el-table-column prop="params" label="参数" width="150" show-overflow-tooltip />
          <el-table-column label="结果" width="120">
            <template #default="{ row }">
              <el-tag size="small" :type="row.result === 'OK' ? 'success' : 'danger'">{{ row.result }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="ip" label="IP" width="130" />
          <template #empty><div class="tab-empty">暂无接口审计记录</div></template>
        </el-table>
        <div class="dl-pagination">
          <el-pagination :current-page="page.page" :page-size="page.size" :total="total"
            :page-sizes="[20, 50, 100]" layout="total, sizes, prev, pager, next, jumper"
            @size-change="onSizeChange" @current-change="onPageChange" />
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 审计明细抽屉 -->
    <el-drawer v-model="detailDlg" title="日志明细" size="420px">
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="时间">{{ detail?.ts }}</el-descriptions-item>
        <el-descriptions-item label="账号">{{ detail?.username }}</el-descriptions-item>
        <el-descriptions-item label="方法">{{ detail?.method }}</el-descriptions-item>
        <el-descriptions-item label="接口">{{ detail?.uri }}</el-descriptions-item>
        <el-descriptions-item label="参数">{{ detail?.params || '—' }}</el-descriptions-item>
        <el-descriptions-item label="结果">{{ detail?.result }}</el-descriptions-item>
        <el-descriptions-item label="IP">{{ detail?.ip }}</el-descriptions-item>
      </el-descriptions>
    </el-drawer>

    <div class="hint"><el-icon><InfoFilled /></el-icon>
      登录日志由登录/登出接口记录（含失败原因）；操作日志为写操作（POST/PUT/DELETE）审计；接口日志由 AuthFilter 对每次 /api/** 请求自动落库。安全审计员只读。
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, InfoFilled, Document } from '@element-plus/icons-vue'
import { api, errMsg, type LogRow, type LoginLogRow } from '@/api'

const tab = ref('login')
const rows = ref<(LogRow | LoginLogRow)[]>([])
const total = ref(0)
const loading = ref(false)
const page = reactive({ page: 1, size: 20 })
const lf = reactive({ username: '', result: '' })            // 登录日志筛选
const f = reactive({ username: '', result: '', keyword: '' }) // 审计筛选
const range = ref<[string, string] | null>(null)
const detailDlg = ref(false)
const detail = ref<LogRow | null>(null)

function methodTag(m: string) { return m === 'GET' ? 'info' : m === 'POST' ? 'success' : 'warning' }

async function load() {
  loading.value = true
  try {
    const begin = range.value?.[0], end = range.value?.[1]
    if (tab.value === 'login') {
      const res = await api.sysLoginLogs({
        page: page.page, size: page.size,
        username: lf.username || undefined, result: lf.result || undefined, begin, end,
      })
      rows.value = res.records; total.value = res.total
    } else {
      const res = await api.sysLogs({
        page: page.page, size: page.size,
        username: f.username || undefined, result: f.result || undefined, keyword: f.keyword || undefined,
        op: tab.value === 'op' ? 'write' : undefined, begin, end,
      })
      rows.value = res.records; total.value = res.total
    }
  } catch (e) { ElMessage.error(errMsg(e)) } finally { loading.value = false }
}
function onTabChange() { page.page = 1; load() }
function search() { page.page = 1; load() }
function onPageChange(p: number) { page.page = p; load() }
function onSizeChange(s: number) { page.size = s; page.page = 1; load() }
function reset() {
  Object.assign(lf, { username: '', result: '' })
  Object.assign(f, { username: '', result: '', keyword: '' })
  range.value = null; page.page = 1; load()
}
function showDetail(row: any) { detail.value = row; detailDlg.value = true }
onMounted(load)
</script>

<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; font-weight: 600; }
.ct-left { display: inline-flex; align-items: center; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.hint { margin-top: 12px; color: var(--tech-text-muted); font-size: 13px; display: flex; align-items: center; gap: 6px; }
.tab-empty { padding: 28px 0; color: var(--tech-text-muted); font-size: 13px; }
:deep(.el-table .el-table__row) { cursor: pointer; }
</style>
