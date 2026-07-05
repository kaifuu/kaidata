<template>
  <div>
    <!-- 存储源管理 -->
    <div class="dl-card" style="margin-bottom:14px">
      <div class="card-title">
        <span>文件存储源</span>
        <div style="display:flex;align-items:center;gap:10px">
          <span class="role-tag">系统管理员</span>
          <el-button type="primary" size="small" @click="openStore()"><el-icon><Plus /></el-icon> 新增存储源</el-button>
        </div>
      </div>
      <el-table :data="stores" size="small" stripe border v-loading="loading">
        <el-table-column prop="id" label="ID" width="130" />
        <el-table-column prop="name" label="名称" min-width="120" />
        <el-table-column label="类型" width="80"><template #default="{ row }"><el-tag size="small">{{ row.type }}</el-tag></template></el-table-column>
        <el-table-column label="地址" min-width="180"><template #default="{ row }">{{ row.host }}:{{ row.port }} {{ row.base_path }}</template></el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="success" :loading="testingId === row.id" @click="testStore(row)">测试</el-button>
            <el-button size="small" link type="primary" @click="openBrowser(row)">浏览</el-button>
            <el-button size="small" link type="primary" @click="openStore(row)">编辑</el-button>
            <el-button size="small" link type="danger" @click="delStore(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 文件浏览器 -->
    <div class="dl-card">
      <div class="card-title">
        <span>文件浏览 <span v-if="currentStore" class="muted">- {{ currentStore.name }}（{{ currentStore.type }}）</span></span>
        <div v-if="currentStore" style="display:flex;gap:8px">
          <el-button size="small" @click="goRoot">回根目录</el-button>
          <el-button size="small" @click="mkdirDlg = true">新建目录</el-button>
          <el-upload :show-file-list="false" :before-upload="onUpload" :http-request="() => {}">
            <el-button size="small" type="primary">上传</el-button>
          </el-upload>
        </div>
      </div>
      <div v-if="!currentStore" class="muted" style="padding:20px 0">请选择一个存储源点击「浏览」</div>
      <template v-else>
        <div class="breadcrumb">路径: <el-link type="primary" @click="cd('')">root</el-link>
          <template v-for="(seg, i) in pathSegs" :key="i"> / <el-link type="primary" @click="cd(pathUpto(i))">{{ seg }}</el-link></template>
        </div>
        <el-table :data="files" size="small" border v-loading="browsing" @row-dblclick="onDblClick">
          <el-table-column prop="name" label="名称" min-width="240">
            <template #default="{ row }"><el-icon v-if="row.isDir" style="margin-right:4px"><Folder /></el-icon>{{ row.name }}</template>
          </el-table-column>
          <el-table-column label="类型" width="80"><template #default="{ row }">{{ row.isDir ? '目录' : '文件' }}</template></el-table-column>
          <el-table-column prop="size" label="大小" width="110" />
          <el-table-column label="操作" width="220">
            <template #default="{ row }">
              <el-button v-if="!row.isDir" size="small" link type="success" @click="openIngest(row)">接入</el-button>
              <el-button size="small" link type="primary" @click="openCopy(row)">复制</el-button>
              <el-button size="small" link type="danger" @click="delFile(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div style="margin-top:10px">
          <el-button size="small" @click="tab = 'ingested'">查看已接入文件</el-button>
        </div>
      </template>
    </div>

    <!-- 存储源编辑 -->
    <el-dialog v-model="storeDlg" :title="storeForm.id ? '编辑存储源' : '新增存储源'" width="500px">
      <el-form :model="storeForm" label-width="80px">
        <el-form-item label="名称"><el-input v-model="storeForm.name" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="storeForm.type" style="width:100%">
            <el-option label="FTP" value="ftp" /><el-option label="SFTP" value="sftp" /><el-option label="HDFS（需profile）" value="hdfs" />
          </el-select>
        </el-form-item>
        <el-form-item label="主机"><el-input v-model="storeForm.host" /></el-form-item>
        <el-form-item label="端口"><el-input-number v-model="storeForm.port" :min="0" :max="65535" controls-position="right" style="width:100%" /></el-form-item>
        <el-form-item label="账号"><el-input v-model="storeForm.username" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="storeForm.password" type="password" show-password :placeholder="storeForm.id ? '留空不修改' : ''" /></el-form-item>
        <el-form-item label="根路径"><el-input v-model="storeForm.base_path" placeholder="/data 或留空" /></el-form-item>
        <el-form-item label="状态"><el-radio-group v-model="storeForm.status"><el-radio value="NORMAL">正常</el-radio><el-radio value="DISABLED">停用</el-radio></el-radio-group></el-form-item>
      </el-form>
      <template #footer><el-button @click="storeDlg = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveStore">保存</el-button></template>
    </el-dialog>

    <!-- 新建目录 -->
    <el-dialog v-model="mkdirDlg" title="新建目录" width="360px">
      <el-input v-model="mkdirName" placeholder="目录名（相对当前路径）" />
      <template #footer><el-button @click="mkdirDlg = false">取消</el-button><el-button type="primary" @click="doMkdir">确定</el-button></template>
    </el-dialog>

    <!-- 复制 -->
    <el-dialog v-model="copyDlg" title="复制到" width="380px">
      <el-input v-model="copyDst" placeholder="目标路径（相对根）" />
      <template #footer><el-button @click="copyDlg = false">取消</el-button><el-button type="primary" @click="doCopy">确定</el-button></template>
    </el-dialog>

    <!-- 接入配置 -->
    <el-dialog v-model="ingestDlg" :title="`接入 ${ingestFile?.name || ''}`" width="560px">
      <el-form label-width="90px">
        <el-form-item label="文件类型"><el-radio-group v-model="ingestType"><el-radio value="csv">CSV</el-radio><el-radio value="json">JSONL</el-radio></el-radio-group></el-form-item>
        <el-form-item label="目标表名"><el-input v-model="ingestTarget" placeholder="留空自动生成 ods_file_xxx" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="ingestDlg = false">取消</el-button><el-button type="primary" :loading="ingesting" @click="doIngest">接入</el-button></template>
      <div v-if="ingestResult" style="margin-top:10px">
        <el-alert :title="`已接入 ${ingestResult.targetTable}，写入 ${ingestResult.rowsWritten} 行`" type="success" :closable="false" />
        <el-table :data="ingestResult.preview" size="small" border max-height="240" style="margin-top:8px">
          <el-table-column v-for="c in ingestResult.columns" :key="c" :prop="c" :label="c" min-width="110" show-overflow-tooltip />
        </el-table>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Folder } from '@element-plus/icons-vue'
