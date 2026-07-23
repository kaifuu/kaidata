<template>
  <div class="cl-page">
    <div class="page-head">
      <div class="page-head-left">
        <span class="title-icon head-ic"><el-icon><Connection /></el-icon></span>
        <div>
          <div class="page-title">远端服务器</div>
          <div class="page-sub">部署目标 · CentOS / Linux · SSH 上传 tar + docker load</div>
        </div>
      </div>
      <div class="head-right">
        <el-button :icon="Plus" type="primary" @click="openEdit()">新增服务器</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <div class="dl-card">
      <el-table :data="rows" v-loading="loading" stripe size="small">
        <el-table-column prop="name" label="名称" min-width="120" show-overflow-tooltip />
        <el-table-column label="地址" min-width="160"><template #default="{ row }"><span class="mono">{{ row.host }}:{{ row.ssh_port }}</span></template></el-table-column>
        <el-table-column prop="username" label="用户" width="100" />
        <el-table-column prop="deploy_path" label="部署目录" min-width="120" show-overflow-tooltip />
        <el-table-column prop="docker_bin" label="docker 命令" width="110" />
        <el-table-column label="状态" width="80"><template #default="{ row }"><el-tag :type="row.status === 'NORMAL' ? 'success' : 'info'" size="small">{{ row.status === 'NORMAL' ? '正常' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :loading="!!row._testing" @click="test(row)">测试连接</el-button>
            <el-button link @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="del(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty><div class="table-empty">暂无服务器，点击「新增服务器」添加</div></template>
      </el-table>
    </div>

    <el-dialog v-model="editDlg" :title="form.id ? '编辑服务器' : '新增服务器'" width="520px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="主机"><el-input v-model="form.host" placeholder="IP 或域名" /></el-form-item>
        <el-form-item label="SSH 端口"><el-input-number v-model="form.ssh_port" :min="1" :max="65535" controls-position="right" /></el-form-item>
        <el-form-item label="用户名"><el-input v-model="form.username" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="form.password" type="password" show-password :placeholder="form.id ? '不修改请留空' : ''" /></el-form-item>
        <el-form-item label="部署目录"><el-input v-model="form.deploy_path" placeholder="/opt/images" /></el-form-item>
        <el-form-item label="docker 命令"><el-input v-model="form.docker_bin" placeholder="docker" /></el-form-item>
        <el-form-item label="状态"><el-select v-model="form.status" style="width:140px"><el-option label="正常" value="NORMAL" /><el-option label="停用" value="DISABLED" /></el-select></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Connection, Plus, Refresh } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'

const rows = ref<any[]>([])
const loading = ref(false)
const editDlg = ref(false)
const form = ref<any>(def())
const saving = ref(false)

function def() {
  return { id: null, name: '', host: '', ssh_port: 22, username: 'root', password: '', deploy_path: '/opt/images', docker_bin: 'docker', status: 'NORMAL', remark: '' }
}

async function load() {
  loading.value = true
  try { rows.value = await api.containerServerList() } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false }
}
function openEdit(row?: any) { form.value = row ? { ...row } : def(); editDlg.value = true }
async function save() {
  if (!form.value.name || !form.value.host) { ElMessage.warning('请填名称和主机'); return }
  saving.value = true
  try { await api.containerSaveServer(form.value); ElMessage.success('保存成功'); editDlg.value = false; load() }
  catch (e: any) { ElMessage.error(errMsg(e)) } finally { saving.value = false }
}
async function test(row: any) {
  row._testing = true
  try {
    const r: any = await api.containerTestServer({ id: row.id })
    r.ok ? ElMessage.success(r.msg) : ElMessage.error(r.msg)
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { row._testing = false }
}
async function del(row: any) {
  try {
    await ElMessageBox.confirm(`确认删除服务器 ${row.name}？`, '提示', { type: 'warning' })
    await api.containerDeleteServer(row.id); ElMessage.success('已删除'); load()
  } catch (e: any) { if (e !== 'cancel') ElMessage.error(errMsg(e)) }
}
onMounted(load)
</script>

<style scoped>
.cl-page { display: flex; flex-direction: column; gap: 14px; }
.page-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.page-head-left { display: flex; align-items: center; gap: 10px; }
.head-ic { font-size: 22px; display: inline-flex; color: var(--tech-primary); }
.page-title { font-size: 18px; font-weight: 700; color: var(--tech-text); }
.page-sub { font-size: 12px; color: var(--tech-text-muted); margin-top: 2px; }
.head-right { display: flex; align-items: center; gap: 10px; }
.dl-card { background: var(--tech-bg-2, var(--el-bg-color)); border: 1px solid var(--tech-panel-border, var(--el-border-color)); border-radius: 12px; padding: 14px; }
.mono { font-family: ui-monospace, Menlo, monospace; font-size: 12px; }
.table-empty { padding: 32px 0; color: var(--tech-text-muted); text-align: center; }
</style>
