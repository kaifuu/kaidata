<template>
  <div class="dl-card">
    <div class="card-title">
      <span>租户管理</span>
      <div style="display:flex;align-items:center;gap:10px">
        <span class="role-tag">系统管理员</span>
        <el-button type="primary" size="small" @click="open()"><el-icon><Plus /></el-icon> 新增租户</el-button>
      </div>
    </div>
    <el-table :data="rows" size="small" stripe border v-loading="loading">
      <el-table-column prop="code" label="租户编码" width="160" />
      <el-table-column prop="name" label="租户名称" />
      <el-table-column prop="org_count" label="组织数" width="90" />
      <el-table-column prop="user_count" label="用户数" width="90" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'NORMAL' ? 'success' : 'warning'">{{ row.status === 'NORMAL' ? '正常' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="open(row)">编辑</el-button>
          <el-button size="small" link type="danger" @click="del(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div class="hint"><el-icon><InfoFilled /></el-icon> 租户是顶层隔离单元，租户下挂组织树与用户。</div>

    <el-dialog v-model="dlg" :title="form.id ? '编辑租户' : '新增租户'" width="420px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="编码"><el-input v-model="form.code" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio value="NORMAL">正常</el-radio>
            <el-radio value="DISABLED">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, InfoFilled } from '@element-plus/icons-vue'
import { api, errMsg, type TenantRow } from '@/api'

const rows = ref<TenantRow[]>([])
const loading = ref(false)
const dlg = ref(false)
const saving = ref(false)
const form = reactive<any>({ id: null, code: '', name: '', status: 'NORMAL' })

async function load() { loading.value = true; try { rows.value = await api.sysTenants() } catch (e) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
function open(row?: TenantRow) { Object.assign(form, { id: null, code: '', name: '', status: 'NORMAL' }); if (row) Object.assign(form, row); dlg.value = true }
async function save() {
  if (!form.code || !form.name) return ElMessage.warning('请填写编码与名称')
  saving.value = true
  try { await api.sysSaveTenant({ ...form }); ElMessage.success('保存成功'); dlg.value = false; await load() }
  catch (e) { ElMessage.error(errMsg(e)) } finally { saving.value = false }
}
async function del(row: TenantRow) {
  await ElMessageBox.confirm(`确定删除租户「${row.name}」？其下组织将一并删除。`, '提示', { type: 'warning' })
  try { await api.sysDeleteTenant(row.id); ElMessage.success('已删除'); await load() } catch (e) { ElMessage.error(errMsg(e)) }
}
onMounted(load)
</script>

<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.hint { margin-top: 12px; color: var(--tech-text-muted); font-size: 13px; display: flex; align-items: center; gap: 6px; }
</style>