import { api, errMsg, type FilestoreRow } from '@/api'

const stores = ref<FilestoreRow[]>([])
const loading = ref(false)
const testingId = ref<number | null>(null)
const storeDlg = ref(false)
const saving = ref(false)
const storeForm = reactive<any>({ id: null, name: '', type: 'ftp', host: '', port: 21, username: '', password: '', base_path: '', status: 'NORMAL' })

const currentStore = ref<FilestoreRow | null>(null)
const curPath = ref('')
const files = ref<any[]>([])
const browsing = ref(false)
const tab = ref('browser')

const mkdirDlg = ref(false); const mkdirName = ref('')
const copyDlg = ref(false); const copyDst = ref(''); const copySrc = ref('')
const ingestDlg = ref(false); const ingestFile = ref<any>(null)
const ingestType = ref('csv'); const ingestTarget = ref(''); const ingesting = ref(false); const ingestResult = ref<any>(null)

const pathSegs = computed(() => curPath.value.split('/').filter(Boolean))
function pathUpto(i: number) { return pathSegs.value.slice(0, i + 1).join('/') }

async function load() {
  loading.value = true
  try { stores.value = await api.daStores() } catch (e) { ElMessage.error(errMsg(e)) } finally { loading.value = false }
}

function openStore(row?: FilestoreRow) {
  Object.assign(storeForm, { id: null, name: '', type: 'ftp', host: '', port: 21, username: '', password: '', base_path: '', status: 'NORMAL' })
  if (row) Object.assign(storeForm, { id: row.id, name: row.name, type: row.type, host: row.host, port: row.port, username: row.username, base_path: row.base_path, status: row.status, password: '' })
  storeDlg.value = true
}
async function saveStore() {
  if (!storeForm.name || !storeForm.type) return ElMessage.warning('请填名称与类型')
  saving.value = true
  try { await api.daSaveStore({ ...storeForm }); ElMessage.success('保存成功'); storeDlg.value = false; await load() }
  catch (e) { ElMessage.error(errMsg(e)) } finally { saving.value = false }
}
async function delStore(row: FilestoreRow) {
  await ElMessageBox.confirm(`确定删除存储源 ${row.name}？`, '提示', { type: 'warning' })
  try { await api.daDeleteStore(row.id); ElMessage.success('已删除'); await load() } catch (e) { ElMessage.error(errMsg(e)) }
}
async function testStore(row: FilestoreRow) {
  testingId.value = row.id
  try { const r: any = await api.daTestStore({ id: row.id }); r.ok ? ElMessage.success(r.msg) : ElMessage.error(r.msg) }
  catch (e: any) { ElMessage.error(errMsg(e)) } finally { testingId.value = null }
}

