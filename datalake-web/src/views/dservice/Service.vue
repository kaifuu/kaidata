<template>
  <div class="dl-card">
    <div class="card-title"><span>服务管理</span><span class="role-tag">系统管理员</span></div>
    <el-button type="primary" size="small" @click="open()" style="margin-bottom:10px"><el-icon><Plus /></el-icon> 新建服务</el-button>
    <el-table :data="rows" size="small" stripe border v-loading="loading">
      <el-table-column prop="code" label="编码" width="140" />
      <el-table-column prop="name" label="名称" min-width="120" />
      <el-table-column label="鉴权" width="70"><template #default="{ row }"><el-tag size="small" :type="row.auth ? '' : 'warning'">{{ row.auth ? '登录' : '公开' }}</el-tag></template></el-table-column>
      <el-table-column prop="status" label="状态" width="90"><template #default="{ row }"><el-tag size="small" :type="row.status === 'PUBLISHED' ? 'success' : 'info'">{{ row.status }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="290"><template #default="{ row }">
        <el-button v-if="row.status !== 'PUBLISHED'" link size="small" type="success" @click="pub(row)">发布</el-button>
        <el-button v-else link size="small" type="warning" @click="unpub(row)">下线</el-button>
        <el-button link size="small" type="primary" @click="openTest(row)">调用</el-button>
        <el-button link size="small" type="primary" @click="open(row)">编辑</el-button>
        <el-button link size="small" type="danger" @click="del(row)">删除</el-button>
      </template></el-table-column>
    </el-table>

    <el-dialog v-model="dlg" :title="form.id ? '编辑服务' : '新建服务'" width="640px">
      <el-form :model="form" label-width="90px" size="small">
        <el-form-item label="编码"><el-input v-model="form.code" placeholder="唯一编码，如 batch_list" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="数据源"><el-select v-model="form.datasource_id" style="width:100%"><el-option v-for="d in dsList" :key="d.id" :label="`${d.name}(${d.type})`" :value="d.id" /></el-select></el-form-item>
        <el-form-item label="SQL"><el-input v-model="form.sql_text" type="textarea" :rows="4" placeholder="SELECT * FROM ods.ods_batch WHERE status='{status}' LIMIT 10" style="font-family:monospace" /></el-form-item>
        <el-form-item label="参数定义"><el-input v-model="form.params" placeholder='["status"]（SQL 用 {status} 引用，调用时传 ?status=DONE）' /></el-form-item>
        <el-form-item label="方法"><el-radio-group v-model="form.method"><el-radio value="GET">GET</el-radio><el-radio value="POST">POST</el-radio></el-radio-group></el-form-item>
        <el-form-item label="需登录"><el-switch v-model="form.auth" /><span class="muted" style="margin-left:8px">关闭则公开访问 /open/{code}（免鉴权）</span></el-form-item>
      </el-form>
      <template #footer><el-button @click="dlg = false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="testDlg" :title="`调用测试 - ${cur?.code || ''}`" width="720px">
      <div class="muted" style="margin-bottom:6px">参数（key=value 每行一个）：</div>
      <el-input v-model="testParams" type="textarea" :rows="3" placeholder="status=DONE" />
      <el-button size="small" type="success" :loading="testing" @click="runTest" style="margin-top:8px">执行</el-button>
      <div v-if="testResult" style="margin-top:10px">
        <span class="muted">{{ testResult.status }} · {{ testResult.rowsRead }} 行 · {{ testResult.cost_ms }}ms · {{ testResult.msg || 'OK' }}</span>
        <el-table :data="testResult.rows" size="small" border max-height="280" v-if="testResult.rows && testResult.rows.length" style="margin-top:6px">
          <el-table-column v-for="c in testResult.columns" :key="c" :prop="c" :label="c" min-width="120" show-overflow-tooltip />
        </el-table>
      </div>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { api, errMsg, http } from '@/api'
const rows = ref<any[]>([]); const dsList = ref<any[]>([]); const loading = ref(false); const dlg = ref(false)
const form = reactive<any>({ id: null, code: '', name: '', sql_text: '', datasource_id: null, method: 'GET', params: '', path: '', auth: true, status: 'DRAFT' })
const testDlg = ref(false); const cur = ref<any>(null); const testParams = ref(''); const testResult = ref<any>(null); const testing = ref(false)
async function load() { loading.value = true; try { rows.value = await api.dsServices(); dsList.value = await api.daSources() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
function open(row?: any) { Object.assign(form, { id: null, code: '', name: '', sql_text: '', datasource_id: dsList.value[0]?.id || null, method: 'GET', params: '', path: '', auth: true, status: 'DRAFT' }, row || {}); dlg.value = true }
async function save() { if (!form.code || !form.sql_text || !form.datasource_id) return ElMessage.warning('填编码/SQL/数据源'); try { await api.dsSaveService({ ...form }); ElMessage.success('保存成功'); dlg.value = false; await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function del(row: any) { await ElMessageBox.confirm(`删除服务 ${row.code}？`, '提示', { type: 'warning' }); try { await api.dsDeleteService(row.id); ElMessage.success('已删除'); await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function pub(row: any) { try { await http.post('/data-service/publish', null, { params: { id: row.id } }); ElMessage.success('已发布'); await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function unpub(row: any) { try { await http.post('/data-service/unpublish', null, { params: { id: row.id } }); ElMessage.success('已下线'); await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
function openTest(row: any) { cur.value = row; testParams.value = ''; testResult.value = null; testDlg.value = true }
async function runTest() { const params: Record<string, any> = {}; testParams.value.split('\n').forEach((line) => { const i = line.indexOf('='); if (i > 0) params[line.slice(0, i).trim()] = line.slice(i + 1).trim() }); testing.value = true; try { testResult.value = await api.dsInvoke(cur.value.code, params) } catch (e:any) { ElMessage.error(errMsg(e)) } finally { testing.value = false } }
onMounted(load)
</script>
<style scoped>.card-title{display:flex;align-items:center;justify-content:space-between;font-weight:600;margin-bottom:12px}.role-tag{font-size:12px;color:var(--tech-text-muted);border:1px solid var(--tech-panel-border);padding:2px 8px;border-radius:4px}.muted{color:var(--tech-text-muted);font-size:12px}</style>
