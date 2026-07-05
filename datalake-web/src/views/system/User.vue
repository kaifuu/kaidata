<template>
  <div>
    <div class="dl-card">
      <div class="card-title">
        <span>用户管理</span>
        <div style="display:flex;align-items:center;gap:10px">
          <span class="role-tag">系统管理员</span>
          <el-button type="primary" size="small" @click="open()">
            <el-icon><Plus /></el-icon> 新增用户
          </el-button>
        </div>
      </div>
      <el-table :data="users" size="small" stripe border v-loading="loading">
        <el-table-column prop="id" label="ID" width="150" />
        <el-table-column prop="username" label="账号" width="130" />
        <el-table-column prop="name" label="姓名" width="150" />
        <el-table-column prop="tenant_name" label="租户" width="140" />
        <el-table-column prop="org_name" label="组织" width="140" />
        <el-table-column label="角色" min-width="180">
          <template #default="{ row }">
            <el-tag v-for="r in row.roles" :key="r" size="small" class="r-tag" :type="roleType(r)">{{ roleName(r) }}</el-tag>
            <span v-if="!row.roles.length" class="muted">未授权（去角色管理授权）</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'NORMAL' ? 'success' : 'warning'">{{ row.status === 'NORMAL' ? '正常' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="create_time" label="创建时间" width="170" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="open(row)">编辑</el-button>
            <el-button size="small" link type="danger" :disabled="protectedUsers.has(row.username)" @click="del(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="hint">
        <el-icon><InfoFilled /></el-icon>
        用户管理只维护账号与所属租户/组织；<b>角色授予</b>由安全保密管理员在「角色管理」完成（三员分立）。
      </div>
    </div>

    <el-dialog v-model="dlg" :title="form.id ? '编辑用户' : '新增用户'" width="460px">
      <el-form :model="form" label-width="72px" size="default">
        <el-form-item label="账号"><el-input v-model="form.username" :disabled="!!form.id" /></el-form-item>
        <el-form-item label="姓名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password :placeholder="form.id ? '留空则不修改' : '请输入密码'" />
        </el-form-item>
        <el-form-item label="租户">
          <el-select v-model="form.tenant_id" style="width:100%" @change="onTenant">
            <el-option v-for="t in tenants" :key="t.id" :label="t.name" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="组织">
          <el-select v-model="form.org_id" style="width:100%">
            <el-option v-for="o in orgOptions" :key="o.id" :label="o.name" :value="o.id" />
          </el-select>
        </el-form-item>
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
import { api, errMsg, type UserRow, type TenantRow, type OrgRow } from '@/api'

const ROLE_NAME: Record<string, string> = { SYS_ADMIN: '系统管理员', SEC_ADMIN: '安全保密管理员', AUDIT_ADMIN: '安全审计员' }
const ROLE_TYPE: Record<string, any> = { SYS_ADMIN: 'primary', SEC_ADMIN: 'warning', AUDIT_ADMIN: 'success' }
const roleName = (c: string) => ROLE_NAME[c] || c
const roleType = (c: string) => ROLE_TYPE[c] || 'info'
const protectedUsers = new Set(['admin', 'sysadmin', 'secadmin', 'audadmin'])

const users = ref<UserRow[]>([])
const tenants = ref<TenantRow[]>([])
const orgOptions = ref<OrgRow[]>([])
const loading = ref(false)
const dlg = ref(false)
const saving = ref(false)
const form = reactive<any>({ id: null, username: '', name: '', password: '', tenant_id: null, org_id: null, status: 'NORMAL' })

async function load() {
  loading.value = true
  try {
    const [u, t] = await Promise.all([api.sysUsers(), api.sysTenants()])
    users.value = u
    tenants.value = t
  } catch (e) { ElMessage.error(errMsg(e, '加载失败')) } finally { loading.value = false }
}

async function onTenant(tid: number) {
  form.org_id = null
  if (!tid) { orgOptions.value = []; return }
  try { orgOptions.value = await api.sysOrgs(tid) } catch { orgOptions.value = [] }
}

function open(row?: UserRow) {
  Object.assign(form, { id: null, username: '', name: '', password: '', tenant_id: null, org_id: null, status: 'NORMAL' })
  if (row) {
    Object.assign(form, { id: row.id, username: row.username, name: row.name, tenant_id: row.tenant_id, org_id: row.org_id, status: row.status, password: '' })
    onTenant(row.tenant_id!)
  } else if (tenants.value[0]) onTenant(tenants.value[0].id)
  dlg.value = true
}

async function save() {
  if (!form.username) return ElMessage.warning('请输入账号')
  if (!form.id && !form.password) return ElMessage.warning('请输入密码')
  saving.value = true
  try {
    await api.sysSaveUser({ ...form })
    ElMessage.success('保存成功')
    dlg.value = false
    await load()
  } catch (e) { ElMessage.error(errMsg(e)) } finally { saving.value = false }
}

async function del(row: UserRow) {
  await ElMessageBox.confirm(`确定删除用户 ${row.username}？`, '提示', { type: 'warning' })
  try { await api.sysDeleteUser(row.id); ElMessage.success('已删除'); await load() }
  catch (e) { ElMessage.error(errMsg(e)) }
}

onMounted(load)
</script>

<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.r-tag { margin-right: 4px; }
.muted { color: var(--tech-text-muted); font-size: 12px; }
.hint { margin-top: 12px; color: var(--tech-text-muted); font-size: 13px; display: flex; align-items: center; gap: 6px; }
.hint b { color: var(--tech-primary); }
</style>