async function openBrowser(row: FilestoreRow) { currentStore.value = row; curPath.value = ''; await browse() }
function goRoot() { curPath.value = ''; browse() }
function cd(p: string) { curPath.value = p; browse() }
function onDblClick(row: any) { if (row.isDir) { curPath.value = row.path; browse() } }
async function browse() {
  if (!currentStore.value) return
  browsing.value = true
  try { files.value = await api.daBrowse(currentStore.value.id, curPath.value) } catch (e: any) { files.value = []; ElMessage.error(errMsg(e, '浏览失败')) }
  finally { browsing.value = false }
}

async function doMkdir() {
  if (!currentStore.value || !mkdirName.value) return
  try { await api.daMkdir(currentStore.value.id, joinPath(curPath.value, mkdirName.value)); ElMessage.success('已创建'); mkdirDlg.value = false; mkdirName.value = ''; await browse() }
  catch (e: any) { ElMessage.error(errMsg(e)) }
}
async function delFile(row: any) {
  if (!currentStore.value) return
  await ElMessageBox.confirm(`确定删除 ${row.name}？`, '提示', { type: 'warning' })
  try { await api.daFileDelete(currentStore.value.id, row.path); ElMessage.success('已删除'); await browse() } catch (e: any) { ElMessage.error(errMsg(e)) }
}
function openCopy(row: any) { copySrc.value = row.path; copyDst.value = ''; copyDlg.value = true }
async function doCopy() {
  if (!currentStore.value) return
  try { await api.daFileCopy(currentStore.value.id, copySrc.value, copyDst.value); ElMessage.success('已复制'); copyDlg.value = false; await browse() }
  catch (e: any) { ElMessage.error(errMsg(e)) }
}
async function onUpload(file: File) {
  if (!currentStore.value) return false
  const fd = new FormData()
  fd.append('storeId', String(currentStore.value.id))
  fd.append('path', joinPath(curPath.value, file.name))
  fd.append('file', file)
  try { await api.daFileUpload(fd); ElMessage.success('上传成功'); await browse() } catch (e: any) { ElMessage.error(errMsg(e)) }
  return false
}

function openIngest(row: any) { ingestFile.value = row; ingestType.value = 'csv'; ingestTarget.value = ''; ingestResult.value = null; ingestDlg.value = true }
async function doIngest() {
  if (!currentStore.value || !ingestFile.value) return
  ingesting.value = true
  try {
    const r: any = await api.daIngestFile({ store_id: currentStore.value.id, path: ingestFile.value.path, file_type: ingestType.value, target_table: ingestTarget.value })
    ingestResult.value = r
    ElMessage.success(`已接入 ${r.targetTable}（${r.rowsWritten} 行）`)
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { ingesting.value = false }
}

function joinPath(a: string, b: string) { return a ? a + '/' + b : b }

onMounted(load)
</script>

<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.muted { color: var(--tech-text-muted); font-size: 12px; }
.breadcrumb { margin-bottom: 8px; font-size: 13px; color: var(--tech-text-muted); }
</style>
