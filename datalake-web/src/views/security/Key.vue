<template>
  <div class="dl-card">
    <div class="card-title"><span>密钥管理</span><span class="role-tag">系统管理员</span></div>
    <el-button size="small" type="primary" @click="open()" style="margin-bottom:10px"><el-icon><Plus /></el-icon> 新增密钥</el-button>
    <el-table :data="rows" size="small" stripe border v-loading="loading">
      <el-table-column prop="name" label="名称" min-width="140" />
      <el-table-column prop="algo" label="算法" width="100" />
      <el-table-column label="密钥值" min-width="160"><template #default="{ row }"><span class="muted">••••••（加密存储，不可见）</span></template></el-table-column>
      <el-table-column prop="status" label="状态" width="80"><template #default="{ row }"><el-tag size="small" :type="row.status === 'NORMAL' ? 'success' : 'warning'">{{ row.status }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="140"><template #default="{ row }"><el-button link size="small" type="primary" @click="open(row)">编辑</el-button><el-button link size="small" type="danger" @click="del(row)">删除</el-button></template></el-table-column>
    </el-table>
    <el-dialog v-model="dlg" :title="form.id ? '编辑密钥' : '新增密钥'" width="460px">
      <el-form :model="form" label-width="70px" size="small">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="算法"><el-select v-model="form.algo" style="width:100%"><el-option v-for="a in ['AES','AES-GCM','RSA','HMAC']" :key="a" :label="a" :value="a" /></el-select></el-form-item>
        <el-form-item label="密钥值"><el-input v-model="form.key_value" type="password" show-password :placeholder="form.id ? '留空则不修改' : '输入密钥'" /></el-form-item>
        <el-form-item label="状态"><el-radio-group v-model="form.status"><el-radio value="NORMAL">启用</el-radio><el-radio value="DISABLED">停用</el-radio></el-radio-group></el-form-item>
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
const form = reactive<any>({ id: null, name: '', algo: 'AES', key_value: '', status: 'NORMAL' })
async function load() { loading.value = true; try { rows.value = await api.secKeys() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
function open(row?: any) { Object.assign(form, { id: null, name: '', algo: 'AES', key_value: '', status: 'NORMAL' }, row ? { id: row.id, name: row.name, algo: row.algo, status: row.status, key_value: '' } : {}); dlg.value = true }
async function save() { if (!form.name) return ElMessage.warning('填名称'); if (!form.id && !form.key_value) return ElMessage.warning('填密钥值'); try { await api.secSaveKey({ ...form }); ElMessage.success('保存成功'); dlg.value = false; await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function del(row: any) { await ElMessageBox.confirm(`删除密钥 ${row.name}？`, '提示', { type: 'warning' }); try { await api.secDeleteKey(row.id); ElMessage.success('已删除'); await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
onMounted(load)
</script>
<style scoped>.card-title{display:flex;align-items:center;justify-content:space-between;font-weight:600;margin-bottom:12px}.role-tag{font-size:12px;color:var(--tech-text-muted);border:1px solid var(--tech-panel-border);padding:2px 8px;border-radius:4px}.muted{color:var(--tech-text-muted);font-size:12px}</style>
