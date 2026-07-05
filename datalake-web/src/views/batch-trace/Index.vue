<template>
  <div>
    <!-- 汇总卡片 -->
    <el-row :gutter="16">
      <el-col :span="6"><div class="dl-card kpi"><div class="metric-value">{{ rows.length }}</div><div class="kpi-label">追溯批次</div></div></el-col>
      <el-col :span="6"><div class="dl-card kpi"><div class="metric-value" style="color:#67c23a">{{ passCount }}</div><div class="kpi-label">检验合格</div></div></el-col>
      <el-col :span="6"><div class="dl-card kpi"><div class="metric-value" style="color:#f56c6c">{{ failCount }}</div><div class="kpi-label">检验不合格</div></div></el-col>
      <el-col :span="6"><div class="dl-card kpi"><div class="metric-value">{{ passRate }}%</div><div class="kpi-label">合格率</div></div></el-col>
    </el-row>

    <div class="dl-card">
      <div class="card-title">
        <span>批次质量全景</span>
        <div class="filters">
          <el-input v-model="keyword" placeholder="按批次号检索" size="small" clearable style="width: 220px" @keyup.enter="search" />
          <el-button size="small" type="primary" @click="search">检索</el-button>
          <el-button size="small" @click="reset">重置</el-button>
        </div>
      </div>
      <el-table :data="rows" size="small" stripe border max-height="540" v-loading="loading">
        <el-table-column prop="batch_no" label="批次号" width="220" />
        <el-table-column prop="material_code" label="物料" width="120" />
        <el-table-column prop="quantity" label="产量" width="90" />
        <el-table-column prop="batch_status" label="批次状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.batch_status === 'COMPLETED' ? 'success' : 'danger'">{{ row.batch_status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="检验结果" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="toBool(row.qc_pass) ? 'success' : 'danger'">
              {{ toBool(row.qc_pass) ? '合格' : '不合格' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="实测/标准" width="160">
          <template #default="{ row }">{{ row.qc_result }} / {{ row.qc_spec }}</template>
        </el-table-column>
        <el-table-column prop="ts" label="完工时间" />
      </el-table>
      <div v-if="!loading && !rows.length" class="empty-tip">
        暂无数据。确认已运行：① 模拟器(批次/检验) ② BatchIngestJob ③ WarehouseBuildJob 批分层加工。
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, type BatchQualityRow } from '@/api'

const rows = ref<BatchQualityRow[]>([])
const keyword = ref('')
const loading = ref(false)

const toBool = (v: boolean | number) => v === true || v === 1
const passCount = computed(() => rows.value.filter(r => toBool(r.qc_pass)).length)
const failCount = computed(() => rows.value.filter(r => !toBool(r.qc_pass)).length)
const passRate = computed(() => (rows.value.length ? Math.round((passCount.value / rows.value.length) * 1000) / 10 : 0))

async function load(batchNo?: string) {
  loading.value = true
  try {
    rows.value = await api.batchQuality(batchNo)
  } finally {
    loading.value = false
  }
}
const search = () => load(keyword.value || undefined)
const reset = () => { keyword.value = ''; load() }

onMounted(() => load())
</script>

<style scoped>
.kpi { text-align: center; }
.kpi-label { color: #909399; font-size: 13px; margin-top: 4px; }
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.filters { display: flex; gap: 8px; }
.empty-tip { color: #909399; font-size: 13px; padding: 20px 0; text-align: center; }
</style>
