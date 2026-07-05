<template>
  <div class="dl-card">
    <div class="card-title"><span>告警管理</span><span class="role-tag">系统管理员</span></div>
    <el-tabs v-model="tab">
      <el-tab-pane label="告警定义" name="def">
        <el-button size="small" type="primary" @click="openDef()" style="margin-bottom:10px"><el-icon><Plus /></el-icon> 新增定义</el-button>
        <el-table :data="defs" size="small" stripe border v-loading="loading">
          <el-table-column prop="name" label="名称" min-width="140" />
          <el-table-column prop="source" label="来源" width="100"><template #default="{ row }"><el-tag size="small">{{ row.source }}</el-tag></template></el-table-column>
          <el-table-column prop="condition_cfg" label="条件(JSON)" min-width="200" show-overflow-tooltip />
          <el-table-column prop="notify_channels" label="通知渠道" width="120" />
          <el-table-column label="启用" width="70"><template #default="{ row }"><el-tag size="small" :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '是' : '否' }}</el-tag></template></el-table-column>
          <el-table-column label="操作" width="140"><template #default="{ row }"><el-button link size="small" type="primary" @click="openDef(row)">编辑</el-button><el-button link size="small" type="danger" @click="delDef(row)">删除</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="告警事件" name="event">
        <el-table :data="events" size="small" stripe border>
          <el-table-column prop="created_time" label="时间" width="160" />
          <el-table-column prop="level" label="级别" width="80"><template #default="{ row }"><el-tag size="small" :type="row.level === '高' ? 'danger' : 'warning'">{{ row.level }}</el-tag></template></el-table-column>
          <el-table-column prop="message" label="告警内容" min-width="280" show-overflow-tooltip />
          <el-table-column prop="status" label="状态" width="80"><template #default="{ row }"><el-tag size="small" :type="row.status === '未处理' ? 'danger' : 'success'">{{ row.status }}</el-tag></template></el-table-column>
          <el-table-column label="操作" width="80"><template #default="{ row }"><el-button v-if="row.status === '未处理'" link size="small" type="primary" @click="handle(row)">处理</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="defDlg" :title="defForm.id ? '编辑告警定义' : '新增告警定义'" width="520px">
      <el-form :model="defForm" label-width="90px" size="small">
        <el-form-item label="名称"><el-input v-model="defForm.name" /></el-form-item>
        <el-form-item label="来源"><el-select v-model="defForm.source" style="width:100%"><el-option v-for="s in ['profile','quality','mask','auth']" :key="s" :label="s" :value="s" /></el-select></el-form-item>
        <el-form-item label="条件(JSON)"><el-input v-model="defForm.condition_cfg" type="textarea" :rows="2" placeholder='{"threshold":0.1}' /></el-form-item>
        <el-form-item label="通知渠道"><el-input v-model="defForm.notify_channels" placeholder="如 邮件,短信" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="defForm.enabled" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="defDlg = false">取消</el-button><el-button type="primary" @click="saveDef">保存</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'
const tab = ref('def')
const defs = ref<any[]>([]); const events = ref<any[]>([]); const loading = ref(false)
const defDlg = ref(false); const defForm = reactive<any>({ id: null, name: '', source: 'profile', condition_cfg: '', notify_channels: '', enabled: true })
async function load() { loading.value = true; try { defs.value = await api.secAlertDefs(); events.value = await api.secAlertEvents() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
function openDef(row?: any) { Object.assign(defForm, { id: null, name: '', source: 'profile', condition_cfg: '', notify_channels: '', enabled: true }, row ? { ...row, enabled: !!row.enabled } : {}); defDlg.value = true }
async function saveDef() { try { await api.secSaveAlertDef({ ...defForm }); ElMessage.success('保存成功'); defDlg.value = false; await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function delDef(row: any) { await ElMessageBox.confirm(`删除定义 ${row.name}？`, '提示', { type: 'warning' }); try { await api.secDeleteAlertDef(row.id); ElMessage.success('已删除'); await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function handle(row: any) { try { await api.secHandleAlert(row.id); ElMessage.success('已处理'); await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
onMounted(load)
</script>
<style scoped>.card-title{display:flex;align-items:center;justify-content:space-between;font-weight:600;margin-bottom:12px}.role-tag{font-size:12px;color:var(--tech-text-muted);border:1px solid var(--tech-panel-border);padding:2px 8px;border-radius:4px}</style>
