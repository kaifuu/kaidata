<template>
  <div class="dl-card">
    <div class="card-title"><span>资产审核</span><span class="role-tag">系统管理员</span></div>
    <el-tabs v-model="tab">
      <el-tab-pane label="待审资产" name="pending">
        <el-table :data="rows" size="small" stripe border v-loading="loading">
          <el-table-column prop="name" label="资产名" min-width="140" />
          <el-table-column prop="asset_type" label="类型" width="80" />
          <el-table-column label="来源" min-width="160"><template #default="{ row }">{{ row.source_type }} #{{ row.source_id }}</template></el-table-column>
          <el-table-column prop="owner" label="归属人" width="100" />
          <el-table-column prop="security_level" label="安全级别" width="90" />
          <el-table-column prop="create_by" label="提交人" width="100" />
          <el-table-column label="操作" width="200"><template #default="{ row }"><el-button link size="small" type="success" @click="doApprove(row)">通过</el-button><el-button link size="small" type="danger" @click="doReject(row)">驳回</el-button><el-button link size="small" type="primary" @click="openHistory(row)">历史</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="全部资产" name="all">
        <el-table :data="allRows" size="small" stripe border v-loading="loadingAll">
          <el-table-column prop="name" label="资产名" min-width="140" />
          <el-table-column label="状态" width="80"><template #default="{ row }"><el-tag size="small" :type="stType(row.status)">{{ row.status }}</el-tag></template></el-table-column>
          <el-table-column prop="create_by" label="提交人" width="100" />
          <el-table-column prop="create_time" label="创建时间" width="160" />
          <el-table-column label="操作" width="160">
            <template #default="{ row }">
              <el-button v-if="row.status === '草稿'" link size="small" type="primary" @click="submit(row)">提交审核</el-button>
              <el-button link size="small" @click="openHistory(row)">历史</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="cmtDlg" :title="cmtTitle" width="440px">
      <el-input v-model="cmt" type="textarea" :rows="3" placeholder="审核意见" />
      <template #footer><el-button @click="cmtDlg = false">取消</el-button><el-button type="primary" @click="confirmCmt">确定</el-button></template>
    </el-dialog>
    <el-dialog v-model="histDlg" :title="`审核历史 - ${cur?.name || ''}`" width="600px">
      <el-table :data="hist" size="small" border>
        <el-table-column prop="action" label="动作" width="80"><template #default="{ row }"><el-tag size="small" :type="row.action === '通过' ? 'success' : row.action === '驳回' ? 'danger' : 'warning'">{{ row.action }}</el-tag></template></el-table-column>
        <el-table-column prop="comment" label="意见" min-width="180" />
        <el-table-column prop="auditor" label="审核人" width="100" />
        <el-table-column prop="audit_time" label="时间" width="160" />
      </el-table>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errMsg } from '@/api'
const tab = ref('pending')
const rows = ref<any[]>([]); const loading = ref(false)
const allRows = ref<any[]>([]); const loadingAll = ref(false)
const cmtDlg = ref(false); const cmtTitle = ref(''); const cmt = ref(''); const pendingAction = ref<'approve'|'reject'>('approve'); const curId = ref(0)
const histDlg = ref(false); const cur = ref<any>(null); const hist = ref<any[]>([])
const stType = (s: string): any => ({ 通过: 'success', 待审: 'warning', 驳回: 'danger', 草稿: 'info', 下线: 'info' } as any)[s] || ''
async function loadPending() { loading.value = true; try { rows.value = await api.assetList({ status: '待审' }) } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
async function loadAll() { loadingAll.value = true; try { allRows.value = await api.assetList() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loadingAll.value = false } }
function doApprove(row: any) { curId.value = row.id; pendingAction.value = 'approve'; cmtTitle.value = '通过审核'; cmt.value = ''; cmtDlg.value = true }
function doReject(row: any) { curId.value = row.id; pendingAction.value = 'reject'; cmtTitle.value = '驳回审核'; cmt.value = ''; cmtDlg.value = true }
async function confirmCmt() { try { if (pendingAction.value === 'approve') await api.assetApprove(curId.value, cmt.value); else await api.assetReject(curId.value, cmt.value); ElMessage.success('已' + (pendingAction.value === 'approve' ? '通过' : '驳回')); cmtDlg.value = false; await loadPending(); await loadAll() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function submit(row: any) { try { await api.assetSubmit(row.id); ElMessage.success('已提交待审'); await loadAll(); await loadPending() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function openHistory(row: any) { cur.value = row; histDlg.value = true; try { hist.value = await api.assetAudit(row.id) } catch { hist.value = [] } }
onMounted(() => { loadPending(); loadAll() })
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
</style>
