<template>
  <div class="cl-page">
    <div class="page-head">
      <div class="page-head-left">
        <span class="title-icon head-ic"><el-icon><Connection /></el-icon></span>
        <div>
          <div class="page-title">发布目标</div>
          <div class="page-sub">远端 CentOS / Linux · SSH 密码 / 秘钥文件双认证 · 可选 sudo 提权 · SFTP 上传 tar + docker load</div>
        </div>
      </div>
      <div class="head-right">
        <el-button :icon="Plus" type="primary" @click="openEdit()">新增发布目标</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <div class="dl-card">
      <el-table :data="paged" v-loading="loading" stripe size="small">
        <el-table-column prop="name" label="名称" min-width="120" show-overflow-tooltip />
        <el-table-column label="地址" min-width="160"><template #default="{ row }"><span class="mono">{{ row.host }}:{{ row.ssh_port }}</span></template></el-table-column>
        <el-table-column prop="username" label="用户" width="100" />
        <el-table-column label="认证方式" width="100">
          <template #default="{ row }">
            <el-tag :type="row.auth_type === 'KEY' ? 'warning' : 'info'" size="small">{{ row.auth_type === 'KEY' ? '秘钥文件' : '密码' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="sudo" width="70">
          <template #default="{ row }">
            <el-tag :type="row.use_sudo === 'ON' ? 'danger' : 'info'" size="small">{{ row.use_sudo === 'ON' ? '提权' : '直连' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="自动启动" width="80">
          <template #default="{ row }">
            <el-tag :type="row.auto_start === 'ON' ? 'success' : 'info'" size="small">{{ row.auto_start === 'ON' ? row.run_port + ' 端口' : '手动' }}</el-tag>
          </template>
        </el-table-column>
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
        <template #empty><div class="table-empty">暂无发布目标，点击「新增发布目标」添加</div></template>
      </el-table>
      <div class="dl-pagination">
        <el-pagination :current-page="page.page" :page-size="page.size" :total="rows.length"
          :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next, jumper" size="small" background
          @size-change="onSizeChange" @current-change="onPageChange" />
      </div>
    </div>

    <el-drawer v-model="editDlg" :title="form.id ? '编辑发布目标' : '新增发布目标'" size="660px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="主机"><el-input v-model="form.host" placeholder="IP 或域名" /></el-form-item>
        <el-form-item label="SSH 端口"><el-input-number v-model="form.ssh_port" :min="1" :max="65535" controls-position="right" /></el-form-item>
        <el-form-item label="用户名"><el-input v-model="form.username" /></el-form-item>
        <el-form-item label="认证方式">
          <el-radio-group v-model="form.auth_type">
            <el-radio value="PASSWORD">密码</el-radio>
            <el-radio value="KEY">秘钥文件</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.auth_type !== 'KEY'" label="密码">
          <el-input v-model="form.password" type="password" show-password :placeholder="form.id ? '留空保存将清除' : 'SSH 登录密码'" />
        </el-form-item>
        <template v-else>
          <el-form-item label="秘钥文件">
            <div class="key-row">
              <el-button size="small" :icon="Upload" @click="pickKey">选择文件</el-button>
              <span v-if="keyFileName" class="key-file">{{ keyFileName }}</span>
              <span class="key-hint">{{ form.id ? '已解密回显，重新粘贴/选择可覆盖' : 'PEM 私钥（id_rsa / xxx.pem）' }}</span>
            </div>
            <el-input v-model="form.private_key" type="textarea" :rows="5" class="key-area mono" placeholder="-----BEGIN OPENSSH PRIVATE KEY-----\n...\n-----END OPENSSH PRIVATE KEY-----" />
          </el-form-item>
          <el-form-item label="私钥口令">
            <el-input v-model="form.key_passphrase" type="password" show-password placeholder="私钥无口令可留空" style="width:240px" />
            <span v-if="form.id && echoLoading" class="sudo-hint">回显中…</span>
          </el-form-item>
        </template>
        <el-form-item label="sudo 提权">
          <el-switch v-model="form.use_sudo" active-value="ON" inactive-value="OFF" />
          <span class="sudo-hint">普通用户无目录写权限 / 不在 docker 组时开启</span>
        </el-form-item>
        <el-form-item v-if="form.use_sudo === 'ON'" label="sudo 密码">
          <el-input v-model="form.sudo_password" type="password" show-password style="width:240px" :placeholder="form.id ? '留空保存将清除' : '该用户 sudo 时的密码'" />
          <span v-if="form.id && echoLoading" class="sudo-hint">回显中…</span>
        </el-form-item>
        <el-form-item label="自动启动">
          <el-switch v-model="form.auto_start" active-value="ON" inactive-value="OFF" />
          <span class="sudo-hint">部署成功后自动 docker run 并探活</span>
        </el-form-item>
        <template v-if="form.auto_start === 'ON'">
          <el-form-item label="宿主端口"><el-input-number v-model="form.run_port" :min="1" :max="65535" controls-position="right" /></el-form-item>
          <el-form-item label="容器名"><el-input v-model="form.container_name" placeholder="默认同镜像名" style="width:240px" /></el-form-item>
          <el-form-item label="环境变量">
            <el-input v-model="form.run_env" type="textarea" :rows="4" class="mono key-area" placeholder="每行 KEY=VALUE（# 注释）。容器需可达的后端依赖：&#10;STARROCKS_HOST=192.168.1.10&#10;STARROCKS_PORT=9030&#10;STARROCKS_USER=root&#10;STARROCKS_PWD=&#10;KAFKA_BOOTSTRAP=192.168.1.10:9094" />
          </el-form-item>
        </template>
        <el-form-item label="部署目录"><el-input v-model="form.deploy_path" placeholder="/opt/images" /></el-form-item>
        <el-form-item label="docker 命令"><el-input v-model="form.docker_bin" placeholder="docker" /></el-form-item>
        <el-form-item label="状态"><el-select v-model="form.status" style="width:140px"><el-option label="正常" value="NORMAL" /><el-option label="停用" value="DISABLED" /></el-select></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="echoLoading" @click="save">保存</el-button>
      </template>
    </el-drawer>
    <input ref="keyInput" type="file" class="key-input" @change="readKey" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Connection, Plus, Refresh, Upload } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'

const rows = ref<any[]>([])
const loading = ref(false)
const page = reactive({ page: 1, size: 10 })
const paged = computed(() => rows.value.slice((page.page - 1) * page.size, page.page * page.size))
function onSizeChange(s: number) { page.size = s; page.page = 1 }
function onPageChange(p: number) { page.page = p }
const editDlg = ref(false)
const form = ref<any>(def())
const saving = ref(false)
const keyInput = ref<HTMLInputElement>()
const keyFileName = ref('')
const echoLoading = ref(false)

function def() {
  return { id: null, name: '', host: '', ssh_port: 22, username: 'root', password: '', auth_type: 'PASSWORD', private_key: '', key_passphrase: '', use_sudo: 'OFF', sudo_password: '', auto_start: 'OFF', run_port: 80, container_name: '', run_env: '', deploy_path: '/opt/images', docker_bin: 'docker', status: 'NORMAL', remark: '' }
}

async function load() {
  page.page = 1
  loading.value = true
  try { rows.value = await api.containerServerList() } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false }
}
async function openEdit(row?: any) {
  keyFileName.value = ''
  if (!row) { form.value = def(); editDlg.value = true; return }
  // 密文四项不随列表下发：先占位空，detail 接口解密回显真实内容（编辑所见即库中所存）
  form.value = { ...row, password: '', private_key: '', key_passphrase: '', sudo_password: '' }
  editDlg.value = true
  echoLoading.value = true
  try {
    const d: any = await api.containerServerDetail(row.id)
    form.value = { ...form.value, password: d.password || '', private_key: d.private_key || '', key_passphrase: d.key_passphrase || '', sudo_password: d.sudo_password || '' }
  } catch (e: any) { ElMessage.error('密文回显失败：' + errMsg(e)) }
  finally { echoLoading.value = false }
}
function pickKey() { keyInput.value?.click() }
function readKey(e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0]
  if (!f) return
  keyFileName.value = f.name
  const rd = new FileReader()
  rd.onload = () => { form.value.private_key = String(rd.result || '').trim() }
  rd.readAsText(f)
  ;(e.target as HTMLInputElement).value = ''
}
async function save() {
  if (!form.value.name || !form.value.host) { ElMessage.warning('请填名称和主机'); return }
  if (form.value.auth_type === 'KEY' && !form.value.private_key) { ElMessage.warning('秘钥文件认证需粘贴或选择 PEM 私钥'); return }
  saving.value = true
  try { await api.containerSaveServer(form.value); ElMessage.success('保存成功'); editDlg.value = false; load() }
  catch (e: any) { ElMessage.error(errMsg(e)) } finally { saving.value = false }
}
async function test(row: any) {
  row._testing = true
  try {
    const r: any = await api.containerTestServer({ id: row.id, private_key: '***', use_sudo: row.use_sudo, sudo_password: '***' })
    r.ok ? ElMessage.success(r.msg) : ElMessage.error(r.msg)
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { row._testing = false }
}
async function del(row: any) {
  try {
    await ElMessageBox.confirm(`确认删除发布目标 ${row.name}？`, '提示', { type: 'warning' })
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
.key-row { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; flex-wrap: wrap; }
.key-file { font-size: 12px; color: var(--tech-primary); }
.key-hint { font-size: 12px; color: var(--tech-text-muted); }
.key-area { width: 100%; }
.key-input { display: none; }
.sudo-hint { margin-left: 10px; font-size: 12px; color: var(--tech-text-muted); }
</style>
