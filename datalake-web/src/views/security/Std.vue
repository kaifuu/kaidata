<template>
  <div class="dl-card">
    <div class="card-title"><span>安全标准</span><span class="role-tag">系统管理员</span></div>
    <el-button size="small" type="primary" @click="open()" style="margin-bottom:10px"><el-icon><Plus /></el-icon> 新增标准</el-button>
    <el-table :data="rows" size="small" stripe border v-loading="loading">
      <el-table-column prop="code" label="编码" width="140" />
      <el-table-column prop="name" label="名称" min-width="120" />
      <el-table-column label="等级" width="80"><template #default="{ row }"><el-tag size="small" :type="lvType(row.level)">L{{ row.level }}</el-tag></template></el-table-column>
      <el-table-column prop="description" label="说明" min-width="220" />
      <el-table-column label="操作" width="140"><template #default="{ row }"><el-button link size="small" type="primary" @click="open(row)">编辑</el-button><el-button link size="small" type="danger" @click="del(row)">删除</el-button></template></el-table-column>
    </el-table>

    <el-dialog v-model="dlg" :title="form.id ? '编辑标准' : '新增标准'" width="440px">
      <el-form :model="form" label-width="70px" size="small">
        <el-form-item label="编码"><el-input v-model="form.code" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="等级"><el-input-number v-model="form.level" :min="1" :max="9" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="form.description" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dlg = false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'
const rows = ref<any[]>([]); const loading = ref(false); const dlg = ref(false)
const form = reactive<any>({ id: null, code: '', name: '', level: 1, description: '' })
const lvType = (l: number): any => ['', 'success', '', 'warning', 'danger', 'danger'][l] || 'info'
async function load() { loading.value = true; try { rows.value = await api.secStandards() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
function open(row?: any) { Object.assign(form, { id: null, code: '', name: '', level: 1, description: '' }, row || {}); dlg.value = true }
async function save() { try { await api.secSaveStandard({ ...form }); ElMessage.success('保存成功'); dlg.value = false; await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function del(row: any) { await ElMessageBox.confirm(`删除标准 ${row.code}？`, '提示', { type: 'warning' }); try { await api.secDeleteStandard(row.id); ElMessage.success('已删除'); await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
onMounted(load)
</script>
<style scoped>.card-title{display:flex;align-items:center;justify-content:space-between;font-weight:600;margin-bottom:12px}.role-tag{font-size:12px;color:var(--tech-text-muted);border:1px solid var(--tech-panel-border);padding:2px 8px;border-radius:4px}</style>
