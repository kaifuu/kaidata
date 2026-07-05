<template>
  <div class="dl-card">
    <div class="card-title"><span>数据权限（表级）</span><span class="role-tag">系统管理员</span></div>
    <el-button size="small" type="primary" @click="open()" style="margin-bottom:10px"><el-icon><Plus /></el-icon> 授权</el-button>
    <el-table :data="rows" size="small" stripe border v-loading="loading">
      <el-table-column prop="role_name" label="角色" width="160" />
      <el-table-column label="数据对象" min-width="220"><template #default="{ row }">{{ row.target_db }}.{{ row.target_table }}</template></el-table-column>
      <el-table-column prop="permission" label="权限" width="100"><template #default="{ row }"><el-tag size="small">{{ row.permission }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="80"><template #default="{ row }"><el-button link size="small" type="danger" @click="del(row)">移除</el-button></template></el-table-column>
    </el-table>
    <el-dialog v-model="dlg" title="授予数据权限" width="500px">
      <el-form :model="form" label-width="80px" size="small">
        <el-form-item label="角色"><el-select v-model="form.role_id" style="width:100%"><el-option v-for="r in roles" :key="r.id" :label="r.name" :value="r.id" /></el-select></el-form-item>
        <el-form-item label="库"><el-input v-model="form.target_db" placeholder="ods" /></el-form-item>
        <el-form-item label="表"><el-input v-model="form.target_table" placeholder="ods_batch" /></el-form-item>
        <el-form-item label="权限"><el-select v-model="form.permission" style="width:100%"><el-option value="select" label="select (查询)" /><el-option value="all" label="all (读写)" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="dlg = false">取消</el-button><el-button type="primary" @click="save">授权</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'
const rows = ref<any[]>([]); const loading = ref(false); const dlg = ref(false); const roles = ref<any[]>([])
const form = reactive<any>({ role_id: null, target_db: 'ods', target_table: '', permission: 'select' })
async function load() { loading.value = true; try { rows.value = await api.secDataPerms(); roles.value = await api.sysRoles() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
function open() { Object.assign(form, { role_id: roles.value[0]?.id || null, target_db: 'ods', target_table: '', permission: 'select' }); dlg.value = true }
async function save() { if (!form.role_id || !form.target_table) return ElMessage.warning('填角色与表'); try { await api.secSavePerm({ ...form }); ElMessage.success('已授权'); dlg.value = false; await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function del(row: any) { await ElMessageBox.confirm('移除该权限？', '提示', { type: 'warning' }); try { await api.secDeletePerm(row.id); ElMessage.success('已移除'); await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
onMounted(load)
</script>
<style scoped>.card-title{display:flex;align-items:center;justify-content:space-between;font-weight:600;margin-bottom:12px}.role-tag{font-size:12px;color:var(--tech-text-muted);border:1px solid var(--tech-panel-border);padding:2px 8px;border-radius:4px}</style>
