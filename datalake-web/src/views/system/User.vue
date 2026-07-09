<template>
  <div class="dl-card">
    <div class="card-title">
      <span class="ct-left"><el-icon class="title-icon"><UserIcon /></el-icon>用户管理</span>
      <div class="head-right">
        <span class="count-badge">共 <b>{{ total }}</b> 个用户</span>
        <span class="role-tag">系统管理员</span>
        <el-button type="primary" size="small" @click="open()">
          <el-icon><Plus /></el-icon> 新增用户
        </el-button>
      </div>
    </div>

    <!-- 检索工具条 -->
    <div class="dl-toolbar">
      <el-input v-model="query.username" placeholder="账号" size="small" clearable style="width:140px" @keyup.enter="search" />
      <el-input v-model="query.name" placeholder="姓名" size="small" clearable style="width:140px" @keyup.enter="search" />
      <el-select v-model="query.tenantId" placeholder="全部租户" size="small" clearable style="width:150px" @change="onQueryTenant">
        <el-option v-for="t in tenants" :key="t.id" :label="t.name" :value="t.id" />
      </el-select>
      <el-select v-model="query.orgId" placeholder="全部组织" size="small" clearable style="width:150px" :disabled="!query.tenantId">
        <el-option v-for="o in queryOrgs" :key="o.id" :label="o.name" :value="o.id" />
      </el-select>
      <el-select v-model="query.status" placeholder="状态" size="small" clearable style="width:110px">
        <el-option label="正常" value="NORMAL" />
        <el-option label="停用" value="DISABLED" />
      </el-select>
      <div class="toolbar-actions">
        <el-button size="small" type="primary" @click="search"><el-icon><Search /></el-icon>查询</el-button>
        <el-button size="small" @click="resetQuery">重置</el-button>
      </div>
    </div>

    <el-table :data="users" size="small" stripe border v-loading="loading">
      <el-table-column prop="id" label="ID" width="140" />
      <el-table-column prop="username" label="账号" width="120" />
      <el-table-column prop="name" label="姓名" width="140" />
      <el-table-column prop="tenant_name" label="租户" width="130" />
      <el-table-column prop="org_name" label="组织" width="130" />
      <el-table-column label="角色" min-width="180">
        <template #default="{ row }">
          <el-tag v-for="r in row.roles" :key="r" size="small" class="r-tag" :type="roleType(r)">{{ roleName(r) }}</el-tag>
          <span v-if="!row.roles?.length" class="muted">未授权</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'NORMAL' ? 'success' : 'warning'">{{ row.status === 'NORMAL' ? '正常' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="create_time" label="创建时间" width="170" />
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <div class="row-actions">
            <el-button size="small" link type="primary" @click="open(row)">编辑</el-button>
            <el-button size="small" link type="danger" :disabled="protectedUsers.has(row.username)" @click="del(row)">删除</el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <div class="dl-pagination">
      <el-pagination :current-page="page.page" :page-size="page.size" :total="total"
        :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next, jumper"
        @size-change="onSizeChange" @current-change="onPageChange" />
    </div>

    <div class="hint">
      <el-icon><InfoFilled /></el-icon>
      用户管理只维护账号与所属租户/组织；<b>角色授予</b>由安全保密管理员在「角色管理」完成（三员分立）。
    </div>

    <el-dialog v-model="dlg" :title="form.id ? '编辑用户' : '新增用户'" width="460px">
      <el-form :model="form" label-width="72px" size="default">
        <el-form-item label="账号"><el-input v-model="form.username" :disabled="!!form.id" /></el-form-item>
        <el-form-item label="姓名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password :placeholder="form.id ? '留空则不修改' : '请输入密码'" />
        </el-form-item>
        <el-form-item label="租户">
          <el-select v-model="form.tenant_id" style="width:100%" @change="onFormTenant">
            <el-option v-for="t in tenants" :key="t.id" :label="t.name" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="组织">
          <el-select v-model="form.org_id" style="width:100%">
            <el-option v-for="o in formOrgs" :key="o.id" :label="o.name" :value="o.id" />
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
import { Plus, InfoFilled, Search, User as UserIcon } from '@element-plus/icons-vue'
import { api, errMsg, type UserRow, type TenantRow, type OrgRow } from '@/api'

const ROLE_NAME: Record<string, string> = { SYS_ADMIN: '系统管理员', SEC_ADMIN: '安全保密管理员', AUDIT_ADMIN: '安全审计员' }
const ROLE_TYPE: Record<string, any> = { SYS_ADMIN: 'primary', SEC_ADMIN: 'warning', AUDIT_ADMIN: 'success' }
const roleName = (c: string) => ROLE_NAME[c] || c
const roleType = (c: string) => ROLE_TYPE[c] || 'info'
const protectedUsers = new Set(['admin', 'sysadmin', 'secadmin', 'audadmin'])

const users = ref<UserRow[]>([])
const tenants = ref<TenantRow[]>([])
const queryOrgs = ref<OrgRow[]>([])
const formOrgs = ref<OrgRow[]>([])
const total = ref(0)
const loading = ref(false)
const dlg = ref(false)
const saving = ref(false)
const page = reactive({ page: 1, size: 20 })
const query = reactive<any>({ username: '', name: '', tenantId: null, orgId: null, status: '' })
const form = reactive<any>({ id: null, username: '', name: '', password: '', tenant_id: null, org_id: null, status: 'NORMAL' })

async function load() {
  loading.value = true
  try {
    const res = await api.sysUsers({
      page: page.page, size: page.size,
      username: query.username || undefined,
      name: query.name || undefined,
      tenantId: query.tenantId || undefined,
      orgId: query.orgId || undefined,
      status: query.status || undefined,
    })
    users.value = res.records
    total.value = res.total
  } catch (e) { ElMessage.error(errMsg(e, '加载失败')) } finally { loading.value = false }
}
function search() { page.page = 1; load() }
function onPageChange(p: number) { page.page = p; load() }
function onSizeChange(s: number) { page.size = s; page.page = 1; load() }
function resetQuery() {
  Object.assign(query, { username: '', name: '', tenantId: null, orgId: null, status: '' })
  queryOrgs.value = []
  page.page = 1; load()
}
async function onQueryTenant(tid: any) {
  query.orgId = null
  if (!tid) { queryOrgs.value = []; return }
  try { queryOrgs.value = await api.sysOrgOptions(tid) } catch { queryOrgs.value = [] }
}
async function onFormTenant(tid: any) {
  form.org_id = null
  if (!tid) { formOrgs.value = []; return }
  try { formOrgs.value = await api.sysOrgOptions(tid) } catch { formOrgs.value = [] }
}

function open(row?: UserRow) {
  Object.assign(form, { id: null, username: '', name: '', password: '', tenant_id: null, org_id: null, status: 'NORMAL' })
  if (row) {
    Object.assign(form, { id: row.id, username: row.username, name: row.name, tenant_id: row.tenant_id, org_id: row.org_id, status: row.status, password: '' })
    onFormTenant(row.tenant_id)
  } else if (tenants.value[0]) onFormTenant(tenants.value[0].id)
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
onMounted(async () => {
  tenants.value = await api.sysTenantOptions()
  await load()
})
</script>

<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.ct-left { display: inline-flex; align-items: center; }
.head-right { display: flex; align-items: center; gap: 10px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.r-tag { margin-right: 4px; }
.muted { color: var(--tech-text-muted); font-size: 12px; }
.hint { margin-top: 12px; color: var(--tech-text-muted); font-size: 13px; display: flex; align-items: center; gap: 6px; }
.hint b { color: var(--tech-primary); }
</style>
